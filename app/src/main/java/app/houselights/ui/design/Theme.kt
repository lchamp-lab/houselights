package app.houselights.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

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
    CompositionLocalProvider(
        LocalHlColors provides c,
        LocalHlType provides HouselightsType,
        LocalBringIntoViewSpec provides HlBringIntoView,
    ) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalContentColor provides c.content, content = content)
        }
    }
}
