package app.houselights.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import app.houselights.ui.util.screenWidthPx

/** Entry point to the design tokens: `Hl.colors`, `Hl.type`. */
object Hl {
    val colors: HlColors
        @Composable @ReadOnlyComposable get() = LocalHlColors.current

    val type: HlType
        @Composable @ReadOnlyComposable get() = LocalHlType.current
}

/** The design system on the canvas, its text at the system's size times [textStep] (« Taille du texte », [textScaleOf]). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HouselightsTheme(textStep: Float = 1f, content: @Composable () -> Unit) {
    val c = HouselightsColors
    // tv-material's defaults in the system's colours; its Text and Icon take the content colour set below.
    val scheme = darkColorScheme(
        primary = c.focus,
        onPrimary = c.onFocus,
        background = c.background,
        onBackground = c.content,
        surface = c.glass,
        onSurface = c.content,
        surfaceVariant = c.glass,
        onSurfaceVariant = c.contentSecondary,
        border = c.focus,
    )
    CompositionLocalProvider(LocalDensity provides canvasDensity(textStep)) {
        CompositionLocalProvider(
            LocalHlColors provides c,
            LocalHlType provides HouselightsType,
            LocalBringIntoViewSpec provides rememberListScroll(),
        ) {
            MaterialTheme(colorScheme = scheme, typography = materialTypography) {
                // Unstyled text takes the reading style, never tv-material's default.
                CompositionLocalProvider(
                    LocalContentColor provides c.content,
                    LocalTextStyle provides HouselightsType.body,
                    content = content,
                )
            }
        }
    }
}

/** tv-material's type roles on the scale, so none of its defaults falls back to another face or size. */
internal val materialTypography = with(HouselightsType) {
    Typography(
        displayLarge = display, displayMedium = headline, displaySmall = subject,
        headlineLarge = headline, headlineMedium = subject, headlineSmall = rowTitle,
        titleLarge = lead, titleMedium = entry, titleSmall = entry,
        bodyLarge = body, bodyMedium = meta, bodySmall = meta,
        labelLarge = entry, labelMedium = group, labelSmall = badge,
    )
}

/** Density that fits the canvas ([HlLayout.CANVAS_WIDTH] dp) to the real screen width, at the system's text size times [step]. */
@Composable
private fun canvasDensity(step: Float): Density {
    val width = screenWidthPx()
    val textScale = textScaleOf(LocalDensity.current.fontScale, step)
    return remember(width, textScale) { canvasDensityOf(width, textScale) }
}

/**
 * The density that lays the canvas across a screen [widthPx] wide at [textScale] ([textScaleOf]), outside a
 * composition too. Compose follows Android's curve from 1.03: the smaller sizes grow most, 30 sp and up not at all.
 */
fun canvasDensityOf(widthPx: Int, textScale: Float = 1f): Density = Density(widthPx / HlLayout.CANVAS_WIDTH, textScale)

/**
 * The text size every screen, sheet, menu and preload is laid out at: the system's ([system]) times the app's [step]
 * (« Taille du texte »), within [TEXT_SCALE_MIN]..[TEXT_SCALE_MAX].
 */
fun textScaleOf(system: Float, step: Float = 1f): Float = (system * step).coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)

/** 1.5 px a sp at 1080p, the Zidoo menus' own: reached only with Android set under 1 and Compacte. */
const val TEXT_SCALE_MIN = 0.75f

/** Android's largest text size on these players, which the layouts are checked at: Grande adds nothing past it. */
const val TEXT_SCALE_MAX = 1.3f
