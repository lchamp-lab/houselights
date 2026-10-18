package app.houselights.ui.home

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SectionType
import app.houselights.providers.local.ScanState
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Focus: down from home's bar, the tabs or the settings button, focus goes into the
 * first row the same way, on the card it last had, else on its first; never on the card that
 * happens to lie under the settings button, at the far end of the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = TV)
class HomeBarFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private fun film(n: Int) = MediaItem(id = "film$n", kind = MediaKind.MOVIE, title = "Film $n", year = 2000 + n)

    private val home = HomeState(
        libraries = listOf(Library("movies", LibraryKind.MOVIES, null), Library("shows", LibraryKind.SHOWS, null)),
        content = HomeContent.Rows(
            listOf(
                HomeSection(SectionType.LATEST, (1..12).map(::film), landscape = false, libraryId = "movies"),
                HomeSection(SectionType.LATEST, (20..32).map(::film), landscape = false, libraryId = "shows"),
            ),
            firstScan = false,
        ),
    )

    private val restoration = StateRestorationTester(compose)

    private fun open() = restoration.setContent {
        HouselightsTheme { HomeBands(home, MutableStateFlow(ScanState()), {}, { _, _ -> }, {}, {}, {}, {}) }
    }

    private fun focusIsOn(title: String) = compose.onNode(isFocused()).assert(hasText(title))

    /** Up from the first row to the tabs, then along the bar to the settings button. */
    private fun upToTheSettingsButton() {
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight, times = home.libraries.size + 1)
        compose.onNode(isFocused()).assert(hasContentDescription("Settings"))
    }

    /** Back from the settings: focus comes back to their button, over rows that remember no card. */
    private fun backFromTheSettings() {
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNode(isFocused()).assert(hasContentDescription("Settings"))
    }

    @Test
    fun downFromTheSettingsButtonComesBackToTheCardTheRowHad() {
        open()
        compose.waitForIdle()
        compose.press(Key.DirectionRight, times = 2)
        focusIsOn("Film 3")
        upToTheSettingsButton()
        compose.press(Key.DirectionDown)
        focusIsOn("Film 3")
    }

    @Test
    fun downFromTheSettingsButtonGoesWhereDownFromTheTabsGoes() {
        open()
        compose.waitForIdle()
        upToTheSettingsButton()
        backFromTheSettings()
        // From the tabs: the track, on its current tab, then the row under it.
        compose.press(Key.DirectionLeft)
        compose.onNode(isFocused()).assert(hasText("Home"))
        compose.press(Key.DirectionDown)
        focusIsOn("Film 1")

        upToTheSettingsButton()
        backFromTheSettings()
        compose.press(Key.DirectionDown)
        focusIsOn("Film 1")
    }
}
