package app.houselights.ui.design

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
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
@Config(application = Application::class, qualifiers = TV)
class TextRoomTest {
    @get:Rule
    val compose = createComposeRule()

    private class Rooms(val heroMin: Dp, val infoLine: Dp, val labelRoom: Dp)

    private fun roomsAt(fontScale: Float): Rooms {
        lateinit var rooms: Rooms
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                HouselightsTheme {
                    rooms = Rooms(HlLayout.detailHeroMin(540.dp), HlLayout.infoLine, HlIconButtonDefaults.labelRoom)
                }
            }
        }
        compose.waitForIdle()
        return rooms
    }

    @Test
    fun theRoomOfALineGrowsWithTheTextSize() {
        val large = roomsAt(1.3f)
        val growth = { style: TextStyle -> with(Density(1f, 1.3f)) { style.lineHeight.toDp().value } - style.lineHeight.value }
        assertTrue(growth(HouselightsType.lead) > 0f)
        assertEquals(454 - growth(HouselightsType.lead), large.heroMin.value, 0.01f)
        assertEquals(with(Density(1f, 1.3f)) { 24.sp.toDp().value }, large.infoLine.value, 0.01f)
        assertEquals(36 + growth(HouselightsType.caption), large.labelRoom.value, 0.01f)
    }

    @Test
    fun atTheDefaultTextSizeTheRoomIsWhatItWas() {
        val default = roomsAt(1f)
        assertEquals(454f, default.heroMin.value, 0.01f)
        assertEquals(24f, default.infoLine.value, 0.01f)
        assertEquals(36f, default.labelRoom.value, 0.01f)
    }
}
