package app.houselights.providers.local

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import app.houselights.R
import app.houselights.core.Definition
import app.houselights.core.HomeSection
import app.houselights.core.ImageRef
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.Quality
import app.houselights.core.WatchStatus
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.MetadataEditor
import app.houselights.core.NfoDocument
import app.houselights.core.Page
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
import app.houselights.core.Person
import app.houselights.core.ProviderCapabilities
import app.houselights.core.SectionType
import app.houselights.core.SortOption
import app.houselights.core.Typeset
import app.houselights.core.UserState
import app.houselights.providers.local.TmdbClient.Companion.ImageType

/** Library built by [LibraryScanner] from the user's network folders. */
class LocalProvider(
    private val context: Context,
    private val dao: LibraryDao,
    override val editor: MetadataEditor,
    /** Films of a collection the library needs to show it; 0 hides collections. */
    private val collectionMinFilms: () -> Int = { 2 },
) : MediaProvider {
    override val name: String = context.getString(R.string.source_local)

    override val capabilities = ProviderCapabilities(markPlayed = true, favorites = true, similar = true, pathMapping = false)

    override suspend fun libraries(): List<Library> = buildList {
        if (dao.count(ItemKind.MOVIE) > 0) add(Library(MOVIES, LibraryKind.MOVIES, null))
        if (dao.count(ItemKind.SERIES) > 0) add(Library(SHOWS, LibraryKind.SHOWS, null))
        val min = collectionMinFilms()
        if (min > 0 && dao.collectionCount(min) > 0) add(Library(COLLECTIONS, LibraryKind.COLLECTIONS, null))
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

    override suspend fun libraryPage(library: Library, sort: SortOption, filter: LibraryFilter, start: Int, limit: Int): Page {
        if (library.id == COLLECTIONS) return collectionsPage(sort, start, limit)
        val kind = if (library.id == SHOWS) ItemKind.SERIES else ItemKind.MOVIE
        val args = mutableListOf<Any>()
        val where = StringBuilder("kind = '${kind.name}'")
        val episodes = "SELECT 1 FROM items e WHERE e.seriesId = items.id AND e.kind = 'EPISODE'"
        when (filter.status) {
            WatchStatus.ALL -> {}
            WatchStatus.UNWATCHED -> where.append(
                if (kind == ItemKind.SERIES) " AND EXISTS ($episodes AND e.played = 0)" else " AND played = 0 AND positionMs = 0"
            )
            WatchStatus.IN_PROGRESS -> where.append(
                if (kind == ItemKind.SERIES) " AND EXISTS ($episodes AND (e.played = 1 OR e.positionMs > 0)) AND EXISTS ($episodes AND e.played = 0)"
                else " AND played = 0 AND positionMs > 0"
            )
            WatchStatus.WATCHED -> where.append(
                if (kind == ItemKind.SERIES) " AND NOT EXISTS ($episodes AND e.played = 0)" else " AND played = 1"
            )
            WatchStatus.FAVORITES -> where.append(" AND favorite = 1")
        }
        filter.genre?.let {
            // Genres are stored as "A|B|C": match a whole entry.
            where.append(" AND ('|' || genres || '|') LIKE ?")
            args += "%|$it|%"
        }
        filter.decade?.let {
            where.append(" AND year BETWEEN ? AND ?")
            args += it
            args += it + 9
        }
        when (filter.quality) {
            null -> {}
            Quality.UHD_4K -> where.append(" AND ('|' || badges || '|') LIKE '%|${Definition.ULTRA_HD.token}|%'")
            Quality.HDR -> where.append(" AND (badges LIKE '%HDR%' OR badges LIKE '%Dolby Vision%')")
            Quality.DOLBY_VISION -> where.append(" AND badges LIKE '%Dolby Vision%'")
        }
        val order = when (sort) {
            SortOption.TITLE -> "sortTitle COLLATE NOCASE ASC"
            SortOption.ADDED -> "addedAt DESC"
            SortOption.YEAR -> "year DESC, sortTitle ASC"
            SortOption.RATING -> "rating DESC, sortTitle ASC"
        }
        val items = dao.query(SimpleSQLiteQuery("SELECT * FROM items WHERE $where ORDER BY $order LIMIT $limit OFFSET $start", args.toTypedArray()))
        val total = dao.countQuery(SimpleSQLiteQuery("SELECT COUNT(*) FROM items WHERE $where", args.toTypedArray()))
        val mapper = Mapper()
        return Page(items.map { mapper.map(it) }, total)
    }

    /** Collections holding enough films, sorted like films: by name, latest addition, latest film, rating. */
    private suspend fun collectionsPage(sort: SortOption, start: Int, limit: Int): Page {
        val min = collectionMinFilms().coerceAtLeast(1)
        val order = when (sort) {
            SortOption.TITLE -> "c.sortTitle COLLATE NOCASE ASC"
            SortOption.ADDED -> "s.added DESC"
            SortOption.YEAR -> "s.lastYear DESC, c.sortTitle ASC"
            SortOption.RATING -> "s.rating DESC, c.sortTitle ASC"
        }
        val collections = dao.collectionQuery(
            SimpleSQLiteQuery(
                "SELECT c.* FROM collections c JOIN (SELECT collectionId, COUNT(*) AS n, MAX(addedAt) AS added, " +
                    "MAX(year) AS lastYear, AVG(rating) AS rating FROM items WHERE kind = 'MOVIE' AND collectionId IS NOT NULL " +
                    "GROUP BY collectionId HAVING n >= ?) s ON s.collectionId = c.id ORDER BY $order LIMIT $limit OFFSET $start",
                arrayOf<Any>(min),
            )
        )
        return Page(collections.map { mapCollection(it) }, dao.collectionCount(min))
    }

    override suspend fun libraryFacets(library: Library): LibraryFacets {
        if (library.id == COLLECTIONS) return LibraryFacets()
        val kind = if (library.id == SHOWS) ItemKind.SERIES else ItemKind.MOVIE
        // Shows carry no technical badges themselves: look at their episodes.
        val badgeKind = if (kind == ItemKind.SERIES) ItemKind.EPISODE else kind
        val badges = dao.badgesOf(badgeKind).flatMap { it.split('|') }.toSet()
        return LibraryFacets(
            genres = dao.genresOf(kind).flatMap { it.split('|') }.filter { it.isNotBlank() }.distinct().sorted(),
            decades = dao.yearsOf(kind).map { it / 10 * 10 }.distinct().sortedDescending(),
            qualities = buildList {
                if (Definition.ULTRA_HD.token in badges) add(Quality.UHD_4K)
                if (badges.any { it.startsWith("HDR") } || "Dolby Vision" in badges) add(Quality.HDR)
                if ("Dolby Vision" in badges) add(Quality.DOLBY_VISION)
            },
        )
    }

    override suspend fun item(id: String): MediaItem {
        collectionKey(id)?.let { key -> return mapCollection(dao.collection(key) ?: error("Collection $id not found")) }
        val entity = dao.item(id.toLong()) ?: error("Item $id not found")
        val castSource = if (entity.kind == ItemKind.MOVIE || entity.kind == ItemKind.SERIES) entity.id else entity.seriesId
        val cast = castSource?.let { dao.cast(it) }.orEmpty().map { c ->
            Person(c.name, c.character, c.profilePath?.let { image(it, ImageType.PROFILE) })
        }
        return Mapper().map(entity).copy(cast = cast)
    }

    override suspend fun collectionOf(item: MediaItem): MediaItem? {
        if (item.kind != MediaKind.MOVIE) return null
        val min = collectionMinFilms().takeIf { it > 0 } ?: return null
        val collection = dao.item(item.id.toLong())?.collectionId?.let { dao.collection(it) } ?: return null
        val films = dao.collectionFilms(collection.id)
        return if (films.size >= min) mapCollection(collection, films) else null
    }

    override suspend fun collectionItems(collection: MediaItem): List<MediaItem> {
        val key = collectionKey(collection.id) ?: return emptyList()
        val mapper = Mapper()
        return dao.collectionFilms(key).map { mapper.map(it) }
    }

    /**
     * A collection as a title of the library: its name and story, the years its films span,
     * how many of them are here (and out of how many), its artwork or its first film's.
     */
    private suspend fun mapCollection(c: CollectionEntity, films: List<ItemEntity>? = null): MediaItem {
        val members = films ?: dao.collectionFilms(c.id)
        val years = members.mapNotNull { it.year }
        val first = members.firstOrNull()
        val unplayed = members.count { !it.played }
        return MediaItem(
            id = COLLECTION_ID + c.id,
            kind = MediaKind.COLLECTION,
            title = c.name,
            overview = Typeset.text(c.overview),
            year = years.minOrNull(),
            endYear = years.maxOrNull()?.takeIf { it != years.minOrNull() },
            communityRating = members.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.average(),
            genres = members.flatMap { it.genres.split('|') }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key },
            poster = (c.posterPath ?: first?.let { shown(it.userPosterPath, it.posterPath) })?.let { image(it, ImageType.POSTER) },
            backdrop = (c.backdropPath ?: first?.let { shown(it.userBackdropPath, it.backdropPath) })?.let { image(it, ImageType.BACKDROP) },
            user = UserState(played = members.isNotEmpty() && unplayed == 0, unplayedCount = unplayed),
            childCount = members.size,
            totalCount = c.partCount?.takeIf { it > members.size },
        )
    }

    override suspend fun nfo(item: MediaItem): NfoDocument? {
        if (item.kind == MediaKind.COLLECTION) return null
        val e = dao.item(item.id.toLong()) ?: return null
        val text = e.nfo ?: return null
        val source = dao.sources().firstOrNull { it.id == e.sourceId }
        val path = listOfNotNull(source?.let { "${it.protocol.name.lowercase()}://${it.host}/${it.share}/${it.path}".trimEnd('/') }, e.nfoPath)
            .joinToString("/")
        return NfoDocument(path, text)
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
        if (series.kind == MediaKind.COLLECTION) super.nextUp(series)
        else nextEpisode(dao.episodesOfSeries(series.id.toLong()))?.let { Mapper().map(it) }

    override suspend fun similar(item: MediaItem): List<MediaItem> {
        if (item.kind == MediaKind.COLLECTION) return emptyList()
        val entity = dao.item(item.id.toLong()) ?: return emptyList()
        if (entity.kind != ItemKind.MOVIE && entity.kind != ItemKind.SERIES) return emptyList()
        val genre = entity.genres.split('|').firstOrNull { it.isNotBlank() } ?: return emptyList()
        val mapper = Mapper()
        return dao.byGenre(entity.kind, genre, entity.id, 16).map { mapper.map(it) }
    }

    override suspend fun setPlayed(item: MediaItem, played: Boolean) {
        val targets = collectionKey(item.id)?.let { dao.collectionFilms(it) } ?: run {
            val entity = dao.item(item.id.toLong()) ?: return
            when (entity.kind) {
                ItemKind.SERIES -> dao.episodesOfSeries(entity.id)
                ItemKind.SEASON -> dao.children(entity.id)
                else -> listOf(entity)
            }
        }
        val now = System.currentTimeMillis()
        targets.forEach { dao.update(it.copy(played = played, positionMs = 0, lastPlayedAt = if (played) now else it.lastPlayedAt)) }
    }

    override suspend fun setFavorite(item: MediaItem, favorite: Boolean) {
        if (item.kind == MediaKind.COLLECTION) return
        val entity = dao.item(item.id.toLong()) ?: return
        dao.update(entity.copy(favorite = favorite))
    }

    override suspend fun playback(item: MediaItem, resume: Boolean, options: PlaybackOptions): PlaybackPlan {
        val entity = dao.item(item.id.toLong()) ?: error("Item ${item.id} not found")
        val file = entity.filePath ?: error("${item.title} has no file")
        val source = dao.sources().firstOrNull { it.id == entity.sourceId } ?: error("Source of ${item.title} was removed")
        val media = Mapper().map(entity)
        return PlaybackPlan.Launch(
            uri = fileUri(source, file),
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
            val media = MediaProbe.decode(e.mediaInfo)
            val unplayed = if (e.kind == ItemKind.SERIES) dao.episodesOfSeries(e.id).count { !it.played } else null
            val seasonCount = if (e.kind == ItemKind.SERIES) dao.children(e.id).count { it.kind == ItemKind.SEASON } else null
            return MediaItem(
                id = e.id.toString(),
                kind = kind,
                title = title,
                originalTitle = e.originalTitle,
                overview = Typeset.text(e.overview),
                tagline = Typeset.text(e.tagline),
                year = e.year,
                endYear = e.endYear,
                continuing = e.continuing,
                runtimeMs = e.durationMs ?: media?.durationMs ?: e.runtimeMin?.let { it * 60_000L },
                officialRating = e.certification,
                communityRating = e.rating,
                genres = e.genres.split('|').filter { it.isNotBlank() },
                directors = e.directors.split('|').filter { it.isNotBlank() },
                badges = e.badges.split('|').filter { it.isNotBlank() },
                media = media,
                hasNfo = e.nfo != null,
                fileName = e.filePath?.substringAfterLast('/'),
                // Seasons and episodes show their series' artwork when they have none.
                poster = (shown(e.userPosterPath, e.posterPath) ?: show?.let { shown(it.userPosterPath, it.posterPath) })
                    ?.let { image(it, ImageType.POSTER) },
                backdrop = (shown(e.userBackdropPath, e.backdropPath) ?: show?.let { shown(it.userBackdropPath, it.backdropPath) })
                    ?.let { image(it, ImageType.BACKDROP) },
                logo = (shown(e.userLogoPath, e.logoPath) ?: show?.let { shown(it.userLogoPath, it.logoPath) })
                    ?.let { image(it, ImageType.LOGO) },
                thumb = e.stillPath?.let { image(it, ImageType.STILL) },
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
        const val COLLECTIONS = "collections"

        /** Collections share the item id space of navigation: "c12" is collection 12. */
        private const val COLLECTION_ID = "c"

        private fun collectionKey(id: String): Long? = id.takeIf { it.startsWith(COLLECTION_ID) }?.drop(COLLECTION_ID.length)?.toLongOrNull()
        private const val WATCHED_RATIO = 0.9
        private const val MIN_RESUME_MS = 60_000L

        /** The user's choice over the automatic value; an empty choice means none (a logo set in type). */
        private fun shown(user: String?, automatic: String?): String? = when {
            user == null -> automatic
            user.isEmpty() -> null
            else -> user
        }

        /** A cached local file, a full URL from an NFO, or a TMDB path. */
        fun image(path: String, type: ImageType) = when {
            path.startsWith("file:") || path.startsWith("http") -> ImageRef(path) { path }
            else -> ImageRef("tmdb:$path") { w -> TmdbClient.imageUrl(path, type, w) }
        }

        /**
         * URI the Zidoo player opens directly:
         * smb://user:password@host/share/folder/file or nfs://host/export/folder/file.
         */
        fun fileUri(source: SourceEntity, relativePath: String): String {
            val path = listOf(source.share.trim('/'), source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("/")
            return when (source.protocol) {
                Protocol.NFS -> "nfs://${source.host}/$path"
                Protocol.SMB -> {
                    val auth = if (source.username.isBlank()) "" else "${Uri.encode(source.username)}:${Uri.encode(source.password)}@"
                    "smb://$auth${source.host}/$path"
                }
            }
        }
    }
}
