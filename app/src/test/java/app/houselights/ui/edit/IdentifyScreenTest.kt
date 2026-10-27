package app.houselights.ui.edit

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
import app.houselights.core.ToIdentify
import app.houselights.ui.design.HlMotion
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
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

/**
 * Identifier on a title to identify says why, by the panel's ring, in a sentence that follows the reason; it can leave
 * the title as it is (« Laisser tel quel »), which says so when Identifier opens again, with « Rétablir l'automatique ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class IdentifyScreenTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createComposeRule()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val heat = MatchCandidate("949", "Heat", null, 1995, null, null)
    private val provider = FakeProvider().apply {
        items["m1"] = film("m1", "Vacances")
        results = listOf(heat)
    }
    private var done = 0

    @After
    fun stop() = work.cancel()

    private val dune = MatchCandidate("438631", "Dune", null, 2021, "Paul Atréides…", null)
    private val lynch = MatchCandidate("841", "Dune", null, 1984, null, null)
    private val next = mutableListOf<String?>()

    private fun identity(
        doubt: MatchDoubt? = null,
        kept: Boolean = false,
        current: MatchCandidate? = null,
        suggestions: List<MatchCandidate> = emptyList(),
    ) = Identity(
        "Vacances.mkv", "Vacances", 2019, current, manual = false, canSearch = true, doubt = doubt, kept = kept,
        suggestions = suggestions,
    )

    private fun show(identity: Identity, queue: Boolean = false) {
        provider.identity = identity
        val vm = IdentifyViewModel("m1", { provider }, work, queue = queue)
        compose.setTvContent { IdentifyPage(vm, onDone = { done++ }, queue = queue, onNext = { next += it }) }
        compose.waitForIdle()
    }

    private fun focusIsOn(name: String) = compose.onNode(isFocused()).assert(hasText(name, substring = true))

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    @Test
    fun aTitleNotFoundSaysWhyAndCanBeLeftAsItIs() {
        show(identity(MatchDoubt.NOT_FOUND))
        compose.onNodeWithText("Not found on TMDB").assertExists()
        compose.onNodeWithText(
            "Houselights could not recognize this title with certainty. Search for it on TMDB: your choice is kept by later scans.",
        ).assertExists()
        button("Back to automatic").assertDoesNotExist()
        button("Leave as is").requestFocus()
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE)
        compose.press(Key.DirectionCenter)
        assertTrue("keep m1" in provider.calls)
        assertEquals("the screen closes", 1, done)
    }

    @Test
    fun aTitleTmdbHesitatesOnSaysSo() {
        show(identity(MatchDoubt.UNSURE))
        compose.onNodeWithText("Several possible titles").assertExists()
        compose.onNodeWithText(
            "Houselights hesitates between these titles. Choose the right one, or search for it on TMDB: " +
                "your choice is kept by later scans.",
        ).assertExists()
        button("Leave as is").assertExists()
    }

    @Test
    fun aTitleLeftAsItIsSaysSoAndGoesBackToAutomatic() {
        show(identity(kept = true))
        compose.onNodeWithText("Left as is").assertExists()
        button("Leave as is").assertDoesNotExist()
        button("Back to automatic").requestFocus()
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE)
        compose.press(Key.DirectionCenter)
        assertTrue("identify m1 null" in provider.calls)
    }

    @Test
    fun aMatchedTitleIsIdentifiedAsBefore() {
        show(identity(current = heat))
        compose.onNodeWithText("Identified").assertExists()
        compose.onNodeWithText(
            "Search for the exact title on TMDB. Your choice takes precedence over automatic matching and is kept by later scans.",
        ).assertExists()
        button("Leave as is").assertDoesNotExist()
        button("Back to automatic").assertDoesNotExist()
    }

    /** Its suggestions at once, under « Suggestions », the best focused: a deliberate OK accepts it, no search asked. */
    @Test
    fun theSuggestionsShowAtOnceTheBestFocused() {
        show(identity(MatchDoubt.UNSURE, suggestions = listOf(dune, lynch)))
        compose.onNodeWithText("Suggestions").assertExists()
        focusIsOn("2021")
        assertTrue("no search", provider.calls.none { it.startsWith("search") })
        // A deliberate OK, past the calm the suggestions come with.
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE)
        compose.press(Key.DirectionCenter)
        assertTrue("identify m1 438631" in provider.calls)
        assertEquals(1, done)
    }

    /** A search typed replaces the suggestions, under its count. */
    @Test
    fun aSearchReplacesTheSuggestions() {
        show(identity(MatchDoubt.UNSURE, suggestions = listOf(dune, lynch)))
        compose.onNode(hasText("Search") and hasClickAction()).requestFocus()
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE)
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText("Suggestions").assertDoesNotExist()
        compose.onNodeWithText("1${Typography.nbsp}result").assertExists()
        compose.onNodeWithText("Heat").assertExists()
    }

    /** In the queue, « Passer » comes first, nearest the results, Left from a suggestion reaching it. */
    @Test
    fun inTheQueuePasserComesFirstAndOpensTheNextTitle() {
        provider.toIdentify = listOf("m1", "m2").map { ToIdentify(film(it), null, MatchDoubt.UNSURE) }
        show(identity(MatchDoubt.UNSURE, suggestions = listOf(dune, lynch)), queue = true)
        focusIsOn("2021")
        compose.press(Key.DirectionLeft)
        focusIsOn("Skip")
        compose.press(Key.DirectionDown)
        focusIsOn("Leave as is")
        compose.press(Key.DirectionUp)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf<String?>("m2"), next)
        assertTrue(provider.calls.none { it.startsWith("identify") })
    }

    /** From a title's page there is no queue to pass through. */
    @Test
    fun outsideTheQueueThereIsNoPasser() {
        show(identity(MatchDoubt.UNSURE, suggestions = listOf(dune)))
        button("Skip").assertDoesNotExist()
    }
}
