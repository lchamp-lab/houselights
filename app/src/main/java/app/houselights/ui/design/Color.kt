package app.houselights.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Raw colours: neutrals named by lightness (`N0` to `N96`) and embers; only [HouselightsColors] and the catalogue read them. */
object HlPalette {
    val N0 = Color(0xFF000000)
    val N8 = Color(0xFF131316)
    val N21 = Color(0xFF33333A)
    val N28 = Color(0xFF45454C)
    val N36 = Color(0xFF5D5D5D)
    val N57 = Color(0xFF8C8C95)
    val N65 = Color(0xFFA3A3AB)
    val N96 = Color(0xFFF4F4F5)

    /** Solid ember, the gradient's midpoint, for marks too small for a gradient. */
    val Ember = Color(0xFFFF7A4D)
    val EmberStart = Color(0xFFFF9F1C)
    val EmberEnd = Color(0xFFFF2E63)

    /** The dark of a grey picture: a picture's own dark (luminance at most 0.006) without its hue, a touch cool. */
    val Deep = Color(0xFF111113)
}

/** Semantic colour roles (DESIGN.md, Colour): the room, glass, fills over [LocalGround], projector-white focus. */
@Immutable
data class HlColors(
    /** The room: the base of every screen. */
    val background: Color,
    /** Glass: side sheets and menus, opaque over the dimmed room. */
    val glass: Color,
    /** Outlined badges, and every edge at `HlAlpha.EDGE`. */
    val outline: Color,
    /** Primary text: projector white. */
    val content: Color,
    val contentSecondary: Color,
    /** The quietest text: 5.56:1 on [glass], never on `FILL_STRONG` (`ContrastTest`). */
    val contentTertiary: Color,
    /** Never for text: the rule of an empty frame, a skeleton. */
    val contentDecorative: Color,
    /**
     * The house light, a closed list of six states (in use, your choice, needs attention, new, where you are,
     * the action to play); never focus or a fact. Screens reach it through component states, never by name.
     */
    val ember: Color,
    /** Ember gradient ends, where it reads as light (progress, a rule, the mark's bars, [emberLight]); small marks take [ember]. */
    val emberFrom: Color,
    val emberTo: Color,
    /** Ink on an ember fill (the main action): black, 5.8:1 at the gradient's raspberry end; never white. */
    val onEmber: Color,
    /** Ember on projector white, for a mark only (3.3:1): what stays lit on a focused plate. */
    val emberOnLight: Color,
    /** Focus outline and filled focused controls. */
    val focus: Color,
    /** Content drawn on [focus]. */
    val onFocus: Color,
    /** Secondary text in a focused control (a summary, a runtime): black at 62 % on [focus]. */
    val onFocusSecondary: Color,
    /** The dark plates and veils take where no picture lends its own ([LocalAmbient]). */
    val ambientDeep: Color,
)

val HouselightsColors = HlColors(
    background = HlPalette.N0,
    glass = HlPalette.N8,
    outline = HlPalette.N21,
    content = HlPalette.N96,
    contentSecondary = HlPalette.N65,
    contentTertiary = HlPalette.N57,
    contentDecorative = HlPalette.N28,
    ember = HlPalette.Ember,
    emberFrom = HlPalette.EmberStart,
    emberTo = HlPalette.EmberEnd,
    onEmber = HlPalette.N0,
    emberOnLight = HlPalette.EmberEnd,
    focus = HlPalette.N96,
    onFocus = HlPalette.N0,
    onFocusSecondary = HlPalette.N36,
    ambientDeep = HlPalette.Deep,
)

val LocalHlColors = staticCompositionLocalOf { HouselightsColors }

/** The house light as a horizontal gradient across what it fills, or over its first [across] px; at [alpha] for a fill. */
fun HlColors.emberLight(alpha: Float = 1f, across: Float = Float.POSITIVE_INFINITY): Brush =
    Brush.horizontalGradient(listOf(emberFrom.copy(alpha = alpha), emberTo.copy(alpha = alpha)), endX = across)

/** Every edge and divider of a container: [HlColors.outline] at `HlAlpha.EDGE`. */
val HlColors.edge: Color get() = outline.copy(alpha = HlAlpha.EDGE)

/** A plate over a picture: its own dark at [alpha], set off by [plateEdge]. */
fun HlColors.plate(deep: Color = ambientDeep, alpha: Float = HlAlpha.PLATE): Color = deep.copy(alpha = alpha)

/** A plate over the picture [light] comes from, read while drawing: as opaque as its quietest text needs there. */
fun HlColors.plate(light: AmbientLight): Color = plate(light.deep(), light.plateAlpha())

/** The hairline around a [plate]. */
val HlColors.plateEdge: Color get() = content.copy(alpha = HlAlpha.PLATE_EDGE)

/** Glass in a picture's [glow] at `GLASS_GLOW`: a showcase in its posters' light. */
fun HlColors.tintedGlass(glow: Color): Color = glow.copy(alpha = glow.alpha * HlAlpha.GLASS_GLOW).compositeOver(glass)

/**
 * A sheet's glass in the dark of the picture under it, read while drawing: [AmbientLight.deep] at `GLASS_DEEP` over
 * glass, a quiet tint that a grey picture leaves neutral.
 */
fun HlColors.sheetGlass(light: AmbientLight): Color = light.deep().copy(alpha = HlAlpha.GLASS_DEEP).compositeOver(glass)

/**
 * A plate on a sheet's glass, in the light of the film under it, read while drawing: a step up (`GLASS_STEP`),
 * then the film's [AmbientLight.glow] at `GLASS_GLOW`; the step alone without a picture.
 */
fun HlColors.glassPlate(light: AmbientLight): Color {
    val glow = light.glow()
    val step = content.copy(alpha = HlAlpha.GLASS_STEP).compositeOver(glass)
    return glow.copy(alpha = glow.alpha * HlAlpha.GLASS_GLOW).compositeOver(step)
}

/**
 * What a component lies on, which decides how it stands out: the black room, glass (sheets, menus) or
 * artwork, where content needs a dark plate. Components read it from [LocalGround], not a parameter.
 */
enum class Ground { Room, Glass, Artwork }

val LocalGround = staticCompositionLocalOf { Ground.Room }

/** Lays [content] on a picture: what it holds takes a plate ([groundPlate]) and reads over any image. */
@Composable
fun OnArtwork(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGround provides Ground.Artwork, content = content)
}

/** The ground's opaque colour, for what hides or fades into it (a hint, scroll edges); black on artwork. */
@Composable
@ReadOnlyComposable
fun groundColor(): Color = if (LocalGround.current == Ground.Glass) Hl.colors.glass else Hl.colors.background

/** Fill one step up: `FILL` on the room; `FILL_FAINT` on glass, keeping the quietest text at 4.5:1 there. */
@Composable
@ReadOnlyComposable
fun groundStep(): Float = if (LocalGround.current == Ground.Glass) HlAlpha.FILL_FAINT else HlAlpha.FILL

/** `content` at [groundStep], translucent: a quiet panel, a scroll area, an empty frame, an image's loading ground. */
@Composable
@ReadOnlyComposable
fun groundStepFill(): Color = Hl.colors.content.copy(alpha = groundStep())

/** `content` at [alpha] composited over the ground: a fill as it shows, opaque. */
@Composable
@ReadOnlyComposable
fun groundFill(alpha: Float): Color = Hl.colors.content.copy(alpha = alpha).compositeOver(groundColor())

/** Plate under content on a picture: black at `VEIL`, so contrast is independent of the image; null elsewhere. */
@Composable
@ReadOnlyComposable
fun groundPlate(): Color? = if (LocalGround.current == Ground.Artwork) Hl.colors.background.copy(alpha = HlAlpha.VEIL) else null

/** Opacities by role: fills of `content`, veils of `background`, edges; every translucent layer uses one. */
object HlAlpha {
    /** Faint wells: a meter's track, a disabled control, an absent film's card. */
    const val FILL_FAINT = 0.06f
    /** Fill of a thing on its ground (a placeholder, a control on glass): #161616 on the room, a step a TV still shows. */
    const val FILL = 0.09f
    /** A progress line's track beside text. */
    const val FILL_STRONG = 0.2f

    /** The room beside a side sheet, which stays readable. */
    const val VEIL_SIDE = 0.6f
    /** Card marks and their progress track on artwork: at 80 % a progress line's raspberry end keeps 3.5:1 on white. */
    const val VEIL = 0.8f
    /**
     * A [plate] in the picture's own dark, raised up to [PLATE_MAX] over a light picture so its quietest text
     * keeps 4.5:1 (`ArtworkSample.plateFloor`), and the hairline (`content`) that sets it off.
     */
    const val PLATE = 0.74f
    const val PLATE_MAX = 0.92f
    const val PLATE_EDGE = 0.11f

    /**
     * A plate on glass ([glassPlate]): `content` a step over it, under `FILL_FAINT` so the quietest text keeps
     * 4.5:1 once the film's glow is added at [GLASS_GLOW].
     */
    const val GLASS_STEP = 0.035f
    const val GLASS_GLOW = 0.05f

    /** A sheet's glass: the picture's dark over it ([sheetGlass]). */
    const val GLASS_DEEP = 0.5f

    /** Every edge and divider of a container: `outline` at this opacity. */
    const val EDGE = 0.6f
    /** The edge of artwork at rest, so a dark poster keeps its outline on the black room: `content`. */
    const val EDGE_ARTWORK = 0.12f

    /** The catalogue's black-level chart: a TV that merges the first steps into black crushes its blacks. */
    val BLACK_LEVELS = listOf(0.03f, 0.06f, 0.09f, 0.12f, 0.15f, 0.2f)
}

/**
 * The light of the picture a screen lies on (`AmbientBackdrop`), read while drawing: it follows the picture's
 * fade without recomposing. Neutral without a picture.
 */
@Stable
interface AmbientLight {
    /** The picture's own dark: its veils, and plates over it. */
    fun deep(): Color

    /**
     * How opaque a plate over it must be: [HlAlpha.PLATE], raised as the header's band needs
     * ([BackdropDefaults.plateBand]), for every plate on the screen.
     */
    fun plateAlpha(): Float

    /** Its glow, for a focused card without a tone of its own; transparent without a picture. */
    fun glow(): Color
}

/** No picture: the neutral dark, plates at [HlAlpha.PLATE], no glow. */
private object NoPicture : AmbientLight {
    override fun deep() = HouselightsColors.ambientDeep
    override fun plateAlpha() = HlAlpha.PLATE
    override fun glow() = Color.Transparent
}

val LocalAmbient = staticCompositionLocalOf<AmbientLight> { NoPicture }
