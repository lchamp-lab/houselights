package app.houselights.ui.detail

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.MatchDoubt
import app.houselights.core.UserState
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.episode
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The board's open question: a show to identify, its row the fullest a title's page has (Lire, Identifier, the watched
 * tile, its NFO, the favourite, Modifier), says « À identifier » and why at the row's end, each on its line, inside the
 * safe area, at the largest text size as at the standard one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts, so the words take the room they take on the player.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailToIdentifyLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val provider = FakeProvider().apply {
        items["show"] = series("show").copy(
            title = "Kaamelott",
            hasNfo = true,
            matchDoubt = MatchDoubt.UNSURE,
            user = UserState(unplayedCount = 2),
        )
        seasons = mapOf("show" to listOf(season("s1", 1, "show").copy(childCount = 2, user = UserState(unplayedCount = 2))))
        episodes = mapOf("s1" to (1..2).map { episode("s1e$it", "s1", it) })
        nextUps = mapOf("show" to "s1e1")
    }

    @After
    fun stop() = work.cancel()

    private fun assertWholeAt(step: Float) {
        RuntimeEnvironment.setFontScale(step)
        val vm = DetailViewModel("show", { provider }, FakeEvents(), work)
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
        val safe = SCREEN - HlSpace.safeH.value
        val words = listOf("To identify", "Several possible titles").map {
            compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        val (state, reason) = words
        assertTrue("at $step, inside the safe area: $words", words.all { it.right <= safe && it.width > 0 })
        val oneLine = reason.top >= state.bottom && reason.height < state.height * ONE_LINE
        assertTrue("at $step, the reason on one line under the state: $words", oneLine)
    }

    @Test
    fun theStateHoldsBesideTheFullestRow() = assertWholeAt(1f)

    @Test
    fun theStateHoldsBesideTheFullestRowAtTheLargestTextSize() = assertWholeAt(LARGEST)

    private companion object {
        const val SCREEN = 960f
        const val LARGEST = 1.3f

        /** Under this many of the state's heights, the reason (same style) holds on one line. */
        const val ONE_LINE = 1.5f
    }
}
