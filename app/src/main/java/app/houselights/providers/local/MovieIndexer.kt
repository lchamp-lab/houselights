package app.houselights.providers.local

import app.houselights.core.Editions
import app.houselights.core.NfoInfo
import app.houselights.core.yearOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Indexes films: file, NFO and artwork, TMDB match and collection. */
internal class MovieIndexer(private val dao: LibraryDao, private val files: ScanFiles, private val clock: () -> Long) {
    /** Collections already fetched from TMDB during the current pass. */
    private val refreshedCollections = mutableSetOf<Long>()

    fun startPass() = refreshedCollections.clear()

    suspend fun process(pass: ScanPass, file: RemoteFile, existing: ItemEntity?) {
        val source = pass.source
        val now = clock()
        // A multi-file film is named without the part suffix and read through its first file.
        val stack = pass.stacks.stackAt(file.relativePath)
        val named = stack?.namedPath ?: file.relativePath
        val parsed = LibraryScanner.parseMovie(named)
        val names = NameIds.ofFilm(named, pass.singleTitleFolders)
        val local = pass.sidecars.forVideo(file)
        val born = if (existing == null) inserted(pass, file, parsed, stack, now) else null
        val row = existing ?: checkNotNull(born).row
        // Its TMDB id before this read: one it gains gives it back what was kept under it ([giveBack]).
        val was = row.userTmdbId ?: row.tmdbId

        val kept = files.nfo(source, local.nfo, row)
        val nfo = kept.info
        val asked = asked(pass, row, existing, nfo, local)
        val query = Query(row.userTmdbId, names, nfo, named, known = row.tmdbId ?: born?.lent)
        // The probe reads the NAS while TMDB answers; the file's tracks and its match are then saved at once.
        val (probed, answers) = coroutineScope {
            val probed = async { files.withMediaInfo(source, file, row) }
            val answers = if (asked.write) fetch(pass, row, query, asked) else null
            probed.await() to answers
        }
        val fetched = answers ?: run {
            // Nothing usable (e.g. an unparsable release NFO): still record the sidecar key so scans skip it.
            val read = SidecarKey.of(local.copy(nfo = local.nfo.takeUnless { kept.stale }))
            if (probed != row || read != probed.sidecarKey) dao.saveScanned(probed.copy(sidecarKey = read))
            // Nothing asked of TMDB nor read from an NFO: a film neither names may be the one kept in its folder.
            if (was == null) givenBackByName(probed)
            return
        }
        val item = namedAsAFilm(cleared(probed, parsed, fetched, asked), file, parsed, fetched)
        val art = files.copyArt(source, local)
        val read = SidecarKey.of(files.sidecarsRead(local, kept, art))
        saveMovie(item.copy(nfoPath = kept.path, nfo = kept.text, sidecarKey = read), nfo, fetched, art, now, was)
    }

    /**
     * [item] without the details it had when they are no longer its: its sidecars removed, or TMDB naming it anew, by
     * its name's id or its NFO's, as another film than it was taken for. Unanswered, the next scan queries TMDB again.
     */
    private fun cleared(item: ItemEntity, parsed: ParsedMovie, fetched: Fetched, asked: Asked): ItemEntity {
        val anew = fetched.movie?.id.let { it != null && item.tmdbId != null && it != item.tmdbId }
        if (!asked.sidecarsRemoved && !anew) return item
        val cleared = MetadataMerge.withoutDetails(item, parsed.title, parsed.year)
        return if (fetched.answered) cleared else cleared.copy(matchedAt = null)
    }

    /** A new film's [row], and the TMDB id a film of its name kept in its folder [lent] it ([inserted]). */
    private class Born(val row: ItemEntity, val lent: Int?)

    /**
     * A new film's row, born with what was kept of the film of its name, before its NFO and TMDB are read
     * ([KeptStates.atBirth]): TMDB, or an NFO beside it, names it once read, which may be another film of that name.
     * A film TMDB matched, kept in its folder, lends it its TMDB id, tried after the name's and NFO's ids and before
     * any search ([lookupMovie]): a name TMDB hesitates on ("Dune.mkv") comes back as the film it was.
     */
    private suspend fun inserted(pass: ScanPass, file: RemoteFile, parsed: ParsedMovie, stack: Stack?, now: Long): Born {
        val source = pass.source
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
        val path = file.relativePath
        val named = dao.keptNamed(ItemKind.MOVIE, KeptKeys.filmName(path, stacked = stack != null))
        val namedLater = pass.tmdb.open || pass.sidecars.forVideo(file).nfo != null
        val folder = Sidecars.folderOf(path)
        val kept = KeptStates.atBirth(named, source.id, folder, namedLater)
            ?: return Born(item.copy(id = dao.insert(item)), lent = KeptStates.fromHere(named, source.id, folder)?.identity)
        val born = KeptStates.onto(item, kept)
        return Born(born.copy(id = dao.insertGivenBack(born, kept)), lent = null)
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
        // Its name gives another id than it was found by ([NameIds.dueFor]).
        val hinted = item.id in pass.hinted
        val tmdb = open && (item.matchedAt == null || pass.refreshMetadata || newIds || undecided(item, nfo) || removed || hinted)
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
        val path = item.namedPath() ?: return {}
        val parsed = LibraryScanner.parseMovie(path)
        val nfo = item.nfoInfo()
        val query = Query(null, namesOf(item), nfo, path, known = null)
        val fetched = fetch(TmdbPass(tmdb, strict = true), tmdbId, query, refresh = false) { client ->
            if (tmdbId != null) Looked(client.movie(tmdbId)) else lookupMovie(client, query)
        }
        return {
            dao.setUserTmdbId(item.id, tmdbId)
            if (fetched.movie?.id != item.tmdbId) dao.clearUserArtwork(item.id)
            dao.item(item.id)?.let { fresh ->
                val cleared = MetadataMerge.withoutDetails(fresh, parsed.title, parsed.year)
                saveMovie(cleared, nfo, fetched, LocalArt.of(fresh), clock(), was = item.userTmdbId ?: item.tmdbId)
            }
        }
    }

    /**
     * Asks TMDB again for a film to identify ([Reidentify]) by what its row holds, never reading the NAS; null when
     * TMDB did not answer. Its writes, true when the film changed: found at last, it is saved as an identification
     * saves it, given back what was kept of it; still not found, only its state, its candidates and when it was asked.
     */
    suspend fun recheck(item: ItemEntity, tmdb: TmdbPass): (suspend () -> Boolean)? {
        val path = item.namedPath() ?: return null
        val parsed = LibraryScanner.parseMovie(path)
        val nfo = item.nfoInfo()
        val query = Query(null, namesOf(item), nfo, path, known = null)
        val fetched = fetch(tmdb, null, query, refresh = false) { client -> lookupMovie(client, query) }
        val lookup = fetched.lookup as? TmdbAnswer.Given ?: return null
        val now = clock()
        if (fetched.movie == null) return { dao.rechecked(item, lookup.value?.offered.orEmpty(), now) }
        return {
            dao.item(item.id)?.let { fresh ->
                saveMovie(MetadataMerge.withoutDetails(fresh, parsed.title, parsed.year), nfo, fetched, LocalArt.of(fresh), now, was = null)
            }
            true
        }
    }

    /** The path a film's row is named by: its file's, a stack's without its part ([Stacks.filmPath]). */
    private fun ItemEntity.namedPath(): String? = filePath?.let { Stacks.filmPath(it, parts.isNotEmpty()) }

    /** [item]'s name ids, its folder's when it holds that film alone among the films known there ([NameIds.ofFilm]). */
    private suspend fun namesOf(item: ItemEntity): NameIds {
        val path = item.namedPath() ?: return NameIds()
        val film = NameIds.filmPath(path)
        val beside = dao.keptRowsIn(item.sourceId, Sidecars.folderOf(film))
            .mapNotNull { row -> row.filePath?.let { NameIds.filmPath(Stacks.filmPath(it, row.parts.isNotEmpty())) } }
        return NameIds.ofFilm(path, ScanTitles.holdingOneFilm(beside + film))
    }

    /**
     * What a film's lookup asks TMDB by ([lookupMovie]), named at [path] ([Stack.namedPath]): [known] is its TMDB id
     * before, or the one a film of its name kept in its folder lends it ([inserted]).
     */
    private class Query(val userTmdbId: Int?, val names: NameIds, val nfo: NfoInfo?, path: String, val known: Int?) {
        val parsed = LibraryScanner.parseMovie(path)

        /** The title and year it is searched by. */
        val title: String get() = nfo?.title ?: parsed.title
        val year: Int? get() = nfo?.year ?: parsed.year

        /** What it is searched by, the bare year and its folder's year read too ([wanted]). */
        val wanted = wanted(path, nfo)
    }

    /**
     * Tries, highest first, the user's match; the name's TMDB id, then its IMDb id; the NFO's, likewise; the existing
     * match ([Query.known]); then a scored search ([TmdbClient.matchMovie]), a film a name's id found but did not trust
     * offered first. An id TMDB has no film for gives way to the next, as does a name's id not trusted
     * ([NameIds.trusts]). The name's ids are tried first, so the lookup carries the name's id it tried.
     */
    private suspend fun lookupMovie(tmdb: TmdbClient, query: Query): Looked<TmdbMovie> {
        query.userTmdbId?.let { return Looked(tmdb.movieById(it)) }
        val names = query.names
        val nfo = query.nfo
        val hint = names.key(show = false)
        val steps = listOf(
            IdStep(IdKind.TMDB) { names.tmdb },
            IdStep(IdKind.IMDB) { names.imdb?.let { tmdb.findByImdb(it).first } },
            IdStep(null) { nfo?.tmdbId },
            IdStep(null) { nfo?.imdbId?.let { tmdb.findByImdb(it).first } },
            IdStep(null) { query.known },
        )
        val byIds = lookUp(hint, steps, tmdb::movieById, TmdbMovie::id) { movie, by -> trusted(tmdb, movie, by, query) }
        byIds.found?.let { return it }
        val matched = tmdb.matchMovie(query.wanted, first = byIds.untrusted?.let(Candidate::of))
        return Looked(matched.found, hint, offered = matched.offered)
    }

    /** Whether [movie], found by [query]'s name's id of kind [by], is the film of that name ([NameIds.trusts]). */
    private suspend fun trusted(tmdb: TmdbClient, movie: TmdbMovie, by: IdKind, query: Query): Boolean {
        val names = query.names
        val ids = TitleIds(movie.id, imdb = movie.imdbId ?: names.imdb.takeIf { by == IdKind.IMDB }, tvdb = null)
        val found = NameIds.Found(ids, listOfNotNull(movie.title, movie.originalTitle), yearOf(movie.releaseDate)) {
            tmdb.alternativeTitles(movie.id, show = false)
        }
        val wanted = listOfNotNull(query.title, query.parsed.title, query.nfo?.originalTitle).distinct()
        return names.trusts(found, by, query.nfo, wanted, query.year)
    }

    /** TMDB answers for a film, all fetched before anything is written. */
    private class Fetched(
        /** Null when TMDB was not asked for the film. */
        val lookup: TmdbAnswer<Looked<TmdbMovie>>?,
        /** The NFO, unless it describes another title than the match. */
        val nfo: NfoInfo?,
        val collection: CollectionPlan?,
        val collectionDetails: TmdbAnswer<TmdbCollection>,
        /** Picks certification and logo for the device's region and language. */
        val client: TmdbClient?,
        /** A refresh, or an NFO set aside until TMDB confirms it: if unanswered, a later pass asks again. */
        val askAgain: Boolean,
    ) {
        val movie: TmdbMovie? get() = lookup?.value?.value

        /** TMDB answered every request, so the film counts as matched until a refresh. */
        val answered: Boolean
            get() = lookup is TmdbAnswer.Given && (collection?.fetch == null || collectionDetails is TmdbAnswer.Given)
    }

    /** TMDB's answers for [item] as [asked] needs them. */
    private suspend fun fetch(pass: ScanPass, item: ItemEntity, query: Query, asked: Asked): Fetched {
        val lookup: (suspend (TmdbClient) -> Looked<TmdbMovie>)? = if (asked.tmdb) { client -> lookupMovie(client, query) } else null
        // No TMDB request and unchanged NFO ids: keep the collection; a film without one gets it from its NFO.
        val keepCollection = lookup == null && !asked.newIds && item.collectionId != null
        return fetch(pass.tmdb, item.userTmdbId, query, pass.refreshMetadata, keepCollection = keepCollection, lookup = lookup)
    }

    /** Runs [lookup] (skipped when null), then fetches collection details if needed, unless [keepCollection]. */
    private suspend fun fetch(
        tmdb: TmdbPass,
        userTmdbId: Int?,
        query: Query,
        refresh: Boolean,
        keepCollection: Boolean = false,
        lookup: (suspend (TmdbClient) -> Looked<TmdbMovie>)?,
    ): Fetched {
        val answer = lookup?.let { tmdb.ask(it) }
        val movie = answer?.value?.value
        val nfoInfo = query.nfo
        // For a user match with no TMDB answer, an IMDb-only NFO is set aside until TMDB can confirm it.
        val untold = userTmdbId != null && movie == null && nfoInfo?.tmdbId == null && nfoInfo?.imdbId != null
        // The film the user or the name chose: an NFO naming another describes another film. Until TMDB answers, the
        // name's TMDB id stands.
        val chosen = userTmdbId ?: if (answer is TmdbAnswer.Given) answer.value?.byName else query.names.tmdb
        val nfo = nfoInfo?.takeUnless { untold || MetadataMerge.describesOther(it, chosen, movie?.imdbId) }
        val plan = if (keepCollection) null else collectionOf(nfo, movie, refresh)
        val details = plan?.fetch?.let { id -> tmdb.ask { it.collection(id) } } ?: TmdbAnswer.Unanswered
        return Fetched(answer, nfo, plan, details, tmdb.client, askAgain = refresh || (untold && answer !is TmdbAnswer.Given))
    }

    /**
     * Saves details ([MetadataMerge]), edition, collection and credits ([CreditRules]) at once: a film matched without
     * its credits would never be read again for them; nor one named since [was], its TMDB id then, without what was kept.
     */
    @Suppress("LongParameterList") // One per source of details, and the TMDB id the film had.
    private suspend fun saveMovie(
        base: ItemEntity,
        nfoInfo: NfoInfo?,
        fetched: Fetched,
        art: LocalArt,
        now: Long,
        was: Int?,
    ) = dao.inTransaction {
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
            .lookedUp(base, fetched.lookup)
            .withNfoStreams(nfoInfo) // Stream details do not depend on the match.
        dao.saveScanned(item)
        dao.offer(item, fetched.lookup)
        giveBack(item, was, fetched)
        // Complete when TMDB answered or the film has no TMDB id, so the backfill skips it.
        val credits = CreditRules.credits(item.id, show = false, nfo, movie?.let { TmdbPeople(it.credits) })
        val answered = fetched.lookup is TmdbAnswer.Given
        dao.saveCredits(item.id, credits, tmdbAnswered = answered, complete = answered || item.tmdbId == null, at = now)
    }

    /**
     * Gives the film what was kept of its title: under the TMDB id it gained since [was] ([LibraryDao.giveBack]), in
     * the transaction writing it; or, TMDB knowing it by no id or not asked (its NFO naming none), what was kept in
     * its folder by its name. Asked without an answer, TMDB may yet name it: a later pass asks again.
     */
    private suspend fun giveBack(item: ItemEntity, was: Int?, fetched: Fetched) {
        val identity = item.userTmdbId ?: item.tmdbId
        if (identity == null) {
            if (fetched.lookup !is TmdbAnswer.Unanswered) givenBackByName(item)
        } else if (identity != was) {
            dao.giveBack(item.id, KeptKeys.film(identity))
        }
    }

    /** What was kept of a film of [item]'s name in its folder ([KeptStates.fromHere]). */
    private suspend fun givenBackByName(item: ItemEntity) {
        val path = item.filePath ?: return
        val named = dao.keptNamed(ItemKind.MOVIE, KeptKeys.filmName(path, item.parts.isNotEmpty()))
        KeptStates.fromHere(named, item.sourceId, Sidecars.folderOf(path))?.let { dao.giveBack(item.id, it.title) }
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
        /**
         * What a film named at [path] ([Stack.namedPath]) is searched by ([Wanted]): its NFO's title and year, else its
         * name's; a bare year read as part of the title too ("Wonder Woman 1984"), and then its folder's title and year
         * when they are in parentheses ("Death Race 2000 (1975)/Death Race 2000.mkv").
         */
        fun wanted(path: String, nfo: NfoInfo?): Wanted {
            val parsed = LibraryScanner.parseMovie(path)
            val year = nfo?.year ?: parsed.year
            nfo?.title?.let { return Wanted.of(it, year, original = nfo.originalTitle) }
            val named = NameIds.filmPath(path)
            val file = named.substringAfterLast('/')
            val folder = Sidecars.folderOf(named).substringAfterLast('/').ifEmpty { null }
            // The year parseMovie took: the file's, else its folder's.
            val bare = FilenameParser.bareYear(if (FilenameParser.parseMovie(file).year != null || folder == null) file else folder)
            val beside = folder?.takeIf { bare && !FilenameParser.bareYear(it) }?.let(FilenameParser::parseMovie)
                ?.takeIf { it.year != null && it.year != parsed.year }
            return Wanted.of(
                parsed.title, year, bareYear = bare && nfo?.year == null, original = nfo?.originalTitle,
                folder = beside?.let { Reading(it.title, it.year) },
            )
        }

        /** This copy's edition: the NFO's (which belongs to the file, whatever it describes), else the file name's. */
        fun editionOf(item: ItemEntity, nfo: NfoInfo?): String? =
            nfo?.edition?.let(Editions::of) ?: item.filePath?.let(FilenameParser::edition)
    }
}
