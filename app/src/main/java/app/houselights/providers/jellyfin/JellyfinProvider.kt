package app.houselights.providers.jellyfin

import android.util.Log
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

class JellyfinProvider(
    config: SourceConfig.Jellyfin,
    deviceId: String,
    http: OkHttpClient = Http.client,
    /** The waits before each new try of a report that failed in passing (see [retried]). */
    private val retryDelaysMs: List<Long> = REPORT_RETRY_MS,
) : MediaProvider {
    private val client = JellyfinClient(config.serverUrl, deviceId, config.accessToken, http)
    private val api = client.api
    private val mapper = JellyfinMapper(client)
    private val userId = config.userId

    override val capabilities = ProviderCapabilities(markPlayed = true, favorites = true, similar = true, pathMapping = true)

    override suspend fun libraries(): List<Library> =
        api.views(userId).items
            .filter { it.collectionType in SUPPORTED_COLLECTIONS }
            .map { Library(it.id, libraryKind(it.collectionType), Typeset.text(it.name)) }

    /**
     * Home's rows, each asked apart: one that fails is left out, and logged. When every one fails, home
     * fails with the first failure, rather than showing a server with nothing on it.
     */
    override suspend fun home(): List<HomeSection> = coroutineScope {
        val resume = async { row("Resume") { api.resume(userId, 20, ROW_FIELDS).items } }
        val nextUp = async { row("Next up") { api.nextUp(userId, 20, ROW_FIELDS).items } }
        val libraries = libraries()
        val latest = libraries.map { lib -> lib to async { row("Latest of ${lib.title}") { api.latest(userId, lib.id, 24, ROW_FIELDS) } } }
        val rows = listOf(resume.await(), nextUp.await()) + latest.map { it.second.await() }
        rows.firstNotNullOfOrNull { it.exceptionOrNull() }?.takeIf { rows.all(Result<*>::isFailure) }?.let { throw it }
        buildList {
            resume.await().getOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { add(HomeSection(SectionType.CONTINUE, it.map(::toMedia), landscape = true)) }
            nextUp.await().getOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { add(HomeSection(SectionType.NEXT_UP, it.map(::toMedia), landscape = true)) }
            for ((lib, items) in latest) {
                val films = items.await().getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                add(HomeSection(SectionType.LATEST, films.map(::toMedia), landscape = false, libraryName = lib.title, libraryId = lib.id))
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
            fields = ROW_FIELDS,
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
        )
        return Page(result.items.map(::toMedia), result.totalRecordCount)
    }

    override suspend fun libraryFacets(library: Library): LibraryFacets = LibraryFacets(
        genres = row("Genres") { api.genres(userId, library.id).items.mapNotNull { it.name } }.getOrDefault(emptyList()),
        // Jellyfin has no cheap decade listing; 4K is the only quality its API filters on.
        qualities = listOf(Quality.UHD_4K),
    )

    override suspend fun item(id: String): MediaItem = toMedia(api.item(id, userId))

    override suspend fun seasons(series: MediaItem): List<MediaItem> =
        api.seasons(series.id, userId, ROW_FIELDS).items.map(::toMedia)

    override suspend fun episodes(series: MediaItem, season: MediaItem): List<MediaItem> =
        api.episodes(series.id, userId, season.id, EPISODE_FIELDS).items.map(::toMedia)

    override suspend fun nextUp(series: MediaItem): MediaItem? =
        if (series.kind == MediaKind.COLLECTION) super.nextUp(series)
        else row("Next up of ${series.id}") { api.nextUp(userId, 1, ROW_FIELDS, series.id).items.firstOrNull()?.let(::toMedia) }.getOrNull()

    /** A collection (box set) holds its films directly. */
    override suspend fun collectionItems(collection: MediaItem): List<MediaItem> = row("Films of ${collection.id}") {
        api.items(
            userId = userId,
            parentId = collection.id,
            includeItemTypes = "Movie",
            recursive = true,
            sortBy = "ProductionYear,PremiereDate,SortName",
            sortOrder = "Ascending",
            startIndex = 0,
            limit = COLLECTION_LIMIT,
            fields = ROW_FIELDS,
        ).items.map(::toMedia)
    }.getOrDefault(emptyList())

    override suspend fun similar(item: MediaItem): List<MediaItem> =
        if (item.kind == MediaKind.EPISODE) emptyList()
        else row("Similar to ${item.id}") { api.similar(item.id, userId, 16, ROW_FIELDS).items.map(::toMedia) }.getOrDefault(emptyList())

    override suspend fun setPlayed(item: MediaItem, played: Boolean) {
        if (played) api.markPlayed(item.id, userId) else api.markUnplayed(item.id, userId)
    }

    override suspend fun setFavorite(item: MediaItem, favorite: Boolean) {
        if (favorite) api.addFavorite(item.id, userId) else api.removeFavorite(item.id, userId)
    }

    override suspend fun samplePath(): String? = row("Sample path") {
        api.items(userId, null, "Movie,Episode", true, "Random", "Ascending", 0, 1, "Path").items.firstOrNull()?.path
    }.getOrNull()

    override suspend fun playback(item: MediaItem, resume: Boolean, options: PlaybackOptions): PlaybackPlan {
        // List entries lack MediaSources: reload the full item.
        val full = api.item(item.id, userId)
        val source = full.mediaSources.firstOrNull()
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
        )
    }

    override fun reporter(resumeKey: String): PlaybackReporter? =
        resumeKey.split(RESUME_KEY_SEPARATOR).let { parts -> Reporter(parts[0], parts.getOrNull(1)) }

    /** Counts playbacks started through this server: a report of an earlier one stops trying once a new one started. */
    private val sessions = AtomicLong()

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
            session = sessions.incrementAndGet()
            api.reportStart(report(positionMs))
        }

        override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) = api.reportProgress(report(positionMs, paused))

        // The server marks the item as played past 90 %, from this report alone: it is tried again.
        override suspend fun stopped(positionMs: Long, durationMs: Long?) = retried(session) { api.reportStopped(report(positionMs)) }
    }

    /**
     * [block], tried again after each of [retryDelaysMs] while it fails in a way that says the server
     * did not take it: no connection (refused, no route, a name unknown) or the server failing (5xx,
     * "database is locked" during a scan). Anything else is not tried again: an answer that timed out
     * may come from a request the server did take, and a second Stopped would count the film twice. A
     * new playback ([session] no longer the latest) ends the tries: a late Stopped would land after the
     * next film's Playing, or mark again as watched what the user has just unmarked.
     */
    private suspend fun retried(session: Long, block: suspend () -> Unit) {
        for (wait in retryDelaysMs) {
            try {
                return block()
            } catch (e: IOException) {
                if (!e.neverReachedTheServer()) throw e
                Log.w(TAG, "Report failed, trying again in $wait ms", e)
            } catch (e: HttpException) {
                if (e.code() < HTTP_SERVER_ERROR) throw e
                Log.w(TAG, "Report failed, trying again in $wait ms", e)
            }
            delay(wait)
            if (sessions.get() != session) {
                Log.i(TAG, "Another playback started: the report of the last one is not tried again")
                return
            }
        }
        block()
    }

    /** The request never got to the server: it can be sent again without the server taking it twice. */
    private fun IOException.neverReachedTheServer() =
        this is ConnectException || this is NoRouteToHostException || this is UnknownHostException

    private fun toMedia(dto: BaseItem): MediaItem = mapper.toMedia(dto)

    companion object {
        private const val TAG = "Jellyfin"
        private const val HTTP_SERVER_ERROR = 500

        /** Stopped is tried three times more, over some twenty seconds. */
        private val REPORT_RETRY_MS = listOf(2_000L, 5_000L, 15_000L)
        private const val RESUME_KEY_SEPARATOR = "|"
        private const val COLLECTION_LIMIT = 200
        private val SUPPORTED_COLLECTIONS = setOf("movies", "tvshows", "boxsets", "homevideos", "musicvideos", null)
        private const val ROW_FIELDS = "Overview,Genres,ProductionYear,OfficialRating,CommunityRating,DateCreated"
        private const val EPISODE_FIELDS = "Overview"

        private fun libraryKind(collectionType: String?) = when (collectionType) {
            "movies" -> LibraryKind.MOVIES
            "tvshows" -> LibraryKind.SHOWS
            "boxsets" -> LibraryKind.COLLECTIONS
            else -> LibraryKind.MIXED
        }

    }
}

