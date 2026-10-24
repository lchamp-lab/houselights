package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.yearOf
import java.util.Locale

/** Indexes episodes with their season and show: NFOs, artwork and TMDB match. */
internal class ShowIndexer(private val dao: LibraryDao, private val files: ScanFiles) {
    /** Shows already read during the current pass. */
    private val refreshedSeries = mutableSetOf<Long>()

    /** Seasons already refreshed from TMDB during the current pass. */
    private val refreshedSeasons = mutableSetOf<Long>()

    fun startPass() {
        refreshedSeries.clear()
        refreshedSeasons.clear()
    }

    /** Whether show [seriesId] was read this pass (sidecars, and TMDB when due). */
    fun readThisPass(seriesId: Long): Boolean = seriesId in refreshedSeries

    suspend fun process(pass: ScanPass, file: RemoteFile, existing: ItemEntity?) {
        val source = pass.source
        val refreshMetadata = pass.refreshMetadata
        val now = System.currentTimeMillis()
        val path = FilenameParser.parseShowPath(file.relativePath) ?: return
        val parsed = path.episode

        val series = series(pass, path, existing, now)
        val season = season(pass, series, path, now)
        // Episodes only use their NFO and thumb (see SidecarKey.listed).
        val local = pass.sidecars.forVideo(file).let { VideoSidecars(nfo = it.nfo, thumb = it.thumb) }
        val base = existing ?: ItemEntity(
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
        val seasonAnswer = series.tmdbId?.takeIf { seasonWanted(base, series, refreshMetadata) }
            ?.let { pass.seasons.get(it, parsed.season) }
        val meta = seasonAnswer?.value?.episodes?.firstOrNull { it.episodeNumber == parsed.episode }
        val answered = answered(pass.tmdb, series, seasonAnswer)
        val art = files.copyArt(source, local)

        val episode = base.copy(
            parentId = season.id,
            seriesId = series.id,
            seasonNumber = parsed.season,
            episodeNumber = parsed.episode,
            lastEpisodeNumber = parsed.lastEpisode,
            sortTitle = "%03d-%04d".format(Locale.ROOT, parsed.season, parsed.episode),
            nfoPath = kept.path,
            nfo = kept.text,
            sidecarKey = SidecarKey.of(files.sidecarsRead(local, kept, art)),
        ).withEpisodeDetails(
            nfo = nfo,
            meta = meta,
            thumb = art.thumb,
            // Unanswered refreshes and episodes of unmatched shows stay unmatched, so a later pass retries the show.
            matchedAt = if (answered) now else base.matchedAt.takeUnless { refreshMetadata || series.matchedAt == null },
        )
        val stale = MediaProbe.isStale(episode.mediaInfo) || episode.sizeChanged(file)
        val probed = (if (stale) files.mediaFields(source, file, episode) else episode).withNfoStreams(nfo)
        if (existing == null) {
            dao.insert(probed)
            // A new episode moves the show up in "recently added".
            dao.saveScanned(series.copy(addedAt = now))
        } else {
            dao.saveScanned(probed)
        }
    }

    /** Season details are needed for a new or unmatched episode, a refresh, or a show re-matched since. */
    private fun seasonWanted(episode: ItemEntity, series: ItemEntity, refreshMetadata: Boolean): Boolean {
        val matched = episode.matchedAt ?: return true
        return refreshMetadata || (series.matchedAt ?: 0) > matched
    }

    /** Matched once the show was looked up and, if TMDB knows the show, its season answered. */
    private fun answered(tmdb: TmdbPass, series: ItemEntity, season: TmdbAnswer<TmdbSeason>?): Boolean {
        val showAnswered = tmdb.client != null && series.matchedAt != null
        return showAnswered && (series.tmdbId == null || season is TmdbAnswer.Given)
    }

    /** Details from the NFO, then TMDB, then the stored values. */
    private fun ItemEntity.withEpisodeDetails(nfo: NfoInfo?, meta: TmdbEpisode?, thumb: String?, matchedAt: Long?) = copy(
        title = nfo?.title ?: meta?.name ?: title,
        overview = nfo?.plot ?: meta?.overview?.ifBlank { null } ?: overview,
        stillPath = thumb ?: meta?.stillPath ?: stillPath,
        runtimeMin = nfo?.runtimeMin ?: meta?.runtime ?: runtimeMin,
        rating = nfo?.rating ?: meta?.voteAverage?.takeIf { it > 0 } ?: rating,
        year = yearOf(nfo?.premiered) ?: yearOf(meta?.airDate) ?: year,
        matchedAt = matchedAt,
    )

    private suspend fun series(pass: ScanPass, path: ShowPath, existing: ItemEntity?, now: Long): ItemEntity {
        val (title, year, showDir) = path
        val source = pass.source
        val tmdb = pass.tmdb
        val refreshMetadata = pass.refreshMetadata
        val series = showOf(source, path, existing, now)

        if (series.id in refreshedSeries) return series
        val local = pass.sidecars.forShow(showDir)
        // tvshow.nfo or artwork added, rewritten in place or removed since the last read.
        val sidecarsChanged = SidecarKey.showDue(series.sidecarKey, source.id, showDir, pass.sidecars)
        val needsLookup = series.matchedAt == null || refreshMetadata || sidecarsChanged
        // A refresh TMDB cannot serve now is kept by leaving the show unmatched (see saveShow).
        val refreshLeft = refreshMetadata && series.matchedAt != null
        val nothingToRead = !tmdb.open && !sidecarsChanged && !refreshLeft
        if (!needsLookup || nothingToRead) return series
        // Once per pass, answered or not; an unanswered show and its episodes stay unmatched until a later pass.
        refreshedSeries += series.id

        val kept = files.nfo(source, local.nfo, series)
        val nfo = kept.info
        // As for films, removed sidecars hand details back to TMDB, dropping stale cached images.
        val removed = SidecarKey.showRemoved(series.sidecarKey, source.id, showDir, local)
        val tv = if (tmdbAsked(series, nfo, refreshMetadata, removed)) {
            // Keep the match, not the details: a removed NFO may have been all that named it. A refresh keeps the
            // match; a show re-keyed from its path is unmatched and looked up by its new name.
            val known = series.tmdbId.takeIf { removed || series.matchedAt != null }
            tmdb.ask { lookupShow(it, series.userTmdbId, nfo, title, year, known = known) }
        } else {
            TmdbAnswer.Unanswered
        }
        val art = files.copyArt(source, local)
        val read = series.copy(
            nfoPath = kept.path,
            nfo = kept.text,
            sidecarKey = SidecarKey.ofShow(source.id, showDir, files.sidecarsRead(local, kept, art)),
        )
        // Details dropped without a TMDB answer: a later pass queries TMDB again (see process).
        val base = if (removed) MetadataMerge.withoutDetails(read, title, year).copy(matchedAt = null) else read
        return saveShow(base, nfo, tv, art, tmdb.client, now, refreshMetadata)
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
     * else a new one. The last episode to leave a show hands over its user state ([takenOver]).
     */
    private suspend fun showOf(source: SourceEntity, path: ShowPath, existing: ItemEntity?, now: Long): ItemEntity {
        val key = FilenameParser.showKey(path.title, path.year)
        val nameKey = FilenameParser.showKey(path.title)
        val show = seriesByKey(key, nameKey, path.year) ?: formerShow(source, path, existing, key, nameKey) ?: ItemEntity(
            kind = ItemKind.SERIES,
            sourceId = source.id,
            matchKey = key,
            title = path.title,
            sortTitle = MetadataMerge.sortKey(path.title),
            year = path.year,
            addedAt = now,
            // New rows never need the backfill.
            creditsAt = now,
        ).let { it.copy(id = dao.insert(it)) }
        val left = existing?.seriesId?.takeIf { it != show.id } ?: return show
        return takenOver(show, left, existing.id)
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
     * ([LibraryDao.handOver]) before it is deleted, e.g. two season packs merging into one show. A user match handed
     * over is looked up this pass or a later one.
     */
    private suspend fun takenOver(show: ItemEntity, formerId: Long, episodeId: Long): ItemEntity {
        if (dao.hasOtherEpisodes(formerId, episodeId)) return show
        dao.handOver(formerId, show.id)
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
     * run in a transaction ([LibraryScanner.identify]); a TMDB failure throws before anything is written.
     */
    suspend fun identify(series: ItemEntity, tmdbId: Int?, tmdb: TmdbClient): suspend () -> Unit {
        val (title, year) = showQuery(series)
        val nfo = series.nfoInfo()
        val tv = TmdbAnswer.Given(if (tmdbId != null) tmdb.tv(tmdbId) else lookupShow(tmdb, null, nfo, title, year))
        // The TMDB id saveShow will store: TMDB's, else that of an NFO describing the show.
        val showId = tv.value?.id ?: nfo?.takeUnless { MetadataMerge.describesOther(it, tmdbId, null) }?.tmdbId
        val cache = SeasonCache(TmdbPass(tmdb, strict = true))
        val seasons = showId?.let { id ->
            dao.children(series.id).filter { it.kind == ItemKind.SEASON }.mapNotNull { it.seasonNumber }
                .associateWith { cache.get(id, it).value }
        }.orEmpty()
        return {
            dao.setUserTmdbId(series.id, tmdbId)
            if (tv.value?.id != series.tmdbId) dao.clearUserArtwork(series.id)
            dao.item(series.id)?.let { fresh ->
                val now = System.currentTimeMillis()
                val saved = saveShow(MetadataMerge.withoutDetails(fresh, title, year), nfo, tv, LocalArt.of(fresh), tmdb, now)
                refreshSeasons(saved, seasons, now)
            }
        }
    }

    /** The show's name and year from its folder, found through one of its episodes. */
    suspend fun showQuery(series: ItemEntity): ParsedMovie {
        val path = dao.episodesOfSeries(series.id).firstNotNullOfOrNull { it.filePath }?.let(FilenameParser::parseShowPath)
            ?: return ParsedMovie(series.title, series.year)
        return ParsedMovie(path.title, path.year)
    }

    /** Tries the user's match, the NFO's ids, the existing match ([known]), then a title search. */
    private suspend fun lookupShow(
        tmdb: TmdbClient,
        userTmdbId: Int?,
        nfo: NfoInfo?,
        title: String,
        year: Int?,
        known: Int? = null,
    ): TmdbTv? {
        userTmdbId?.let { return tmdb.tvById(it) }
        val id = nfo?.tmdbId ?: nfo?.imdbId?.let { tmdb.findByImdb(it).second } ?: known
        return id?.let { tmdb.tvById(it) } ?: tmdb.findTv(nfo?.title ?: title, nfo?.year ?: year)
    }

    /**
     * Saves details ([MetadataMerge]) and credits ([CreditRules]). Matched only when TMDB answered, so shows saved from
     * sidecars alone, unanswered refreshes and NFOs awaiting confirmation are looked up again later.
     */
    @Suppress("LongParameterList") // One per source of details, as for films.
    private suspend fun saveShow(
        base: ItemEntity,
        nfoInfo: NfoInfo?,
        lookup: TmdbAnswer<TmdbTv>,
        art: LocalArt,
        tmdb: TmdbClient?,
        now: Long,
        refresh: Boolean = false,
    ): ItemEntity {
        val tv = lookup.value
        val ids = tv?.externalIds
        // For a user match with no TMDB answer, an NFO with only IMDb or TVDB ids is set aside until TMDB confirms it.
        val untold = base.userTmdbId != null && tv == null && nfoInfo?.tmdbId == null &&
            (nfoInfo?.imdbId != null || nfoInfo?.tvdbId != null)
        val nfo = nfoInfo?.takeUnless { untold || MetadataMerge.describesOther(it, base.userTmdbId, ids?.imdbId, ids?.tvdbId) }
        val details = tv?.details(tmdb?.tvCertification(tv), tmdb?.pickLogo(tv.images))
        val matchedAt = if (lookup is TmdbAnswer.Given) now else base.matchedAt.takeUnless { refresh || untold }
        val series = MetadataMerge.merge(base, nfo, details, art).copy(matchedAt = matchedAt)
        val credits = CreditRules.credits(series.id, show = true, nfo, tv?.let { TmdbPeople(it.credits, it.createdBy) })
        val answered = lookup is TmdbAnswer.Given
        // At once: a show matched without its credits would never be looked up again for them.
        dao.inTransaction {
            dao.saveScanned(series)
            dao.saveCredits(series.id, credits, tmdbAnswered = answered, complete = answered || series.tmdbId == null, at = now)
        }
        return series
    }

    /**
     * The season row of [path]'s episode, with TMDB details fetched once (again on a refresh, once per pass) and its
     * poster from the show folder. An unanswered refresh leaves it unmatched for a later pass.
     */
    private suspend fun season(pass: ScanPass, series: ItemEntity, path: ShowPath, now: Long): ItemEntity {
        val source = pass.source
        val number = path.episode.season
        val localPoster = pass.sidecars.seasonPoster(path.dir, number)
        var season = dao.season(series.id, number) ?: ItemEntity(
            kind = ItemKind.SEASON,
            sourceId = source.id,
            parentId = series.id,
            seriesId = series.id,
            matchKey = "${series.id}:$number",
            title = "",
            sortTitle = "%03d".format(Locale.ROOT, number),
            seasonNumber = number,
            addedAt = now,
        ).let { it.copy(id = dao.insert(it)) }

        // A matched season is not fetched again per episode; a refresh fetches it once per pass.
        val refreshDue = pass.refreshMetadata && season.matchedAt != null && season.id !in refreshedSeasons
        val answer = series.tmdbId?.takeIf { season.matchedAt == null || refreshDue }?.let { pass.seasons.get(it, number) }
        val meta = answer?.value
        if (meta != null) {
            refreshedSeasons += season.id
            season = season.withSeasonDetails(meta, now)
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

    /** Applies TMDB season details, keeping a local poster; without details, stays unmatched. */
    private fun ItemEntity.withSeasonDetails(meta: TmdbSeason?, now: Long) = copy(
        title = meta?.name ?: "",
        overview = meta?.overview?.ifBlank { null },
        posterPath = posterPath?.takeIf { it.isCachedArtwork() } ?: meta?.posterPath,
        year = yearOf(meta?.airDate),
        matchedAt = if (meta != null) now else null,
    )

    /** After a new match, applies the prefetched season [details], stored NFOs and local thumbs. */
    private suspend fun refreshSeasons(series: ItemEntity, details: Map<Int, TmdbSeason?>, now: Long) {
        for (season in dao.children(series.id).filter { it.kind == ItemKind.SEASON }) {
            val meta = season.seasonNumber?.let { details[it] }
            dao.saveScanned(season.withSeasonDetails(meta, now))
            for (episode in dao.children(season.id)) {
                val nfo = episode.nfoInfo()
                val episodeMeta = meta?.episodes?.firstOrNull { it.episodeNumber == episode.episodeNumber }
                val cleared = episode.copy(title = "", overview = null, stillPath = null, runtimeMin = null, rating = null, year = null)
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
