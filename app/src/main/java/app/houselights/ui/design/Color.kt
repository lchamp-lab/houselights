package app.houselights.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * The colours themselves, named by what they are, not by what they do: a ramp of neutrals, each
 * named by its lightness (`N0` black to `N96` projector white), and the three embers. Only
 * [HouselightsColors] reads them: a screen or a component always says a role.
 */
object HlPalette {
    val N0 = Color(0xFF000000)
    val N8 = Color(0xFF131316)
    val N21 = Color(0xFF33333A)
    val N28 = Color(0xFF45454C)
    val N36 = Color(0xFF5D5D5D)
    val N57 = Color(0xFF8C8C95)
    val N65 = Color(0xFFA3A3AB)
    val N96 = Color(0xFFF4F4F5)

    /** Ember, solid: the middle of its light, for what is too small for a gradient. */
    val Ember = Color(0xFFFF7A4D)
    val EmberStart = Color(0xFFFF9F1C)
    val EmberEnd = Color(0xFFFF2E63)
}

/**
 * Semantic colors. The room is pure black, like the masking around a cinema screen;
 * the only light comes from the artwork (see [LocalAmbient]) and a sparse ember accent.
 * The neutrals are neutral, a breath cool: the warmth belongs to the films and to the ember.
 * Depth is three materials: the room ([background]), glass ([surfaceRaised]: side sheets, menus,
 * dialogs) and fills, `content` at an `HlAlpha.FILL*` opacity over whatever lies below
 * ([LocalGround]); then focus, in projector white.
 */
@Immutable
data class HlColors(
    /** Screen masking: the base of every screen. */
    val background: Color,
    /** Glass: side sheets, menus and dialogs, opaque over the dimmed room. */
    val surfaceRaised: Color,
    /** Outlined badges, and every edge at `HlAlpha.EDGE`. */
    val outline: Color,
    /** Primary text: projector white. */
    val content: Color,
    val contentSecondary: Color,
    /**
     * The quietest text: 5.56:1 on [surfaceRaised], but never on `FILL_STRONG` (see
     * ContrastTest).
     */
    val contentTertiary: Color,
    /** Never for text: the rule of an empty frame, a skeleton. */
    val contentDecorative: Color,
    /**
     * The house light. States only: in use, your choice, needs attention, new; the wordmark is the
     * one exception. Never focus, a place, a fact or a series of data. Screens reach it through the
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
    background = HlPalette.N0,
    surfaceRaised = HlPalette.N8,
    outline = HlPalette.N21,
    content = HlPalette.N96,
    contentSecondary = HlPalette.N65,
    contentTertiary = HlPalette.N57,
    contentDecorative = HlPalette.N28,
    ember = HlPalette.Ember,
    emberFrom = HlPalette.EmberStart,
    emberTo = HlPalette.EmberEnd,
    focus = HlPalette.N96,
    onFocus = HlPalette.N0,
    onFocusSecondary = HlPalette.N36,
)

val LocalHlColors = staticCompositionLocalOf { HouselightsColors }

/** The house light as a gradient across what it fills, from start to end; at [alpha] for a fill. */
fun HlColors.emberLight(alpha: Float = 1f): Brush =
    Brush.horizontalGradient(listOf(emberFrom.copy(alpha = alpha), emberTo.copy(alpha = alpha)))

/**
 * What a component lies on, which decides how it stands out: the black [Room]; [Glass], the
 * raised surface of a sheet, a menu or a dialog, already a step up; or [Artwork], a picture of
 * any lightness, where what is laid must take a dark plate to read. Sheets and raised panels
 * provide Glass, artwork surfaces and [OnArtwork] provide Artwork; a component reads it rather
 * than being told.
 */
enum class Ground { Room, Glass, Artwork }

val LocalGround = staticCompositionLocalOf { Ground.Room }

/** Lays [content] on a picture: what it holds takes its plate ([groundPlate]) and reads over any image. */
@Composable
fun OnArtwork(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGround provides Ground.Artwork, content = content)
}

/**
 * The ground's own colour, opaque: what must hide what is under it (the hint at the end of a
 * field) or fade into it (the edges of a scrolling area) reads it. A picture's is the room's black,
 * the black of its plate.
 */
@Composable
@ReadOnlyComposable
fun groundColor(): Color = if (LocalGround.current == Ground.Glass) Hl.colors.surfaceRaised else Hl.colors.background

/**
 * The fill of a thing on its ground, one step up: `FILL` on the room, a step a TV still shows;
 * `FILL_FAINT` on glass, which is already a step up (and keeps the quietest text at 4.5:1 there).
 */
@Composable
@ReadOnlyComposable
fun groundStep(): Float = if (LocalGround.current == Ground.Glass) HlAlpha.FILL_FAINT else HlAlpha.FILL

/** `content` at [alpha] laid on the ground, opaque: a fill as it shows. */
@Composable
@ReadOnlyComposable
fun groundFill(alpha: Float): Color = Hl.colors.content.copy(alpha = alpha).compositeOver(groundColor())

/**
 * The plate under what sits on a picture: black at `VEIL`, matte, so its contrast no longer
 * depends on the image. Null off artwork, where a fill of `content` stands out instead.
 */
@Composable
@ReadOnlyComposable
fun groundPlate(): Color? = if (LocalGround.current == Ground.Artwork) Hl.colors.background.copy(alpha = HlAlpha.VEIL) else null

/**
 * Opacities, by role, in three families: fills (`content` over whatever lies below), veils
 * (`background` over a picture or the room) and edges. Translucent layers are always one of
 * these, so the same thing looks the same everywhere.
 */
object HlAlpha {
    /** Faint wells: the track of a segmented control or a meter, a row at rest, a disabled control. */
    const val FILL_FAINT = 0.06f
    /**
     * The fill of a thing on its ground: a secondary control at rest, a quiet panel, a field, an
     * empty frame, a placeholder. On the room it is #161616, a step a TV still shows.
     */
    const val FILL = 0.09f
    /**
     * A primary control at rest, a progress line's track off the artwork, a lit disc (in ember),
     * the current option of a track.
     */
    const val FILL_STRONG = 0.2f

    /** The room beside a side sheet, still to be read. */
    const val VEIL_SIDE = 0.6f
    /**
     * The room behind a dialog, and the plate under whatever sits on artwork, whatever the image: a
     * card's marks and progress track, the controls laid over a backdrop. At 80 %, the raspberry end
     * of a progress line keeps 3.5:1 over a white image.
     */
    const val VEIL = 0.8f

    /** Every edge and divider of a container: `outline` at this opacity. */
    const val EDGE = 0.6f
    /** The edge of artwork at rest, so a dark poster keeps its outline on the black room: `content`. */
    const val EDGE_ARTWORK = 0.12f

    /**
     * The steps of the black-level chart in the catalogue: `content` at each opacity on the room.
     * On a TV that shows them all apart, a fill on the room reads; where the first ones merge into
     * black, the TV crushes its blacks.
     */
    val BLACK_LEVELS = listOf(0.03f, 0.06f, 0.09f, 0.12f, 0.15f, 0.2f)
}

/**
 * Light spilling from the current artwork; animated by the screen that owns the backdrop. A
 * function, so that what draws with it reads it while drawing: the light changing over 600 ms
 * never recomposes the screen. Without artwork there is no light: transparent, the room stays black.
 */
val LocalAmbient = staticCompositionLocalOf<() -> Color> { { Color.Transparent } }
