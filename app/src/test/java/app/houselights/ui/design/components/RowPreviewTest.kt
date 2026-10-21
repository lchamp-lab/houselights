package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSize
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A row's preview is one 16:9 box whatever it previews, so the names after it line up. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class RowPreviewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyPreviewIsTheSameBox() {
        val previews = listOf(
            Triple("poster", PreviewShape.Poster, null),
            Triple("backdrop", PreviewShape.Backdrop, null),
            Triple("logo", PreviewShape.Logo, HlIcons.Logo),
            Triple("identify", PreviewShape.Backdrop, HlIcons.Search),
        )
        compose.setTvContent {
            Column { previews.forEach { (tag, shape, icon) -> RowPreview(null, shape, Modifier.testTag(tag), icon = icon) } }
        }
        previews.forEach { (tag, _, _) ->
            val box = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertEquals(tag, HlSize.rowPreview.value, box.width.value, 0.5f)
            assertEquals(tag, HlSize.rowPreview.value / HlSize.PREVIEW_RATIO, box.height.value, 0.5f)
        }
    }
}
