package app.houselights.ui.home

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.houselights.R
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.providers.local.Folder
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanState
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.drawnWhole
import app.houselights.ui.design.houseLit
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.DoorCell
import app.houselights.ui.design.components.DoorFacts
import app.houselights.ui.design.components.DoorFigures
import app.houselights.ui.design.components.DoorGrid
import app.houselights.ui.design.components.DoorNote
import app.houselights.ui.design.components.DoorPosters
import app.houselights.ui.design.components.DoorProgress
import app.houselights.ui.design.components.DoorSagas
import app.houselights.ui.design.components.DoorStill
import app.houselights.ui.design.components.DoorWall
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.HomeDoor
import app.houselights.ui.design.components.HomeHead
import app.houselights.ui.design.components.ResumeCase
import app.houselights.ui.design.components.ScanProgress
import app.houselights.ui.design.components.StateFact
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.gridHeights
import app.houselights.ui.design.components.rememberCutPosters
import app.houselights.ui.design.components.rememberFigureSize
import app.houselights.ui.settings.relativeTime
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.formatCount
import app.houselights.ui.util.libraryName
import app.houselights.ui.util.okClears
import app.houselights.ui.util.scanErrorText
import app.houselights.ui.util.timeLeft
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Where home's case and doors lead; each does nothing by default. */
data class HomeActions(
    val onPlay: (MediaItem, resume: Boolean) -> Unit = { _, _ -> },
    val onOpenItem: (MediaItem) -> Unit = {},
    val onOpenWatch: (WatchRow) -> Unit = {},
    /** Opens a library's wall; for films, also the sagas it groups. */
    val onOpenLibrary: (Library, sagas: Library?) -> Unit = { _, _ -> },
    /** Opens the films' wall on its sagas alone. */
    val onOpenSagas: (films: Library?, sagas: Library) -> Unit = { _, _ -> },
    /** Null when the source cannot search. */
    val onOpenSearch: (() -> Unit)? = null,
    val onOpenSettings: () -> Unit = {},
    val onAddFolder: () -> Unit = {},
    /** Dismisses why the case did not play. */
    val onClearProblem: () -> Unit = {},
    val onReload: () -> Unit = {},
    val onRescan: () -> Unit = {},
    /** Prefetches a library's wall as its door is chosen. */
    val onLibraryAhead: (Library, sagas: Library?) -> Unit = { _, _ -> },
)

/** Home; [playProblem] says why a play asked from it did not start. */
@Composable
fun HomeScreen(actions: HomeActions, playProblem: UiError? = null) {
    val app = appContainer()
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(app))
    ReloadOnLibraryChange(vm.libraryShown) { vm.onLibraryChanged() }
    val state by vm.state.collectAsStateWithLifecycle()
    val search = actions.onOpenSearch.takeIf { app.providerOrNull()?.capabilities?.search == true }
    HomeGrid(state, vm.scan, actions.copy(onOpenSearch = search, onReload = vm::load, onRescan = vm::rescan), playProblem)
}

/**
 * Home for [state] (DESIGN.md, Layout): the lockup, then the grid of the case and its doors, or what says why there is
 * none. Focus comes back to the door left; Back says once that a second one quits. Why the case did not play
 * ([playProblem]) is said under its action until an OK there.
 */
@Composable
internal fun HomeGrid(state: HomeState, scan: StateFlow<ScanState>, actions: HomeActions, playProblem: UiError? = null) {
    val content = state.content
    val memory = rememberFocusMemory()
    // The line saying a second Back quits goes as soon as focus moves to another door.
    val quitting = quitOnSecondBack(atStart = true) { memory.last }
    val plan = rememberPlan(state, canSearch = actions.onOpenSearch != null)
    // A first focus waits for the case: with nothing in progress, it proposes a title as soon as one is read.
    val ready = plan != null && (plan.case != null || state.doors != null)
    RestoreFocus(memory, fallback = plan?.start?.let { memory.requester(it) }, ready = ready)
    val listing = rememberSaveable { mutableStateOf(false) }
    val options = rememberSaveable { mutableStateOf(false) }
    val case = plan?.case
    ClosedWithout(options, case != null)
    ClosedWithout(listing, plan?.doors?.any { it is Door.Libraries } == true)
    // Whole for the launch screen once the grid shows with its doors read, or a message.
    val whole = content !is HomeContent.Loading && (plan == null || state.doors?.shelvesRead == true)
    Column(Modifier.fillMaxSize().houseLit(LocalHouseLights.current).drawnWhole(whole)) {
        // Without a grid, the settings stay one tile away, where a message may send the viewer.
        val settings: (@Composable () -> Unit)? = if (content is HomeContent.Message) {
            { HlIconButton(HlIcons.Settings, stringResource(R.string.nav_settings), onClick = actions.onOpenSettings) }
        } else {
            null
        }
        HomeHead(stringResource(R.string.back_again_to_quit).takeIf { quitting }, end = settings)
        when {
            plan != null -> Grid(
                plan, state, scan, actions, memory, playProblem, onList = { listing.value = true }, onOptions = { options.value = true },
            )
            content is HomeContent.Message -> NoRows(content, scan, actions, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l))
            else -> Unit
        }
    }
    if (listing.value && plan != null) LibrariesSheet(plan, state.sagas, actions) { listing.value = false }
    if (options.value && case != null) CaseOptions(case, actions) { options.value = false }
}

/** A [sheet] whose [element] went with a reload closes, and does not open again by itself when it comes back. */
@Composable
private fun ClosedWithout(sheet: MutableState<Boolean>, element: Boolean) {
    SideEffect { if (!element) sheet.value = false }
}

/** Home's grid for [state]'s rows, once there are; null for a message or while loading. */
@Composable
private fun rememberPlan(state: HomeState, canSearch: Boolean): HomePlan? {
    val rows = state.content as? HomeContent.Rows ?: return null
    return remember(rows, state.libraries, state.doors, canSearch) {
        homePlan(rows.sections, state.browsable, state.sagas, state.doors, canSearch)
    }
}

/** Where a first visit starts: on the case, else on the first door. */
private val HomePlan.start: String? get() = case?.let { CASE } ?: doors.firstOrNull()?.key

/** The grid of [plan]: the case, then its rows of doors. */
@Composable
private fun Grid(
    plan: HomePlan,
    state: HomeState,
    scan: StateFlow<ScanState>,
    actions: HomeActions,
    memory: FocusMemory,
    playProblem: UiError?,
    onList: () -> Unit,
    onOptions: () -> Unit,
) {
    val watch = plan.rows.any { it.kind == RowKind.WATCH }
    val libraries = plan.rows.firstOrNull { it.kind == RowKind.LIBRARIES }
    val heights = gridHeights(watch, libraries != null)
    val counts = libraries?.doors?.map { (door, _) -> countOf(door)?.let { formatCount(it.toLong()) }.orEmpty() }.orEmpty()
    val libraryHeight = heights[plan.rows.indexOfFirst { it.kind == RowKind.LIBRARIES }.coerceAtLeast(0)]
    val figure = rememberFigureSize(counts, libraries?.doors?.map { it.second }.orEmpty(), libraryHeight)
    DoorGrid(
        memory = memory,
        caseKey = CASE,
        hasCase = plan.case != null,
        rightOfCase = plan.firstLibrary?.key,
        rows = plan.rows.map { row ->
            row.doors.map { (door, span) ->
                DoorCell(door.key, span) { modifier -> DoorOf(door, state, scan, actions, figure, onList, modifier) }
            }
        },
        heights = heights,
        modifier = Modifier.padding(horizontal = HlSpace.safeH),
    ) { modifier -> CaseOf(plan.case, actions, onOptions, modifier, playProblem) }
}

/**
 * The case: the title last in progress, resumed on OK; else what it proposes, a film played, a show's page opened.
 * One case either way, so focus stays on it as what it shows changes. Why it did not play ([problem]) is said under
 * its action, where OK was pressed, until the next OK there.
 */
@Composable
private fun CaseOf(case: CaseShows?, actions: HomeActions, onOptions: () -> Unit, modifier: Modifier, problem: UiError?) {
    if (case == null) {
        // Nothing to propose: the grid moves focus to its first door.
        Box(modifier.fillMaxSize().clearAndSetSemantics {})
        return
    }
    val proposed = case as? CaseShows.Proposed
    val opens = proposed?.opensPage == true
    val action = when {
        proposed == null -> R.string.resume
        !opens -> R.string.play
        case.item.kind == MediaKind.SERIES -> R.string.view_series
        else -> R.string.home_open_title
    }
    ResumeCase(
        case.item,
        stringResource(action),
        onClick = { if (opens) actions.onOpenItem(case.item) else actions.onPlay(case.item, proposed == null) },
        modifier = modifier.okClears(actions.onClearProblem),
        label = stringResource(R.string.home_unseen).takeIf { proposed?.notSeen == true },
        plays = !opens,
        onLongClick = onOptions,
        onLongClickLabel = stringResource(R.string.more_options),
        says = problem?.let { error ->
            { HlStatus(StatusState.Attention, stringResource(R.string.play_failed), detail = stringResource(error.message)) }
        },
    )
}

/** A door of the grid, drawn for what it shows. */
@Composable
private fun DoorOf(
    door: Door,
    state: HomeState,
    scan: StateFlow<ScanState>,
    actions: HomeActions,
    figure: TextUnit,
    onList: () -> Unit,
    modifier: Modifier,
) {
    when (door) {
        is Door.Next, is Door.AlsoResume, is Door.News -> WatchDoor(door, actions, modifier)
        is Door.Library -> LibraryDoor(door, state, actions, figure, modifier)
        is Door.Libraries -> LibrariesDoor(door, figure, onList, modifier)
        is Door.Sagas, is Door.Search, Door.Settings -> ToolDoor(door, state, scan, actions, modifier)
    }
}

/**
 * A door of what is watched: the next episode, the second title in progress, or the latest titles. The first two
 * are one door, so focus stays on it as it changes from one to the other.
 */
@Composable
private fun WatchDoor(door: Door, actions: HomeActions, modifier: Modifier) {
    if (door is Door.News) {
        HomeDoor(
            stringResource(R.string.home_news),
            HlIcons.Sparkles,
            onClick = { actions.onOpenWatch(WatchRow.LATEST) },
            modifier = modifier,
            note = door.thisWeek.takeIf { it > 0 }?.let { DoorNote.News(pluralStringResource(R.plurals.home_news_week, it, it)) },
        ) { DoorPosters(door.posters) }
        return
    }
    val next = door as? Door.Next
    val item = next?.item ?: (door as? Door.AlsoResume)?.item ?: return
    val mark = episodeMark(item)?.let { DoorNote.Mark(it) }
    HomeDoor(
        stringResource(if (next != null) R.string.section_next_up else R.string.section_continue),
        if (next != null) HlIcons.Next else HlIcons.Play,
        onClick = { actions.onOpenWatch(if (next != null) WatchRow.NEXT_UP else WatchRow.CONTINUE) },
        modifier = modifier,
        note = if (next != null) mark else mark ?: timeLeft(item)?.let { DoorNote.Words(it) },
        progress = if (next != null) next.season?.let { DoorProgress.Episodes(it) } else DoorProgress.Seen(item.progress),
    ) { DoorStill(item) }
}

/** The libraries past the third, behind one door: their number cut in their posters; OK lists them. */
@Composable
private fun LibrariesDoor(door: Door.Libraries, figure: TextUnit, onList: () -> Unit, modifier: Modifier) {
    val cut = rememberCutPosters(door.posters)
    val name = stringResource(R.string.home_libraries)
    val count = formatCount(door.shelves.size.toLong())
    // Read as it says: its name, how many, then which.
    val names = door.shelves.map { it.library.title ?: libraryName(it.library.kind) }
    HomeDoor(name, HlIcons.Library, onClick = onList, modifier = modifier, glowTones = { cut.tones }) { lit ->
        DoorFigures(count, cut, figure, lit, readAs = (listOf(name, count) + names).joinToString(READ_JOIN))
    }
}

/** A tool's door: the sagas, the search, the settings and the app's state. */
@Composable
private fun ToolDoor(door: Door, state: HomeState, scan: StateFlow<ScanState>, actions: HomeActions, modifier: Modifier) {
    when (door) {
        is Door.Sagas -> HomeDoor(
            stringResource(R.string.library_collections),
            HlIcons.Layers,
            onClick = { state.sagas?.let { sagas -> actions.onOpenSagas(state.films, sagas) } },
            modifier = modifier,
            note = door.shelf.count?.let { DoorNote.Mark(formatCount(it.toLong())) },
        ) { DoorSagas(door.shelf.posters) }
        is Door.Search -> HomeDoor(
            stringResource(R.string.search),
            HlIcons.Search,
            onClick = { actions.onOpenSearch?.invoke() },
            modifier = modifier,
        ) { DoorWall(door.posters) }
        Door.Settings -> HomeDoor(
            stringResource(R.string.nav_settings),
            HlIcons.Settings,
            onClick = actions.onOpenSettings,
            modifier = modifier,
        ) { state.status?.let { status -> StateOf(status, scan) } }
        else -> Unit
    }
}

/** A library's door: its count cut in its posters, and what is new in it. */
@Composable
private fun LibraryDoor(door: Door.Library, state: HomeState, actions: HomeActions, figure: TextUnit, modifier: Modifier) {
    val library = door.shelf.library
    val cut = rememberCutPosters(door.posters)
    val sagas = state.sagas.takeIf { library.kind == LibraryKind.MOVIES }
    val note = when {
        (door.newEpisodes ?: 0) > 0 -> door.newEpisodes?.let { DoorNote.News(pluralStringResource(R.plurals.home_new_episodes, it, it)) }
        door.news > 0 -> DoorNote.News(pluralStringResource(R.plurals.row_news, door.news, door.news))
        else -> null
    }
    val name = library.title ?: libraryName(library.kind)
    val count = door.shelf.count
    // Read as it says: its name first, then how many titles, then what is new.
    val titles = count?.let { pluralStringResource(R.plurals.title_count, it, it) }
    HomeDoor(
        name,
        iconOf(library.kind),
        onClick = {
            actions.onLibraryAhead(library, sagas)
            actions.onOpenLibrary(library, sagas)
        },
        modifier = modifier,
        note = note,
        glowTones = { cut.tones },
    ) { lit ->
        count?.let {
            val readAs = listOfNotNull(name, titles, (note as? DoorNote.News)?.text).joinToString(READ_JOIN)
            DoorFigures(formatCount(it.toLong()), cut, figure, lit, readAs = readAs)
        }
    }
}

/** Between the parts a door is read as. */
private const val READ_JOIN = ", "

private fun iconOf(kind: LibraryKind) = when (kind) {
    LibraryKind.MOVIES -> HlIcons.Film
    LibraryKind.SHOWS -> HlIcons.Tv
    LibraryKind.COLLECTIONS -> HlIcons.Layers
    LibraryKind.MIXED -> HlIcons.Folder
}

/** The count a library's door cuts in posters. */
private fun countOf(door: Door): Int? = when (door) {
    is Door.Library -> door.shelf.count
    is Door.Libraries -> door.shelves.size
    else -> null
}

/** "S1 · É4" for an episode; nothing for a film. */
@Composable
private fun episodeMark(item: MediaItem): String? {
    val number = item.episodeNumbers?.takeIf { item.kind == MediaKind.EPISODE } ?: return null
    return stringResource(R.string.home_episode, item.seasonNumber ?: 0, number)
}

/** The settings door's state; only it reads a running scan's progress, file by file. */
@Composable
private fun StateOf(status: HomeStatus, scan: StateFlow<ScanState>) {
    val progress = if (status.scanning) scan.collectAsStateWithLifecycle().value.let { it.processed to it.total } else null
    DoorFacts(stateFacts(status, progress))
}

/**
 * The app's state as the settings door states it, ranked as it keeps them when they do not all fit: the source, Trakt
 * where it follows it, the last scan or the one running, and above them a problem TMDB had, in the source's place.
 */
@Composable
private fun stateFacts(status: HomeStatus, progress: Pair<Int, Int>?): List<StateFact> {
    val context = LocalContext.current
    val label = stringResource(R.string.home_state_source)
    val source = when (val from = status.source) {
        is StatusSource.Folders -> {
            val folders = from.count.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.folder_count, it, it) }
            StateFact(label, stringResource(R.string.source_local), detail = from.hosts.singleOrNull() ?: folders, quietDetail = false)
        }
        is StatusSource.Server -> StateFact(label, from.name ?: stringResource(R.string.source_jellyfin))
    }
    val trakt = status.trakt?.let { on ->
        val said = stringResource(if (on) R.string.trakt_connected else R.string.trakt_signed_out)
        StateFact(stringResource(R.string.home_state_trakt), said, done = on, rank = 1)
    }
    val scan = progress?.let { (done, total) ->
        StateFact(
            stringResource(R.string.home_state_scan),
            stringResource(R.string.home_scan_running),
            detail = stringResource(R.string.scan_progress, done, total).takeIf { total > 0 },
            wide = true,
            rank = 2,
        )
    } ?: status.scannedAt?.let { at ->
        val ago = relativeTime(context, at, System.currentTimeMillis(), alone = true, short = true)
        StateFact(stringResource(R.string.home_state_scan), ago, wide = true, rank = 2)
    }
    val tmdb = status.tmdb?.let { issue ->
        StateFact(stringResource(R.string.home_state_tmdb), stringResource(tmdbState(issue)), wide = true, attention = true, rank = 3)
    }
    return listOfNotNull(source, trakt, tmdb, scan)
}

/**
 * TMDB's problem in the door's words: refused, a certificate refused, or out of reach whatever else failed, as the first
 * scan's counter says it; the label already names TMDB.
 */
private fun tmdbState(issue: UiError): Int = when (issue) {
    UiError.TMDB_KEY_REFUSED -> R.string.home_state_tmdb_refused
    UiError.CERTIFICATE -> R.string.error_certificate_title
    else -> R.string.home_state_tmdb_offline
}

/** The libraries past the third, each opening its wall. */
@Composable
private fun LibrariesSheet(plan: HomePlan, sagas: Library?, actions: HomeActions, onDismiss: () -> Unit) {
    val door = plan.doors.filterIsInstance<Door.Libraries>().firstOrNull() ?: return
    val first = remember { FocusRequester() }
    HlSideSheet(stringResource(R.string.home_libraries), onDismiss, initialFocus = first) { sheet ->
        HlSettingGroup(null) {
            door.shelves.forEachIndexed { i, shelf ->
                val library = shelf.library
                val films = sagas.takeIf { library.kind == LibraryKind.MOVIES }
                HlSettingRow(
                    library.title ?: libraryName(library.kind),
                    if (i == 0) Modifier.focusRequester(first) else Modifier,
                    value = shelf.count?.let { pluralStringResource(R.plurals.title_count, it, it) },
                    icon = iconOf(library.kind),
                    onClick = {
                        actions.onLibraryAhead(library, films)
                        sheet.dismiss { actions.onOpenLibrary(library, films) }
                    },
                )
            }
        }
    }
}

/** The case's options: its title's page, and, once started, playing it from the start. */
@Composable
private fun CaseOptions(case: CaseShows, actions: HomeActions, onDismiss: () -> Unit) {
    val item = case.item
    val first = remember { FocusRequester() }
    val title = if (item.kind == MediaKind.EPISODE) item.seriesTitle ?: item.title else item.title
    HlSideSheet(title, onDismiss, facts = listOf(item.episodeLabel ?: item.year?.toString()), initialFocus = first) { sheet ->
        HlSettingGroup(null) {
            HlSettingRow(
                stringResource(R.string.home_open_title),
                Modifier.focusRequester(first),
                icon = HlIcons.Info,
                onClick = { sheet.dismiss { actions.onOpenItem(item) } },
            )
            if (item.user.positionMs > 0) {
                HlSettingRow(
                    stringResource(R.string.play_from_start),
                    icon = HlIcons.Restart,
                    onClick = { sheet.dismiss { actions.onPlay(item, false) } },
                )
            }
        }
    }
}

/** How long after a first Back at home a second one quits. */
internal const val QUIT_WINDOW_MS = 2_500L

/**
 * Back at home's start ([atStart]): a first press arms the second, which then goes through to Android and quits;
 * under the launch screen, where nothing has been seen, the first goes through. Disarmed after [QUIT_WINDOW_MS] or
 * once focus moves ([where] changes). Whether the line saying so shows.
 */
@Composable
private fun quitOnSecondBack(atStart: Boolean, where: () -> Any?): Boolean {
    var armed by remember { mutableStateOf(false) }
    val launch = LocalLaunchScreen.current
    val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current) { "No Back dispatcher" }.onBackPressedDispatcher
    // The launch screen is asked as Back comes: its lifting recomposes nothing.
    val first = remember(launch, dispatcher) {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (!launch.covers) {
                    armed = true
                    return
                }
                // Stepped aside, this Back goes on to Android, which quits.
                isEnabled = false
                dispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }
    SideEffect { first.isEnabled = atStart && !armed }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(first, lifecycle) {
        dispatcher.addCallback(lifecycle, first)
        onDispose { first.remove() }
    }
    val currentWhere by rememberUpdatedState(where)
    LaunchedEffect(armed) {
        if (!armed) return@LaunchedEffect
        val from = currentWhere()
        withTimeoutOrNull(QUIT_WINDOW_MS) { snapshotFlow { currentWhere() }.first { it != from } }
        armed = false
    }
    return armed
}

/** The first scan's counter; only it reads [progress]. */
@Composable
private fun FirstScan(progress: StateFlow<ScanState>, modifier: Modifier) {
    val scan by progress.collectAsStateWithLifecycle()
    val tmdbIssue = scan.tmdbIssue?.toUiError()
    Column(modifier) {
        ScanProgress(
            scan.processed,
            scan.total,
            label = stringResource(R.string.home_scanning),
            current = scan.current,
            lineWidth = HlLayout.readableNarrow,
        )
        if (tmdbIssue != null) {
            Spacer(Modifier.height(HlSpace.m))
            AttentionNote(stringResource(noteOf(tmdbIssue)), Modifier.widthIn(max = HlLayout.readableNarrow), style = Hl.type.meta)
        }
        scanNoteAction(tmdbIssue)?.let { action ->
            val fix = LocalErrorFix.current
            Spacer(Modifier.height(HlSpace.m))
            HlButton(stringResource(action.label), onClick = { fix(action) })
        }
    }
}

private fun noteOf(issue: UiError): Int = when (issue) {
    UiError.TMDB_KEY_REFUSED -> R.string.home_scan_tmdb_refused
    UiError.CERTIFICATE -> R.string.home_scan_tmdb_certificate
    else -> R.string.home_scan_tmdb_offline
}

/** The counter's fix for [issue]: edit a refused TMDB key. */
internal fun scanNoteAction(issue: UiError?): ErrorAction? = issue?.action?.takeIf { it == ErrorAction.EDIT_TMDB_KEY }

/** No rows and nothing loading: the first scan, a failure, a scan that failed, or a library still empty. */
@Composable
private fun NoRows(content: HomeContent.Message, progress: StateFlow<ScanState>, actions: HomeActions, modifier: Modifier) {
    when (content) {
        HomeContent.FirstScan -> FirstScan(progress, modifier)
        is HomeContent.Failed -> HlErrorState(content.error, actions.onReload, modifier, content.retrying)
        HomeContent.ReadingFolders -> Unit
        is HomeContent.ScanFailed -> ScanFailed(content.folder, content.error, actions, modifier)
        is HomeContent.NoFilms -> NoFilms(content.hasFolders, actions, modifier)
        HomeContent.ServerEmpty ->
            HlEmptyState(stringResource(R.string.empty_title), modifier, message = stringResource(R.string.empty_server))
    }
}

/** No films yet: an action to add the folder holding them and, with [hasFolders], a link to the folders. */
@Composable
private fun NoFilms(hasFolders: Boolean, actions: HomeActions, modifier: Modifier) {
    HlEmptyState(
        stringResource(R.string.home_empty_title),
        modifier,
        message = stringResource(R.string.home_empty_local),
        action = stringResource(R.string.add_folder),
        actionIcon = HlIcons.Add,
        onAction = actions.onAddFolder,
        secondaryAction = stringResource(R.string.home_see_folders).takeIf { hasFolders },
        onSecondaryAction = actions.onOpenSettings,
    )
}

/** [folder] could not be read: the [error], a rescan and the folders. */
@Composable
private fun ScanFailed(folder: Folder, error: ScanError, actions: HomeActions, modifier: Modifier) {
    HlEmptyState(
        stringResource(R.string.home_scan_failed),
        modifier,
        message = scanErrorText(error, folder.host),
        attention = true,
        action = stringResource(R.string.retry),
        actionIcon = HlIcons.Refresh,
        onAction = actions.onRescan,
        secondaryAction = stringResource(R.string.home_see_folders),
        onSecondaryAction = actions.onOpenSettings,
    )
}
