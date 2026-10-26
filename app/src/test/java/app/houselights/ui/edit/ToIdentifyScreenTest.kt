package app.houselights.ui.edit

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
import app.houselights.core.ToIdentify
import app.houselights.ui.design.components.MENU_KEY
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.longClickNamed
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
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
 * « À identifier » as the remote drives it: focus opens on the first title, stays on the one it is on as the list
 * reloads and, once that title leaves, goes to the one in its place, else to the last; Back from deep in the list
 * first returns to its start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class ToIdentifyScreenTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val provider = FakeProvider().apply { toIdentify = (1..TITLES).map { title(it) } }
    private val vm by lazy { ToIdentifyViewModel({ provider }, work) }
    private val opened = mutableListOf<String>()
    private var left = 0

    /** Film [n], unsure between titles, the best of which is "Best [n]" (2020); or not found on TMDB. */
    private fun title(n: Int, doubt: MatchDoubt = MatchDoubt.UNSURE): ToIdentify {
        val best = listOf(MatchCandidate("$n", "Best $n", null, 2020, null, null)).takeIf { doubt == MatchDoubt.UNSURE }
        return ToIdentify(film("m$n", "Film $n").copy(year = 2000 + n, matchDoubt = doubt), "Films/Film $n.mkv", doubt, best.orEmpty())
    }

    @After
    fun stop() = work.cancel()

    private fun show() {
        compose.setTvContent {
            // What Back reaches once the list lets it go: the screen under it.
            BackHandler { left++ }
            ToIdentifyPage(vm, onOpen = { opened += it })
        }
        compose.waitForIdle()
    }

    /** Focus on the title named [name], whole: "Film 1" is not "Film 10". */
    private fun focusIsOn(name: String) = compose.onNode(isFocused()).assert(hasText(name))

    /** The list read again, as a change of the library has it. */
    private fun reload(change: (List<ToIdentify>) -> List<ToIdentify>) {
        provider.toIdentify = change(provider.toIdentify)
        compose.runOnIdle { vm.load() }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun theListOpensOnItsFirstTitleUnderItsCount() {
        show()
        compose.onNodeWithText("$TITLES${NB}titles").assertExists()
        focusIsOn("Film 1")
    }

    @Test
    fun eachTitleSaysWhyWhatItMayBeAndWhereItLies() {
        provider.toIdentify = listOf(title(1), title(2, MatchDoubt.NOT_FOUND))
        show()
        compose.onNode(isFocused()).assert(hasText("Several possible titles"))
            .assert(hasText("Maybe “Best 1” (2020)")).assert(hasText("Films/Film 1.mkv"))
        compose.press(Key.DirectionDown)
        focusIsOn("Film 2")
        compose.onNode(isFocused()).assert(hasText("Not found on TMDB"))
        compose.onNodeWithText("Maybe", substring = true).assertExists()
        compose.onNodeWithText("Maybe “Best 2”", substring = true).assertDoesNotExist()
    }

    @Test
    fun okOpensIdentifierOnTheTitle() {
        show()
        compose.press(Key.DirectionDown)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("m2"), opened)
    }

    @Test
    fun aTitleIdentifiedLeavesFocusOnTheOneTakingItsPlace() {
        show()
        compose.press(Key.DirectionDown)
        focusIsOn("Film 2")
        reload { titles -> titles.filter { it.item.id != "m2" } }
        focusIsOn("Film 3")
        compose.onNodeWithText("${TITLES - 1}${NB}titles").assertExists()
    }

    @Test
    fun theLastTitleIdentifiedLeavesFocusOnTheNewLast() {
        provider.toIdentify = provider.toIdentify.take(3)
        show()
        compose.press(Key.DirectionDown, times = 2)
        focusIsOn("Film 3")
        reload { titles -> titles.dropLast(1) }
        focusIsOn("Film 2")
    }

    /** A scan adding a title above, or removing another, never moves focus. */
    @Test
    fun aReloadLeavesFocusOnTheTitleItIsOn() {
        show()
        compose.press(Key.DirectionDown)
        reload { titles -> listOf(title(0)) + titles.filter { it.item.id != "m5" } }
        focusIsOn("Film 2")
    }

    @Test
    fun anEmptyListSaysSo() {
        provider.toIdentify = emptyList()
        show()
        compose.onNodeWithText("Nothing to identify").assertExists()
        compose.onNodeWithText("Titles Houselights did not recognize with certainty will show here.").assertExists()
    }

    @Test
    fun backFromPastTheFirstTitleReturnsToItThenLeaves() {
        show()
        compose.press(Key.DirectionDown, times = 5)
        focusIsOn("Film 6")
        back()
        focusIsOn("Film 1")
        assertEquals(0, left)
        back()
        assertEquals(1, left)
    }

    /** Menu on a title TMDB hesitated on opens its suggestions, the best focused: Menu then OK accepts it. */
    @Test
    fun menuThenOkAcceptsTheBestSuggestion() {
        provider.toIdentify = listOf(title(1), title(2))
        show()
        compose.onNode(isFocused()).assert(longClickNamed("More options"))
        compose.press(Key.Menu)
        compose.onNode(hasText("Best 1") and isFocused()).assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("identify m1 1" in provider.calls)
        compose.waitForIdle()
        focusIsOn("Film 2")
        assertTrue("no Identifier opened", opened.isEmpty())
    }

    /** Back closes the suggestions, nothing chosen, focus on the title. */
    @Test
    fun backClosesTheSuggestionsUnchanged() {
        show()
        compose.press(Key.Menu)
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        assertTrue(provider.calls.none { it.startsWith("identify") })
        focusIsOn("Film 1")
    }

    /** A title not found on TMDB has nothing to suggest: no Menu key, no options. */
    @Test
    fun aTitleNotFoundShowsNoMenuKey() {
        provider.toIdentify = listOf(title(1), title(2, MatchDoubt.NOT_FOUND))
        show()
        compose.onAllNodes(hasTestTag(MENU_KEY), useUnmergedTree = true).assertCountEquals(1)
        compose.press(Key.DirectionDown)
        focusIsOn("Film 2")
        compose.onAllNodes(hasTestTag(MENU_KEY), useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(isFocused()).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    /** Back from the queue, focus is on the title the queue reached, once. */
    @Test
    fun backFromTheQueueFocusIsOnTheTitleItReached() {
        var reached: String? = "m3"
        compose.setTvContent { ToIdentifyPage(vm, onOpen = {}, queueAt = reached, onQueueSeen = { reached = null }) }
        compose.waitForIdle()
        focusIsOn("Film 3")
        assertEquals(null, reached)
    }

    private companion object {
        const val TITLES = 8

        /** A number never leaves its unit. */
        const val NB = Typography.nbsp
    }
}
