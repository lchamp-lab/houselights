package app.houselights

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb
import app.houselights.ui.design.HlPalette
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.player.PlaybackManager
import app.houselights.ui.util.ErrorAction
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
import app.houselights.ui.detail.NfoScreen
import app.houselights.ui.edit.ArtworkScreen
import app.houselights.ui.edit.IdentifyScreen
import app.houselights.ui.home.HomeScreen
import app.houselights.ui.library.LibraryScreen
import app.houselights.ui.onboarding.AddFolderScreen
import app.houselights.ui.onboarding.JellyfinLoginScreen
import app.houselights.ui.onboarding.WelcomeScreen
import app.houselights.ui.settings.SettingsScreen
import app.houselights.ui.design.HouselightsTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.houselights.settings.DisplayModes
import app.houselights.settings.SettingsStore
import app.houselights.settings.outputModes
import app.houselights.ui.design.Scrub
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.snap
import app.houselights.ui.design.components.BackdropRelay
import app.houselights.ui.design.components.LeavingBackdrop
import app.houselights.ui.design.components.LocalBackdropRelay
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // The mark alone on black (Theme.Houselights.Launch) covers a cold start while home loads underneath.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        holdTheLaunchScreen(splash, restored = savedInstanceState != null)

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
            HouselightsTheme {
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
                ) {
                    // The window background is already the room (@color/room): no second black under every screen.
                    Box(Modifier.fillMaxSize()) {
                        LeavingBackdrop(relay)
                        AppNavigation(nav, onPlay = ::play)
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
        container.homeLaunch.begin(container.scope, restored, loadFirstPictures(applicationContext, resources.displayMetrics.widthPixels))
        splash.setOnExitAnimationListener { view ->
            if (!held) {
                view.remove()
            } else {
                lifecycleScope.launch {
                    launch.awaitOpen()
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
            LaunchMarkDrawable(HlPalette.Ember.toArgb()).also(view::setImageDrawable)
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
    }

    /** During the cue, Back cancels it; other non-system keys are swallowed, so a second OK never plays twice. */
    // This is Activity.dispatchKeyEvent; lint sees androidx.core's restricted overload of the same name.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Tracks whether an arrow is held (focus running across cards); it sees every key and consumes none.
        Scrub.onKey(event)
        return when {
            // Under the launch screen nothing is selected unseen: only system keys pass, Back included.
            covered && !event.isSystem -> true
            !lightsDown.value -> super.dispatchKeyEvent(event)
            event.keyCode == KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) cancelCue()
                true
            }
            event.isSystem -> super.dispatchKeyEvent(event)
            else -> true
        }
    }

    private fun play(item: MediaItem, resume: Boolean) {
        // One cue at a time.
        if (lightsDown.value) return
        container.playback.clearProblem()
        lightsDown.value = true
        cue = lifecycleScope.launch {
            val s = container.settings.state.value
            suspendRunCatching { container.provider.playback(item, resume, PlaybackOptions(s.pathMappings, s.forceHttp)) }
                .onSuccess { plan ->
                    // The cue ran while the plan resolved: the room is dark before the player takes over.
                    snapshotFlow { lightsLevel.value }.first { it >= 1f }
                    when (plan) {
                        is PlaybackPlan.Launch -> try {
                            // The player needs the memory more than pictures that reload from disk.
                            imageLoader.memoryCache?.clear()
                            playerLauncher.launch(container.player.intentFor(plan))
                            container.player.onPlayerLaunched()
                        } catch (_: ActivityNotFoundException) {
                            container.player.onLaunchFailed()
                            failed(UiError.NO_PLAYER)
                        } catch (_: SecurityException) {
                            // A player that refuses to be started by other apps is unusable here.
                            container.player.onLaunchFailed()
                            failed(UiError.NO_PLAYER)
                        } catch (e: PlaybackManager.CredentialsNotShared) {
                            failed(if (e.playerChanged) UiError.PLAYER_CHANGED else UiError.CREDENTIALS)
                        }
                    }
                }
                .onFailure {
                    Log.w("Playback", "Playback failed", it)
                    failed(it.toUiError())
                }
        }
    }

    /** Brings the lights back up; the detail page shows why nothing plays. */
    private fun failed(error: UiError) {
        lightsDown.value = false
        container.playback.reportProblem(error)
    }

    private fun cancelCue() {
        cue?.cancel()
        cue = null
        lightsDown.value = false
    }
}

@Composable
private fun AppNavigation(nav: NavHostController, onPlay: (MediaItem, Boolean) -> Unit) {
    val app = appContainer()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    if (!settings.loaded) {
        // Never a black screen: unreadable settings show an error with a retry.
        if (settings.readFailed) SettingsUnread(app.settings)
        return
    }

    val scope = rememberCoroutineScope()
    val start: Any = remember { if (settings.source != null) Route.Home else Route.Welcome }

    fun openItem(id: String) = nav.navigate(Route.Item(id))
    fun openLibrary(library: Library, sagas: Library? = null) =
        nav.navigate(Route.Library(library.id, library.kind, library.title, sagas = sagas?.id))
    fun goHome() = nav.navigate(Route.Home) { popUpTo(0) { inclusive = true } }

    // Why choosing folders on the welcome screen failed (storage refused it), shown there until it is left.
    var sourceProblem by remember { mutableStateOf<UiError?>(null) }

    /** "My files": reuses folders added before, else opens the form; failures go to [onError], shown where focus is. */
    fun useFolders(onError: (UiError) -> Unit) {
        scope.launchAction("Sources", "Choosing the folders", onError = onError, errorOf = Throwable::toWriteError) {
            if (!app.library.hasFolders()) {
                nav.navigate(Route.AddFolder())
            } else {
                app.sources.switchTo(SourceConfig.Local)
                goHome()
            }
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
                    useFolders { sourceProblem = it }
                },
                onJellyfin = { nav.navigate(Route.JellyfinLogin) },
                problem = sourceProblem,
            )
        }
        composable<Route.JellyfinLogin> { JellyfinLoginScreen(onLoggedIn = ::goHome) }
        composable<Route.AddFolder> { entry ->
            val route = entry.toRoute<Route.AddFolder>()
            AddFolderScreen(route.folderId, route.focus, onDone = { switched ->
                // A new source or a first folder: the home screen; another folder, or one edited: back to settings.
                if (switched || !nav.popBackStack<Route.Settings>(inclusive = false)) goHome()
            })
        }
        composable<Route.Home> {
            HomeScreen(
                onOpenItem = { openItem(it.id) },
                onOpenLibrary = ::openLibrary,
                onOpenSettings = { nav.navigate(Route.Settings) },
                onAddFolder = { nav.navigate(Route.AddFolder()) },
                onOpenSearch = { nav.navigate(Route.Search) },
            )
        }
        composable<Route.Search> {
            SearchScreen(onOpenItem = { found -> openItem(found.id) }, onOpenPerson = { id -> nav.navigate(Route.Person(id)) })
        }
        composable<Route.Library> { entry ->
            val route = entry.toRoute<Route.Library>()
            val library = Library(id = route.id, kind = route.kind, title = route.title?.ifBlank { null })
            LibraryScreen(
                library = library,
                title = library.title ?: libraryName(library.kind),
                onOpenItem = { openItem(it.id) },
                opensOn = route.filter,
                personName = route.personName,
                sagas = route.sagas?.let { Library(id = it, kind = LibraryKind.COLLECTIONS, title = null) },
            )
        }
        composable<Route.Item> { entry ->
            DetailScreen(
                itemId = entry.toRoute<Route.Item>().id,
                onPlay = onPlay,
                onOpenItem = ::openItem,
                onOpenNfo = { nav.navigate(Route.Nfo(it)) },
                onEditArtwork = { id, kind -> nav.navigate(Route.Artwork(id, kind)) },
                onIdentify = { nav.navigate(Route.Identify(it)) },
                onOpenPerson = { nav.navigate(Route.Person(it)) },
            )
        }
        composable<Route.Artwork> { entry ->
            val route = entry.toRoute<Route.Artwork>()
            ArtworkScreen(itemId = route.id, kind = route.kind, onDone = { nav.popBackStack() })
        }
        composable<Route.Identify> { entry ->
            IdentifyScreen(entry.toRoute<Route.Identify>().id, onDone = { nav.popBackStack() })
        }
        composable<Route.Nfo> { entry -> NfoScreen(entry.toRoute<Route.Nfo>().id) }
        composable<Route.Person> { entry -> PersonScreen(entry.toRoute<Route.Person>().id, onOpenItem = { openItem(it.id) }) }
        composable<Route.Settings> {
            // Source changes keep Settings on the back stack: Back returns to it, the source unchanged.
            SettingsScreen(
                onFolderForm = { folderId, focus -> nav.navigate(Route.AddFolder(folderId, focus)) },
                onUseFolders = ::useFolders,
                onSignIn = { nav.navigate(Route.JellyfinLogin) },
                onOpenCatalog = { nav.navigate(Route.Catalog) },
                onOpenWall = { library, filter -> nav.navigate(Route.Library.of(library, filter)) },
                onOpenPerson = { id -> nav.navigate(Route.Person(id)) },
            )
        }
        // Debug builds only: every component in its states, for design review.
        if (BuildConfig.DEBUG) composable<Route.Catalog> { CatalogScreen() }
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

/** Navigates to where an error is fixed; retrying is left to each screen. */
private fun NavHostController.fixError(action: ErrorAction) = when (action) {
    ErrorAction.RECONNECT -> navigate(Route.JellyfinLogin)
    ErrorAction.ADD_TMDB_KEY, ErrorAction.EDIT_TMDB_KEY -> navigate(Route.Settings)
    ErrorAction.RETRY -> Unit
}

/** Returning to the app scans for new files unless a scan ended this recently. */
private const val RESCAN_AFTER_MS = 2 * 60_000L

/** Scan interval while the app is visible. */
private const val RESCAN_EVERY_MS = 10 * 60_000L

/** Fully opaque drawable alpha. */
private const val OPAQUE = 255
