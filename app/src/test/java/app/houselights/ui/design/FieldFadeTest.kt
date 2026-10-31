package app.houselights.ui.design

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A form fading into its field ([fadeIntoField]) draws on the Zidoo's Android 9, whose layers are recorded by a view:
 * there, the density read inside a layer's recording referred back to itself, and the first scrolled form overflowed
 * the stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FieldFadeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aListWithMoreAboveAndBelowFadesBothEnds() {
        compose.setTvContent {
            val scroll = rememberScrollState(SCROLLED)
            Box(Modifier.size(WIDTH.dp, HEIGHT.dp).fadeIntoField(scroll, start = 0.dp, end = 0.dp)) {
                Column(Modifier.verticalScroll(scroll)) {
                    repeat(ROWS) { Box(Modifier.fillMaxWidth().height(ROW.dp).background(Color.White)) }
                }
            }
        }
        compose.waitForIdle()

        val shot = compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        val middle = shot.getPixel(WIDTH / 2, HEIGHT / 2)
        assertNotEquals("the rows were not drawn", 0, middle)
        // White rows fading into the black room: the ends darker than the middle.
        listOf("top" to 0, "bottom" to HEIGHT - 1).forEach { (end, y) ->
            val level = android.graphics.Color.red(shot.getPixel(WIDTH / 2, y))
            assertTrue("the $end end did not fade: $level", level < android.graphics.Color.red(middle))
        }
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
        const val ROWS = 12
        const val ROW = 60

        /** Partway down the rows, with more above and below the box. */
        const val SCROLLED = 200
    }
}
