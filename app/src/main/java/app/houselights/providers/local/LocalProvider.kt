package app.houselights.providers.local

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import app.houselights.R
import app.houselights.core.NfoParser
import app.houselights.core.NotFoundException
import app.houselights.core.Catalog
import app.houselights.core.Definition
import app.houselights.core.HdrFormat
import app.houselights.core.HomeSection
import app.houselights.core.Job
import app.houselights.core.ImageRef
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.Quality
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
import app.houselights.core.launch
import app.houselights.core.Person
import app.houselights.core.ProviderCapabilities
import app.houselights.core.SectionType
import app.houselights.core.SortOption
import app.houselights.core.Typeset
import app.houselights.core.UserState
import app.houselights.core.escapeUriPath
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import app.houselights.core.CollectionThreshold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Library built by [LibraryScanner] from the user's network folders. */
class LocalProvider(
    private val context: Context,
    private val dao: LibraryDao,
    override val editor: MetadataEditor,
    /** Films of a collection the library needs to show it; 0 hides collections. */
    private val collectionMinFilms: () -> Int = { CollectionThreshold.DEFAULT },
) : MediaProvider {

    override val capabilities = ProviderCapabilities(
        markPlayed = true, favorites = true, similar = true, pathMapping = false, people = true, crew = true, sagaParts = true,
        versions = true, versionChoice = true, formatNumbers = true, formatFilters = true, personFilter = true, search = true,
    )

    override val catalog: Catalog by lazy { LocalCatalog(context, dao, this, collectionMinFilms) }

    override suspend fun libraries(): List<Library> = buildList {
        if (dao.count(ItemKind.MOVIE) > 0) add(Library(MOVIES, LibraryKind.MOVIES, null))
        if (dao.count(ItemKind.SERIES) > 0) add(Library(SHOWS, LibraryKind.SHOWS, null))
        val min = collectionMinFilms()
        if (min > 0 && dao.collectionCount(min) > 0) add(Library(COLLECTIONS, LibraryKind.COLLECTIONS, null))
    }

    override suspend fun home(): List<HomeSection> {
        val mapper = LocalMapper(dao, context)
        val inProgress = dao.inProgress(20)
        return buildList {
            if (inProgress.isNotEmpty()) add(HomeSection(SectionType.CONTINUE, mapper.mapAll(inProgress), landscape = true))
            val next = nextUp(exclude = inProgress.map { it.id }.toSet())
            if (next.isNotEmpty()) add(HomeSection(SectionType.NEXT_UP, mapper.mapAll(next), landscape = true))
            for ((kind, library) in listOf(LibraryKind.MOVIES to MOVIES, LibraryKind.SHOWS to SHOWS)) {
                val items = dao.latest(if (kind == LibraryKind.MOVIES) ItemKind.MOVIE else ItemKind.SERIES, 24)
                if (items.isEmpty()) continue
                val mapped = mapper.mapAll(items)
                add(HomeSection(SectionType.LATEST, mapped, landscape = false, libraryKind = kind, libraryId = library))
            }
        }
    }

    override suspend fun libraryPage(library: Library, sort: SortOption, filter: LibraryFilter, start: Int, limit: Int): Page {
        if (library.id == COLLECTIONS) return collectionsPage(sort, start, limit)
        val kind = if (library.id == SHOWS) ItemKind.SERIES else ItemKind.MOVIE
        // Shows carry no technical badges themselves: one of their episodes must, as for the menu (libraryFacets).
        val filtered = LocalFilters.where(kind, filter)
        val where = filtered.sql
        val args = filtered.args
        val order = when (sort) {
            // Sort keys are lower case already: a plain order can follow the (kind, sortTitle) index.
            SortOption.TITLE -> "sortTitle ASC"
            SortOption.ADDED -> "addedAt DESC"
            SortOption.YEAR -> "year DESC, sortTitle ASC"
            SortOption.RATING -> "rating DESC, sortTitle ASC"
        }
        // The id settles ties, so that pages never overlap nor skip a title.
        val page = "SELECT $CARD_ROW FROM items WHERE $where ORDER BY $order, id LIMIT $limit OFFSET $start"
        val items = dao.query(SimpleSQLiteQuery(page, args.toTypedArray()))
        val total = dao.countQuery(SimpleSQLiteQuery("SELECT COUNT(*) FROM items WHERE $where", args.toTypedArray()))
        val mapper = LocalMapper(dao, context)
        return Page(mapper.mapAll(items), total)
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
                    "AND versionOf IS NULL " +
                    "GROUP BY collectionId HAVING n >= ?) s ON s.collectionId = c.id ORDER BY $order, c.id LIMIT $limit OFFSET $start",
                arrayOf<Any>(min),
            )
        )
        return Page(collectionItems(collections), dao.collectionCount(min))
    }

    /** [collections] as titles of the library, their films read in a query a [SQL_IN_LIMIT] collections. */
    internal suspend fun collectionItems(collections: List<CollectionEntity>): List<MediaItem> {
        val members = membersOf(collections.map { it.id })
        return withContext(Dispatchers.Default) { collections.map { collectionItem(it, members[it.id].orEmpty()) } }
    }

    /** The films of each collection of [ids], by collection: a query a [SQL_IN_LIMIT] collections, rather than one each. */
    internal suspend fun membersOf(ids: List<Long>): Map<Long, List<CollectionMember>> =
        ids.chunked(SQL_IN_LIMIT).flatMap { dao.collectionMembers(it) }.groupBy { it.collectionId }

    override suspend fun libraryFacets(library: Library): LibraryFacets {
        if (library.id == COLLECTIONS) return LibraryFacets()
        val kind = if (library.id == SHOWS) ItemKind.SERIES else ItemKind.MOVIE
        // Shows carry no technical badges themselves: look at their episodes.
        val files = if (kind == ItemKind.SERIES) ItemKind.EPISODE else kind
        val badgeLists = dao.badgesOf(files)
        val genreLists = dao.genresOf(kind)
        val years = dao.yearsOf(kind)
        val ends = dao.fileEnds(files)
        // Every episode's badges, split off the main thread.
        return withContext(Dispatchers.Default) {
            val badges = badgeLists.flatMap(PipeList::fromText).toSet()
            LibraryFacets(
                genres = PipeList.distinctGenres(genreLists),
                decades = years.map { it / 10 * 10 }.distinct().sortedDescending(),
                qualities = buildList {
                    if (Definition.ULTRA_HD.token in badges) add(Quality.UHD_4K)
                    if (badges.any { it in LocalFilters.HDR_BADGES }) add(Quality.HDR)
                    if (HdrFormat.DOLBY_VISION.token in badges) add(Quality.DOLBY_VISION)
                },
                pictures = LocalFilters.PICTURE_TOKENS.filter { it in badges },
                sounds = badges.filter(LocalFilters::isSound).sortedWith(compareBy(::soundRank).thenBy { it }),
                fileTypes = ends.mapNotNull(LocalFilters::fileTypeOf).distinct().sorted(),
            )
        }
    }

    /** The library row of a film, show, season or episode; null for a collection. */
    private fun itemKey(item: MediaItem): Long? = (LocalId.parse(item.id) as? LocalId.Item)?.key

    /** [item]'s row, without its NFO text ([LibraryDao.itemRow]). */
    private suspend fun entity(item: MediaItem): ItemEntity? = itemKey(item)?.let { dao.itemRow(it) }

    override suspend fun item(id: String): MediaItem {
        val entity = when (val local = LocalId.parse(id)) {
            is LocalId.Collection -> return mapCollection(dao.collection(local.key) ?: throw NotFoundException("Collection $id"))
            is LocalId.Item -> dao.itemRow(local.key)
            null -> null
        } ?: throw NotFoundException("Item $id")
        val castSource = if (entity.kind == ItemKind.MOVIE || entity.kind == ItemKind.SERIES) entity.id else entity.seriesId
        val people = castSource?.let { dao.creditsOf(it) }.orEmpty().filter { it.job == Job.CAST }
        val cast = withContext(Dispatchers.Default) { people.map(::person) }
        return LocalMapper(dao, context).map(entity).copy(cast = cast)
    }

    override suspend fun collectionOf(item: MediaItem): MediaItem? {
        if (item.kind != MediaKind.MOVIE) return null
        val min = collectionMinFilms().takeIf { it > 0 } ?: return null
        val collection = entity(item)?.collectionId?.let { dao.collection(it) } ?: return null
        val films = dao.collectionMembers(listOf(collection.id))
        return if (films.size >= min) mapCollection(collection, films) else null
    }

    override suspend fun collectionItems(collection: MediaItem): List<MediaItem> {
        val key = (LocalId.parse(collection.id) as? LocalId.Collection)?.key ?: return emptyList()
        val mapper = LocalMapper(dao, context)
        return mapper.mapAll(dao.collectionFilms(key))
    }

    /**
     * A collection as a title of the library: its name and story, the years its films span,
     * how many of them are here (and out of how many), its artwork or its first film's.
     */
    private suspend fun mapCollection(c: CollectionEntity, films: List<CollectionMember>? = null): MediaItem {
        val members = films ?: dao.collectionMembers(listOf(c.id))
        return withContext(Dispatchers.Default) { collectionItem(c, members) }
    }

    private fun collectionItem(c: CollectionEntity, members: List<CollectionMember>): MediaItem {
        val years = members.mapNotNull { it.year }
        val first = members.firstOrNull()
        val unplayed = members.count { !it.played }
        return MediaItem(
            id = LocalId.ofCollection(c.id),
            kind = MediaKind.COLLECTION,
            title = Typeset.text(CollectionRules.shownName(c.name)),
            overview = Typeset.text(c.overview),
            year = years.minOrNull(),
            endYear = years.maxOrNull()?.takeIf { it != years.minOrNull() },
            communityRating = members.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.average(),
            // One genre whatever its case: an NFO's "drame" and TMDB's "Drame" are one, as for the genre filter.
            genres = members.flatMap { it.genres }.groupBy { it.lowercase(Locale.ROOT) }.values
                .sortedByDescending { it.size }.take(3).map(PipeList::spellingOf),
            poster = (c.posterPath ?: first?.let { shown(it.userPosterPath, it.posterPath) })?.let { image(it, ImageType.POSTER) },
            backdrop = (c.backdropPath ?: first?.let { shown(it.userBackdropPath, it.backdropPath) })?.let { image(it, ImageType.BACKDROP) },
            user = UserState(played = members.isNotEmpty() && unplayed == 0, unplayedCount = unplayed),
            childCount = members.size,
            totalCount = c.partCount?.takeIf { it > members.size },
        )
    }

    override suspend fun nfo(item: MediaItem): NfoDocument? {
        if (item.kind == MediaKind.COLLECTION) return null
        val e = itemKey(item)?.let { dao.nfoOf(it) } ?: return null
        val text = e.nfo ?: return null
        val path = listOfNotNull(dao.source(e.sourceId)?.address(), e.nfoPath)
            .joinToString("/")
        return NfoDocument(path, text)
    }

    override suspend fun seasons(series: MediaItem): List<MediaItem> {
        val mapper = LocalMapper(dao, context)
        val key = itemKey(series) ?: return emptyList()
        return mapper.mapAll(dao.childRows(key).filter { it.kind == ItemKind.SEASON })
    }

    override suspend fun episodes(series: MediaItem, season: MediaItem): List<MediaItem> {
        val mapper = LocalMapper(dao, context)
        val key = itemKey(season) ?: return emptyList()
        return mapper.mapAll(dao.childRows(key))
    }

    override suspend fun nextUp(series: MediaItem): MediaItem? {
        if (series.kind == MediaKind.COLLECTION) return super.nextUp(series)
        val next = itemKey(series)?.let { nextEpisode(dao.episodeStates(listOf(it))) } ?: return null
        return dao.rows(listOf(next.id)).singleOrNull()?.let { LocalMapper(dao, context).map(it) }
    }

    override suspend fun similar(item: MediaItem): List<MediaItem> {
        if (item.kind == MediaKind.COLLECTION) return emptyList()
        val entity = entity(item) ?: return emptyList()
        if (entity.kind != ItemKind.MOVIE && entity.kind != ItemKind.SERIES) return emptyList()
        val genre = entity.genres.firstOrNull() ?: return emptyList()
        val mapper = LocalMapper(dao, context)
        return mapper.mapAll(dao.byGenre(entity.kind, genre, entity.id, SIMILAR))
    }

    override suspend fun setPlayed(item: MediaItem, played: Boolean) {
        val now = System.currentTimeMillis()
        val local = LocalId.parse(item.id)
        if (local is LocalId.Collection) return dao.setCollectionPlayed(local.key, played, now)
        val entity = entity(item) ?: return
        when (entity.kind) {
            ItemKind.SERIES -> dao.setSeriesPlayed(entity.id, played, now)
            ItemKind.SEASON -> dao.setSeasonPlayed(entity.id, played, now)
            else -> dao.setPlayed(entity.id, played, now)
        }
    }

    override suspend fun setFavorite(item: MediaItem, favorite: Boolean) {
        if (item.kind == MediaKind.COLLECTION) return
        itemKey(item)?.let { dao.setFavorite(it, favorite) }
    }

    /**
     * A film in several files plays one part at a time: the part [item] names ([MediaItem.parts]), from
     * its start; the film itself plays its first part, where its resume point lies.
     */
    override suspend fun playback(item: MediaItem, resume: Boolean, options: PlaybackOptions): PlaybackPlan {
        // With its NFO: the player is handed its artwork when that is all the web has (see LocalMapper.forPlayer).
        val entity = itemKey(item)?.let { dao.item(it) } ?: throw NotFoundException("Item ${item.id}")
        val part = (LocalId.parse(item.id) as? LocalId.Item)?.part
        val files = listOfNotNull(entity.filePath) + entity.parts
        // A part gone since the page was shown is a title gone.
        val file = files.getOrNull((part ?: 1) - 1) ?: throw NotFoundException("No file for ${item.id}")
        val source = dao.sources().firstOrNull { it.id == entity.sourceId } ?: error("Source of ${item.title} was removed")
        val mapper = LocalMapper(dao, context)
        val container = FilenameParser.extension(file)
        val mapped = mapper.map(entity)
        // The player names the part it opens.
        val shown = if (entity.parts.isEmpty()) mapped else mapped.copy(title = partTitle(mapped.title, part ?: 1, files.size))
        return mapper.forPlayer(entity, shown).launch(
            fileUri(source, file), container,
            startMs = if (resume && part == null) entity.positionMs else 0L,
            reporter = LocalReporter(dao, entity.id, part ?: 1),
            resumeKey = if (part == null) entity.id.toString() else LocalId.ofPart(entity.id, part),
            // A disc's image, or the stream a scan took for a Blu-ray's or a DVD's title (see ScanTitles).
            disc = container == DISC_IMAGE || FilenameParser.discOf(file) != null,
        )
    }

    private fun partTitle(title: String, part: Int, parts: Int) = "$title · ${context.getString(R.string.part_of, part, parts)}"

    override fun reporter(resumeKey: String): PlaybackReporter? =
        (LocalId.parse(resumeKey) as? LocalId.Item)?.let { LocalReporter(dao, it.key, it.part ?: 1) }

    /**
     * "À suivre": the next episode of each show recently watched, and the next film of each saga one of whose films
     * was ([nextInSagas]), the most recently watched first, skipping what is in progress already ([exclude]):
     * where every episode of those shows and every film of those sagas stands in two light queries, then the rows
     * of what comes next.
     */
    private suspend fun nextUp(exclude: Set<Long>): List<ItemEntity> {
        val shows = dao.recentSeries(NEXT_UP_SHOWS)
        val episodes = shows.map { it.id }.chunked(SQL_IN_LIMIT).flatMap { dao.episodeStates(it) }.groupBy { it.seriesId }
        val nextEpisodes = shows.mapNotNull { show -> episodes[show.id]?.let(::nextEpisode)?.let { Recent(it.id, show.at) } }
        val next = (nextEpisodes + nextInSagas(dao.startedSagas(NEXT_UP_SHOWS)))
            .sortedByDescending { it.at }.map { it.id }.filter { it !in exclude }.distinct().take(NEXT_UP_SHOWS)
        val rows = next.chunked(SQL_IN_LIMIT).flatMap { dao.rows(it) }.associateBy { it.id }
        return next.mapNotNull { rows[it] }
    }

    /**
     * The next episode among the seasons, specials (season 0, first in [episodes]) once none is left
     * there: as [MediaProvider.nextUp] does, a show starts with S01E01, not with a special.
     */
    private fun nextEpisode(episodes: List<EpisodeState>): EpisodeState? {
        val (specials, seasons) = episodes.partition { (it.seasonNumber ?: 0) == 0 }
        return nextOf(seasons) ?: nextOf(specials)
    }

    /** First in-progress episode, else the one after the last watched, else the first unwatched. */
    private fun nextOf(episodes: List<EpisodeState>): EpisodeState? {
        episodes.firstOrNull { it.positionMs > 0 && !it.played }?.let { return it }
        val lastWatched = episodes.indexOfLast { it.played }
        return episodes.drop(lastWatched + 1).firstOrNull { !it.played } ?: episodes.firstOrNull { !it.played }
    }

    /** A person a title credits, as screens show them: their page opens by [Person.id]. */
    private fun person(c: TitleCredit) =
        Person(Typeset.text(c.name), Typeset.text(c.character), c.profilePath?.let { image(it, ImageType.PROFILE) }, c.personKey, c.titles)

    companion object {
        const val MOVIES = "movies"
        const val SHOWS = "shows"
        const val COLLECTIONS = "collections"

        private const val NEXT_UP_SHOWS = 20

        /** The extension of a disc's image. */
        private const val DISC_IMAGE = "iso"
        private const val SIMILAR = 16

        /**
         * The next film of each saga of [films] (saga by saga, in the order they came out, [LibraryDao.startedSagas]):
         * the first after the last one watched that is not watched, when there is one; dated by the saga's last
         * viewing. A film in progress is "Continue"'s, and is left out by the caller.
         */
        internal fun nextInSagas(films: List<SagaFilmState>): List<Recent> =
            films.groupBy { it.collectionId }.values.mapNotNull { saga ->
                val last = saga.indexOfLast { it.played }
                val next = saga.drop(last + 1).firstOrNull { !it.played } ?: return@mapNotNull null
                Recent(next.id, saga.mapNotNull { it.lastPlayedAt }.maxOrNull() ?: 0)
            }

        /** How a sound format ranks among a library's, best first: objects, lossless, DTS, then the rest. */
        internal fun soundRank(token: String): Int = when {
            "Atmos" in token || token == "DTS:X" -> 0
            token.startsWith("TrueHD") || token == "DTS-HD MA" || token == "PCM" || token == "FLAC" -> 1
            token.startsWith("DTS") -> 2
            token == "E-AC3" -> 3
            else -> 4
        }

        /** The user's choice over the automatic value; an empty choice means none (a logo set in type). */
        internal fun shown(user: String?, automatic: String?): String? = when {
            user == null -> automatic
            user.isEmpty() -> null
            else -> user
        }

        /**
         * A cached local file, a full URL from an NFO, or a TMDB path. An NFO's address on TMDB is its
         * path there: Kodi and Radarr write the original file (a 4K fanart, a portrait of a few
         * megabytes), which is asked for at the size shown, as TMDB's own paths are.
         */
        fun image(path: String, type: ImageType): ImageRef {
            if (path.isCachedArtwork()) return ImageRef(path) { path }
            val tmdb = if (path.startsWith("http")) TMDB_IMAGE.matchEntire(path)?.groupValues?.get(1) else path
            if (tmdb != null) return ImageRef("tmdb:$tmdb") { w -> TmdbClient.imageUrl(tmdb, type, w) }
            // Also upgrades addresses read from NFO files before they were fetched over HTTPS only.
            return (NfoParser.imageUrl(path) ?: path).let { url -> ImageRef(url) { url } }
        }

        /** A file on TMDB's image server, at any size ("w500", "original"): its path there is the group. */
        private val TMDB_IMAGE = Regex("""https?://image\.tmdb\.org/t/p/[^/]+(/[^/?#]+)""", RegexOption.IGNORE_CASE)

        /**
         * URI the Zidoo player opens directly:
         * smb://user:password@host/share/folder/file (smb://domain;user:password@… for an account of a
         * domain, as SMB URLs write it) or nfs://host/export/folder/file. The name, domain and password
         * are percent-encoded: a password holding @, : or / would otherwise end the user's part.
         */
        fun fileUri(source: SourceEntity, relativePath: String): String {
            val path = listOf(source.share.trim('/'), source.path.trim('/'), relativePath).filter { it.isNotEmpty() }
                .joinToString("/") { escapeUriPath(it) }
            return when (source.protocol) {
                Protocol.NFS -> "nfs://${source.host}/$path"
                Protocol.SMB -> {
                    val user = listOf(source.domain, source.username).filter { it.isNotBlank() }.joinToString(";") { Uri.encode(it) }
                    val auth = if (source.username.isBlank()) "" else "$user:${Uri.encode(source.password.value)}@"
                    "smb://$auth${source.host}/$path"
                }
            }
        }
    }
}
