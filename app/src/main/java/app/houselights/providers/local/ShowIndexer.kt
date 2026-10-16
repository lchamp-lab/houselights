package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser
import app.houselights.core.yearOf
import java.util.Locale

/** Shows: each episode file, its season and its show, with their NFOs, artwork and TMDB match. */
internal class ShowIndexer(private val dao: LibraryDao, private val files: ScanFiles) {
    /** Shows already looked up during the current scan (full refresh only). */
    private val refreshedSeries = mutableSetOf<Long>()

    /** A new scan looks shows up again. */
    fun startPass() = refreshedSeries.clear()

    @Suppress("LongParameterList") // What a pass hands each file.
    suspend fun process(
        source: SourceEntity,
        file: RemoteFile,
        existing: ItemEntity?,
        tmdb: TmdbPass,
        seasons: SeasonCache,
        sidecars: Sidecars,
        refreshMetadata: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val folders = file.relativePath.split('/').dropLast(1)
        val parsed = FilenameParser.parseEpisode(file.relativePath.substringAfterLast('/'), folders) ?: return
        val showTitle = parsed.showTitle ?: return
        val showDir = folders.firstOrNull { FilenameParser.seasonFromFolder(it) == null } ?: ""
        val showYear = folders.firstOrNull()?.let { FilenameParser.parseMovie(it).year }

        val series = series(source, showTitle, showYear, showDir, sidecars, tmdb, refreshMetadata, now)
        val season = season(source, series, parsed.season, seasons, now, sidecars.seasonPoster(showDir, parsed.season))
        val local = sidecars.forVideo(file)
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
        val seasonAnswer = series.tmdbId?.let { seasons.get(it, parsed.season) }
        val meta = seasonAnswer?.value?.episodes?.firstOrNull { it.episodeNumber == parsed.episode }
        val answered = answered(tmdb, series, seasonAnswer)

        val episode = base.copy(
            parentId = season.id,
            seriesId = series.id,
            seasonNumber = parsed.season,
            episodeNumber = parsed.episode,
            lastEpisodeNumber = parsed.lastEpisode,
            sortTitle = "%03d-%04d".format(Locale.ROOT, parsed.season, parsed.episode),
            nfoPath = kept.path,
            nfo = kept.text,
        ).withEpisodeDetails(
            nfo = nfo,
            meta = meta,
            thumb = local.thumb?.let { files.copyArtwork(source, it) },
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

    @Suppress("LongParameterList", "CyclomaticComplexMethod") // The show an episode names, as the pass met it; one choice per sidecar.
    private suspend fun series(
        source: SourceEntity,
        title: String,
        year: Int?,
        showDir: String,
        sidecars: Sidecars,
        tmdb: TmdbPass,
        refreshMetadata: Boolean,
        now: Long,
    ): ItemEntity {
        val key = FilenameParser.showKey(title)
        val series = dao.seriesByKey(key) ?: ItemEntity(
            kind = ItemKind.SERIES,
            sourceId = source.id,
            matchKey = key,
            title = title,
            sortTitle = MetadataMerge.sortKey(title),
            year = year,
            addedAt = now,
        ).let { it.copy(id = dao.insert(it)) }

        if (series.id in refreshedSeries) return series
        val local = sidecars.forShow(showDir)
        val nfoChanged = local.nfo != null && series.nfoPath != local.nfo.relativePath
        val needsLookup = series.matchedAt == null || refreshMetadata || nfoChanged ||
            (local.poster != null && !series.posterPath.isCachedArtwork())
        // A refresh TMDB cannot be asked for now is not lost: the show is left unmatched (see saveShow).
        val refreshLeft = refreshMetadata && series.matchedAt != null
        val nothingToRead = !tmdb.open && local.nfo == null && local.poster == null && !refreshLeft
        if (!needsLookup || nothingToRead) return series
        // Once a pass, answered or not: a show TMDB did not answer stays unmatched, and so do its
        // episodes, until a later pass asks again.
        refreshedSeries += series.id

        val kept = files.nfo(source, local.nfo, series)
        val nfo = kept.info
        val tv = tmdb.ask { lookupShow(it, series.userTmdbId, nfo, title, year) }
        val art = LocalArt(
            poster = local.poster?.let { files.copyArtwork(source, it) },
            fanart = local.fanart?.let { files.copyArtwork(source, it) },
            logo = local.logo?.let { files.copyArtwork(source, it) },
        )
        return saveShow(series.copy(nfoPath = kept.path, nfo = kept.text), nfo, tv, art, tmdb.client, now, refreshMetadata)
    }

    /**
     * Asks TMDB all a new match needs (the show, then each of its seasons the library holds), then
     * returns the writes that apply it to the show, its seasons and episodes, for the caller to run at
     * once ([LibraryScanner.identify]): TMDB out of reach throws before anything is written.
     */
    suspend fun identify(series: ItemEntity, tmdbId: Int?, tmdb: TmdbClient): suspend () -> Unit {
        val (title, year) = showQuery(series)
        val nfo = series.nfo?.let(NfoParser::parse)
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
        val path = dao.episodesOfSeries(series.id).firstNotNullOfOrNull { it.filePath }
            ?: return ParsedMovie(series.title, series.year)
        val folders = path.split('/').dropLast(1)
        val title = FilenameParser.parseEpisode(path.substringAfterLast('/'), folders)?.showTitle ?: series.title
        return ParsedMovie(title, folders.firstOrNull()?.let { FilenameParser.parseMovie(it).year })
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
        val nfo = nfoInfo?.takeUnless { MetadataMerge.describesOther(it, base.userTmdbId, null) }
        val details = tv?.details(tmdb?.tvCertification(tv), tmdb?.pickLogo(tv.images))
        val matchedAt = if (lookup is TmdbAnswer.Given) now else base.matchedAt.takeUnless { refresh }
        val series = MetadataMerge.merge(base, nfo, details, art).copy(matchedAt = matchedAt)
        dao.saveScanned(series)
        MetadataMerge.cast(series.id, nfo, tv?.credits)?.let { dao.replaceCast(series.id, it) }
        return series
    }

    private suspend fun season(
        source: SourceEntity,
        series: ItemEntity,
        number: Int,
        cache: SeasonCache,
        now: Long,
        localPoster: RemoteFile?,
    ): ItemEntity {
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

        val meta = series.tmdbId?.let { cache.get(it, number).value }
        if (meta != null && season.matchedAt == null) {
            season = season.withSeasonDetails(meta, now)
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
                val nfo = episode.nfo?.let(NfoParser::parse)
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
