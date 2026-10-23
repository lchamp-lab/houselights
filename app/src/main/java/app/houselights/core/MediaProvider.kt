package app.houselights.core

/** Server path → network URI rewrite rule (e.g. /media/movies → smb://nas/movies). */
@kotlinx.serialization.Serializable
data class PathMapping(val from: String, val to: String)

/** An NFO file's path and text; [info] is parsed and typeset for display, [text] stays as written. */
data class NfoDocument(val path: String, val text: String) {
    val info: NfoInfo? by lazy { NfoParser.parse(text)?.typeset() }
}

private fun NfoInfo.typeset() = copy(
    title = Typeset.text(title),
    originalTitle = Typeset.text(originalTitle),
    plot = Typeset.text(plot),
    tagline = Typeset.text(tagline),
    directors = directors.map { Typeset.text(it) },
    writers = writers.map { Typeset.text(it) },
    studios = studios.map { Typeset.text(it) },
    actors = actors.map { it.copy(name = Typeset.text(it.name), role = Typeset.text(it.role)) },
    collection = Typeset.text(collection),
)

data class PlaybackOptions(val pathMappings: List<PathMapping>, val forceHttp: Boolean)

/** Receives playback progress so the backend can keep resume points and watched state. */
interface PlaybackReporter {
    suspend fun started(positionMs: Long)

    /** [durationMs] is the real file duration reported by the player, when known. */
    suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean)
    suspend fun stopped(positionMs: Long, durationMs: Long?)

    /** The title's next file (a film's next part), which the player moves on to by itself; null for another title. */
    suspend fun next(): Next? = null

    /** A title's next file: its [path], matched by name against the player's, its [reporter] and [resumeKey]. */
    class Next(val path: String, val reporter: PlaybackReporter, val resumeKey: String?)
}

sealed interface PlaybackPlan {
    /** Launch the native Zidoo player on [uri]. */
    data class Launch(
        val uri: String,
        val container: String?,
        val title: String,
        val backdropUrl: String?,
        val logoUrl: String?,
        val startMs: Long,
        val reporter: PlaybackReporter?,
        /** Recovers the reporter ([MediaProvider.reporter]) if the app is stopped while the film plays. */
        val resumeKey: String? = null,
        /** A disc (an ISO, a Blu-ray or DVD folder): the player may open it on a menu or an intro before its title. */
        val disc: Boolean = false,
        /** The file's tracks, when known in the player's order ([MediaInfo.tracksInOrder]); never a disc's. */
        val media: MediaInfo? = null,
        /** Where the title's choice of tracks is kept: the film, all its copies, or the whole show for an episode. */
        val titleKey: String? = null,
    ) : PlaybackPlan
}

private const val PLAYER_BACKDROP_PX = 1920

private const val PLAYER_LOGO_PX = 500

/** Playback of this item: while it starts, the player shows the title with its backdrop and logo. */
fun MediaItem.launch(
    uri: String,
    container: String?,
    startMs: Long,
    reporter: PlaybackReporter?,
    resumeKey: String?,
    disc: Boolean = false,
    media: MediaInfo? = null,
    titleKey: String? = null,
) = PlaybackPlan.Launch(
    uri = uri,
    container = container,
    title = listOfNotNull(seriesTitle, episodeLabel, title).joinToString(" · "),
    backdropUrl = backdrop?.url(PLAYER_BACKDROP_PX),
    logoUrl = logo?.url(PLAYER_LOGO_PX),
    startMs = startMs,
    reporter = reporter,
    resumeKey = resumeKey,
    disc = disc,
    media = media.takeUnless { disc },
    titleKey = titleKey,
)

/**
 * What a source can do, so screens never check which source it is. Editing is [MediaProvider.editor], NFOs
 * are per item ([MediaItem.hasNfo]), and functions a source does not offer return nothing.
 */
data class ProviderCapabilities(
    val markPlayed: Boolean,
    val favorites: Boolean,
    val similar: Boolean,
    /** Its file paths can be mapped to network paths the player opens itself (settings, Playback). */
    val pathMapping: Boolean,
    /** People have an id and a page ([Catalog.person], [Catalog.people]), and credits count their titles. */
    val people: Boolean = false,
    /** Credits include the crew ([Credits.crew]) for at least some jobs, besides the cast. */
    val crew: Boolean = false,
    /** A saga lists the films the library lacks ([Catalog.saga]); else only those it holds. */
    val sagaParts: Boolean = false,
    /** A film's copies are grouped into one title ([Catalog.versions]). */
    val versions: Boolean = false,
    /** The copy chosen for a film is remembered ([Catalog.chooseVersion]); else the best plays. */
    val versionChoice: Boolean = false,
    /** The library numbers include disk space, runtime and formats ([LibraryNumbers]); else title counts only. */
    val formatNumbers: Boolean = false,
    /** Grids filter by [LibraryFilter.picture], [LibraryFilter.sound] and [LibraryFilter.fileType]. */
    val formatFilters: Boolean = false,
    val personFilter: Boolean = false,
    /** A sagas library's wall takes a [LibraryFilter], which its films pass; else it is never filtered. */
    val sagaFilters: Boolean = false,
    val search: Boolean = false,
) {
    companion object {
        /** No source, none of it: a screen restored before its source could be read. */
        val NONE = ProviderCapabilities(markPlayed = false, favorites = false, similar = false, pathMapping = false)
    }
}

/** A media library backend: local folders on the NAS, or a Jellyfin server. */
interface MediaProvider {
    val capabilities: ProviderCapabilities

    suspend fun libraries(): List<Library>
    suspend fun home(): List<HomeSection>
    /**
     * A page of [library]'s wall. [grouped], a films library shows each saga ([LibraryKind.COLLECTIONS]) as one title in
     * place of its films, as far as the source groups them, and counts them in [Page.sagas] when it knows how many.
     */
    suspend fun libraryPage(
        library: Library,
        sort: SortOption,
        filter: LibraryFilter,
        start: Int,
        limit: Int,
        grouped: Boolean = false,
    ): Page

    /**
     * Episodes of [library] added since [since] (epoch ms) and not watched yet, up to [cap]; null when the source cannot
     * say (home's door then says nothing of them).
     */
    suspend fun newEpisodes(library: Library, since: Long, cap: Int = NEW_EPISODES_CAP): Int? = null

    /** Values present in [library] (genres, decades, formats…) for the filter menu. */
    suspend fun libraryFacets(library: Library): LibraryFacets = LibraryFacets()

    suspend fun item(id: String): MediaItem
    suspend fun seasons(series: MediaItem): List<MediaItem>
    suspend fun episodes(series: MediaItem, season: MediaItem): List<MediaItem>
    suspend fun similar(item: MediaItem): List<MediaItem> = emptyList()

    /** Marks [item] watched or not, its resume point cleared either way; a show, season or saga marks all it holds. */
    suspend fun setPlayed(item: MediaItem, played: Boolean) {}
    suspend fun setFavorite(item: MediaItem, favorite: Boolean) {}
    suspend fun playback(item: MediaItem, resume: Boolean, options: PlaybackOptions): PlaybackPlan

    /** The reporter of a playback started earlier, from its [PlaybackPlan.Launch.resumeKey]. */
    fun reporter(resumeKey: String): PlaybackReporter? = null

    /** What "Play" starts: a series' first unwatched or in-progress episode, or a collection's film per [nextUpIn]. */
    suspend fun nextUp(series: MediaItem): MediaItem? {
        if (series.kind == MediaKind.COLLECTION) return nextUpIn(collectionItems(series))
        for (season in seasons(series).filter { (it.seasonNumber ?: 0) > 0 }) {
            episodes(series, season).firstOrNull { !it.user.played }?.let { return it }
        }
        return null
    }

    /** The collection (saga) a film belongs to, when the library shows it. */
    suspend fun collectionOf(item: MediaItem): MediaItem? = null

    /** The films of a collection, in release order. */
    suspend fun collectionItems(collection: MediaItem): List<MediaItem> = emptyList()

    /** The NFO file describing [item], when there is one. */
    suspend fun nfo(item: MediaItem): NfoDocument? = null

    /** A sample server-side file path, shown in settings to help writing path mappings. */
    suspend fun samplePath(): String? = null

    /** Artwork and identification editing, when this backend supports it. */
    val editor: MetadataEditor? get() = null

    /** People, whole sagas, versions, numbers and search, as far as [capabilities] says. */
    val catalog: Catalog? get() = null
}

/** The most new episodes a source counts for home: past it, the door says as many. */
const val NEW_EPISODES_CAP = 99

/**
 * The film "Play" starts in a collection's [films] (release order): the first in progress, else unwatched,
 * else the first. Outside the provider so a page holding the films need not fetch them again.
 */
fun nextUpIn(films: List<MediaItem>): MediaItem? =
    films.firstOrNull { it.user.positionMs > 0 && !it.user.played } ?: films.firstOrNull { !it.user.played } ?: films.firstOrNull()
