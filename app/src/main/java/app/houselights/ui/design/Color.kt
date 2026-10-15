package app.houselights.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Semantic colors. The room is pure black, like the masking around a cinema screen;
 * the only light comes from the artwork (see [LocalAmbient]) and a sparse ember accent.
 * The neutrals are neutral, a breath cool: the warmth belongs to the films and to the ember.
 * Depth is one ramp of them: the room, [surface], [surfaceRaised], then white fills by `HlAlpha`.
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
     * The quietest text: 5.56:1 on [surfaceRaised], but never on `CONTROL_FILL_STRONG` (see
     * ContrastTest).
     */
    val contentTertiary: Color,
    /** Never for text: dividers, empty thumbnails, inactive marks. */
    val contentDecorative: Color,
    /**
     * The house light. States only: in use, your choice, needs attention; the wordmark is the one
     * exception. Never focus, a place, a fact or a series of data. Screens reach it through the
     * components' states (`HlStatus`, `AttentionNote`, `lit`, `valueLit`, `detailLit`), never by name.
     */
    val ember: Color,
    /**
     * The house light where it has room to show as light, not ink: from [emberFrom] to
     * [emberTo] along the wordmark, a progress line, a rule, a lit edge ([emberLight]). What
     * is too small for a gradient to read (a dot, a check, a word) takes [ember], drawn from it.
     */
    val emberFrom: Color,
    val emberTo: Color,
    /** Focus outline and filled focused controls. */
    val focus: Color,
    /** Content drawn on [focus]. */
    val onFocus: Color,
    /** The line under a name in a focused control (a summary, a runtime): black at 62 % on [focus]. */
    val onFocusSecondary: Color,
)

val HouselightsColors = HlColors(
    background = Color(0xFF000000),
    surface = Color(0xFF0C0C0E),
    surfaceRaised = Color(0xFF131316),
    outline = Color(0xFF33333A),
    content = Color(0xFFF4F4F5),
    contentSecondary = Color(0xFFA3A3AB),
    contentTertiary = Color(0xFF8C8C95),
    contentDecorative = Color(0xFF45454C),
    ember = Color(0xFFFF7A4D),
    emberFrom = Color(0xFFFF9F1C),
    emberTo = Color(0xFFFF2E63),
    focus = Color(0xFFF4F4F5),
    onFocus = Color(0xFF000000),
    onFocusSecondary = Color(0xFF5D5D5D),
)

val LocalHlColors = staticCompositionLocalOf { HouselightsColors }

/** The house light as a gradient across what it fills, from start to end; at [alpha] for an edge. */
fun HlColors.emberLight(alpha: Float = 1f): Brush =
    Brush.horizontalGradient(listOf(emberFrom.copy(alpha = alpha), emberTo.copy(alpha = alpha)))

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
     * At 80 %, the raspberry end of a progress line keeps 3.5:1 over a white image.
     */
    const val PLATE = 0.8f

    /**
     * The steps of the black-level chart in the catalogue: `content` at each opacity on the room.
     * On a TV that shows them all apart, a fill on the room reads; where the first ones merge into
     * black, the TV crushes its blacks.
     */
    val BLACK_LEVELS = listOf(0.03f, 0.06f, 0.09f, 0.12f, 0.15f, 0.2f)
    /** Progress track beside text, off the artwork (a hero, a pane): `content` at this opacity. */
    const val TRACK = 0.22f
    /** Focus on an option of a track that is not the current one: `content` at this opacity. */
    const val FOCUS_QUIET = 0.3f
    /** A lit icon disc: `ember` at this opacity. */
    const val LIT_DISC = 0.18f
}

/**
 * Light spilling from the current artwork; animated by the screen that owns the backdrop. A
 * function, so that what draws with it reads it while drawing: the light changing over 600 ms
 * never recomposes the screen. Without artwork there is no light: transparent, the room stays black.
 */
val LocalAmbient = staticCompositionLocalOf<() -> Color> { { Color.Transparent } }
