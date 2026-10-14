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
    /**
     * The quietest text: 4.76:1 on [surfaceRaised], but never on a `WELL` row inside a raised panel
     * nor on `CONTROL_FILL_STRONG` (see ContrastTest).
     */
    val contentTertiary: Color,
    /** Never for text: dividers, empty thumbnails, inactive marks. */
    val contentDecorative: Color,
    /** House-light accent. Only for progress, the active tab and the wordmark. */
    val tungsten: Color,
    /** Focus outline and filled focused controls. */
    val focus: Color,
    /** Content drawn on [focus]. */
    val onFocus: Color,
    /** The line under a name in a focused control (a summary, a runtime): black at 62 % on [focus]. */
    val onFocusSecondary: Color,
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
    contentTertiary = Color(0xFF8C867E),
    contentDecorative = Color(0xFF4A4642),
    tungsten = Color(0xFFF2B66D),
    focus = Color(0xFFF2F0EB),
    onFocus = Color(0xFF000000),
    onFocusSecondary = Color(0xFF5C5B59),
    ambientFallback = Color(0xFF3B2B1C),
)

val LocalHlColors = staticCompositionLocalOf { HouselightsColors }

/**
 * Opacities, by role. Translucent layers are always one of these, applied to the colour the
 * role names, so the same thing looks the same everywhere.
 */
object HlAlpha {
    /** Quiet panels on the room: `surface` at this opacity. */
    const val QUIET_SURFACE = 0.72f
    /** Unfocused secondary controls: `content` at this opacity. */
    const val CONTROL_FILL = 0.09f
    /** Unfocused primary controls and selected navigation: `content` at this opacity. */
    const val CONTROL_FILL_STRONG = 0.2f
    /** Faint tracks and wells (segmented track, meter track): `content` at this opacity. */
    const val WELL = 0.06f
    /** Panel borders: `outline` at this opacity. */
    const val BORDER = 0.6f
    /** Dividers: `outline` at this opacity. */
    const val DIVIDER = 0.5f
    /** The room behind a dialog: `background` at this opacity. */
    const val SCRIM = 0.72f
    /** The room beside a side sheet, still to be read: `background` at this opacity. */
    const val SCRIM_SIDE = 0.6f
    /** The edge of artwork at rest, so a dark poster keeps its outline on the black room: `content`. */
    const val ARTWORK_EDGE = 0.12f
    /**
     * The plate under whatever sits on artwork, whatever the image: a card's marks and progress
     * track, and the controls laid over a backdrop (the home bar's track and button): `background`.
     */
    const val PLATE = 0.72f
    /** Progress track beside text, off the artwork (a hero, a pane): `content` at this opacity. */
    const val TRACK = 0.22f
    /** Focus on an option of a track that is not the current one: `content` at this opacity. */
    const val FOCUS_QUIET = 0.3f
    /** A lit icon disc: `tungsten` at this opacity. */
    const val LIT_DISC = 0.18f
    /** The glow of the wordmark's own lamp: `tungsten` at this opacity. */
    const val WORDMARK_GLOW = 0.55f
}

/** Light spilling from the current artwork; animated by the screen that owns the backdrop. */
val LocalAmbient = staticCompositionLocalOf { HouselightsColors.ambientFallback }
