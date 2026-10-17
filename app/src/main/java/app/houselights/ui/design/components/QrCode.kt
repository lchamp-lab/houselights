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
 * An address to open on a phone: its QR code, then the [address] as written, for whoever types it.
 * [code] is a vector of the code's dark modules, one unit of its viewport each, as `qrencode` writes
 * them. They are drawn black on projector white, the one light ground at rest, since a reader
 * looks for dark modules on a light one, with the quiet zone readers need around them, at whole
 * pixels ([QrCodeDefaults]); the address under it in the technical voice. Read out as one element:
 * a QR code, and its address.
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
