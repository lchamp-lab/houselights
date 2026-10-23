package app.houselights.providers.local

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import app.houselights.R
import app.houselights.core.NfoParser
import app.houselights.core.NotFoundException
import app.houselights.core.BadgeTokens
import app.houselights.core.Catalog
import app.houselights.core.Codecs
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

/** Provider for the library [LibraryScanner] builds from network sources. */
class LocalProvider(
    private val context: Context,
    private val dao: LibraryDao,
    override val editor: MetadataEditor,
    /** Told of marks and stops, for the Trakt account. */
    private val history: WatchHistory = WatchHistory.None,
    /** Minimum owned films for a collection to be shown; 0 hides collections. */
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
        // Shows have no technical badges of their own: file filters match their episodes, as in libraryFacets.
        val filtered = LocalFilters.where(kind, filter)
        val where = filtered.sql
        val args = filtered.args
        val order = when (sort) {
            // Sort keys are already lowercase, so a plain ORDER BY can use the (kind, sortTitle) index.
            SortOption.TITLE -> "sortTitle ASC"
            SortOption.ADDED -> "addedAt DESC"
            SortOption.YEAR -> "year DESC, sortTitle ASC"
            SortOption.RATING -> "rating DESC, sortTitle ASC"
        }
        // Ties sort by id so pages never overlap or skip a title.
        val page = "SELECT $CARD_ROW FROM items WHERE $where ORDER BY $order, id LIMIT $limit OFFSET $start"
        val items = dao.query(SimpleSQLiteQuery(page, args.toTypedArray()))
        val total = dao.countQuery(SimpleSQLiteQuery("SELECT COUNT(*) FROM items WHERE $where", args.toTypedArray()))
        val mapper = LocalMapper(dao, context)
        return Page(mapper.mapAll(items), total)
    }

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

    internal suspend fun collectionItems(collections: List<CollectionEntity>): List<MediaItem> {
        val members = membersOf(collections.map { it.id })
        return withContext(Dispatchers.Default) { collections.map { collectionItem(it, members[it.id].orEmpty()) } }
    }

    /** One query per [SQL_IN_LIMIT] collections rather than one each. */
    internal suspend fun membersOf(ids: List<Long>): Map<Long, List<CollectionMember>> =
        ids.chunked(SQL_IN_LIMIT).flatMap { dao.collectionMembers(it) }.groupBy { it.collectionId }

    override suspend fun libraryFacets(library: Library): LibraryFacets {
        if (library.id == COLLECTIONS) return LibraryFacets()
        val kind = if (library.id == SHOWS) ItemKind.SERIES else ItemKind.MOVIE
        // Shows have no technical badges of their own: read their episodes'.
        val files = if (kind == ItemKind.SERIES) ItemKind.EPISODE else kind
        val badgeLists = dao.badgesOf(files)
        val genreLists = dao.genresOf(kind)
        val years = dao.yearsOf(kind)
        val ends = dao.fileEnds(files)
        // Splitting every episode's badges is costly: off the main thread.
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

    private fun itemKey(item: MediaItem): Long? = (LocalId.parse(item.id) as? LocalId.Item)?.key

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
            // Genres grouped case-insensitively, as in the genre filter: an NFO's "drame" is TMDB's "Drame".
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
        val counts = dao.seasonCounts(key).associateBy { it.seasonId }
        return mapper.mapAll(dao.childRows(key).filter { it.kind == ItemKind.SEASON }).map { season ->
            val id = season.id.toLongOrNull() ?: return@map season
            // A season whose episodes are all gone holds none: it is not watched.
            val c = counts[id] ?: return@map season.copy(childCount = 0, user = season.user.copy(played = false, unplayedCount = 0))
            season.copy(
                childCount = c.episodes,
                user = season.user.copy(played = season.user.played || c.played == c.episodes, unplayedCount = c.episodes - c.played),
            )
        }
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
        // Exclude the film's main row, so a copy does not list its own film as similar.
        return mapper.mapAll(dao.byGenre(entity.kind, genre, entity.versionOf ?: entity.id, SIMILAR))
    }

    /** A show, season or saga in one statement: all its titles change together. */
    override suspend fun setPlayed(item: MediaItem, played: Boolean) {
        val now = System.currentTimeMillis()
        val local = LocalId.parse(item.id)
        if (local is LocalId.Collection) {
            return history.mark(Marked.Saga(local.key), played) { dao.setCollectionPlayed(local.key, played, now) }
        }
        val entity = entity(item) ?: return
        history.mark(Marked.Row(entity.id), played) {
            when (entity.kind) {
                ItemKind.SERIES -> dao.setSeriesPlayed(entity.id, played, now)
                ItemKind.SEASON -> dao.setSeasonPlayed(entity.id, played, now)
                else -> dao.setPlayed(entity.id, played, now)
            }
        }
    }

    override suspend fun setFavorite(item: MediaItem, favorite: Boolean) {
        if (item.kind == MediaKind.COLLECTION) return
        itemKey(item)?.let { dao.setFavorite(it, favorite) }
    }

    /** A part ([MediaItem.parts]) plays from its start; the film plays its first part, which holds the resume point. */
    override suspend fun playback(item: MediaItem, resume: Boolean, options: PlaybackOptions): PlaybackPlan {
        val key = itemKey(item) ?: throw NotFoundException("Item ${item.id}")
        val part = (LocalId.parse(item.id) as? LocalId.Item)?.part
        // A multi-copy film plays the chosen copy, else the best ([LocalCatalog.versions]).
        val copy = if (part == null) chosenCopy(key) else null
        return play(copy ?: key, part, resume)
    }

    private suspend fun chosenCopy(titleId: Long): Long? = VersionRank.played(dao.copies(titleId))

    /** Plays row [rowId] itself: [part] from its start, else the first part from the resume point. */
    internal suspend fun play(rowId: Long, part: Int?, resume: Boolean): PlaybackPlan {
        // Full row with NFO, whose artwork URLs may be the only web ones (see LocalMapper.forPlayer).
        val entity = dao.item(rowId) ?: throw NotFoundException("Item $rowId")
        val files = listOfNotNull(entity.filePath) + entity.parts
        // A part removed since the page was shown is reported as not found.
        val file = files.getOrNull((part ?: 1) - 1) ?: throw NotFoundException("No file for $rowId")
        val source = dao.sources().firstOrNull { it.id == entity.sourceId } ?: error("Source of ${entity.title} was removed")
        val mapper = LocalMapper(dao, context)
        val container = FilenameParser.extension(file)
        val mapped = mapper.map(entity)
        val shown = if (entity.parts.isEmpty()) mapped else mapped.copy(title = partTitle(mapped.title, part ?: 1, files.size))
        return mapper.forPlayer(entity, shown).launch(
            fileUri(source, file), container,
            startMs = if (resume && part == null) entity.positionMs else 0L,
            reporter = LocalReporter(dao, entity.id, part ?: 1, history),
            resumeKey = if (part == null) entity.id.toString() else LocalId.ofPart(entity.id, part),
            // A disc image, or the stream a scan chose for a Blu-ray or DVD title (see ScanTitles).
            disc = container == DISC_IMAGE || FilenameParser.discOf(file) != null,
            // What the scan read is the first file's.
            media = MediaProbe.decode(entity.mediaInfo).takeIf { (part ?: 1) == 1 },
            titleKey = tracksKey(entity),
        )
    }

    private fun partTitle(title: String, part: Int, parts: Int) = "$title · ${context.getString(R.string.part_of, part, parts)}"

    override fun reporter(resumeKey: String): PlaybackReporter? =
        (LocalId.parse(resumeKey) as? LocalId.Item)?.let { LocalReporter(dao, it.key, it.part ?: 1, history) }

    /**
     * Next episode of each recent show and next film of each recent saga ([nextInSagas]), most recent first, without
     * [exclude]; two light state queries, then only the rows shown.
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

    /** Regular seasons before specials, so a show starts with S01E01, as in [MediaProvider.nextUp]. */
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

    private fun person(c: TitleCredit) =
        Person(Typeset.text(c.name), Typeset.text(c.character), c.profilePath?.let { image(it, ImageType.PROFILE) }, c.personKey, c.titles)

    companion object {
        const val MOVIES = "movies"
        const val SHOWS = "shows"
        const val COLLECTIONS = "collections"

        private const val NEXT_UP_SHOWS = 20

        private const val DISC_IMAGE = "iso"
        private const val SIMILAR = 16

        /**
         * Per saga ([LibraryDao.startedSagas]), the first unwatched film after the last watched one, dated by the
         * saga's last viewing; none when it is in progress, as Continue already shows it.
         */
        internal fun nextInSagas(films: List<SagaFilmState>): List<Recent> =
            films.groupBy { it.collectionId }.values.mapNotNull { saga ->
                val last = saga.indexOfLast { it.played }
                val next = saga.drop(last + 1).firstOrNull { !it.played }?.takeIf { it.positionMs == 0L } ?: return@mapNotNull null
                Recent(next.id, saga.mapNotNull { it.lastPlayedAt }.maxOrNull() ?: 0)
            }

        /** Rank of a sound format, best first: object-based, lossless, DTS, Dolby Digital Plus, then the rest. */
        internal fun soundRank(token: String): Int = SOUND_ORDER.indexOfFirst { it(token) }.let { if (it < 0) SOUND_ORDER.size else it }

        private val SOUND_ORDER: List<(String) -> Boolean> = listOf(
            { it.endsWith(BadgeTokens.ATMOS) || it == BadgeTokens.DTS_X },
            { it.startsWith(Codecs.TRUEHD) || it == Codecs.DTS_HD_MA || it == Codecs.PCM || it == Codecs.FLAC },
            { it.startsWith(Codecs.DTS) },
            { it == Codecs.EAC3 },
        )

        /** The user's choice over the automatic value; an empty choice means none (e.g. no logo). */
        internal fun shown(user: String?, automatic: String?): String? = when {
            user == null -> automatic
            user.isEmpty() -> null
            else -> user
        }

        /**
         * Image for a cached file, an NFO URL or a TMDB path. NFO URLs on TMDB, which Kodi and Radarr write at original
         * size, are reduced to their path and requested at display size.
         */
        fun image(path: String, type: ImageType): ImageRef {
            if (path.isCachedArtwork()) return ImageRef(path) { path }
            val tmdb = if (path.startsWith("http")) TMDB_IMAGE.matchEntire(path)?.groupValues?.get(1) else path
            if (tmdb != null) return ImageRef("tmdb:$tmdb") { w -> TmdbClient.imageUrl(tmdb, type, w) }
            // NfoParser.imageUrl also upgrades stored http URLs to HTTPS.
            return (NfoParser.imageUrl(path) ?: path).let { url -> ImageRef(url) { url } }
        }

        /** A TMDB image URL at any size ("w500", "original"); the group is the file path. */
        private val TMDB_IMAGE = Regex("""https?://image\.tmdb\.org/t/p/[^/]+(/[^/?#]+)""", RegexOption.IGNORE_CASE)

        /**
         * URI the Zidoo player opens: smb://user:password@host/share/path (domain;user for a domain account) or
         * nfs://host/export/path. Credentials are percent-encoded, as an @, : or / would end the user info.
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
