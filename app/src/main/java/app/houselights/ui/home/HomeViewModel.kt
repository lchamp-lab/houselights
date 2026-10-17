package app.houselights.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.MediaProvider
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.SourceEntity
import app.houselights.settings.AppSettings
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.UiError
import app.houselights.ui.util.reported
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import android.util.Log

/**
 * What home shows under its bar, decided here rather than in the screen, which only draws it. Two
 * families: [Browsing], the hero's band then the rows; [Message], an intertitle (or the first scan's
 * counter) where the title under focus would start.
 */
sealed interface HomeContent {
    /** The hero's band, then the rows or their empty frames; [firstScan]: the scan's counter between them. */
    sealed interface Browsing : HomeContent {
        val firstScan: Boolean
    }

    /** Nothing to browse: what the screen says starts where the title under focus would. */
    sealed interface Message : HomeContent

    /** The rows load for the first time. */
    data class Loading(override val firstScan: Boolean) : Browsing

    /** The rows; [firstScan]: a folder is read for the first time, its counter above them. */
    data class Rows(val sections: List<HomeSection>, override val firstScan: Boolean) : Browsing

    /** No rows yet, and a scan going on: its counter, the rows filling in as titles are found. */
    data object FirstScan : Message

    /** The source could not be read; [retrying]: it is read again, and the intertitle stays, its button at work. */
    data class Failed(val error: UiError, val retrying: Boolean) : Message

    /** The folders are still being read: nothing to say about them yet. */
    data object ReadingFolders : Message

    /** Not "add a folder" when there is one: [folder]'s last scan failed, and [error] says why. */
    data class ScanFailed(val folder: SourceEntity, val error: ScanError) : Message

    /** Folders without a film yet, or none at all ([hasFolders]: they can be seen from here). */
    data class NoFilms(val hasFolders: Boolean) : Message

    /** A server with nothing to show. */
    data object ServerEmpty : Message
}

/** Home: the tabs' libraries, and what shows under them. */
data class HomeState(
    val libraries: List<Library> = emptyList(),
    val content: HomeContent = HomeContent.Loading(firstScan = false),
)

/**
 * What the source last gave home, or why it could not: kept while it loads again with nothing shown
 * ([loading] with an [error]), so the intertitle stays in place with focus on its button.
 */
internal data class HomeLoad(
    val loading: Boolean = true,
    val error: UiError? = null,
    val libraries: List<Library> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    /** The rows the library writes under them ([LibraryRows]), once drawn. */
    val drawn: List<HomeSection> = emptyList(),
)

/**
 * Home's rows, from the [provider] of the source in use, and what home shows without them, from the
 * [scan], the [folders] and the source in [settings]. The scan's progress is the first scan's
 * counter's alone to read ([scan]): a file read changes [state] only when it changes what home shows.
 */
class HomeViewModel(
    private val provider: () -> MediaProvider,
    val scan: StateFlow<ScanState>,
    folders: Flow<List<SourceEntity>>,
    settings: StateFlow<AppSettings>,
    private val scanAll: () -> Unit,
    /** The rows the launch asked for home already ([HomeLaunch.take]), once; null: home asks the source. */
    private val launched: suspend () -> HomeRows? = { null },
    /** The rows the library writes under home's own ([LibraryRows]); none, when null. */
    private val libraryRows: LibraryRows? = null,
    /** Waits for the launch screen to leave: the library's rows are read after it, outside home's first view. */
    private val afterLaunch: suspend () -> Unit = {},
) : ViewModel() {
    private val load = MutableStateFlow(HomeLoad())
    private val loads = LatestJob(viewModelScope)
    private val draws = LatestJob(viewModelScope)

    /**
     * Null until the folders are first read. Back on home after a while away, the folders last read
     * stand until the database reads them again: what home showed stays, with no blank in between.
     */
    private val folderList: StateFlow<List<SourceEntity>?> = folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val state: StateFlow<HomeState> = combine(load, scan, folderList, settings) { loaded, progress, list, current ->
        HomeState(loaded.libraries, homeContent(loaded, progress, list, local = current.source == SourceConfig.Local))
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_AFTER_MS),
        HomeState(content = homeContent(load.value, scan.value, folders = null, local = settings.value.source == SourceConfig.Local)),
    )

    init {
        load()
    }

    fun load() {
        // Trying again with nothing shown shows: the error stays, at work, until the answer. Rows shown,
        // or the first scan's progress over none, stay as they are while they load again.
        if (load.value.sections.isEmpty() && load.value.error != null) load.update { it.copy(loading = true) }
        loads.launch {
            suspendRunCatching {
                launched()?.let { it.libraries to it.sections } ?: provider().let { source -> source.libraries() to source.home() }
            }.onSuccess { (libraries, sections) ->
                load.update { HomeLoad(loading = false, libraries = libraries, sections = sections, drawn = it.drawn) }
                drawLibraryRows()
            }.onFailure { e ->
                val error = e.reported(TAG, "Home")
                load.update { it.copy(loading = false, error = error) }
            }
        }
    }

    /** The library changed: its rows are drawn again from what it holds now, with home's own. */
    fun onLibraryChanged() {
        libraryRows?.forget()
        load()
    }

    /** The library's rows, under home's own, once the launch screen has left; a failure leaves them out. */
    private fun drawLibraryRows() {
        val rows = libraryRows ?: return
        draws.launch {
            afterLaunch()
            suspendRunCatching { rows.today() }
                .onSuccess { drawn -> load.update { it.copy(drawn = drawn) } }
                .onFailure { Log.w(TAG, "Library rows", it) }
        }
    }

    /** The folders are read again: a scan that failed is tried once more. */
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
                )
            }
        }
    }
}

/**
 * What home shows, from what the source gave ([load]), the [scan], the [folders] (null until read)
 * and whether the source is the [local] folders. Rows shown stay, whatever else happens; with none,
 * a first load shows the hero's room and empty frames, then the first scan's counter, an error, or
 * why the library is still empty.
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

/** A folder read for the first time, or a scan going on while home has nothing to show. */
internal fun firstScan(scan: ScanState, empty: Boolean, folders: List<SourceEntity>?): Boolean =
    scan.running && (empty || folders.orEmpty().any { it.id == scan.sourceId && it.lastScanAt <= 0 })

/** The first folder still there whose last scan failed, and why. */
internal fun failedFolder(errors: Map<Long, ScanError>, folders: List<SourceEntity>): Pair<SourceEntity, ScanError>? =
    folders.firstNotNullOfOrNull { folder -> errors[folder.id]?.let { folder to it } }

/** How long the screen may be away (a configuration change, a title opened and closed) before home stops listening. */
private const val STOP_AFTER_MS = 5_000L

private const val TAG = "Home"
