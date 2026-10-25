package app.houselights.ui.home

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.holdOk
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.UiError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A play asked from home's case that fails is said in place of its facts, in a word, its plate following what fixes
 * it, until a click on it; Menu then opens its options with the whole sentence first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class HomePlayFailureTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** What the case did: plays, dismissals, page openings and fixes, in order. */
    private val did = mutableListOf<String>()

    private val actions = HomeActions(
        onPlay = { item, resume -> did += "play ${item.id} $resume" },
        onOpenItem = { did += "open ${it.id}" },
        onClearProblem = { did += "clear" },
    )

    private var problem by mutableStateOf<UiError?>(null)
    private var fontScale by mutableFloatStateOf(1f)

    private fun open(home: HomeState = fullHome(), failed: UiError? = null) {
        problem = failed
        compose.setTvContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalErrorFix provides { did += "fix $it" },
            ) { HomeGrid(home, actions, playProblem = problem) }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = context.getString(id)

    private fun said(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    private val case get() = compose.onNode(hasText("Heat") and hasClickAction())

    @Test
    fun theMessageTakesThePlaceOfTheFacts() {
        open(failed = UiError.NO_PLAYER)
        said(text(R.string.play_failed)).assertIsDisplayed()
        said(text(R.string.play_failed_no_player)).assertIsDisplayed()
        // The facts (here the time left) and the sentence are not said on the case.
        case.assert(!hasText("left", substring = true)).assert(!hasText(text(R.string.no_player)))
    }

    @Test
    fun foldersOutOfReachAreTheNas() {
        open(failed = UiError.OFFLINE)
        said(text(R.string.error_offline_nas_title)).assertIsDisplayed()
    }

    @Test
    fun aServerOutOfReachIsTheServer() {
        open(fullHome(status = HomeStatus(StatusSource.Server("Salon"))), UiError.OFFLINE)
        said(text(R.string.error_offline_server_title)).assertIsDisplayed()
    }

    @Test
    fun eachPlayerErrorHasItsWordAndTheOthersTheirTitle() {
        open()
        val words = mapOf(
            UiError.NO_PLAYER to R.string.play_failed_no_player,
            UiError.CREDENTIALS to R.string.play_failed_credentials,
            UiError.PLAYER_CHANGED to R.string.play_failed_player_changed,
            UiError.CERTIFICATE to R.string.error_certificate_title,
            UiError.DENIED to R.string.error_denied_title,
            UiError.GONE to R.string.error_gone_title,
            UiError.FAILED to R.string.error_failed_title,
        )
        words.forEach { (error, word) ->
            problem = error
            compose.waitForIdle()
            said(text(word)).assertIsDisplayed()
        }
    }

    /** Réessayer plays the title again, as the case did. */
    @Test
    fun aRetryPlateRetriesOnClick() {
        open(failed = UiError.OFFLINE)
        case.assert(hasText(text(R.string.retry))).assert(!hasText(text(R.string.resume)))
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("clear", "play heat true"), did)
    }

    @Test
    fun aRefusedConnectionPlateSignsInAgain() {
        open(failed = UiError.DENIED)
        case.assert(hasText(text(R.string.error_action_reconnect)))
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("clear", "fix ${ErrorAction.RECONNECT}"), did)
    }

    /** Nothing a new try fixes: the case's own plate, the reason saying why. */
    @Test
    fun withoutAFixThePlateStaysTheCasesOwn() {
        open(failed = UiError.PLAYER_CHANGED)
        case.assert(hasText(text(R.string.resume))).assert(!hasText(text(R.string.retry)))
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("clear", "play heat true"), did)
    }

    /** OK held opens the options with the failure first, the message kept: only a click dismisses it. */
    @Test
    fun okHeldKeepsTheMessageForTheOptions() {
        open(failed = UiError.OFFLINE)
        compose.holdOk()
        assertEquals(emptyList<String>(), did)
        compose.onNodeWithText(text(R.string.error_offline)).assertExists()
        compose.onNodeWithText(text(R.string.home_open_title)).assertExists()
        compose.onNodeWithText(text(R.string.play_from_start)).assertExists()
        compose.onNodeWithText(text(R.string.error_action_reconnect)).assertDoesNotExist()
    }

    @Test
    fun theOptionsOfARefusedConnectionSignInAgain() {
        open(failed = UiError.DENIED)
        compose.holdOk()
        compose.onNodeWithText(text(R.string.error_denied)).assertExists()
        compose.onNodeWithText(text(R.string.play_from_start)).assertDoesNotExist()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(listOf("fix ${ErrorAction.RECONNECT}"), did)
    }

    @Test
    fun theOptionsOfAnErrorNoTryFixesOnlyOpenThePage() {
        open(failed = UiError.NO_PLAYER)
        compose.holdOk()
        compose.onNodeWithText(text(R.string.no_player)).assertExists()
        compose.onNodeWithText(text(R.string.home_open_title)).assertExists()
        compose.onNodeWithText(text(R.string.play_from_start)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.error_action_reconnect)).assertDoesNotExist()
    }

    /** The message gone, the options are the case's own again. */
    @Test
    fun withoutAMessageTheOptionsAreTheCasesOwn() {
        open()
        compose.holdOk()
        compose.onNodeWithText(text(R.string.play_failed)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.play_from_start)).assertExists()
    }

    /** At the largest text size, every reason holds on one line, and the logo stays where the facts left it. */
    @Test
    fun theReasonNeverWrapsNorMovesTheLogo() {
        fontScale = LARGEST_TEXT
        open()
        val logo = logoTop()
        UiError.entries.forEach { error ->
            problem = error
            compose.waitForIdle()
            assertEquals("the logo, for $error", logo, logoTop(), HALF_PX)
            val reason = said(reasonOf(error))
            assertEquals("lines of $error's reason", 1, lineCount(reason.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]))
        }
    }

    /** A film proposed with no genres keeps the message's room under its facts, so its logo does not move either. */
    @Test
    fun aProposedFilmKeepsTheMessagesRoom() {
        val sections = (fullHome().content as HomeContent.Rows).sections.drop(1)
        val home = fullHome(sections = sections).let { it.copy(doors = it.doors!!.copy(proposed = title("heat"))) }
        open(home)
        val logo = logoTop()
        problem = UiError.OFFLINE
        compose.waitForIdle()
        said(text(R.string.error_offline_nas_title)).assertIsDisplayed()
        assertEquals(logo, logoTop(), HALF_PX)
    }

    /** Where the case's title sets, its name standing for its logo: the lower of the two, under the fallback picture's. */
    private fun logoTop(): Float =
        compose.onAllNodesWithText("Heat", useUnmergedTree = true).fetchSemanticsNodes().maxOf { it.boundsInRoot.top }

    private fun reasonOf(error: UiError): String = text(
        when (error) {
            UiError.NO_PLAYER -> R.string.play_failed_no_player
            UiError.CREDENTIALS -> R.string.play_failed_credentials
            UiError.PLAYER_CHANGED -> R.string.play_failed_player_changed
            UiError.OFFLINE -> R.string.error_offline_nas_title
            else -> error.title
        },
    )

    private fun lineCount(action: AccessibilityAction<(MutableList<TextLayoutResult>) -> Boolean>): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        action.action?.invoke(layouts)
        return layouts.single().lineCount
    }

    private companion object {
        const val LARGEST_TEXT = 1.3f
        const val HALF_PX = 0.5f
    }
}
