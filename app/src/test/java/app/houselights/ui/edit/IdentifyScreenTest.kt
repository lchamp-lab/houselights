package app.houselights.ui.edit

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
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

    private fun identity(doubt: MatchDoubt? = null, kept: Boolean = false, current: MatchCandidate? = null) =
        Identity("Vacances.mkv", "Vacances", 2019, current, manual = false, canSearch = true, doubt = doubt, kept = kept)

    private fun show(identity: Identity) {
        provider.identity = identity
        val vm = IdentifyViewModel("m1", { provider }, work)
        compose.setTvContent { IdentifyPage(vm, onDone = { done++ }) }
        compose.waitForIdle()
    }

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
}
