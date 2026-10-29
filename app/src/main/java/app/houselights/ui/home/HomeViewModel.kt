package app.houselights.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.LibraryEvents
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaProvider
import app.houselights.core.SectionType
import app.houselights.core.SourceConfig
import app.houselights.core.browsable
import app.houselights.core.sagas
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.FirstTitleRow
import app.houselights.providers.local.Folder
import app.houselights.providers.local.HeldRow
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanState
import app.houselights.settings.AppSettings
import app.houselights.settings.SecretSetting
import app.houselights.ui.design.FirstScanDefaults
import app.houselights.ui.design.components.FirstWallState
import app.houselights.ui.library.Laid
import app.houselights.ui.library.NotSaved
import app.houselights.ui.library.PosterChanges
import app.houselights.ui.library.Undone
import app.houselights.ui.library.favoredAs
import app.houselights.ui.library.markedAs
import app.houselights.ui.settings.HeldTitles
import app.houselights.ui.settings.heldByFolder
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.LibraryShown
import app.houselights.ui.util.RoomPosters
import app.houselights.ui.util.UiError
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.reported
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** What Home shows under its head: the grid of its [Rows], or a [Message] saying why there is none. */
sealed interface HomeContent {
    /** Nothing to browse: the message starts at the safe area, under the head. */
    sealed interface Message : HomeContent

    data object Loading : HomeContent

    /** The rows home's grid is made of (« À regarder » shows them). */
    data class Rows(val sections: List<HomeSection>) : HomeContent

    /**
     * Home's first scan, its own screen under the head: for the whole pass begun on an empty library until its files are
     * read or « Aller à l'accueil » is chosen, then until home's one read has its shelves; else while no row shows and a
     * scan runs.
     */
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
    /**
     * Whether the library's first pass runs ([ScanState.firstPass]), on its first scan or on home's grid after it: a
     * first Back says the scan will resume, and a version found waits for the pass's end.
     */
    val firstPass: Boolean = false,
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

/** Trakt as home's settings door states it: connected, not connected, or its session kept but not opened at this launch. */
enum class TraktFact { CONNECTED, SIGNED_OUT, UNREAD }

/**
 * The app's state as home's settings door states it: the [source], how a Trakt account follows it ([trakt], null where
 * Trakt does not follow this source), when the folders were last scanned ([scannedAt], 0 never, null on a server),
 * whether a scan runs ([scanning]; the door alone reads its progress) and what TMDB did to the last ([tmdb]).
 */
data class HomeStatus(
    val source: StatusSource,
    val trakt: TraktFact? = null,
    val scannedAt: Long? = null,
    val scanning: Boolean = false,
    val tmdb: UiError? = null,
)

/**
 * Home's rows from the current source's [provider], and what Home shows without them. Only the first scan and the
 * settings door read [scan] progress, so a file scanned changes [state] only when it changes what Home shows. Over the
 * library's first pass, the first scan holds home and home reads nothing behind it, then reads once at the cut
 * ([FirstScanHold]); what the first scan shows ([firstScan]) is read only while it holds home or shows.
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
    /**
     * The films and shows first found, then the latest ([app.houselights.providers.local.LocalLibrary.firstTitles]),
     * and how many there are ([app.houselights.providers.local.LocalLibrary.firstTitleCount]): read while the first scan shows.
     */
    firstTitles: (limit: Int) -> Flow<List<FirstTitleRow>> = { flowOf(emptyList()) },
    firstTitleCount: Flow<Int> = flowOf(0),
    /** « Aller à l'accueil » chosen during the first pass, kept while the app runs ([FirstScanLeft]); home's own by default. */
    left: FirstScanLeft? = null,
    /** Where an answer runs, outliving home; its own scope by default. */
    work: CoroutineScope? = null,
    /** Told of a change a card's options saved in « À regarder », so the other screens show it. */
    events: LibraryEvents? = null,
) : ViewModel() {
    private val work = work ?: viewModelScope
    private val left = left ?: FirstScanLeft(viewModelScope, scan)

    /** The library version home and « À regarder » last showed: one change reloads them once. */
    val libraryShown = LibraryShown()

    private val load = MutableStateFlow(HomeLoad())

    /** How the first scan holds home: over a first pass found under way, home reads nothing until its cut. */
    private val hold = MutableStateFlow(if (holds(scan.value, this.left.left.value)) FirstScanHold.PASS else FirstScanHold.NONE)

    /** The first scan's wall, kept with home so it is found again as it was after Réglages; new for each first pass. */
    var wall = FirstWallState()
        private set

    private val loads = LatestJob(viewModelScope)
    private val draws = LatestJob(viewModelScope)
    private val doorReads = LatestJob(viewModelScope)

    /**
     * What a card's options change in « À regarder » (#47), saved as the title's page saves it, held over every read
     * of the rows until they are read again ([PendingChanges][app.houselights.ui.library.PendingChanges]).
     */
    internal val changes = PosterChanges(provider, events, this.work, TAG)

    /** The last change a card's options asked that was not saved, said in the hero while its title has focus. */
    val notSaved: StateFlow<NotSaved?> get() = changes.notSaved

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

    val state: StateFlow<HomeState> = combine(
        combine(load, hold, ::Pair),
        scan,
        folderList,
        settings,
        heldNote,
    ) { (loaded, holding), progress, list, current, note ->
        val local = current.source == SourceConfig.Local
        HomeState(
            loaded.libraries,
            shownContent(holding, loaded, progress, list, local = local),
            loaded.doors,
            homeStatus(current, list, progress, traktAvailable),
            held = note.takeIf { local },
            firstPass = progress.firstPass,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_AFTER_MS),
        HomeState(
            content = shownContent(hold.value, load.value, scan.value, folders = null, local = settings.value.source == SourceConfig.Local),
            firstPass = scan.value.firstPass,
        ),
    )

    /**
     * What the first scan shows beside its progress: the folder the pass reads, the titles found as its wall takes them
     * (the first [FirstScanDefaults.FRAMES], then the latest) and how many; read while it holds home, from Réglages too,
     * so its wall comes back with the titles found meanwhile at once, else only while it shows. Forgotten once nothing
     * reads it, unread again: another first pass's wall waits for its own titles, never showing the last pass's at once.
     */
    val firstScan: StateFlow<FirstScanShows> = combine(
        firstTitles(FirstScanDefaults.FRAMES),
        firstTitleCount,
        combine(scan.map { it.sourceId }.distinctUntilChanged(), folderList, ::folderRead),
    ) { rows, count, folder -> FirstScanShows(folder, rows.map(::firstTitleOf), count) }
        .catch { e -> Log.w(TAG, "Reading the first scan's titles failed", e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS, replayExpirationMillis = 0), FirstScanShows())

    init {
        if (hold.value == FirstScanHold.NONE) load()
        // While the first scan holds home its titles are read, from Réglages too: its wall comes back with them at once.
        viewModelScope.launch {
            hold.map { it == FirstScanHold.PASS }.distinctUntilChanged().collectLatest { holding -> if (holding) firstScan.collect() }
        }
        viewModelScope.launch {
            combine(scan, this@HomeViewModel.left.left, ::holds).distinctUntilChanged().collect { holding ->
                when {
                    holding -> holdForThePass()
                    hold.value == FirstScanHold.PASS -> cut()
                }
            }
        }
    }

    /** A first pass under way: the first scan holds home, on a wall of its own. */
    private fun holdForThePass() {
        if (hold.value == FirstScanHold.PASS) return
        wall = FirstWallState()
        hold.value = FirstScanHold.PASS
    }

    /**
     * The pass's files read, « Aller à l'accueil » chosen, or the pass ended: home reads once, the first scan staying until
     * this read has its shelves, so the grid comes in one cut, focus placed once on its case.
     */
    private fun cut() {
        hold.value = FirstScanHold.CUT
        // What was read before the pass says nothing of the grid to come.
        load.update { it.copy(loading = true, error = null, doors = null) }
        load()
        viewModelScope.launch {
            load.first { !it.loading && (it.error != null || it.doors?.shelvesRead == true) }
            hold.compareAndSet(FirstScanHold.CUT, FirstScanHold.NONE)
        }
    }

    fun load() {
        // A retry with nothing shown keeps the error, busy; shown rows or first-scan progress stay while reloading.
        if (load.value.sections.isEmpty() && load.value.error != null) load.update { it.copy(loading = true) }
        val read = changes.pending.reading()
        loads.launch {
            suspendRunCatching {
                launched() ?: provider().let { source -> HomeRows(source.libraries(), source.home()) }
            }.onSuccess { rows ->
                val sections = rows.sections.under(changes.pending.over(read))
                load.update { last ->
                    HomeLoad(false, null, rows.libraries, sections, drawn = last.drawn, doors = rows.doors ?: last.doors)
                }
                shown(sections)
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

    /**
     * Reloads after a library change, the library's rows drawn again unless a scan's progress alone moved it; never behind
     * the first scan, which reads at its cut.
     */
    fun onLibraryChanged() {
        if (hold.value == FirstScanHold.NONE) load()
    }

    /** Marks [item] watched, or not once it is, from its card's options in « À regarder », shown at once ([change]). */
    fun mark(item: MediaItem) {
        val played = !item.user.played
        change(item.markedAs(played)) { undo -> changes.mark(item, played, undo) }
    }

    /** Makes [item] a favourite, or not once it is, from its card's options in « À regarder », shown at once. */
    fun favorite(item: MediaItem) {
        val favorite = !item.user.favorite
        change(item.favoredAs(favorite)) { undo -> changes.favorite(item, favorite, undo) }
    }

    /**
     * Shows [changed] at once in every row holding it, Reprendre letting go of a title no longer in progress, a row
     * emptied going; then [save]s it, the rows read again as the library is told it changed, so that what the source
     * places anew comes in its place (À suivre's next episode). Not saved, the rows are as they were, or read again when
     * they changed meanwhile, that change alone undone in them while they are read: another of the title still saving
     * or saved keeps showing.
     */
    private fun change(changed: MediaItem, save: (undo: (Undone) -> Unit) -> Unit) {
        val before = load.value
        val after = before.copy(sections = before.sections.showing(changed), drawn = before.drawn.showing(changed))
        load.value = after
        save { undone -> undo(before, after, undone) }
    }

    /**
     * The rows as they were [before] a change not saved, while they are still those it showed ([after]); else read again,
     * the rows shown meanwhile, and should the read fail. Either way, the change is [undone] in every row holding its
     * title, which [before] may show with another change of it that was not saved either, its own undoing changing
     * nothing the rows show.
     */
    private fun undo(before: HomeLoad, after: HomeLoad, undone: Undone) {
        if (load.value === after) {
            load.value = before.undone(undone)
        } else {
            load.update { it.undone(undone) }
            onLibraryChanged()
        }
    }

    /** « Aller à l'accueil » on the first scan: home cuts to its grid once read, the scan going on; again, nothing more. */
    fun goHome() {
        if (hold.value == FirstScanHold.PASS) left.leave()
    }

    /** Loads the library's rows, below Home's own, once the launch screen has closed; a failure leaves them out. */
    private fun drawLibraryRows() {
        val rows = libraryRows ?: return
        val read = changes.pending.reading()
        draws.launch {
            afterLaunch()
            suspendRunCatching { rows.today() }
                .onSuccess { drawn -> load.update { it.copy(drawn = drawn.under(changes.pending.over(read))) } }
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
                    firstTitles = app.library::firstTitles,
                    firstTitleCount = app.library.firstTitleCount,
                    left = app.firstScanLeft,
                    work = app.scope,
                    events = app,
                )
            }
        }
    }
}

/**
 * How the first scan holds home over the library's first pass (DESIGN.md, Layout): not at all ([NONE]); while the pass
 * reads its files and « Aller à l'accueil » is not chosen ([PASS]), home reading nothing behind it; then until home's
 * one read has its shelves ([CUT]).
 */
private enum class FirstScanHold { NONE, PASS, CUT }

/** Whether the first scan holds home for [scan]: the first pass reads its files, « Aller à l'accueil » not chosen ([left]). */
private fun holds(scan: ScanState, left: Boolean) = scan.firstPass && !scan.finishing && !left

/** The folder a pass reads ([ScanState.sourceId]) among [folders], the first while it reaches none; null until they are read. */
private fun folderRead(id: Long?, folders: List<Folder>?): Folder? =
    folders?.let { list -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }

/** What home shows: its first scan while it [holds][FirstScanHold] home, else [homeContent]. */
private fun shownContent(hold: FirstScanHold, load: HomeLoad, scan: ScanState, folders: List<Folder>?, local: Boolean): HomeContent =
    if (hold == FirstScanHold.NONE) homeContent(load, scan, folders, local) else HomeContent.FirstScan

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
            trakt = traktFact(settings).takeIf { traktAvailable },
            scannedAt = folders?.maxOfOrNull { it.lastScanAt } ?: 0L,
            scanning = scan.running,
            tmdb = scan.tmdbIssue?.toUiError(),
        )
        is SourceConfig.Jellyfin -> HomeStatus(StatusSource.Server(source.serverName))
    }

/**
 * Trakt as [settings] hold it: a session kept that the Keystore did not open at this launch is neither connected nor
 * not, as Réglages › Trakt says it.
 */
private fun traktFact(settings: AppSettings): TraktFact = when {
    settings.trakt?.ended == false -> TraktFact.CONNECTED
    SecretSetting.TRAKT in settings.unreadSecrets -> TraktFact.UNREAD
    else -> TraktFact.SIGNED_OUT
}

/**
 * [this] showing [changed] in every row holding it: Reprendre lets go of a title no longer in progress, and a row it
 * empties goes; the others keep it in its place, its mark changed.
 */
internal fun List<HomeSection>.showing(changed: MediaItem): List<HomeSection> = showing(changed.id) { changed }

/** [this] showing title [id] as [shown] makes it of what each row holds of it, as [showing] a title changed does. */
private fun List<HomeSection>.showing(id: String, shown: (MediaItem) -> MediaItem): List<HomeSection> = mapNotNull { section ->
    val held = section.items.firstOrNull { it.id == id } ?: return@mapNotNull section
    val changed = shown(held)
    val stays = section.type != SectionType.CONTINUE || (changed.user.positionMs > 0 && !changed.user.played)
    val items = if (stays) {
        section.items.map { if (it.id == id) changed else it }
    } else {
        section.items.filterNot { it.id == id }
    }
    section.copy(items = items).takeIf { items.isNotEmpty() }
}

/** [this] with [undone] in every row holding its title, as the source has it. */
private fun HomeLoad.undone(undone: Undone): HomeLoad =
    copy(sections = sections.showing(undone.id, undone::over), drawn = drawn.showing(undone.id, undone::over))

/** [this], as a read answered them, showing the changes its cards' options asked that it may not know ([laid]). */
private fun List<HomeSection>.under(laid: List<Laid>): List<HomeSection> =
    laid.fold(this) { rows, change -> rows.showing(change.item.id, change::over) }

/** The first existing folder whose last scan failed, and why. */
internal fun failedFolder(errors: Map<Long, ScanError>, folders: List<Folder>): Pair<Folder, ScanError>? =
    folders.firstNotNullOfOrNull { folder -> errors[folder.id]?.let { folder to it } }

/** How long the screen may be away (a configuration change, a title opened and closed) before Home stops collecting. */
private const val STOP_AFTER_MS = 5_000L

private const val TAG = "Home"
