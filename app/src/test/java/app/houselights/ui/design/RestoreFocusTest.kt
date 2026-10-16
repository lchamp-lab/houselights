package app.houselights.ui.design

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Focus: coming back to a screen finds focus on what was left, by what it is, not where
 * it sat, even after the list around it changed; a list that changes under the viewer never moves
 * focus away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = TV)
class RestoreFocusTest {
    @get:Rule
    val compose = createComposeRule()

    /** A screen of titles in a list, its memory kept while another screen shows (as the back stack keeps it). */
    @Composable
    private fun Titles(titles: List<String>) {
        val memory = rememberFocusMemory()
        val list = rememberLazyListState()
        RestoreFocus(memory, fallback = memory.requester(FocusKey.item(titles.first())), ready = titles.isNotEmpty()) { key ->
            val index = titles.indexOf(FocusKey.itemId(key))
            if (index >= 0) list.scrollToItem(index)
            index >= 0
        }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(titles, key = { it }) { id ->
                HlSettingRow("Titre $id", Modifier.focusKey(FocusKey.item(id), memory).testTag(id), onClick = {})
            }
        }
    }

    @Test
    fun comingBackFindsTheTitleLeftWhereverTheListPutIt() {
        var titles by mutableStateOf((1..20).map { "t$it" })
        var away by mutableStateOf(false)
        compose.setTvContent {
            val screens = rememberSaveableStateHolder()
            if (away) HlButton("Lire", onClick = {}, modifier = Modifier.testTag("player"))
            else screens.SaveableStateProvider("titles") { Titles(titles) }
        }
        // A first visit starts on the first title.
        compose.onNodeWithTag("t1").assertIsFocused()
        compose.press(Key.DirectionDown, times = 4)
        compose.onNodeWithTag("t5").assertIsFocused()

        // Away, the library changes: twelve titles arrive ahead, so the one left is out of sight.
        away = true
        compose.waitForIdle()
        titles = (1..12).map { "new$it" } + titles
        away = false
        compose.onNodeWithTag("t5").assertIsFocused()

        // More arriving while the viewer is there does not move focus.
        titles = listOf("newer") + titles
        compose.onNodeWithTag("t5").assertIsFocused()
    }
}
