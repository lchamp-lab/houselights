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
import androidx.tv.material3.MaterialTheme
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
    // tv-material components (Surface, Button) read this scheme for their defaults.
    val scheme = darkColorScheme(
        primary = c.focus,
        onPrimary = c.onFocus,
        background = c.background,
        onBackground = c.content,
        surface = c.surface,
        onSurface = c.content,
        surfaceVariant = c.surfaceRaised,
        onSurfaceVariant = c.contentSecondary,
        border = c.focus,
    )
    CompositionLocalProvider(LocalDensity provides canvasDensity()) {
        CompositionLocalProvider(
            LocalHlColors provides c,
            LocalHlType provides HouselightsType,
            LocalBringIntoViewSpec provides rememberListScroll(),
        ) {
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalContentColor provides c.content, content = content)
            }
        }
    }
}

/**
 * The density that lays the canvas ([HlLayout.CANVAS_WIDTH] dp) across the screen's real width.
 * The system's text scale stays: it is the viewer's choice.
 */
@Composable
private fun canvasDensity(): Density {
    val width = screenWidthPx()
    val fontScale = LocalDensity.current.fontScale
    return remember(width, fontScale) { Density(width / HlLayout.CANVAS_WIDTH, fontScale) }
}
