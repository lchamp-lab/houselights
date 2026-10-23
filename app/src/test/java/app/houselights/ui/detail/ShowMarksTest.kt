package app.houselights.ui.detail

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.UserState
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.episode
import app.houselights.ui.testing.holdOk
import app.houselights.ui.testing.longClickNamed
import app.houselights.ui.testing.press
import app.houselights.ui.testing.season
import app.houselights.ui.testing.series
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.PlaybackOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** A show's page on a fake source, its watched tile and its episodes pressed as with the remote. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class ShowMarksTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Saves at once, on the main thread, as the app's scope does. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Two seasons of two episodes, none watched. */
    private val provider = FakeProvider().apply {
        items["show"] = series("show").copy(user = UserState(unplayedCount = 4))
        seasons = mapOf("show" to (1..2).map { season("s$it", it, "show").copy(childCount = 2, user = UserState(unplayedCount = 2)) })
        episodes = (1..2).associate { n -> "s$n" to (1..2).map { episode("s${n}e$it", "s$n", it) } }
    }
    private val played = mutableListOf<String>()

    @After
    fun stop() = work.cancel()

    private fun showPage() {
        val vm = DetailViewModel("show", { provider }, FakeEvents(), work)
        compose.setTvContent {
            DetailPage(
                vm,
                PlaybackOutcome(MutableSharedFlow()),
                onPlay = { episode, _ -> played += episode.id },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = {},
                onOpenPerson = {},
            )
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = context.getString(id)

    private fun tile(label: Int) = compose.onNodeWithContentDescription(text(label))

    private fun episodeCard(id: String) = compose.onNode(hasText(id) and hasClickAction())

    /** From the action row, where the page opens, down past the season track to the first episode. */
    private fun downToTheEpisodes() {
        compose.press(Key.DirectionDown, times = 2)
        episodeCard("s1e1").assertIsFocused()
    }

    @Test
    fun okMarksTheSeasonShownAndALongOkTheWholeShow() {
        showPage()
        tile(R.string.mark_season_watched).assert(longClickNamed(text(R.string.mark_series_watched))).requestFocus()
        compose.press(Key.DirectionCenter)
        assertTrue("played s1 true" in provider.calls)
        // The season watched, the tile says what OK does now.
        tile(R.string.mark_season_unwatched).assertIsFocused()

        compose.holdOk()
        assertTrue("played show true" in provider.calls)
        tile(R.string.mark_season_unwatched).assert(longClickNamed(text(R.string.mark_series_unwatched)))
    }

    @Test
    fun aLongOkMarksAnEpisodeAndOkPlaysIt() {
        showPage()
        downToTheEpisodes()
        episodeCard("s1e1").assert(longClickNamed(text(R.string.mark_episode_watched)))
        compose.holdOk()
        assertTrue("played s1e1 true" in provider.calls)
        episodeCard("s1e1").assert(longClickNamed(text(R.string.mark_episode_unwatched)))
        assertEquals(emptyList<String>(), played)

        compose.press(Key.DirectionCenter)
        assertEquals(listOf("s1e1"), played)
    }

    /** Remotes without repeats mark with Menu: the episode on its card, the whole show on the tile. */
    @Test
    fun menuMarksAnEpisodeAndTheWholeShow() {
        showPage()
        downToTheEpisodes()
        compose.press(Key.Menu)
        assertTrue("played s1e1 true" in provider.calls)
        assertEquals(emptyList<String>(), played)

        tile(R.string.mark_season_watched).requestFocus()
        compose.press(Key.Menu)
        assertTrue("played show true" in provider.calls)
    }

    @Test
    fun aSourceThatCannotMarkHasNeitherTheTileNorALongOk() {
        provider.capabilities = provider.capabilities.copy(markPlayed = false)
        showPage()
        tile(R.string.mark_season_watched).assertDoesNotExist()
        downToTheEpisodes()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).assertCountEquals(0)
    }

    /** A mark not saved is said on the episode row: held, OK's repeats leave it, and so does an OK elsewhere. */
    @Test
    fun aMarkNotSavedIsSaidOnTheRowUntilAnOkThere() {
        provider.fail = { if (it.startsWith("played")) IOException("Server away") else null }
        showPage()
        downToTheEpisodes()
        compose.holdOk()
        val notSaved = text(R.string.action_not_saved)
        compose.onAllNodesWithText(notSaved).assertCountEquals(1)
        episodeCard("s1e1").assert(longClickNamed(text(R.string.mark_episode_watched)))

        // The season's own tab: OK there chooses it again, and the row keeps its error.
        compose.press(Key.DirectionUp)
        compose.onNode(isSelected() and hasText("Season 1", substring = true)).assertIsFocused()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(notSaved).assertExists()

        compose.press(Key.DirectionDown)
        episodeCard("s1e1").assertIsFocused()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(notSaved).assertDoesNotExist()
        assertEquals(listOf("s1e1"), played)
    }
}
