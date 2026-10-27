package app.houselights.ui.detail

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.EpisodeOrderChoices
import app.houselights.core.EpisodeOrderKind
import app.houselights.core.EpisodeOrderOption
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.episode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A show's order of episodes, chosen in Modifier: its row under Identifier says the one in use, only for a show TMDB
 * numbers more than one way; OK closes Modifier and opens the choice, Automatique first and the one in use focused;
 * another applies, the episode row saying so meanwhile; the one in use and Back change nothing; a failure is said by
 * the actions, where Modifier was opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class DetailEpisodeOrderTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val provider = FakeProvider().apply {
        items[SHOW] = series(SHOW).copy(title = "Firefly", year = 2002)
        seasons = mapOf(SHOW to listOf(season("s1", 1, SHOW)))
        episodes = mapOf("s1" to (1..3).map { episode("e$it", "s1", it) })
        orders = mapOf(SHOW to FIREFLY)
    }

    @After
    fun stop() = work.cancel()

    private fun text(id: Int) = context.getString(id)

    /** A row's line of [parts], as every setting row joins them. */
    private fun line(vararg parts: String) = parts.joinToString(" · ")

    private fun episodes(n: Int) = context.resources.getQuantityString(R.plurals.numbers_episodes, n, "$n")

    private fun page(): DetailViewModel {
        val vm = DetailViewModel(SHOW, { provider }, FakeEvents(), work)
        compose.setTvContent {
            DetailPage(
                vm,
                PlaybackOutcome(MutableSharedFlow()),
                screen = "page",
                onPlay = { _, _, _ -> },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = {},
                onOpenPerson = {},
            )
        }
        compose.waitForIdle()
        return vm
    }

    private fun modifier() {
        compose.onNodeWithContentDescription(text(R.string.edit)).requestFocus()
        compose.press(Key.DirectionCenter)
    }

    /** Modifier, then OK on its row Ordre des épisodes: Modifier closes, the choice opens, focus on the order in use. */
    private fun choices() {
        modifier()
        compose.onNode(hasText(text(R.string.episode_order)) and hasClickAction()).requestFocus()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
    }

    @Test
    fun modifierSaysTheOrderInUseUnderIdentifier() {
        page()
        modifier()
        compose.onNodeWithText(text(R.string.episode_order)).assertExists()
        compose.onNodeWithText(line(text(R.string.episode_order_auto), text(R.string.episode_order_aired))).assertExists()
    }

    /** Automatique by the NFO says the order by its kind and the NFO, so the longest kinds hold on the row's one line. */
    @Test
    fun modifierSaysTheOrderTheNfoNamesByItsKindAndTheNfo() {
        provider.orders = mapOf(SHOW to FIREFLY.copy(automatic = FIREFLY.options[1], fromNfo = true))
        page()
        modifier()
        compose.onNodeWithText(context.getString(R.string.episode_order_auto_nfo, text(R.string.episode_order_dvd))).assertExists()
    }

    @Test
    fun aShowTmdbNumbersOneWayOffersNoOrder() {
        provider.orders = emptyMap()
        page()
        modifier()
        compose.onNodeWithText(text(R.string.identify)).assertExists()
        compose.onNodeWithText(text(R.string.episode_order)).assertDoesNotExist()
    }

    /** Automatique first, said by what it resolves to, then TMDB's seasons by their counts, then each group by its name. */
    @Test
    fun theChoicesAreAutomaticThenTmdbsSeasonsThenItsGroups() {
        page()
        choices()
        compose.onNodeWithText(text(R.string.episode_order_text)).assertExists()
        compose.onNodeWithText(line(context.resources.getQuantityString(R.plurals.season_count, 1, 1), episodes(14))).assertExists()
        compose.onNodeWithText(line("DVD Order", episodes(14))).assertExists()
        compose.onNode(hasText(text(R.string.episode_order_auto)) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun choosingAnOrderAppliesItAndTheRowSaysSoMeanwhile() {
        val placing = provider.gate("order $SHOW $DVD")
        val vm = page()
        choices()
        repeat(2) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.episode_order_applying)).assertExists()
        // The cards of the order before, kept until the page reads again.
        compose.onNode(hasText("e1") and hasClickAction()).assertExists()
        placing.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.episode_order_applying)).assertDoesNotExist()
        assertEquals(DVD, vm.state.value.episodeOrders?.chosen)
        assertFalse(vm.state.value.reordering)
    }

    /**
     * The order chosen empties the season shown, its episodes placed in season 21: the page shows that season, not the
     * first, and focus, on an episode of the row meanwhile, goes to its first episode.
     */
    @Test
    fun theShownSeasonEmptiedFocusGoesToTheFirstEpisodeOfTheSeasonTheEpisodesMovedTo() {
        val placing = provider.gate("order $SHOW $DVD")
        val vm = page()
        choices()
        repeat(2) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.onNode(hasText("e2") and hasClickAction()).requestFocus()
        compose.waitForIdle()

        val moved = (1..3).map { episode("e$it", "s21", 179 + it) }
        provider.seasons = mapOf(SHOW to listOf(season("s2", 2, SHOW), season("s21", 21, SHOW)))
        provider.episodes = mapOf("s2" to listOf(episode("x1", "s2", 1)), "s21" to moved)
        moved.forEach { provider.items[it.id] = it }
        placing.complete(Unit)
        compose.waitForIdle()

        assertEquals("s21", vm.state.value.selectedSeasonId)
        compose.onNode(hasText("e1") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun theOrderInUseAndBackChangeNothing() {
        page()
        choices()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        choices()
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()

        assertEquals(emptyList<String>(), provider.calls.filter { it.startsWith("order ") })
        compose.onNodeWithContentDescription(text(R.string.edit)).assertIsFocused()
    }

    @Test
    fun aChoiceThatFailedIsSaidAtTheEndOfTheActions() {
        provider.fail = { call -> IOException("TMDB is unreachable").takeIf { call.startsWith("order ") } }
        val vm = page()
        vm.chooseEpisodeOrder(DVD)
        compose.waitForIdle()

        assertNotNull(vm.state.value.actionError)
        assertFalse(vm.state.value.reordering)
        compose.onNodeWithText(text(R.string.episode_order_applying)).assertDoesNotExist()
    }

    private companion object {
        const val SHOW = "firefly"
        const val DVD = "dvd"
        val FIREFLY = EpisodeOrderChoices(
            options = listOf(
                EpisodeOrderOption("aired", EpisodeOrderKind.AIRED, episodes = 14, seasons = 1),
                EpisodeOrderOption(DVD, EpisodeOrderKind.DVD, "DVD Order", episodes = 14),
                EpisodeOrderOption("air", EpisodeOrderKind.ORIGINAL_AIR_DATE, "Original Air Date", episodes = 14),
            ),
            chosen = null,
            automatic = EpisodeOrderOption("aired", EpisodeOrderKind.AIRED, episodes = 14, seasons = 1),
        )
    }
}
