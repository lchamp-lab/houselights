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
 */
@Composable
fun QrCode(@DrawableRes code: Int, address: String, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val modules = ImageVector.vectorResource(code)
    val quietZone = QrCodeDefaults.module * QrCodeDefaults.QUIET_ZONE
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Image(
            rememberVectorPainter(modules),
            contentDescription = stringResource(R.string.qr_code),
            modifier = Modifier
                .size(QrCodeDefaults.module * modules.viewportWidth + quietZone * 2)
                .clip(HlShapes.artwork)
                .background(c.content)
                .padding(quietZone),
            colorFilter = ColorFilter.tint(c.background),
        )
        Spacer(Modifier.height(HlSpace.s))
        Text(address, style = Hl.type.spec, color = c.content)
    }
}
