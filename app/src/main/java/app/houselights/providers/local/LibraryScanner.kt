package app.houselights.providers.local

import android.util.Log
import app.houselights.core.NfoActor
import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser
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
    private val artwork: ArtworkCache,
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
        val listing = RemoteBrowser.of(source).list(source)
        val files = listing.videos
        val sidecars = Sidecars(listing.sidecars, files)
        val known = dao.filesOf(source.id).associateBy { it.filePath }

        val present = files.map { it.relativePath }.toSet()
        val missing = known.values.filter { it.filePath !in present }.map { it.id }
        if (missing.isNotEmpty()) dao.deleteItems(missing)

        val tmdb = tmdbKey()?.takeIf { it.isNotBlank() }?.let(::TmdbClient)
        val todo = files.filter { f ->
            val item = known[f.relativePath]
            val local = sidecars.forVideo(f)
            item == null || item.mediaInfo == null || refreshMetadata ||
                (tmdb != null && item.matchedAt == null) ||
                // An NFO or artwork placed next to the file since the last scan.
                (local.nfo != null && item.nfoPath != local.nfo.relativePath) ||
                (local.poster != null && item.posterPath?.startsWith("file:") != true)
        }
        _state.update { it.copy(total = todo.size) }
        Log.i(TAG, "${source.name}: ${files.size} files, ${missing.size} removed, ${todo.size} to process")

        val seasons = SeasonCache(tmdb)
        todo.forEachIndexed { index, file ->
            _state.update { it.copy(processed = index, current = file.relativePath.substringAfterLast('/')) }
            runCatching {
                when (source.kind) {
                    SourceKind.MOVIES -> processMovie(source, file, known[file.relativePath], tmdb, sidecars, refreshMetadata)
                    SourceKind.SHOWS -> processEpisode(source, file, known[file.relativePath], tmdb, seasons, sidecars, refreshMetadata)
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
        sidecars: Sidecars,
        refreshMetadata: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val folders = file.relativePath.split('/').dropLast(1)
        val fromFile = FilenameParser.parseMovie(file.relativePath.substringAfterLast('/'))
        // "Title (Year)/movie.mkv": the folder name is often more reliable than the file name.
        val fromFolder = folders.lastOrNull()?.let(FilenameParser::parseMovie)
        val parsed = if (fromFile.year == null && fromFolder?.year != null) fromFolder else fromFile
        val local = sidecars.forVideo(file)

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

        val nfoText = local.nfo?.let { readText(source, it) }
        val nfo = nfoText?.let(NfoParser::parse)
        val needsTmdb = tmdb != null && (item.matchedAt == null || refreshMetadata || nfo != null)
        if (nfo == null && !needsTmdb && local.poster == null && local.fanart == null && local.logo == null) return

        // Identify exactly when the NFO carries an id; search by title otherwise.
        val movie = if (!needsTmdb) null else {
            val id = nfo?.tmdbId ?: nfo?.imdbId?.let { tmdb!!.findByImdb(it).first }
            id?.let { tmdb!!.movieById(it) } ?: tmdb!!.findMovie(nfo?.title ?: parsed.title, nfo?.year ?: parsed.year)
        }
        val title = nfo?.title ?: movie?.title ?: item.title
        item = item.copy(
            tmdbId = movie?.id ?: nfo?.tmdbId ?: item.tmdbId,
            title = title,
            sortTitle = sortKey(nfo?.sortTitle ?: title),
            originalTitle = nfo?.originalTitle ?: movie?.originalTitle ?: item.originalTitle,
            overview = nfo?.plot ?: movie?.overview?.ifBlank { null } ?: item.overview,
            tagline = nfo?.tagline ?: movie?.tagline?.ifBlank { null } ?: item.tagline,
            year = nfo?.year ?: nfo?.premiered?.take(4)?.toIntOrNull() ?: movie?.releaseDate?.take(4)?.toIntOrNull() ?: item.year,
            runtimeMin = nfo?.runtimeMin ?: movie?.runtime?.takeIf { it > 0 } ?: item.runtimeMin,
            certification = nfo?.certification ?: movie?.let { tmdb?.movieCertification(it) } ?: item.certification,
            rating = nfo?.rating ?: movie?.voteAverage?.takeIf { it > 0 } ?: item.rating,
            genres = nfo?.genres?.takeIf { it.isNotEmpty() }?.joinToString("|") ?: movie?.genres?.joinToString("|") { it.name } ?: item.genres,
            directors = nfo?.directors?.takeIf { it.isNotEmpty() }?.joinToString("|")
                ?: movie?.credits?.crew?.filter { it.job == "Director" }?.joinToString("|") { it.name } ?: item.directors,
            posterPath = local.poster?.let { artwork.copy(source, it) } ?: nfo?.posterUrl ?: movie?.posterPath ?: item.posterPath,
            backdropPath = local.fanart?.let { artwork.copy(source, it) } ?: nfo?.fanartUrl ?: movie?.backdropPath ?: item.backdropPath,
            logoPath = local.logo?.let { artwork.copy(source, it) } ?: nfo?.logoUrl ?: movie?.let { tmdb?.pickLogo(it.images) } ?: item.logoPath,
            stillPath = local.thumb?.let { artwork.copy(source, it) } ?: item.stillPath,
            nfoPath = local.nfo?.relativePath,
            nfo = nfoText,
            matchedAt = if (tmdb != null || nfo != null) now else item.matchedAt,
        ).withNfoStreams(nfo)
        dao.update(item)
        when {
            nfo != null && nfo.actors.isNotEmpty() -> dao.replaceCast(item.id, castOf(item.id, nfo.actors))
            movie != null -> dao.replaceCast(item.id, castOf(item.id, movie.credits))
        }
    }

    /** NFO stream details stand in when the file itself could not be read. */
    private fun ItemEntity.withNfoStreams(nfo: NfoInfo?): ItemEntity {
        val streams = nfo?.streams ?: return this
        val media = MediaProbe.decode(mediaInfo)
        if (media?.video != null) return this
        val merged = NfoStreamDetails.toMediaInfo(streams, media)
        return copy(mediaInfo = MediaProbe.encode(merged), badges = merged.badges().ifEmpty { badges.split('|') }.joinToString("|"))
    }

    private suspend fun readText(source: SourceEntity, file: RemoteFile): String? = runCatching {
        String(RemoteBrowser.of(source).readPrefix(source, file.relativePath, 1 shl 20), Charsets.UTF_8)
    }.getOrNull()

    // --- TV shows ------------------------------------------------------------------------------

    private suspend fun processEpisode(
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
        val nfoText = local.nfo?.let { readText(source, it) }
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
            badges = FilenameParser.badges(file.relativePath).joinToString("|"),
            addedAt = now,
        )
        val episode = base.copy(
            parentId = season.id,
            seriesId = series.id,
            seasonNumber = parsed.season,
            episodeNumber = parsed.episode,
            title = nfo?.title ?: meta?.name ?: base.title,
            sortTitle = "%03d-%04d".format(parsed.season, parsed.episode),
            overview = nfo?.plot ?: meta?.overview?.ifBlank { null } ?: base.overview,
            stillPath = local.thumb?.let { artwork.copy(source, it) } ?: meta?.stillPath ?: base.stillPath,
            runtimeMin = nfo?.runtimeMin ?: meta?.runtime ?: base.runtimeMin,
            rating = nfo?.rating ?: meta?.voteAverage?.takeIf { it > 0 } ?: base.rating,
            year = nfo?.premiered?.take(4)?.toIntOrNull() ?: meta?.airDate?.take(4)?.toIntOrNull() ?: base.year,
            nfoPath = local.nfo?.relativePath,
            nfo = nfoText,
            matchedAt = if (tmdb != null || nfo != null) now else base.matchedAt,
        )
        val probed = (if (episode.mediaInfo == null) mediaFields(source, file, episode) else episode).withNfoStreams(nfo)
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
        showDir: String,
        sidecars: Sidecars,
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

        if (series.id in refreshedSeries) return series
        val local = sidecars.forShow(showDir)
        val nfoChanged = local.nfo != null && series.nfoPath != local.nfo.relativePath
        val needsLookup = series.matchedAt == null || refreshMetadata || nfoChanged ||
            (local.poster != null && series.posterPath?.startsWith("file:") != true)
        if (!needsLookup || (tmdb == null && local.nfo == null && local.poster == null)) return series
        refreshedSeries += series.id

        val nfoText = local.nfo?.let { readText(source, it) }
        val nfo = nfoText?.let(NfoParser::parse)
        val tv = tmdb?.let { t ->
            val id = nfo?.tmdbId ?: nfo?.imdbId?.let { t.findByImdb(it).second }
            id?.let { t.tvById(it) } ?: t.findTv(nfo?.title ?: title, nfo?.year ?: year)
        }
        val name = nfo?.title ?: tv?.name ?: series.title
        series = series.copy(
            tmdbId = tv?.id ?: nfo?.tmdbId ?: series.tmdbId,
            title = name,
            sortTitle = sortKey(nfo?.sortTitle ?: name),
            originalTitle = nfo?.originalTitle ?: tv?.originalName ?: series.originalTitle,
            overview = nfo?.plot ?: tv?.overview?.ifBlank { null } ?: series.overview,
            tagline = nfo?.tagline ?: tv?.tagline?.ifBlank { null } ?: series.tagline,
            year = nfo?.year ?: nfo?.premiered?.take(4)?.toIntOrNull() ?: tv?.firstAirDate?.take(4)?.toIntOrNull() ?: year,
            endYear = tv?.lastAirDate?.take(4)?.toIntOrNull() ?: series.endYear,
            continuing = nfo?.status?.let { it.contains("Continuing", true) || it.contains("Returning", true) }
                ?: (tv?.status == "Returning Series" || tv?.status == "In Production"),
            runtimeMin = nfo?.runtimeMin ?: tv?.episodeRunTime?.firstOrNull() ?: series.runtimeMin,
            certification = nfo?.certification ?: tv?.let { tmdb.tvCertification(it) } ?: series.certification,
            rating = nfo?.rating ?: tv?.voteAverage?.takeIf { it > 0 } ?: series.rating,
            genres = nfo?.genres?.takeIf { it.isNotEmpty() }?.joinToString("|") ?: tv?.genres?.joinToString("|") { it.name } ?: series.genres,
            posterPath = local.poster?.let { artwork.copy(source, it) } ?: nfo?.posterUrl ?: tv?.posterPath ?: series.posterPath,
            backdropPath = local.fanart?.let { artwork.copy(source, it) } ?: nfo?.fanartUrl ?: tv?.backdropPath ?: series.backdropPath,
            logoPath = local.logo?.let { artwork.copy(source, it) } ?: nfo?.logoUrl ?: tv?.let { tmdb.pickLogo(it.images) } ?: series.logoPath,
            nfoPath = local.nfo?.relativePath,
            nfo = nfoText,
            matchedAt = now,
        )
        dao.update(series)
        when {
            nfo != null && nfo.actors.isNotEmpty() -> dao.replaceCast(series.id, castOf(series.id, nfo.actors))
            tv != null -> dao.replaceCast(series.id, castOf(series.id, tv.credits))
        }
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
        if (localPoster != null && season.posterPath?.startsWith("file:") != true) {
            artwork.copy(source, localPoster)?.let { season = season.copy(posterPath = it); dao.update(season) }
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

        private fun castOf(itemId: Long, actors: List<NfoActor>) =
            actors.sortedBy { it.order ?: Int.MAX_VALUE }.take(30).mapIndexed { i, a ->
                CastEntity(itemId, i, a.name, a.role?.ifBlank { null }, a.thumb)
            }

        private fun castOf(itemId: Long, credits: TmdbCredits) =
            credits.cast.sortedBy { it.order }.take(30).mapIndexed { i, c ->
                CastEntity(itemId, i, c.name, c.character?.ifBlank { null }, c.profilePath)
            }
    }
}
