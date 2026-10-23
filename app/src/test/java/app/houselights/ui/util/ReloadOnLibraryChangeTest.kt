package app.houselights.ui.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Screens sharing one view model reload it once for a change of the library, whichever shows it first. */
@RunWith(RobolectricTestRunner::class)
class ReloadOnLibraryChangeTest {
    @get:Rule
    val compose = createComposeRule()

    /** Home, then « À regarder » over it, each keeping its saved state as the back stack does: one change, one reload. */
    @Test
    fun aChangeSeenOnThePageIsNotReloadedAgainBackHome() {
        val version = MutableStateFlow(1L)
        val shown = LibraryShown()
        var reloads = 0
        var screen by mutableStateOf("home")
        compose.setContent {
            val saved = rememberSaveableStateHolder()
            saved.SaveableStateProvider(screen) { ReloadOnLibraryChange(version, shown) { reloads++ } }
        }
        compose.waitForIdle()
        screen = "watch"
        compose.waitForIdle()
        version.value = 2L
        compose.waitForIdle()
        assertEquals("the page reloads", 1, reloads)
        screen = "home"
        compose.waitForIdle()
        assertEquals("home does not again", 1, reloads)
    }
}
