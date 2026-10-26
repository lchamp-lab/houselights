package app.houselights

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.design.LaunchScreen
import app.houselights.ui.design.LocalLaunchScreen
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import app.houselights.ui.design.LaunchCue
import app.houselights.ui.home.loadFirstPictures
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.MediaItem
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.PlayOrigin
import app.houselights.ui.util.PlaybackProblem
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.libraryName
import app.houselights.ui.util.toUiError
import app.houselights.ui.util.toWriteError
import coil.imageLoader
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.HouseLights
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import app.houselights.ui.catalog.CatalogScreen
import app.houselights.ui.detail.DetailScreen
import app.houselights.ui.edit.ArtworkScreen
import app.houselights.ui.edit.IdentifyScreen
import app.houselights.ui.home.HomeActions
import app.houselights.ui.home.HomeScreen
import app.houselights.ui.home.WatchScreen
import app.houselights.ui.library.WallView
import app.houselights.ui.design.components.rememberPicturesAhead
import app.houselights.ui.design.textScaleOf
import androidx.compose.ui.platform.LocalContext
import app.houselights.ui.home.HomeViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import app.houselights.ui.library.LibraryScreen
import app.houselights.ui.onboarding.AddFolderFlowScreen
import app.houselights.ui.onboarding.AddFolderScreen
import app.houselights.ui.onboarding.JellyfinLoginScreen
import app.houselights.ui.onboarding.LoginFill
import app.houselights.ui.onboarding.WelcomeScreen
import app.houselights.ui.settings.SettingsScreen
import app.houselights.ui.design.HouselightsTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.houselights.settings.AppSettings
import app.houselights.settings.DisplayModes
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.settings.outputModes
import app.houselights.ui.design.Scrub
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.snap
import app.houselights.ui.design.components.BackdropRelay
import app.houselights.ui.design.components.LeavingBackdrop
import app.houselights.ui.design.components.LocalBackdropRelay
import app.houselights.ui.design.components.LocalCutPosters
import app.houselights.ui.person.PersonScreen
import app.houselights.ui.search.SearchScreen

class MainActivity : ComponentActivity() {
    private val container by lazy { appContainer() }

    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        container.player.onPlayerResult(it.data)
    }

    /** House lights: down while the player starts, back up on return to the app. */
    private val lightsDown = mutableStateOf(false)

    /** Drawn dimming level, 0 to 1: the player starts only once the room is dark. */
    private var lightsLevel: State<Float> = mutableFloatStateOf(0f)

    /** The dimming cue in progress until the player takes over; Back cancels it. */
    private var cue: Job? = null

    /** True while the launch screen covers the app (first window of a cold start), until its black starts to lift. */
    private var covered = false

    private var drawnWhole = false

    /** The launch screen as the screens beneath it see it ([LocalLaunchScreen]). */
    private val launchScreen = object : LaunchScreen {
        override val covers: Boolean get() = covered

        override fun drawn() {
            if (!covered || drawnWhole) return
            drawnWhole = true
            // Posted to run after the frame rather than during its drawing: the cue starts on the next one.
            window.decorView.post(container.launch::homeDrawn)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        newBase.appContainer().language.overrides()?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The mark alone on black (Theme.Houselights.Launch) covers a cold start while home loads underneath.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        holdTheLaunchScreen(splash, restored = savedInstanceState != null)

        // Another interface language: the screens are made again in it, where they were. Android 9 drops a
        // relaunch asked while the activity is only started, so it is asked once resumed.
        val spoken = container.language.chosen.value
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                container.language.chosen.first { it != spoken }
                recreate()
            }
        }
        lifecycleScope.launch {
            container.settings.state
                .filter { it.loaded }
                .map { it.ui4k }
                .distinctUntilChanged()
                .collect(::applyDisplayMode)
        }
        // An incremental scan on coming to the foreground (unless one just ran), then every few minutes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settings.state.first { it.loaded }
                // Not while the launch screen holds: home's rows and pictures come first.
                container.launch.awaitOpen()
                while (true) {
                    if (container.settings.state.value.source == SourceConfig.Local) {
                        container.scanner.scanIfStale(RESCAN_AFTER_MS)
                    }
                    delay(RESCAN_EVERY_MS)
                }
            }
        }

        setContent {
            // One read for the theme and the screens: nothing shows before the settings are read, then at their text size.
            val settings = container.settings.state.collectAsStateWithLifecycle()
            HouselightsTheme(textStep = settings.value.textSize.step) {
                val nav = rememberNavController()
                val fixError = remember(nav) { nav::fixError }
                val relay = remember { BackdropRelay() }
                // One cue for the whole app; its steps (room, scrims, black) are in HouseLightCue.
                val lights = animateFloatAsState(
                    if (lightsDown.value) 1f else 0f,
                    tween(HlMotion.DIM, easing = LinearEasing),
                    label = "houselights",
                )
                SideEffect { lightsLevel = lights }
                CompositionLocalProvider(
                    LocalHouseLights provides { lights.value },
                    LocalErrorFix provides fixError,
                    LocalLaunchScreen provides launchScreen,
                    LocalBackdropRelay provides relay,
                    LocalCutPosters provides container.cutPosters,
                ) {
                    // The window background is already the room (@color/room): no second black under every screen.
                    Box(Modifier.fillMaxSize()) {
                        LeavingBackdrop(relay)
                        AppNavigation(nav, settings, onPlay = ::play)
                        HouseLights()
                    }
                }
            }
        }
    }

    /**
     * On a cold start's first window, the launch screen covers the app until [LaunchGate] opens while
     * [HomeLaunch] prefetches and home draws underneath (the splash view lies over it; drawing is never
     * held), then leaves on its cue ([lightsDown]). A warm start holds nothing.
     */
    private fun holdTheLaunchScreen(splash: SplashScreen, restored: Boolean) {
        val launch = container.launch
        val held = !launch.isOpen
        covered = held
        val system = resources.configuration.fontScale
        val settings = container.settings
        val pictures = loadFirstPictures(
            applicationContext,
            resources.displayMetrics.widthPixels,
            // Read once the settings are, as home is laid out.
            textScale = { textScaleOf(system, settings.state.value.textSize.step) },
            cuts = container.cutPosters,
        ) { container.providerOrNull()?.capabilities?.search == true }
        container.homeLaunch.begin(container.scope, restored, pictures)
        // The launch screen lifts once the gate opens, on a complete home or at its cap: the start-up's full display.
        splash.setOnExitAnimationListener { view ->
            if (!held) {
                view.remove()
                reportFullyDrawn()
            } else {
                lifecycleScope.launch {
                    launch.awaitOpen()
                    reportFullyDrawn()
                    lightsDown(view)
                }
            }
        }
    }

    /**
     * Plays the launch cue ([LaunchCue]): bars out one by one, widest first, then the black lifts. The
     * background and each bar fade separately, so the GPU never fills a full-screen layer each frame.
     */
    private fun lightsDown(splash: SplashScreenViewProvider) {
        // Mutated: the room drawable is shared with the window, which must stay black.
        val ground = splash.view.background?.mutate()
        val icon = splash.iconView
        // On Android 9 the mark is an image view spanning its whole layer: the bars are drawn into it.
        val bars = (icon as? ImageView)?.takeIf { Build.VERSION.SDK_INT < Build.VERSION_CODES.S }?.let { view ->
            LaunchMarkDrawable(HouselightsColors).also(view::setImageDrawable)
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LaunchCue.DURATION.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                // The system splash screen (Android 12 and up) draws its own mark: it fades with the last bar.
                if (bars != null) bars.at(t) else icon.alpha = LaunchCue.bar(LaunchCue.bars - 1, t)
                val darkness = LaunchCue.darkness(t)
                if (darkness < 1f) covered = false
                // Without a background drawable, the whole splash view fades instead.
                if (ground != null) ground.alpha = (darkness * OPAQUE).roundToInt() else splash.view.alpha = darkness
            }
            doOnEnd {
                covered = false
                splash.remove()
            }
            start()
        }
    }

    override fun onDestroy() {
        // Back under the launch screen: prefetched rows are dropped so a later window never shows stale ones.
        if (isFinishing) container.homeLaunch.drop()
        super.onDestroy()
    }

    /** Requests 2160p60 for the UI where listed; a Zidoo lists only placeholder modes and sets its own output. */
    private fun applyDisplayMode(ui4k: Boolean) {
        @Suppress("DEPRECATION")
        val display: Display = window.decorView.display ?: windowManager.defaultDisplay
        val modes = display.outputModes()
        val control = DisplayModes.control(modes)
        val target = if (ui4k) DisplayModes.ui4k(modes) else null
        Log.i("Display", "Current mode ${display.mode}, listed $modes ($control), target $target")
        window.attributes = window.attributes.apply { preferredDisplayModeId = target?.id ?: 0 }
    }

    override fun onResume() {
        super.onResume()
        // Back from the player, or from elsewhere during the cue: the lights come up and nothing plays.
        cancelCue()
        // Secrets the Keystore did not open are tried again.
        container.settings.readAgain()
        // Back from Android's settings, which may now let the app install its update, or from its installer's window.
        container.updates.returned()
    }

    // This is Activity.dispatchKeyEvent; lint sees androidx.core's restricted overload of the same name.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Tracks whether an arrow is held (focus running across cards); it sees every key and consumes none.
        Scrub.onKey(event)
        return dispatchUnseen(event, covered, lightsDown.value, ::cancelCue) { super.dispatchKeyEvent(it) }
    }

    /**
     * Plays [item] after the house-light cue; a failure is said where play was asked ([origin]). A title whose file is
     * missing is not tried ([tryPlay][app.houselights.ui.util.PlaybackOutcome.tryPlay]): the room stays lit, and where
     * play was asked says why.
     */
    private fun play(item: MediaItem, resume: Boolean, origin: PlayOrigin) {
        // One cue at a time.
        if (lightsDown.value) return
        container.playback.tryPlay(item, origin) { dimAndPlay(item, resume, origin) }
    }

    /** Dims the house lights while the plan of [item] resolves, then hands it to the player. */
    private fun dimAndPlay(item: MediaItem, resume: Boolean, origin: PlayOrigin) {
        lightsDown.value = true
        cue = lifecycleScope.launch {
            val s = container.settings.state.value
            suspendRunCatching { container.provider.playback(item, resume, PlaybackOptions(s.pathMappings, s.forceHttp)) }
                .onSuccess { plan ->
                    // The cue ran while the plan resolved: the room is dark before the player takes over.
                    snapshotFlow { lightsLevel.value }.first { it >= 1f }
                    when (plan) {
                        is PlaybackPlan.Launch -> {
                            val launched = container.player.handOff(
                                plan,
                                tracks = { container.tracks.plan(plan) },
                                onScreen = lifecycle::onScreen,
                                freeMemory = {
                                    imageLoader.memoryCache?.clear()
                                    container.cutPosters.clear()
                                },
                                start = playerLauncher::launch,
                                failed = { failed(it, origin) },
                            )
                            // Home pressed meanwhile: the lights come back for whoever returns.
                            if (!launched) lightsDown.value = false
                        }
                    }
                }
                .onFailure {
                    Log.w("Playback", "Playback failed", it)
                    failed(it.toUiError(), origin)
                }
        }
    }

    /** Brings the lights back up; the screen play was asked from ([origin]) says why nothing plays. */
    private fun failed(error: UiError, origin: PlayOrigin) {
        lightsDown.value = false
        container.playback.reportProblem(PlaybackProblem(error, origin))
    }

    private fun cancelCue() {
        cue?.cancel()
        cue = null
        lightsDown.value = false
    }
}

@Composable
private fun AppNavigation(nav: NavHostController, read: State<AppSettings>, onPlay: (MediaItem, Boolean, PlayOrigin) -> Unit) {
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
            if (folderId == null) {
                // « Ajouter un dossier », its steps; Back from the first leaves it where it was opened.
                AddFolderFlowScreen(onLeave = { nav.popBackStack() }, onDone = { done ->
                    // A first folder or a new source: home, its first scan showing; another: Réglages, on its showcase.
                    val settings = nav.settingsEntry()
                    if (done.switched || settings == null) {
                        goHome()
                    } else {
                        settings.savedStateHandle[ADDED_FOLDER] = done.folderId
                        nav.popBackStack<Route.Settings>(inclusive = false)
                    }
                })
            } else {
                AddFolderScreen(folderId, route.focus, onDone = { switched ->
                    // A new source: the home screen; a folder edited: back to settings.
                    if (switched || !nav.popBackStack<Route.Settings>(inclusive = false)) goHome()
                })
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
                SearchScreen(onOpenItem = { found -> openItem(found.id) }, onOpenPerson = { id -> nav.navigate(Route.Person(id)) })
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
                    onEditArtwork = { id, kind -> nav.navigate(Route.Artwork(id, kind)) },
                    onIdentify = { nav.navigate(Route.Identify(it)) },
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
            WithSource(source) { IdentifyScreen(entry.toRoute<Route.Identify>().id, onDone = { nav.popBackStack() }) }
        }
        composable<Route.Person> { entry ->
            WithSource(source) { PersonScreen(entry.toRoute<Route.Person>().id, onOpenItem = { openItem(it.id) }) }
        }
        composable<Route.Settings> { entry ->
            // A folder « Ajouter un dossier » added from here: its showcase takes focus.
            val added by entry.savedStateHandle.getStateFlow<Long?>(ADDED_FOLDER, null).collectAsStateWithLifecycle()
            // Source changes keep Settings on the back stack: Back returns to it, the source unchanged.
            WithSource(source) {
                SettingsScreen(
                    onFolderForm = { folderId, focus -> nav.navigate(Route.AddFolder(folderId, focus)) },
                    onUseFolders = ::useFolders,
                    onSignIn = { nav.navigate(Route.JellyfinLogin.of(it)) },
                    onSourceResumed = ::goHome,
                    onOpenCatalog = { nav.navigate(Route.Catalog) },
                    onOpenWall = nav::openWall,
                    onOpenPerson = { id -> nav.navigate(Route.Person(id)) },
                    addedFolder = added,
                    onAddedFolderShown = { entry.savedStateHandle[ADDED_FOLDER] = null },
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
private fun WatchPage(nav: NavController, entry: NavBackStackEntry, onOpenItem: (MediaItem) -> Unit) {
    val row = entry.toRoute<Route.Watch>().watchRow
    val owner = remember(entry) { nav.watchOwner(entry) }
    val vm: HomeViewModel = viewModel(owner, factory = HomeViewModel.factory(appContainer()))
    WatchScreen(vm, row, onOpenItem)
}

/** Whose state « À regarder » at [entry] shares: home's beneath it, so it shows the rows home read, read once; else its own. */
internal fun NavController.watchOwner(entry: NavBackStackEntry): NavBackStackEntry = homeEntry() ?: entry

/** Home's entry in the back stack, beneath every other screen; null when it is not there. */
internal fun NavController.homeEntry(): NavBackStackEntry? = try {
    getBackStackEntry<Route.Home>()
} catch (_: IllegalArgumentException) {
    null
}

/** Settings' entry in the back stack, under « Ajouter un dossier » opened from it; null when it is not there. */
private fun NavController.settingsEntry(): NavBackStackEntry? = try {
    getBackStackEntry<Route.Settings>()
} catch (_: IllegalArgumentException) {
    null
}

/** The folder « Ajouter un dossier » just added, handed to Settings beneath it so that its showcase takes focus. */
private const val ADDED_FOLDER = "addedFolder"

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
    val top = currentBackStackEntry?.takeIf { it.destination.hasRoute<Route.Library>() }?.toRoute<Route.Library>()
    if (top != route) navigate(route)
}

/** Opens the page of [route], one of a kind, unless it is on top. */
private fun NavController.openOnce(route: Any) = navigate(route) { launchSingleTop = true }

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

/**
 * Passes [event] on ([pass]) unless nothing must be chosen unseen: under the launch screen ([covered]) only system keys
 * pass, Back included; while the lights go down ([dimming]) Back cancels the cue ([cancel]) and other non-system keys
 * are swallowed, so a second OK never plays twice. Menu, though a system key, opens options: it is swallowed too.
 */
internal fun dispatchUnseen(
    event: KeyEvent,
    covered: Boolean,
    dimming: Boolean,
    cancel: () -> Unit,
    pass: (KeyEvent) -> Boolean,
): Boolean {
    val system = event.isSystem && event.keyCode != KeyEvent.KEYCODE_MENU
    return when {
        covered && !system -> true
        !dimming -> pass(event)
        event.keyCode == KeyEvent.KEYCODE_BACK -> {
            if (event.action == KeyEvent.ACTION_UP) cancel()
            true
        }
        system -> pass(event)
        else -> true
    }
}

/** Started, if only paused under an overlay: Home always stops the app, so the player never opens from behind. */
internal fun Lifecycle.onScreen(): Boolean = currentState.isAtLeast(Lifecycle.State.STARTED)

/** Navigates to where an error is fixed; retrying is left to each screen. */
private fun NavHostController.fixError(action: ErrorAction) = when (action) {
    ErrorAction.RECONNECT -> navigate(Route.JellyfinLogin())
    ErrorAction.ADD_TMDB_KEY, ErrorAction.EDIT_TMDB_KEY -> navigate(Route.Settings)
    ErrorAction.RETRY -> Unit
}

/** Returning to the app scans for new files unless a scan ended this recently. */
private const val RESCAN_AFTER_MS = 2 * 60_000L

/** Scan interval while the app is visible. */
private const val RESCAN_EVERY_MS = 10 * 60_000L

/** Fully opaque drawable alpha. */
private const val OPAQUE = 255
