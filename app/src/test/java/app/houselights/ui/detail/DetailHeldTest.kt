package app.houselights.ui.detail

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import app.houselights.R
import app.houselights.core.MatchDoubt
import app.houselights.core.UserState
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.episode
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.season
import app.houselights.ui.testing.series
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.text
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
 * alone, and Lire, refused before any try, says it is gone and what to do; removed, the page leaves, for the film's page
 * again when a copy of it is left, and stays while it is there. A show's episode held says so on its row, and its
 * options remove it alone. Held and to identify, a title says it is held, Identifier waiting in Modifier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class DetailHeldTest {
    @get:Rule
    val compose = createComposeRule()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playback = PlaybackOutcome(MutableSharedFlow())

    private val since = System.currentTimeMillis() - 14 * HOUR - 5 * MINUTE

    /** A film held, a film there, a film held and to identify, and a show of two seasons whose first episode alone is held. */
    private val provider = FakeProvider().apply {
        items["m1"] = film("m1", "Heat").copy(missingSince = since)
        items["m2"] = film("m2", "Dune")
        items["m3"] = film("m3", "Wonder Woman").copy(year = 1984, missingSince = since, matchDoubt = MatchDoubt.UNSURE)
        items["show"] = series("show").copy(user = UserState(unplayedCount = 4))
        seasons = mapOf("show" to (1..2).map { season("s$it", it, "show").copy(childCount = 2, user = UserState(unplayedCount = 2)) })
        episodes = (1..2).associate { n ->
            "s$n" to (1..2).map { e -> episode("s${n}e$e", "s$n", e).copy(missingSince = since.takeIf { n == 1 && e == 1 }) }
        }
    }

    /** Each title removed, each time the page left (for which page), each play the house lights dimmed for, each Identifier opened. */
    private val removed = mutableListOf<String>()
    private val left = mutableListOf<String?>()
    private val dimmed = mutableListOf<String>()
    private val identifying = mutableListOf<String>()

    /** What holds a title once it is removed: nothing of it is left by default. */
    private var stays: (String) -> String? = { null }

    @After
    fun stop() = work.cancel()

    private fun page(id: String) {
        val vm = DetailViewModel(
            id,
            { provider },
            FakeEvents(),
            work,
            tracksOf = { null },
            removeHeldTitle = { title ->
                removed += title
                stays(title)
            },
        )
        compose.setTvContent {
            DetailPage(
                vm,
                playback,
                screen = PAGE,
                // As MainActivity plays: the room dims only for a title tried.
                onPlay = { item, _, origin -> playback.tryPlay(item, origin) { dimmed += item.id } },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = { identifying += it },
                onOpenPerson = {},
                onLeave = { left += it },
            )
        }
        compose.waitForIdle()
    }

    private val removeTile = hasContentDescription("Remove from the library") and hasClickAction()

    private fun said(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    @Test
    fun itsPageSaysSinceWhenItsFileIsMissing() {
        page("m1")
        said("Not found on the NAS for 14${NB}h").assertIsDisplayed()
        compose.onNode(removeTile).assertExists()
    }

    @Test
    fun aTitleThereSaysNothingAndOffersNoRemoval() {
        page("m2")
        compose.onNodeWithText("Not found on the NAS", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(removeTile).assertDoesNotExist()
    }

    /** Lire is not tried, the room never dimmed: in place of since when, that it is gone and what to do, never "Can't play". */
    @Test
    fun lireSaysItIsGoneWithoutTrying() {
        page("m1")
        compose.press(Key.DirectionCenter)
        said("This title is gone").assertIsDisplayed()
        said(FIX).assertIsDisplayed()
        said("Can’t play").assertDoesNotExist()
        said("Not found on the NAS for 14${NB}h").assertDoesNotExist()
        assertEquals(PlayOrigin(PAGE), playback.playbackProblem.value?.origin)
        assertEquals(emptyList<String>(), dimmed)
    }

    /**
     * Held and to identify, its file not there and identifying able to wait: its page says since when its file is
     * missing, never "To identify", and Lire that it is gone, untried; its Identifier button waits with "To identify",
     * the removal ending the actions as on any title held, and Modifier opens Identifier, to find the title on TMDB.
     */
    @Test
    fun aTitleHeldAndToIdentifySaysItIsHeldAndOpensIdentifierFromModifier() {
        page("m3")
        said("Not found on the NAS for 14${NB}h").assertIsDisplayed()
        said("To identify").assertDoesNotExist()
        said("Several possible titles").assertDoesNotExist()
        compose.onNode(identify).assertDoesNotExist()
        compose.onNode(removeTile).assertExists()

        compose.press(Key.DirectionCenter)
        said("This title is gone").assertIsDisplayed()
        said("To identify").assertDoesNotExist()
        assertEquals(emptyList<String>(), dimmed)

        compose.onNode(hasContentDescription("Edit") and hasClickAction()).requestFocus()
        compose.press(Key.DirectionCenter)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("Find this title on TMDB") and isFocused()).assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("m3"), identifying)
    }

    private val identify = hasText("Identify") and hasClickAction()

    private fun pressRemove() {
        compose.onNode(removeTile).requestFocus()
        compose.press(Key.DirectionCenter)
        compose.waitForIdle()
    }

    /** Remove from the library takes the title alone out, and the page closes on the screen it came from. */
    @Test
    fun removedThePageLeaves() {
        page("m1")
        pressRemove()
        assertEquals(listOf("m1"), removed)
        assertEquals(listOf<String?>(null), left)
    }

    /** A copy of the film left under another row, its own gone: that copy's page takes this one's place. */
    @Test
    fun aCopyLeftOpensItsPageInstead() {
        stays = { "m9" }
        page("m1")
        pressRemove()
        assertEquals(listOf<String?>("m9"), left)
    }

    /** Its file back meanwhile, or a copy left on its row: the title is still there, so the page stays and reads it again. */
    @Test
    fun aTitleStillThereStaysOnItsPage() {
        stays = { it }
        page("m1")
        val reads = provider.calls.count { it == "item m1" }
        pressRemove()
        assertEquals(listOf("m1"), removed)
        assertEquals(emptyList<String?>(), left)
        assertEquals("read again", reads + 1, provider.calls.count { it == "item m1" })
    }

    private val firstEpisode = hasText("s1e1") and hasClickAction()

    /** From the action row, where the page opens, down past the season track to the first episode. */
    private fun downToTheEpisodes() {
        compose.press(Key.DirectionDown, times = 2)
        compose.onNode(firstEpisode).assertIsFocused()
    }

    /** An episode held, asked from its card: said on its row, gone and what to do, never "Can't play", nothing dimmed. */
    @Test
    fun anEpisodeHeldSaysItIsGoneOnItsRow() {
        page("show")
        downToTheEpisodes()
        compose.press(Key.DirectionCenter)
        said("This title is gone").assertIsDisplayed()
        said(FIX).assertIsDisplayed()
        said("Can’t play").assertDoesNotExist()
        assertEquals(PlayOrigin(PAGE, "s1e1"), playback.playbackProblem.value?.origin)
        assertEquals(emptyList<String>(), dimmed)
    }

    /**
     * The show not held, its episode is: Menu on the episode's card offers to remove it alone, last, as the fix says; the
     * show stays, read again. An episode there offers no such thing.
     */
    @Test
    fun anEpisodeHeldIsRemovedAloneFromItsOptions() {
        stays = { "show" }
        page("show")
        compose.onNode(removeTile).assertDoesNotExist()
        downToTheEpisodes()
        compose.press(Key.DirectionRight)
        compose.onNode(hasText("s1e2") and hasClickAction()).assertIsFocused()
        compose.press(Key.Menu)
        compose.onNode(hasText(REMOVE) and hasClickAction()).assertDoesNotExist()
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)

        compose.press(Key.DirectionLeft)
        compose.onNode(firstEpisode).assertIsFocused()
        compose.press(Key.Menu)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText(REMOVE) and isFocused()).assertExists()
        val reads = provider.calls.count { it == "item show" }
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(listOf("s1e1"), removed)
        assertEquals(emptyList<String?>(), left)
        assertEquals("the show read again", reads + 1, provider.calls.count { it == "item show" })
    }

    private companion object {
        const val NB = Typography.nbsp
        const val PAGE = "page"
        const val REMOVE = "Remove from the library"
        val FIX: String get() = text(R.string.held_gone_fix)
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
