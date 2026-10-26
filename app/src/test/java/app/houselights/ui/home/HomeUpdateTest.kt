package app.houselights.ui.home

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.providers.local.ScanState
import app.houselights.ui.design.LaunchScreen
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.settings.FakeControls
import app.houselights.ui.settings.OFFER
import app.houselights.ui.settings.updates
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.update.UpdateState
import app.houselights.update.Updated
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Messages: a version found opens its sheet on home once, never over another sheet, under the launch screen
 * or with the check off; once the app is back from an update, home's head says it for 2.5 s, once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class HomeUpdateTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    private val home = fullHome()

    private val title = "Une nouvelle version est disponible"

    /** The launch screen, covering until it lifts. */
    private class Cover : LaunchScreen {
        override var covers = true

        override fun drawn() = Unit
    }

    /** Another sheet over home, open while [other] is. */
    private var other by mutableStateOf(false)

    private fun show(state: MutableStateFlow<UpdateState>, launch: LaunchScreen? = null): FakeControls {
        val controls = FakeControls(state)
        compose.setTvContent {
            val grid: @Composable () -> Unit = {
                HomeGrid(home, remember { MutableStateFlow(ScanState()) }, HomeActions(onOpenSearch = {}), updates = controls.ui)
                if (other) HlSideSheet("Autre", onDismiss = { other = false }) {}
            }
            if (launch == null) grid() else CompositionLocalProvider(LocalLaunchScreen provides launch) { grid() }
        }
        settle()
        return controls
    }

    /** Lets home settle, as it does before it opens a sheet or says a word. */
    private fun settle(ms: Long = SETTLED_MS) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    @Test
    fun aVersionFoundOpensItsSheetOnHomeOnce() {
        val state = MutableStateFlow(updates())
        val controls = show(state)
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
        assertTrue(controls.asked.toString(), "shownOnHome 1010" in controls.asked)
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        settle()
        compose.onNodeWithText(title).assertDoesNotExist()
        settle(LATER_MS)
        compose.onNodeWithText(title).assertDoesNotExist()
        // A newer version opens it again.
        state.value = state.value.copy(offer = OFFER.copy(tag = "v0.4.2", versionName = "0.4.2", versionCode = 1020))
        settle()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("Version 0.4.2").assertExists()
    }

    @Test
    fun withTheCheckOffItNeverOpens() {
        show(MutableStateFlow(updates().copy(automatic = false)))
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun itNeverOpensOverAnotherSheet() {
        other = true
        val controls = show(MutableStateFlow(updates()))
        compose.onNodeWithText(title).assertDoesNotExist()
        assertTrue(controls.asked.none { it.startsWith("shownOnHome") })
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        settle()
        compose.onNodeWithText(title).assertExists()
    }

    @Test
    fun itNeverOpensUnderTheLaunchScreen() {
        val cover = Cover()
        show(MutableStateFlow(updates()), cover)
        compose.onNodeWithText(title).assertDoesNotExist()
        cover.covers = false
        settle()
        compose.onNodeWithText(title).assertExists()
    }

    @Test
    fun homeSaysTheUpdateInstalledOnceAtItsHead() {
        val updated = Updated("0.4.1", installed = true, at = System.currentTimeMillis())
        val state = MutableStateFlow(updates(offer = null).copy(updated = updated))
        val controls = show(state)
        compose.onNodeWithText("Houselights 0.4.1 installé").assertExists()
        settle(SAID_MS)
        compose.onNodeWithText("Houselights 0.4.1 installé").assertDoesNotExist()
        assertEquals(1, controls.asked.count { it == "updatedSaid" })
        assertEquals(true, state.value.updated?.said)
    }

    @Test
    fun homeSaysAnUpdateThatDidNotHappenOnce() {
        val missed = Updated("0.4.1", installed = false, at = System.currentTimeMillis())
        show(MutableStateFlow(updates(offer = null).copy(updated = missed)))
        compose.onNodeWithText("La version 0.4.1 n’a pas été installée").assertExists()
        settle(SAID_MS)
        compose.onNodeWithText("La version 0.4.1 n’a pas été installée").assertDoesNotExist()
    }

    @Test
    fun aBuildWithoutTheUpdaterShowsNoneOfIt() {
        val off = updates().copy(on = false, updated = Updated("0.4.1", installed = true, at = System.currentTimeMillis()))
        show(MutableStateFlow(off))
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText("Houselights 0.4.1 installé").assertDoesNotExist()
    }

    private companion object {
        /** Past what home waits before it opens the sheet: the launch screen's polls, then focus settled. */
        const val SETTLED_MS = 1_000L
        const val SAID_MS = 2_600L
        const val LATER_MS = 10_000L
    }
}
