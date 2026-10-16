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

/**
 * Maps entities. [mapAll] reads what cards need beyond their own row (the parent show, a show's
 * counts) in one query for the whole list, instead of one per title.
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
        return entities.map { map(it) }
    }

    /** True once what [e]'s card needs beyond its row is known (see [mapAll]). */
    private fun prepared(e: ItemEntity): Boolean =
        firstScans != null && (e.kind != ItemKind.SERIES || e.id in counts) && (e.seriesId == null || e.seriesId in series)

    /** When [e] joined a library that was already there; null for what its first scan found. */
    private fun addedLater(e: ItemEntity): Long? {
        val first = firstScans?.get(e.sourceId) ?: 0L
        return e.addedAt.takeIf { first > 0 && it > first }
    }

    private fun titleOf(e: ItemEntity): String = e.title.ifBlank {
        when (e.kind) {
            ItemKind.SEASON -> context.getString(R.string.season_number, e.seasonNumber ?: 0)
            ItemKind.EPISODE -> context.getString(R.string.episode_number, e.episodeNumber ?: 0)
            else -> e.title
        }
    }

    suspend fun map(e: ItemEntity): MediaItem {
        if (!prepared(e)) return mapAll(listOf(e)).single()
        val show = e.seriesId?.let { series[it] }
        val kind = when (e.kind) {
            ItemKind.MOVIE -> MediaKind.MOVIE
            ItemKind.SERIES -> MediaKind.SERIES
            ItemKind.SEASON -> MediaKind.SEASON
            ItemKind.EPISODE -> MediaKind.EPISODE
        }
        // Text is set for display; sorting reads the entity, as written.
        val title = Typeset.text(titleOf(e))
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
            runtimeMs = e.durationMs ?: media?.durationMs ?: e.runtimeMin?.let { it * MS_PER_MINUTE },
            officialRating = e.certification,
            communityRating = e.rating,
            genres = e.genres,
            directors = e.directors.map { Typeset.text(it) },
            badges = e.badges,
            media = media,
            hasNfo = e.nfoPath != null,
            fileName = e.filePath?.substringAfterLast('/'),
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
            childCount = seasonCount,
        )
    }

    /**
     * [item] (mapped from [e]) as the player shows it while it opens the file: its backdrop and logo
     * where the player, another app, can fetch them, a web address. A file the app keeps itself (a
     * sidecar fanart or clearlogo, an image downloaded) is out of its reach: the automatic choice
     * stands in for it when that is on the web, else the address the NFO gives. None shown, none given.
     */
    suspend fun forPlayer(e: ItemEntity, item: MediaItem): MediaItem {
        val show = e.seriesId?.let { dao.item(it) }
        return item.copy(
            backdrop = webArtwork(e, show, ItemEntity::userBackdropPath, ItemEntity::backdropPath, NfoInfo::fanartUrl, ImageType.BACKDROP),
            logo = webArtwork(e, show, ItemEntity::userLogoPath, ItemEntity::logoPath, NfoInfo::logoUrl, ImageType.LOGO),
        )
    }

    private fun webArtwork(
        e: ItemEntity,
        show: ItemEntity?,
        user: (ItemEntity) -> String?,
        automatic: (ItemEntity) -> String?,
        nfo: (NfoInfo) -> String?,
        type: ImageType,
    ): ImageRef? {
        // Whose artwork the screens show: the item's, else its show's.
        val owner = listOfNotNull(e, show).firstOrNull { LocalProvider.shown(user(it), automatic(it)) != null } ?: return null
        val inReach = listOf(
            { LocalProvider.shown(user(owner), automatic(owner)) },
            { automatic(owner) },
            { owner.nfo?.let(NfoParser::parse)?.let(nfo) },
        ).firstNotNullOfOrNull { candidate -> candidate()?.takeUnless { it.isEmpty() || it.isCachedArtwork() } }
        return inReach?.let { LocalProvider.image(it, type) }
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

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
