package app.houselights.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colors. The room is pure black, like the masking around a cinema screen;
 * the only light comes from the artwork (see [LocalAmbient]) and a sparse tungsten accent.
 */
@Immutable
data class HlColors(
    /** Screen masking: the base of every screen. */
    val background: Color,
    /** Placeholders, text fields, quiet containers. */
    val surface: Color,
    /** Focused fields, panels above [surface]. */
    val surfaceRaised: Color,
    /** Hairlines and outlined badges. */
    val outline: Color,
    /** Primary text: projector white. */
    val content: Color,
    val contentSecondary: Color,
    val contentTertiary: Color,
    /** House-light accent. Only for progress, the active tab and the wordmark. */
    val tungsten: Color,
    /** Focus outline and filled focused controls. */
    val focus: Color,
    /** Content drawn on [focus]. */
    val onFocus: Color,
    /** Light spill used when a title has no artwork. */
    val ambientFallback: Color,
)

val HouselightsColors = HlColors(
    background = Color(0xFF000000),
    surface = Color(0xFF121110),
    surfaceRaised = Color(0xFF1D1B19),
    outline = Color(0xFF3A3632),
    content = Color(0xFFF2F0EB),
    contentSecondary = Color(0xFFA39E96),
    contentTertiary = Color(0xFF6B6660),
    tungsten = Color(0xFFF2B66D),
    focus = Color(0xFFF2F0EB),
    onFocus = Color(0xFF000000),
    ambientFallback = Color(0xFF3B2B1C),
)

val LocalHlColors = staticCompositionLocalOf { HouselightsColors }

/** Light spilling from the current artwork; animated by the screen that owns the backdrop. */
val LocalAmbient = staticCompositionLocalOf { HouselightsColors.ambientFallback }
