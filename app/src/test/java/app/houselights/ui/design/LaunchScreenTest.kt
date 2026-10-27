package app.houselights.ui.design

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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

/** The launch screen lifts on the first full frame drawn under it, never earlier, nor when it covers nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** A launch screen that counts what it is told. */
    private class Cover(override val covers: Boolean) : LaunchScreen {
        var told = 0

        override fun drawn() {
            told++
        }
    }

    private fun show(cover: Cover, whole: () -> Boolean) = compose.setContent {
        CompositionLocalProvider(LocalLaunchScreen provides cover) {
            Box(Modifier.size(HlSize.icon).drawnWhole(whole()))
        }
    }

    private fun drawAFrame() {
        compose.waitForIdle()
        compose.drawWindow()
    }

    @Test
    fun itIsToldOnceAFrameIsDrawnWhole() {
        val cover = Cover(covers = true)
        var whole by mutableStateOf(false)
        show(cover) { whole }
        drawAFrame()
        assertEquals("still being prepared", 0, cover.told)
        whole = true
        drawAFrame()
        assertTrue("drawn whole", cover.told >= 1)
    }

    @Test
    fun nothingIsToldWhenNothingCovers() {
        val cover = Cover(covers = false)
        show(cover) { true }
        drawAFrame()
        assertEquals(0, cover.told)
    }
}
