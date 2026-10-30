package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.tv.material3.Text
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The marks lie on one plate ([MarkPlate]), at their corner of the picture ([markCorner]): square, or around a count's number. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class MarkPlateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aMarkSitsSquareInItsCorner() {
        compose.setTvContent {
            Box(Modifier.size(PICTURE.dp)) {
                CheckMark(markCorner().testTag(CHECK))
                HeldMark(Modifier.testTag(HELD))
            }
        }
        compose.onNodeWithTag(CHECK)
            .assertWidthIsEqualTo(HlSize.mark)
            .assertHeightIsEqualTo(HlSize.mark)
            .assertLeftPositionInRootIsEqualTo(PICTURE.dp - HlSpace.xs - HlSize.mark)
            .assertTopPositionInRootIsEqualTo(HlSpace.xs)
        compose.onNodeWithTag(HELD, useUnmergedTree = true).assertWidthIsEqualTo(HlSize.mark).assertHeightIsEqualTo(HlSize.mark)
    }

    /**
     * A count's plate holds its number [HlSpace.xs] in across and [HlControl.badgePaddingV] down, inside its veil: the
     * plate's background covers the number and its padding.
     */
    @Test
    fun aCountsPlateHoldsItsNumberInItsVeil() {
        val show = MediaItem("s1", MediaKind.SERIES, "MobLand", user = UserState(unplayedCount = 6))
        compose.setTvContent {
            Column {
                Box(Modifier.testTag(COUNT)) { StatusMark(show) }
                Text("6", style = Hl.type.badge, modifier = Modifier.testTag(NUMBER))
            }
        }
        val number = compose.onNodeWithTag(NUMBER).getUnclippedBoundsInRoot()
        val width = number.width + HlSpace.xs * 2
        val height = number.height + HlControl.badgePaddingV * 2
        compose.onNodeWithTag(COUNT).assertWidthIsEqualTo(width).assertHeightIsEqualTo(height)
        val plate = compose.onNodeWithTag(COUNT, useUnmergedTree = true).onChild().fetchSemanticsNode()
        val veil = plate.layoutInfo.getModifierInfo().first { "Background" in it.modifier.javaClass.simpleName }
        val expected = with(compose.density) { IntSize(width.roundToPx(), height.roundToPx()) }
        assertEquals("the veil under the number and its padding", expected, veil.coordinates.size)
    }

    private companion object {
        const val PICTURE = 120
        const val CHECK = "check"
        const val HELD = "held"
        const val COUNT = "count"
        const val NUMBER = "number"
    }
}
