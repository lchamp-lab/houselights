package app.houselights

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.houselights.core.ArtworkKind
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.SourceConfig
import app.houselights.ui.util.appContainer
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.components.HouseLights
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
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
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val container by lazy { appContainer() }

    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        container.playback.onPlayerResult(it.data)
    }

    /** House lights: down while the player starts, back up when the user returns. */
    private val lightsDown = mutableStateOf(false)

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
                Box(Modifier.fillMaxSize().background(HouselightsColors.background)) {
                    AppNavigation(onPlay = ::play)
                    HouseLights(down = lightsDown.value)
                }
            }
        }
    }

    /**
     * Requests 2160p60 output for the interface. The Zidoo player then switches to the
     * mode it needs (native frame rate, HDR...) during playback.
     */
    private fun applyDisplayMode(ui4k: Boolean) {
        @Suppress("DEPRECATION")
        val display: Display = window.decorView.display ?: windowManager.defaultDisplay
        val target = if (ui4k) {
            display.supportedModes
                .filter { it.physicalWidth >= 3840 && it.physicalHeight >= 2160 }
                .minByOrNull { abs(it.refreshRate - 60f) }
                ?.takeIf { abs(it.refreshRate - 60f) < 1f }
        } else null
        Log.i("Display", "Current mode ${display.mode}, target $target")
        window.attributes = window.attributes.apply { preferredDisplayModeId = target?.modeId ?: 0 }
    }

    override fun onResume() {
        super.onResume()
        lightsDown.value = false
    }

    private fun play(item: MediaItem, resume: Boolean) {
        lifecycleScope.launch {
            val s = container.settings.state.value
            lightsDown.value = true
            runCatching { container.provider.playback(item, resume, PlaybackOptions(s.pathMappings, s.forceHttp)) }
                .onSuccess { plan ->
                    // Let the room go dark before the player takes over the screen.
                    delay(HlMotion.DIM.toLong())
                    when (plan) {
                        is PlaybackPlan.Launch -> try {
                            playerLauncher.launch(container.playback.intentFor(plan))
                            container.playback.onPlayerLaunched()
                        } catch (e: ActivityNotFoundException) {
                            lightsDown.value = false
                            toast(getString(R.string.no_player))
                        }
                        is PlaybackPlan.Delegated -> plan.start()
                    }
                }
                .onFailure {
                    lightsDown.value = false
                    toast(getString(R.string.play_error, it.message ?: it.javaClass.simpleName))
                }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

@Composable
private fun AppNavigation(onPlay: (MediaItem, Boolean) -> Unit) {
    val app = appContainer()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    if (!settings.loaded) return

    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val start = remember { if (settings.source != null) "home" else "welcome" }

    fun openItem(id: String) = nav.navigate("item/$id")
    fun openLibrary(library: Library) =
        nav.navigate("library/${library.id}?kind=${library.kind.name}&title=${Uri.encode(library.title ?: "")}")
    fun goHome() = nav.navigate("home") { popUpTo(0) { inclusive = true } }

    NavHost(nav, startDestination = start) {
        composable("welcome") {
            WelcomeScreen(
                onLocal = {
                    // Folders added before are kept when switching sources: reuse them.
                    scope.launch {
                        if (app.database.dao().sources().isEmpty()) {
                            nav.navigate("folder/add")
                        } else {
                            app.settings.setSource(SourceConfig.Local)
                            goHome()
                        }
                    }
                },
                onJellyfin = { nav.navigate("jellyfin/login") },
            )
        }
        composable("jellyfin/login") { JellyfinLoginScreen(onLoggedIn = ::goHome) }
        composable("folder/add") {
            AddFolderScreen(onAdded = {
                // First folder: go to the home screen; otherwise back to settings.
                if (!nav.popBackStack("settings", inclusive = false)) goHome()
            })
        }
        composable("home") {
            HomeScreen(
                onOpenItem = { openItem(it.id) },
                onOpenLibrary = ::openLibrary,
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable(
            "library/{id}?kind={kind}&title={title}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("kind") { type = NavType.StringType; defaultValue = LibraryKind.MIXED.name },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val args = entry.arguments!!
            val library = Library(
                id = args.getString("id")!!,
                kind = LibraryKind.valueOf(args.getString("kind")!!),
                title = args.getString("title")?.ifBlank { null },
            )
            LibraryScreen(
                library = library,
                title = library.title ?: app.libraryTitle(library.kind),
                onOpenItem = { openItem(it.id) },
            )
        }
        composable("item/{id}") { entry ->
            DetailScreen(
                itemId = entry.arguments!!.getString("id")!!,
                onPlay = onPlay,
                onOpenItem = ::openItem,
                onOpenNfo = { nav.navigate("nfo/$it") },
                onEditArtwork = { id, kind -> nav.navigate("artwork/$id/${kind.name}") },
                onIdentify = { nav.navigate("identify/$it") },
            )
        }
        composable("artwork/{id}/{kind}") { entry ->
            val args = entry.arguments!!
            ArtworkScreen(
                itemId = args.getString("id")!!,
                kind = ArtworkKind.valueOf(args.getString("kind")!!),
                onDone = { nav.popBackStack() },
            )
        }
        composable("identify/{id}") { entry ->
            IdentifyScreen(entry.arguments!!.getString("id")!!, onDone = { nav.popBackStack() })
        }
        composable("nfo/{id}") { entry -> NfoScreen(entry.arguments!!.getString("id")!!) }
        composable("settings") {
            SettingsScreen(
                onAddFolder = { nav.navigate("folder/add") },
                onChangeSource = { nav.navigate("welcome") { popUpTo(0) { inclusive = true } } },
            )
        }
    }
}

/** Coming back to the app looks for new files, unless a scan ended this recently. */
private const val RESCAN_AFTER_MS = 2 * 60_000L

/** While the app is on screen, it looks for new files this often. */
private const val RESCAN_EVERY_MS = 10 * 60_000L
