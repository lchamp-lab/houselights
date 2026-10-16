package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser
import app.houselights.core.yearOf
import kotlinx.coroutines.flow.first
import java.util.Locale

/** Shows: each episode file, its season and its show, with their NFOs, artwork and TMDB match. */
internal class ShowIndexer(private val dao: LibraryDao, private val files: ScanFiles) {
    /** Shows already looked up during the current scan (full refresh only). */
    private val refreshedSeries = mutableSetOf<Long>()

    /** A new scan looks shows up again. */
    fun startPass() = refreshedSeries.clear()

    suspend fun process(
        source: SourceEntity,
        file: RemoteFile,
        existing: ItemEntity?,
        tmdb: TmdbClient?,
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
        val nfoText = local.nfo?.let { files.readText(source, it) }
        val nfo = nfoText?.let(NfoParser::parse)
        val meta = series.tmdbId?.let { seasons.get(it, parsed.season) }?.episodes
            ?.firstOrNull { it.episodeNumber == parsed.episode }

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
        val episode = base.copy(
            parentId = season.id,
            seriesId = series.id,
            seasonNumber = parsed.season,
            episodeNumber = parsed.episode,
            sortTitle = "%03d-%04d".format(Locale.ROOT, parsed.season, parsed.episode),
            nfoPath = local.nfo?.relativePath,
            nfo = nfoText,
        ).withEpisodeDetails(
            nfo = nfo,
            meta = meta,
            thumb = local.thumb?.let { files.copyArtwork(source, it) },
            matchedAt = if (tmdb != null || nfo != null) now else base.matchedAt,
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

    private suspend fun series(
        source: SourceEntity,
        title: String,
        year: Int?,
        showDir: String,
        sidecars: Sidecars,
        tmdb: TmdbClient?,
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
        if (!needsLookup || (tmdb == null && local.nfo == null && local.poster == null)) return series
        refreshedSeries += series.id

        val nfoText = local.nfo?.let { files.readText(source, it) }
        val nfo = nfoText?.let(NfoParser::parse)
        val tv = tmdb?.let { lookupShow(it, series.userTmdbId, nfo, title, year) }
        val art = LocalArt(
            poster = local.poster?.let { files.copyArtwork(source, it) },
            fanart = local.fanart?.let { files.copyArtwork(source, it) },
            logo = local.logo?.let { files.copyArtwork(source, it) },
        )
        return saveShow(series.copy(nfoPath = local.nfo?.relativePath, nfo = nfoText), nfo, tv, art, tmdb, now)
    }

    suspend fun identify(series: ItemEntity, tmdbId: Int?, tmdb: TmdbClient) {
        val (title, year) = showQuery(series)
        val nfo = series.nfo?.let(NfoParser::parse)
        // Fetched before anything changes: an unreachable TMDB throws here.
        val tv = if (tmdbId != null) tmdb.tv(tmdbId) else lookupShow(tmdb, null, nfo, title, year)
        dao.setUserTmdbId(series.id, tmdbId)
        if (tv?.id != series.tmdbId) dao.clearUserArtwork(series.id)
        val fresh = dao.item(series.id) ?: return
        val now = System.currentTimeMillis()
        val saved = saveShow(MetadataMerge.withoutDetails(fresh, title, year), nfo, tv, LocalArt.of(fresh), tmdb, now)
        refreshSeasons(saved, tmdb, now)
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

    /** Saves a show's details (see [MetadataMerge]) and its cast. */
    private suspend fun saveShow(base: ItemEntity, nfoInfo: NfoInfo?, tv: TmdbTv?, art: LocalArt, tmdb: TmdbClient?, now: Long): ItemEntity {
        val nfo = nfoInfo?.takeUnless { MetadataMerge.describesOther(it, base.userTmdbId, null) }
        val details = tv?.details(tmdb?.tvCertification(tv), tmdb?.pickLogo(tv.images))
        val series = MetadataMerge.merge(base, nfo, details, art).copy(matchedAt = now)
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

        val meta = series.tmdbId?.let { cache.get(it, number) }
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
     * After a new match, seasons and episodes take the new show's details at once, from
     * what the library already knows of their files (NFO, local thumbs).
     */
    private suspend fun refreshSeasons(series: ItemEntity, tmdb: TmdbClient, now: Long) {
        val cache = SeasonCache(tmdb)
        for (season in dao.children(series.id).filter { it.kind == ItemKind.SEASON }) {
            val meta = series.tmdbId?.let { id -> season.seasonNumber?.let { cache.get(id, it) } }
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

    /** One TMDB season request per (show, season) during a scan. */
    class SeasonCache(private val tmdb: TmdbClient?) {
        private val cache = mutableMapOf<Pair<Int, Int>, TmdbSeason?>()
        suspend fun get(tvId: Int, season: Int): TmdbSeason? {
            if (tmdb == null) return null
            val key = tvId to season
            if (key !in cache) cache[key] = tmdb.season(tvId, season)
            return cache[key]
        }
    }
}
