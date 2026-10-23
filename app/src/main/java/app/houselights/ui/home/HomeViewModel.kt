package app.houselights.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaProvider
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.SourceEntity
import app.houselights.settings.AppSettings
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.RoomPosters
import app.houselights.ui.util.UiError
import app.houselights.ui.util.reported
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import android.util.Log

/** What Home shows below its bar: [Browsing] (hero, then rows) or a [Message] where the focused title would start. */
sealed interface HomeContent {
    /** The hero's band, then the rows or their empty frames; with [firstScan], the scan's counter between them. */
    sealed interface Browsing : HomeContent {
        val firstScan: Boolean
    }

    /** Nothing to browse: the message starts where the focused title would. */
    sealed interface Message : HomeContent

    data class Loading(override val firstScan: Boolean) : Browsing

    /** The rows; with [firstScan], a folder's first scan is running and its counter shows above them. */
    data class Rows(val sections: List<HomeSection>, override val firstScan: Boolean) : Browsing

    /** No rows yet and a scan running: its counter, with rows filling in as titles are found. */
    data object FirstScan : Message

    /** The source could not be read; while [retrying], the intertitle stays with its button busy. */
    data class Failed(val error: UiError, val retrying: Boolean) : Message

    data object ReadingFolders : Message

    /** Shown instead of "add a folder" when folders exist: [folder]'s last scan failed, [error] says why. */
    data class ScanFailed(val folder: SourceEntity, val error: ScanError) : Message

    /** No films yet: folders without any, or no folders at all ([hasFolders]; they can be opened from here). */
    data class NoFilms(val hasFolders: Boolean) : Message

    data object ServerEmpty : Message
}

/** Home: the source's libraries, what shows (the grid's rows, or a message), its doors and the app's state. */
data class HomeState(
    val libraries: List<Library> = emptyList(),
    val content: HomeContent = HomeContent.Loading(firstScan = false),
    val doors: HomeDoors? = null,
    val status: HomeStatus? = null,
) {
    /** The tabs: every library except the sagas, which a films' wall shows among its films ([sagas]). */
    val tabs: List<Library> get() = libraries.filter { it.kind != LibraryKind.COLLECTIONS }

    /** The source's sagas, if it has any: a films' wall shows them among its films, or alone. */
    val sagas: Library? get() = libraries.firstOrNull { it.kind == LibraryKind.COLLECTIONS }

    /** The first films library, whose wall the sagas door opens on its sagas alone. */
    val films: Library? get() = tabs.firstOrNull { it.kind == LibraryKind.MOVIES }
}

/** The source's last result for Home, kept while reloading so an intertitle and its focused button stay. */
internal data class HomeLoad(
    val loading: Boolean = true,
    val error: UiError? = null,
    val libraries: List<Library> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    /** The library's own rows below them ([LibraryRows]), once computed. */
    val drawn: List<HomeSection> = emptyList(),
    /** What the doors show beyond the rows, once read; kept while reloading. */
    val doors: HomeDoors? = null,
)

/** Where home's settings door says the library comes from. */
sealed interface StatusSource {
    /** The folders, by their servers' names. */
    data class Folders(val hosts: List<String>, val count: Int) : StatusSource

    /** A media server, by its name when it gave one. */
    data class Server(val name: String?) : StatusSource
}

/**
 * The app's state as home's settings door states it: the [source], whether a Trakt account follows it ([trakt], null
 * where Trakt does not follow this source), when the folders were last scanned ([scannedAt], 0 never, null on a
 * server) and whether a scan runs ([scanning]; the door alone reads its progress).
 */
data class HomeStatus(
    val source: StatusSource,
    val trakt: Boolean? = null,
    val scannedAt: Long? = null,
    val scanning: Boolean = false,
)

/**
 * Home's rows from the current source's [provider], and what Home shows without them. Only the first scan's
 * counter reads [scan] progress, so a file scanned changes [state] only when it changes what Home shows.
 */
class HomeViewModel(
    private val provider: () -> MediaProvider,
    val scan: StateFlow<ScanState>,
    folders: Flow<List<SourceEntity>>,
    settings: StateFlow<AppSettings>,
    private val scanAll: () -> Unit,
    /** Rows the launch already requested for Home ([HomeLaunch.take]), once; on null, Home asks the source. */
    private val launched: suspend () -> HomeRows? = { null },
    /** The library's rows below Home's own ([LibraryRows]); none when null. */
    private val libraryRows: LibraryRows? = null,
    /** Waits for the launch screen to close: the library's rows load after it, outside Home's first view. */
    private val afterLaunch: suspend () -> Unit = {},
    /** Home's rows as they are shown ([RoomPosters.shown]). */
    private val shown: (List<HomeSection>) -> Unit = {},
    /** The film the case proposes when nothing is in progress, kept all session. */
    private val pick: UnseenPick = UnseenPick(),
    /** Whether this build reaches Trakt at all. */
    private val traktAvailable: Boolean = false,
) : ViewModel() {
    private val load = MutableStateFlow(HomeLoad())
    private val loads = LatestJob(viewModelScope)
    private val draws = LatestJob(viewModelScope)
    private val doorReads = LatestJob(viewModelScope)

    /** Null until first read; on return, the last list stays until the database re-reads it, so Home never blanks. */
    private val folderList: StateFlow<List<SourceEntity>?> = folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val state: StateFlow<HomeState> = combine(load, scan, folderList, settings) { loaded, progress, list, current ->
        HomeState(
            loaded.libraries,
            homeContent(loaded, progress, list, local = current.source == SourceConfig.Local),
            loaded.doors,
            homeStatus(current, list, progress, traktAvailable),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_AFTER_MS),
        HomeState(content = homeContent(load.value, scan.value, folders = null, local = settings.value.source == SourceConfig.Local)),
    )

    /** The source's libraries as last read: the places of the bar over a wall. */
    val libraries: StateFlow<List<Library>> =
        load.map { it.libraries }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), load.value.libraries)

    init {
        load()
    }

    fun load() {
        // A retry with nothing shown keeps the error, busy; shown rows or first-scan progress stay while reloading.
        if (load.value.sections.isEmpty() && load.value.error != null) load.update { it.copy(loading = true) }
        loads.launch {
            suspendRunCatching {
                launched() ?: provider().let { source -> HomeRows(source.libraries(), source.home()) }
            }.onSuccess { rows ->
                load.update { last ->
                    HomeLoad(false, null, rows.libraries, rows.sections, drawn = last.drawn, doors = rows.doors ?: last.doors)
                }
                shown(rows.sections)
                if (rows.doors == null) doorReads.launch { loadDoors(rows) }
                drawLibraryRows()
            }.onFailure { e ->
                val error = e.reported(TAG, "Home")
                load.update { it.copy(loading = false, error = error) }
            }
        }
    }

    /**
     * The doors of [rows]' libraries: the launch's read when it made one, else read here; a part that fails is left out,
     * the rows stay. Even when nothing could be read, the doors count as read, so home takes its first focus.
     */
    private suspend fun loadDoors(rows: HomeRows) {
        val doors = rows.doorsAhead?.let { awaitAhead(it) }
            ?: suspendRunCatching { readDoors(provider(), rows.libraries, rows.sections, pick) }
                .onFailure { Log.w(TAG, "Doors", it) }
                .getOrNull()
        load.update { it.copy(doors = doors ?: it.doors ?: HomeDoors()) }
    }

    /** The launch's read of the doors; null when it went away, and the doors are read here. */
    private suspend fun awaitAhead(read: Deferred<HomeDoors>): HomeDoors? = try {
        read.await()
    } catch (gone: CancellationException) {
        if (!currentCoroutineContext().isActive) throw gone
        null
    }

    /** Reloads after a library change, recomputing the library's rows along with Home's own. */
    fun onLibraryChanged() {
        libraryRows?.forget()
        load()
    }

    /** Loads the library's rows, below Home's own, once the launch screen has closed; a failure leaves them out. */
    private fun drawLibraryRows() {
        val rows = libraryRows ?: return
        draws.launch {
            afterLaunch()
            suspendRunCatching { rows.today() }
                .onSuccess { drawn -> load.update { it.copy(drawn = drawn) } }
                .onFailure { Log.w(TAG, "Library rows", it) }
        }
    }

    /** Scans the folders again, retrying a failed scan. */
    fun rescan() = scanAll()

    companion object {
        fun factory(app: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    app::provider,
                    app.library.scan,
                    app.library.folders,
                    app.settings.state,
                    scanAll = { app.library.scan() },
                    launched = { app.homeLaunch.take(app.settings.state.value.source) },
                    libraryRows = app.libraryRows,
                    afterLaunch = { app.launch.awaitOpen() },
                    shown = { app.roomPosters.shown(app.settings.state.value.source, it) },
                    pick = app.unseenPick,
                    traktAvailable = app.trakt.available,
                )
            }
        }
    }
}

/**
 * What Home shows, from [load], [scan], [folders] (null until read) and whether the source is [local]. Shown rows
 * always stay; with none, the hero's room and empty frames, then the scan counter, an error, or why it is empty.
 */
internal fun homeContent(load: HomeLoad, scan: ScanState, folders: List<SourceEntity>?, local: Boolean): HomeContent {
    val empty = load.sections.isEmpty()
    val firstScan = firstScan(scan, empty, folders)
    val failed = failedFolder(scan.errors, folders.orEmpty())
    return when {
        !empty -> HomeContent.Rows(load.sections + load.drawn, firstScan)
        load.loading && load.error == null -> HomeContent.Loading(firstScan)
        firstScan -> HomeContent.FirstScan
        load.error != null -> HomeContent.Failed(load.error, retrying = load.loading)
        local && folders == null -> HomeContent.ReadingFolders
        local && failed != null -> HomeContent.ScanFailed(failed.first, failed.second)
        local -> HomeContent.NoFilms(hasFolders = !folders.isNullOrEmpty())
        else -> HomeContent.ServerEmpty
    }
}

/** What home's settings door states from [settings], the [folders] (null until read) and the [scan]. */
internal fun homeStatus(settings: AppSettings, folders: List<SourceEntity>?, scan: ScanState, traktAvailable: Boolean): HomeStatus? =
    when (val source = settings.source) {
        null -> null
        SourceConfig.Local -> HomeStatus(
            StatusSource.Folders(folders.orEmpty().map { it.host }.distinct(), folders.orEmpty().size),
            trakt = (settings.trakt?.ended == false).takeIf { traktAvailable },
            scannedAt = folders?.maxOfOrNull { it.lastScanAt } ?: 0L,
            scanning = scan.running,
        )
        is SourceConfig.Jellyfin -> HomeStatus(StatusSource.Server(source.serverName))
    }

/** A folder's first scan, or any scan while Home has nothing to show. */
internal fun firstScan(scan: ScanState, empty: Boolean, folders: List<SourceEntity>?): Boolean =
    scan.running && (empty || folders.orEmpty().any { it.id == scan.sourceId && it.lastScanAt <= 0 })

/** The first existing folder whose last scan failed, and why. */
internal fun failedFolder(errors: Map<Long, ScanError>, folders: List<SourceEntity>): Pair<SourceEntity, ScanError>? =
    folders.firstNotNullOfOrNull { folder -> errors[folder.id]?.let { folder to it } }

/** How long the screen may be away (a configuration change, a title opened and closed) before Home stops collecting. */
private const val STOP_AFTER_MS = 5_000L

private const val TAG = "Home"
