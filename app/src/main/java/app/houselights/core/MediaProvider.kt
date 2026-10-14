package app.houselights.core

/** Server path → network URI rewrite rule (e.g. /media/movies → smb://nas/movies). */
@kotlinx.serialization.Serializable
data class PathMapping(val from: String, val to: String)

/** An NFO file: where it is and what it says. */
data class NfoDocument(val path: String, val text: String) {
    val info: NfoInfo? by lazy { NfoParser.parse(text) }
}

data class PlaybackOptions(val pathMappings: List<PathMapping>, val forceHttp: Boolean)

/** Receives playback progress so the backend can keep resume points and watched state. */
interface PlaybackReporter {
    suspend fun started(positionMs: Long)

    /** [durationMs] is the real file duration reported by the player, when known. */
    suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean)
    suspend fun stopped(positionMs: Long, durationMs: Long?)
}

sealed interface PlaybackPlan {
    /** Launch the native Zidoo player ourselves on [uri]. */
    data class Launch(
        val uri: String,
        val container: String?,
        val title: String,
        val backdropUrl: String?,
        val logoUrl: String?,
        val startMs: Long,
        val reporter: PlaybackReporter?,
        /** Names the reporter again ([MediaProvider.reporter]) if the app is stopped while the film plays. */
        val resumeKey: String? = null,
    ) : PlaybackPlan
}

private const val PLAYER_BACKDROP_PX = 1920
private const val PLAYER_LOGO_PX = 500

/** Playback of this item: while it starts, the player shows the title with its backdrop and logo. */
fun MediaItem.launch(uri: String, container: String?, startMs: Long, reporter: PlaybackReporter?, resumeKey: String?) =
    PlaybackPlan.Launch(
        uri = uri,
        container = container,
        title = listOfNotNull(seriesTitle, episodeLabel, title).joinToString(" · "),
        backdropUrl = backdrop?.url(PLAYER_BACKDROP_PX),
        logoUrl = logo?.url(PLAYER_LOGO_PX),
        startMs = startMs,
        reporter = reporter,
        resumeKey = resumeKey,
    )

data class ProviderCapabilities(
    val markPlayed: Boolean,
    val favorites: Boolean,
    val similar: Boolean,
    val pathMapping: Boolean,
)

/** A media library backend: Home Theater, Jellyfin, Emby, Plex... */
interface MediaProvider {
    val capabilities: ProviderCapabilities

    suspend fun libraries(): List<Library>
    suspend fun home(): List<HomeSection>
    suspend fun libraryPage(library: Library, sort: SortOption, filter: LibraryFilter, start: Int, limit: Int): Page

    /** Genres, decades and qualities present in [library], to build the filter menu. */
    suspend fun libraryFacets(library: Library): LibraryFacets = LibraryFacets()
    suspend fun item(id: String): MediaItem
    suspend fun seasons(series: MediaItem): List<MediaItem>
    suspend fun episodes(series: MediaItem, season: MediaItem): List<MediaItem>
    suspend fun similar(item: MediaItem): List<MediaItem> = emptyList()
    suspend fun setPlayed(item: MediaItem, played: Boolean) {}
    suspend fun setFavorite(item: MediaItem, favorite: Boolean) {}
    suspend fun playback(item: MediaItem, resume: Boolean, options: PlaybackOptions): PlaybackPlan

    /** The reporter of a playback started earlier, from its [PlaybackPlan.Launch.resumeKey]. */
    fun reporter(resumeKey: String): PlaybackReporter? = null

    /**
     * What "Play" starts: for a series, the first in-progress or unwatched episode; for a
     * collection, the first film in progress, else the first unwatched one in release order.
     */
    suspend fun nextUp(series: MediaItem): MediaItem? {
        if (series.kind == MediaKind.COLLECTION) {
            val films = collectionItems(series)
            return films.firstOrNull { it.user.positionMs > 0 && !it.user.played } ?: films.firstOrNull { !it.user.played } ?: films.firstOrNull()
        }
        for (season in seasons(series).filter { (it.seasonNumber ?: 0) > 0 }) {
            episodes(series, season).firstOrNull { !it.user.played }?.let { return it }
        }
        return null
    }

    /** The collection (saga) a film belongs to, when the library shows it. */
    suspend fun collectionOf(item: MediaItem): MediaItem? = null

    /** The films of a collection, in the order they came out. */
    suspend fun collectionItems(collection: MediaItem): List<MediaItem> = emptyList()

    /** The NFO file describing [item], when there is one. */
    suspend fun nfo(item: MediaItem): NfoDocument? = null

    /** A sample server-side file path, shown in settings to help writing path mappings. */
    suspend fun samplePath(): String? = null

    /** Artwork and identification editing, when this backend supports it. */
    val editor: MetadataEditor? get() = null
}
