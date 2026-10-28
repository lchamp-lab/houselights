package app.houselights.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.requestFocus
import app.houselights.core.FailureKind
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.toFolder
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.components.FirstTitle
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.LocalErrorFix
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Focus: home's first scan has three stops at most, the settings tile, « Aller à l'accueil » and « Modifier
 * la clé » for a refused key. Nothing takes focus as it comes; the button takes it as it comes only while nothing has
 * it, never from the tile or the key, nor coming back from Réglages, where focus is back on the tile. Down from the
 * tile goes to the button, Up back, Left to the key, Up or Right from it back; at the cut, focus is placed once on the
 * case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class FirstScanFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private val scan = MutableStateFlow(ScanState(running = true, sourceId = 1, processed = 3, total = 412, firstPass = true))

    private val shows = MutableStateFlow(FirstScanShows(nfsFolder(host = "DS920", share = "volume1", path = "Films", id = 1).toFolder()))

    private var home by mutableStateOf(HomeState(content = HomeContent.FirstScan, firstPass = true))

    /** What the screen did: the settings opened, « Aller à l'accueil », a fix. */
    private val did = mutableListOf<String>()

    private val actions = HomeActions(onOpenSettings = { did += "settings" }, onGoHome = { did += "home" })

    private val button get() = compose.onNodeWithContentDescription("Go to Home. The scan continues in the background")

    private val tile get() = compose.onNodeWithContentDescription("Settings")

    private val key get() = compose.onNode(hasText("Change the key") and hasClickAction())

    /** Home on its first scan, as the app gives it its fixes. */
    @Composable
    private fun Screen() = CompositionLocalProvider(LocalErrorFix provides { did += "fix $it" }) {
        HomeGrid(home, scan, actions, firstScan = shows)
    }

    private fun open() {
        compose.setTvContent { Screen() }
        compose.waitForIdle()
    }

    /** [count] titles found, in one-sheets, so no picture is asked. */
    private fun found(count: Int) {
        val titles = (1..count).map { FirstTitle(it.toLong(), "Film $it", YEAR, null) }
        compose.runOnIdle { shows.value = shows.value.copy(titles = titles, found = count) }
        compose.waitForIdle()
    }

    private fun refused() {
        scan.value = scan.value.copy(tmdbIssue = FailureKind.TMDB_KEY_REFUSED)
    }

    @Test
    fun nothingTakesFocusAsItComesThenTheButtonTakesItAsNothingHasIt() {
        open()
        compose.onNode(isFocused()).assertDoesNotExist()
        button.assertDoesNotExist()
        found(1)
        button.assertIsFocused()
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("home"), did)
    }

    @Test
    fun theButtonLeavesFocusOnTheTile() {
        open()
        tile.requestFocus()
        found(3)
        tile.assertIsFocused()
        button.assertIsNotFocused()
    }

    /** « Modifier la clé » never takes focus as it comes, and keeps it once the viewer placed it there. */
    @Test
    fun theButtonLeavesFocusOnTheKey() {
        refused()
        open()
        key.assertIsNotFocused()
        key.requestFocus()
        found(3)
        key.assertIsFocused()
        button.assertIsNotFocused()
    }

    /** Back from Réglages, the first scan is as it was, the button included, and focus is back on the tile. */
    @Test
    fun comingBackFromSettingsFocusIsOnTheTile() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HouselightsTheme { Screen() } }
        compose.waitForIdle()
        found(3)
        button.assertIsFocused()
        compose.press(Key.DirectionUp)
        tile.assertIsFocused()
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("settings"), did)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        tile.assertIsFocused()
        button.assertIsNotFocused()
    }

    @Test
    fun theArrowsGoBetweenTheTileTheButtonAndTheKey() {
        refused()
        open()
        found(3)
        button.assertIsFocused()
        compose.press(Key.DirectionUp)
        tile.assertIsFocused()
        compose.press(Key.DirectionDown)
        button.assertIsFocused()
        compose.press(Key.DirectionLeft)
        key.assertIsFocused()
        compose.press(Key.DirectionUp)
        button.assertIsFocused()
        compose.press(Key.DirectionLeft)
        compose.press(Key.DirectionRight)
        button.assertIsFocused()
        compose.press(Key.DirectionLeft)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("fix EDIT_TMDB_KEY"), did)
    }

    /** OK cuts to the grid once read, the scan going on: focus is placed once on the case. */
    @Test
    fun atTheCutFocusIsPlacedOnceOnTheCase() {
        open()
        found(3)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("home"), did)
        home = fullHome().copy(firstPass = true)
        compose.waitForIdle()
        compose.onNode(isFocused()).assert(hasText("Resume", substring = true))
    }

    private companion object {
        const val YEAR = 2020
    }
}
