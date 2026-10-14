package app.houselights.ui.catalog

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import kotlin.math.roundToInt

/**
 * The launcher icon and the TV banner: what the app ships today, then the directions proposed to
 * replace it, side by side. Drawn by Android as a launcher draws them (the icon cut by this
 * device's mask), at the size a launcher shows them on this screen, then twice as close.
 */
internal fun LazyListScope.catalogBrand() {
    item {
        Group("Brand: the icon and banner shipped today", still = true) {
            BrandSample(
                "A lamp and its beam in a gradient; a white wordmark under a warm halo, in pixels.",
                icon = R.mipmap.ic_launcher,
                banner = R.drawable.banner,
            )
        }
    }
    brandDirections.forEach { direction ->
        item {
            Group("Brand, proposal: ${direction.name}", still = true) {
                BrandSample(direction.says, direction.foreground, direction.banner, foreground = true)
            }
        }
    }
}

/** A direction for the icon and the banner, as its two drawables. */
private class BrandDirection(val name: String, val says: String, @DrawableRes val foreground: Int, @DrawableRes val banner: Int)

private val brandDirections = listOf(
    BrandDirection(
        "Monogram",
        "An H set like a film’s title, condensed Bold, alone in the room. " +
            "The banner sets it as a large initial, the wordmark on its baseline.",
        R.drawable.brand_icon_monogram_foreground,
        R.drawable.brand_banner_monogram,
    ),
    BrandDirection(
        "Wordmark",
        "The name as the welcome screen sets it: on two lines in the icon, like a title on a poster; " +
            "at the foot of a dark room on the banner.",
        R.drawable.brand_icon_wordmark_foreground,
        R.drawable.brand_banner_wordmark,
    ),
    BrandDirection(
        "Your seat",
        "The room from above: the screen lit, the house dark, one seat lit in tungsten, yours. " +
            "On the banner, the name is on the screen.",
        R.drawable.brand_icon_seat_foreground,
        R.drawable.brand_banner_seat,
    ),
    BrandDirection(
        "Three steps",
        "The house lights going down in three steps, and the house rising in tiers. " +
            "On the banner, the sign stands a capital high before the name.",
        R.drawable.brand_icon_steps_foreground,
        R.drawable.brand_banner_steps,
    ),
)

/** The launcher icon as a launcher draws it: an adaptive icon, cut by this device's mask. */
private fun launcherIcon(context: Context, @DrawableRes icon: Int, foreground: Boolean): Drawable? {
    val drawable = context.getDrawable(icon) ?: return null
    return if (foreground) AdaptiveIconDrawable(ColorDrawable(context.getColor(R.color.room)), drawable) else drawable
}

/** How much closer the second view of each drawable is. */
private const val CLOSER = 2

/** Android TV lays a banner out at 320 × 180 px at xhdpi: 160 × 90 dp of the device. */
private const val TV_BANNER_WIDTH = 160f
private const val TV_BANNER_HEIGHT = 90f

/**
 * The icon on three launcher grounds, and the banner; each at launcher size, then closer. [icon]
 * is an adaptive icon, or its [foreground] alone, set here on the room as the icon's ground.
 */
@Composable
private fun BrandSample(says: String, @DrawableRes icon: Int, @DrawableRes banner: Int, foreground: Boolean = false) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val iconDrawable = remember(icon) { launcherIcon(context, icon, foreground) } ?: return
    val bannerDrawable = remember(banner) { context.getDrawable(banner) } ?: return
    // Sizes in this device's pixels, as the launcher draws them; the catalogue's own density differs.
    val iconPx = context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
    val deviceDensity = context.resources.displayMetrics.density
    val iconSize = with(density) { DpSize(iconPx.toDp(), iconPx.toDp()) }
    val bannerSize = with(density) { DpSize((TV_BANNER_WIDTH * deviceDensity).toDp(), (TV_BANNER_HEIGHT * deviceDensity).toDp()) }
    val c = Hl.colors
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        Text(says, style = Hl.type.body, color = c.contentSecondary, modifier = Modifier.widthIn(max = HlLayout.readable))
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Bottom) {
            BrandCase("launcher, on black") { OnGround(c.background) { DrawableBox(iconDrawable, iconSize) } }
            BrandCase("on grey") { OnGround(c.contentTertiary) { DrawableBox(iconDrawable, iconSize) } }
            BrandCase("on light") { OnGround(c.content) { DrawableBox(iconDrawable, iconSize) } }
            BrandCase("closer") { OnGround(c.background) { DrawableBox(iconDrawable, iconSize * CLOSER) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Bottom) {
            BrandCase("banner, at launcher size") { DrawableBox(bannerDrawable, bannerSize) }
            BrandCase("closer") { DrawableBox(bannerDrawable, bannerSize * CLOSER) }
        }
    }
}

/** One view, named underneath. */
@Composable
private fun BrandCase(name: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
        content()
        Text(name, style = Hl.type.caption, color = Hl.colors.contentTertiary)
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
