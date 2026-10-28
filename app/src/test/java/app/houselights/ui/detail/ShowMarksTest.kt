package app.houselights.ui.detail

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.TrackPlan
import app.houselights.core.TrackPreferences
import app.houselights.core.UserState
import app.houselights.ui.design.components.MENU_KEY
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.episode
import app.houselights.ui.testing.holdOk
import app.houselights.ui.testing.longClickNamed
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
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

/** A show's page on a fake source, its watched tile and its episodes pressed as with the remote, and their options. */
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

    /** Each episode played, and whether from where it was left. */
    private val played = mutableListOf<Pair<String, Boolean>>()

    /** The tracks of what Lire plays; none by default. */
    private var plan: TrackPlan? = null

    @After
    fun stop() = work.cancel()

    private fun showPage() {
        val vm = DetailViewModel("show", { provider }, FakeEvents(), work, tracksOf = { plan })
        compose.setTvContent {
            DetailPage(
                vm,
                PlaybackOutcome(MutableSharedFlow()),
                screen = "page",
                onPlay = { episode, resume, _ -> played += episode.id to resume },
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

    /** A row of the open sheet, by its name. */
    private fun option(name: String) = compose.onNode(hasText(name) and hasClickAction())

    private fun option(name: Int) = option(text(name))

    /** From the action row, where the page opens, down past the season track to the first episode. */
    private fun downToTheEpisodes() {
        compose.press(Key.DirectionDown, times = 2)
        episodeCard("s1e1").assertIsFocused()
    }

    @Test
    fun okMarksTheSeasonShowAndMenuOpensTheTilesOptions() {
        showPage()
        tile(R.string.mark_season_watched).assert(longClickNamed(text(R.string.more_options))).requestFocus()
        compose.press(Key.DirectionCenter)
        assertTrue("played s1 true" in provider.calls)
        // The season watched, the tile says what OK does now.
        tile(R.string.mark_season_unwatched).assertIsFocused()

        compose.press(Key.Menu)
        // The season shown as it stands, focused, then the whole show.
        option(context.getString(R.string.mark_season_number_unwatched, 1)).assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        option(R.string.mark_series_watched).assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("played show true" in provider.calls)
        option(R.string.mark_series_watched).assertDoesNotExist()
        tile(R.string.mark_season_unwatched).assertIsFocused()
    }

    /** A show of one season has no season track: its tile marks the show, its one season, says so, and has no options. */
    @Test
    fun aShowOfOneSeasonSaysItsTileMarksTheSeason() {
        provider.items["show"] = series("show").copy(user = UserState(unplayedCount = 2))
        provider.seasons = mapOf("show" to listOf(season("s1", 1, "show").copy(childCount = 2, user = UserState(unplayedCount = 2))))
        showPage()
        // Words that read as one episode are gone.
        tile(R.string.mark_watched).assertDoesNotExist()
        tile(R.string.mark_season_watched).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick)).requestFocus()
        compose.press(Key.DirectionCenter)
        assertTrue("played show true" in provider.calls)
        tile(R.string.mark_season_unwatched).assertIsFocused()

        compose.press(Key.Menu)
        option(R.string.mark_series_watched).assertDoesNotExist()
        tile(R.string.mark_season_unwatched).assertIsFocused()
        compose.press(Key.DirectionCenter)
        assertTrue("played show false" in provider.calls)
        tile(R.string.mark_season_watched).assertIsFocused()
    }

    /** Held, OK opens the tile's options and marks nothing; Back closes them. */
    @Test
    fun aLongOkOpensTheTilesOptions() {
        showPage()
        tile(R.string.mark_season_watched).requestFocus()
        compose.holdOk()
        option(context.getString(R.string.mark_season_number_watched, 1)).assertIsFocused()
        assertTrue(provider.calls.none { it.startsWith("played") })

        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        option(R.string.mark_series_watched).assertDoesNotExist()
        tile(R.string.mark_season_watched).assertIsFocused()
    }

    /** Menu then OK marks an episode: its options open on marking it, and close on the card. */
    @Test
    fun menuOnAnEpisodeOpensItsOptionsAndOkMarksIt() {
        showPage()
        downToTheEpisodes()
        episodeCard("s1e1").assert(longClickNamed(text(R.string.more_options)))
        compose.press(Key.Menu)
        compose.onNodeWithText(context.getString(R.string.episode_number, 1)).assertExists()
        option(R.string.mark_watched).assertIsFocused()
        // Not started, no tracks to choose: marking it is all.
        option(R.string.play_from_start).assertDoesNotExist()
        option(R.string.tracks).assertDoesNotExist()

        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("played s1e1 true" in provider.calls)
        option(R.string.mark_watched).assertDoesNotExist()
        episodeCard("s1e1").assertIsFocused()
        assertEquals(emptyList<Pair<String, Boolean>>(), played)

        compose.press(Key.Menu)
        option(R.string.mark_unwatched).assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        episodeCard("s1e1").assertIsFocused()
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("s1e1" to false), played)
    }

    /** A season the source names in its own language is said by its number in the app's, with the episode's. */
    @Test
    fun anEpisodesOptionsSayItsSeasonByItsNumber() {
        provider.seasons = mapOf("show" to listOf(season("s1", 1, "show").copy(title = "Staffel 1", childCount = 2)))
        showPage()
        downToTheEpisodes()
        compose.press(Key.Menu)
        compose.onNodeWithText(context.getString(R.string.season_number, 1)).assertExists()
        compose.onNodeWithText(context.getString(R.string.episode_number, 1)).assertExists()
    }

    /** Specials, season 0, keep the name the source gives them. */
    @Test
    fun aSpecialsOptionsSayTheSourcesName() {
        provider.seasons = mapOf("show" to listOf(season("s0", 0, "show").copy(title = "Specials", childCount = 1)))
        provider.episodes = mapOf("s0" to listOf(episode("s0e1", "s0", 1)))
        showPage()
        val onThePage = compose.onAllNodesWithText("Specials").fetchSemanticsNodes().size
        episodeCard("s0e1").requestFocus()
        compose.press(Key.Menu)
        option(R.string.mark_watched).assertIsFocused()
        compose.onAllNodesWithText("Specials").assertCountEquals(onThePage + 1)
        compose.onNodeWithText(context.getString(R.string.season_number, 0)).assertDoesNotExist()
    }

    /** Held, OK opens an episode's options too, and plays nothing. */
    @Test
    fun aLongOkOpensAnEpisodesOptions() {
        showPage()
        downToTheEpisodes()
        compose.holdOk()
        option(R.string.mark_watched).assertIsFocused()
        assertTrue(provider.calls.none { it.startsWith("played") })
        assertEquals(emptyList<Pair<String, Boolean>>(), played)
    }

    @Test
    fun aStartedEpisodePlaysFromItsStartFromItsOptions() {
        provider.episodes += "s1" to listOf(
            episode("s1e1", "s1", 1).copy(runtimeMs = 3_000_000, user = UserState(positionMs = 600_000)),
            episode("s1e2", "s1", 2),
        )
        showPage()
        downToTheEpisodes()
        compose.press(Key.Menu)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        option(R.string.play_from_start).assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("s1e1" to false), played)
    }

    /** Where tracks are chosen, an episode's options say what Lire plays and open the show's audio and subtitles. */
    @Test
    fun theTracksOptionOpensTheShowsTracks() {
        provider.nextUps = mapOf("show" to "s1e1")
        plan = TrackPlan(TWO_AUDIO, TrackPreferences(), null, "fr", "FR", "local:show")
        showPage()
        downToTheEpisodes()
        compose.press(Key.Menu)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText(text(R.string.tracks)) and hasText(text(R.string.tracks_player)) and hasClickAction()).assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(text(R.string.tracks_for_show)).assertExists()
    }

    /** The Menu key shows under the card with focus alone, and not under a sheet, where focus is out. */
    @Test
    fun theMenuKeyShowsUnderTheFocusedCardOnly() {
        showPage()
        downToTheEpisodes()
        assertKeyUnder("s1e1")
        compose.press(Key.DirectionRight)
        assertKeyUnder("s1e2")

        compose.press(Key.Menu)
        assertEquals(emptyList<Rect>(), keysBelow(episodeCard("s1e2").fetchSemanticsNode().boundsInRoot.top))
    }

    /** Where the keys below [top] are: the episode row's, the tiles above keeping theirs to their labels. */
    private fun keysBelow(top: Float): List<Rect> =
        compose.onAllNodes(hasTestTag(MENU_KEY), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.top >= top }

    /** One key in the episode row, under [id]'s card and within its width. */
    private fun assertKeyUnder(id: String) {
        val card = episodeCard(id).fetchSemanticsNode().boundsInRoot
        val keys = keysBelow(card.top)
        assertEquals("$keys", 1, keys.size)
        val key = keys.single()
        assertTrue("$key under $card", key.top >= card.bottom && key.left >= card.left && key.right <= card.right + 0.5f)
    }

    @Test
    fun aSourceThatCannotMarkHasNeitherTheTileNorOptionsToOffer() {
        provider.capabilities = provider.capabilities.copy(markPlayed = false)
        showPage()
        tile(R.string.mark_season_watched).assertDoesNotExist()
        downToTheEpisodes()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(MENU_KEY), useUnmergedTree = true).assertCountEquals(0)
    }

    /** A mark not saved is said on the episode row, and an OK elsewhere leaves it. */
    @Test
    fun aMarkNotSavedIsSaidOnTheRowUntilAnOkThere() {
        provider.fail = { if (it.startsWith("played")) IOException("Server away") else null }
        showPage()
        downToTheEpisodes()
        compose.press(Key.Menu)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        val notSaved = text(R.string.action_not_saved)
        compose.onAllNodesWithText(notSaved).assertCountEquals(1)
        episodeCard("s1e1").assertIsFocused()

        // The season's own tab: OK there chooses it again, and the row keeps its error.
        compose.press(Key.DirectionUp)
        compose.onNode(isSelected() and hasText("Season 1", substring = true)).assertIsFocused()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(notSaved).assertExists()

        compose.press(Key.DirectionDown)
        episodeCard("s1e1").assertIsFocused()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(notSaved).assertDoesNotExist()
        assertEquals(listOf("s1e1" to false), played)
    }

    private companion object {
        /** Two audio tracks in the file's order: a choice to make. */
        val TWO_AUDIO = MediaInfo(
            audio = listOf(AudioTrack(Codecs.EAC3, 6, "fre", default = true), AudioTrack(Codecs.EAC3, 6, "kor")),
            tracksInOrder = true,
        )
    }
}
