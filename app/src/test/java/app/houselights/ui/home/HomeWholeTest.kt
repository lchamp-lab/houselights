package app.houselights.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.LaunchScreen
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.drawWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home is whole for the launch screen once a frame shows its grid with its doors read, or the message it shows instead. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeWholeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The launch screen over home, told once. */
    private class Cover : LaunchScreen {
        override val covers = true
        var told = 0

        override fun drawn() {
            told++
        }
    }

    private val cover = Cover()

    private fun show(home: () -> HomeState) {
        compose.setContent {
            CompositionLocalProvider(LocalLaunchScreen provides cover) {
                HouselightsTheme { HomeGrid(home()) }
            }
        }
        repeat(FRAMES) {
            compose.waitForIdle()
            compose.drawWindow()
        }
    }

    @Test
    fun homeIsWholeOnceItsDoorsAreRead() {
        var home by mutableStateOf(fullHome().copy(doors = null))
        show { home }
        assertEquals("the rows are in, the doors' counts are not: nothing said", 0, cover.told)

        home = fullHome()
        repeat(FRAMES) {
            compose.waitForIdle()
            compose.drawWindow()
        }
        assertTrue("said once the doors are in", cover.told > 0)
    }

    @Test
    fun loadingIsNotWhole() {
        show { HomeState(content = HomeContent.Loading(firstScan = false)) }
        assertEquals(0, cover.told)
    }

    @Test
    fun aMessageIsWhole() {
        show { HomeState(content = HomeContent.ServerEmpty) }
        assertTrue(cover.told > 0)
    }

    private companion object {
        const val FRAMES = 5
    }
}
