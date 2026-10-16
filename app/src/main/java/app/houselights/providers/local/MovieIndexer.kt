package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser

/** Films: a file, its NFO and artwork, its TMDB match and its collection. */
internal class MovieIndexer(private val dao: LibraryDao, private val files: ScanFiles) {
    /** Collections already asked to TMDB during the current scan. */
    private val refreshedCollections = mutableSetOf<Long>()

    /** A new scan asks TMDB again. */
    fun startPass() = refreshedCollections.clear()

    @Suppress("LongParameterList", "CyclomaticComplexMethod") // What a pass hands each file; one choice per sidecar.
    suspend fun process(
        source: SourceEntity,
        file: RemoteFile,
        existing: ItemEntity?,
        tmdb: TmdbPass,
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
            badges = FilenameParser.badges(file.relativePath),
            addedAt = now,
        )
        if (existing == null) item = item.copy(id = dao.insert(item))
        item = files.withMediaInfo(source, file, item)

        val kept = files.nfo(source, local.nfo, item)
        val nfo = kept.info
        // Sidecars that disappeared leave details to TMDB again.
        val sidecarsRemoved = (item.nfoPath != null && local.nfo == null) ||
            (local.poster == null && item.posterPath.isCachedArtwork())
        val needsTmdb = tmdb.open && (item.matchedAt == null || refreshMetadata || nfo != null || sidecarsRemoved)
        if (nfo == null && !needsTmdb && !sidecarsRemoved && local.poster == null && local.fanart == null && local.logo == null) return

        val known = item
        val lookup: (suspend (TmdbClient) -> TmdbMovie?)? =
            if (needsTmdb) { client -> lookupMovie(client, known.userTmdbId, nfo, parsed, known = known.tmdbId) } else null
        val fetched = fetch(tmdb, item.userTmdbId, nfo, refreshMetadata, lookup)
        // Details dropped and none from TMDB: the next scan asks it again.
        if (sidecarsRemoved) {
            item = MetadataMerge.withoutDetails(item, parsed.title, parsed.year)
                .let { if (fetched.answered) it else it.copy(matchedAt = null) }
        }
        val art = LocalArt(
            poster = local.poster?.let { files.copyArtwork(source, it) },
            fanart = local.fanart?.let { files.copyArtwork(source, it) },
            logo = local.logo?.let { files.copyArtwork(source, it) },
            thumb = local.thumb?.let { files.copyArtwork(source, it) },
        )
        saveMovie(item.copy(nfoPath = kept.path, nfo = kept.text), nfo, fetched, art, now)
    }

    /**
     * Asks TMDB all a new match needs (the film, its collection's details), then returns the writes
     * that apply it, for the caller to run at once ([LibraryScanner.identify]): TMDB out of reach
     * throws before anything is written.
     */
    suspend fun identify(item: ItemEntity, tmdbId: Int?, tmdb: TmdbClient): suspend () -> Unit {
        val parsed = LibraryScanner.parseMovie(item.filePath ?: return {})
        val nfo = item.nfo?.let(NfoParser::parse)
        val fetched = fetch(TmdbPass(tmdb, strict = true), tmdbId, nfo, refresh = false) { client ->
            if (tmdbId != null) client.movie(tmdbId) else lookupMovie(client, null, nfo, parsed)
        }
        return {
            dao.setUserTmdbId(item.id, tmdbId)
            if (fetched.movie?.id != item.tmdbId) dao.clearUserArtwork(item.id)
            dao.item(item.id)?.let { fresh ->
                val cleared = MetadataMerge.withoutDetails(fresh, parsed.title, parsed.year)
                saveMovie(cleared, nfo, fetched, LocalArt.of(fresh), System.currentTimeMillis())
            }
        }
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

    /** What TMDB said of a film, all asked before anything is written. */
    private class Fetched(
        /** Unanswered as well when it was not asked. */
        val lookup: TmdbAnswer<TmdbMovie>,
        /** The NFO, unless it names another title than the one matched. */
        val nfo: NfoInfo?,
        val collection: CollectionPlan?,
        val collectionDetails: TmdbAnswer<TmdbCollection>,
        /** What picks the film's certification and logo for the device's region and language. */
        val client: TmdbClient?,
    ) {
        val movie: TmdbMovie? get() = lookup.value

        /** TMDB answered all it was asked: the film is matched, and not asked again before a refresh. */
        val answered: Boolean
            get() = lookup is TmdbAnswer.Given && (collection?.fetch == null || collectionDetails is TmdbAnswer.Given)
    }

    /**
     * The film [lookup] finds (not asked when null), then its collection's details when they are
     * wanted: from TMDB's film, or from the NFO's set.
     */
    private suspend fun fetch(
        tmdb: TmdbPass,
        userTmdbId: Int?,
        nfoInfo: NfoInfo?,
        refresh: Boolean,
        lookup: (suspend (TmdbClient) -> TmdbMovie?)?,
    ): Fetched {
        val answer = lookup?.let { tmdb.ask(it) } ?: TmdbAnswer.Unanswered
        val nfo = nfoInfo?.takeUnless { MetadataMerge.describesOther(it, userTmdbId, answer.value?.imdbId) }
        val plan = collectionOf(nfo, answer.value, refresh)
        val details = plan?.fetch?.let { id -> tmdb.ask { it.collection(id) } } ?: TmdbAnswer.Unanswered
        return Fetched(answer, nfo, plan, details, tmdb.client)
    }

    /** Saves a film's details (see [MetadataMerge]) and its cast. */
    private suspend fun saveMovie(base: ItemEntity, nfoInfo: NfoInfo?, fetched: Fetched, art: LocalArt, now: Long) {
        val movie = fetched.movie
        val tmdb = fetched.client
        val nfo = fetched.nfo
        // Without a fresh TMDB answer, the collection already known stays.
        val collectionId = fetched.collection?.let { saveCollection(it, fetched.collectionDetails, now) }
            ?: base.collectionId.takeIf { movie == null }
        val details = movie?.details(tmdb?.movieCertification(movie), tmdb?.pickLogo(movie.images))
        // Until TMDB answers, the next scans ask it again.
        val matchedAt = if (fetched.answered) now else base.matchedAt
        val item = MetadataMerge.merge(base, nfo, details, art).copy(collectionId = collectionId, matchedAt = matchedAt)
            .withNfoStreams(nfoInfo) // What the file contains does not depend on the match.
        dao.saveScanned(item)
        MetadataMerge.cast(item.id, nfo, movie?.credits)?.let { dao.replaceCast(item.id, it) }
    }

    /** The collection a film belongs to, as known before anything is written. */
    private class CollectionPlan(
        /** The collection's row; one to insert (id 0) when first met. */
        val row: CollectionEntity,
        /** The name the NFO gives it, which stays over TMDB's. */
        val nfoName: String?,
        /** The TMDB id to ask its details of, when they are wanted. */
        val fetch: Int?,
    )

    /**
     * The collection a film belongs to: the NFO's set first (what the user's media manager
     * decided), then TMDB's. Its details are asked to TMDB once, or again on a full refresh.
     * Null when the film is in no collection.
     */
    private suspend fun collectionOf(nfo: NfoInfo?, movie: TmdbMovie?, refresh: Boolean): CollectionPlan? {
        val ref = movie?.belongsToCollection
        val name = nfo?.collection ?: ref?.name ?: return null
        // A set of the user's own ("Marvel") is not TMDB's collection, even if the film is in one.
        val tmdbId = nfo?.collectionTmdbId
            ?: ref?.id?.takeIf { nfo?.collection == null || CollectionRules.sameName(nfo.collection, ref.name) }
        val key = CollectionRules.key(tmdbId, name)
        val row = dao.collectionByKey(key) ?: CollectionEntity(
            matchKey = key,
            tmdbId = tmdbId,
            name = name,
            sortTitle = MetadataMerge.sortKey(name),
            posterPath = ref?.posterPath.takeIf { tmdbId == ref?.id },
            backdropPath = ref?.backdropPath.takeIf { tmdbId == ref?.id },
        )
        val fetch = tmdbId?.takeIf { (row.fetchedAt == null || refresh) && row.id !in refreshedCollections }
        return CollectionPlan(row, nfo?.collection, fetch)
    }

    /** Writes a collection: created when first met, given its details when TMDB gave them. Returns its id. */
    private suspend fun saveCollection(plan: CollectionPlan, details: TmdbAnswer<TmdbCollection>, now: Long): Long {
        var c = plan.row.takeIf { it.id != 0L } ?: plan.row.copy(id = dao.insertCollection(plan.row))
        // Asked once a pass, answered or not.
        if (plan.fetch != null) refreshedCollections += c.id
        details.value?.let { full ->
            val shown = plan.nfoName ?: full.name
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
        return c.id
    }
}
