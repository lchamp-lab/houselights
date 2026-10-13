package app.houselights.providers.local

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanState(
    val running: Boolean = false,
    val sourceName: String? = null,
    val processed: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    /** Last error per source name, kept after the scan ends. */
    val errors: Map<String, ScanError> = emptyMap(),
)

/**
 * Incremental scanner: lists the files of each source, removes the missing ones,
 * and identifies new ones on TMDB. Files already known are never looked up again,
 * unless they were never matched (no TMDB key at the time) or a full refresh is asked.
 */
class LibraryScanner(
    private val dao: LibraryDao,
    private val tmdbKey: () -> String?,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits while scanning, so screens show new entries progressively. */
    val changed: SharedFlow<Unit> = _changed

    private var job: Job? = null

    /** Shows already looked up during the current scan (full refresh only). */
    private val refreshedSeries = mutableSetOf<Long>()

    fun scanAll(refreshMetadata: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            refreshedSeries.clear()
            val errors = mutableMapOf<String, ScanError>()
            for (source in dao.sources()) {
                runCatching { scan(source, refreshMetadata) }.onFailure { e ->
                    Log.w(TAG, "Scan of ${source.name} failed", e)
                    errors[source.name] = ScanErrors.classify(e)
                }
            }
            dao.deleteEmptySeasons()
            dao.deleteEmptySeries()
            _state.value = ScanState(errors = errors)
            _changed.emit(Unit)
        }
    }

    private suspend fun scan(source: SourceEntity, refreshMetadata: Boolean) {
        _state.update { it.copy(running = true, sourceName = source.name, processed = 0, total = 0, current = null) }
        val files = RemoteBrowser.of(source).listVideos(source)
        val known = dao.filesOf(source.id).associateBy { it.filePath }

        val present = files.map { it.relativePath }.toSet()
        val missing = known.values.filter { it.filePath !in present }.map { it.id }
        if (missing.isNotEmpty()) dao.deleteItems(missing)

        val tmdb = tmdbKey()?.takeIf { it.isNotBlank() }?.let(::TmdbClient)
        val todo = files.filter { f ->
            val item = known[f.relativePath]
            item == null || item.mediaInfo == null || (tmdb != null && (item.matchedAt == null || refreshMetadata))
        }
        _state.update { it.copy(total = todo.size) }
        Log.i(TAG, "${source.name}: ${files.size} files, ${missing.size} removed, ${todo.size} to process")

        val seasons = SeasonCache(tmdb)
        todo.forEachIndexed { index, file ->
            _state.update { it.copy(processed = index, current = file.relativePath.substringAfterLast('/')) }
            runCatching {
                when (source.kind) {
                    SourceKind.MOVIES -> processMovie(source, file, known[file.relativePath], tmdb, refreshMetadata)
                    SourceKind.SHOWS -> processEpisode(source, file, known[file.relativePath], tmdb, seasons, refreshMetadata)
                }
            }.onFailure { Log.w(TAG, "Failed on ${file.relativePath}", it) }
            if (index % 5 == 4) _changed.emit(Unit)
        }
        dao.updateSource(source.copy(lastScanAt = System.currentTimeMillis()))
        _changed.emit(Unit)
    }

    // --- Movies --------------------------------------------------------------------------------

    private suspend fun processMovie(
        source: SourceEntity,
        file: RemoteFile,
        existing: ItemEntity?,
        tmdb: TmdbClient?,
        refreshMetadata: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val folders = file.relativePath.split('/').dropLast(1)
        val fromFile = FilenameParser.parseMovie(file.relativePath.substringAfterLast('/'))
        // "Title (Year)/movie.mkv": the folder name is often more reliable than the file name.
        val fromFolder = folders.lastOrNull()?.let(FilenameParser::parseMovie)
        val parsed = if (fromFile.year == null && fromFolder?.year != null) fromFolder else fromFile

        var item = existing ?: ItemEntity(
            kind = ItemKind.MOVIE,
            sourceId = source.id,
            title = parsed.title,
            sortTitle = sortKey(parsed.title),
            year = parsed.year,
            filePath = file.relativePath,
            fileSize = file.size,
            badges = FilenameParser.badges(file.relativePath).joinToString("|"),
            addedAt = now,
        )
        if (existing == null) item = item.copy(id = dao.insert(item))
        item = withMediaInfo(source, file, item)
        if (tmdb == null || (item.matchedAt != null && !refreshMetadata)) return

        val movie = tmdb.findMovie(parsed.title, parsed.year)
        if (movie == null) {
            dao.update(item.copy(matchedAt = now))
            return
        }
        dao.update(
            item.copy(
                tmdbId = movie.id,
                title = movie.title,
                sortTitle = sortKey(movie.title),
                originalTitle = movie.originalTitle,
                overview = movie.overview?.ifBlank { null },
                tagline = movie.tagline?.ifBlank { null },
                year = movie.releaseDate?.take(4)?.toIntOrNull() ?: parsed.year,
                runtimeMin = movie.runtime?.takeIf { it > 0 },
                certification = tmdb.movieCertification(movie),
                rating = movie.voteAverage?.takeIf { it > 0 },
                genres = movie.genres.joinToString("|") { it.name },
                directors = movie.credits.crew.filter { it.job == "Director" }.joinToString("|") { it.name },
                posterPath = movie.posterPath,
                backdropPath = movie.backdropPath,
                logoPath = tmdb.pickLogo(movie.images),
                matchedAt = now,
            )
        )
        dao.replaceCast(item.id, castOf(item.id, movie.credits))
    }

    // --- TV shows ------------------------------------------------------------------------------

    private suspend fun processEpisode(
        source: SourceEntity,
        file: RemoteFile,
        existing: ItemEntity?,
        tmdb: TmdbClient?,
        seasons: SeasonCache,
        refreshMetadata: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val folders = file.relativePath.split('/').dropLast(1)
        val parsed = FilenameParser.parseEpisode(file.relativePath.substringAfterLast('/'), folders) ?: return
        val showTitle = parsed.showTitle ?: return
        val showYear = folders.firstOrNull()?.let { FilenameParser.parseMovie(it).year }

        val series = series(source, showTitle, showYear, tmdb, refreshMetadata, now)
        val season = season(source, series, parsed.season, seasons, now)
        val meta = series.tmdbId?.let { seasons.get(it, parsed.season) }?.episodes
            ?.firstOrNull { it.episodeNumber == parsed.episode }

        val base = existing ?: ItemEntity(
            kind = ItemKind.EPISODE,
            sourceId = source.id,
            title = "",
            sortTitle = "",
            filePath = file.relativePath,
            fileSize = file.size,
            badges = FilenameParser.badges(file.relativePath).joinToString("|"),
            addedAt = now,
        )
        val episode = base.copy(
            parentId = season.id,
            seriesId = series.id,
            seasonNumber = parsed.season,
            episodeNumber = parsed.episode,
            title = meta?.name ?: base.title,
            sortTitle = "%03d-%04d".format(parsed.season, parsed.episode),
            overview = meta?.overview?.ifBlank { null } ?: base.overview,
            stillPath = meta?.stillPath ?: base.stillPath,
            runtimeMin = meta?.runtime ?: base.runtimeMin,
            rating = meta?.voteAverage?.takeIf { it > 0 } ?: base.rating,
            year = meta?.airDate?.take(4)?.toIntOrNull() ?: base.year,
            matchedAt = if (tmdb != null) now else base.matchedAt,
        )
        val probed = if (episode.mediaInfo == null) mediaFields(source, file, episode) else episode
        if (existing == null) {
            dao.insert(probed)
            // New episode: the show moves up in "recently added".
            dao.update(series.copy(addedAt = now))
        } else {
            dao.update(probed)
        }
    }

    /** Reads the file once and stores what it contains, with format badges for filters. */
    private suspend fun withMediaInfo(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity {
        if (item.mediaInfo != null) return item
        val updated = mediaFields(source, file, item)
        dao.update(updated)
        return updated
    }

    private suspend fun mediaFields(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity {
        val media = MediaProbe.probe(source, file)
        val badges = media.badges().ifEmpty { FilenameParser.badges(file.relativePath) }
        return item.copy(mediaInfo = MediaProbe.encode(media), badges = badges.joinToString("|"))
    }

    private suspend fun series(
        source: SourceEntity,
        title: String,
        year: Int?,
        tmdb: TmdbClient?,
        refreshMetadata: Boolean,
        now: Long,
    ): ItemEntity {
        val key = FilenameParser.showKey(title)
        var series = dao.seriesByKey(key) ?: ItemEntity(
            kind = ItemKind.SERIES,
            sourceId = source.id,
            matchKey = key,
            title = title,
            sortTitle = sortKey(title),
            year = year,
            addedAt = now,
        ).let { it.copy(id = dao.insert(it)) }

        val needsLookup = tmdb != null && (series.matchedAt == null || (refreshMetadata && series.id !in refreshedSeries))
        if (!needsLookup) return series
        refreshedSeries += series.id
        val tv = tmdb!!.findTv(title, year)
        series = if (tv == null) series.copy(matchedAt = now) else series.copy(
            tmdbId = tv.id,
            title = tv.name,
            sortTitle = sortKey(tv.name),
            originalTitle = tv.originalName,
            overview = tv.overview?.ifBlank { null },
            tagline = tv.tagline?.ifBlank { null },
            year = tv.firstAirDate?.take(4)?.toIntOrNull() ?: year,
            endYear = tv.lastAirDate?.take(4)?.toIntOrNull(),
            continuing = tv.status == "Returning Series" || tv.status == "In Production",
            runtimeMin = tv.episodeRunTime.firstOrNull(),
            certification = tmdb.tvCertification(tv),
            rating = tv.voteAverage?.takeIf { it > 0 },
            genres = tv.genres.joinToString("|") { it.name },
            directors = "",
            posterPath = tv.posterPath,
            backdropPath = tv.backdropPath,
            logoPath = tmdb.pickLogo(tv.images),
            matchedAt = now,
        )
        dao.update(series)
        if (tv != null) dao.replaceCast(series.id, castOf(series.id, tv.credits))
        return series
    }

    private suspend fun season(source: SourceEntity, series: ItemEntity, number: Int, cache: SeasonCache, now: Long): ItemEntity {
        var season = dao.season(series.id, number) ?: ItemEntity(
            kind = ItemKind.SEASON,
            sourceId = source.id,
            parentId = series.id,
            seriesId = series.id,
            matchKey = "${series.id}:$number",
            title = "",
            sortTitle = "%03d".format(number),
            seasonNumber = number,
            addedAt = now,
        ).let { it.copy(id = dao.insert(it)) }

        val meta = series.tmdbId?.let { cache.get(it, number) }
        if (meta != null && season.matchedAt == null) {
            season = season.copy(
                title = meta.name ?: "",
                overview = meta.overview?.ifBlank { null },
                posterPath = meta.posterPath,
                year = meta.airDate?.take(4)?.toIntOrNull(),
                matchedAt = now,
            )
            dao.update(season)
        }
        return season
    }

    /** One TMDB season request per (show, season) during a scan. */
    private class SeasonCache(private val tmdb: TmdbClient?) {
        private val cache = mutableMapOf<Pair<Int, Int>, TmdbSeason?>()
        suspend fun get(tvId: Int, season: Int): TmdbSeason? {
            if (tmdb == null) return null
            val key = tvId to season
            if (key !in cache) cache[key] = tmdb.season(tvId, season)
            return cache[key]
        }
    }

    companion object {
        private const val TAG = "LibraryScanner"
        private val ARTICLES = Regex("""(?i)^(the|a|an|le|la|les|l'|un|une|des)\s+""")

        fun sortKey(title: String): String = ARTICLES.replace(title.trim(), "").lowercase()

        private fun castOf(itemId: Long, credits: TmdbCredits) =
            credits.cast.sortedBy { it.order }.take(30).mapIndexed { i, c ->
                CastEntity(itemId, i, c.name, c.character?.ifBlank { null }, c.profilePath)
            }
    }
}
