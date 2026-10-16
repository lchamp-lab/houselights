package app.houselights.ui.design

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A screen's [FocusMemory] on screen: whether focus really took, and what it keeps of a long list. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = TV)
class FocusMemoryTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * The element left is still there but takes no focus (nothing in it can have it): the memory
     * says so, and the restore goes on to where the screen starts instead of leaving focus nowhere.
     */
    @Test
    fun anElementThatRefusesFocusLeavesItToTheFallback() {
        val memory = FocusMemory(initial = FocusKey.item("left"))
        val start = FocusRequester()
        compose.setTvContent {
            Column {
                Box(Modifier.focusKey(FocusKey.item("left"), memory).focusProperties { canFocus = false }.focusable().size(HlSize.control))
                HlButton("Lire", onClick = {}, modifier = Modifier.focusRequester(start).testTag("start"))
            }
            RestoreFocus(memory, fallback = start)
        }
        compose.onNodeWithTag("start").assertIsFocused()
        compose.runOnIdle { assertEquals(false, memory.focus(FocusKey.item("left"))) }
    }

    /** Nor is the element that refused focus held in view: the list stays where focus went. */
    @Test
    fun anElementThatRefusesFocusIsNotHeldInView() {
        val memory = FocusMemory(initial = FocusKey.item("left"))
        val start = FocusRequester()
        val list = LazyListState()
        compose.setTvContent {
            LazyColumn(Modifier.height(300.dp), state = list) {
                item { HlButton("Lire", onClick = {}, modifier = Modifier.focusRequester(start).testTag("start")) }
                // The third box sits across the bottom edge: bringing it into view would scroll.
                items(10) { i ->
                    val left = Modifier.focusKey(FocusKey.item("left"), memory).focusProperties { canFocus = false }
                    Box((if (i == 2) left else Modifier).focusable().size(100.dp))
                }
            }
            RestoreFocus(memory, fallback = start)
        }
        compose.onNodeWithTag("start").assertIsFocused()
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
    }

    /**
     * The card left has gone and its row is there, but nothing in it takes focus: the row says so,
     * and the restore goes on to where the screen starts. A requester on the row would only have
     * said the row was there, and left focus nowhere.
     */
    @Test
    fun aGroupThatRefusesFocusLeavesItToTheFallback() {
        val row = FocusKey.row("similar")
        val memory = FocusMemory(initial = FocusKey.within(row, FocusKey.item("gone")))
        val start = FocusRequester()
        compose.setTvContent {
            Column {
                MediaRow(header = null, modifier = Modifier.focusGroupKey(row, memory)) {
                    item { Box(Modifier.focusProperties { canFocus = false }.focusable().size(HlSize.control)) }
                }
                HlButton("Lire", onClick = {}, modifier = Modifier.focusRequester(start).testTag("start"))
            }
            RestoreFocus(memory, fallback = start)
        }
        compose.onNodeWithTag("start").assertIsFocused()
        compose.runOnIdle { assertEquals(false, memory.focus(row)) }
    }

    /** The card left has gone from a row that has others: focus comes back to the row, and goes on from there. */
    @Test
    fun aGroupTakesFocusForTheCardGoneFromIt() {
        val row = FocusKey.row("similar")
        val memory = FocusMemory(initial = FocusKey.within(row, FocusKey.item("gone")))
        val start = FocusRequester()
        compose.setTvContent {
            Column {
                HlButton("Lire", onClick = {}, modifier = Modifier.focusRequester(start).testTag("start"))
                MediaRow(header = null, modifier = Modifier.focusGroupKey(row, memory)) {
                    items(listOf("a", "b"), key = { it }) { id ->
                        val key = FocusKey.within(row, FocusKey.item(id))
                        HlButton(id, onClick = {}, modifier = Modifier.focusKey(key, memory).testTag(id))
                    }
                }
            }
            RestoreFocus(memory, fallback = start)
        }
        compose.onNodeWithTag("a").assertIsFocused()
        compose.press(Key.DirectionRight)
        compose.onNodeWithTag("b").assertIsFocused()
        compose.runOnIdle {
            assertEquals(FocusKey.within(row, FocusKey.item("b")), memory.last)
            assertTrue(memory.focus(row))
        }
    }

    /** Down a long list, the memory keeps what is composed, not every element it ever saw. */
    @Test
    fun theMemoryForgetsTheElementsThatLeft() {
        val memory = FocusMemory()
        val titles = (1..300).map { "t$it" }
        compose.setTvContent {
            LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                items(titles, key = { it }) { id ->
                    HlSettingRow("Titre $id", Modifier.focusKey(FocusKey.item(id), memory), onClick = {})
                }
            }
        }
        for (index in titles.indices step 5) compose.onNodeWithTag("list").performScrollToIndex(index)
        compose.runOnIdle {
            assertTrue("held ${memory.held}", memory.held.size < 30)
            assertTrue(FocusKey.item("t300") in memory.held)
            assertTrue(FocusKey.item("t1") !in memory.held)
        }
        // What is back on screen is known again.
        compose.onNodeWithTag("list").performScrollToIndex(0)
        compose.runOnIdle { assertTrue(memory.focus(FocusKey.item("t1"))) }
    }
}
