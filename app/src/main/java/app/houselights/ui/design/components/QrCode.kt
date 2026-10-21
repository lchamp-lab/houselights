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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.semantics
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
 */
@Composable
fun QrCode(@DrawableRes code: Int, address: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Hl.colors
    val modules = ImageVector.vectorResource(code)
    val module = if (compact) QrCodeDefaults.moduleCompact else QrCodeDefaults.module
    val quietZone = module * QrCodeDefaults.QUIET_ZONE
    Column(
        modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Image(
            rememberVectorPainter(modules),
            contentDescription = stringResource(R.string.qr_code),
            modifier = Modifier
                .size(module * modules.viewportWidth + quietZone * 2)
                .clip(if (compact) HlShapes.control else HlShapes.artwork)
                .background(c.content)
                .padding(quietZone),
            colorFilter = ColorFilter.tint(c.background),
        )
        Spacer(Modifier.height(HlSpace.s))
        if (compact) {
            Text(address, style = Hl.type.jaquette, color = c.contentSecondary)
        } else {
            Text(address, style = Hl.type.spec, color = c.content)
        }
    }
}
