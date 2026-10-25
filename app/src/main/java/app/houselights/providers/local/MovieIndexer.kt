package app.houselights.providers.local

import app.houselights.core.Editions
import app.houselights.core.NfoInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Indexes films: file, NFO and artwork, TMDB match and collection. */
internal class MovieIndexer(private val dao: LibraryDao, private val files: ScanFiles) {
    /** Collections already fetched from TMDB during the current pass. */
    private val refreshedCollections = mutableSetOf<Long>()

    fun startPass() = refreshedCollections.clear()

    suspend fun process(pass: ScanPass, file: RemoteFile, existing: ItemEntity?) {
        val source = pass.source
        val now = System.currentTimeMillis()
        // A multi-file film is named without the part suffix and read through its first file.
        val stack = pass.stacks.stackAt(file.relativePath)
        val parsed = LibraryScanner.parseMovie(stack?.namedPath ?: file.relativePath)
        val local = pass.sidecars.forVideo(file)
        val row = existing ?: inserted(source, file, parsed, stack, now)

        val kept = files.nfo(source, local.nfo, row)
        val nfo = kept.info
        val asked = asked(pass, row, existing, nfo, local)
        // The probe reads the NAS while TMDB answers; the file's tracks and its match are then saved at once.
        val (probed, answers) = coroutineScope {
            val probed = async { files.withMediaInfo(source, file, row) }
            val answers = if (asked.write) fetch(pass, row, nfo, parsed, asked) else null
            probed.await() to answers
        }
        var item = probed
        val fetched = answers ?: run {
            // Nothing usable (e.g. an unparsable release NFO): still record the sidecar key so scans skip it.
            val read = SidecarKey.of(local.copy(nfo = local.nfo.takeUnless { kept.stale }))
            if (item != row || read != item.sidecarKey) dao.saveScanned(item.copy(sidecarKey = read))
            return
        }
        // Details dropped without a TMDB answer: the next scan queries TMDB again.
        if (asked.sidecarsRemoved) {
            item = MetadataMerge.withoutDetails(item, parsed.title, parsed.year)
                .let { if (fetched.answered) it else it.copy(matchedAt = null) }
        }
        item = namedAsAFilm(item, file, parsed, fetched)
        val art = files.copyArt(source, local)
        val read = SidecarKey.of(files.sidecarsRead(local, kept, art))
        saveMovie(item.copy(nfoPath = kept.path, nfo = kept.text, sidecarKey = read), nfo, fetched, art, now)
    }

    private suspend fun inserted(source: SourceEntity, file: RemoteFile, parsed: ParsedMovie, stack: Stack?, now: Long): ItemEntity {
        val item = ItemEntity(
            kind = ItemKind.MOVIE,
            sourceId = source.id,
            title = parsed.title,
            sortTitle = MetadataMerge.sortKey(parsed.title),
            year = parsed.year,
            filePath = file.relativePath,
            // Existing rows get their parts from MissingFiles before processing.
            parts = stack?.rest.orEmpty().map { it.relativePath },
            fileSize = file.size,
            badges = FilenameParser.badges(file.relativePath),
            edition = FilenameParser.edition(file.relativePath),
            addedAt = now,
            // New rows never need the backfill.
            creditsAt = now,
        )
        return item.copy(id = dao.insert(item))
    }

    /** What reprocessing a film requires ([asked]). */
    private class Asked(
        val tmdb: Boolean,
        /** The NFO names another title or set than before. */
        val newIds: Boolean,
        val sidecarsRemoved: Boolean,
        /** Something to write: details, artwork, or a refresh deferred to a later pass. */
        val write: Boolean,
    )

    private fun asked(pass: ScanPass, item: ItemEntity, existing: ItemEntity?, nfo: NfoInfo?, local: VideoSidecars): Asked {
        val open = pass.tmdb.open
        // A re-read NFO triggers a TMDB request only when it names another title or set.
        val newIds = nfo != null && !sameIds(existing?.nfoInfo(), nfo)
        val removed = sidecarsRemoved(item, local)
        val tmdb = open && (item.matchedAt == null || pass.refreshMetadata || newIds || undecided(item, nfo) || removed)
        // A refresh TMDB cannot serve now is kept by leaving the film unmatched (see saveMovie).
        val refreshLeft = pass.refreshMetadata && !open && item.matchedAt != null
        return Asked(tmdb, newIds, removed, write = nfo != null || tmdb || refreshLeft || removed || local.hasArt)
    }

    private val VideoSidecars.hasArt: Boolean get() = poster != null || fanart != null || logo != null

    /** A user-matched film with an IMDb-only NFO: only TMDB can tell whether the NFO describes it (describesOther). */
    private fun undecided(item: ItemEntity, nfo: NfoInfo?): Boolean =
        nfo != null && item.userTmdbId != null && nfo.tmdbId == null && nfo.imdbId != null

    /** True when a sidecar read before is gone; details then return to TMDB, dropping stale cached images. */
    private fun sidecarsRemoved(item: ItemEntity, local: VideoSidecars): Boolean =
        (item.nfoPath != null && local.nfo == null) ||
            (local.poster == null && item.posterPath.isCachedArtwork()) ||
            (local.fanart == null && item.backdropPath.isCachedArtwork()) ||
            (local.logo == null && item.logoPath.isCachedArtwork())

    /**
     * Renames a row named after its first file ("Heat CD1", scanned before the other parts) to the film's name
     * ([film], "Heat") once it has parts, unless the NFO or TMDB named it.
     */
    private fun namedAsAFilm(item: ItemEntity, file: RemoteFile, film: ParsedMovie, fetched: Fetched): ItemEntity {
        val unnamed = fetched.nfo?.title == null && fetched.movie == null
        val ownName = item.title == LibraryScanner.parseMovie(file.relativePath).title
        if (item.parts.isEmpty() || !unnamed || !ownName) return item
        return item.copy(title = film.title, sortTitle = MetadataMerge.sortKey(film.title))
    }

    private fun sameIds(known: NfoInfo?, nfo: NfoInfo): Boolean =
        known != null && known.tmdbId == nfo.tmdbId && known.imdbId == nfo.imdbId &&
            known.collection == nfo.collection && known.collectionTmdbId == nfo.collectionTmdbId

    /**
     * Fetches everything a new match needs (film, collection details), then returns the writes for the caller to
     * run in a transaction ([LibraryScanner.identify]); a TMDB failure throws before anything is written.
     */
    suspend fun identify(item: ItemEntity, tmdbId: Int?, tmdb: TmdbClient): suspend () -> Unit {
        val parsed = item.filmName() ?: return {}
        val nfo = item.nfoInfo()
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

    /** Tries the user's match, the NFO's ids, the existing match ([known]), then a title search. */
    private suspend fun lookupMovie(tmdb: TmdbClient, userTmdbId: Int?, nfo: NfoInfo?, parsed: ParsedMovie, known: Int? = null): TmdbMovie? {
        userTmdbId?.let { return tmdb.movieById(it) }
        val id = nfo?.tmdbId ?: nfo?.imdbId?.let { tmdb.findByImdb(it).first } ?: known
        return id?.let { tmdb.movieById(it) } ?: tmdb.findMovie(nfo?.title ?: parsed.title, nfo?.year ?: parsed.year)
    }

    /** TMDB answers for a film, all fetched before anything is written. */
    private class Fetched(
        /** Also Unanswered when not requested. */
        val lookup: TmdbAnswer<TmdbMovie>,
        /** The NFO, unless it describes another title than the match. */
        val nfo: NfoInfo?,
        val collection: CollectionPlan?,
        val collectionDetails: TmdbAnswer<TmdbCollection>,
        /** Picks certification and logo for the device's region and language. */
        val client: TmdbClient?,
        /** A refresh, or an NFO set aside until TMDB confirms it: if unanswered, a later pass asks again. */
        val askAgain: Boolean,
    ) {
        val movie: TmdbMovie? get() = lookup.value

        /** TMDB answered every request, so the film counts as matched until a refresh. */
        val answered: Boolean
            get() = lookup is TmdbAnswer.Given && (collection?.fetch == null || collectionDetails is TmdbAnswer.Given)
    }

    /** TMDB's answers for [item] as [asked] needs them. */
    private suspend fun fetch(pass: ScanPass, item: ItemEntity, nfo: NfoInfo?, parsed: ParsedMovie, asked: Asked): Fetched {
        val lookup: (suspend (TmdbClient) -> TmdbMovie?)? =
            if (asked.tmdb) { client -> lookupMovie(client, item.userTmdbId, nfo, parsed, known = item.tmdbId) } else null
        // No TMDB request and unchanged NFO ids: keep the collection; a film without one gets it from its NFO.
        val keepCollection = lookup == null && !asked.newIds && item.collectionId != null
        return fetch(pass.tmdb, item.userTmdbId, nfo, pass.refreshMetadata, keepCollection = keepCollection, lookup = lookup)
    }

    /** Runs [lookup] (skipped when null), then fetches collection details if needed, unless [keepCollection]. */
    private suspend fun fetch(
        tmdb: TmdbPass,
        userTmdbId: Int?,
        nfoInfo: NfoInfo?,
        refresh: Boolean,
        keepCollection: Boolean = false,
        lookup: (suspend (TmdbClient) -> TmdbMovie?)?,
    ): Fetched {
        val answer = lookup?.let { tmdb.ask(it) } ?: TmdbAnswer.Unanswered
        // For a user match with no TMDB answer, an IMDb-only NFO is set aside until TMDB can confirm it.
        val untold = userTmdbId != null && answer.value == null && nfoInfo?.tmdbId == null && nfoInfo?.imdbId != null
        val nfo = nfoInfo?.takeUnless { untold || MetadataMerge.describesOther(it, userTmdbId, answer.value?.imdbId) }
        val plan = if (keepCollection) null else collectionOf(nfo, answer.value, refresh)
        val details = plan?.fetch?.let { id -> tmdb.ask { it.collection(id) } } ?: TmdbAnswer.Unanswered
        return Fetched(answer, nfo, plan, details, tmdb.client, askAgain = refresh || (untold && answer !is TmdbAnswer.Given))
    }

    /**
     * Saves details ([MetadataMerge]), edition, collection and credits ([CreditRules]) at once: a film matched without
     * its credits would never be read again for them.
     */
    private suspend fun saveMovie(base: ItemEntity, nfoInfo: NfoInfo?, fetched: Fetched, art: LocalArt, now: Long) = dao.inTransaction {
        val movie = fetched.movie
        val tmdb = fetched.client
        val nfo = fetched.nfo
        // Without a TMDB answer, keep the known collection.
        val collectionId = fetched.collection?.let { saveCollection(it, fetched.collectionDetails, now) }
            ?: base.collectionId.takeIf { movie == null }
        val details = movie?.details(tmdb?.movieCertification(movie), tmdb?.pickLogo(movie.images))
        // Unanswered refreshes and NFOs awaiting confirmation are retried by clearing matchedAt.
        val matchedAt = when {
            fetched.answered -> now
            fetched.askAgain -> null
            else -> base.matchedAt
        }
        val item = MetadataMerge.merge(base, nfo, details, art)
            .copy(collectionId = collectionId, matchedAt = matchedAt, edition = editionOf(base, nfoInfo))
            .withNfoStreams(nfoInfo) // Stream details do not depend on the match.
        dao.saveScanned(item)
        // Complete when TMDB answered or the film has no TMDB id, so the backfill skips it.
        val credits = CreditRules.credits(item.id, show = false, nfo, movie?.let { TmdbPeople(it.credits) })
        val answered = fetched.lookup is TmdbAnswer.Given
        dao.saveCredits(item.id, credits, tmdbAnswered = answered, complete = answered || item.tmdbId == null, at = now)
    }

    /** A film's collection, resolved before anything is written. */
    private class CollectionPlan(
        /** The collection row; id 0 when it must be inserted. */
        val row: CollectionEntity,
        /** The NFO's name, which takes precedence over TMDB's. */
        val nfoName: String?,
        /** TMDB id to fetch details for; null when not needed. */
        val fetch: Int?,
    )

    /**
     * The film's collection: the NFO set first, then TMDB's; null for none. Details are fetched once, and again on a
     * full refresh.
     */
    private suspend fun collectionOf(nfo: NfoInfo?, movie: TmdbMovie?, refresh: Boolean): CollectionPlan? {
        val ref = movie?.belongsToCollection
        val name = nfo?.collection ?: ref?.name ?: return null
        // A custom NFO set ("Marvel") is not TMDB's collection, even if the film is in one.
        val tmdbId = nfo?.collectionTmdbId
            ?: ref?.id?.takeIf { nfo?.collection == null || ref.name != null && CollectionRules.sameName(nfo.collection, ref.name) }
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

    /** Inserts the collection if new and stores TMDB details when given; returns its id. */
    private suspend fun saveCollection(plan: CollectionPlan, details: TmdbAnswer<TmdbCollection>, now: Long): Long {
        var c = plan.row.takeIf { it.id != 0L } ?: plan.row.copy(id = dao.insertCollection(plan.row))
        // Fetched at most once per pass, answered or not.
        if (plan.fetch != null) refreshedCollections += c.id
        details.value?.let { full ->
            val shown = plan.nfoName ?: full.name ?: c.name
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
            dao.replaceSagaParts(c.id, CollectionRules.parts(c.id, full))
        }
        return c.id
    }

    companion object {
        /** This copy's edition: the NFO's (which belongs to the file, whatever it describes), else the file name's. */
        fun editionOf(item: ItemEntity, nfo: NfoInfo?): String? =
            nfo?.edition?.let(Editions::of) ?: item.filePath?.let(FilenameParser::edition)
    }
}
