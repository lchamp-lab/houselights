package app.houselights.ui.detail

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.UserState
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.OK_HINT
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Saga page: while an open panel itself has focus, what OK does on it shows in its bottom corner, on the line
 * of its neighbours' years, a hint's line tall: the OK key then its words, « Voir la fiche » on a film held, « Lire le
 * synopsis » on one the library lacks, « Lire le résumé entier » on the saga's own while its overview is cut, nothing
 * where OK does nothing; gone once focus enters the actions, never on a closed panel. It is never focused, and is read
 * after the panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Words measured as drawn: whether a hint has room is a matter of its width.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SagaHintTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Loads at once, on the main thread, as the app's scope does. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun stop() = work.cancel()

    private fun text(id: Int) = context.getString(id)

    private fun page(source: SagaSource = duneSource()) = SagaPage(compose, source, work).also { it.open(duneSaga.id) }

    /** Focus settled where it is, the panel's words come with or without their picture. */
    private fun rest() {
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + HlMotion.HOLD)
        compose.waitForIdle()
    }

    /** The words of the hint shown and placed, if any, once focus rested. */
    private fun hint(): String? = rest().let { hintNow() }

    private fun hintNow(): String? = compose.onAllNodes(hasTestTag(OK_HINT), useUnmergedTree = true).fetchSemanticsNodes()
        .filter { it.layoutInfo.isPlaced }
        .flatMap { node -> node.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() } }
        .map { it.text }
        .singleOrNull { it != text(R.string.key_ok) }

    @Test
    fun aFilmHeldSaysOkOpensItsPageUntilFocusEntersItsActions() {
        page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
        assertEquals(text(R.string.saga_film_page), hint())
        compose.press(Key.DirectionDown)
        assertEquals("gone in its actions", null, hint())
        compose.press(Key.DirectionUp)
        assertEquals(text(R.string.saga_film_page), hint())
    }

    /** In the open panel's bottom corner, a card's inset from its side, on the closed panels' years' line, 20 tall. */
    @Test
    fun theHintStandsInThePanelsCornerOnTheYearsLine() {
        val page = page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
        assertEquals(text(R.string.saga_film_page), hint())
        val panel = compose.onNodeWithContentDescription(context.getString(R.string.saga_card_name, dune.title, 1) + ", 2021")
            .getUnclippedBoundsInRoot()
        val shown = compose.onNode(hasTestTag(OK_HINT), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("at the panel's end, its inset in", (panel.right - HlInset.card).value, shown.right.value, HALF)
        // A closed panel's year: one meta line, a step over the foot.
        assertEquals("on the years' line", (panel.bottom - HlSpace.m).value, shown.bottom.value, HALF)
        assertEquals("a hint's line tall", page.hintRoom.value, shown.height.value, HALF)
    }

    /** Only the open panel with focus says it: the saga's closed spine, its overview cut, says nothing. */
    @Test
    fun aClosedPanelSaysNothing() {
        page(longOverview(next = duneTwo.copy(user = UserState())))
        compose.press(Key.DirectionUp)
        assertEquals(text(R.string.saga_hint_overview), hint())
        compose.press(Key.DirectionRight)
        assertEquals("the saga closed, film 1 open", text(R.string.saga_film_page), hint())
        assertEquals("one hint", 1, compose.onAllNodes(hasTestTag(OK_HINT), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** A film the library lacks without an overview: OK does nothing on it, and nothing says it does. */
    @Test
    fun aFilmOkDoesNothingOnSaysNothing() {
        page(duneSource(third = duneThree.copy(overview = null)))
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight, times = 3)
        assertEquals(null, hint())
    }

    @Test
    fun aFilmTheLibraryLacksSaysOkReadsItsSynopsis() {
        page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight, times = 3)
        assertEquals(text(R.string.saga_hint_synopsis), hint())
    }

    @Test
    fun theSagasPanelSaysOkReadsItsOverviewOnlyWhileCut() {
        page()
        assertEquals("focus on Lire", null, hint())
        compose.press(Key.DirectionUp)
        assertEquals("a short overview, whole", null, hint())
    }

    @Test
    fun theSagasPanelCutSaysOkReadsItsOverviewWhole() {
        page(longOverview())
        compose.press(Key.DirectionUp)
        assertEquals(text(R.string.saga_hint_overview), hint())
        compose.press(Key.DirectionDown)
        assertEquals(null, hint())
    }

    /** Dune, its overview long enough to be cut, film 2 at [next]. */
    private fun longOverview(next: app.houselights.core.MediaItem = duneTwo): SagaSource {
        val long = duneSaga.copy(overview = "La saga de Paul Atréides, de Caladan au désert d’Arrakis. ".repeat(LONG))
        val provider = FakeProvider().apply {
            items[long.id] = long
            items[dune.id] = dune
            items[next.id] = next
            catalog = FakeCatalog().apply { sagas = mapOf(long.id to duneWhole(second = next).copy(collection = long)) }
            capabilities = capabilities.copy(sagaParts = true)
        }
        return SagaSource(provider, long, listOf(dune, next))
    }

    /** The hint is no button and takes no focus; it is read after the panel it concerns. */
    @Test
    fun theHintIsReadAfterThePanelNeverFocused() {
        page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
        assertEquals(text(R.string.saga_film_page), hint())
        val name = context.getString(R.string.saga_card_name, dune.title, 1) + ", 2021"
        val order = readingOrder()
        val panel = order.indexOfFirst { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == name }
        val words = order.indexOfLast { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any {
                it.text == text(R.string.saga_film_page)
            } == true
        }
        assertTrue("the panel, then the hint: $panel, $words", panel in 0 until words)
        val hinted = order[words]
        assertTrue("no click", SemanticsActions.OnClick !in hinted.config)
        assertTrue("not focusable", SemanticsProperties.Focused !in hinted.config && SemanticsActions.RequestFocus !in hinted.config)
        compose.onNode(hasTestTag(OK_HINT), useUnmergedTree = true).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
    }

    /** The page's semantics nodes in reading order: the tree walked depth first, as a screen reader goes. */
    private fun readingOrder(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return nodes
    }

    private companion object {
        /** Sentences enough for two lines and more. */
        const val LONG = 8

        const val HALF = 0.5f
    }
}
