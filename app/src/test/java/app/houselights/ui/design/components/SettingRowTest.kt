package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.design.HlGap
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSettingGroupDefaults
import app.houselights.ui.design.HlSize
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Secondary screens: a place is 48 tall, a row 52 in a pane's group as in a sheet, an icon included; a pane's
 * plates follow one another at a plate's gap, a titled group a band from them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aRowKeepsItsHeightInAPaneAndInASheet() {
        compose.setTvContent {
            Column {
                HlNavItem(HlIcons.Folder, "Bibliothèque", selected = true, onFocus = {})
                HlSettingGroup("Groupe") { HlSettingRow("Dans un volet", description = "Une ligne", onClick = {}) }
            }
            HlSideSheet("Modifier", onDismiss = {}) {
                HlSettingGroup(null) {
                    HlSettingRow("Un nom", description = "Une ligne", onClick = {})
                    HlSettingRow("Identifier", description = "Une ligne", icon = HlIcons.Search, onClick = {})
                }
            }
        }
        compose.onNodeWithText("Bibliothèque").assertHeightIsEqualTo(HlSize.navRow).assertIsSelected()
        compose.onNodeWithText("Dans un volet").assertHeightIsEqualTo(HlSize.row)
        compose.onNodeWithText("Un nom").assertHeightIsEqualTo(HlSize.row)
        // An icon keeps the row's height.
        compose.onNodeWithText("Identifier").assertHeightIsEqualTo(HlSize.row)
    }

    @Test
    fun aGroupsRowsLieInItsPlateWithoutAGap() {
        compose.setTvContent {
            HlSettingGroup(null, Modifier.testTag("plate")) {
                HlSettingRow("Première", description = "Une ligne", onClick = {})
                HlSettingRow("Seconde", description = "Une ligne", onClick = {})
            }
        }
        val plate = compose.onNodeWithTag("plate").getUnclippedBoundsInRoot()
        val first = compose.onNodeWithText("Première").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithText("Seconde").getUnclippedBoundsInRoot()
        assertEquals("one after the other", first.bottom.value, second.top.value, 0.5f)
        assertEquals("the group's inset", HlSettingGroupDefaults.inset.value, (first.left - plate.left).value, 0.5f)
        assertEquals("the group's inset", HlSettingGroupDefaults.inset.value, (first.top - plate.top).value, 0.5f)
        assertEquals("the group's inset", HlSettingGroupDefaults.inset.value, (plate.bottom - second.bottom).value, 0.5f)
    }

    @Test
    fun aPanesPlatesStandAPlatesGapApartAndATitledGroupABandFromThem() {
        compose.setTvContent {
            HlPlates {
                HlSettingGroup(null, Modifier.testTag("one")) { HlSettingRow("Une", onClick = {}) }
                HlSettingGroup(null, Modifier.testTag("two")) { HlSettingRow("Deux", onClick = {}) }
                HlSettingGroup("Groupe", Modifier.testTag("three")) { HlSettingRow("Trois", onClick = {}) }
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertEquals("plates without a title", HlGap.plates.value, (bounds("two").top - bounds("one").bottom).value, 0.5f)
        assertEquals("a titled group", HlGap.band.value, (bounds("three").top - bounds("two").bottom).value, 0.5f)
    }
}
