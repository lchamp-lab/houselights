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
    /** Delays before each retry of a report that failed transiently (see [retried]). */
    private val retryDelaysMs: List<Long> = REPORT_RETRY_MS,
    /** Context for mapping responses to titles; typesetting runs regexes, so keep it off the main thread. */
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

    /** Libraries from the last [libraries] call, reused once by [home], which needs them right after. */
    private val librariesRead = AtomicReference<List<Library>?>(null)

    override suspend fun libraries(): List<Library> = askLibraries().also { librariesRead.set(it) }

    private suspend fun askLibraries(): List<Library> =
        api.views(userId).items
            .filter { it.collectionType in SUPPORTED_COLLECTIONS }
            .map { Library(it.id, libraryKind(it.collectionType), Typeset.text(it.name)) }

    /** Rows fetched in parallel; failed rows are left out, but if all fail the first failure is thrown. */
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

    /** A show or season by its own id: the server marks each of its episodes, clearing their resume points. */
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

    /** Plays media source [sourceId] of item [itemId]; falls back to the first source when null or missing. */
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

    /** Playback session counter: retries of an older session's report stop once a new session starts. */
    private val sessions = AtomicLong()

    /** Orders reports across playbacks: no Playing before the previous Stopped, and no Stopped sent twice. */
    private val reports = Mutex()

    /** Previous Stopped awaiting retry ([retried]), sent before the next Playing. Guarded by [reports]. */
    private var pendingStop: (suspend () -> Unit)? = null

    private inner class Reporter(private val itemId: String, private val mediaSourceId: String?) : PlaybackReporter {
        /** Session this reporter belongs to: the latest at creation, or the one it started. */
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
                // A pending Stopped from the previous playback gets one last try before this Playing.
                pendingStop?.let { stop ->
                    pendingStop = null
                    suspendRunCatching { stop() }.onFailure { Log.w(TAG, "The last playback's Stopped failed again: given up", it) }
                }
            }
            api.reportStart(report(positionMs))
        }

        override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) = api.reportProgress(report(positionMs, paused))

        // The server marks the item played past 90 % from this report alone, so it is retried.
        override suspend fun stopped(positionMs: Long, durationMs: Long?) = retried(session) { api.reportStopped(report(positionMs)) }
    }

    /**
     * Retries [block] after each of [retryDelaysMs] only when the server cannot have processed it (no connection,
     * or 5xx such as "database is locked" during a scan): a second Stopped would count the play twice. While
     * waiting it is [pendingStop], so a new playback sends it before its own Playing and retries stop there.
     */
    private suspend fun retried(session: Long, block: suspend () -> Unit) {
        var tries = 0
        while (true) {
            val failure = reports.withLock {
                // A new playback already sent it before its Playing.
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

    /** Null on success; the failure when it is retryable (see [retried]); any other failure is thrown. */
    private suspend fun failureToRetry(block: suspend () -> Unit): Exception? = try {
        block()
        null
    } catch (e: IOException) {
        if (e.neverReachedTheServer()) e else throw e
    } catch (e: HttpException) {
        if (e.code() >= HTTP_SERVER_ERROR) e else throw e
    }

    /** The request never reached the server, so resending it cannot apply it twice. */
    private fun IOException.neverReachedTheServer() =
        this is ConnectException || this is NoRouteToHostException || this is UnknownHostException || isConnectTimeout()

    /**
     * Connect timeouts only: Android says "failed to connect to … after 10000ms", a JVM "Connect timed out". Read
     * timeouts are excluded, as the server may have processed the request.
     */
    private fun IOException.isConnectTimeout() = this is SocketTimeoutException &&
        message.orEmpty().let { it.startsWith("failed to connect", ignoreCase = true) || it.equals("connect timed out", ignoreCase = true) }

    private fun toMedia(dto: BaseItem): MediaItem = mapper.toMedia(dto)

    private suspend fun titles(items: List<BaseItem>): List<MediaItem> = withContext(mapping) { items.map(::toMedia) }

    companion object {
        private const val TAG = "Jellyfin"
        private const val HTTP_SERVER_ERROR = 500

        /** Three retries of Stopped, over about twenty seconds. */
        private val REPORT_RETRY_MS = listOf(2_000L, 5_000L, 15_000L)
        private const val RESUME_KEY_SEPARATOR = "|"

        /** Media source video types that are discs: ISO image, Blu-ray folder, DVD folder. */
        private val DISC_VIDEO_TYPES = setOf("Iso", "BluRay", "Dvd")
        private const val DISC_IMAGE = "iso"

        private const val COLLECTION_LIMIT = 200
        private val SUPPORTED_COLLECTIONS = setOf("movies", "tvshows", "boxsets", "homevideos", "musicvideos", null)
        private const val ROW_FIELDS = "Overview,Genres,ProductionYear,OfficialRating,CommunityRating,DateCreated"

        /** Fields a wall needs: year and ratings, without overview or genres. */
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

