package app.houselights.ui.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.houselights.ui.testing.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Heights holding a line of text grow with the text size as much as the line (non-linearly from Android 14). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class TextRoomTest {
    @get:Rule
    val compose = createComposeRule()

    private class Rooms(val heroMin: Dp, val infoLine: Dp, val labelRoom: Dp)

    @Test
    fun theRoomOfALineGrowsWithTheTextSize() {
        val rooms = mutableMapOf<Float, Rooms>()
        compose.setContent {
            listOf(1f, LARGEST).forEach { scale ->
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    HouselightsTheme {
                        rooms[scale] = Rooms(HlLayout.detailHeroMin(540.dp), HlLayout.infoLine, HlIconButtonDefaults.labelRoom)
                    }
                }
            }
        }
        compose.waitForIdle()
        val default = rooms.getValue(1f)
        val large = rooms.getValue(LARGEST)
        // How much taller a line of [height] at the default text size is at the largest.
        fun growth(height: Float) = with(Density(1f, LARGEST)) { height.sp.toDp().value } - height
        val header = growth(HouselightsType.lead.lineHeight.value)
        assertTrue(header > 0f)
        assertEquals("the hero makes room for its header", default.heroMin.value - header, large.heroMin.value, 0.01f)
        assertEquals("a poster wall's title line", default.infoLine.value + growth(default.infoLine.value), large.infoLine.value, 0.01f)
        val label = growth(HouselightsType.caption.lineHeight.value)
        assertEquals("a button's label", default.labelRoom.value + label, large.labelRoom.value, 0.01f)
    }

    private companion object {
        /** Android's largest text size. */
        const val LARGEST = 1.3f
    }
}
