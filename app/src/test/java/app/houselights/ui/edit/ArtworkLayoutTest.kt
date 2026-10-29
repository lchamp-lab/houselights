package app.houselights.ui.edit

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.ArtworkOrigin
import app.houselights.core.ImageRef
import app.houselights.settings.InterfaceLanguage
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.textRoom
import app.houselights.ui.detail.Speaking
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.pictureServer
import app.houselights.ui.testing.pressOnRemote
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.abs

/**
 * DESIGN.md, Layout: the contact sheet's composition, at the standard text size and the largest. Six posters a line
 * beside the print, five backdrops and four logos across the band; one left edge; the line with focus always at the
 * sheet's top, level with the print, its frame inside the safe area's top and foot and its picture across it, its words
 * inside it; the preview whole above the band; « Automatique » on one line in the twelve languages. Measured in the
 * app's own font.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkLayoutTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val provider = FakeProvider().apply { items["m1"] = film("m1", TITLE).copy(year = YEAR) }

    /** The language the screen speaks, null the device's. */
    private var language by mutableStateOf<String?>(null)

    /** The sheet shown, of a kind at a text size; none before the first. */
    private var shown by mutableStateOf<Shown?>(null)

    private data class Shown(val kind: ArtworkKind, val step: Float)

    /** A section's name's room at the text size in use, once a sheet shows. */
    private var nameRoom = HlSpace.none

    @Before
    fun serve() {
        // Every picture comes back at once, so no choice is set in type.
        Coil.setImageLoader(ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components { add(pictureServer()) }.build())
        compose.setContent {
            // A sheet a kind and a text size, as the app opens it again: its own view model, its own composition.
            shown?.let { sheet ->
                key(sheet) {
                    HouselightsTheme(sheet.step) {
                        val room = textRoom(Hl.type.rowTitle)
                        SideEffect { nameRoom = room }
                        val vm = remember { ArtworkViewModel("m1", sheet.kind, { provider }, work) }
                        Speaking(language) { ArtworkScreen("m1", sheet.kind, onDone = {}, vm = vm) }
                    }
                }
            }
        }
    }

    @After
    fun stop() {
        work.cancel()
        Coil.reset()
    }

    private fun online(value: String, language: String?) =
        ArtworkOption(value, ImageRef(value) { "test://$value?w=$it" }, ArtworkOrigin.ONLINE, language)

    private val automatic = online("auto", "fr")

    /** Three languages of nine, those without text first for a backdrop. */
    private fun options(kind: ArtworkKind): List<ArtworkOption> {
        val languages = if (kind == ArtworkKind.BACKDROP) listOf(null, "fr", "en") else listOf("fr", "en", null)
        return listOf(automatic) + languages.flatMap { language -> (0 until PER_LANGUAGE).map { online("$language-$it", language) } }
    }

    private fun show(kind: ArtworkKind, step: Float = 1f) {
        provider.choices = ArtworkChoices(options(kind), automatic, automatic, onlineAvailable = true)
        shown = Shown(kind, step)
        compose.waitForIdle()
    }

    private fun down() {
        compose.pressOnRemote(Key.DirectionDown)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        compose.waitForIdle()
    }

    private fun focused(): DpRect = compose.onNode(isFocused()).getUnclippedBoundsInRoot()

    /** The choices of the line with focus: those at its top. */
    private fun line(): List<DpRect> {
        val top = focused().top
        return compose.onAllNodes(isFocusable()).fetchSemanticsNodes().map { it.unclipped() }.filter { abs((it.top - top).value) <= HALF }
    }

    /** Where this node lies on the canvas, a dp a pixel, whatever clips it. */
    private fun SemanticsNode.unclipped(): DpRect {
        val at = positionInRoot
        return DpRect(at.x.dp, at.y.dp, (at.x + size.width).dp, (at.y + size.height).dp)
    }

    private fun text(text: String): List<DpRect> =
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().map { it.unclipped() }

    @Test
    fun sixPostersFiveBackdropsAndFourLogosALine() {
        COLUMNS.forEach { (kind, count) ->
            show(kind)
            down()
            val cards = line().sortedBy { it.left }
            assertEquals("$kind: a full line", count, cards.size)
            val pitch = cards[1].left - cards[0].left
            assertTrue("$kind: evenly", cards.zipWithNext().all { (a, b) -> abs((b.left - a.left - pitch).value) <= HALF })
            assertEquals("$kind: a gutter apart", HlLayout.choiceGutter.value, (cards[1].left - cards[0].right).value, HALF)
        }
    }

    @Test
    fun aBandHasOneLeftEdge() {
        listOf(ArtworkKind.BACKDROP, ArtworkKind.LOGO).forEach { kind ->
            show(kind)
            val title = spoken(null, if (kind == ArtworkKind.BACKDROP) R.string.artwork_backdrop else R.string.artwork_logo)
            assertEquals("$kind: the screen's title", EDGE, text(title).single().left.value, HALF)
            assertEquals("$kind: the preview", EDGE, preview().left.value, HALF)
            assertEquals("$kind: Automatique", EDGE, focused().left.value, HALF)
            down()
            assertEquals("$kind: the held name", EDGE, heldName(sectionNames(kind).first()).left.value, HALF)
            assertEquals("$kind: a line", EDGE, line().minOf { it.left.value }, HALF)
        }
    }

    @Test
    fun aPosterSheetStandsBesideThePrint() {
        show(ArtworkKind.POSTER)
        assertEquals("the screen's title", EDGE, text(spoken(null, R.string.artwork_poster)).single().left.value, HALF)
        val sheet = HlSpace.safeH + HlLayout.choicePrint + HlLayout.choicePrintGap
        assertEquals("Automatique, the sheet's first column", sheet.value, focused().left.value, HALF)
        down()
        val cards = line()
        assertEquals("the line's first column", sheet.value, cards.minOf { it.left.value }, HALF)
        assertTrue(
            "the last inside the safe area, 4 short of its end",
            cards.maxOf { it.right.value } <= (CANVAS - HlSpace.safeH).value - 4f + HALF,
        )
    }

    @Test
    fun theLineWithFocusComesToTheTopInsideTheSafeAreaAtTheDefaultTextSize() = everyLineAtTheTop(1f)

    @Test
    fun theLineWithFocusComesToTheTopInsideTheSafeAreaAtTheLargestTextSize() = everyLineAtTheTop(LARGEST_TEXT)

    /**
     * From the head to the last line, each kind: the line with focus at the same place, a poster's level with the print
     * under the head's words, its frame inside the safe area's top and foot, its picture across it, its section's name
     * held over it.
     */
    private fun everyLineAtTheTop(step: Float) {
        ArtworkKind.entries.forEach { kind ->
            show(kind, step)
            val top = focused().top
            if (kind == ArtworkKind.POSTER) {
                // The print stands the wall's room under the head's words, as the line with focus does.
                val head = text(TITLE).single().bottom
                assertEquals("$kind at $step: level with the print", (head + HlFocus.wallRoom).value, top.value, HALF)
            }
            val words = text(spoken(null, R.string.artwork_automatic)).filter { abs((it.left - focused().left).value) <= HALF }
            assertTrue("$kind at $step: Automatique's words inside the safe area", words.single().bottom <= SAFE_FOOT + HALF.dp)
            val names = sectionNames(kind)
            val perSection = (PER_LANGUAGE + COLUMNS.getValue(kind) - 1) / COLUMNS.getValue(kind)
            repeat(1 + names.size * perSection) { line ->
                val at = "$kind at $step, line ${line + 1}"
                val box = focused()
                assertEquals("$at: at the top", top.value, box.top.value, HALF)
                assertTrue("$at: its frame under the safe area's top", box.top - HlFocus.reach >= HlSpace.safeV - HALF.dp)
                assertTrue("$at: its frame over the safe area's foot", box.bottom + HlFocus.reach <= SAFE_FOOT + HALF.dp)
                assertTrue(
                    "$at: across the safe area",
                    box.left >= HlSpace.safeH - HALF.dp && box.right <= CANVAS - HlSpace.safeH + HALF.dp,
                )
                if (line > 0) {
                    val held = heldName(names[(line - 1) / perSection])
                    assertEquals("$at: its section's name held over it", (top - HlLayout.choiceNameGap).value, held.bottom.value, HALF)
                }
                down()
            }
        }
    }

    @Test
    fun thePreviewStaysWholeAboveTheBand() {
        listOf(1f, LARGEST_TEXT).forEach { step ->
            listOf(ArtworkKind.BACKDROP, ArtworkKind.LOGO).forEach { kind ->
                show(kind, step)
                val band = focused().top - HlFocus.wallRoom - nameRoom
                val shown = preview()
                assertTrue("$kind at $step: the preview, $shown, above the band at $band", shown.bottom <= band + HALF.dp)
                assertTrue("$kind at $step: under the screen's title", shown.top > HlSpace.safeV)
                if (kind == ArtworkKind.BACKDROP) {
                    val facts = text(YEAR.toString()).single()
                    assertTrue("$kind at $step: its facts above the band", facts.bottom <= band + HALF.dp)
                }
            }
        }
    }

    /** « Automatique » under its picture, across the next column, and under the print: on one line, whole, in each language. */
    @Test
    fun automatiqueHoldsOnOneLineInTheTwelveLanguagesAtTheLargestTextSize() {
        val device = Locale.getDefault()
        val faults = try {
            listOf(ArtworkKind.POSTER, ArtworkKind.BACKDROP, ArtworkKind.LOGO).flatMap { kind ->
                show(kind, LARGEST_TEXT)
                InterfaceLanguage.LANGUAGES.flatMap { tag ->
                    Locale.setDefault(Locale.forLanguageTag(tag))
                    compose.runOnIdle { language = tag }
                    compose.waitForIdle()
                    val word = spoken(tag, R.string.artwork_automatic)
                    val nodes = compose.onAllNodes(hasText(word), useUnmergedTree = true).fetchSemanticsNodes()
                    listOfNotNull("$kind, $tag: « $word » not shown".takeIf { nodes.isEmpty() }) +
                        nodes.mapNotNull { node -> cut(layoutOf(node))?.let { "$kind, $tag: « $word » $it" } }
                }
            }
        } finally {
            Locale.setDefault(device)
            compose.runOnIdle { language = null }
        }
        assertEquals(emptyList<String>(), faults)
    }

    /** What is wrong with [layout]: more than one line, or cut. */
    private fun cut(layout: TextLayoutResult): String? = when {
        layout.lineCount > 1 -> "on ${layout.lineCount} lines"
        layout.hasVisualOverflow || layout.isLineEllipsized(0) -> "cut"
        else -> null
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    /** The title previewed over the band: its logo with focus, or its name set in type. */
    private fun preview(): DpRect = (
        compose.onAllNodes(hasContentDescription(TITLE)).fetchSemanticsNodes() +
            compose.onAllNodes(hasText(TITLE), useUnmergedTree = true).fetchSemanticsNodes()
        ).single().unclipped()

    /** The names of the sections of [options] after the head, in order, in English. */
    private fun sectionNames(kind: ArtworkKind): List<String> {
        val none = spoken(null, if (kind == ArtworkKind.LOGO) R.string.artwork_any_language else R.string.artwork_no_text)
        return if (kind == ArtworkKind.BACKDROP) listOf(none, "French", "English") else listOf("French", "English", none)
    }

    /** The section's name held over the line with focus: the one called [name] just over it. */
    private fun heldName(name: String): DpRect {
        val over = focused().top - HlLayout.choiceNameGap
        return text(name).minBy { abs((it.bottom - over).value) }
    }

    /** [id] as the screen says it in [tag], null the device's language. */
    private fun spoken(tag: String?, id: Int): String {
        val configuration = Configuration(context.resources.configuration).apply { tag?.let { setLocale(Locale.forLanguageTag(it)) } }
        return context.createConfigurationContext(configuration).getString(id)
    }

    private companion object {
        const val TITLE = "Heat"
        const val YEAR = 1995
        const val PER_LANGUAGE = 9
        const val LARGEST_TEXT = 1.3f
        const val HALF = 0.5f
        const val FRAME_MS = 32L

        /** The choices a line holds, by kind. */
        val COLUMNS = mapOf(ArtworkKind.POSTER to 6, ArtworkKind.BACKDROP to 5, ArtworkKind.LOGO to 4)
        val CANVAS = 960.dp
        val SAFE_FOOT = 540.dp - HlSpace.safeV
        val EDGE = HlSpace.safeH.value
    }
}
