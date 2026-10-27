package app.houselights.ui.detail

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.MatchDoubt
import app.houselights.core.MediaItem
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.PlaybackOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A title to identify says so at the end of its page's actions, by the ring, never ember: a state, not a failure; its
 * Identifier button comes after Lire, and once the title is identified both leave, focus back on Lire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class DetailToIdentifyTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createComposeRule()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val provider = FakeProvider().apply {
        items["ww"] = film("ww", "Wonder Woman").copy(year = 1984, matchDoubt = MatchDoubt.UNSURE)
    }
    private val vm by lazy { DetailViewModel("ww", { provider }, FakeEvents(), work) }
    private val identifying = mutableListOf<String>()

    @After
    fun stop() = work.cancel()

    private fun show() {
        compose.setTvContent {
            DetailPage(
                vm,
                PlaybackOutcome(MutableSharedFlow()),
                screen = "page",
                onPlay = { _, _, _ -> },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = { identifying += it },
                onOpenPerson = {},
            )
        }
        compose.waitForIdle()
    }

    private fun focusIsOn(name: String) = compose.onNode(isFocused()).assert(hasText(name, substring = true))

    /** The title as the source has it now, its page reading it again. */
    private fun nowIs(item: MediaItem) {
        provider.items[item.id] = item
        compose.runOnIdle { vm.onLibraryChanged() }
        compose.waitForIdle()
    }

    @Test
    fun aTitleToIdentifySaysWhyBesideItsActionsAndOffersIdentifier() {
        show()
        focusIsOn("Play")
        compose.onNodeWithText("To identify").assertExists()
        compose.onNodeWithText("Several possible titles").assertExists()
        // Right then OK from Lire.
        compose.press(Key.DirectionRight)
        focusIsOn("Identify")
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("ww"), identifying)
    }

    @Test
    fun aTitleNotFoundSaysSo() {
        provider.items["ww"] = film("ww", "Wonder Woman").copy(matchDoubt = MatchDoubt.NOT_FOUND)
        show()
        compose.onNodeWithText("Not found on TMDB").assertExists()
    }

    @Test
    fun aMatchedTitleKeepsItsPage() {
        provider.items["ww"] = film("ww", "Wonder Woman 1984")
        show()
        compose.onNodeWithText("To identify").assertDoesNotExist()
        compose.onNode(hasText("Identify") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun aTitleIdentifiedFromItsPageComesBackOnLire() {
        show()
        compose.press(Key.DirectionRight)
        focusIsOn("Identify")
        nowIs(film("ww", "Wonder Woman 1984").copy(year = 2020))
        compose.onNodeWithText("To identify").assertDoesNotExist()
        focusIsOn("Play")
    }

    /** A failure takes the slot while it is said. */
    @Test
    fun aFailureTakesTheStatesPlace() {
        provider.fail = { call -> IOException("NAS asleep").takeIf { call.startsWith("played") } }
        show()
        compose.runOnIdle { vm.togglePlayed() }
        compose.waitForIdle()
        compose.onNodeWithText("Not saved").assertExists()
        compose.onNodeWithText("To identify").assertDoesNotExist()
    }
}
