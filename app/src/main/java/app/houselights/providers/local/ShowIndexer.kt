package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.yearOf
import java.util.Locale

/** Shows: each episode file, its season and its show, with their NFOs, artwork and TMDB match. */
internal class ShowIndexer(private val dao: LibraryDao, private val files: ScanFiles) {
    /** Shows already looked up during the current scan (full refresh only). */
    private val refreshedSeries = mutableSetOf<Long>()

    /** Seasons a full refresh already gave TMDB's details again during the current scan. */
    private val refreshedSeasons = mutableSetOf<Long>()

    /** A new scan looks shows and seasons up again. */
    fun startPass() {
        refreshedSeries.clear()
        refreshedSeasons.clear()
    }

    /** Whether the show [seriesId] was read during the current scan: its sidecars, and TMDB when it was due. */
    fun readThisPass(seriesId: Long): Boolean = seriesId in refreshedSeries

    /** The episode at [file] in the [pass]'s folder, [existing] its row when the library knows it; its season and show. */
    suspend fun process(pass: ScanPass, file: RemoteFile, existing: ItemEntity?) {
        val source = pass.source
        val refreshMetadata = pass.refreshMetadata
        val now = System.currentTimeMillis()
        val path = FilenameParser.parseShowPath(file.relativePath) ?: return
        val parsed = path.episode

        val series = series(pass, path, existing, now)
        val season = season(pass, series, path, now)
        // An episode has its NFO and its thumb, no poster, fanart or logo of its own (see SidecarKey.listed).
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
            // A refresh TMDB did not answer is asked again at a later pass, the show's lookup with it.
            matchedAt = if (answered) now else base.matchedAt.takeUnless { refreshMetadata },
        )
        val stale = MediaProbe.isStale(episode.mediaInfo) || episode.sizeChanged(file)
        val probed = (if (stale) files.mediaFields(source, file, episode) else episode).withNfoStreams(nfo)
        if (existing == null) {
            dao.insert(probed)
            // New episode: the show moves up in "recently added".
            dao.saveScanned(series.copy(addedAt = now))
        } else {
            dao.saveScanned(probed)
        }
    }

    /**
     * TMDB's season is asked for a new episode, one unmatched, a refresh, or a show matched again since:
     * an episode read again for its sidecars or its file alone keeps what TMDB said of it.
     */
    private fun seasonWanted(episode: ItemEntity, series: ItemEntity, refreshMetadata: Boolean): Boolean {
        val matched = episode.matchedAt ?: return true
        return refreshMetadata || (series.matchedAt ?: 0) > matched
    }

    /** An episode is matched once TMDB answered for it: its show looked up, and its season asked when TMDB knows the show. */
    private fun answered(tmdb: TmdbPass, series: ItemEntity, season: TmdbAnswer<TmdbSeason>?): Boolean {
        val showAnswered = tmdb.client != null && series.matchedAt != null
        return showAnswered && (series.tmdbId == null || season is TmdbAnswer.Given)
    }

    /** Details: NFO, then TMDB, then what the episode had. */
    private fun ItemEntity.withEpisodeDetails(nfo: NfoInfo?, meta: TmdbEpisode?, thumb: String?, matchedAt: Long?) = copy(
        title = nfo?.title ?: meta?.name ?: title,
        overview = nfo?.plot ?: meta?.overview?.ifBlank { null } ?: overview,
        stillPath = thumb ?: meta?.stillPath ?: stillPath,
        runtimeMin = nfo?.runtimeMin ?: meta?.runtime ?: runtimeMin,
        rating = nfo?.rating ?: meta?.voteAverage?.takeIf { it > 0 } ?: rating,
        year = yearOf(nfo?.premiered) ?: yearOf(meta?.airDate) ?: year,
        matchedAt = matchedAt,
    )

    /** The show [path] names, for the episode [existing] (null for a new one), as the [pass] met it. */
    @Suppress("CyclomaticComplexMethod") // One choice per sidecar.
    private suspend fun series(pass: ScanPass, path: ShowPath, existing: ItemEntity?, now: Long): ItemEntity {
        val (title, year, showDir) = path
        val source = pass.source
        val tmdb = pass.tmdb
        val refreshMetadata = pass.refreshMetadata
        val series = showOf(source, path, existing, now)

        if (series.id in refreshedSeries) return series
        val local = pass.sidecars.forShow(showDir)
        // Its tvshow.nfo or artwork placed, rewritten in place or removed since they were read.
        val sidecarsChanged = SidecarKey.showDue(series.sidecarKey, source.id, showDir, pass.sidecars)
        val needsLookup = series.matchedAt == null || refreshMetadata || sidecarsChanged
        // A refresh TMDB cannot be asked for now is not lost: the show is left unmatched (see saveShow).
        val refreshLeft = refreshMetadata && series.matchedAt != null
        val nothingToRead = !tmdb.open && !sidecarsChanged && !refreshLeft
        if (!needsLookup || nothingToRead) return series
        // Once a pass, answered or not: a show TMDB did not answer stays unmatched, and so do its
        // episodes, until a later pass asks again.
        refreshedSeries += series.id

        val kept = files.nfo(source, local.nfo, series)
        val nfo = kept.info
        // Sidecars read again ask TMDB only when the NFO names another show; or, beside the user's match,
        // one only TMDB's ids tell of (IMDb's, TVDB's), as for a film.
        val newIds = nfo != null && !sameIds(series.nfoInfo(), nfo)
        val undecided = nfo != null && series.userTmdbId != null && nfo.tmdbId == null && (nfo.imdbId != null || nfo.tvdbId != null)
        val needsTmdb = series.matchedAt == null || refreshMetadata || newIds || undecided
        val tv = if (needsTmdb) tmdb.ask { lookupShow(it, series.userTmdbId, nfo, title, year) } else TmdbAnswer.Unanswered
        val art = files.copyArt(source, local)
        val base = series.copy(
            nfoPath = kept.path,
            nfo = kept.text,
            sidecarKey = SidecarKey.ofShow(source.id, showDir, files.sidecarsRead(local, kept, art)),
        )
        return saveShow(base, nfo, tv, art, tmdb.client, now, refreshMetadata)
    }

    /**
     * The show's row: the one keyed as [path] names it ([seriesByKey]), else the one the episode lay in, keyed
     * by an earlier rule ([formerShow]), else a new one. An episode that leaves its show for another, the last
     * to, hands it what the user did with the show it leaves ([takenOver]).
     */
    private suspend fun showOf(source: SourceEntity, path: ShowPath, existing: ItemEntity?, now: Long): ItemEntity {
        val key = FilenameParser.showKey(path.title, path.year)
        val nameKey = FilenameParser.showKey(path.title)
        val show = seriesByKey(key, nameKey) ?: formerShow(source, path, existing, key, nameKey) ?: ItemEntity(
            kind = ItemKind.SERIES,
            sourceId = source.id,
            matchKey = key,
            title = path.title,
            sortTitle = MetadataMerge.sortKey(path.title),
            year = path.year,
            addedAt = now,
        ).let { it.copy(id = dao.insert(it)) }
        val left = existing?.seriesId?.takeIf { it != show.id } ?: return show
        return takenOver(show, left, existing.id)
    }

    /**
     * The show the episode [existing] lies in, or for a new one that of the episodes of its show's folder that
     * name its show ([nameKey]), when keyed by an earlier rule than their paths' ("slow horses s01" for a pack of
     * a season at the root; "animation" when a folder of kinds named the shows under it): keyed again in place
     * ([key]), it keeps its rows and what was done with it, as [seriesByKey] keeps a show keyed before its year.
     * It is looked up again under its new name, the user's match first; one its path alone named takes the
     * path's name. A show whose key one of its episodes' paths still gives is another one, and keeps its key:
     * "Animation/Blue Eye Samurai" once in the same show as "Animation/Arcane".
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

    /** The show of the episodes the library knows under the show's folder [dir] that name it ([nameKey]); none at the source's root. */
    private suspend fun showBeside(source: SourceEntity, dir: String, nameKey: String): Long? {
        if (dir.isEmpty()) return null
        return dao.episodesUnder(source.id, dir).filter { nameKeyOf(it.filePath) == nameKey }.mapNotNullTo(HashSet()) { it.seriesId }
            .singleOrNull()
    }

    /** The show an episode's path names, by its name alone ([FilenameParser.showKey]). */
    private fun nameKeyOf(filePath: String?): String? =
        filePath?.let(FilenameParser::parseShowPath)?.let { FilenameParser.showKey(it.title) }

    /**
     * [show], once the episode [episodeId] left the show [formerId] for it: the last to leave, it takes along
     * what the user did with that show, which then goes empty ([LibraryDao.deleteEmptySeries]), where [show]
     * has none of it ([LibraryDao.handOver]): two packs of a season, once two shows, are one now. A match by
     * the user so handed over is looked up at once, or at a later pass.
     */
    private suspend fun takenOver(show: ItemEntity, formerId: Long, episodeId: Long): ItemEntity {
        if (dao.hasOtherEpisodes(formerId, episodeId)) return show
        dao.handOver(formerId, show.id)
        val taken = dao.item(show.id) ?: return show
        if (taken.userTmdbId == show.userTmdbId || taken.userTmdbId == taken.tmdbId) return taken
        refreshedSeries -= taken.id
        return taken.copy(matchedAt = null).also { dao.saveScanned(it) }
    }

    /** [known] and [nfo] name the same show: TMDB has nothing new to say of it. */
    private fun sameIds(known: NfoInfo?, nfo: NfoInfo): Boolean =
        known != null && known.tmdbId == nfo.tmdbId && known.imdbId == nfo.imdbId && known.tvdbId == nfo.tvdbId

    /**
     * The show [key] names. A key with a year ("doctor who|2005") takes the show of its name alone
     * ([nameKey]), made before keys held the year, or from a folder that gives none: it keeps its rows
     * and what was done with it, and a folder without the year finds it again below. A key without a
     * year finds the one show of that name and some year: two folders of one show, one giving its
     * year, stay one show; with two such shows ("Doctor Who" of 1963 and of 2005), it is a show of its own.
     */
    private suspend fun seriesByKey(key: String, nameKey: String): ItemEntity? {
        dao.seriesByKey(key)?.let { return it }
        if (key == nameKey) return dao.seriesNamed(nameKey).singleOrNull()
        return dao.seriesByKey(nameKey)?.copy(matchKey = key)?.also { dao.saveScanned(it) }
    }

    /**
     * Asks TMDB all a new match needs (the show, then each of its seasons the library holds), then
     * returns the writes that apply it to the show, its seasons and episodes, for the caller to run at
     * once ([LibraryScanner.identify]): TMDB out of reach throws before anything is written.
     */
    suspend fun identify(series: ItemEntity, tmdbId: Int?, tmdb: TmdbClient): suspend () -> Unit {
        val (title, year) = showQuery(series)
        val nfo = series.nfoInfo()
        val tv = TmdbAnswer.Given(if (tmdbId != null) tmdb.tv(tmdbId) else lookupShow(tmdb, null, nfo, title, year))
        // The show's id once saved (see saveShow): TMDB's, else that of an NFO describing it.
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

    /** The show's name and year as its folder gives them, through one of its episodes. */
    suspend fun showQuery(series: ItemEntity): ParsedMovie {
        val path = dao.episodesOfSeries(series.id).firstNotNullOfOrNull { it.filePath }?.let(FilenameParser::parseShowPath)
            ?: return ParsedMovie(series.title, series.year)
        return ParsedMovie(path.title, path.year)
    }

    /** The user's match first, then the NFO's ids, then a search by title. */
    private suspend fun lookupShow(tmdb: TmdbClient, userTmdbId: Int?, nfo: NfoInfo?, title: String, year: Int?): TmdbTv? {
        userTmdbId?.let { return tmdb.tvById(it) }
        val id = nfo?.tmdbId ?: nfo?.imdbId?.let { tmdb.findByImdb(it).second }
        return id?.let { tmdb.tvById(it) } ?: tmdb.findTv(nfo?.title ?: title, nfo?.year ?: year)
    }

    /**
     * Saves a show's details (see [MetadataMerge]) and its cast. Matched only when TMDB answered: a
     * show saved from its NFO or its poster alone is looked up at a later scan, and so is one TMDB did
     * not answer for a [refresh].
     */
    @Suppress("LongParameterList") // One per source of a show's details, as for a film.
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
        // Matched by hand, a show TMDB gives nothing for now keeps what it had: an NFO only TMDB's
        // ids can tell of (IMDb's, TVDB's) waits for them, rather than its title and cast win meanwhile.
        val untold = base.userTmdbId != null && tv == null
        val nfo = nfoInfo?.takeUnless {
            untold && it.tmdbId == null && (it.imdbId != null || it.tvdbId != null) ||
                MetadataMerge.describesOther(it, base.userTmdbId, ids?.imdbId, ids?.tvdbId)
        }
        val details = tv?.details(tmdb?.tvCertification(tv), tmdb?.pickLogo(tv.images))
        val matchedAt = if (lookup is TmdbAnswer.Given) now else base.matchedAt.takeUnless { refresh }
        val series = MetadataMerge.merge(base, nfo, details, art).copy(matchedAt = matchedAt)
        dao.saveScanned(series)
        MetadataMerge.cast(series.id, nfo, tv?.credits)?.let { dao.replaceCast(series.id, it) }
        return series
    }

    /**
     * The season of the episode at [path], with TMDB's details once, and again at a refresh, once a pass
     * (the [pass]'s seasons are asked of TMDB once a pass too), and its poster in its show's folder. A
     * refresh TMDB does not answer leaves the season unmatched, as it does its show and episodes: a later
     * pass asks again.
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

        // A season TMDB described is not asked again for each episode read again; a refresh asks once a pass.
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
        if (localPoster != null && !season.posterPath.isCachedArtwork()) {
            files.copyArtwork(source, localPoster)?.let { season = season.copy(posterPath = it); dao.saveScanned(season) }
        }
        return season
    }

    /** TMDB's season details; a local poster stays. Without details, the next scan asks again. */
    private fun ItemEntity.withSeasonDetails(meta: TmdbSeason?, now: Long) = copy(
        title = meta?.name ?: "",
        overview = meta?.overview?.ifBlank { null },
        posterPath = posterPath?.takeIf { it.isCachedArtwork() } ?: meta?.posterPath,
        year = yearOf(meta?.airDate),
        matchedAt = if (meta != null) now else null,
    )

    /**
     * After a new match, seasons and episodes take the new show's details at once ([details], by
     * season number, asked before), and what the library already knows of their files (NFO, local thumbs).
     */
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

    /**
     * One TMDB season request per (show, season) during a scan, once answered. One left unanswered
     * (TMDB paused, or failing) is asked again for the next episode: the pass's breaker keeps that cheap.
     */
    class SeasonCache(private val tmdb: TmdbPass) {
        private val cache = mutableMapOf<Pair<Int, Int>, TmdbAnswer.Given<TmdbSeason>>()

        suspend fun get(tvId: Int, season: Int): TmdbAnswer<TmdbSeason> =
            cache[tvId to season] ?: tmdb.ask { it.season(tvId, season) }.also { if (it is TmdbAnswer.Given) cache[tvId to season] = it }
    }
}
