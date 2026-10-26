package app.houselights.ui.detail

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.PlayOrigin
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

/**
 * A title held while its file is missing: its page says since when beside its actions, by the ring, offers to remove it
 * alone, and Lire, refused before any try, says it is gone and what to do; removed, the page leaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class DetailHeldTest {
    @get:Rule
    val compose = createComposeRule()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playback = PlaybackOutcome(MutableSharedFlow())

    private val since = System.currentTimeMillis() - 14 * HOUR - 5 * MINUTE
    private val provider = FakeProvider().apply {
        items["m1"] = film("m1", "Heat").copy(missingSince = since)
        items["m2"] = film("m2", "Dune")
    }

    /** Each title removed, and each time the page left. */
    private val removed = mutableListOf<String>()
    private var left = 0

    @After
    fun stop() = work.cancel()

    private fun page(id: String) {
        val vm = DetailViewModel(id, { provider }, FakeEvents(), work, tracksOf = { null }, removeHeldTitle = { removed += it })
        compose.setTvContent {
            DetailPage(
                vm,
                playback,
                screen = PAGE,
                onPlay = { item, _, origin -> playback.refusesHeld(item, origin) },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = {},
                onOpenPerson = {},
                onLeave = { left++ },
            )
        }
        compose.waitForIdle()
    }

    private val removeTile = hasContentDescription("Remove from the library") and hasClickAction()

    @Test
    fun itsPageSaysSinceWhenItsFileIsMissing() {
        page("m1")
        compose.onNodeWithText("Not found on the NAS for 14${NB}h", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(removeTile).assertExists()
    }

    @Test
    fun aTitleThereSaysNothingAndOffersNoRemoval() {
        page("m2")
        compose.onNodeWithText("Not found on the NAS", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(removeTile).assertDoesNotExist()
    }

    /** Lire is not tried: in place of since when, that it is gone and what to do, never "Can't play". */
    @Test
    fun lireSaysItIsGoneWithoutTrying() {
        page("m1")
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText("This title is gone", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Plug the NAS’s disk back in or remove this title.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Can’t play", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Not found on the NAS for 14${NB}h", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(PlayOrigin(PAGE), playback.playbackProblem.value?.origin)
    }

    /** Remove from the library takes the title alone out, and the page closes on the screen it came from. */
    @Test
    fun removedThePageLeaves() {
        page("m1")
        compose.onNode(removeTile).requestFocus()
        compose.press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(listOf("m1"), removed)
        assertEquals(1, left)
    }

    private companion object {
        const val NB = Typography.nbsp
        const val PAGE = "page"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
