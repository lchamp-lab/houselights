package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import app.houselights.ui.design.HlModalDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSpace

/**
 * A sheet to read, centred over the dimmed room: details on demand, such as the technical sheet.
 * What it is ([title], in the voice of the poster) and what it is about ([facts]), its content,
 * and the Back hint; no button to close it, Back does. It grows in and fades out. It never leaves
 * the safe area: longer content scrolls, in one area, between the header and the hint, which
 * stay. For acting on something, use [HlSideSheet].
 */
@Composable
fun HlModal(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    facts: List<String?> = emptyList(),
    width: Dp = HlModalDefaults.width,
    initialFocus: FocusRequester? = null,
    content: @Composable ColumnScope.(sheet: SheetScope) -> Unit,
) {
    SheetWindow(onDismiss, HlAlpha.VEIL, initialFocus) { progress, sheet ->
        HlPanel(
            modifier
                .align(Alignment.Center)
                .padding(vertical = HlSpace.safeV)
                .width(width)
                .graphicsLayer {
                    val p = progress()
                    val scale = HlMotion.ENTER_SCALE + (1f - HlMotion.ENTER_SCALE) * p
                    alpha = p
                    scaleX = scale
                    scaleY = scale
                },
            level = PanelLevel.Raised,
            padding = HlInset.sheet,
            shape = HlShapes.sheet,
        ) {
            Text(title, style = Hl.type.subject, color = Hl.colors.content)
            if (facts.any { !it.isNullOrBlank() }) {
                Spacer(Modifier.height(HlSpace.xxs))
                FactRow(facts)
            }
            Spacer(Modifier.height(HlSpace.m))
            HlScrollArea(Modifier.weight(1f, fill = false), framed = false) {
                Column { content(sheet) }
            }
            Spacer(Modifier.height(HlSpace.m))
            BackHint()
        }
    }
}
