package app.posterwall.providers.local

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import app.posterwall.R
import app.posterwall.core.HomeSection
import app.posterwall.core.ImageRef
import app.posterwall.core.Library
import app.posterwall.core.LibraryKind
import app.posterwall.core.MediaItem
import app.posterwall.core.MediaKind
import app.posterwall.core.MediaProvider
import app.posterwall.core.Page
import app.posterwall.core.PlaybackOptions
import app.posterwall.core.PlaybackPlan
import app.posterwall.core.PlaybackReporter
import app.posterwall.core.Person
import app.posterwall.core.ProviderCapabilities
import app.posterwall.core.SectionType
import app.posterwall.core.SortOption
import app.posterwall.core.UserState
import app.posterwall.providers.local.TmdbClient.Companion.ImageType

/** Library built by [LibraryScanner] from the user's SMB shares. */
class LocalProvider(private val context: Context, private val dao: LibraryDao) : MediaProvider {
    override val name: String = context.getString(R.string.source_local)

    override val capabilities = ProviderCapabilities(markPlayed = true, favorites = true, similar = true, pathMapping = false)

    override suspend fun libraries(): List<Library> = buildList {
        if (dao.count(ItemKind.MOVIE) > 0) add(Library(MOVIES, LibraryKind.MOVIES, null))
        if (dao.count(ItemKind.SERIES) > 0) add(Library(SHOWS, LibraryKind.SHOWS, null))
    }

    override suspend fun home(): List<HomeSection> {
        val mapper = Mapper()
        val inProgress = dao.inProgress(20)
        return buildList {
            if (inProgress.isNotEmpty()) add(HomeSection(SectionType.CONTINUE, inProgress.map { mapper.map(it) }, landscape = true))
            val next = nextUpAcrossShows(exclude = inProgress.map { it.id }.toSet())
            if (next.isNotEmpty()) add(HomeSection(SectionType.NEXT_UP, next.map { mapper.map(it) }, landscape = true))
            dao.latest(ItemKind.MOVIE, 24).takeIf { it.isNotEmpty() }?.let { items ->
                add(HomeSection(SectionType.LATEST, items.map { mapper.map(it) }, landscape = false, libraryKind = LibraryKind.MOVIES))
            }
            dao.latest(ItemKind.SERIES, 24).takeIf { it.isNotEmpty() }?.let { items ->
                add(HomeSection(SectionType.LATEST, items.map { mapper.map(it) }, landscape = false, libraryKind = LibraryKind.SHOWS))
            }
        }
    }

    override suspend fun libraryPage(library: Library, sort: SortOption, unplayedOnly: Boolean, start: Int, limit: Int): Page {
        val kind = if (library.id == SHOWS) ItemKind.SERIES else ItemKind.MOVIE
        val unplayed = when {
            !unplayedOnly -> ""
            kind == ItemKind.SERIES -> " AND EXISTS (SELECT 1 FROM items e WHERE e.seriesId = items.id AND e.kind = 'EPISODE' AND e.played = 0)"
            else -> " AND played = 0"
        }
        val order = when (sort) {
            SortOption.TITLE -> "sortTitle COLLATE NOCASE ASC"
            SortOption.ADDED -> "addedAt DESC"
            SortOption.YEAR -> "year DESC, sortTitle ASC"
            SortOption.RATING -> "rating DESC, sortTitle ASC"
        }
        val where = "kind = '${kind.name}'$unplayed"
        val items = dao.query(SimpleSQLiteQuery("SELECT * FROM items WHERE $where ORDER BY $order LIMIT $limit OFFSET $start"))
        val total = dao.countQuery(SimpleSQLiteQuery("SELECT COUNT(*) FROM items WHERE $where"))
        val mapper = Mapper()
        return Page(items.map { mapper.map(it) }, total)
    }

    override suspend fun item(id: String): MediaItem {
        val entity = dao.item(id.toLong()) ?: error("Item $id not found")
        val castSource = if (entity.kind == ItemKind.MOVIE || entity.kind == ItemKind.SERIES) entity.id else entity.seriesId
        val cast = castSource?.let { dao.cast(it) }.orEmpty().map { c ->
            Person(c.name, c.character, c.profilePath?.let { tmdbImage(it, ImageType.PROFILE) })
        }
        return Mapper().map(entity).copy(cast = cast)
    }

    override suspend fun seasons(series: MediaItem): List<MediaItem> {
        val mapper = Mapper()
        return dao.children(series.id.toLong()).filter { it.kind == ItemKind.SEASON }.map { mapper.map(it) }
    }

    override suspend fun episodes(series: MediaItem, season: MediaItem): List<MediaItem> {
        val mapper = Mapper()
        return dao.children(season.id.toLong()).map { mapper.map(it) }
    }

    override suspend fun nextUp(series: MediaItem): MediaItem? =
        nextEpisode(dao.episodesOfSeries(series.id.toLong()))?.let { Mapper().map(it) }

    override suspend fun similar(item: MediaItem): List<MediaItem> {
        val entity = dao.item(item.id.toLong()) ?: return emptyList()
        if (entity.kind != ItemKind.MOVIE && entity.kind != ItemKind.SERIES) return emptyList()
        val genre = entity.genres.split('|').firstOrNull { it.isNotBlank() } ?: return emptyList()
        val mapper = Mapper()
        return dao.byGenre(entity.kind, genre, entity.id, 16).map { mapper.map(it) }
    }

    override suspend fun setPlayed(item: MediaItem, played: Boolean) {
        val entity = dao.item(item.id.toLong()) ?: return
        val targets = when (entity.kind) {
            ItemKind.SERIES -> dao.episodesOfSeries(entity.id)
            ItemKind.SEASON -> dao.children(entity.id)
            else -> listOf(entity)
        }
        val now = System.currentTimeMillis()
        targets.forEach { dao.update(it.copy(played = played, positionMs = 0, lastPlayedAt = if (played) now else it.lastPlayedAt)) }
    }

    override suspend fun setFavorite(item: MediaItem, favorite: Boolean) {
        val entity = dao.item(item.id.toLong()) ?: return
        dao.update(entity.copy(favorite = favorite))
    }

    override suspend fun playback(item: MediaItem, resume: Boolean, options: PlaybackOptions): PlaybackPlan {
        val entity = dao.item(item.id.toLong()) ?: error("Item ${item.id} not found")
        val file = entity.filePath ?: error("${item.title} has no file")
        val source = dao.sources().firstOrNull { it.id == entity.sourceId } ?: error("Source of ${item.title} was removed")
        val media = Mapper().map(entity)
        return PlaybackPlan.Launch(
            uri = smbUri(source, file),
            container = FilenameParser.extension(file),
            title = listOfNotNull(media.seriesTitle, media.episodeLabel, media.title).joinToString(" · "),
            backdropUrl = media.backdrop?.url(1920),
            logoUrl = media.logo?.url(500),
            startMs = if (resume) entity.positionMs else 0L,
            reporter = Reporter(entity.id),
        )
    }

    private inner class Reporter(private val itemId: Long) : PlaybackReporter {
        override suspend fun started(positionMs: Long) = save(positionMs, null, finished = false)

        override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) =
            save(positionMs, durationMs, finished = false)

        override suspend fun stopped(positionMs: Long, durationMs: Long?) = save(positionMs, durationMs, finished = true)

        private suspend fun save(positionMs: Long, durationMs: Long?, finished: Boolean) {
            val entity = dao.item(itemId) ?: return
            val duration = durationMs ?: entity.durationMs ?: entity.runtimeMin?.let { it * 60_000L }
            val watched = duration != null && duration > 0 && positionMs >= duration * WATCHED_RATIO
            dao.update(
                entity.copy(
                    positionMs = when {
                        finished && watched -> 0
                        finished && positionMs < MIN_RESUME_MS -> entity.positionMs.takeIf { !entity.played } ?: 0
                        else -> positionMs
                    },
                    durationMs = durationMs ?: entity.durationMs,
                    played = entity.played || (finished && watched),
                    lastPlayedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Next episode of each show recently watched, skipping ones already in progress. */
    private suspend fun nextUpAcrossShows(exclude: Set<Long>): List<ItemEntity> {
        val seriesIds = dao.recentlyPlayedEpisodes().mapNotNull { it.seriesId }.distinct().take(20)
        return seriesIds.mapNotNull { nextEpisode(dao.episodesOfSeries(it)) }.filter { it.id !in exclude }
    }

    /** First in-progress episode, else the one after the last watched, else the first unwatched. */
    private fun nextEpisode(episodes: List<ItemEntity>): ItemEntity? {
        episodes.firstOrNull { it.positionMs > 0 && !it.played }?.let { return it }
        val lastWatched = episodes.indexOfLast { it.played }
        return episodes.drop(lastWatched + 1).firstOrNull { !it.played } ?: episodes.firstOrNull { !it.played }
    }

    /** Maps entities, looking up each parent series once. */
    private inner class Mapper {
        private val series = mutableMapOf<Long, ItemEntity?>()

        suspend fun map(e: ItemEntity): MediaItem {
            val show = e.seriesId?.let { id -> series.getOrPut(id) { dao.item(id) } }
            val kind = when (e.kind) {
                ItemKind.MOVIE -> MediaKind.MOVIE
                ItemKind.SERIES -> MediaKind.SERIES
                ItemKind.SEASON -> MediaKind.SEASON
                ItemKind.EPISODE -> MediaKind.EPISODE
            }
            val title = e.title.ifBlank {
                when (e.kind) {
                    ItemKind.SEASON -> context.getString(R.string.season_number, e.seasonNumber ?: 0)
                    ItemKind.EPISODE -> context.getString(R.string.episode_number, e.episodeNumber ?: 0)
                    else -> e.title
                }
            }
            val unplayed = if (e.kind == ItemKind.SERIES) dao.episodesOfSeries(e.id).count { !it.played } else null
            val seasonCount = if (e.kind == ItemKind.SERIES) dao.children(e.id).count { it.kind == ItemKind.SEASON } else null
            return MediaItem(
                id = e.id.toString(),
                kind = kind,
                title = title,
                originalTitle = e.originalTitle,
                overview = e.overview,
                tagline = e.tagline,
                year = e.year,
                endYear = e.endYear,
                continuing = e.continuing,
                runtimeMs = e.durationMs ?: e.runtimeMin?.let { it * 60_000L },
                officialRating = e.certification,
                communityRating = e.rating,
                genres = e.genres.split('|').filter { it.isNotBlank() },
                directors = e.directors.split('|').filter { it.isNotBlank() },
                badges = e.badges.split('|').filter { it.isNotBlank() },
                poster = (e.posterPath ?: show?.posterPath)?.let { tmdbImage(it, ImageType.POSTER) },
                backdrop = (e.backdropPath ?: show?.backdropPath)?.let { tmdbImage(it, ImageType.BACKDROP) },
                logo = (e.logoPath ?: show?.logoPath)?.let { tmdbImage(it, ImageType.LOGO) },
                thumb = e.stillPath?.let { tmdbImage(it, ImageType.STILL) },
                user = UserState(
                    played = e.played || (unplayed == 0 && seasonCount != null && seasonCount > 0),
                    favorite = e.favorite,
                    positionMs = e.positionMs,
                    unplayedCount = unplayed,
                ),
                seriesId = e.seriesId?.toString(),
                seriesTitle = show?.title,
                seasonId = if (e.kind == ItemKind.EPISODE) e.parentId?.toString() else null,
                seasonNumber = e.seasonNumber,
                episodeNumber = e.episodeNumber,
                childCount = seasonCount,
            )
        }
    }

    companion object {
        const val MOVIES = "movies"
        const val SHOWS = "shows"
        private const val WATCHED_RATIO = 0.9
        private const val MIN_RESUME_MS = 60_000L

        private fun tmdbImage(path: String, type: ImageType) =
            ImageRef("tmdb:$path") { w -> TmdbClient.imageUrl(path, type, w) }

        /** smb://user:password@host/share/folder/file — the format the Zidoo player opens directly. */
        fun smbUri(source: SourceEntity, relativePath: String): String {
            val auth = if (source.username.isBlank()) "" else "${Uri.encode(source.username)}:${Uri.encode(source.password)}@"
            val path = listOf(source.share, source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("/")
            return "smb://$auth${source.host}/$path"
        }
    }
}
