package app.houselights

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import app.houselights.core.ArtworkKind
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.MediaItem
import app.houselights.core.SourceConfig
import app.houselights.settings.AppSettings
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.catalog.CatalogScreen
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.rememberPicturesAhead
import app.houselights.ui.detail.DetailScreen
import app.houselights.ui.edit.ArtworkScreen
import app.houselights.ui.edit.IdentifyScreen
import app.houselights.ui.edit.ToIdentifyScreen
import app.houselights.ui.home.HomeActions
import app.houselights.ui.home.HomeScreen
import app.houselights.ui.home.HomeViewModel
import app.houselights.ui.home.WatchScreen
import app.houselights.ui.library.LibraryScreen
import app.houselights.ui.library.WallView
import app.houselights.ui.onboarding.AddFolderFlowScreen
import app.houselights.ui.onboarding.AddFolderScreen
import app.houselights.ui.onboarding.FlowDone
import app.houselights.ui.onboarding.JellyfinLoginScreen
import app.houselights.ui.onboarding.LoginFill
import app.houselights.ui.onboarding.SharePick
import app.houselights.ui.onboarding.WelcomeScreen
import app.houselights.ui.person.PersonScreen
import app.houselights.ui.search.SearchScreen
import app.houselights.ui.settings.SettingsScreen
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.PlayOrigin
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.libraryName
import app.houselights.ui.util.toWriteError
import coil.imageLoader

@Composable
internal fun AppNavigation(nav: NavHostController, read: State<AppSettings>, onPlay: (MediaItem, Boolean, PlayOrigin) -> Unit) {
    val app = appContainer()
    val settings by read
    val folders by app.hasFolders.collectAsStateWithLifecycle()
    if (!settings.loaded) {
        // Never a black screen: unreadable settings show an error with a retry.
        if (settings.readFailed) SettingsUnread(app.settings)
        return
    }
    // The first screen waits for the folders' first read too, under the launch screen; never unknown again after.
    val source = shownSource(settings.source, folders ?: return)

    val scope = rememberCoroutineScope()
    val start: Any = remember { if (source != null) Route.Home else Route.Welcome }
    val playProblem by app.playback.playbackProblem.collectAsStateWithLifecycle()

    fun openItem(id: String) = nav.navigate(Route.Item(id))
    fun goHome() = nav.navigate(Route.Home) { popUpTo(0) { inclusive = true } }

    // Why choosing folders on the welcome screen failed (storage refused it), shown there until it is left.
    var sourceProblem by remember { mutableStateOf<UiError?>(null) }

    /**
     * "My files": reuses folders added before, else opens « Ajouter un dossier »; a server left stays signed in unless
     * [keepServer] is false. Failures go to [onError], shown where focus is.
     */
    fun useFolders(keepServer: Boolean, onError: (UiError) -> Unit) {
        scope.launchAction("Sources", "Choosing the folders", onError = onError, errorOf = Throwable::toWriteError) {
            chooseFolders(keepServer, app.library::hasFolders, app.sources, addFolder = { nav.navigate(Route.AddFolder()) }, ::goHome)
        }
    }

    // Screens cut, never cross-fade: the backdrop carries across (BackdropRelay) and the text changes at once;
    // the exiting screen is hidden from the first frame, so it never shows under one without a backdrop.
    NavHost(
        nav,
        startDestination = start,
        enterTransition = { EnterTransition.None },
        exitTransition = { fadeOut(snap()) },
    ) {
        composable<Route.Welcome> {
            // Cleared on leaving, so a later visit (signed out, connection lost) does not repeat it.
            DisposableEffect(Unit) { onDispose { sourceProblem = null } }
            WelcomeScreen(
                onLocal = {
                    sourceProblem = null
                    useFolders(keepServer = true) { sourceProblem = it }
                },
                onJellyfin = { nav.navigate(Route.JellyfinLogin.of(LoginFill.Blank)) },
                problem = sourceProblem,
                onReadBack = ::goHome,
            )
        }
        composable<Route.JellyfinLogin> { entry -> JellyfinLoginScreen(entry.toRoute<Route.JellyfinLogin>().fill, onLoggedIn = ::goHome) }
        composable<Route.AddFolder> { entry ->
            val route = entry.toRoute<Route.AddFolder>()
            val folderId = route.folderId
            val pick = route.sharePick
            when {
                // « Choisir un dossier partagé »: the steps' step 4 over the form, which the folder chosen goes back to
                // (#59); Back returns to the form, nothing chosen.
                pick != null -> AddFolderFlowScreen(onLeave = { nav.leaveSharePick() }, onDone = {}, pick = pick, onPick = nav::sharePicked)
                // « Ajouter un dossier », its steps; Back from the first leaves it where it was opened. A folder of the
                // player's disks is edited through them, Back from its disk's root returning to its sheet.
                folderId == null || route.onDisk -> AddFolderFlowScreen(
                    onLeave = { edited -> nav.leaveFolderSteps(edited) },
                    onDone = { done -> nav.folderStepsDone(done, ::goHome) },
                    editing = folderId,
                )
                else -> {
                    val picked by entry.savedStateHandle.getStateFlow<String?>(PICKED_SHARE, null).collectAsStateWithLifecycle()
                    AddFolderScreen(
                        folderId,
                        focusOn = route.focus,
                        onDone = {
                            // A folder edited: back to settings, else (opened from home's problem) home.
                            if (!nav.popBackStack<Route.Settings>(inclusive = false)) goHome()
                        },
                        picked = picked,
                        onPickShare = nav::openSharePick,
                        onPickRead = { entry.savedStateHandle[PICKED_SHARE] = null },
                    )
                }
            }
        }
        composable<Route.Home> { entry ->
            val context = LocalContext.current
            val ahead = rememberPicturesAhead()
            WithSource(source) {
                HomeScreen(
                    nav.homeActions(
                        HomeActions(
                            onPlay = { item, resume -> onPlay(item, resume, PlayOrigin(entry.id)) },
                            onClearProblem = app.playback::clearProblem,
                            onOpenItem = { openItem(it.id) },
                            onAddFolder = { nav.navigate(Route.AddFolder()) },
                            onLibraryAhead = { library, sagas ->
                                app.libraryAhead.ask(app.scope, library, sagas, ahead, context.imageLoader)
                            },
                        ),
                    ),
                    playProblem = playProblem?.takeIf { it.origin.screen == entry.id }?.error,
                )
            }
        }
        composable<Route.Watch> { entry ->
            WithSource(source) { WatchPage(nav, entry, onOpenItem = { openItem(it.id) }) }
        }
        composable<Route.Search> {
            WithSource(source) {
                SearchScreen(
                    onOpenItem = { found -> openItem(found.id) },
                    onOpenPerson = { id -> nav.navigate(Route.Person(id)) },
                    onEditArtwork = nav::openArtwork,
                    onIdentify = nav::openIdentify,
                )
            }
        }
        composable<Route.Library> { entry ->
            val route = entry.toRoute<Route.Library>()
            WithSource(source) {
                LibraryScreen(
                    library = route.wallLibrary,
                    title = route.wallTitle(libraryName(route.kind)),
                    onOpenItem = { openItem(it.id) },
                    opensOn = route.filter,
                    personName = route.personName,
                    sagas = route.sagasLibrary,
                    onEditArtwork = nav::openArtwork,
                    onIdentify = nav::openIdentify,
                )
            }
        }
        composable<Route.Item> { entry ->
            WithSource(source) {
                DetailScreen(
                    itemId = entry.toRoute<Route.Item>().id,
                    screen = entry.id,
                    onPlay = onPlay,
                    onOpenItem = ::openItem,
                    onEditArtwork = nav::openArtwork,
                    onIdentify = nav::openIdentify,
                    onOpenPerson = { nav.navigate(Route.Person(it)) },
                    // A copy of the film left under another row: its page in this one's place.
                    onLeave = { stays ->
                        nav.popBackStack()
                        stays?.let(::openItem)
                    },
                )
            }
        }
        composable<Route.Artwork> { entry ->
            val route = entry.toRoute<Route.Artwork>()
            WithSource(source) { ArtworkScreen(itemId = route.id, kind = route.kind, onDone = { nav.popBackStack() }) }
        }
        composable<Route.Identify> { entry ->
            val route = entry.toRoute<Route.Identify>()
            WithSource(source) { IdentifyScreen(route.id, onDone = { nav.popBackStack() }, queue = route.queue, onNext = nav::queueTo) }
        }
        composable<Route.ToIdentify> { entry ->
            val at by entry.savedStateHandle.getStateFlow<String?>(QUEUE_AT, null).collectAsStateWithLifecycle()
            WithSource(source) {
                ToIdentifyScreen(onOpen = nav::openQueue, queueAt = at, onQueueAtFocus = { entry.savedStateHandle[QUEUE_AT] = null })
            }
        }
        composable<Route.Person> { entry ->
            WithSource(source) {
                PersonScreen(
                    entry.toRoute<Route.Person>().id,
                    onOpenItem = { openItem(it.id) },
                    onEditArtwork = nav::openArtwork,
                    onIdentify = nav::openIdentify,
                )
            }
        }
        composable<Route.Settings> { entry ->
            // A folder « Ajouter un dossier » added or edited from here: its showcase takes focus, its sheet too once its
            // edit was left.
            val added by entry.savedStateHandle.getStateFlow<Long?>(ADDED_FOLDER, null).collectAsStateWithLifecycle()
            val sheet by entry.savedStateHandle.getStateFlow<Long?>(FOLDER_SHEET, null).collectAsStateWithLifecycle()
            // Source changes keep Settings on the back stack: Back returns to it, the source unchanged.
            WithSource(source) {
                SettingsScreen(
                    onFolderForm = { folderId, focus, onDisk -> nav.navigate(Route.AddFolder(folderId, focus, onDisk)) },
                    onUseFolders = ::useFolders,
                    onSignIn = { nav.navigate(Route.JellyfinLogin.of(it)) },
                    onSourceResume = ::goHome,
                    onOpenCatalog = { nav.navigate(Route.Catalog) },
                    onOpenWall = nav::openWall,
                    onOpenPerson = { id -> nav.navigate(Route.Person(id)) },
                    onOpenToIdentify = { nav.openOnce(Route.ToIdentify) },
                    addedFolder = added,
                    folderSheet = sheet,
                    onAddedFolderShow = {
                        entry.savedStateHandle[ADDED_FOLDER] = null
                        entry.savedStateHandle[FOLDER_SHEET] = null
                    },
                )
            }
        }
        // Debug builds only: every component in its states, for design review.
        if (BuildConfig.DEBUG) composable<Route.Catalog> { CatalogScreen() }
    }
    WelcomeWhenLost(nav, source)
    // A play's problem belongs to the screen it was asked from: another one shown clears it.
    LaunchedEffect(nav) { nav.currentBackStackEntryFlow.collect { app.playback.screenShown(it.id) } }
}

/** « À regarder » at [entry], on the row it was opened on, with home's rows as home read them: what its doors showed. */
@Composable
private fun WatchPage(
    nav: NavController,
    entry: NavBackStackEntry,
    onOpenItem: (MediaItem) -> Unit,
    vm: HomeViewModel = viewModel(remember(entry) { nav.watchOwner(entry) }, factory = HomeViewModel.factory(appContainer())),
) {
    val row = entry.toRoute<Route.Watch>().watchRow
    WatchScreen(vm, row, onOpenItem, onEditArtwork = nav::openArtwork, onIdentify = nav::openIdentify)
}

/** The choice of title [id]'s picture of [kind], from its page's Modifier or its poster's. */
private fun NavController.openArtwork(id: String, kind: ArtworkKind) = navigate(Route.Artwork(id, kind))

/** The search title [id] is identified by, from its page's Modifier or its poster's. */
private fun NavController.openIdentify(id: String) = navigate(Route.Identify(id))

/** Whose state « À regarder » at [entry] shares: home's beneath it, so it shows the rows home read, read once; else its own. */
internal fun NavController.watchOwner(entry: NavBackStackEntry): NavBackStackEntry = homeEntry() ?: entry

/** Home's entry in the back stack, beneath every other screen; null when it is not there. */
internal fun NavController.homeEntry(): NavBackStackEntry? = entryOrNull<Route.Home>()

/** Settings' entry in the back stack, under « Ajouter un dossier » opened from it; null when it is not there. */
private fun NavController.settingsEntry(): NavBackStackEntry? = entryOrNull<Route.Settings>()

/** The route on top of the back stack when it is a [T]; null when another screen, or none, is on top. */
private inline fun <reified T : Any> NavController.topOrNull(): T? =
    currentBackStackEntry?.takeIf { it.destination.hasRoute<T>() }?.toRoute<T>()

/** The topmost entry of route [T] in the back stack; null when there is none. */
private inline fun <reified T : Any> NavController.entryOrNull(): NavBackStackEntry? = try {
    getBackStackEntry<T>()
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Back from « Ajouter un dossier »'s first step: where it was opened; from the disk's root of the folder [edited], its
 * sheet in Settings, open again. A folder the edit could not read (gone, or the storage silent) has no sheet to open.
 */
private fun NavController.leaveFolderSteps(edited: Long?) {
    if (edited != null) {
        settingsEntry()?.savedStateHandle?.let { opener ->
            // Only the last folder Settings is handed is shown: one handed earlier and never shown would take its place.
            opener[ADDED_FOLDER] = null
            opener[FOLDER_SHEET] = edited
        }
    }
    popBackStack()
}

/**
 * A folder added or edited through « Ajouter un dossier »'s steps ([done]): a first folder or a new source home
 * ([goHome]), its first scan showing; another Réglages, on its showcase.
 */
private fun NavController.folderStepsDone(done: FlowDone, goHome: () -> Unit) {
    val opener = settingsEntry()
    if (done.switched || opener == null) {
        goHome()
    } else {
        opener.savedStateHandle[FOLDER_SHEET] = null
        opener.savedStateHandle[ADDED_FOLDER] = done.folderId
        popBackStack<Route.Settings>(inclusive = false)
    }
}

/** The folder « Ajouter un dossier » just added or edited, handed to Settings beneath it so that its showcase takes focus. */
private const val ADDED_FOLDER = "addedFolder"

/** « Choisir un dossier partagé » on the form of « Modifier le dossier » ([pick]): the steps' step 4 over it, once. */
internal fun NavController.openSharePick(pick: SharePick) {
    if (!pickOnTop()) navigate(Route.AddFolder.picking(pick))
}

/**
 * The shared folder [path] chosen at the steps' step 4 over the form ([openSharePick]): handed to the form beneath it
 * ([PICKED_SHARE]), which tests it, and the step left; once, a second OK as it goes choosing nothing more.
 */
internal fun NavController.sharePicked(path: String) {
    if (!pickOnTop()) return
    previousBackStackEntry?.savedStateHandle?.set(PICKED_SHARE, path)
    popBackStack()
}

/** Back from the steps' step 4 over the form ([openSharePick]): the form as it was, nothing chosen; once. */
internal fun NavController.leaveSharePick() {
    if (pickOnTop()) popBackStack()
}

/** Whether the steps' step 4 over the form is the screen on top. */
private fun NavController.pickOnTop(): Boolean = topOrNull<Route.AddFolder>()?.pick == true

/** The shared folder chosen at the steps' step 4, handed to the form of « Modifier le dossier » beneath it. */
internal const val PICKED_SHARE = "pickedShare"

/** The folder whose edit through the flow's steps was left by Back, handed to Settings so that its sheet opens again. */
private const val FOLDER_SHEET = "folderSheet"

// A wall is a page over the screen that opens it, home or another page: Back goes back to that screen.

/** Home's doors wired to the pages they open over it; the rest of what home does, from [base]. */
internal fun NavController.homeActions(base: HomeActions = HomeActions()): HomeActions = base.copy(
    onOpenWatch = { row -> openOnce(Route.Watch.of(row)) },
    onOpenLibrary = { library, sagas -> openLibrary(library, sagas) },
    onOpenSagas = { films, sagas -> openSagas(films, sagas) },
    onOpenSearch = { openOnce(Route.Search) },
    onOpenSettings = { openOnce(Route.Settings) },
)

/** Opens [library]'s whole wall, a films' wall with the source's [sagas] among its films. */
internal fun NavController.openLibrary(library: Library, sagas: Library? = null) =
    openWall(Route.Library(library.id, library.kind, library.title, sagas = sagas?.id))

/** Opens the films' wall ([films]) on the source's [sagas] alone; without a films library, the sagas' own wall. */
internal fun NavController.openSagas(films: Library?, sagas: Library) = openWall(
    if (films != null) {
        Route.Library(films.id, films.kind, films.title, sagas = sagas.id, view = WallView.SAGAS.name)
    } else {
        Route.Library(sagas.id, sagas.kind, sagas.title)
    },
)

/** Opens [library]'s wall filtered by [filter]. */
internal fun NavController.openWall(library: Library, filter: LibraryFilter) = openWall(Route.Library.of(library, filter))

// OK pressed twice before a page shows asks for it twice: the second finds it on top and opens nothing.

/** Opens the wall of [route], unless that very wall is on top; another wall opens over it. */
private fun NavController.openWall(route: Route.Library) {
    if (topOrNull<Route.Library>() != route) navigate(route)
}

/** Opens the page of [route], one of a kind, unless it is on top. */
private fun NavController.openOnce(route: Any) = navigate(route) { launchSingleTop = true }

/** Identifier on [id] over the titles to identify, those after it following ([queueTo]); once, OK pressed twice. */
internal fun NavController.openQueue(id: String) {
    if (currentBackStackEntry?.destination?.hasRoute<Route.ToIdentify>() == true) navigate(Route.Identify(id, queue = true))
}

/**
 * The queue moves on to [next], over the list, which Back returns to, on that title ([QUEUE_AT]), never from title to
 * title; after the last, back to the settings rather than to an empty list.
 */
internal fun NavController.queueTo(next: String?) {
    val list = entryOrNull<Route.ToIdentify>()
    when {
        list == null -> popBackStack()
        next == null -> popBackStack<Route.ToIdentify>(inclusive = true)
        else -> {
            list.savedStateHandle[QUEUE_AT] = next
            navigate(Route.Identify(next, queue = true)) { popUpTo<Route.Identify> { inclusive = true } }
        }
    }
}

/** The title the queue reached, which the list's focus goes to once back ([Route.ToIdentify]'s saved state). */
internal const val QUEUE_AT = "queueAt"

/** [content], a screen that shows the source, composed only while there is one ([source]): it would read it at once. */
@Composable
private fun WithSource(source: SourceConfig?, content: @Composable () -> Unit) {
    if (source != null) content()
}

/**
 * Sends a screen that shows the source to the welcome screen, alone, while there is none ([shownSource]): no folder
 * left, or restored after the process died, its connection could not be read back. Apart, so that only it follows each
 * navigation.
 */
@Composable
internal fun WelcomeWhenLost(nav: NavHostController, source: SourceConfig?) {
    val entry by nav.currentBackStackEntryAsState()
    val lost = mustWelcome(source, entry?.destination)
    LaunchedEffect(lost) { if (lost) nav.navigate(Route.Welcome) { popUpTo(0) { inclusive = true } } }
}

/**
 * The source the screens show, derived and never stored: [source], but none for the folders without a folder
 * ([hasFolders]), which the welcome screen replaces as on a first launch.
 */
internal fun shownSource(source: SourceConfig?, hasFolders: Boolean): SourceConfig? =
    source.takeUnless { it == SourceConfig.Local && !hasFolders }

/**
 * "Mes fichiers": the folders added before become the source, then [goHome]; a server left stays kept unless
 * [keepServer] is false. Without a folder, [addFolder] opens « Ajouter un dossier »; signing out instead leaves no library, and the
 * welcome screen follows ([WelcomeWhenLost]).
 */
internal suspend fun chooseFolders(
    keepServer: Boolean,
    hasFolders: suspend () -> Boolean,
    sources: SourceSwitcher,
    addFolder: () -> Unit,
    goHome: () -> Unit,
) {
    when {
        hasFolders() -> {
            sources.switchTo(SourceConfig.Local, keepServer)
            goHome()
        }
        keepServer -> addFolder()
        else -> sources.switchTo(SourceConfig.Local, keepServer = false)
    }
}

/** Shown when the settings keep failing to load: an error with Réessayer, busy while they are read again. */
@Composable
private fun SettingsUnread(settings: SettingsStore) {
    val reading by settings.reading.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().padding(top = HlLayout.errorTop)) {
        HlErrorState(UiError.SETTINGS_UNREAD, settings::readAgain, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l), reading)
    }
}

/** The screens shown without a source: the welcome screen and the forms that set one up. */
private val SETUP_SCREENS = listOf(Route.Welcome::class, Route.JellyfinLogin::class, Route.AddFolder::class)

/** Whether the screen at [destination] gives way to the welcome screen: there is no source and it would show one. */
internal fun mustWelcome(source: SourceConfig?, destination: NavDestination?): Boolean =
    source == null && destination != null && SETUP_SCREENS.none { destination.hasRoute(it) }

/** Navigates to where an error is fixed; retrying is left to each screen. */
internal fun NavHostController.fixError(action: ErrorAction) = when (action) {
    ErrorAction.RECONNECT -> navigate(Route.JellyfinLogin())
    ErrorAction.ADD_TMDB_KEY, ErrorAction.EDIT_TMDB_KEY -> navigate(Route.Settings)
    // The trust sheet opens over the screen ([TrustAsked.fixing]).
    ErrorAction.RETRY, ErrorAction.TRUST_PLAYER -> Unit
}
