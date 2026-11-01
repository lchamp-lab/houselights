package app.houselights.ui.design.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.tv.material3.Text
import app.houselights.ui.design.DeviceCodeDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import kotlin.math.max

/**
 * A code to type elsewhere (Trakt's), on a plate: [instructions] in secondary `meta`, the [code] in groups of four in
 * `codeSignature`, projector white, never lit, then the time it has [left]; the [qr] code of [address] at the plate's end.
 * The code keeps its own width: where it does not fit beside the QR code, it goes under both. Before the code comes,
 * [asking] says it is on its way. It holds focus without showing it, so Back cancels.
 */
@Composable
fun DeviceCodePlate(
    code: String?,
    instructions: String,
    @DrawableRes qr: Int,
    address: String,
    modifier: Modifier = Modifier,
    left: String? = null,
    asking: String? = null,
) {
    val c = Hl.colors
    HlPlate(modifier.fillMaxWidth().focusable(), padding = PaddingValues(DeviceCodeDefaults.inset)) {
        Layout(
            contents = listOf(
                { Text(instructions, style = Hl.type.body, color = c.contentSecondary) },
                {
                    if (code != null) {
                        Text(
                            code.chunked(CODE_GROUP).joinToString(" "),
                            style = Hl.type.codeSignature,
                            color = c.content,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.paddingFromBaseline(top = HlSpace.xxl + HlSpace.xl),
                        )
                    }
                },
                {
                    when {
                        code != null && left != null -> Text(
                            left,
                            style = Hl.type.meta,
                            color = c.contentSecondary,
                            modifier = Modifier.paddingFromBaseline(top = HlSpace.xl),
                        )
                        code == null && asking != null -> HlStatus(StatusState.Pending, asking, Modifier.padding(top = HlSpace.l))
                    }
                },
                { QrCode(qr, address, compact = true) },
            ),
        ) { parts, constraints ->
            val (words, codes, after) = parts
            val qrs = parts[QR]
            val width = constraints.maxWidth
            val image = qrs.first().measure(Constraints())
            // As near the QR code as the board sets it, so most codes stay beside it.
            val beside = (width - image.width - HlSpace.xs.roundToPx()).coerceAtLeast(0)
            val text = words.first().measure(Constraints(maxWidth = beside))
            val typed = codes.firstOrNull()?.measure(Constraints(maxWidth = width))
            // Measured whole: a code that does not fit beside the QR code goes under it, never cut.
            val fits = (typed?.width ?: 0) <= beside
            val room = if (fits) beside else width
            val then = after.firstOrNull()?.measure(Constraints(maxWidth = room))
            val top = if (fits) text.height else max(text.height, image.height)
            val codeHeight = typed?.height ?: 0
            val height = max(top + codeHeight + (then?.height ?: 0), image.height)
            layout(width, height) {
                text.placeRelative(0, 0)
                image.placeRelative(width - image.width, 0)
                typed?.placeRelative(0, top)
                then?.placeRelative(0, top + codeHeight)
            }
        }
    }
}

/** Letters in each of a code's groups. */
private const val CODE_GROUP = 4

/** The QR code's place among the plate's parts. */
private const val QR = 3
