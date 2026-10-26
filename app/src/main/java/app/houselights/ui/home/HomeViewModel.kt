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
import app.houselights.core.browsable
import app.houselights.core.sagas
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.Folder
import app.houselights.providers.local.HeldRow
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanState
import app.houselights.settings.AppSettings
import app.houselights.ui.settings.HeldTitles
import app.houselights.ui.settings.heldByFolder
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.LibraryShown
import app.houselights.ui.util.RoomPosters
import app.houselights.ui.util.UiError
import app.houselights.ui.util.reported
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import android.util.Log

/** What Home shows under its head: the grid of its [Rows], or a [Message] saying why there is none. */
sealed interface HomeContent {
    /** Nothing to browse: the message starts at the safe area, under the head. */
    sealed interface Message : HomeContent

    data object Loading : HomeContent

    /** The rows home's grid is made of (« À regarder » shows them). */
    data class Rows(val sections: List<HomeSection>) : HomeContent

    /** No rows yet and a scan running: its counter, with rows filling in as titles are found. */
    data object FirstScan : Message

    /** The source could not be read; while [retrying], the intertitle stays with its button busy. */
    data class Failed(val error: UiError, val retrying: Boolean) : Message

    /** The folders not read yet, or unreadable: nothing but the head and its settings. */
    data object ReadingFolders : Message

    /** Shown instead of "add a folder" when folders exist: [folder]'s last scan failed, [error] says why. */
    data class ScanFailed(val folder: Folder, val error: ScanError) : Message

    /** Folders without a film yet: they can be opened from here. */
    data object NoFilms : Message

    data object ServerEmpty : Message
}

/** Home: the source's libraries, what shows (the grid's rows, or a message), its doors and the app's state. */
data class HomeState(
    val libraries: List<Library> = emptyList(),
    val content: HomeContent = HomeContent.Loading,
    val doors: HomeDoors? = null,
    val status: HomeStatus? = null,
    /**
     * A folder's titles a sudden drop holds while their files are missing, not answered for yet: said at the end of
     * home's head until the user answers or the files are back.
     */
    val held: HeldTitles? = null,
) {
    /** Every library but the sagas ([sagas]), each with its own wall. */
    val browsable: List<Library> get() = libraries.browsable()

    /** The source's sagas, if it has any: a films' wall shows them among its films, or alone. */
    val sagas: Library? get() = libraries.sagas()

    /** The first films library, whose wall the sagas door opens on its sagas alone. */
    val films: Library? get() = browsable.firstOrNull { it.kind == LibraryKind.MOVIES }
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
 * server), whether a scan runs ([scanning]; the door alone reads its progress) and what TMDB did to the last ([tmdb]).
 */
data class HomeStatus(
    val source: StatusSource,
    val trakt: Boolean? = null,
    val scannedAt: Long? = null,
    val scanning: Boolean = false,
    val tmdb: UiError? = null,
)

/**
 * Home's rows from the current source's [provider], and what Home shows without them. Only the first scan's counter
 * and the settings door read [scan] progress, so a file scanned changes [state] only when it changes what Home shows.
 */
class HomeViewModel(
    private val provider: () -> MediaProvider,
    val scan: StateFlow<ScanState>,
    folders: Flow<List<Folder>>,
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
    /** The titles held while their files are missing ([LocalLibrary.held][app.houselights.providers.local.LocalLibrary.held]). */
    held: Flow<List<HeldRow>> = flowOf(emptyList()),
    /** « Garder » for a folder's titles held, those shown ([app.houselights.providers.local.LocalLibrary.keepHeld]). */
    private val keep: suspend (folder: Long, shown: List<Long>) -> Unit = { _, _ -> },
    /** « Retirer » for titles held ([app.houselights.providers.local.LocalLibrary.removeHeld]). */
    private val remove: suspend (List<Long>) -> Unit = {},
    /** Where an answer runs, outliving home; its own scope by default. */
    work: CoroutineScope? = null,
) : ViewModel() {
    private val work = work ?: viewModelScope

    /** The library version home and « À regarder » last showed: one change reloads them once. */
    val libraryShown = LibraryShown()

    private val load = MutableStateFlow(HomeLoad())
    private val loads = LatestJob(viewModelScope)
    private val draws = LatestJob(viewModelScope)
    private val doorReads = LatestJob(viewModelScope)

    /**
     * Null until first read, and when they cannot be read: never taken for none. On return, the last list stays until
     * the database re-reads it, so Home never blanks.
     */
    private val folderList: StateFlow<List<Folder>?> = folders
        .catch { e -> Log.w(TAG, "Reading the folders failed", e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** The first folder whose titles held the user has not answered for, in the folders' order. */
    private val heldNote: Flow<HeldTitles?> = combine(held, folderList) { rows, list ->
        list?.let { heldByFolder(rows, it).firstOrNull { titles -> !titles.kept } }
    }.catch { e ->
        Log.w(TAG, "Reading the titles held failed", e)
        emit(null)
    }

    val state: StateFlow<HomeState> = combine(load, scan, folderList, settings, heldNote) { loaded, progress, list, current, note ->
        val local = current.source == SourceConfig.Local
        HomeState(
            loaded.libraries,
            homeContent(loaded, progress, list, local = local),
            loaded.doors,
            homeStatus(current, list, progress, traktAvailable),
            held = note.takeIf { local },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_AFTER_MS),
        HomeState(content = homeContent(load.value, scan.value, folders = null, local = settings.value.source == SourceConfig.Local)),
    )

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
        val doors = rows.doorsAhead?.let { awaitDoors(it) }
            ?: suspendRunCatching { coroutineScope { awaitDoors(readDoorsAsync(provider(), rows.libraries, rows.sections, pick)) } }
                .onFailure { Log.w(TAG, "Doors", it) }
                .getOrNull()
        load.update { it.copy(doors = doors ?: it.doors?.copy(shelvesRead = true) ?: HomeDoors()) }
    }

    /** What [read] proposes, shown as soon as it comes over the doors shown, then its doors; null when it went away. */
    private suspend fun awaitDoors(read: DoorsRead): HomeDoors? {
        awaitAhead(read.first)?.let { first ->
            load.update { last -> last.copy(doors = last.doors?.copy(proposed = first.proposed) ?: first) }
        }
        return awaitAhead(read.whole)
    }

    /** A read of the doors, the launch's or one under way; null when it went away, and the doors are read here. */
    private suspend fun awaitAhead(read: Deferred<HomeDoors>): HomeDoors? = try {
        read.await()
    } catch (gone: CancellationException) {
        if (!currentCoroutineContext().isActive) throw gone
        null
    }

    /** Reloads after a library change; the library's rows are drawn again unless a scan's progress alone moved it. */
    fun onLibraryChanged() = load()

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

    /** « Garder »: [held]'s titles wait for their files, and home no longer says so; a failure leaves the note. */
    fun keepHeld(held: HeldTitles) {
        work.launchAction(TAG, "Keeping the titles held in folder ${held.folder.id}") { keep(held.folder.id, held.ids) }
    }

    /** « Retirer »: [held]'s titles leave the library, what the user did kept by title; a failure leaves the note. */
    fun removeHeld(held: HeldTitles) {
        work.launchAction(TAG, "Removing the titles held in folder ${held.folder.id}") { remove(held.ids) }
    }

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
                    held = app.library.held,
                    keep = app.library::keepHeld,
                    remove = app.library::removeHeld,
                    work = app.scope,
                )
            }
        }
    }
}

/**
 * What Home shows, from [load], [scan], [folders] (null until read) and whether the source is [local]. Shown rows
 * always stay; with none, nothing while loading or without a folder, then the scan counter, an error, or why it is empty.
 */
internal fun homeContent(load: HomeLoad, scan: ScanState, folders: List<Folder>?, local: Boolean): HomeContent {
    val failed = failedFolder(scan.errors, folders.orEmpty())
    return when {
        load.sections.isNotEmpty() -> HomeContent.Rows(load.sections + load.drawn)
        load.loading && load.error == null -> HomeContent.Loading
        scan.running -> HomeContent.FirstScan
        load.error != null -> HomeContent.Failed(load.error, retrying = load.loading)
        local && folders == null -> HomeContent.ReadingFolders
        // No folder is no library: the welcome screen takes over, nothing shows meanwhile.
        local && folders.isNullOrEmpty() -> HomeContent.Loading
        local && failed != null -> HomeContent.ScanFailed(failed.first, failed.second)
        local -> HomeContent.NoFilms
        else -> HomeContent.ServerEmpty
    }
}

/** What home's settings door states from [settings], the [folders] (null until read) and the [scan]. */
internal fun homeStatus(settings: AppSettings, folders: List<Folder>?, scan: ScanState, traktAvailable: Boolean): HomeStatus? =
    when (val source = settings.source) {
        null -> null
        SourceConfig.Local -> HomeStatus(
            StatusSource.Folders(folders.orEmpty().map { it.host }.distinct(), folders.orEmpty().size),
            trakt = (settings.trakt?.ended == false).takeIf { traktAvailable },
            scannedAt = folders?.maxOfOrNull { it.lastScanAt } ?: 0L,
            scanning = scan.running,
            tmdb = scan.tmdbIssue?.toUiError(),
        )
        is SourceConfig.Jellyfin -> HomeStatus(StatusSource.Server(source.serverName))
    }

/** The first existing folder whose last scan failed, and why. */
internal fun failedFolder(errors: Map<Long, ScanError>, folders: List<Folder>): Pair<Folder, ScanError>? =
    folders.firstNotNullOfOrNull { folder -> errors[folder.id]?.let { folder to it } }

/** How long the screen may be away (a configuration change, a title opened and closed) before Home stops collecting. */
private const val STOP_AFTER_MS = 5_000L

private const val TAG = "Home"
