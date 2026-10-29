package app.houselights.ui.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.houselights.R
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkNote
import app.houselights.core.ArtworkOption
import app.houselights.core.ArtworkOrigin
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.pictureServer
import app.houselights.ui.testing.pressOnRemote
import app.houselights.ui.testing.setTvContent
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import kotlin.math.abs

/**
 * DESIGN.md, Layout: the contact sheet through the remote. Focus comes on the picture in use, beside Automatique; Up
 * and Down go from line to line and section to section, the line with focus always at the top, its section's name held
 * over it down to a long section's last line; the print and the backdrop behind follow focus once it settles, the
 * print keeping the picture it showed, and its words, until the next is ready; OK chooses, saves and returns; Back
 * leaves without choosing. Read from the screen as drawn, each picture in its own colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkScreenTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val provider = FakeProvider().apply { items["m1"] = film("m1", "Heat") }

    /** Every picture asked of the server, as its address, at the width asked. */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The address the server holds until [hold] completes; none by default. */
    @Volatile
    private var holding: String? = null
    private val hold = CompletableDeferred<Unit>()

    private var open by mutableStateOf(true)
    private var done = 0

    private fun picture(value: String) = ImageRef(value) { "test://$value?w=$it" }

    private fun online(value: String, language: String?) = ArtworkOption(value, picture(value), ArtworkOrigin.ONLINE, language)

    private val automatic = online("auto", "fr")
    private val french = (0..8).map { online("fr-$it", "fr") }
    private val english = (0 until ENGLISH).map { online("en-$it", "en") }
    private val none = (0 until 3).map { online("none-$it", null) }

    /** The poster in use, one of the French: at the head, beside Automatique, never again among the French. */
    private val inUse = french[2]

    @Before
    fun serve() {
        val server = pictureServer(fillOf = ::colourOf) { data, _ ->
            asked += "$data"
            if ("$data" == holding) hold.await()
        }
        Coil.setImageLoader(
            ImageLoader.Builder(compose.activity)
                .dispatcher(Dispatchers.Unconfined)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .components { add(server) }
                .build(),
        )
    }

    @After
    fun stop() {
        work.cancel()
        Coil.reset()
    }

    /** The one in use blue, Automatique orange, every other picture green. */
    private fun colourOf(data: Any): Int = when ("$data".removePrefix("test://").substringBefore('?')) {
        inUse.value -> BLUE
        automatic.value -> ORANGE
        else -> GREEN
    }

    /** The sheet of [kind], in the app, or recreated by [restoration] as an activity is, its view model kept. */
    private fun show(kind: ArtworkKind = ArtworkKind.POSTER, restoration: StateRestorationTester? = null) {
        val options = listOf(automatic) + if (kind == ArtworkKind.BACKDROP) none + french + english else french + english + none
        provider.choices = ArtworkChoices(options, automatic, inUse, onlineAvailable = true)
        val vm = ArtworkViewModel("m1", kind, { provider }, work)
        val screen = @Composable {
            if (open) {
                // Where Back goes once the screen lets it: the title's page in the app.
                BackHandler { open = false }
                ArtworkScreen("m1", kind, onDone = { done++ }, vm = vm)
            }
        }
        if (restoration == null) compose.setTvContent(content = screen) else restoration.setContent { HouselightsTheme(content = screen) }
        compose.waitForIdle()
    }

    /** Focus moved on the remote, then left to settle. */
    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.pressOnRemote(key)
        rest()
    }

    private fun rest() {
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        compose.waitForIdle()
    }

    private fun focused(): DpRect = compose.onNode(isFocused()).getUnclippedBoundsInRoot()

    /** Where the poster of column [index] stands across: the sheet's start, the print's gap past it. */
    private fun columnAt(index: Int): Dp =
        HlSpace.safeH + HlLayout.choicePrint + HlLayout.choicePrintGap + (HlLayout.posterChoiceCell + HlLayout.choiceGutter) * index

    /** The section names drawn just over the line with focus, where the sheet holds its section's. */
    private fun held(): Set<String> {
        val at = focused().top - HlLayout.choiceNameGap
        return SECTIONS.filter { name ->
            compose.onAllNodes(hasText(name), useUnmergedTree = true).fetchSemanticsNodes().any { node ->
                abs(node.positionInRoot.y + node.size.height - at.value) <= HALF
            }
        }.toSet()
    }

    private fun assertFocusAt(what: String, column: Int, top: Dp) {
        val box = focused()
        assertEquals("$what: its column", columnAt(column).value, box.left.value, HALF)
        assertEquals("$what: at the top, level with the print; ${drawn()}", top.value, box.top.value, HALF)
    }

    /** Where the choices and the sections' names lie, the one with focus starred: what a failure says. */
    private fun drawn(): String {
        val cards = compose.onAllNodes(isFocusable()).fetchSemanticsNodes().map { node ->
            val star = if (node.config.getOrNull(SemanticsProperties.Focused) == true) "*" else ""
            "$star${node.positionInRoot.x.toInt()},${node.positionInRoot.y.toInt()}"
        }
        val names = SECTIONS.flatMap { name ->
            compose.onAllNodes(hasText(name), useUnmergedTree = true).fetchSemanticsNodes().map { "$name@${it.positionInRoot.y.toInt()}" }
        }
        return "choices $cards, names $names"
    }

    @Test
    fun focusComesOnThePictureInUseBesideAutomatique() {
        show()
        compose.onNode(isFocused()).assertIsSelected()
        assertEquals("checked once, at the head alone", 1, compose.onAllNodes(isSelected()).fetchSemanticsNodes().size)
        assertEquals("beside Automatique", columnAt(1).value, focused().left.value, HALF)
        val words = compose.onAllNodes(hasText("Automatic"), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Automatique named under its picture", words.any { abs(it.positionInRoot.x - columnAt(0).value) <= HALF })
        assertEquals("no section's name over the head", emptySet<String>(), held())
    }

    @Test
    fun upAndDownGoFromLineToLineAndSectionToSectionTheLineWithFocusAtTheTop() {
        show()
        val top = focused().top
        press(Key.DirectionDown)
        assertFocusAt("French, its first line", 1, top)
        assertEquals(setOf("French"), held())
        press(Key.DirectionDown)
        assertFocusAt("French, its second line", 1, top)
        assertEquals(setOf("French"), held())
        press(Key.DirectionDown)
        assertFocusAt("English, its first line", 1, top)
        assertEquals(setOf("English"), held())
        press(Key.DirectionUp)
        assertFocusAt("back to French", 1, top)
        assertEquals(setOf("French"), held())
        press(Key.DirectionUp, times = 2)
        assertFocusAt("back to the head", 1, top)
        compose.onNode(isFocused()).assertIsSelected()
        assertEquals(emptySet<String>(), held())
        press(Key.DirectionLeft)
        assertFocusAt("Automatique, along its line", 0, top)
    }

    /**
     * Left and Right go along a line and stop at its ends, never into the line under a short one, whose choices stand
     * further across: the head's two, French's last two over English's six.
     */
    @Test
    fun leftAndRightGoAlongALineNeverIntoTheNext() {
        show()
        val top = focused().top
        press(Key.DirectionRight)
        assertFocusAt("the one in use, the head's last", 1, top)
        compose.onNode(isFocused()).assertIsSelected()
        press(Key.DirectionDown, times = 2)
        assertFocusAt("French's last line, its second", 1, top)
        press(Key.DirectionRight)
        assertFocusAt("still French's last", 1, top)
        assertEquals(setOf("French"), held())
        press(Key.DirectionLeft, times = 2)
        assertFocusAt("its first, where the line starts", 0, top)
        assertEquals(setOf("French"), held())
    }

    @Test
    fun theSectionsNameIsHeldDownToTheLastLineOfALongSection() {
        show()
        val top = focused().top
        press(Key.DirectionDown, times = 3)
        // English's four lines, each saying its language.
        repeat(ENGLISH_LINES) { line ->
            assertFocusAt("English, line ${line + 1}", 1, top)
            assertEquals("English, line ${line + 1}", setOf("English"), held())
            press(Key.DirectionDown)
        }
        assertFocusAt("those without text", 1, top)
        assertEquals(setOf("No text"), held())
        press(Key.DirectionDown)
        assertFocusAt("the last line, come to the top too, nothing under it", 1, top)
    }

    /**
     * A sheet recreated part way down, as its activity is when the text size or the language changes, comes back as it
     * arrives: at its head, focus on the one in use, none of its lines restored without it.
     */
    @Test
    fun aSheetRecreatedComesBackAtItsHeadOnThePictureInUse() {
        val restoration = StateRestorationTester(compose)
        show(restoration = restoration)
        val top = focused().top
        press(Key.DirectionDown, times = 3)
        assertEquals(setOf("English"), held())

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        rest()
        compose.onNode(isFocused()).assertIsSelected()
        assertFocusAt("the one in use, at the head", 1, top)
        assertEquals("no section's name over the head", emptySet<String>(), held())
    }

    @Test
    fun thePrintFollowsTheSettledFocusKeepingThePictureItShowedUntilTheNextIsReady() {
        holding = "test://auto?w=240"
        show()
        val inside = { x: Dp -> at(x, focused().top + PRINT_DEPTH) }
        val print = HlSpace.safeH + HlLayout.choicePrint / 2
        compose.waitUntil(WAIT_MS) { inside(print).isBlue() }
        assertTrue("the print asks the one in use at its width", "test://fr-2?w=240" in asked)

        press(Key.DirectionLeft)
        assertTrue("then Automatique, once focus settled", "test://auto?w=240" in asked)
        assertTrue("the one it showed kept whole until Automatique is ready: ${inside(print).hex()}", inside(print).isBlue())
        assertFalse("its words kept with it: no « Automatique » under the one in use", printSaysAutomatique())

        hold.complete(Unit)
        compose.waitUntil(WAIT_MS) { inside(print).isOrange() }
        compose.waitUntil(WAIT_MS) { printSaysAutomatique() }
    }

    /** Whether « Automatique » is written under the print, along its left edge, the safe area's. */
    private fun printSaysAutomatique(): Boolean = compose.onAllNodes(hasText("Automatic"), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .any { abs(it.positionInRoot.x - HlSpace.safeH.value) <= HALF }

    @Test
    fun aBackdropFillsTheScreenOnceFocusSettles() {
        show(ArtworkKind.BACKDROP)
        compose.onNode(isFocused()).assertIsSelected()
        press(Key.DirectionDown)
        // Those without text first, for a backdrop, in the column of the one in use.
        val shown = none[1]
        compose.waitUntil(WAIT_MS) { asked.any { it.startsWith("test://${shown.value}?") && !it.endsWith("w=$BACKDROP_CELL") } }
    }

    @Test
    fun okChoosesSavesAndReturns() {
        show()
        press(Key.DirectionDown)
        compose.pressOnRemote(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals("the second French, next to the first, the one in use left at the head", french[1], provider.chosen)
        assertEquals("back on the title", 1, done)
    }

    @Test
    fun backLeavesWithoutChoosing() {
        show()
        press(Key.DirectionDown)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("the screen gone", false, open)
        assertNull("nothing chosen", provider.chosen)
        assertTrue(provider.calls.none { it.startsWith("setArtwork") })
        assertEquals(0, done)
    }

    /** A saga TMDB does not know (#48), named by NFO files, says why as a fact in place of TMDB's sections, its films under it. */
    @Test
    fun aSagaTmdbDoesNotKnowSaysWhyInPlaceOfTmdbsSections() = assertSagaSays(ArtworkNote.SAGA_UNKNOWN, R.string.artwork_saga_unknown)

    /** A saga TMDB knows, TMDB out of reach, says the connection alone, in the same place: it is never to identify. */
    @Test
    fun aSagaTmdbCannotListSaysTheConnectionInPlaceOfItsSections() = assertSagaSays(ArtworkNote.SAGA, R.string.artwork_offline_saga)

    /** A saga's poster choice, its films alone, says [words] ([note]) under its head, over its films' section. */
    private fun assertSagaSays(note: ArtworkNote, words: Int) {
        val films = (1..3).map { n ->
            ArtworkOption("film-$n", picture("film-$n"), ArtworkOrigin.FILM, film = "Film $n", filmYear = YEAR + n)
        }
        provider.choices = ArtworkChoices(films, films.first(), films.first(), onlineAvailable = false, note = note)
        val vm = ArtworkViewModel("m1", ArtworkKind.POSTER, { provider }, work)
        compose.setTvContent { ArtworkScreen("m1", ArtworkKind.POSTER, onDone = {}, vm = vm) }
        compose.waitForIdle()
        val head = focused()
        val said = compose.onNode(hasText(compose.activity.getString(words)), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val section = compose.onNode(hasText(compose.activity.getString(R.string.artwork_saga_films)), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("under the head ($head), over its films ($section): $said", head.bottom <= said.top && said.bottom <= section.top)
    }

    /** The colour drawn at [x], [y] on the canvas, the screen drawn whole. */
    private fun at(x: Dp, y: Dp): Int = compose.runOnUiThread {
        val window = compose.activity.window.decorView
        val screen = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888).also { window.draw(Canvas(it)) }
        val density = compose.activity.resources.displayMetrics.density
        screen.getPixel((x.value * density).toInt(), (y.value * density).toInt())
    }

    private fun Int.isBlue() = Color.blue(this) > Color.red(this) + DARK && Color.blue(this) > Color.green(this) + DARK

    private fun Int.isOrange() = Color.red(this) > Color.blue(this) + DARK && Color.red(this) > Color.green(this)

    private fun Int.hex() = "#%08X".format(this)

    private companion object {
        const val ENGLISH = 20

        /** The year before a saga's first film's. */
        const val YEAR = 1960

        /** English's 20 in lines of six. */
        const val ENGLISH_LINES = 4

        /** The section names the sheet holds, in English. */
        val SECTIONS = listOf("French", "English", "No text")

        /** Into the print from its top: well inside its picture. */
        val PRINT_DEPTH = 150.dp
        const val BACKDROP_CELL = 160
        const val HALF = 0.5f
        const val DARK = 48
        const val FRAME_MS = 32L
        const val WAIT_MS = 5_000L
        val BLUE = Color.rgb(32, 64, 224)
        val ORANGE = Color.rgb(232, 120, 24)
        val GREEN = Color.rgb(40, 200, 60)
    }
}
