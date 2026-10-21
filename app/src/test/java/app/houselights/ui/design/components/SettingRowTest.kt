package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSize
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** DESIGN.md, Panels, lists and settings: a row is 60 tall on the room and 52 in a sheet, a preview included. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aRowKeepsItsHeightOnTheRoomAndInASheet() {
        compose.setTvContent {
            Column {
                HlNavItem(HlIcons.Folder, "Bibliothèque", "10 titres", selected = true, onFocused = {})
                HlSettingRow("Sur la salle", description = "Une ligne", onClick = {})
            }
            HlSideSheet("Modifier", onDismiss = {}) {
                HlSettingRow("Un nom", description = "Une ligne", onClick = {})
                HlSettingRow("Une affiche", description = "Une ligne", onClick = {}, leading = { RowPreview(null, PreviewShape.Poster) })
                HlSettingRow(
                    "Un logo",
                    description = "Une ligne",
                    onClick = {},
                    leading = { RowPreview(null, PreviewShape.Logo, icon = HlIcons.Logo) },
                )
            }
        }
        compose.onNodeWithText("Bibliothèque").assertHeightIsEqualTo(HlSize.row).assertIsSelected()
        compose.onNodeWithText("Sur la salle").assertHeightIsEqualTo(HlSize.row)
        compose.onNodeWithText("Un nom").assertHeightIsEqualTo(HlSize.sheetRow)
        // A preview keeps the row's height, whatever it previews.
        compose.onNodeWithText("Une affiche").assertHeightIsEqualTo(HlSize.sheetRow)
        compose.onNodeWithText("Un logo").assertHeightIsEqualTo(HlSize.sheetRow)
    }
}
