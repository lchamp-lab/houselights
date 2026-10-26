package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.yearOf

/** Indexes episodes with their season and show: NFOs, artwork and TMDB match. */
internal class ShowIndexer(private val dao: LibraryDao, private val files: ScanFiles, private val clock: () -> Long) {
    /** Shows already read during the current pass. */
    private val refreshedSeries = mutableSetOf<Long>()

    /** Seasons already refreshed from TMDB during the current pass. */
    private val refreshedSeasons = mutableSetOf<Long>()

    /** Shows whose last episode went to another show this pass, their user state with it ([takenOver]). */
    private val handedOver = mutableSetOf<Long>()

    /** Places a show's episodes anew, once read ([settle]). */
    private val placing = EpisodePlacing(dao, clock)

    /**
     * Shows TMDB named anew while one of their episodes was read, whose rows [refreshSeasons] then rewrote, or placed anew
     * ([settle]).
     */
    private val namedAnew = mutableSetOf<Long>()

    /**
     * Shows whose TMDB seasons were counted anew this pass and count otherwise ([saveShow]): their numbers from the start
     * are placed again ([settle]).
     */
    private val recounted = mutableSetOf<Long>()

    fun startPass() {
        refreshedSeries.clear()
        refreshedSeasons.clear()
        handedOver.clear()
        namedAnew.clear()
        recounted.clear()
    }

    /** Whether show [seriesId] was read this pass (sidecars, and TMDB when due). */
    fun readThisPass(seriesId: Long): Boolean = seriesId in refreshedSeries

    /** Shows emptied this pass whose user state another show took ([LibraryDao.deleteEmptyShows] keeps nothing of them). */
    fun handedOver(): Set<Long> = handedOver.toSet()

    suspend fun process(pass: ScanPass, file: RemoteFile, existing: ItemEntity?) {
        val source = pass.source
        val now = clock()
        val path = FilenameParser.parseShowPath(file.relativePath) ?: return

        val series = series(pass, path, existing, now, NameIds.ofShow(file.relativePath))
        val current = current(existing, series)
        val placed = placed(pass, series, path.episode, current)
        val season = season(pass, series, SeasonAt(placed.placement.season, path.dir, placed.order), now)
        // Episodes only use their NFO and thumb (see SidecarKey.listed).
        val local = pass.sidecars.forVideo(file).let { VideoSidecars(nfo = it.nfo, thumb = it.thumb) }
        val base = current ?: ItemEntity(
            kind = ItemKind.EPISODE,
            sourceId = source.id,
            title = "",
            sortTitle = "",
            filePath = file.relativePath,
            fileSize = file.size,
            badges = FilenameParser.badges(file.relativePath),
            addedAt = now,
        )
        val kept = files.nfo(source, local.nfo, base)
        val nfo = kept.info
        // Shown anew in another TMDB episode, as when TMDB lists one waiting at last: what it showed goes.
        val moved = current != null && placed.placement.tmdb != current.tmdbNumber()
        val (meta, answered) = shownBy(pass, series, base, placed, moved)
        val art = files.copyArt(source, local)

        val episode = base.at(placed, season.id, series.id).copy(
            nfoPath = kept.path,
            nfo = kept.text,
            sidecarKey = SidecarKey.of(files.sidecarsRead(local, kept, art)),
        ).let { if (moved) it.withoutDetails() else it }.withEpisodeDetails(
            nfo = nfo,
            meta = meta,
            thumb = art.thumb,
            // Unanswered refreshes and episodes of unmatched shows stay unmatched, so a later pass retries the show.
            matchedAt = if (answered) now else base.matchedAt.takeUnless { pass.refreshMetadata || series.matchedAt == null },
        )
        val stale = MediaProbe.isStale(episode.mediaInfo) || episode.sizeChanged(file)
        val probed = (if (stale) files.mediaFields(source, file, episode) else episode).withNfoStreams(nfo)
        if (existing == null) inserted(pass, series, probed, now) else dao.saveScanned(probed)
    }

    /** [existing] as its show left it when named or placed anew as it was read ([namedAnew]): nothing of the other show. */
    private suspend fun current(existing: ItemEntity?, series: ItemEntity): ItemEntity? {
        existing ?: return null
        return if (namedAnew.remove(series.id)) dao.item(existing.id) ?: existing else existing
    }

    /**
     * The TMDB episode [placed] shows, fetched when its season is wanted ([seasonWanted]) or the episode is [moved] to it,
     * and whether it is matched: TMDB answered for its show, its numbering and, when it shows one, its season.
     */
    private suspend fun shownBy(
        pass: ScanPass,
        series: ItemEntity,
        base: ItemEntity,
        placed: Placed,
        moved: Boolean,
    ): Pair<TmdbEpisode?, Boolean> {
        val tmdb = placed.placement.tmdb
        val answer = series.tmdbId?.takeIf { moved || seasonWanted(base, series, pass.refreshMetadata) }
            ?.let { id -> tmdb?.let { pass.seasons.get(id, it.season) } }
        val meta = tmdb?.let { shown -> answer?.value?.episodes?.firstOrNull { it.episodeNumber == shown.episode } }
        return meta to (placed.numbered && answered(pass.tmdb, series, answer, tmdb))
    }

    /** This episode at [placed]'s place, in season [seasonId] of show [seriesId], its TMDB episode kept apart as its order says. */
    private fun ItemEntity.at(placed: Placed, seasonId: Long, seriesId: Long): ItemEntity {
        val placement = placed.placement
        val keeps = placed.keepsTmdb
        return copy(
            parentId = seasonId,
            seriesId = seriesId,
            seasonNumber = placement.season,
            episodeNumber = placement.episode,
            lastEpisodeNumber = placement.last,
            sortTitle = placement.sortTitle,
            absoluteNumber = placement.absolute,
            tmdbSeason = placement.tmdb?.season?.takeIf { keeps },
            tmdbEpisode = placement.tmdb?.episode?.takeIf { keeps },
            tmdbLastEpisode = placement.tmdbLast?.takeIf { keeps },
        )
    }

    /**
     * Where [parsed] goes in [series] ([EpisodeNumbering]), a place held by another file refused ([EpisodeNumbering.place]);
     * not numbered when TMDB had to be asked and did not answer: [current] then stays where it was, a new episode goes
     * where its name puts it, both for a later pass.
     */
    private suspend fun placed(pass: ScanPass, series: ItemEntity, parsed: ParsedEpisode, current: ItemEntity?): Placed {
        val numbering = pass.orders.of(series, EpisodeNumbering.needsAbsolute(parsed)).value
        if (numbering != null) {
            val key = parsed.key()
            val placement = EpisodeNumbering.place(parsed, numbering) { place ->
                dao.episodesAt(series.id, place.season, place.episode).any { it.id != current?.id && fileKeyOf(it.filePath) != key }
            }
            return Placed(placement, numbering.order, numbered = true, keepsTmdb = placement.keptTmdb(numbering.order))
        }
        val unknown = ShowNumbering.UNKNOWN.order
        val named = current?.placement() ?: EpisodeNumbering.place(parsed, ShowNumbering.UNKNOWN) { false }
        // Kept as it was: a TMDB episode of its own stays, as in an order of its show's ([ItemEntity.episodeOrder]). A new
        // one shows none yet, so Trakt is never sent its name's numbers in an order where they name another episode.
        val keeps = current != null && (series.episodeOrder != null || named.keptTmdb(unknown))
        return Placed(named, unknown, numbered = false, keepsTmdb = keeps)
    }

    /**
     * Where an episode goes ([placement]), the [order] that put it there, whether TMDB answered for its numbering
     * ([numbered]), and whether its TMDB episode is kept apart ([keepsTmdb], [keptTmdb]).
     */
    private class Placed(val placement: Placement, val order: EpisodeOrder, val numbered: Boolean, val keepsTmdb: Boolean)

    /** A season's [number] in [order], its show's folder [dir] holding its poster. */
    private class SeasonAt(val number: Int, val dir: String, val order: EpisodeOrder)

    /**
     * Inserts a new episode, given back what was kept of it ([keptEpisode]): a return. Else it is new, and moves its
     * show up in "recently added".
     */
    private suspend fun inserted(pass: ScanPass, series: ItemEntity, episode: ItemEntity, now: Long) {
        val kept = keptEpisode(pass, series, episode)
        if (kept != null) {
            dao.insertGivenBack(KeptStates.onto(episode, kept), kept)
        } else {
            dao.insert(episode)
            dao.setAddedAt(series.id, now)
        }
    }

    /**
     * What was kept of [episode]'s season and episodes in [series]: under its show's TMDB id, else under its show's
     * key, one alone ([KeptStates.forEpisode]), which waits while TMDB may still name the show.
     */
    private suspend fun keptEpisode(pass: ScanPass, series: ItemEntity, episode: ItemEntity): KeptStateEntity? {
        val span = episode.keptSpan() ?: return null
        val show = series.userTmdbId ?: series.tmdbId
        show?.let { dao.keptState(KeptKeys.episode(it, span)) }?.let { return it }
        val key = series.matchKey ?: return null
        val pending = show == null && pass.tmdb.open && series.matchedAt == null
        return KeptStates.forEpisode(dao.keptNamed(ItemKind.EPISODE, KeptKeys.episodeName(key, span)), show, pending)
    }

    /** Season details are needed for a new or unmatched episode, a refresh, or a show re-matched since. */
    private fun seasonWanted(episode: ItemEntity, series: ItemEntity, refreshMetadata: Boolean): Boolean {
        val matched = episode.matchedAt ?: return true
        return refreshMetadata || (series.matchedAt ?: 0) > matched
    }

    /**
     * Matched once the show was looked up and, if TMDB knows the show, its season answered, or it shows no TMDB episode
     * yet ([shown], one waiting for TMDB to list it).
     */
    private fun answered(tmdb: TmdbPass, series: ItemEntity, season: TmdbAnswer<TmdbSeason>?, shown: TmdbNumber?): Boolean {
        val showAnswered = tmdb.client != null && series.matchedAt != null
        return showAnswered && (series.tmdbId == null || shown == null || season is TmdbAnswer.Given)
    }

    private suspend fun series(pass: ScanPass, path: ShowPath, existing: ItemEntity?, now: Long, names: NameIds): ItemEntity {
        val (title, year, showDir) = path
        val source = pass.source
        val tmdb = pass.tmdb
        val refreshMetadata = pass.refreshMetadata
        val local = pass.sidecars.forShow(showDir)
        // TMDB, or a tvshow.nfo beside, names a new show once read, which may be another of that name.
        val series = showOf(source, path, existing, now, namedLater = tmdb.open || local.nfo != null)
        // Its TMDB id before this read: one it gains gives it back what was kept under it ([saveShow]).
        val was = series.userTmdbId ?: series.tmdbId

        if (series.id in refreshedSeries || !due(pass, series, showDir)) return series
        // Once per pass, answered or not; an unanswered show and its episodes stay unmatched until a later pass.
        refreshedSeries += series.id

        val kept = files.nfo(source, local.nfo, series)
        val nfo = kept.info
        // As for films, removed sidecars hand details back to TMDB, dropping stale cached images.
        val removed = SidecarKey.showRemoved(series.sidecarKey, source.id, showDir, local)
        val query = Query(series.userTmdbId, names, nfo, title, year, known = null, searches = !series.userNoMatch)
        // Its folder's name gives another id than its last lookup tried.
        val asked = tmdbAsked(series, nfo, refreshMetadata, removed) || series.id in pass.hinted
        val answer = if (asked) lookedUp(pass, series, query, removed) else TmdbAnswer.Unanswered
        val (tv, seasons) = withSeasons(series, answer, pass.seasons)
        val art = files.copyArt(source, local)
        val read = series.copy(
            nfoPath = kept.path,
            nfo = kept.text,
            sidecarKey = SidecarKey.ofShow(source.id, showDir, files.sidecarsRead(local, kept, art)),
        )
        // Details dropped without a TMDB answer: a later pass queries TMDB again (see process). A show TMDB names anew
        // keeps nothing of the one it was taken for.
        val anew = seasons != null && series.tmdbId != null
        val base = if (removed || anew) MetadataMerge.withoutDetails(read, title, year).copy(matchedAt = null) else read
        val saved = saveShow(base, nfo, tv, art, tmdb.client, now, was, refreshMetadata, seasons)
        return settled(pass, saved, named = seasons != null)
    }

    /**
     * [saved] once its episodes are placed in the order they follow ([settle]), as it is now when they moved or it was
     * [named] anew; when TMDB did not answer, read again whole at the next pass, as an unanswered refresh is: matched, it
     * would never be placed.
     */
    private suspend fun settled(pass: ScanPass, saved: ItemEntity, named: Boolean): ItemEntity {
        // Named anew, its order went with the show it was taken for ([inTmdbsSeasons]).
        val series = if (named) dao.item(saved.id) ?: saved else saved
        return when (settle(pass, series)) {
            true -> dao.item(series.id) ?: series
            false -> series
            null -> series.copy(matchedAt = null).also { dao.readAgain(series.id) }
        }
    }

    /**
     * Once [series] was read: its groups listed the first time, then its episodes placed anew when the order they follow
     * is no longer the one they are in (the user's choice, its tvshow.nfo), or its numbers from the start when TMDB counts
     * its seasons otherwise. True when they moved, false when they stay; null when TMDB had to be asked and did not
     * answer, which leaves them for the next pass.
     */
    private suspend fun settle(pass: ScanPass, series: ItemEntity): Boolean? {
        val recount = recounted.remove(series.id)
        series.tmdbId?.let { pass.orders.listed(it) }
        val absolute = recount || dao.hasAbsoluteEpisodes(series.id)
        val numbering = pass.orders.of(series, absolute).value ?: return null
        val anew = numbering.order.id != (series.episodeOrder ?: EpisodeOrderEntity.AIRED)
        val episodes = when {
            anew -> dao.episodesOfSeries(series.id)
            recount -> dao.episodesOfSeries(series.id).filter { it.absoluteNumber != null }
            else -> return false
        }
        val moved = placing.placeAnew(series, numbering, episodes, pass.seasons, reordered = anew) ?: return null
        pass.orders.forget(series.id)
        if (moved) namedAnew += series.id
        return moved
    }

    /**
     * Whether [series], its folder at [showDir], is read this pass: never read, refreshed, its tvshow.nfo or artwork
     * added, rewritten in place or removed since the last read, or its folder's name giving another id than its last
     * lookup tried ([ScanPass.hinted]); while TMDB is closed, only for its sidecars or to keep a refresh for later.
     */
    private fun due(pass: ScanPass, series: ItemEntity, showDir: String): Boolean {
        val sidecarsChanged = SidecarKey.showDue(series.sidecarKey, pass.source.id, showDir, pass.sidecars)
        val needsLookup = series.matchedAt == null || pass.refreshMetadata || sidecarsChanged || series.id in pass.hinted
        // A refresh TMDB cannot serve now is kept by leaving the show unmatched (see saveShow).
        val refreshLeft = pass.refreshMetadata && series.matchedAt != null
        return needsLookup && (pass.tmdb.open || sidecarsChanged || refreshLeft)
    }

    /**
     * TMDB's answer for [series] by [query]. Keeps the match, not the details: a [removed] NFO may have been all that
     * named it. A refresh keeps the match; a show re-keyed from its path is unmatched and looked up by its new name.
     */
    private suspend fun lookedUp(pass: ScanPass, series: ItemEntity, query: Query, removed: Boolean): TmdbAnswer<Looked<TmdbTv>> {
        val known = series.tmdbId.takeIf { removed || series.matchedAt != null } ?: lent(series)
        return pass.tmdb.ask { lookupShow(it, query.copy(known = known)) }
    }

    /**
     * The TMDB id a show TMDB matched, kept by [series]'s key ([KeptStates.fromHere]), lends it while TMDB names it by
     * none, at each scan or recheck asking TMDB until one answers: tried after its folder's and NFO's ids and before
     * any search ([lookupShow]), so a name TMDB hesitates on comes back as the show it was.
     */
    private suspend fun lent(series: ItemEntity): Int? {
        val key = series.matchKey?.takeIf { series.tmdbId == null && series.userTmdbId == null } ?: return null
        return KeptStates.fromHere(dao.keptNamed(ItemKind.SERIES, key), series.sourceId, folder = null)?.identity
    }

    /**
     * [tv], with its owned seasons' details when TMDB names [series] anew, by its folder's id, its NFO's or a title
     * found at last, else none: each episode it holds takes them in the transaction saving it ([saveShow]), none read
     * again from the NAS. A season unanswered leaves the show unanswered, as it was, for a later pass.
     */
    private suspend fun withSeasons(
        series: ItemEntity,
        tv: TmdbAnswer<Looked<TmdbTv>>,
        cache: SeasonCache,
    ): Pair<TmdbAnswer<Looked<TmdbTv>>, Map<Int, TmdbSeason?>?> {
        val id = tv.value?.value?.id?.takeIf { it != series.tmdbId } ?: return tv to null
        val numbers = dao.children(series.id).filter { it.kind == ItemKind.SEASON }.mapNotNull { it.seasonNumber }
        val seasons = numbers.associateWith { cache.get(id, it) }
        if (seasons.values.any { it !is TmdbAnswer.Given }) return TmdbAnswer.Unanswered to null
        return tv to seasons.mapValues { it.value.value }
    }

    /**
     * TMDB is needed when unmatched, refreshed or [removed], when a re-read NFO names another show, or when a user
     * match has an NFO with only IMDb or TVDB ids, which only TMDB can check, as for films.
     */
    private fun tmdbAsked(series: ItemEntity, nfo: NfoInfo?, refresh: Boolean, removed: Boolean): Boolean {
        val newIds = nfo != null && !sameIds(series.nfoInfo(), nfo)
        val undecided = nfo != null && series.userTmdbId != null && nfo.tmdbId == null && (nfo.imdbId != null || nfo.tvdbId != null)
        return series.matchedAt == null || refresh || newIds || undecided || removed
    }

    /**
     * The show row: by [path]'s key ([seriesByKey]), else the episode's show keyed by another rule ([formerShow]),
     * else a new one ([newShow]). The last episode to leave a show hands over its user state ([takenOver]).
     */
    private suspend fun showOf(source: SourceEntity, path: ShowPath, existing: ItemEntity?, now: Long, namedLater: Boolean): ItemEntity {
        val key = FilenameParser.showKey(path.title, path.year)
        val nameKey = FilenameParser.showKey(path.title)
        val show = seriesByKey(key, nameKey, path.year) ?: formerShow(source, path, existing, key, nameKey)
            ?: newShow(source, path, key, now, namedLater)
        val left = existing?.seriesId?.takeIf { it != show.id } ?: return show
        return takenOver(show, left, existing.id)
    }

    /**
     * A new show's row, born with what was kept of a show of its [key] ([KeptStates.atBirth]); a show TMDB matched,
     * kept by that key, lends it its TMDB id meanwhile ([lent]).
     */
    private suspend fun newShow(source: SourceEntity, path: ShowPath, key: String, now: Long, namedLater: Boolean): ItemEntity {
        val show = ItemEntity(
            kind = ItemKind.SERIES,
            sourceId = source.id,
            matchKey = key,
            title = path.title,
            sortTitle = MetadataMerge.sortKey(path.title),
            year = path.year,
            addedAt = now,
            // New rows never need the backfill.
            creditsAt = now,
        )
        // A show's key stands for its folder, where a match by hand is given back.
        val named = dao.keptNamed(ItemKind.SERIES, key)
        val kept = KeptStates.atBirth(named, source.id, folder = null, namedLater) ?: return show.copy(id = dao.insert(show))
        val born = KeptStates.onto(show, kept)
        return born.copy(id = dao.insertGivenBack(born, kept))
    }

    /**
     * The existing show of [existing], or of new episodes beside it in the folder ([showBeside]), when its key differs
     * from the path's ("slow horses s01" for a root season pack, "animation" from a genre folder). It is re-keyed in
     * place ([key]), keeping its rows and user state, and unmatched so it is looked up by its new name; a show named
     * only by its path takes the path's name. A show still named by one of its episodes' paths keeps its key, e.g.
     * "Animation/Arcane" when "Animation/Blue Eye Samurai" shared its show.
     */
    private suspend fun formerShow(source: SourceEntity, path: ShowPath, existing: ItemEntity?, key: String, nameKey: String): ItemEntity? {
        val id = existing?.seriesId ?: showBeside(source, path.dir, nameKey) ?: return null
        val show = dao.item(id)?.takeIf { it.kind == ItemKind.SERIES } ?: return null
        val name = show.matchKey?.substringBefore(FilenameParser.YEAR_MARK)
        if (dao.episodesOfSeries(id).any { nameKeyOf(it.filePath) == name }) return null
        val named = if (show.tmdbId == null && show.nfo == null) {
            show.copy(title = path.title, sortTitle = MetadataMerge.sortKey(path.title), year = path.year)
        } else {
            show
        }
        return named.copy(matchKey = key, matchedAt = null).also { dao.saveScanned(it) }
    }

    /** The single show of known episodes under [dir] whose path names [nameKey]; null at the source root. */
    private suspend fun showBeside(source: SourceEntity, dir: String, nameKey: String): Long? {
        if (dir.isEmpty()) return null
        return dao.episodesUnder(source.id, dir).filter { nameKeyOf(it.filePath) == nameKey }.mapNotNullTo(HashSet()) { it.seriesId }
            .singleOrNull()
    }

    /** Show key without year of an episode's path ([FilenameParser.showKey]). */
    private fun nameKeyOf(filePath: String?): String? =
        filePath?.let(FilenameParser::parseShowPath)?.let { FilenameParser.showKey(it.title) }

    /**
     * When episode [episodeId] is the last to leave [formerId] for [show], hands over the former show's user state
     * ([LibraryDao.handOver]) before it is deleted, e.g. two season packs merging into one show, and nothing is kept
     * of it ([handedOver]). A user match handed over is looked up this pass or a later one.
     */
    private suspend fun takenOver(show: ItemEntity, formerId: Long, episodeId: Long): ItemEntity {
        if (dao.hasOtherEpisodes(formerId, episodeId)) return show
        dao.handOver(formerId, show.id)
        handedOver += formerId
        val taken = dao.item(show.id) ?: return show
        if (taken.userTmdbId == show.userTmdbId || taken.userTmdbId == taken.tmdbId) return taken
        refreshedSeries -= taken.id
        return taken.copy(matchedAt = null).also { dao.saveScanned(it) }
    }

    private fun sameIds(known: NfoInfo?, nfo: NfoInfo): Boolean =
        known != null && known.tmdbId == nfo.tmdbId && known.imdbId == nfo.imdbId && known.tvdbId == nfo.tvdbId

    /**
     * The show for [key]. A key with a year ("doctor who|2005") adopts the yearless show of that name, keeping its
     * rows and user state, unless that show has another year its episodes' folders give ([ofAnotherYear]). A key
     * without a year finds the single show of that name with a year, so two folders of one show stay one show;
     * with two such shows ("Doctor Who" 1963 and 2005), it becomes a show of its own.
     */
    private suspend fun seriesByKey(key: String, nameKey: String, year: Int?): ItemEntity? {
        dao.seriesByKey(key)?.let { return it }
        if (key == nameKey) return dao.seriesNamed(nameKey).singleOrNull()
        val named = dao.seriesByKey(nameKey)?.takeUnless { ofAnotherYear(it, nameKey, year) } ?: return null
        return named.copy(matchKey = key).also { dao.saveScanned(it) }
    }

    /**
     * Whether yearless [show] has a year other than [year] (NFO, TMDB or user match) that one of its episodes' folders
     * gives, e.g. "Doctor Who" matched with the 2005 series with episodes in "(1963)" and "(2005)" folders. Otherwise
     * the first year met takes the show.
     */
    private suspend fun ofAnotherYear(show: ItemEntity, nameKey: String, year: Int?): Boolean {
        val own = show.year?.takeIf { it != year } ?: return false
        return dao.episodesOfSeries(show.id).any { episode ->
            val path = episode.filePath?.let(FilenameParser::parseShowPath)
            path != null && path.year == own && FilenameParser.showKey(path.title) == nameKey
        }
    }

    /**
     * Fetches everything a new match needs (show, then each owned season), then returns the writes for the caller to
     * run in a transaction ([LibraryScanner.identify]); a TMDB failure throws before anything is written. The same show
     * in another order than TMDB's seasons keeps its episodes where they are, for its order to place them again
     * ([EpisodePlacing.reorder]): left as they were should that fail.
     */
    suspend fun identify(series: ItemEntity, tmdbId: Int?, tmdb: TmdbClient): suspend () -> Unit {
        val (title, year) = showQuery(series)
        val nfo = series.nfoInfo()
        val query = Query(null, namesOf(series), nfo, title, year, known = null)
        val tv = TmdbAnswer.Given(if (tmdbId != null) Looked(tmdb.tv(tmdbId)) else lookupShow(tmdb, query))
        // The TMDB id saveShow will store: TMDB's, else that of an NFO describing the show.
        val showId = tv.value?.value?.id ?: nfo?.takeUnless { MetadataMerge.describesOther(it, tmdbId, null) }?.tmdbId
        val cache = SeasonCache(TmdbPass(tmdb, strict = true))
        val inItsOrder = series.episodeOrder != null && tv.value?.value?.id == series.tmdbId
        val seasons = if (inItsOrder) {
            null
        } else {
            showId?.let { id ->
                dao.children(series.id).filter { it.kind == ItemKind.SEASON }.mapNotNull { it.seasonNumber }
                    .associateWith { cache.get(id, it).value }
            }.orEmpty()
        }
        return {
            dao.setUserTmdbId(series.id, tmdbId)
            // A choice, or automatic matching asked back, undoes « Laisser tel quel ».
            dao.setUserNoMatch(series.id, false)
            if (tv.value?.value?.id != series.tmdbId) {
                dao.clearUserArtwork(series.id)
                // An order is a show's own at TMDB: the new one starts in TMDB's seasons.
                dao.setUserEpisodeOrder(series.id, null)
                dao.setEpisodeOrder(series.id, null)
            }
            dao.item(series.id)?.let { fresh ->
                val was = series.userTmdbId ?: series.tmdbId
                val cleared = MetadataMerge.withoutDetails(fresh, title, year)
                saveShow(cleared, nfo, tv, LocalArt.of(fresh), tmdb, clock(), was, seasons = seasons)
            }
        }
    }

    /**
     * Asks TMDB again for a show to identify ([Reidentify]) by what its rows hold, never reading the NAS; null when TMDB
     * did not answer, for the show or one of its seasons. Its writes, true when the show changed: found at last, it is
     * saved as an identification saves it, every episode with its season's details and what was kept of it; still not
     * found, only its state, its candidates and when it was asked, its seasons and episodes left as they are.
     */
    suspend fun recheck(series: ItemEntity, tmdb: TmdbPass): (suspend () -> Boolean)? {
        val (title, year) = showQuery(series)
        val nfo = series.nfoInfo()
        val query = Query(null, namesOf(series), nfo, title, year, known = lent(series))
        val answer = tmdb.ask { lookupShow(it, query) } as? TmdbAnswer.Given ?: return null
        val now = clock()
        val tv = answer.value?.value ?: return { dao.rechecked(series, answer.value?.offered.orEmpty(), now) }
        val cache = SeasonCache(tmdb)
        val seasons = dao.children(series.id).filter { it.kind == ItemKind.SEASON }.mapNotNull { it.seasonNumber }
            .associateWith { cache.get(tv.id, it) }
        if (seasons.values.any { it !is TmdbAnswer.Given }) return null
        return {
            dao.item(series.id)?.let { fresh ->
                val cleared = MetadataMerge.withoutDetails(fresh, title, year)
                val details = seasons.mapValues { it.value.value }
                saveShow(cleared, nfo, answer, LocalArt.of(fresh), tmdb.client, now, was = null, seasons = details)
            }
            true
        }
    }

    /** A show's ids, its folder's name's ([NameIds.ofShow]), found through one of its episodes. */
    private suspend fun namesOf(series: ItemEntity): NameIds =
        dao.episodesOfSeries(series.id).firstNotNullOfOrNull { it.filePath }?.let(NameIds::ofShow) ?: NameIds()

    /** The show's name and year from its folder, found through one of its episodes. */
    suspend fun showQuery(series: ItemEntity): ParsedMovie {
        val path = dao.episodesOfSeries(series.id).firstNotNullOfOrNull { it.filePath }?.let(FilenameParser::parseShowPath)
            ?: return ParsedMovie(series.title, series.year)
        return ParsedMovie(path.title, path.year)
    }

    /**
     * What a show's lookup asks TMDB by ([lookupShow]), its folder's name and year: [known] is its TMDB id before; without
     * [searches], a show left as it is ([ItemEntity.userNoMatch]), its ids alone.
     */
    private data class Query(
        val userTmdbId: Int?,
        val names: NameIds,
        val nfo: NfoInfo?,
        val name: String,
        val folderYear: Int?,
        val known: Int?,
        val searches: Boolean = true,
    ) {
        /** The title and year it is searched by. */
        val title: String get() = nfo?.title ?: name
        val year: Int? get() = nfo?.year ?: folderYear

        /** What it is searched by ([TmdbClient.matchTv]): a show's folder gives no bare year to read otherwise. */
        val wanted: Wanted get() = Wanted.of(title, year, original = nfo?.originalTitle)
    }

    /**
     * Tries, highest first, the user's match; the folder name's TMDB id, then its TVDB id (Sonarr's), then its IMDb id;
     * the NFO's, likewise; the existing match ([Query.known]); then a scored search ([TmdbClient.matchTv]), unless the
     * show is left as it is ([Query.searches]). An id TMDB has no show for gives way to the next, as does a name's id
     * not trusted ([NameIds.trusts]), as for films.
     */
    private suspend fun lookupShow(tmdb: TmdbClient, query: Query): Looked<TmdbTv> {
        query.userTmdbId?.let { return Looked(tmdb.tvById(it)) }
        val names = query.names
        val nfo = query.nfo
        val hint = names.key(show = true)
        val steps = listOf(
            IdStep(IdKind.TMDB) { names.tmdb },
            IdStep(IdKind.TVDB) { names.tvdb?.let { tmdb.findByTvdb(it) } },
            IdStep(IdKind.IMDB) { names.imdb?.let { tmdb.findByImdb(it).second } },
            IdStep(null) { nfo?.tmdbId },
            IdStep(null) { nfo?.tvdbId?.trim()?.toIntOrNull()?.let { tmdb.findByTvdb(it) } },
            IdStep(null) { nfo?.imdbId?.let { tmdb.findByImdb(it).second } },
            IdStep(null) { query.known },
        )
        val byIds = lookUp(hint, steps, tmdb::tvById, TmdbTv::id) { tv, by -> trusted(tmdb, tv, by, query) }
        byIds.found?.let { return it }
        // Left as it is: never searched for by its name.
        if (!query.searches) return Looked(null, hint)
        val matched = tmdb.matchTv(query.wanted, first = byIds.untrusted?.let(Candidate::of))
        return Looked(matched.found, hint, offered = matched.offered)
    }

    /** Whether [tv], found by [query]'s folder name's id of kind [by], is the show of that name ([NameIds.trusts]). */
    private suspend fun trusted(tmdb: TmdbClient, tv: TmdbTv, by: IdKind, query: Query): Boolean {
        val names = query.names
        val external = tv.externalIds
        val ids = TitleIds(
            tv.id,
            imdb = external.imdbId ?: names.imdb.takeIf { by == IdKind.IMDB },
            tvdb = external.tvdbId ?: names.tvdb.takeIf { by == IdKind.TVDB },
        )
        val found = NameIds.Found(ids, listOfNotNull(tv.name, tv.originalName), yearOf(tv.firstAirDate)) {
            tmdb.alternativeTitles(tv.id, show = true)
        }
        return names.trusts(found, by, query.nfo, query.wanted.alsoNamed(query.name))
    }

    /**
     * Saves details ([MetadataMerge]) and credits ([CreditRules]). Matched only when TMDB answered, so shows saved from
     * sidecars alone, unanswered refreshes and NFOs awaiting confirmation are looked up again later. A show named
     * since [was], its TMDB id then, takes back what was kept of it and its episodes ([LibraryDao.giveBackShow]).
     */
    @Suppress("LongParameterList") // One per source of details, as for films.
    private suspend fun saveShow(
        base: ItemEntity,
        nfoInfo: NfoInfo?,
        lookup: TmdbAnswer<Looked<TmdbTv>>,
        art: LocalArt,
        tmdb: TmdbClient?,
        now: Long,
        was: Int?,
        refresh: Boolean = false,
        seasons: Map<Int, TmdbSeason?>? = null,
    ): ItemEntity {
        val tv = lookup.value?.value
        val ids = tv?.externalIds
        val untold = untold(base, tv, nfoInfo)
        // The show the user or its folder's name chose: an NFO naming another describes another show.
        val chosen = base.userTmdbId ?: lookup.value?.byName
        val nfo = nfoInfo?.takeUnless { untold || MetadataMerge.describesOther(it, chosen, ids?.imdbId, ids?.tvdbId) }
        val details = tv?.details(tmdb?.tvCertification(tv), tmdb?.pickLogo(tv.images))
        val matchedAt = if (lookup is TmdbAnswer.Given) now else base.matchedAt.takeUnless { refresh || untold }
        val series = MetadataMerge.merge(base, nfo, details, art).copy(matchedAt = matchedAt).lookedUp(base, lookup)
        val credits = CreditRules.credits(series.id, show = true, nfo, tv?.let { TmdbPeople(it.credits, it.createdBy) })
        val answered = lookup is TmdbAnswer.Given
        var given = false
        // At once: a show matched without its credits would never be looked up again for them, nor without what was
        // kept; its episodes take its seasons' details with it, and its seasons' counts are kept.
        dao.inTransaction {
            dao.saveScanned(series)
            dao.offer(series, lookup)
            dao.saveCredits(series.id, credits, tmdbAnswered = answered, complete = answered || series.tmdbId == null, at = now)
            tv?.let { if (dao.recount(it, refresh, now)) recounted += series.id }
            seasons?.let { inTmdbsSeasons(series, was, it, now) }
            given = givenBack(series, was)
        }
        return if (given) dao.item(series.id) ?: series else series
    }

    /**
     * After a new match, [series]'s episodes back in TMDB's seasons with their [details] ([refreshSeasons]); an order is a
     * show's own at TMDB, so one named anew since [was] drops its, as an identification does.
     */
    private suspend fun inTmdbsSeasons(series: ItemEntity, was: Int?, details: Map<Int, TmdbSeason?>, now: Long) {
        if ((series.userTmdbId ?: series.tmdbId) != was) dao.setUserEpisodeOrder(series.id, null)
        dao.setEpisodeOrder(series.id, null)
        refreshSeasons(series, details, now)
        namedAnew += series.id
    }

    /** For a user match TMDB did not answer for, an NFO with only IMDb or TVDB ids is set aside until TMDB confirms it. */
    private fun untold(base: ItemEntity, tv: TmdbTv?, nfo: NfoInfo?): Boolean =
        base.userTmdbId != null && tv == null && nfo?.tmdbId == null && (nfo?.imdbId != null || nfo?.tvdbId != null)

    /** Gives [series], named by TMDB or its NFO since [was], what was kept of it and its episodes; true when it was. */
    private suspend fun givenBack(series: ItemEntity, was: Int?): Boolean {
        val identity = series.userTmdbId ?: series.tmdbId
        return identity != null && identity != was && dao.giveBackShow(series.id, identity)
    }

    /**
     * The season row [at] its number in its show's order, with TMDB details fetched once (again on a refresh, once per
     * pass) and its poster from the show folder; a group's season takes those of the TMDB season it is made of, if one
     * ([seasonSource]). An unanswered refresh leaves it unmatched for a later pass.
     */
    private suspend fun season(pass: ScanPass, series: ItemEntity, at: SeasonAt, now: Long): ItemEntity {
        val source = pass.source
        val number = at.number
        val localPoster = pass.sidecars.seasonPoster(at.dir, number)
        var season = dao.seasonRow(series, number, source.id, now)

        // A matched season is not fetched again per episode; a refresh fetches it once per pass.
        val refreshDue = pass.refreshMetadata && season.matchedAt != null && season.id !in refreshedSeasons
        val shows = at.order.seasonSource(number)
        val asked = series.tmdbId?.takeIf { season.matchedAt == null || refreshDue }
        val answer = asked?.let { id -> shows.tmdbSeason?.let { pass.seasons.get(id, it) } }
        if (described(answer, asked != null, shows)) {
            refreshedSeasons += season.id
            season = season.withSeasonDetails(answer?.value, now, shows)
            dao.saveScanned(season)
        } else if (refreshDue && answer == TmdbAnswer.Unanswered) {
            season = season.copy(matchedAt = null)
            dao.saveScanned(season)
        }
        // ArtworkCache only copies a poster again when it changed.
        val copy = localPoster?.let { files.copyArtwork(source, it) }
        if (copy != null && copy != season.posterPath) {
            season = season.copy(posterPath = copy)
            dao.saveScanned(season)
        }
        return season
    }

    /** Whether a season [asked] about is described: TMDB gave its details, or it is a group's that TMDB did not fail for. */
    private fun described(answer: TmdbAnswer<TmdbSeason>?, asked: Boolean, shows: SeasonSource): Boolean =
        answer?.value != null || asked && shows.group && answer !is TmdbAnswer.Unanswered

    /**
     * After a new match, applies the prefetched season [details], stored NFOs and local thumbs, in TMDB's seasons; an
     * episode numbered from its show's start is read again at the next pass, to be placed in the new show's.
     */
    private suspend fun refreshSeasons(series: ItemEntity, details: Map<Int, TmdbSeason?>, now: Long) {
        for (season in dao.children(series.id).filter { it.kind == ItemKind.SEASON }) {
            val meta = season.seasonNumber?.let { details[it] }
            dao.saveScanned(season.withSeasonDetails(meta, now))
            for (episode in dao.children(season.id)) {
                if (episode.absoluteNumber != null) {
                    dao.saveScanned(episode.copy(matchedAt = null))
                    continue
                }
                val nfo = episode.nfoInfo()
                val episodeMeta = meta?.episodes?.firstOrNull { it.episodeNumber == episode.episodeNumber }
                val cleared = episode.copy(
                    title = "", overview = null, stillPath = null, runtimeMin = null, rating = null, year = null, tmdbSeason = null,
                    tmdbEpisode = null, tmdbLastEpisode = null,
                )
                val thumb = episode.stillPath?.takeIf { it.isCachedArtwork() }
                dao.saveScanned(cleared.withEpisodeDetails(nfo, episodeMeta, thumb, if (episodeMeta != null) now else null))
            }
        }
    }

    /** Caches answered TMDB seasons per pass; unanswered ones are retried, which the pass's breaker keeps cheap. */
    class SeasonCache(private val tmdb: TmdbPass) {
        private val cache = mutableMapOf<Pair<Int, Int>, TmdbAnswer.Given<TmdbSeason>>()

        suspend fun get(tvId: Int, season: Int): TmdbAnswer<TmdbSeason> =
            cache[tvId to season] ?: tmdb.ask { it.season(tvId, season) }.also { if (it is TmdbAnswer.Given) cache[tvId to season] = it }
    }
}
