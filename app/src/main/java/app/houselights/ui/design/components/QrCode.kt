package app.houselights.ui.design.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.QrCodeDefaults

/**
 * A QR code for [address], then the address for typing it; [code] is a `qrencode` vector of the dark
 * modules, drawn black on projector white (readers expect dark on light) with its quiet zone, at whole px.
 * [compact] beside words in a pane: smaller modules, the address centred under it in secondary `jaquette`.
 * [small]: the smaller modules alone, the address kept, in a sheet that says more under the code.
 */
@Composable
fun QrCode(@DrawableRes code: Int, address: String, modifier: Modifier = Modifier, compact: Boolean = false, small: Boolean = compact) {
    val modules = ImageVector.vectorResource(code)
    val ink = Hl.colors.background
    QrFrame(modules.viewportWidth.toInt(), address, modifier, compact, small) { frame ->
        Image(
            rememberVectorPainter(modules),
            contentDescription = stringResource(R.string.qr_code),
            modifier = frame,
            colorFilter = ColorFilter.tint(ink),
        )
    }
}

/**
 * A QR code holding [data], encoded here ([QrModules]) for what is known only at run time, such as an address on the
 * home network; otherwise as the drawn one.
 */
@Composable
fun QrCode(data: String, address: String, modifier: Modifier = Modifier, compact: Boolean = false, small: Boolean = compact) {
    val modules = remember(data) { QrModules.encode(data) }
    val ink = Hl.colors.background
    val description = stringResource(R.string.qr_code)
    QrFrame(modules.size, address, modifier, compact, small) { frame ->
        Spacer(
            frame
                .semantics {
                    contentDescription = description
                    role = Role.Image
                }
                .drawWithCache {
                    val path = darkPath(modules, size.width / modules.size)
                    onDrawBehind { drawPath(path, ink) }
                },
        )
    }
}

/** Each row's runs of dark modules, [module] px square, as one path. */
private fun darkPath(modules: QrModules, module: Float): Path {
    val path = Path()
    for (row in 0 until modules.size) {
        var col = 0
        while (col < modules.size) {
            val start = col
            while (col < modules.size && modules[row, col]) col++
            if (col > start) path.addRect(Rect(Offset(start * module, row * module), Size((col - start) * module, module)))
            col++
        }
    }
    return path
}

/** The code's light plate around [modulesAcross] modules and its quiet zone, then the address under it. */
@Composable
private fun QrFrame(
    modulesAcross: Int,
    address: String,
    modifier: Modifier,
    compact: Boolean,
    small: Boolean,
    code: @Composable (Modifier) -> Unit,
) {
    val c = Hl.colors
    val module: Dp = if (small) QrCodeDefaults.moduleCompact else QrCodeDefaults.module
    val quietZone = module * QrCodeDefaults.QUIET_ZONE
    Column(
        modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        code(
            Modifier
                .size(module * modulesAcross + quietZone * 2)
                .clip(if (compact) HlShapes.control else HlShapes.artwork)
                .background(c.content)
                .padding(quietZone),
        )
        Spacer(Modifier.height(HlSpace.s))
        if (compact) {
            Text(address, style = Hl.type.jaquette, color = c.contentSecondary)
        } else {
            Text(address, style = Hl.type.spec, color = c.content)
        }
    }
}
