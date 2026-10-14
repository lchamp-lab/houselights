package app.houselights

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.houselights.ui.util.toUiError
import coil.imageLoader
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.LocalHouseLights
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
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.design.HouselightsTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.houselights.settings.DisplayModes
import app.houselights.settings.outputModes

class MainActivity : ComponentActivity() {
    private val container by lazy { appContainer() }

    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        container.playback.onPlayerResult(it.data)
    }

    /** House lights: down while the player starts, back up when the user returns. */
    private val lightsDown = mutableStateOf(false)

    /** How far down the lights are as drawn, 0 to 1: the player waits for the room to be dark. */
    private var lightsLevel: State<Float> = mutableFloatStateOf(0f)

    /** The cue under way, until the player takes over: Back stops it. */
    private var cue: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            container.settings.state
                .filter { it.loaded }
                .map { it.ui4k }
                .distinctUntilChanged()
                .collect(::applyDisplayMode)
        }
        // New files on the NAS show up by themselves: an incremental scan when the app opens or
        // comes back to the front (unless one just ran), then every few minutes while it is shown.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settings.state.first { it.loaded }
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
                // One cue for the whole app; its steps (room, scrims, black) are in HouseLightCue.
                val lights = animateFloatAsState(
                    if (lightsDown.value) 1f else 0f,
                    tween(HlMotion.DIM, easing = LinearEasing),
                    label = "houselights",
                )
                SideEffect { lightsLevel = lights }
                CompositionLocalProvider(LocalHouseLights provides { lights.value }, LocalErrorFix provides fixError) {
                    Box(Modifier.fillMaxSize().background(HouselightsColors.background)) {
                        AppNavigation(nav, onPlay = ::play)
                        HouseLights()
                    }
                }
            }
        }
    }

    /**
     * Asks for 2160p60 for the interface where the system lists it. A player that lists only
     * stand-in modes (the Zidoo) sets its output in its own settings: there nothing is asked, and
     * the stored choice waits for a device where it works.
     */
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
        // Back from the player, or from elsewhere during the cue: the lights come up, nothing plays.
        cancelCue()
    }

    /**
     * While the lights go down the remote waits: Back brings them up again and nothing plays, other
     * keys are held (a second OK never starts a second playback). The system's own keys still work.
     */
    // This is Activity.dispatchKeyEvent; lint sees androidx.core's restricted overload of the same name.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when {
        !lightsDown.value -> super.dispatchKeyEvent(event)
        event.keyCode == KeyEvent.KEYCODE_BACK -> {
            if (event.action == KeyEvent.ACTION_UP) cancelCue()
            true
        }
        event.isSystem -> super.dispatchKeyEvent(event)
        else -> true
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
                    // The room goes dark before the player takes over the screen: the cue ran while
                    // the plan was resolved, and with animations off it is already over.
                    snapshotFlow { lightsLevel.value }.first { it >= 1f }
                    when (plan) {
                        is PlaybackPlan.Launch -> try {
                            // The player needs the memory more than pictures that load again from disk.
                            imageLoader.memoryCache?.clear()
                            playerLauncher.launch(container.playback.intentFor(plan))
                            container.playback.onPlayerLaunched()
                        } catch (_: ActivityNotFoundException) {
                            container.playback.onLaunchFailed()
                            failed(UiError.NO_PLAYER)
                        } catch (_: PlaybackManager.CredentialsNotShared) {
                            failed(UiError.CREDENTIALS)
                        }
                    }
                }
                .onFailure {
                    Log.w("Playback", "Playback failed", it)
                    failed(it.toUiError())
                }
        }
    }

    /** The lights come back up, and the detail page says why nothing plays. */
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
    if (!settings.loaded) return

    val scope = rememberCoroutineScope()
    val start: Any = remember { if (settings.source != null) Route.Home else Route.Welcome }

    fun openItem(id: String) = nav.navigate(Route.Item(id))
    fun openLibrary(library: Library) = nav.navigate(Route.Library(library.id, library.kind, library.title))
    fun goHome() = nav.navigate(Route.Home) { popUpTo(0) { inclusive = true } }

    /** "My files": the folders added before are kept across sources, else the first one is added. */
    fun useFolders() {
        scope.launch {
            if (!app.library.hasFolders()) {
                nav.navigate(Route.AddFolder)
            } else {
                app.sources.switchTo(SourceConfig.Local)
                goHome()
            }
        }
    }

    NavHost(nav, startDestination = start) {
        composable<Route.Welcome> {
            WelcomeScreen(onLocal = ::useFolders, onJellyfin = { nav.navigate(Route.JellyfinLogin) })
        }
        composable<Route.JellyfinLogin> { JellyfinLoginScreen(onLoggedIn = ::goHome) }
        composable<Route.AddFolder> {
            AddFolderScreen(onAdded = { switched ->
                // A new source or a first folder: the home screen; another folder: back to settings.
                if (switched || !nav.popBackStack<Route.Settings>(inclusive = false)) goHome()
            })
        }
        composable<Route.Home> {
            HomeScreen(
                onOpenItem = { openItem(it.id) },
                onOpenLibrary = ::openLibrary,
                onOpenSettings = { nav.navigate(Route.Settings) },
                onAddFolder = { nav.navigate(Route.AddFolder) },
            )
        }
        composable<Route.Library> { entry ->
            val route = entry.toRoute<Route.Library>()
            val library = Library(id = route.id, kind = route.kind, title = route.title?.ifBlank { null })
            LibraryScreen(
                library = library,
                title = library.title ?: app.libraryTitle(library.kind),
                onOpenItem = { openItem(it.id) },
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
        composable<Route.Settings> {
            // Changing the source leaves the settings below: Back returns to them, the source intact.
            SettingsScreen(
                onAddFolder = { nav.navigate(Route.AddFolder) },
                onUseFolders = ::useFolders,
                onSignIn = { nav.navigate(Route.JellyfinLogin) },
                onOpenCatalog = { nav.navigate(Route.Catalog) },
            )
        }
        // Debug builds only: every component in its states, for design review.
        if (BuildConfig.DEBUG) composable<Route.Catalog> { CatalogScreen() }
    }
}

/** What fixes an error, when it is not trying again (each screen does that itself): where it is fixed. */
private fun NavHostController.fixError(action: ErrorAction) = when (action) {
    ErrorAction.RECONNECT -> navigate(Route.JellyfinLogin)
    ErrorAction.ADD_TMDB_KEY, ErrorAction.EDIT_TMDB_KEY -> navigate(Route.Settings)
    ErrorAction.RETRY -> Unit
}

/** Coming back to the app looks for new files, unless a scan ended this recently. */
private const val RESCAN_AFTER_MS = 2 * 60_000L

/** While the app is on screen, it looks for new files this often. */
private const val RESCAN_EVERY_MS = 10 * 60_000L
