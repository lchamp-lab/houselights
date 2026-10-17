package app.houselights.providers.local

import android.content.Context
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser
import app.houselights.core.Typeset
import app.houselights.core.UserState
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Maps entities. [mapAll] reads what cards need beyond their own row (the parent show, a show's
 * counts) in one query for the whole list, instead of one per title, then maps the list off the main
 * thread: what a file holds is decoded and every text set ([Typeset]) for each title, a whole wall
 * of them while a scan refreshes it.
 */
internal class LocalMapper(private val dao: LibraryDao, private val context: Context) {
    private val series = mutableMapOf<Long, ItemEntity?>()
    private val counts = mutableMapOf<Long, SeriesCounts>()
    private var firstScans: Map<Long, Long>? = null

    suspend fun mapAll(entities: List<ItemEntity>): List<MediaItem> {
        if (firstScans == null) firstScans = dao.firstScans().associate { it.id to it.firstScanAt }
        val shows = entities.filter { it.kind == ItemKind.SERIES }.map { it.id }.filter { it !in counts }
        for (chunk in shows.chunked(SQL_IN_LIMIT)) {
            chunk.forEach { counts[it] = SeriesCounts(it, unplayed = 0, seasons = 0) }
            dao.seriesCounts(chunk).forEach { counts[it.seriesId] = it }
        }
        val parents = entities.mapNotNull { it.seriesId }.distinct().filter { it !in series }
        for (chunk in parents.chunked(SQL_IN_LIMIT)) {
            chunk.forEach { series[it] = null }
            dao.rows(chunk).forEach { series[it.id] = it }
        }
        return withContext(Dispatchers.Default) { entities.map(::mapPrepared) }
    }

    suspend fun map(e: ItemEntity): MediaItem = mapAll(listOf(e)).single()

    /** When [e] joined a library that was already there; null for what its first scan found. */
    private fun addedLater(e: ItemEntity): Long? {
        val first = firstScans?.get(e.sourceId) ?: 0L
        return e.addedAt.takeIf { first > 0 && it > first }
    }

    private fun titleOf(e: ItemEntity): String = e.title.ifBlank {
        when (e.kind) {
            ItemKind.SEASON -> context.getString(R.string.season_number, e.seasonNumber ?: 0)
            ItemKind.EPISODE -> {
                val first = e.episodeNumber ?: 0
                e.lastEpisodeNumber?.takeIf { it > first }?.let { context.getString(R.string.episode_numbers, first, it) }
                    ?: context.getString(R.string.episode_number, first)
            }
            else -> e.title
        }
    }

    /** [e] as a title, once [mapAll] read what its card needs beyond its row. */
    private fun mapPrepared(e: ItemEntity): MediaItem {
        val show = e.seriesId?.let { series[it] }
        val kind = when (e.kind) {
            ItemKind.MOVIE -> MediaKind.MOVIE
            ItemKind.SERIES -> MediaKind.SERIES
            ItemKind.SEASON -> MediaKind.SEASON
            ItemKind.EPISODE -> MediaKind.EPISODE
        }
        // Text is set for display; sorting reads the entity, as written.
        val title = Typeset.text(titleOf(e))
        // Null in a list (CARD_ROW): only a title's page shows what its file holds.
        val media = MediaProbe.decode(e.mediaInfo)
        val showCounts = counts[e.id]?.takeIf { e.kind == ItemKind.SERIES }
        val unplayed = showCounts?.unplayed
        val seasonCount = showCounts?.seasons
        return MediaItem(
            id = e.id.toString(),
            kind = kind,
            title = title,
            originalTitle = Typeset.text(e.originalTitle),
            overview = Typeset.text(e.overview),
            tagline = Typeset.text(e.tagline),
            year = e.year,
            endYear = e.endYear,
            continuing = e.continuing,
            runtimeMs = e.runtimeMs(),
            officialRating = e.certification,
            communityRating = e.rating,
            genres = e.genres,
            directors = e.directors.map { Typeset.text(it) },
            badges = e.badges,
            media = media,
            hasNfo = e.nfoPath != null,
            fileName = e.filePath?.substringAfterLast('/'),
            parts = partsOf(e, title),
            // Seasons and episodes show their series' artwork when they have none.
            poster = artwork(e, show, ItemEntity::userPosterPath, ItemEntity::posterPath, ImageType.POSTER),
            backdrop = artwork(e, show, ItemEntity::userBackdropPath, ItemEntity::backdropPath, ImageType.BACKDROP),
            logo = artwork(e, show, ItemEntity::userLogoPath, ItemEntity::logoPath, ImageType.LOGO),
            thumb = e.stillPath?.let { LocalProvider.image(it, ImageType.STILL) },
            addedAt = addedLater(e),
            user = UserState(
                played = e.played || (unplayed == 0 && seasonCount != null && seasonCount > 0),
                favorite = e.favorite,
                positionMs = e.positionMs,
                unplayedCount = unplayed,
            ),
            seriesId = e.seriesId?.toString(),
            seriesTitle = Typeset.text(show?.title),
            seasonId = if (e.kind == ItemKind.EPISODE) e.parentId?.toString() else null,
            seasonNumber = e.seasonNumber,
            episodeNumber = e.episodeNumber,
            lastEpisodeNumber = e.lastEpisodeNumber,
            childCount = seasonCount,
        )
    }

    /** Each part of a film in several files, playable on its own; none for a film in one file. */
    private fun partsOf(e: ItemEntity, title: String): List<MediaItem> {
        if (e.parts.isEmpty()) return emptyList()
        return (listOfNotNull(e.filePath) + e.parts).mapIndexed { i, path ->
            MediaItem(id = LocalId.ofPart(e.id, i + 1), kind = MediaKind.MOVIE, title = title, fileName = path.substringAfterLast('/'))
        }
    }

    /**
     * [item] (mapped from [e]) as the player shows it while it opens the file: its backdrop and logo
     * where the player, another app, can fetch them, a web address. A file the app keeps itself (a
     * sidecar fanart or clearlogo, an image downloaded) is out of its reach: the automatic choice
     * stands in for it when that is on the web, else the address the NFO gives, else the same of its
     * show's. None shown, none given; none chosen, none of that item's.
     */
    suspend fun forPlayer(e: ItemEntity, item: MediaItem): MediaItem {
        val show = e.seriesId?.let { dao.item(it) }
        // An NFO may be read for it, off the main thread.
        return withContext(Dispatchers.Default) {
            item.copy(backdrop = webArtwork(e, show, PlayerArt.BACKDROP), logo = webArtwork(e, show, PlayerArt.LOGO))
        }
    }

    /** What the player shows while it opens a file, and where an item keeps it. */
    private enum class PlayerArt(
        val user: (ItemEntity) -> String?,
        val automatic: (ItemEntity) -> String?,
        val nfo: (NfoInfo) -> String?,
        val type: ImageType,
    ) {
        BACKDROP(ItemEntity::userBackdropPath, ItemEntity::backdropPath, NfoInfo::fanartUrl, ImageType.BACKDROP),
        LOGO(ItemEntity::userLogoPath, ItemEntity::logoPath, NfoInfo::logoUrl, ImageType.LOGO),
    }

    private fun webArtwork(e: ItemEntity, show: ItemEntity?, art: PlayerArt): ImageRef? {
        val owners = listOfNotNull(e, show)
        // The screens show the item's artwork, else its show's: none there (none chosen, none found), none given.
        if (owners.none { LocalProvider.shown(art.user(it), art.automatic(it)) != null }) return null
        // An episode whose own image is a file of the app's still has its show's on the web.
        return owners.firstNotNullOfOrNull { webCandidate(it, art) }?.let { LocalProvider.image(it, art.type) }
    }

    /**
     * The first of [x]'s images the player can fetch, a web address: the one shown, the automatic
     * one, the NFO's. None when the user chose none for [x].
     */
    private fun webCandidate(x: ItemEntity, art: PlayerArt): String? {
        if (art.user(x)?.isEmpty() == true) return null
        return listOf(
            { LocalProvider.shown(art.user(x), art.automatic(x)) },
            { art.automatic(x) },
            { x.nfo?.let(NfoParser::parse)?.let(art.nfo) },
        ).firstNotNullOfOrNull { candidate -> candidate()?.takeUnless { it.isEmpty() || it.isCachedArtwork() } }
    }

    /** The user's choice, else the automatic one; for the item, else its show. */
    private fun artwork(
        e: ItemEntity,
        show: ItemEntity?,
        user: (ItemEntity) -> String?,
        automatic: (ItemEntity) -> String?,
        type: ImageType,
    ) = (LocalProvider.shown(user(e), automatic(e)) ?: show?.let { LocalProvider.shown(user(it), automatic(it)) })
        ?.let { LocalProvider.image(it, type) }
}
