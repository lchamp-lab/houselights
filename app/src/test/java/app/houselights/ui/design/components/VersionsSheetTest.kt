package app.houselights.ui.design.components

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.houselights.core.Editions
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Version
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A film's copies: focus starts on the current choice, announced as such; OK on another chooses it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class VersionsSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val film = MediaItem("m1", MediaKind.MOVIE, "Blade Runner", year = 1982)
    private fun copy(key: String, edition: String, chosen: Boolean) =
        Version(film, edition, null, null, null, 7_020_000, chosen = chosen, best = chosen, key = key)

    private val versions = listOf(copy("theatrical", Editions.THEATRICAL, chosen = false), copy("final", Editions.FINAL_CUT, chosen = true))
    private var chosen: Version? = null

    /** The sheet, opened from its button as a detail page opens it. */
    private fun open() {
        var open by mutableStateOf(false)
        val opener = FocusRequester()
        compose.setTvContent {
            HlButton("Versions", onClick = { open = true }, modifier = Modifier.focusRequester(opener))
            if (open) VersionsSheet(film, versions, onChoose = { chosen = it }, onDismiss = { open = false })
        }
        compose.runOnIdle { opener.requestFocus() }
        compose.press(Key.DirectionCenter)
    }

    @Test
    fun yourChoiceIsSaidAndFocusStartsOnIt() {
        open()
        compose.onNodeWithContentDescription("Final cut", substring = true, useUnmergedTree = true)
            .assert(hasContentDescription("Your choice", substring = true))
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("OK where focus starts: the copy chosen", "final", chosen?.key)
    }

    @Test
    fun okOnAnotherChoosesIt() {
        open()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_UP)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("theatrical", chosen?.key)
    }
}
