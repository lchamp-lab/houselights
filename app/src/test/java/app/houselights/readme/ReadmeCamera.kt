package app.houselights.readme

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.houselights.AppContainer
import app.houselights.HouselightsApp
import app.houselights.core.LibraryKind
import app.houselights.core.SourceConfig
import app.houselights.core.TrackPreferences
import app.houselights.core.Typeset
import app.houselights.player.PlayerStatus
import app.houselights.player.ZidooPlayer
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.components.BackdropRelay
import app.houselights.ui.design.components.CutPosterCache
import app.houselights.ui.design.components.FIELD_POSTERS
import app.houselights.ui.design.components.HouseLights
import app.houselights.ui.design.components.LeavingBackdrop
import app.houselights.ui.design.components.LocalBackdropRelay
import app.houselights.ui.design.components.LocalCutPosters
import app.houselights.ui.detail.DetailScreen
import app.houselights.ui.detail.DetailViewModel
import app.houselights.ui.edit.IdentifyScreen
import app.houselights.ui.edit.IdentifyViewModel
import app.houselights.ui.edit.ToIdentifyScreen
import app.houselights.ui.edit.ToIdentifyViewModel
import app.houselights.ui.home.HomeActions
import app.houselights.ui.home.HomeScreen
import app.houselights.ui.home.HomeViewModel
import app.houselights.ui.home.LibraryRows
import app.houselights.ui.home.UnseenPick
import app.houselights.ui.home.WatchRow
import app.houselights.ui.home.WatchScreen
import app.houselights.ui.library.LibraryScreen
import app.houselights.ui.library.LibraryViewModel
import app.houselights.ui.settings.AboutViewModel
import app.houselights.ui.settings.NumbersViewModel
import app.houselights.ui.settings.SettingsScreen
import app.houselights.ui.settings.SettingsViewModel
import app.houselights.ui.settings.TraktViewModel
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeTraktLink
import app.houselights.ui.testing.FakeTraktSync
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.press
import app.houselights.ui.util.PlaybackOutcome
import app.houselights.ui.util.RoomPosters
import app.houselights.ui.util.libraryName
import app.houselights.update.FakeApp
import app.houselights.update.FakeArchive
import app.houselights.update.FakeDownloads
import app.houselights.update.FakeInstaller
import app.houselights.update.FakeSource
import app.houselights.update.PINNED
import app.houselights.update.ReleaseChoice
import app.houselights.update.UpdateMoments
import app.houselights.update.UpdateOffer
import app.houselights.update.UpdateParts
import app.houselights.update.UpdateState
import app.houselights.update.UpdateStore
import app.houselights.update.UpdateVerifier
import app.houselights.update.Updater
import app.houselights.update.downloads
import coil.Coil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.Locale
import java.util.Properties
import kotlin.math.roundToInt

/**
 * The TV the README's images are shot on, as Robolectric qualifiers: the 960 × 540 dp canvas at density 2, 1920 × 1080
 * px, in English (United States). With `@GraphicsMode(NATIVE)`, so Archivo and the pictures draw as on the device.
 */
const val README_TV = "en-rUS-w960dp-h540dp-land-xhdpi"

/**
 * The Android the README's images are shot on: 13, where a picture's shader inside another sets its own filtering, as
 * the walls' diffuse light does (Diffuse.kt). Robolectric draws with Android 14's Skia whatever the version, which on
 * 9 would enlarge that light without filtering, as the Zidoo's own renderer never does.
 */
const val README_SDK = 33

/** Where the README's captures go: `$HOUSELIGHTS_README`. Unset, they are skipped, as in CI's checks. */
internal val README_DIR: File? = System.getenv("HOUSELIGHTS_README")?.takeIf { it.isNotBlank() }?.let(::File)

/**
 * The README's camera: the app's real screens over the README's library ([library], [ReadmeLibrary]), in the frame
 * MainActivity gives them (theme, text size, backdrop relay, house lights), driven by the remote's keys ([press]) and
 * shot whole, a sheet over the screen beneath it ([capture]), or filmed frame by frame on a paused clock ([reel]).
 * The screens find the app's container where they look for it (`appContainer()`), holding what they read, and their
 * view models made as the app's factories make them, from the library. [extra] adds files to the library.
 */
internal class ReadmeCamera(private val extra: ReadmeLibrary.() -> Unit = {}) : TestRule {
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val folder = TemporaryFolder()
    private val network = ReadmeNetwork(CACHE_DIR)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var built: ReadmeLibrary? = null

    /** The library, built at its first use. */
    val library: ReadmeLibrary
        get() = built ?: ReadmeLibrary(folder.newFolder("library"), network, tmdbKey(), extra).also { built = it }

    private var updater: Updater? = null

    /** The house lights: down, the room goes dark as before the player starts; up, it lights again. */
    private val lightsDown = mutableStateOf(false)

    private var reelStart = 0L
    private var frame = 0

    override fun apply(base: Statement, description: Description): Statement {
        val shot = object : Statement() {
            override fun evaluate() {
                // The player as on a Zidoo, which About shows; text from elsewhere typeset as English.
                ShadowBuild.setManufacturer("ZIDOO")
                ShadowBuild.setBrand("ZIDOO")
                ShadowBuild.setModel("Z1000 Pro")
                Typeset.language = Locale.ENGLISH.language
                Coil.setImageLoader(network.loader(compose.activity))
                try {
                    base.evaluate()
                } finally {
                    built?.close()
                    io.cancel()
                }
            }
        }
        return RuleChain.outerRule(Asked).around(folder).around(DeclaredActivity).around(compose).around(SettleMainLooper())
            .apply(shot, description)
    }

    /** Home, as the app opens on it: focus on the case. */
    fun home() {
        keepHome()
        show { HomeScreen(HomeActions(onOpenSearch = {})) }
    }

    /** Home once a version is found: its sheet opens over the grid as soon as home is drawn. */
    fun homeWithAnUpdate() {
        keepHome()
        offer(updates())
        show { HomeScreen(HomeActions(onOpenSearch = {})) }
    }

    /** The page of the film, show or saga [id], as it opens. */
    fun title(id: String) {
        keepPages(listOf(id))
        show { Page(id) }
    }

    /** The films' wall, its sagas among its films, as home's door opens it. */
    fun movies() {
        val lib = library
        val libraries = runBlocking { lib.provider.libraries() }
        val films = libraries.first { it.kind == LibraryKind.MOVIES }
        val sagas = libraries.firstOrNull { it.kind == LibraryKind.COLLECTIONS }
        keep<LibraryViewModel> { LibraryViewModel({ lib.provider }, films, SavedStateHandle(), sagas = sagas) }
        show { LibraryScreen(films, libraryName(films.kind), onOpenItem = {}, sagas = sagas) }
    }

    /** Settings, on Library as they open; another place opens once focus rests on it ([pressUntil], [settle]). */
    fun settings() {
        val lib = library
        keep<SettingsViewModel> {
            val sources = SourceSwitcher({ lib.settings.state.value }, lib.settings::setSource, lib.work, signOut = { _, _ -> })
            SettingsViewModel(
                lib.settings, lib.library, { lib.provider }, lib.work, sources, Coil.imageLoader(compose.activity),
                deviceLocale = MutableStateFlow(Locale.US),
                // A Zidoo takes the tracks asked of it: Playback holds its audio and subtitles.
                asksTracks = true,
            )
        }
        keep<NumbersViewModel> { NumbersViewModel { lib.provider } }
        keep<TraktViewModel> { TraktViewModel(FakeTraktLink(), FakeTraktSync(), lib.work) }
        keep<AboutViewModel> { AboutViewModel(playerStatus = { PlayerStatus.Detected(ZidooPlayer.KNOWN_PACKAGE) }, apiAnswers = { true }) }
        show { SettingsScreen(onFolderForm = { _, _ -> }, onUseFolders = { _, _ -> }, onSignIn = {}, onSourceResumed = {}) }
    }

    /** The audio and subtitles asked of the player for every title, as chosen in Settings › Playback. */
    fun chooseTracks(tracks: TrackPreferences) = runBlocking {
        library.settings.setTracks(tracks)
        library.settings.state.first { it.tracks == tracks }
    }

    /** « To identify », as Settings › Library opens it. */
    fun toIdentify() {
        val lib = library
        keep<ToIdentifyViewModel> { ToIdentifyViewModel({ lib.provider }, lib.work) }
        show { ToIdentifyScreen(onOpen = {}) }
    }

    /** Identify on the title [id], from the list of those to identify. */
    fun identify(id: String) {
        val lib = library
        keep("identify-$id") { IdentifyViewModel(id, { lib.provider }, lib.work, SavedStateHandle(), queue = true) }
        show { IdentifyScreen(id, onDone = {}, queue = true) }
    }

    /** The id of the first title to identify that TMDB has suggestions for, as the list orders them. */
    fun firstToIdentify(): String = runBlocking {
        val first = library.provider.editor.toIdentify().firstOrNull { it.suggestions.isNotEmpty() }
        checkNotNull(first) { "Nothing to identify with suggestions:\n${library.report()}" }.item.id
    }

    /**
     * Home and the pages it opens, as the app's navigation shows them: « To watch » from its doors, a title's page from a
     * card, whose Play dims the room as before the player starts; each screen cut, the backdrop handed across.
     */
    fun journey() {
        val home = keepHome()
        keepPages(library.titleIds())
        show { Journey(home) }
    }

    /** On home, focus on the next episode's door, beside the latest titles': Right to the films, then Up. */
    fun toNextUp() {
        pressUntil(Key.DirectionRight, "Movies")
        pressUntil(Key.DirectionUp, "Next up")
    }

    @Composable
    private fun Journey(home: HomeViewModel) {
        var at by remember { mutableStateOf<Stop>(Stop.Home) }
        when (val stop = at) {
            Stop.Home -> HomeScreen(
                HomeActions(onOpenWatch = { at = Stop.Watch(it) }, onOpenItem = { at = Stop.Page(it.id) }, onOpenSearch = {}),
            )
            is Stop.Watch -> WatchScreen(home, stop.row, onOpenItem = { at = Stop.Page(it.id) })
            is Stop.Page -> Page(stop.id)
        }
    }

    @Composable
    private fun Page(id: String) = DetailScreen(
        id,
        screen = PAGE,
        onPlay = { _, _, _ -> lightsDown.value = true },
        onOpenItem = {},
        onEditArtwork = { _, _ -> },
        onIdentify = {},
    )

    /** Where [Journey] is: home, « To watch » on a row, or a title's page. */
    private sealed interface Stop {
        data object Home : Stop

        data class Watch(val row: WatchRow) : Stop

        data class Page(val id: String) : Stop
    }

    /** Home's view model, made as the app's factory makes it: « To watch » reads the same. */
    private fun keepHome(): HomeViewModel {
        val lib = library
        return keep<HomeViewModel> {
            HomeViewModel(
                { lib.provider },
                lib.library.scan,
                lib.library.folders,
                lib.settings.state,
                scanAll = { lib.library.scan() },
                libraryRows = LibraryRows({ lib.provider }),
                pick = UnseenPick(),
                traktAvailable = true,
                held = lib.library.held,
            )
        }
    }

    /** The pages of [ids], each read at once, as a page opened from a card is. */
    private fun keepPages(ids: List<String>) {
        val lib = library
        ids.forEach { id ->
            keep("detail-$id") {
                DetailViewModel(
                    id, { lib.provider }, FakeEvents(), lib.work,
                    tracksOf = lib::tracksOf, keepTracks = lib.tracks::choose, removeHeldTitle = lib.library::removeHeldTitle,
                )
            }
        }
    }

    /** [content] in the app's frame, as MainActivity sets it, on the app's container; settled. */
    fun show(content: @Composable () -> Unit) {
        val app = readmeApp(library)
        val context = ReadmeContext(compose.activity, app)
        val step = library.settings.state.value.textSize.step
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                HouselightsTheme(textStep = step) {
                    val relay = remember { BackdropRelay() }
                    // One cue for the whole app, as MainActivity's.
                    val lights = animateFloatAsState(
                        if (lightsDown.value) 1f else 0f,
                        tween(HlMotion.DIM, easing = LinearEasing),
                        label = "houselights",
                    )
                    CompositionLocalProvider(
                        LocalHouseLights provides { lights.value },
                        LocalBackdropRelay provides relay,
                        LocalCutPosters provides app.container.cutPosters,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            LeavingBackdrop(relay)
                            content()
                            HouseLights()
                        }
                    }
                }
            }
        }
        settle()
    }

    /** Presses [key] [times] times on the screen, as the remote does, each after the last settles. */
    fun press(key: Key, times: Int = 1) {
        compose.press(key, times)
        if (!compose.mainClock.autoAdvance) return
        settle(ROUNDS_AFTER_KEY)
    }

    /** Presses [key] until focus is on what says [label] (its apostrophes as the app sets them), at most [limit] times. */
    fun pressUntil(key: Key, label: String, limit: Int = 12) = pressUntil(key, listOf(label), limit)

    /** Presses [key] until focus is on what says one of [labels], at most [limit] times. */
    fun pressUntil(key: Key, labels: List<String>, limit: Int = 12) {
        val seen = mutableListOf<List<String>>()
        repeat(limit) { _ ->
            val focused = focusedLabels()
            seen += focused
            if (focused.any { shown -> labels.any { label -> typeset(label) in typeset(shown) } }) return
            press(key)
        }
        fail("Focus never reached ${labels.take(3)}…, only $seen")
    }

    /**
     * A key of the remote ([KeyEvent] code) in the open sheet, sent to its window as Android does: Robolectric draws a
     * sheet's first focus only once a key moved it.
     */
    fun pressInSheet(keyCode: Int) {
        compose.runOnIdle {
            val window = checkNotNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window) { "No sheet shows" }
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { window.decorView.dispatchKeyEvent(KeyEvent(it, keyCode)) }
        }
        settle(ROUNDS_AFTER_KEY)
    }

    /** What the focused elements say (text, then description), in every window. */
    fun focusedLabels(): List<String> = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val described = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        (texts + described).joinToString(" — ")
    }

    fun assertFocusOn(label: String) {
        val focused = focusedLabels()
        if (focused.none { typeset(label) in typeset(it) }) fail("Focus is not on « $label » but on $focused")
    }

    /** [text] with its apostrophes typographic, as the screens show TMDB's texts. */
    private fun typeset(text: String) = text.replace('\'', '’')

    /**
     * Lets the screen settle as time passes on the TV, [PAUSE_MS] a round: the library's reads, its pictures, its motion,
     * what waits for focus to rest (a place of Settings opens its pane once focus has rested on it). A sheet just shown
     * takes the window focus.
     */
    fun settle(rounds: Int = ROUNDS) = repeat(rounds) {
        compose.waitForIdle()
        focusSheet()
        compose.mainClock.advanceTimeBy(PAUSE_MS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PAUSE_MS))
        Thread.sleep(PAUSE_MS)
    }

    /**
     * Shoots the screen as the TV shows it, every window in its order, as [name].png under `stills`, once two draws a
     * settle apart are alike: what ends off the main thread lands first, as a door's figures cut in its posters, ink
     * until then.
     */
    fun capture(name: String) {
        settle()
        save(steadyDraw(), "stills/$name.png", Bitmap.CompressFormat.PNG, LOSSLESS)
    }

    /** The screen drawn once two draws a settle apart are alike, or after [STILL_DRAWS] more. */
    private fun steadyDraw(): Bitmap {
        var shot = draw()
        repeat(STILL_DRAWS) {
            settle()
            val again = draw()
            if (again.sameAs(shot)) return again
            shot = again
        }
        return shot
    }

    /** The room goes dark, its cue run whole: a reel starts on black, then lights it ([lightsUp]). */
    fun lightsDown() {
        lightsDown.value = true
        settle(ROUNDS * 2)
    }

    /** The room lights up again, as on coming back from the player: the cue backwards. */
    fun lightsUp() {
        lightsDown.value = false
    }

    /** Films from now on: the clock paused, each frame [FRAME_MS] after the last, two of the TV's ([roll]). */
    fun reel() {
        compose.mainClock.autoAdvance = false
        reelStart = compose.mainClock.currentTime
        frame = 0
        cues.clear()
    }

    private val cues = mutableListOf<Pair<String, Int>>()

    /**
     * Names the frame the reel is at, where a key pressed next is first seen, in `frames/cues.txt` with the frames'
     * interval: compose.py cuts the animation on these cues rather than on counts of its own.
     */
    fun cue(name: String) {
        cues += name to frame
        val lines = listOf("frame-ms $FRAME_MS") + cues.map { (cue, at) -> "$cue $at" }
        val file = checkNotNull(README_DIR).resolve("frames/cues.txt")
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /**
     * [seconds] of screen time, a frame each [FRAME_MS] of the paused clock, saved losslessly as `frames/NNNN.png`, so
     * that the animation's encoder meets no noise of a first compression. A key pressed between two rolls is seen at
     * the next frame; what the library and the pictures do on their own threads lands before each frame, as it would
     * within a frame on the TV.
     */
    fun roll(seconds: Double) = repeat((seconds * MS_PER_S / FRAME_MS).roundToInt()) {
        val behind = reelStart + frame * FRAME_MS - compose.mainClock.currentTime
        if (behind > 0) {
            compose.mainClock.advanceTimeBy(behind, ignoreFrameDuration = true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(behind))
        }
        compose.waitForIdle()
        focusSheet()
        Thread.sleep(FRAME_PAUSE_MS)
        compose.waitForIdle()
        save(draw(), "frames/${frame.toString().padStart(FRAME_DIGITS, '0')}.png", Bitmap.CompressFormat.PNG, LOSSLESS)
        frame++
    }

    /** What TMDB gave for the library's titles, beside the captures: `library.txt`. */
    fun report() {
        val dir = checkNotNull(README_DIR)
        dir.mkdirs()
        dir.resolve("library.txt").writeText(library.report() + "\n")
    }

    private val focusedSheets = mutableSetOf<android.app.Dialog>()

    /**
     * Gives the window focus to a sheet just shown, as Android's window manager does to the window on top, and takes
     * it from the screen beneath: Robolectric gives none to a dialog, so the focus its sheet asks for at once would
     * not be drawn until a first key.
     */
    private fun focusSheet() {
        val sheet = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing && it !in focusedSheets } ?: return
        focusedSheets += sheet
        compose.runOnUiThread {
            windowFocus(compose.activity.window.decorView, false)
            windowFocus(checkNotNull(sheet.window).decorView, true)
        }
        compose.waitForIdle()
    }

    private fun windowFocus(root: View, focused: Boolean) {
        val viewRoot = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(root)
        // Android 9 also takes the touch mode, which Android 13 reads elsewhere.
        val change = viewRoot.javaClass.declaredMethods.first { it.name == "windowFocusChanged" }.apply { isAccessible = true }
        if (change.parameterCount == 2) change.invoke(viewRoot, focused, false) else change.invoke(viewRoot, focused)
    }

    /** The screen as the TV shows it, 1920 × 1080: every window in its order, a sheet or a menu over the screen beneath. */
    private fun draw(): Bitmap = compose.runOnUiThread {
        val decor = compose.activity.window.decorView
        check(decor.width == WIDTH && decor.height == HEIGHT) { "The window is ${decor.width} × ${decor.height}" }
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(HouselightsColors.background.toArgb())
        windows().filter { it.isShown && it.width > 0 }.forEach { root ->
            val params = root.layoutParams as? WindowManager.LayoutParams
            if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 && params.dimAmount > 0f) {
                canvas.drawColor(Color.argb((params.dimAmount * OPAQUE).toInt(), 0, 0, 0))
            }
            val at = IntArray(2).also(root::getLocationOnScreen)
            canvas.save()
            canvas.translate(at[0].toFloat(), at[1].toFloat())
            root.draw(canvas)
            canvas.restore()
        }
        bitmap
    }

    private fun save(shot: Bitmap, name: String, format: Bitmap.CompressFormat, quality: Int) {
        val file = checkNotNull(README_DIR).resolve(name)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { check(shot.compress(format, quality, it)) { "$name not written" } }
    }

    /** Every window's root view, the activity's first, as the window manager stacks them. */
    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val manager = global.getMethod("getInstance").invoke(null)
        return (global.getDeclaredField("mViews").apply { isAccessible = true }.get(manager) as List<View>).toList()
    }

    /** Puts the view model [make] makes where the screen's `viewModel()` finds it, under [key] (null: the class's own). */
    private inline fun <reified T : ViewModel> keep(key: String? = null, crossinline make: () -> T): T {
        val factory = viewModelFactory { initializer { make() } }
        val provider = ViewModelProvider(compose.activity.viewModelStore, factory)
        return if (key == null) provider[T::class.java] else provider[key, T::class.java]
    }

    /** The app's updater, never run: GitHub, the downloads and Android's installer as the updater's tests fake them. */
    private fun updates(): Updater = updater ?: Updater(
        library.work,
        library.settings.state,
        UpdateStore(PreferenceDataStoreFactory.create(scope = io) { folder.root.resolve("updates.preferences_pb") }),
        FakeApp(name = ReadmeTitles.INSTALLED),
        UpdateParts(
            FakeSource(),
            ReleaseChoice(::downloads, PINNED, Build.VERSION_CODES.P),
            FakeDownloads(folder.root.resolve("updates")),
            UpdateVerifier(FakeArchive(null), PINNED),
            FakeInstaller(),
        ),
        UpdateMoments(MutableStateFlow(true), MutableStateFlow(false), MutableStateFlow(false), launched = {}),
    ).also { updater = it }

    /** The updater as once GitHub answered with [ReadmeTitles.OFFERED]: home opens its sheet. */
    private fun offer(updates: Updater) {
        val field = Updater::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(updates) as MutableStateFlow<UpdateState>
        val name = ReadmeTitles.OFFERED
        val offer = UpdateOffer(
            tag = "v$name",
            versionName = name,
            versionCode = OFFERED_CODE,
            prerelease = false,
            size = APK_BYTES,
            sha256 = "e".repeat(SHA256_DIGITS),
            url = downloads("v$name") + "Houselights-$name.apk",
            notes = mapOf("en" to ReadmeTitles.OFFERED_NOTES),
        )
        state.value = UpdateState(on = true, installed = ReadmeTitles.INSTALLED, checkedAt = library.now, offer = offer)
    }

    /** The app as the screens find it: never started, its container holding what they read, from [lib]. */
    private fun readmeApp(lib: ReadmeLibrary): HouselightsApp {
        val app = HouselightsApp()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(app, compose.activity.applicationContext)
        val container = allocate(AppContainer::class.java)
        container.put("scope", lib.work)
        container.put("settings", lib.settings)
        container.put("playback", PlaybackOutcome(MutableSharedFlow()))
        container.put("libraryVersion", MutableStateFlow(0L))
        container.put("settledVersion", MutableStateFlow(0L))
        container.put("library", lib.library)
        container.put("cached", SourceConfig.Local to lib.provider)
        container.put("cutPosters", CutPosterCache())
        container.put("unseenPick", UnseenPick())
        container.put("tracks", lib.tracks)
        container.put("updates", updates())
        container.put(
            "roomPosters",
            RoomPosters(
                source = { SourceConfig.Local },
                latest = { lib.library.latestPosters(FIELD_POSTERS).first() },
                hasFolders = { true },
                trending = { emptyList() },
            ),
        )
        app.put("container", container)
        return app
    }

    /** The captures run only when asked where to go ([README_DIR]): CI's checks skip them. */
    private object Asked : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                assumeTrue("HOUSELIGHTS_README names the folder the README's captures go to", README_DIR != null)
                base.evaluate()
            }
        }
    }

    /**
     * The activity the screens are set in, declared to the package manager: the release's manifest has none (Compose's
     * test manifest is the debug build's), and the captures are shot on the release, as users have it.
     */
    private object DeclaredActivity : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }

    /** A screen's context whose application is [app], where `appContainer()` looks. */
    private class ReadmeContext(base: Context, private val app: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = app
    }

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val LOSSLESS = 100
        const val OPAQUE = 255
        const val ROUNDS = 40
        const val ROUNDS_AFTER_KEY = 8

        /** The draws a still waits for the screen to stay as it is. */
        const val STILL_DRAWS = 6
        const val PAUSE_MS = 25L
        const val PAGE = "page"

        /** Two of the TV's frames, 31.25 a second: the clock's frames (16 ms) fall evenly into the reel's. */
        const val FRAME_MS = 32L
        const val MS_PER_S = 1000
        const val FRAME_PAUSE_MS = 10L
        const val FRAME_DIGITS = 4

        const val OFFERED_CODE = 1010L
        const val APK_BYTES = 6_444_633L
        const val SHA256_DIGITS = 64

        /** Where TMDB's answers and pictures are kept between shots: `$HOUSELIGHTS_README_CACHE`, else the system's temp. */
        val CACHE_DIR: File = System.getenv("HOUSELIGHTS_README_CACHE")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("java.io.tmpdir"), "houselights-readme")

        /** TMDB's key, as the build reads it: `$TMDB_API_KEY`, else local.properties' `tmdb.apiKey`; never written. */
        fun tmdbKey(): String {
            System.getenv("TMDB_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val local = File("../local.properties").takeIf { it.exists() }?.let { file ->
                Properties().apply { file.inputStream().use(::load) }
            }
            return checkNotNull(local?.getProperty("tmdb.apiKey")?.takeIf { it.isNotBlank() }) { "The README's captures need a TMDB key" }
        }

        /** An instance of [type] whose constructor never ran: its fields are set one by one ([put]). */
        fun <T> allocate(type: Class<T>): T {
            val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            @Suppress("UNCHECKED_CAST")
            return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
        }

        fun Any.put(field: String, value: Any?) {
            var type: Class<*>? = javaClass
            while (type != null) {
                val found = type.declaredFields.firstOrNull { it.name == field }
                if (found != null) {
                    found.isAccessible = true
                    found.set(this, value)
                    return
                }
                type = type.superclass
            }
            error("No field $field in ${javaClass.name}")
        }
    }
}
