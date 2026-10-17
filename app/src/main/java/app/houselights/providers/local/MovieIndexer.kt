package app.houselights.providers.local

import app.houselights.core.NfoInfo

/** Films: a file, its NFO and artwork, its TMDB match and its collection. */
internal class MovieIndexer(private val dao: LibraryDao, private val files: ScanFiles) {
    /** Collections already asked to TMDB during the current scan. */
    private val refreshedCollections = mutableSetOf<Long>()

    /** A new scan asks TMDB again. */
    fun startPass() = refreshedCollections.clear()

    /** The film at [file] in the [pass]'s folder, [existing] its row when the library knows it. */
    suspend fun process(pass: ScanPass, file: RemoteFile, existing: ItemEntity?) {
        val source = pass.source
        val now = System.currentTimeMillis()
        // A film in several files is named without the part, and read through its first.
        val stack = pass.stacks.stackAt(file.relativePath)
        val parsed = LibraryScanner.parseMovie(stack?.namedPath ?: file.relativePath)
        val local = pass.sidecars.forVideo(file)
        var item = files.withMediaInfo(source, file, existing ?: inserted(source, file, parsed, stack, now))

        val kept = files.nfo(source, local.nfo, item)
        val nfo = kept.info
        val asked = asked(pass, item, existing, nfo, local)
        if (!asked.write) {
            // Nothing beside it to use (a release NFO no parser reads): what was read is noted all the same,
            // so that the next scans read it no more.
            val read = SidecarKey.of(local.copy(nfo = local.nfo.takeUnless { kept.stale }))
            if (read != item.sidecarKey) dao.saveScanned(item.copy(sidecarKey = read))
            return
        }

        val known = item
        val lookup: (suspend (TmdbClient) -> TmdbMovie?)? =
            if (asked.tmdb) { client -> lookupMovie(client, known.userTmdbId, nfo, parsed, known = known.tmdbId) } else null
        // TMDB not asked, and the NFO's set as it was: the film stays in its collection. One out of any
        // (version 10 took it out of a saga whose name gave no key) finds it again from its NFO, without TMDB.
        val keepCollection = lookup == null && !asked.newIds && item.collectionId != null
        val fetched = fetch(pass.tmdb, item.userTmdbId, nfo, pass.refreshMetadata, keepCollection = keepCollection, lookup = lookup)
        // Details dropped and none from TMDB: the next scan asks it again.
        if (asked.sidecarsRemoved) {
            item = MetadataMerge.withoutDetails(item, parsed.title, parsed.year)
                .let { if (fetched.answered) it else it.copy(matchedAt = null) }
        }
        item = namedAsAFilm(item, file, parsed, fetched)
        val art = files.copyArt(source, local)
        val read = SidecarKey.of(files.sidecarsRead(local, kept, art))
        saveMovie(item.copy(nfoPath = kept.path, nfo = kept.text, sidecarKey = read), nfo, fetched, art, now)
    }

    /** The row of a film new to the library, written: named as [parsed], a film in several files ([stack]) without its part. */
    private suspend fun inserted(source: SourceEntity, file: RemoteFile, parsed: ParsedMovie, stack: Stack?, now: Long): ItemEntity {
        val item = ItemEntity(
            kind = ItemKind.MOVIE,
            sourceId = source.id,
            title = parsed.title,
            sortTitle = MetadataMerge.sortKey(parsed.title),
            year = parsed.year,
            filePath = file.relativePath,
            // An existing row's parts follow its folder before it is processed (see MissingFiles).
            parts = stack?.rest.orEmpty().map { it.relativePath },
            fileSize = file.size,
            badges = FilenameParser.badges(file.relativePath),
            addedAt = now,
        )
        return item.copy(id = dao.insert(item))
    }

    /** What reading a film again asks for ([asked]). */
    private class Asked(
        /** TMDB is asked of the film. */
        val tmdb: Boolean,
        /** Its NFO names another title or set than the one read before. */
        val newIds: Boolean,
        /** Sidecars disappeared from beside it ([sidecarsRemoved]). */
        val sidecarsRemoved: Boolean,
        /** Something to write beyond what was read: details, artwork, or a refresh left for a later pass. */
        val write: Boolean,
    )

    /** What reading the film [item] ([existing]: as the pass found it) asks for, its [nfo] as read and [local] beside it now. */
    private fun asked(pass: ScanPass, item: ItemEntity, existing: ItemEntity?, nfo: NfoInfo?, local: VideoSidecars): Asked {
        val open = pass.tmdb.open
        // An NFO read again (rewritten in place, the video replaced) asks TMDB only when it names another title or set.
        val newIds = nfo != null && !sameIds(existing?.nfoInfo(), nfo)
        val removed = sidecarsRemoved(item, local)
        val tmdb = open && (item.matchedAt == null || pass.refreshMetadata || newIds || undecided(item, nfo) || removed)
        // A refresh TMDB cannot be asked for now is not lost: the film is left unmatched (see saveMovie).
        val refreshLeft = pass.refreshMetadata && !open && item.matchedAt != null
        return Asked(tmdb, newIds, removed, write = nfo != null || tmdb || refreshLeft || removed || local.hasArt)
    }

    /** A poster, a fanart or a logo lies beside the film. */
    private val VideoSidecars.hasArt: Boolean get() = poster != null || fanart != null || logo != null

    /**
     * A film the user matched, beside an NFO naming a title by its IMDb id alone: only TMDB's answer tells
     * whether the NFO describes the user's choice (see MetadataMerge.describesOther).
     */
    private fun undecided(item: ItemEntity, nfo: NfoInfo?): Boolean =
        nfo != null && item.userTmdbId != null && nfo.tmdbId == null && nfo.imdbId != null

    /**
     * Sidecars that disappeared from beside the film [item] since they were read ([local]: what lies there now)
     * leave details to TMDB again: an image among them, whose copy the film would show otherwise (nothing else
     * copies one there).
     */
    private fun sidecarsRemoved(item: ItemEntity, local: VideoSidecars): Boolean =
        (item.nfoPath != null && local.nfo == null) ||
            (local.poster == null && item.posterPath.isCachedArtwork()) ||
            (local.fanart == null && item.backdropPath.isCachedArtwork()) ||
            (local.logo == null && item.logoPath.isCachedArtwork())

    /**
     * A row named by its first file alone ("Heat CD1", scanned before its other parts were there) that has
     * become a film in parts takes the film's name ([film], "Heat"), when neither its NFO nor TMDB names it.
     * A name the NFO or TMDB gave it before stays.
     */
    private fun namedAsAFilm(item: ItemEntity, file: RemoteFile, film: ParsedMovie, fetched: Fetched): ItemEntity {
        val unnamed = fetched.nfo?.title == null && fetched.movie == null
        val ownName = item.title == LibraryScanner.parseMovie(file.relativePath).title
        if (item.parts.isEmpty() || !unnamed || !ownName) return item
        return item.copy(title = film.title, sortTitle = MetadataMerge.sortKey(film.title))
    }

    /** [known] and [nfo] name the same title and the same set: TMDB has nothing new to say of the film. */
    private fun sameIds(known: NfoInfo?, nfo: NfoInfo): Boolean =
        known != null && known.tmdbId == nfo.tmdbId && known.imdbId == nfo.imdbId &&
            known.collection == nfo.collection && known.collectionTmdbId == nfo.collectionTmdbId

    /**
     * Asks TMDB all a new match needs (the film, its collection's details), then returns the writes
     * that apply it, for the caller to run at once ([LibraryScanner.identify]): TMDB out of reach
     * throws before anything is written.
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
        /**
         * TMDB's word is wanted: for a full refresh, or for an NFO set aside until TMDB tells whether it describes
         * the user's match. Unanswered, the film is asked again at a later pass, which decides for the NFO as it is then.
         */
        val askAgain: Boolean,
    ) {
        val movie: TmdbMovie? get() = lookup.value

        /** TMDB answered all it was asked: the film is matched, and not asked again before a refresh. */
        val answered: Boolean
            get() = lookup is TmdbAnswer.Given && (collection?.fetch == null || collectionDetails is TmdbAnswer.Given)
    }

    /**
     * The film [lookup] finds (not asked when null), then its collection's details when they are
     * wanted: from TMDB's film, or from the NFO's set; none when [keepCollection].
     */
    private suspend fun fetch(
        tmdb: TmdbPass,
        userTmdbId: Int?,
        nfoInfo: NfoInfo?,
        refresh: Boolean,
        keepCollection: Boolean = false,
        lookup: (suspend (TmdbClient) -> TmdbMovie?)?,
    ): Fetched {
        val answer = lookup?.let { tmdb.ask(it) } ?: TmdbAnswer.Unanswered
        // Matched by hand, a film TMDB gives nothing for now keeps what it had: an NFO only TMDB's IMDb id
        // can tell of waits for it, rather than its title, plot and cast win meanwhile.
        val untold = userTmdbId != null && answer.value == null && nfoInfo?.tmdbId == null && nfoInfo?.imdbId != null
        val nfo = nfoInfo?.takeUnless { untold || MetadataMerge.describesOther(it, userTmdbId, answer.value?.imdbId) }
        val plan = if (keepCollection) null else collectionOf(nfo, answer.value, refresh)
        val details = plan?.fetch?.let { id -> tmdb.ask { it.collection(id) } } ?: TmdbAnswer.Unanswered
        return Fetched(answer, nfo, plan, details, tmdb.client, askAgain = refresh || (untold && answer !is TmdbAnswer.Given))
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
        // Until TMDB answers, the next scans ask it again; a refresh it did not answer, and an NFO waiting for its word, too.
        val matchedAt = when {
            fetched.answered -> now
            fetched.askAgain -> null
            else -> base.matchedAt
        }
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
