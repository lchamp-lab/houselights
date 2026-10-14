package app.houselights.providers.local

import android.util.Log
import app.houselights.core.NfoActor
import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser
import app.houselights.core.suspendRunCatching
import app.houselights.core.yearOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

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
 *
 * Choices made by the user (match, artwork) and their viewing state are never overwritten:
 * every write goes through [LibraryDao.saveScanned].
 */
class LibraryScanner(
    private val dao: LibraryDao,
    /** The TMDB client in use, or null without a key. */
    private val tmdb: () -> TmdbClient?,
    private val scope: CoroutineScope,
    private val artwork: ArtworkCache,
    /** True while a film plays: the scan waits, so as not to read the NAS while the player streams from it. */
    private val playing: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits while scanning, so screens show new entries progressively. */
    val changed: SharedFlow<Unit> = _changed

    private val lock = Mutex()

    /** Shows already looked up during the current scan (full refresh only). */
    private val refreshedSeries = mutableSetOf<Long>()

    /** Collections already asked to TMDB during the current scan. */
    private val refreshedCollections = mutableSetOf<Long>()

    fun scanAll(refreshMetadata: Boolean = false) {
        synchronized(passes) {
            // A folder added or a refresh asked during a scan is not lost: one more pass follows.
            passes.ask(refreshMetadata)
            if (passes.running) return
            passes.running = true
        }
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val refresh = synchronized(passes) { passes.next() } ?: break
                    scanAllSources(refresh)
                }
            } finally {
                synchronized(passes) { passes.running = false }
            }
        }
    }

    /** Scans asked for: one running, at most one more waiting. Guarded by its own monitor. */
    private class Passes {
        var running = false
        private var waiting = false
        private var refresh = false

        fun ask(refreshMetadata: Boolean) {
            waiting = true
            refresh = refresh || refreshMetadata
        }

        /** The next pass to run (true: refresh metadata), or null when none is waiting: the loop ends. */
        fun next(): Boolean? {
            if (!waiting) {
                running = false
                return null
            }
            waiting = false
            return refresh.also { refresh = false }
        }
    }

    private val passes = Passes()

    /**
     * Scans unless the last scan ended less than [maxAgeMs] ago. A NAS cannot tell the app that
     * files were added, so the app looks again by itself; an incremental scan only lists the
     * folders and processes what is new, which keeps it light.
     */
    fun scanIfStale(maxAgeMs: Long) {
        if (System.currentTimeMillis() - lastScanEnd < maxAgeMs) return
        scanAll()
    }

    @Volatile
    private var lastScanEnd = 0L

    /**
     * Removes cached artwork and cast rows nothing refers to anymore. Runs after every scan,
     * and on demand from the settings; never while an item is being processed.
     */
    suspend fun cleanup(): CleanupResult = withContext(Dispatchers.IO) {
        lock.withLock { cleanupLocked() }
    }

    /** Tells screens the library changed outside a scan (a choice made by the user). */
    fun notifyChanged() {
        _changed.tryEmit(Unit)
    }

    /**
     * Matches a film or show with the TMDB title the user chose, or goes back to automatic
     * matching when [tmdbId] is null. Seasons and episodes of a show follow. Throws when TMDB
     * cannot be reached, leaving the item as it was.
     */
    suspend fun identify(itemId: Long, tmdbId: Int?) {
        withContext(Dispatchers.IO) {
            val tmdb = client() ?: error("No TMDB key")
            lock.withLock {
                val item = dao.item(itemId) ?: return@withLock
                when (item.kind) {
                    ItemKind.MOVIE -> identifyMovie(item, tmdbId, tmdb)
                    ItemKind.SERIES -> identifyShow(item, tmdbId, tmdb)
                    else -> error("Only films and shows can be identified")
                }
                // The film may have left its collection for another one.
                dao.deleteEmptyCollections()
            }
        }
        _changed.emit(Unit)
    }

    private fun client(): TmdbClient? = tmdb()

    private suspend fun cleanupLocked(): CleanupResult {
        val orphanCast = dao.deleteOrphanCast()
        // Based on what the library references, not on what the NAS shows: an unreachable
        // NAS never causes artwork to be deleted.
        val result = artwork.sweep(dao.cachedArtworkPaths())
        if (result.deletedFiles > 0 || orphanCast > 0) {
            Log.i(TAG, "Cleanup: ${result.deletedFiles} images (${result.freedBytes / 1024} KB), $orphanCast cast rows")
        }
        return result
    }

    private suspend fun scanAllSources(refreshMetadata: Boolean) {
        refreshedSeries.clear()
        refreshedCollections.clear()
        val errors = mutableMapOf<String, ScanError>()
        var changed = false
        try {
            for (source in dao.sources()) {
                suspendRunCatching { changed = scan(source, refreshMetadata) || changed }.onFailure { e ->
                    Log.w(TAG, "Scan of ${source.name} failed", e)
                    errors[source.name] = ScanErrors.classify(e)
                }
            }
            lock.withLock {
                suspendRunCatching {
                    // Rows a folder removed during the scan may have received meanwhile.
                    dao.deleteOrphanItems()
                    dao.deleteEmptySeasons()
                    dao.deleteEmptySeries()
                    dao.deleteEmptyCollections()
                    // Pictures only become unused when titles change or go.
                    if (changed) cleanupLocked()
                }.onFailure { Log.w(TAG, "Cleanup failed", it) }
            }
        } finally {
            // Whatever happened, the scan is over: the settings must not show it running forever.
            _state.value = ScanState(errors = errors)
            lastScanEnd = System.currentTimeMillis()
            SmbBrowser.closeAll()
        }
        // A scan that found nothing new leaves screens as they are.
        if (changed) _changed.emit(Unit)
    }

    /**
     * Removes a folder and everything found in it. Waits for the item being processed; a scan of that
     * folder stops at its next file.
     */
    suspend fun removeSource(source: SourceEntity) {
        withContext(Dispatchers.IO) {
            lock.withLock { dao.removeSource(source.id) }
        }
        _changed.emit(Unit)
    }

    /** True when the library changed: files were added, updated or removed. */
    private suspend fun scan(source: SourceEntity, refreshMetadata: Boolean): Boolean {
        _state.update { it.copy(running = true, sourceName = source.name, processed = 0, total = 0, current = null) }
        val listing = RemoteBrowser.of(source).list(source)
        val files = listing.videos
        val sidecars = Sidecars(listing.sidecars, files)
        val known = dao.filesOf(source.id).associateBy { it.filePath }

        val present = files.map { it.relativePath }.toSet()
        val missing = known.values.filter { it.filePath !in present }.map { it.id }
        if (missing.isNotEmpty()) lock.withLock { dao.deleteItems(missing) }

        val tmdb = client()
        val todo = files.filter { f -> needsWork(known[f.relativePath], sidecars.forVideo(f), tmdb != null, refreshMetadata) }
        _state.update { it.copy(total = todo.size) }
        Log.i(TAG, "${source.name}: ${files.size} files, ${missing.size} removed, ${todo.size} to process")

        val seasons = SeasonCache(tmdb)
        var unreachable = 0
        todo.forEachIndexed { index, file ->
            // Bounded, should the player never report back.
            withTimeoutOrNull(MAX_PAUSE_MS) { playing.first { !it } }
            _state.update { it.copy(processed = index, current = file.relativePath.substringAfterLast('/')) }
            suspendRunCatching {
                // One item at a time, so a choice made by the user waits for one item, not
                // for the whole scan. Read again: the item may have been edited meanwhile.
                lock.withLock {
                    // The folder was removed meanwhile: nothing more is added to it.
                    if (dao.source(source.id) == null) return@forEachIndexed
                    val existing = known[file.relativePath]?.let { dao.item(it.id) }
                    when (source.kind) {
                        SourceKind.MOVIES -> processMovie(source, file, existing, tmdb, sidecars, refreshMetadata)
                        SourceKind.SHOWS -> processEpisode(source, file, existing, tmdb, seasons, sidecars, refreshMetadata)
                    }
                }
            }.onSuccess {
                unreachable = 0
            }.onFailure { e ->
                Log.w(TAG, "Failed on ${file.relativePath}", e)
                // A NAS that stops answering would fail every remaining file, each after long timeouts.
                if (ScanErrors.isUnreachable(e) && ++unreachable >= MAX_UNREACHABLE) throw e
            }
            if (index % 5 == 4) _changed.emit(Unit)
        }
        dao.updateSource(source.copy(lastScanAt = System.currentTimeMillis()))
        val changed = missing.isNotEmpty() || todo.isNotEmpty()
        if (changed) _changed.emit(Unit)
        return changed
    }

    /** A file is processed when new, not described yet, to refresh, or when its NFO or artwork changed. */
    private fun needsWork(item: ItemEntity?, local: VideoSidecars, hasTmdb: Boolean, refreshMetadata: Boolean): Boolean =
        item == null || item.mediaInfo == null || refreshMetadata ||
            (hasTmdb && item.matchedAt == null) ||
            // An NFO or artwork placed next to the file, or removed, since the last scan.
            item.nfoPath != local.nfo?.relativePath ||
            (local.poster != null) != (item.posterPath.isCachedArtwork())

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
        val parsed = parseMovie(file.relativePath)
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
        // Sidecars that disappeared leave details to TMDB again.
        val sidecarsRemoved = (item.nfoPath != null && local.nfo == null) ||
            (local.poster == null && item.posterPath.isCachedArtwork())
        val needsTmdb = tmdb != null && (item.matchedAt == null || refreshMetadata || nfo != null || sidecarsRemoved)
        if (nfo == null && !needsTmdb && !sidecarsRemoved && local.poster == null && local.fanart == null && local.logo == null) return

        val movie = if (needsTmdb) lookupMovie(tmdb!!, item.userTmdbId, nfo, parsed, known = item.tmdbId) else null
        if (sidecarsRemoved) item = item.withoutDetails(parsed.title, parsed.year)
        val art = LocalArt(
            poster = local.poster?.let { artwork.copy(source, it) },
            fanart = local.fanart?.let { artwork.copy(source, it) },
            logo = local.logo?.let { artwork.copy(source, it) },
            thumb = local.thumb?.let { artwork.copy(source, it) },
        )
        saveMovie(item.copy(nfoPath = local.nfo?.relativePath, nfo = nfoText), nfo, movie, art, tmdb, now, refreshMetadata)
    }

    private suspend fun identifyMovie(item: ItemEntity, tmdbId: Int?, tmdb: TmdbClient) {
        val parsed = parseMovie(item.filePath ?: return)
        val nfo = item.nfo?.let(NfoParser::parse)
        // Fetched before anything changes: an unreachable TMDB throws here.
        val movie = if (tmdbId != null) tmdb.movie(tmdbId) else lookupMovie(tmdb, null, nfo, parsed)
        dao.setUserTmdbId(item.id, tmdbId)
        if (movie?.id != item.tmdbId) dao.clearUserArtwork(item.id)
        val fresh = dao.item(item.id) ?: return
        saveMovie(fresh.withoutDetails(parsed.title, parsed.year), nfo, movie, LocalArt.of(fresh), tmdb, System.currentTimeMillis())
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

    /** Details: NFO, then TMDB, then what [base] had. Artwork: local files, NFO, TMDB. */
    private suspend fun saveMovie(
        base: ItemEntity,
        nfoInfo: NfoInfo?,
        movie: TmdbMovie?,
        art: LocalArt,
        tmdb: TmdbClient?,
        now: Long,
        refresh: Boolean = false,
    ) {
        val nfo = nfoInfo?.takeUnless { describesOther(it, base.userTmdbId, movie?.imdbId) }
        // Without a fresh TMDB answer, the collection already known stays.
        val collectionId = collection(nfo, movie, tmdb, now, refresh) ?: base.collectionId.takeIf { movie == null }
        val title = nfo?.title ?: movie?.title ?: base.title
        val item = base.copy(
            tmdbId = movie?.id ?: nfo?.tmdbId ?: base.tmdbId,
            title = title,
            sortTitle = sortKey(nfo?.sortTitle ?: title),
            originalTitle = nfo?.originalTitle ?: movie?.originalTitle ?: base.originalTitle,
            overview = nfo?.plot ?: movie?.overview?.ifBlank { null } ?: base.overview,
            tagline = nfo?.tagline ?: movie?.tagline?.ifBlank { null } ?: base.tagline,
            year = nfo?.year ?: yearOf(nfo?.premiered) ?: yearOf(movie?.releaseDate) ?: base.year,
            runtimeMin = nfo?.runtimeMin ?: movie?.runtime?.takeIf { it > 0 } ?: base.runtimeMin,
            certification = nfo?.certification ?: movie?.let { tmdb?.movieCertification(it) } ?: base.certification,
            rating = nfo?.rating ?: movie?.voteAverage?.takeIf { it > 0 } ?: base.rating,
            genres = nfo?.genres?.takeIf { it.isNotEmpty() }?.joinToString("|") ?: movie?.genres?.joinToString("|") { it.name } ?: base.genres,
            directors = nfo?.directors?.takeIf { it.isNotEmpty() }?.joinToString("|")
                ?: movie?.credits?.crew?.filter { it.job == "Director" }?.joinToString("|") { it.name } ?: base.directors,
            posterPath = art.poster ?: nfo?.posterUrl ?: movie?.posterPath ?: base.posterPath,
            backdropPath = art.fanart ?: nfo?.fanartUrl ?: movie?.backdropPath ?: base.backdropPath,
            logoPath = art.logo ?: nfo?.logoUrl ?: movie?.let { tmdb?.pickLogo(it.images) } ?: base.logoPath,
            stillPath = art.thumb ?: base.stillPath,
            collectionId = collectionId,
            matchedAt = if (tmdb != null || nfoInfo != null) now else base.matchedAt,
        ).withNfoStreams(nfoInfo) // What the file contains does not depend on the match.
        dao.saveScanned(item)
        when {
            nfo != null && nfo.actors.isNotEmpty() -> dao.replaceCast(item.id, castOf(item.id, nfo.actors))
            movie != null -> dao.replaceCast(item.id, castOf(item.id, movie.credits))
        }
    }

    /**
     * The collection a film belongs to: the NFO's set first (what the user's media manager
     * decided), then TMDB's. Created when first met; its details are asked to TMDB once, or
     * again on a full refresh. Returns null when the film is in no collection.
     */
    private suspend fun collection(nfo: NfoInfo?, movie: TmdbMovie?, tmdb: TmdbClient?, now: Long, refresh: Boolean): Long? {
        val ref = movie?.belongsToCollection
        val name = nfo?.collection ?: ref?.name ?: return null
        // A set of the user's own ("Marvel") is not TMDB's collection, even if the film is in one.
        val tmdbId = nfo?.collectionTmdbId
            ?: ref?.id?.takeIf { nfo?.collection == null || CollectionRules.sameName(nfo.collection, ref.name) }
        val key = CollectionRules.key(tmdbId, name)
        var c = dao.collectionByKey(key) ?: CollectionEntity(
            matchKey = key,
            tmdbId = tmdbId,
            name = name,
            sortTitle = sortKey(name),
            posterPath = ref?.posterPath.takeIf { tmdbId == ref?.id },
            backdropPath = ref?.backdropPath.takeIf { tmdbId == ref?.id },
        ).let { it.copy(id = dao.insertCollection(it)) }
        if (tmdbId != null && tmdb != null && (c.fetchedAt == null || refresh) && refreshedCollections.add(c.id)) {
            tmdb.collection(tmdbId)?.let { full ->
                val shown = nfo?.collection ?: full.name
                c = c.copy(
                    name = shown,
                    sortTitle = sortKey(shown),
                    overview = full.overview?.ifBlank { null } ?: c.overview,
                    posterPath = full.posterPath ?: c.posterPath,
                    backdropPath = full.backdropPath ?: c.backdropPath,
                    partCount = CollectionRules.released(full.parts.map { it.releaseDate }, CollectionRules.today()),
                    fetchedAt = now,
                )
                dao.updateCollection(c)
            }
        }
        return c.id
    }

    /** NFO stream details stand in when the file itself could not be read. */
    private fun ItemEntity.withNfoStreams(nfo: NfoInfo?): ItemEntity {
        val streams = nfo?.streams ?: return this
        val media = MediaProbe.decode(mediaInfo)
        if (media?.video != null) return this
        val merged = NfoStreamDetails.toMediaInfo(streams, media)
        return copy(mediaInfo = MediaProbe.encode(merged), badges = merged.badges().ifEmpty { badges.split('|') }.joinToString("|"))
    }

    /** null when the file can't be read; a NAS that stops answering throws, so the file is tried again later. */
    private suspend fun readText(source: SourceEntity, file: RemoteFile): String? = suspendRunCatching {
        val length = file.size.coerceIn(0, MAX_NFO_BYTES.toLong()).toInt().takeIf { it > 0 } ?: MAX_NFO_BYTES
        String(RemoteBrowser.of(source).readPrefix(source, file.relativePath, length), Charsets.UTF_8)
    }.getOrElse { e -> if (ScanErrors.isUnreachable(e)) throw e else null }

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
            sortTitle = "%03d-%04d".format(Locale.ROOT, parsed.season, parsed.episode),
            nfoPath = local.nfo?.relativePath,
            nfo = nfoText,
        ).withEpisodeDetails(
            nfo = nfo,
            meta = meta,
            thumb = local.thumb?.let { artwork.copy(source, it) },
            matchedAt = if (tmdb != null || nfo != null) now else base.matchedAt,
        )
        val probed = (if (episode.mediaInfo == null) mediaFields(source, file, episode) else episode).withNfoStreams(nfo)
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

    /** Reads the file once and stores what it contains, with format badges for filters. */
    private suspend fun withMediaInfo(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity {
        if (item.mediaInfo != null) return item
        val updated = mediaFields(source, file, item)
        dao.saveScanned(updated)
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
        val series = dao.seriesByKey(key) ?: ItemEntity(
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
            (local.poster != null && !series.posterPath.isCachedArtwork())
        if (!needsLookup || (tmdb == null && local.nfo == null && local.poster == null)) return series
        refreshedSeries += series.id

        val nfoText = local.nfo?.let { readText(source, it) }
        val nfo = nfoText?.let(NfoParser::parse)
        val tv = tmdb?.let { lookupShow(it, series.userTmdbId, nfo, title, year) }
        val art = LocalArt(
            poster = local.poster?.let { artwork.copy(source, it) },
            fanart = local.fanart?.let { artwork.copy(source, it) },
            logo = local.logo?.let { artwork.copy(source, it) },
        )
        return saveShow(series.copy(nfoPath = local.nfo?.relativePath, nfo = nfoText), nfo, tv, art, tmdb, now)
    }

    private suspend fun identifyShow(series: ItemEntity, tmdbId: Int?, tmdb: TmdbClient) {
        val (title, year) = showQuery(series)
        val nfo = series.nfo?.let(NfoParser::parse)
        // Fetched before anything changes: an unreachable TMDB throws here.
        val tv = if (tmdbId != null) tmdb.tv(tmdbId) else lookupShow(tmdb, null, nfo, title, year)
        dao.setUserTmdbId(series.id, tmdbId)
        if (tv?.id != series.tmdbId) dao.clearUserArtwork(series.id)
        val fresh = dao.item(series.id) ?: return
        val now = System.currentTimeMillis()
        val saved = saveShow(fresh.withoutDetails(title, year), nfo, tv, LocalArt.of(fresh), tmdb, now)
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

    /** Details: NFO, then TMDB, then what [base] had. Artwork: local files, NFO, TMDB. */
    private suspend fun saveShow(base: ItemEntity, nfoInfo: NfoInfo?, tv: TmdbTv?, art: LocalArt, tmdb: TmdbClient?, now: Long): ItemEntity {
        val nfo = nfoInfo?.takeUnless { describesOther(it, base.userTmdbId, null) }
        val name = nfo?.title ?: tv?.name ?: base.title
        val series = base.copy(
            tmdbId = tv?.id ?: nfo?.tmdbId ?: base.tmdbId,
            title = name,
            sortTitle = sortKey(nfo?.sortTitle ?: name),
            originalTitle = nfo?.originalTitle ?: tv?.originalName ?: base.originalTitle,
            overview = nfo?.plot ?: tv?.overview?.ifBlank { null } ?: base.overview,
            tagline = nfo?.tagline ?: tv?.tagline?.ifBlank { null } ?: base.tagline,
            year = nfo?.year ?: yearOf(nfo?.premiered) ?: yearOf(tv?.firstAirDate) ?: base.year,
            endYear = yearOf(tv?.lastAirDate) ?: base.endYear,
            continuing = nfo?.status?.let { it.contains("Continuing", true) || it.contains("Returning", true) }
                ?: tv?.let { it.status == "Returning Series" || it.status == "In Production" }
                ?: base.continuing,
            runtimeMin = nfo?.runtimeMin ?: tv?.episodeRunTime?.firstOrNull() ?: base.runtimeMin,
            certification = nfo?.certification ?: tv?.let { tmdb?.tvCertification(it) } ?: base.certification,
            rating = nfo?.rating ?: tv?.voteAverage?.takeIf { it > 0 } ?: base.rating,
            genres = nfo?.genres?.takeIf { it.isNotEmpty() }?.joinToString("|") ?: tv?.genres?.joinToString("|") { it.name } ?: base.genres,
            posterPath = art.poster ?: nfo?.posterUrl ?: tv?.posterPath ?: base.posterPath,
            backdropPath = art.fanart ?: nfo?.fanartUrl ?: tv?.backdropPath ?: base.backdropPath,
            logoPath = art.logo ?: nfo?.logoUrl ?: tv?.let { tmdb?.pickLogo(it.images) } ?: base.logoPath,
            matchedAt = now,
        )
        dao.saveScanned(series)
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
            artwork.copy(source, localPoster)?.let { season = season.copy(posterPath = it); dao.saveScanned(season) }
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
    private class SeasonCache(private val tmdb: TmdbClient?) {
        private val cache = mutableMapOf<Pair<Int, Int>, TmdbSeason?>()
        suspend fun get(tvId: Int, season: Int): TmdbSeason? {
            if (tmdb == null) return null
            val key = tvId to season
            if (key !in cache) cache[key] = tmdb.season(tvId, season)
            return cache[key]
        }
    }

    /** Artwork found next to the video or the show, already copied into the app. */
    private data class LocalArt(
        val poster: String? = null,
        val fanart: String? = null,
        val logo: String? = null,
        val thumb: String? = null,
    ) {
        companion object {
            /** What [item] already uses from local files. */
            fun of(item: ItemEntity) = LocalArt(item.posterPath.local(), item.backdropPath.local(), item.logoPath.local(), item.stillPath.local())

            private fun String?.local() = this?.takeIf { it.isCachedArtwork() }
        }
    }

    companion object {
        private const val TAG = "LibraryScanner"
        private const val MAX_UNREACHABLE = 3
        private const val MAX_NFO_BYTES = 1 shl 20
        private const val MAX_PAUSE_MS = 4 * 60 * 60_000L
        private val ARTICLES = Regex("""(?i)^(the|a|an|le|la|les|l'|un|une|des)\s+""")

        fun sortKey(title: String): String = ARTICLES.replace(title.trim(), "").lowercase()

        /** Title and year of a film, from its file name or its folder ("Title (Year)/movie.mkv"). */
        fun parseMovie(relativePath: String): ParsedMovie {
            val fromFile = FilenameParser.parseMovie(relativePath.substringAfterLast('/'))
            // The folder name is often more reliable than the file name.
            val fromFolder = relativePath.split('/').dropLast(1).lastOrNull()?.let(FilenameParser::parseMovie)
            return if (fromFile.year == null && fromFolder?.year != null) fromFolder else fromFile
        }

        /**
         * True when the NFO names another title than the one the user matched the file with:
         * it then no longer describes this file.
         */
        fun describesOther(nfo: NfoInfo, userTmdbId: Int?, imdbId: String?): Boolean = when {
            userTmdbId == null -> false
            nfo.tmdbId != null -> nfo.tmdbId != userTmdbId
            nfo.imdbId != null && imdbId != null -> !nfo.imdbId.equals(imdbId, ignoreCase = true)
            else -> false
        }

        /** Only what the file or folder name says: details of a previous match are dropped. */
        private fun ItemEntity.withoutDetails(title: String, year: Int?) = copy(
            tmdbId = null,
            title = title,
            sortTitle = sortKey(title),
            originalTitle = null,
            overview = null,
            tagline = null,
            year = year,
            endYear = null,
            runtimeMin = null,
            certification = null,
            rating = null,
            genres = "",
            directors = "",
            posterPath = null,
            backdropPath = null,
            logoPath = null,
            stillPath = null,
        )

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
