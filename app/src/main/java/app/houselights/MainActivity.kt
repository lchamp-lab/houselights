package app.houselights

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import app.houselights.core.MediaItem
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.settings.DisplayModes
import app.houselights.settings.outputModes
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.LaunchCue
import app.houselights.ui.design.LaunchScreen
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.design.Scrub
import app.houselights.ui.design.components.BackdropRelay
import app.houselights.ui.design.components.HouseLights
import app.houselights.ui.design.components.LeavingBackdrop
import app.houselights.ui.design.components.LocalBackdropRelay
import app.houselights.ui.design.components.LocalCutPosters
import app.houselights.ui.design.textScaleOf
import app.houselights.ui.home.loadFirstPictures
import app.houselights.ui.settings.PlayerTrustSheet
import app.houselights.ui.settings.TrustAsked
import app.houselights.ui.settings.ZidooTrust
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.PlayOrigin
import app.houselights.ui.util.PlaybackProblem
import app.houselights.ui.util.ProvideLibraryPlace
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.toUiError
import coil.imageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
                // « Faire confiance au nouveau lecteur » opens its sheet over the screen that says the failure (#58).
                val trust = remember { TrustAsked(ZidooTrust(container.players)) }
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
                    LocalErrorFix provides remember(nav, trust) { trust.fixing(nav::fixError) },
                    LocalLaunchScreen provides launchScreen,
                    LocalBackdropRelay provides relay,
                    LocalCutPosters provides container.cutPosters,
                ) {
                    // The window background is already the room (@color/room): no second black under every screen.
                    Box(Modifier.fillMaxSize()) {
                        LeavingBackdrop(relay)
                        ProvideLibraryPlace(container.libraryPlace) { AppNavigation(nav, settings, onPlay = ::play) }
                        HouseLights()
                        PlayerTrustSheet(trust, jellyfin = settings.value.source is SourceConfig.Jellyfin)
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
        @Suppress("DEPRECATION") // defaultDisplay until the window is attached, as Context.getDisplay is Android 11's.
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

/** Returning to the app scans for new files unless a scan ended this recently. */
private const val RESCAN_AFTER_MS = 2 * 60_000L

/** Scan interval while the app is visible. */
private const val RESCAN_EVERY_MS = 10 * 60_000L

/** Fully opaque drawable alpha. */
private const val OPAQUE = 255
