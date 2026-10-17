package app.houselights.providers.jellyfin

import android.util.Log
import app.houselights.core.Catalog
import app.houselights.core.HomeSection
import app.houselights.core.Http
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.Quality
import app.houselights.core.WatchStatus
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.Page
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
import app.houselights.core.launch
import app.houselights.core.ProviderCapabilities
import app.houselights.core.SectionType
import app.houselights.core.SortOption
import app.houselights.core.SourceConfig
import app.houselights.core.Typeset
import app.houselights.core.suspendRunCatching
import app.houselights.player.PathResolver
import app.houselights.player.Redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

class JellyfinProvider(
    config: SourceConfig.Jellyfin,
    deviceId: String,
    http: OkHttpClient = Http.client,
    /** The waits before each new try of a report that failed in passing (see [retried]). */
    private val retryDelaysMs: List<Long> = REPORT_RETRY_MS,
    /** Where the server's answers are made into titles: typesetting their text takes regexes, off the main thread. */
    private val mapping: CoroutineContext = Dispatchers.Default,
) : MediaProvider {
    private val client = JellyfinClient(config.serverUrl, deviceId, config.accessToken, http)
    private val api = client.api
    private val mapper = JellyfinMapper(client)
    private val userId = config.userId

    override val capabilities = ProviderCapabilities(
        markPlayed = true, favorites = true, similar = true, pathMapping = true, people = true, crew = true, versions = true,
        personFilter = true, search = true,
    )

    override val catalog: Catalog by lazy { JellyfinCatalog(api, mapper, userId, this, mapping) }

    /**
     * The libraries [libraries] read last, until [home] takes them: home loads the libraries, then
     * its rows, which need them too. One request to the server serves both.
     */
    private val librariesRead = AtomicReference<List<Library>?>(null)

    override suspend fun libraries(): List<Library> = askLibraries().also { librariesRead.set(it) }

    private suspend fun askLibraries(): List<Library> =
        api.views(userId).items
            .filter { it.collectionType in SUPPORTED_COLLECTIONS }
            .map { Library(it.id, libraryKind(it.collectionType), Typeset.text(it.name)) }

    /**
     * Home's rows, each asked apart: one that fails is left out, and logged. When every one fails, home
     * fails with the first failure, rather than showing a server with nothing on it. The libraries
     * just read for home are not asked for again.
     */
    override suspend fun home(): List<HomeSection> = coroutineScope {
        val resume = async { row("Resume") { api.resume(userId, 20, ROW_FIELDS).items } }
        val nextUp = async { row("Next up") { api.nextUp(userId, 20, ROW_FIELDS).items } }
        val libraries = librariesRead.getAndSet(null) ?: askLibraries()
        val latest = libraries.map { lib -> lib to async { row("Latest of ${lib.title}") { api.latest(userId, lib.id, 24, ROW_FIELDS) } } }
        val rows = listOf(resume.await(), nextUp.await()) + latest.map { it.second.await() }
        rows.firstNotNullOfOrNull { it.exceptionOrNull() }?.takeIf { rows.all(Result<*>::isFailure) }?.let { throw it }
        withContext(mapping) {
            buildList {
                resume.await().getOrNull()?.takeIf { it.isNotEmpty() }
                    ?.let { add(HomeSection(SectionType.CONTINUE, it.map(::toMedia), landscape = true)) }
                nextUp.await().getOrNull()?.takeIf { it.isNotEmpty() }
                    ?.let { add(HomeSection(SectionType.NEXT_UP, it.map(::toMedia), landscape = true)) }
                for ((lib, items) in latest) {
                    val films = items.await().getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                    val shown = films.map(::toMedia)
                    add(HomeSection(SectionType.LATEST, shown, landscape = false, libraryName = lib.title, libraryId = lib.id))
                }
            }
        }
    }

    /** What a row of a screen asks for, its failure logged: the screen goes on without it. */
    private suspend fun <T> row(what: String, block: suspend () -> T): Result<T> =
        suspendRunCatching { block() }.onFailure { Log.w(TAG, "$what failed", it) }

    override suspend fun libraryPage(library: Library, sort: SortOption, filter: LibraryFilter, start: Int, limit: Int): Page {
        val types = when (library.kind) {
            LibraryKind.MOVIES -> "Movie"
            LibraryKind.SHOWS -> "Series"
            LibraryKind.COLLECTIONS -> "BoxSet"
            LibraryKind.MIXED -> null
        }
        val (sortBy, order) = when (sort) {
            SortOption.TITLE -> "SortName" to "Ascending"
            SortOption.ADDED -> "DateCreated,SortName" to "Descending"
            SortOption.YEAR -> "ProductionYear,PremiereDate,SortName" to "Descending"
            SortOption.RATING -> "CommunityRating,SortName" to "Descending"
        }
        val result = api.items(
            userId = userId,
            parentId = library.id,
            includeItemTypes = types,
            recursive = types != null,
            sortBy = sortBy,
            sortOrder = order,
            startIndex = start,
            limit = limit,
            fields = GRID_FIELDS,
            filters = when (filter.status) {
                WatchStatus.ALL -> null
                WatchStatus.UNWATCHED -> "IsUnplayed"
                WatchStatus.IN_PROGRESS -> "IsResumable"
                WatchStatus.WATCHED -> "IsPlayed"
                WatchStatus.FAVORITES -> "IsFavorite"
            },
            genres = filter.genre,
            years = filter.decade?.let { d -> (d..d + 9).joinToString(",") },
            is4K = if (filter.quality == Quality.UHD_4K) true else null,
            personIds = filter.person,
        )
        return Page(titles(result.items), result.totalRecordCount)
    }

    override suspend fun libraryFacets(library: Library): LibraryFacets = LibraryFacets(
        genres = row("Genres") { api.genres(userId, library.id).items.mapNotNull { it.name } }.getOrDefault(emptyList()),
        // Jellyfin has no cheap decade listing; 4K is the only quality its API filters on.
        qualities = listOf(Quality.UHD_4K),
    )

    override suspend fun item(id: String): MediaItem = toMedia(api.item(id, userId))

    override suspend fun seasons(series: MediaItem): List<MediaItem> =
        titles(api.seasons(series.id, userId, ROW_FIELDS).items)

    override suspend fun episodes(series: MediaItem, season: MediaItem): List<MediaItem> =
        titles(api.episodes(series.id, userId, season.id, EPISODE_FIELDS).items)

    override suspend fun nextUp(series: MediaItem): MediaItem? =
        if (series.kind == MediaKind.COLLECTION) super.nextUp(series)
        else row("Next up of ${series.id}") { api.nextUp(userId, 1, ROW_FIELDS, series.id).items.firstOrNull()?.let(::toMedia) }.getOrNull()

    /** A collection (box set) holds its films directly. */
    override suspend fun collectionItems(collection: MediaItem): List<MediaItem> = row("Films of ${collection.id}") {
        val films = api.items(
            userId = userId,
            parentId = collection.id,
            includeItemTypes = "Movie",
            recursive = true,
            sortBy = "ProductionYear,PremiereDate,SortName",
            sortOrder = "Ascending",
            startIndex = 0,
            limit = COLLECTION_LIMIT,
            fields = ROW_FIELDS,
        ).items
        titles(films)
    }.getOrDefault(emptyList())

    override suspend fun similar(item: MediaItem): List<MediaItem> =
        if (item.kind == MediaKind.EPISODE) emptyList()
        else row("Similar to ${item.id}") { titles(api.similar(item.id, userId, 16, ROW_FIELDS).items) }.getOrDefault(emptyList())

    override suspend fun setPlayed(item: MediaItem, played: Boolean) {
        if (played) api.markPlayed(item.id, userId) else api.markUnplayed(item.id, userId)
    }

    override suspend fun setFavorite(item: MediaItem, favorite: Boolean) {
        if (favorite) api.addFavorite(item.id, userId) else api.removeFavorite(item.id, userId)
    }

    override suspend fun samplePath(): String? = row("Sample path") {
        api.items(userId, null, "Movie,Episode", true, "Random", "Ascending", 0, 1, "Path").items.firstOrNull()?.path
    }.getOrNull()

    override suspend fun playback(item: MediaItem, resume: Boolean, options: PlaybackOptions): PlaybackPlan =
        play(item.id, sourceId = null, resume, options)

    /** Plays the media source [sourceId] of the item [itemId], one of its versions; its first when null or gone. */
    internal suspend fun play(itemId: String, sourceId: String?, resume: Boolean, options: PlaybackOptions): PlaybackPlan {
        // List entries lack MediaSources: reload the full item.
        val full = api.item(itemId, userId)
        val source = full.mediaSources.firstOrNull { it.id == sourceId } ?: full.mediaSources.firstOrNull()
        val container = (source?.container ?: full.container)?.substringBefore(',')
        val networkUri = if (options.forceHttp) null else PathResolver.resolve(source?.path ?: full.path, options.pathMappings)
        val uri = networkUri ?: client.streamUrl(full.id, source?.id, container)
        val media = toMedia(full)
        val startMs = if (resume) media.user.positionMs else 0L
        Log.i(TAG, "Play ${full.name} -> ${Redact.uri(uri)} (start ${startMs} ms)")

        return media.launch(
            uri, container, startMs,
            reporter = Reporter(full.id, source?.id),
            resumeKey = listOfNotNull(full.id, source?.id).joinToString(RESUME_KEY_SEPARATOR),
            disc = source?.videoType in DISC_VIDEO_TYPES || container.equals(DISC_IMAGE, ignoreCase = true),
        )
    }

    override fun reporter(resumeKey: String): PlaybackReporter? =
        resumeKey.split(RESUME_KEY_SEPARATOR).let { parts -> Reporter(parts[0], parts.getOrNull(1)) }

    /** Counts playbacks started through this server: a report of an earlier one stops trying once a new one started. */
    private val sessions = AtomicLong()

    /** Keeps reports in order across playbacks: a Playing never goes before the last playback's Stopped, nor a Stopped twice. */
    private val reports = Mutex()

    /** The last playback's Stopped while it waits to be tried again (see [retried]): the next Playing sends it first. Under [reports]. */
    private var pendingStop: (suspend () -> Unit)? = null

    private inner class Reporter(private val itemId: String, private val mediaSourceId: String?) : PlaybackReporter {
        /** The playback this reporter speaks for: the latest when it was made, or the one it started. */
        private var session = sessions.get()

        private fun report(positionMs: Long, paused: Boolean = false) = PlaybackReport(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            positionTicks = positionMs * TICKS_PER_MS,
            isPaused = paused,
        )

        override suspend fun started(positionMs: Long) {
            reports.withLock {
                session = sessions.incrementAndGet()
                // The last playback's Stopped, still to be tried again: once more now, before this Playing, then given up.
                pendingStop?.let { stop ->
                    pendingStop = null
                    suspendRunCatching { stop() }.onFailure { Log.w(TAG, "The last playback's Stopped failed again: given up", it) }
                }
            }
            api.reportStart(report(positionMs))
        }

        override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) = api.reportProgress(report(positionMs, paused))

        // The server marks the item as played past 90 %, from this report alone: it is tried again.
        override suspend fun stopped(positionMs: Long, durationMs: Long?) = retried(session) { api.reportStopped(report(positionMs)) }
    }

    /**
     * [block], tried again after each of [retryDelaysMs] while it fails in a way that says the server
     * did not take it: no connection (refused or timed out, no route, a name unknown) or the server
     * failing (5xx, "database is locked" during a scan). Anything else is not tried again: an answer
     * that timed out may come from a request the server did take, and a second Stopped would count the
     * film twice. While it waits, it is [pendingStop]: a new playback sends it once more before its own
     * Playing, and the tries end there. A late Stopped would land after the next episode's Playing,
     * and a dropped one would leave the episode unwatched. Each try holds [reports].
     */
    private suspend fun retried(session: Long, block: suspend () -> Unit) {
        var tries = 0
        while (true) {
            val failure = reports.withLock {
                // A new playback took it over, and sent it before its Playing.
                if (tries > 0 && pendingStop !== block) return
                pendingStop = null
                val failure = failureToRetry(block) ?: return
                if (tries == retryDelaysMs.size) throw failure
                if (sessions.get() != session) {
                    Log.i(TAG, "Another playback started before this report: it is not tried again")
                    return
                }
                pendingStop = block
                failure
            }
            val wait = retryDelaysMs[tries++]
            Log.w(TAG, "Report failed, trying again in $wait ms", failure)
            delay(wait)
        }
    }

    /** Null when [block] succeeds; its failure when it is one to try again (see [retried]). Any other is thrown. */
    private suspend fun failureToRetry(block: suspend () -> Unit): Exception? = try {
        block()
        null
    } catch (e: IOException) {
        if (e.neverReachedTheServer()) e else throw e
    } catch (e: HttpException) {
        if (e.code() >= HTTP_SERVER_ERROR) e else throw e
    }

    /** The request never got to the server: it can be sent again without the server taking it twice. */
    private fun IOException.neverReachedTheServer() =
        this is ConnectException || this is NoRouteToHostException || this is UnknownHostException || isConnectTimeout()

    /**
     * No connection within the timeout (a server or a NAS asleep, a Wi-Fi joining again): Android says
     * "failed to connect to … after 10000ms", a JVM "Connect timed out". An answer that timed out says
     * "timeout" or "Read timed out": the server may have taken the request.
     */
    private fun IOException.isConnectTimeout() = this is SocketTimeoutException &&
        message.orEmpty().let { it.startsWith("failed to connect", ignoreCase = true) || it.equals("connect timed out", ignoreCase = true) }

    private fun toMedia(dto: BaseItem): MediaItem = mapper.toMedia(dto)

    /** The titles of a list the server answered, made in [mapping]. */
    private suspend fun titles(items: List<BaseItem>): List<MediaItem> = withContext(mapping) { items.map(::toMedia) }

    companion object {
        private const val TAG = "Jellyfin"
        private const val HTTP_SERVER_ERROR = 500

        /** Stopped is tried three times more, over some twenty seconds. */
        private val REPORT_RETRY_MS = listOf(2_000L, 5_000L, 15_000L)
        private const val RESUME_KEY_SEPARATOR = "|"

        /** A media source's video types that are discs: an image, a Blu-ray's folder, a DVD's. */
        private val DISC_VIDEO_TYPES = setOf("Iso", "BluRay", "Dvd")
        private const val DISC_IMAGE = "iso"

        private const val COLLECTION_LIMIT = 200
        private val SUPPORTED_COLLECTIONS = setOf("movies", "tvshows", "boxsets", "homevideos", "musicvideos", null)
        private const val ROW_FIELDS = "Overview,Genres,ProductionYear,OfficialRating,CommunityRating,DateCreated"

        /** What a wall shows of a title: its year and facts, never its overview nor its genres. */
        private const val GRID_FIELDS = "ProductionYear,OfficialRating,CommunityRating,DateCreated"
        private const val EPISODE_FIELDS = "Overview"

        private fun libraryKind(collectionType: String?) = when (collectionType) {
            "movies" -> LibraryKind.MOVIES
            "tvshows" -> LibraryKind.SHOWS
            "boxsets" -> LibraryKind.COLLECTIONS
            else -> LibraryKind.MIXED
        }

    }
}

