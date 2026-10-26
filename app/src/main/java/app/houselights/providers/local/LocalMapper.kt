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
 * Maps rows to [MediaItem]s; [mapAll] loads related rows with one query per list, then maps off the main thread, as
 * decoding and typesetting a whole wall is costly.
 */
internal class LocalMapper(private val dao: LibraryDao, private val context: Context) {
    private val series = mutableMapOf<Long, ItemEntity?>()
    private val counts = mutableMapOf<Long, SeriesCounts>()
    private var firstScans: Map<Long, Long>? = null

    /** Films read for copies held ([playedHeld]), by main row. */
    private val filmsRead = HashSet<Long>()

    /**
     * Films in several copies, one of them at least held, by main row: since when the copy Lire plays ([VersionRank.played])
     * is missing, null while it is there. Lire plays that copy, so the film is held as it is.
     */
    private val playedHeld = mutableMapOf<Long, Long?>()

    suspend fun mapAll(entities: List<ItemEntity>): List<MediaItem> {
        if (firstScans == null) firstScans = dao.firstScans().associate { it.id to it.firstScanAt }
        val shows = entities.filter { it.kind == ItemKind.SERIES }.map { it.id }.filter { it !in counts }
        for (chunk in shows.chunked(SQL_IN_LIMIT)) {
            chunk.forEach { counts[it] = SeriesCounts(it, unplayed = 0, seasons = 0) }
            dao.seriesCounts(chunk).forEach { counts[it.seriesId] = it }
        }
        readCopiesHeld(entities)
        val parents = entities.mapNotNull { it.seriesId }.distinct().filter { it !in series }
        for (chunk in parents.chunked(SQL_IN_LIMIT)) {
            chunk.forEach { series[it] = null }
            dao.rows(chunk).forEach { series[it.id] = it }
        }
        return withContext(Dispatchers.Default) { entities.map(::mapPrepared) }
    }

    suspend fun map(e: ItemEntity): MediaItem = mapAll(listOf(e)).single()

    /** Fills [playedHeld] for the films among [entities]: one query per list, then, rare, each film held in several copies. */
    private suspend fun readCopiesHeld(entities: List<ItemEntity>) {
        val films = entities.filter { it.kind == ItemKind.MOVIE && it.versionOf == null }.map { it.id }.filter(filmsRead::add)
        for (chunk in films.chunked(SQL_IN_LIMIT)) {
            // Read whole, as Lire ranks them.
            for (title in dao.heldAmongCopies(chunk)) {
                val copies = dao.copies(title)
                val played = VersionRank.played(copies) ?: continue
                playedHeld[title] = copies.first { it.id == played }.missingSince
            }
        }
    }

    /** When [e] was added after its source's first scan; null for titles that scan found. */
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

    private fun mapPrepared(e: ItemEntity): MediaItem {
        val show = e.seriesId?.let { series[it] }
        val kind = when (e.kind) {
            ItemKind.MOVIE -> MediaKind.MOVIE
            ItemKind.SERIES -> MediaKind.SERIES
            ItemKind.SEASON -> MediaKind.SEASON
            ItemKind.EPISODE -> MediaKind.EPISODE
        }
        // Typeset for display only; sorting uses the raw entity text.
        val title = Typeset.text(titleOf(e))
        // Null for list rows (CARD_ROW): only the title page shows media info.
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
            // Seasons and episodes fall back to their series' artwork.
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
            edition = e.edition,
            // A film in several copies is held as the copy Lire plays is; a show once every episode is.
            missingSince = if (e.id in playedHeld) {
                playedHeld[e.id]
            } else {
                e.missingSince ?: showCounts?.takeIf { it.episodes > 0 && it.held == it.episodes }?.heldSince
            },
        )
    }

    private fun partsOf(e: ItemEntity, title: String): List<MediaItem> {
        if (e.parts.isEmpty()) return emptyList()
        return (listOfNotNull(e.filePath) + e.parts).mapIndexed { i, path ->
            MediaItem(
                id = LocalId.ofPart(e.id, i + 1),
                kind = MediaKind.MOVIE,
                title = title,
                fileName = path.substringAfterLast('/'),
                missingSince = e.missingSince,
            )
        }
    }

    /**
     * [item] with backdrop and logo as web URLs, as the external player cannot read the app's cached files: the
     * automatic image, else the NFO's URL, else the show's; nothing when screens show nothing.
     */
    suspend fun forPlayer(e: ItemEntity, item: MediaItem): MediaItem {
        val show = e.seriesId?.let { dao.item(it) }
        // May parse an NFO, so run off the main thread.
        return withContext(Dispatchers.Default) {
            item.copy(backdrop = webArtwork(e, show, PlayerArt.BACKDROP), logo = webArtwork(e, show, PlayerArt.LOGO))
        }
    }

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
        // Give the player nothing when the screens show no artwork for the item or its show.
        if (owners.none { LocalProvider.shown(art.user(it), art.automatic(it)) != null }) return null
        // An episode whose own image is a cached file can still use its show's web image.
        return owners.firstNotNullOfOrNull { webCandidate(it, art) }?.let { LocalProvider.image(it, art.type) }
    }

    /** The first web URL among [x]'s displayed, automatic and NFO images; null when the user chose none. */
    private fun webCandidate(x: ItemEntity, art: PlayerArt): String? {
        if (art.user(x)?.isEmpty() == true) return null
        return listOf(
            { LocalProvider.shown(art.user(x), art.automatic(x)) },
            { art.automatic(x) },
            { x.nfo?.let(NfoParser::parse)?.let(art.nfo) },
        ).firstNotNullOfOrNull { candidate -> candidate()?.takeUnless { it.isEmpty() || it.isCachedArtwork() } }
    }

    private fun artwork(
        e: ItemEntity,
        show: ItemEntity?,
        user: (ItemEntity) -> String?,
        automatic: (ItemEntity) -> String?,
        type: ImageType,
    ) = (LocalProvider.shown(user(e), automatic(e)) ?: show?.let { LocalProvider.shown(user(it), automatic(it)) })
        ?.let { LocalProvider.image(it, type) }
}
