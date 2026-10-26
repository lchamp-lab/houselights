package app.houselights.ui.detail

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import app.houselights.R
import app.houselights.core.MatchDoubt
import app.houselights.core.UserState
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.episode
import app.houselights.ui.testing.press
import app.houselights.ui.testing.season
import app.houselights.ui.testing.series
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.PlaybackOutcome
import app.houselights.ui.util.formatHours
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
 * safe area, at the largest text size as at the standard one. Every episode held, its Identifier waits and the removal
 * ends the row as « Titres introuvables »'s board shows it: since when on two lines at most, Lire's refusal on four, inside
 * the safe area, in German, the longest, as in English.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts, so the words take the room they take on the player.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailToIdentifyLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playback = PlaybackOutcome(MutableSharedFlow())

    /** Kaamelott, to identify; [held], since 14 hours, as each of its episodes. */
    private fun provider(held: Boolean = false) = FakeProvider().apply {
        val since = (System.currentTimeMillis() - 14 * HOUR_MS - 5 * MINUTE_MS).takeIf { held }
        items["show"] = series("show").copy(
            title = "Kaamelott",
            hasNfo = true,
            matchDoubt = MatchDoubt.UNSURE,
            user = UserState(unplayedCount = 2),
            missingSince = since,
        )
        seasons = mapOf("show" to listOf(season("s1", 1, "show").copy(childCount = 2, user = UserState(unplayedCount = 2))))
        episodes = mapOf("s1" to (1..2).map { episode("s1e$it", "s1", it).copy(missingSince = since) })
        nextUps = mapOf("show" to "s1e1")
    }

    @After
    fun stop() = work.cancel()

    private fun open(step: Float, held: Boolean = false) {
        RuntimeEnvironment.setFontScale(step)
        val provider = provider(held)
        val vm = DetailViewModel("show", { provider }, FakeEvents(), work)
        compose.setTvContent {
            DetailPage(
                vm,
                playback,
                screen = "page",
                // As MainActivity plays: a title held is never tried.
                onPlay = { item, _, origin -> playback.tryPlay(item, origin) {} },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = {},
                onOpenPerson = {},
            )
        }
        compose.waitForIdle()
    }

    private fun assertWholeAt(step: Float) {
        open(step)
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

    /** [text]'s bounds and how many lines it takes. */
    private fun laidOut(text: String): Pair<Rect, Int> {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return node.boundsInRoot to layouts.first().lineCount
    }

    private fun string(id: Int, vararg args: Any) = RuntimeEnvironment.getApplication().getString(id, *args)

    /** Every episode held: since when, then Lire's refusal, each inside the safe area and on as many lines as the board. */
    private fun assertHeldWholeAt(step: Float) {
        open(step, held = true)
        val (identify, remove) = string(R.string.identify) to string(R.string.held_remove_title)
        compose.onNode(hasText(identify) and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasContentDescription(remove) and hasClickAction()).assertExists()
        val inside = { at: Rect -> at.left > 0 && at.right <= SCREEN - HlSpace.safeH.value && at.bottom <= HEIGHT - HlSpace.safeV.value }

        val (since, sinceLines) = laidOut(string(R.string.held_since, formatHours(RuntimeEnvironment.getApplication(), 14 * HOUR_MS)))
        assertTrue("at $step, since when inside the safe area: $since", inside(since))
        assertTrue("at $step, since when on $sinceLines lines", sinceLines <= SINCE_LINES)

        compose.press(Key.DirectionCenter)
        val (gone, goneLines) = laidOut(string(R.string.error_gone_title))
        val (fix, fixLines) = laidOut(string(R.string.held_gone_fix))
        assertTrue("at $step, the refusal inside the safe area: $gone, $fix", inside(gone) && inside(fix) && fix.top >= gone.bottom)
        assertTrue("at $step, the refusal on ${goneLines + fixLines} lines", goneLines + fixLines <= REFUSAL_LINES)
    }

    @Test
    fun theHeldStateHoldsBesideTheRow() = assertHeldWholeAt(1f)

    @Test
    fun theHeldStateHoldsBesideTheRowAtTheLargestTextSize() = assertHeldWholeAt(LARGEST)

    @Test
    @Config(qualifiers = "de-$TV")
    fun theHeldStateHoldsBesideTheRowInGerman() = assertHeldWholeAt(1f)

    @Test
    @Config(qualifiers = "de-$TV")
    fun theHeldStateHoldsBesideTheRowInGermanAtTheLargestTextSize() = assertHeldWholeAt(LARGEST)

    private companion object {
        const val SCREEN = 960f
        const val HEIGHT = 540f
        const val LARGEST = 1.3f
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS

        /** Under this many of the state's heights, the reason (same style) holds on one line. */
        const val ONE_LINE = 1.5f

        /** As « Titres introuvables »'s board shows them beside a film's row: since when on two lines, the refusal on four. */
        const val SINCE_LINES = 2
        const val REFUSAL_LINES = 4
    }
}
