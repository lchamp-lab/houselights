package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser
import kotlinx.coroutines.flow.first

/** Films: a file, its NFO and artwork, its TMDB match and its collection. */
internal class MovieIndexer(private val dao: LibraryDao, private val files: ScanFiles) {
    /** Collections already asked to TMDB during the current scan. */
    private val refreshedCollections = mutableSetOf<Long>()

    /** A new scan asks TMDB again. */
    fun startPass() = refreshedCollections.clear()

    suspend fun process(
        source: SourceEntity,
        file: RemoteFile,
        existing: ItemEntity?,
        tmdb: TmdbClient?,
        sidecars: Sidecars,
        refreshMetadata: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val parsed = LibraryScanner.parseMovie(file.relativePath)
        val local = sidecars.forVideo(file)

        var item = existing ?: ItemEntity(
            kind = ItemKind.MOVIE,
            sourceId = source.id,
            title = parsed.title,
            sortTitle = MetadataMerge.sortKey(parsed.title),
            year = parsed.year,
            filePath = file.relativePath,
            fileSize = file.size,
            badges = FilenameParser.badges(file.relativePath).joinToString("|"),
            addedAt = now,
        )
        if (existing == null) item = item.copy(id = dao.insert(item))
        item = files.withMediaInfo(source, file, item)

        val nfoText = local.nfo?.let { files.readText(source, it) }
        val nfo = nfoText?.let(NfoParser::parse)
        // Sidecars that disappeared leave details to TMDB again.
        val sidecarsRemoved = (item.nfoPath != null && local.nfo == null) ||
            (local.poster == null && item.posterPath.isCachedArtwork())
        val needsTmdb = tmdb != null && (item.matchedAt == null || refreshMetadata || nfo != null || sidecarsRemoved)
        if (nfo == null && !needsTmdb && !sidecarsRemoved && local.poster == null && local.fanart == null && local.logo == null) return

        val movie = if (needsTmdb) lookupMovie(tmdb!!, item.userTmdbId, nfo, parsed, known = item.tmdbId) else null
        if (sidecarsRemoved) item = MetadataMerge.withoutDetails(item, parsed.title, parsed.year)
        val art = LocalArt(
            poster = local.poster?.let { files.artwork.copy(source, it) },
            fanart = local.fanart?.let { files.artwork.copy(source, it) },
            logo = local.logo?.let { files.artwork.copy(source, it) },
            thumb = local.thumb?.let { files.artwork.copy(source, it) },
        )
        saveMovie(item.copy(nfoPath = local.nfo?.relativePath, nfo = nfoText), nfo, movie, art, tmdb, now, refreshMetadata)
    }

    suspend fun identify(item: ItemEntity, tmdbId: Int?, tmdb: TmdbClient) {
        val parsed = LibraryScanner.parseMovie(item.filePath ?: return)
        val nfo = item.nfo?.let(NfoParser::parse)
        // Fetched before anything changes: an unreachable TMDB throws here.
        val movie = if (tmdbId != null) tmdb.movie(tmdbId) else lookupMovie(tmdb, null, nfo, parsed)
        dao.setUserTmdbId(item.id, tmdbId)
        if (movie?.id != item.tmdbId) dao.clearUserArtwork(item.id)
        val fresh = dao.item(item.id) ?: return
        val cleared = MetadataMerge.withoutDetails(fresh, parsed.title, parsed.year)
        saveMovie(cleared, nfo, movie, LocalArt.of(fresh), tmdb, System.currentTimeMillis())
    }

    /**
     * The user's match first, then the NFO's ids, then the title the film was already matched
     * with ([known]: a refresh does not re-identify it), then a search by title.
     */
    private suspend fun lookupMovie(tmdb: TmdbClient, userTmdbId: Int?, nfo: NfoInfo?, parsed: ParsedMovie, known: Int? = null): TmdbMovie? {
        userTmdbId?.let { return tmdb.movieById(it) }
        val id = nfo?.tmdbId ?: nfo?.imdbId?.let { tmdb.findByImdb(it).first } ?: known
        return id?.let { tmdb.movieById(it) } ?: tmdb.findMovie(nfo?.title ?: parsed.title, nfo?.year ?: parsed.year)
    }

    /** Saves a film's details (see [MetadataMerge]) and its cast. */
    private suspend fun saveMovie(
        base: ItemEntity,
        nfoInfo: NfoInfo?,
        movie: TmdbMovie?,
        art: LocalArt,
        tmdb: TmdbClient?,
        now: Long,
        refresh: Boolean = false,
    ) {
        val nfo = nfoInfo?.takeUnless { MetadataMerge.describesOther(it, base.userTmdbId, movie?.imdbId) }
        // Without a fresh TMDB answer, the collection already known stays.
        val collectionId = collection(nfo, movie, tmdb, now, refresh) ?: base.collectionId.takeIf { movie == null }
        val details = movie?.details(tmdb?.movieCertification(movie), tmdb?.pickLogo(movie.images))
        val matchedAt = if (tmdb != null || nfoInfo != null) now else base.matchedAt
        val item = MetadataMerge.merge(base, nfo, details, art).copy(collectionId = collectionId, matchedAt = matchedAt)
            .withNfoStreams(nfoInfo) // What the file contains does not depend on the match.
        dao.saveScanned(item)
        MetadataMerge.cast(item.id, nfo, movie?.credits)?.let { dao.replaceCast(item.id, it) }
    }

    /**
     * The collection a film belongs to: the NFO's set first (what the user's media manager
     * decided), then TMDB's. Created when first met; its details are asked to TMDB once, or
     * again on a full refresh. Returns null when the film is in no collection.
     */
    private suspend fun collection(nfo: NfoInfo?, movie: TmdbMovie?, tmdb: TmdbClient?, now: Long, refresh: Boolean): Long? {
        val ref = movie?.belongsToCollection
        val name = nfo?.collection ?: ref?.name ?: return null
        // A set of the user's own ("Marvel") is not TMDB's collection, even if the film is in one.
        val tmdbId = nfo?.collectionTmdbId
            ?: ref?.id?.takeIf { nfo?.collection == null || CollectionRules.sameName(nfo.collection, ref.name) }
        val key = CollectionRules.key(tmdbId, name)
        var c = dao.collectionByKey(key) ?: CollectionEntity(
            matchKey = key,
            tmdbId = tmdbId,
            name = name,
            sortTitle = MetadataMerge.sortKey(name),
            posterPath = ref?.posterPath.takeIf { tmdbId == ref?.id },
            backdropPath = ref?.backdropPath.takeIf { tmdbId == ref?.id },
        ).let { it.copy(id = dao.insertCollection(it)) }
        if (tmdbId != null && tmdb != null && (c.fetchedAt == null || refresh) && refreshedCollections.add(c.id)) {
            tmdb.collection(tmdbId)?.let { full ->
                val shown = nfo?.collection ?: full.name
                c = c.copy(
                    name = shown,
                    sortTitle = MetadataMerge.sortKey(shown),
                    overview = full.overview?.ifBlank { null } ?: c.overview,
                    posterPath = full.posterPath ?: c.posterPath,
                    backdropPath = full.backdropPath ?: c.backdropPath,
                    partCount = CollectionRules.released(full.parts.map { it.releaseDate }, CollectionRules.today()),
                    fetchedAt = now,
                )
                dao.updateCollection(c)
            }
        }
        return c.id
    }
}
