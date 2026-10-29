package app.houselights.ui.design

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Focus: focus returns to what was left, by identity, and a list changing under it never moves it, but for the
 * title with focus leaving it, whose place takes it, as when it went while the screen was away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class RestoreFocusTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * A screen of titles in a list, its memory kept while another screen shows, as the back stack keeps it, and the place
     * of the title last focused: a title gone while it was away leaves focus to the one now there.
     */
    @Composable
    private fun Titles(titles: List<String>) {
        val memory = rememberFocusMemory()
        val list = rememberLazyListState()
        var place by rememberSaveable { mutableIntStateOf(0) }
        RestoreFocus(
            memory,
            fallback = memory.requester(FocusKey.item(titles.first())),
            ready = titles.isNotEmpty(),
            inPlace = { key -> inPlaceOf(key, titles, place) },
        ) { key ->
            val index = titles.indexOf(FocusKey.itemId(key))
            if (index >= 0) list.scrollToItem(index)
            index >= 0
        }
        FocusTakesPlace(remember(titles) { listOf(FocusPlaces(null, titles.map(FocusKey::item))) }, memory)
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            itemsIndexed(titles, key = { _, id -> id }) { i, id ->
                HlSettingRow(
                    "Titre $id",
                    Modifier.focusKey(FocusKey.item(id), memory).testTag(id).onFocusChanged { if (it.isFocused) place = i },
                    onClick = {},
                )
            }
        }
    }

    /** The title now at [place] among [titles] when the one [key] names is gone from them, else the last; null if it is there. */
    private fun inPlaceOf(key: String, titles: List<String>, place: Int): String? {
        if (FocusKey.itemId(key) in titles) return null
        return (titles.getOrNull(place) ?: titles.lastOrNull())?.let(FocusKey::item)
    }

    @Test
    fun comingBackFindsTheTitleLeftWhereverTheListPutIt() {
        var titles by mutableStateOf((1..20).map { "t$it" })
        var away by mutableStateOf(false)
        compose.setTvContent {
            val screens = rememberSaveableStateHolder()
            if (away) {
                HlButton("Lire", onClick = {}, modifier = Modifier.testTag("player"))
            } else {
                screens.SaveableStateProvider("titles") { Titles(titles) }
            }
        }
        compose.onNodeWithTag("t1").assertIsFocused()
        compose.press(Key.DirectionDown, times = 4)
        compose.onNodeWithTag("t5").assertIsFocused()

        // While away, the library changes: twelve titles arrive ahead, so the one left is out of sight.
        away = true
        compose.waitForIdle()
        titles = (1..12).map { "new$it" } + titles
        away = false
        compose.onNodeWithTag("t5").assertIsFocused()

        // More arriving while the viewer is there does not move focus.
        titles = listOf("newer") + titles
        compose.onNodeWithTag("t5").assertIsFocused()
    }

    /** The title with focus leaving the list, the one now in its place takes it; the last leaving, the one before it. */
    @Test
    fun aTitleLeavingWithFocusLeavesItToTheOneInItsPlace() {
        var titles by mutableStateOf((1..6).map { "t$it" })
        compose.setTvContent { Titles(titles) }
        compose.press(Key.DirectionDown, times = 2)
        compose.onNodeWithTag("t3").assertIsFocused()

        titles = titles - "t3"
        compose.onNodeWithTag("t4").assertIsFocused()
        compose.press(Key.DirectionDown, times = 3)
        compose.onNodeWithTag("t6").assertIsFocused()
        titles = titles - "t6"
        compose.onNodeWithTag("t5").assertIsFocused()
        // One that leaves without focus moves nothing.
        titles = titles - "t1"
        compose.onNodeWithTag("t5").assertIsFocused()
    }

    /** A title gone while the screen was away: coming back, focus is on the one now in its place. */
    @Test
    fun aTitleGoneWhileAwayLeavesFocusToTheOneInItsPlace() {
        var titles by mutableStateOf((1..6).map { "t$it" })
        var away by mutableStateOf(false)
        compose.setTvContent {
            val screens = rememberSaveableStateHolder()
            if (away) {
                HlButton("Lire", onClick = {}, modifier = Modifier.testTag("player"))
            } else {
                screens.SaveableStateProvider("titles") { Titles(titles) }
            }
        }
        compose.press(Key.DirectionDown, times = 2)
        compose.onNodeWithTag("t3").assertIsFocused()
        away = true
        compose.waitForIdle()
        titles = titles - "t3"
        away = false
        compose.onNodeWithTag("t4").assertIsFocused()
    }

    /** In rows, a card's row emptied gives focus to the row after it, else the one before it. */
    @Test
    fun aRowEmptiedLeavesFocusToTheRowAfterItElseBefore() {
        val before = listOf(FocusPlaces("a", listOf("a1")), FocusPlaces("b", listOf("b1", "b2")), FocusPlaces("c", listOf("c1")))
        val left = leaving(before, before.filter { it.group != "b" }) { it == "b2" }
        assertEquals(LeftPlace(1, 1), left)
        assertEquals("c", takesPlace(checkNotNull(left), before, before.filter { it.group != "b" }))
        assertEquals("a", takesPlace(LeftPlace(2, 0), before, before.take(2)))
        assertEquals("b2", takesPlace(LeftPlace(1, 0), before, listOf(before[0], FocusPlaces("b", listOf("b2")), before[2])))
    }
}
