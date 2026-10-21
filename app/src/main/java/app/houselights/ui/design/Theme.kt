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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HouselightsTheme(content: @Composable () -> Unit) {
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
    CompositionLocalProvider(LocalDensity provides canvasDensity()) {
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

/** tv-material's type roles in Archivo, so none of its defaults falls back to another face. */
private val materialTypography = with(HouselightsType) {
    Typography(
        displayLarge = display, displayMedium = headline, displaySmall = subject,
        headlineLarge = headline, headlineMedium = subject, headlineSmall = title,
        titleLarge = title, titleMedium = lead, titleSmall = entry,
        bodyLarge = body, bodyMedium = body, bodySmall = caption,
        labelLarge = entry, labelMedium = entry, labelSmall = badge,
    )
}

/** Density that fits the canvas ([HlLayout.CANVAS_WIDTH] dp) to the real screen width; font scale kept. */
@Composable
private fun canvasDensity(): Density {
    val width = screenWidthPx()
    val fontScale = LocalDensity.current.fontScale
    return remember(width, fontScale) { canvasDensityOf(width, fontScale) }
}

/** The density that lays the canvas across a screen [widthPx] wide, outside a composition too. */
fun canvasDensityOf(widthPx: Int, fontScale: Float = 1f): Density = Density(widthPx / HlLayout.CANVAS_WIDTH, fontScale)
