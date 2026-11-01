package app.houselights.ui.catalog

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.DpSize
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Gradation
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HlStroke
import app.houselights.ui.design.components.Lockup
import kotlin.math.roundToInt

/** Gradation as Android draws it before the app runs (icon, banner, splash), then on screen: the lockup. */
internal fun LazyListScope.catalogBrand() {
    item {
        Group("Brand: Gradation, the icon and the banner", still = true) {
            BrandSample(
                "Three bars whose widths halve, 4:2:1, as the house lights go down one stop at a time, in one " +
                    "ember gradient, orange to raspberry; the fourth step is the black, where the name appears, in " +
                    "white. On the banner, a title card: the mark above the name.",
                icon = R.mipmap.ic_launcher,
                banner = R.drawable.banner,
            )
        }
    }
    item {
        Group("Brand: the launch mark, as Android 9 draws it, in the circle it shows", still = true) {
            LaunchMark(R.drawable.splash_mark)
        }
    }
    item {
        Group("Brand: the lockup, Gradation in its light beside the name, white and flat", still = true) {
            // The mark hangs past the name's box: a wider gap keeps it off the caption.
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Bottom) {
                Specimen("over the welcome wall", gap = HlSpace.m) { Lockup(HlSize.wordmark) }
                Specimen("in About", gap = HlSpace.m) { Lockup(HlSize.wordmark) }
            }
        }
    }
}

/** The launcher icon as a launcher draws it: an adaptive icon, cut by this device's mask. */
private fun launcherIcon(context: Context, @DrawableRes icon: Int, foreground: Boolean): Drawable? {
    val drawable = context.getDrawable(icon) ?: return null
    return if (foreground) AdaptiveIconDrawable(context.getColor(R.color.room).toDrawable(), drawable) else drawable
}

/** How much closer the second view of each drawable is. */
private const val CLOSER = 2

/** Android TV lays a banner out at 320 × 180 px at xhdpi: 160 × 90 dp of the device. */
private const val TV_BANNER_WIDTH = 160f
private const val TV_BANNER_HEIGHT = 90f

/** Android 9's launch screen lays the mark's layer out 288 dp of the device wide. */
private const val LAUNCH_ICON = 288f

/** The icon on three launcher backgrounds and the banner, each at launcher size, then closer. */
@Composable
private fun BrandSample(says: String, @DrawableRes icon: Int, @DrawableRes banner: Int, foreground: Boolean = false) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val density = LocalDensity.current
    val iconDrawable = remember(icon) { launcherIcon(context, icon, foreground) } ?: return
    val bannerDrawable = remember(banner) { ResourcesCompat.getDrawable(resources, banner, context.theme) } ?: return
    // Sizes in this device's pixels, as the launcher draws them; the catalogue's own density differs.
    val iconPx = resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
    val deviceDensity = resources.displayMetrics.density
    val iconSize = with(density) { DpSize(iconPx.toDp(), iconPx.toDp()) }
    val bannerSize = with(density) { DpSize((TV_BANNER_WIDTH * deviceDensity).toDp(), (TV_BANNER_HEIGHT * deviceDensity).toDp()) }
    val c = Hl.colors
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        Text(says, style = Hl.type.body, color = c.contentSecondary, modifier = Modifier.widthIn(max = HlLayout.readable))
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Bottom) {
            Specimen("launcher, on black", gap = HlSpace.xs) { OnGround(c.background) { DrawableBox(iconDrawable, iconSize) } }
            Specimen("on grey", gap = HlSpace.xs) { OnGround(c.contentTertiary) { DrawableBox(iconDrawable, iconSize) } }
            Specimen("on light", gap = HlSpace.xs) { OnGround(c.content) { DrawableBox(iconDrawable, iconSize) } }
            Specimen("closer", gap = HlSpace.xs) { OnGround(c.background) { DrawableBox(iconDrawable, iconSize * CLOSER) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Bottom) {
            Specimen("banner, at launcher size", gap = HlSpace.xs) { DrawableBox(bannerDrawable, bannerSize) }
            Specimen("closer", gap = HlSpace.xs) { DrawableBox(bannerDrawable, bannerSize * CLOSER) }
        }
    }
}

/** The [launch] mark at Android 9's layer width on this device, with the circle it shows as a hairline. */
@Composable
private fun LaunchMark(@DrawableRes launch: Int) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val drawable = remember(launch) { ResourcesCompat.getDrawable(resources, launch, context.theme) } ?: return
    val size = with(LocalDensity.current) { (LAUNCH_ICON * resources.displayMetrics.density).toDp() }
    val ring = Hl.colors.contentDecorative
    Box(Modifier.background(Hl.colors.background)) {
        DrawableBox(drawable, DpSize(size, size))
        Canvas(Modifier.size(size)) {
            val radius = this.size.width / 2 * Gradation.LAUNCH_CIRCLE / Gradation.LAYER
            drawCircle(ring, radius, style = Stroke(HlStroke.hair.toPx()))
        }
    }
}

/** A launcher's background around an icon. */
@Composable
private fun OnGround(ground: Color, content: @Composable () -> Unit) {
    Box(Modifier.background(ground).padding(HlSpace.s)) { content() }
}

/** An Android drawable drawn by Android, at the size given. */
@Composable
private fun DrawableBox(drawable: Drawable, size: DpSize) {
    Canvas(Modifier.size(size)) {
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, this.size.width.roundToInt(), this.size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}
