package app.houselights.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 4 dp grid. [safeH]/[safeV] keep content inside the TV overscan-safe area (~5 %). */
object HlSpace {
    val none = 0.dp
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val safeH = 48.dp
    val safeV = 28.dp
    /** Between facts on one line (year, runtime, rating): wider than a word space. */
    val fact = 20.dp
    /** Fade length at a scrolling edge with more beyond it (`scrollEdges`). */
    val fadeEdge = l
}

/**
 * Radii follow hierarchy, never a pill or a disc: only dots are round. A nested shape keeps its corners
 * parallel: outer = inner + gap (a menu: 10 + 6 = 16; a focus frame: the picture's + its gap).
 */
object HlShapes {
    /** Images up to 48 dp: thumbnails, row previews. */
    val thumb = RoundedCornerShape(4.dp)
    val badge = RoundedCornerShape(4.dp)
    /** A mark over a picture, a tile's label. */
    val mark = RoundedCornerShape(6.dp)
    /** Buttons, tiles, a tab's plate, menu options. */
    val control = RoundedCornerShape(10.dp)
    val field = RoundedCornerShape(10.dp)
    /** Setting and sheet rows. */
    val row = RoundedCornerShape(12.dp)
    /** Posters and portraits. */
    val artwork = RoundedCornerShape(12.dp)
    /** Wide images: stills, a backdrop to choose. */
    val wide = RoundedCornerShape(14.dp)
    /** Menus, source cards, panels. */
    val panel = RoundedCornerShape(16.dp)
    /** A side sheet: its free corners rounded, square against the screen's edge. */
    val sheet = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
}

/** Inner padding of containers by kind, so the same kind of thing breathes the same everywhere. */
object HlInset {
    /** Results holding a thumbnail. */
    val tight = HlSpace.xs
    /** Panels and cards. */
    val card = HlSpace.l
    val sheet = HlSpace.xl
}

object HlSize {
    /** A poster in a row: 120 × 180. */
    val poster = 120.dp
    /** A poster on a wall: seven across the safe area ([HlLayout.wallGutter]), 112 × 168. */
    val wallPoster = 112.dp
    /** A poster of the welcome screen's drifting wall. */
    val welcomePoster = 148.dp
    /** Poster in a list (search results, source cards). */
    val posterThumb = 64.dp
    /** Poster of the title in use, above the results listed under it. */
    val posterThumbLarge = 72.dp
    /** A wide image in a row: 240 × 135. */
    val landscape = 240.dp
    val episode = 264.dp
    /** A person's portrait, [PORTRAIT_RATIO]: in a row of people and at the head of their page. */
    val portrait = 96.dp
    const val PORTRAIT_RATIO = 3f / 4f
    /** Cast member column: the portrait, then a name of at most two lines. */
    val personColumn = 120.dp
    /** A button on a detail page; [controlCompact] in a screen's header. */
    val control = 48.dp
    val controlCompact = 44.dp
    /** A square icon button; [tileCompact] in a screen's header and home's bar. */
    val tile = 48.dp
    val tileCompact = 40.dp
    val icon = 20.dp
    /** An icon on a [tile]. */
    val tileIcon = 22.dp
    /** An icon larger than a line's: a panel button's, an empty preview's. */
    val largeIcon = 24.dp
    /** A mark over a picture (watched, chosen), on its plate. */
    val mark = 22.dp
    val markIcon = 14.dp
    /** Status dot (default track, online). */
    val dot = 8.dp
    /** The dot of a title new to the library, ringed in black ([newDotRing]) to stand off any picture. */
    val newDot = 10.dp
    val newDotRing = 2.5.dp
    /** The ember dot before a row's count of new titles. */
    val newsDot = 7.dp
    /** The box a state's light sits in, so text lines up after a dot or the attention mark. */
    val statusLight = 16.dp
    /** An icon in a line of text (rating star). */
    val inlineIcon = 16.dp
    val meter = 10.dp
    /** Between two parts of a meter, so two greys never touch. */
    val meterGap = 2.dp
    /** How far one press of up or down scrolls a long text. */
    val scrollStep = 120.dp
    /** Least height of a setting row; [sheetRow] in a side sheet. */
    val row = 60.dp
    val sheetRow = 52.dp
    /** Image at the head of a row, as tall as a sheet row allows: its height, a poster's width at it, its slot's width. */
    val rowPreview = 40.dp
    val rowPreviewPoster = 27.dp
    val rowPreviewWide = 72.dp
    /** Progress shown beside text (time left). */
    val progressShort = 120.dp
    /** The progress of home's hero title, before its time left. */
    val heroProgress = 220.dp
    val heroProgressWeight = 4.dp
    /** Placeholder rules for a title and its facts while a page loads. */
    val skeletonTitle = 48.dp
    val skeletonFacts = 24.dp
    val progress = 3.dp
    /** Progress burning at the foot of a poster, of a wide image. */
    val progressPoster = 4.dp
    val progressWide = 5.dp
    /** The short rule heading every intertitle. */
    val intertitleRule = 24.dp
    val intertitleRuleWeight = 2.dp
    /** A word of a track (`HlSegmented`) on its plate: as tall as a compact tile. */
    val segment = 40.dp
    /** The dot before the current place's word when it has focus. */
    val wordDot = 7.dp
    /** Gradation as home's place in its bar, as tall as its words' capitals. */
    val barMark = 18.dp
    /** The ring of focus on a field, a read-only area, a control that cannot act. */
    val focusBorder = 2.dp
    val hairline = 1.dp
    /** The lockup's cap height: in About (168 wide) and over the welcome wall (364 wide, as the name alone was). */
    val wordmark = 12.dp
    val wordmarkHero = 26.dp
}

/** Layout measures of screens (DESIGN.md, Layout). */
object HlLayout {
    /**
     * Width every screen is laid out for, in dp (960 × 540). Density follows the screen's real width,
     * not the declared dpi: the Zidoo declares 240 dpi, which would draw everything a quarter smaller.
     */
    const val CANVAS_WIDTH = 960f
    /** Longest comfortable line of reading text. */
    val readable = 640.dp
    /** Text set beside artwork (heroes, detail pages). */
    val readableNarrow = 520.dp
    /** Longest caption line: about 66 characters at 14 sp, the most readable measure from three metres. */
    val captionMeasure = 400.dp
    /** Side column of two-pane screens: navigation (settings), explanation (setup forms). */
    val sideColumn = 280.dp
    val sideColumnWide = 340.dp
    /** Where a two-pane screen's content starts, level with its first navigation entry. */
    val paneTop = 72.dp
    val fieldShort = 120.dp
    /** Start of rows carrying their own [HlInset.card], so their text aligns with the safe area (end credits). */
    val insetRows = HlSpace.safeH - HlInset.card
    /** Clear logo or title of a detail page, and of a browsing hero. */
    val titleArtWidth = 440.dp
    val titleArtHeight = 112.dp
    val heroTitleWidth = 380.dp
    val heroTitleHeight = 56.dp
    /**
     * Home hero band, [HlSpace.l] from the bar and the rows: logo, facts, two overview lines, [HlSpace.s]
     * apart (56 + 12 + 20 + 12 + 48), so a focused poster's foot stays inside the safe area.
     */
    val hero = 148.dp
    /** Title line above a poster wall, in `rowTitle`: 28 at the default text size, growing with it. */
    val infoLine: Dp
        @Composable @ReadOnlyComposable
        get() = textRoom(infoLineAtDefault)
    private val infoLineAtDefault = 28.dp
    /** Overview of the focused episode under the episode row: three caption lines (3 × 18), on the grid. */
    val episodeOverview = 56.dp
    /** Between detail page blocks: wider than [HlFocus.edge], so the block above leaves as the next arrives. */
    val detailGap = HlSpace.xl
    /** The first row's visible part under a detail hero: gap, header, its gap and focus room, a strip of images. */
    private fun detailPeek(header: Dp) = detailGap + header + HlSpace.xxs + HlFocus.room + HlSpace.s
    /** Least height of a detail hero on a [screen]: all but [detailPeek], keeping a [header] in the safe area. */
    internal fun detailHeroMin(screen: Dp, header: Dp): Dp = screen - detailPeek(header)
    /** [detailHeroMin] with the row's header at the viewer's text size (28 at the default one). */
    @Composable
    @ReadOnlyComposable
    fun detailHeroMin(screen: Dp): Dp = detailHeroMin(screen, textRoom(Hl.type.rowTitle))
    /** Where an error sits on an otherwise empty detail page. */
    val errorTop = 96.dp
    /** Between a wall's columns: [WALL_COLUMNS] posters of [HlSize.wallPoster] across the safe area. */
    val wallGutter = (CANVAS_WIDTH.dp - HlSpace.safeH * 2 - HlSize.wallPoster * WALL_COLUMNS) / (WALL_COLUMNS - 1)
    /** Artwork picker choices; a poster, being its own preview, takes a preview's room (180 × 270). */
    val posterChoice = 180.dp
    val landscapeChoice = 240.dp
    /** The two columns of the welcome screen: the choices' holds its title on one line in French. */
    val welcomeTagline = 380.dp
    val welcomeChoices = 456.dp
    /** Columns of the technical sheet's track tables. */
    val specLanguage = 120.dp
    val specFormat = 136.dp
    /** Height of long text panels (NFO). */
    val textPanel = 440.dp
    /** Label column of label and value lists, and its short form inside a narrow panel. */
    val definitionLabel = 220.dp
    val definitionLabelShort = 120.dp
}

/** Columns of a poster wall (`LibraryScreen`, `PersonScreen`). */
private const val WALL_COLUMNS = 7

// Component parts: measures a component keeps to itself; screens lay out with HlLayout.

/** A side sheet: 440 dp of content between its inset and the safe area, the page still in view. */
internal object HlSideSheetDefaults {
    val width = 520.dp
}

/** The widest value on the right of a setting row, and the room above and below its words. */
internal object HlSettingRowDefaults {
    val valueWidth = 360.dp

    /** A name over its description, or a [HlSize.rowPreview], fills a [HlSize.sheetRow] with this room. */
    val paddingV = 6.dp
}

/** The label a tile shows under it with focus. */
internal object HlIconButtonDefaults {
    /** Widest label; a longer one ends in an ellipsis. */
    val labelWidth = 256.dp

    /**
     * Room below a row of tiles for the focused label (gap, plate, one caption line), on the grid:
     * 36 at the default text size. The label takes none of its own; [HlActionRow] keeps it.
     */
    val labelRoom: Dp
        @Composable @ReadOnlyComposable
        get() = HlSpace.xs + HlControl.labelPaddingV * 2 + textRoom(Hl.type.caption) + toTheGrid

    /** Rounds the label's room at the default text size, 34, up to the 4 dp grid. */
    private val toTheGrid = 2.dp

    /** Closest the label may come to a screen side: past the safe area, so an edge button keeps it centred. */
    val labelEdge = HlSpace.l
}

/** Menus anchored to a button (sort); their height is the room the screen leaves them. */
internal object HlMenuDefaults {
    val width = 320.dp
    /** Between the menu's edge and its options: [HlShapes.panel] less [HlShapes.control]. */
    val inset = 6.dp
}

/** Tech sheet rows: least height (as a compact tile) and widest value ("Dolby Digital Plus Atmos   7.1"). */
internal object TechSpecsDefaults {
    val rowHeight = 40.dp
    val valueWidth = 240.dp
}

/**
 * Module 6.5 wide: whole pixels at 1080p (13) and 4K (26), so no module edge blurs. 29 modules plus the
 * 4-module quiet zone readers need make 240.5, which a phone reads from the sofa.
 */
internal object QrCodeDefaults {
    val module = 6.5.dp
    const val QUIET_ZONE = 4
}

/**
 * The left veil's fade past the text column, by style: slow, as a quicker fade over a light picture reads as a
 * panel edge. [plateBand]: the top of the screen where plates sit over the picture (a header's tiles, their labels).
 */
internal object BackdropDefaults {
    val homeFade = 650.dp
    val detailFade = 500.dp
    val quietFade = 420.dp
    val plateBand = 112.dp
}

/** Inner padding of controls and of the options of a segmented track. */
object HlControl {
    /** A tile's label plate: room around the words. */
    val labelPaddingH = 12.dp
    val labelPaddingV = 4.dp
    val tabPaddingH = 12.dp
    val buttonPaddingH = 20.dp
    val buttonPaddingIcon = 16.dp
    /** Between an icon and its text inside a control. */
    val iconGap = 8.dp
    val badgePaddingH = 6.dp
    val badgePaddingV = 2.dp
}

/**
 * Focus by family (DESIGN.md, Focus): a framed picture takes the white frame [frameGap] off its edge and glows;
 * controls and rows take the white plate. Nothing grows.
 */
object HlFocus {
    /** The white frame of focus, and how far it stands off the edge of what it frames. */
    val frame = 2.5.dp
    val frameGap = 3.dp

    /** How far focus reaches past what it frames: the gap, then the frame. */
    val reach = frameGap + frame

    /**
     * Room a row of pictures keeps above and below its cards: their [reach] inside it, their [glow] past it, as far
     * as a lazy row draws across its axis (30 dp).
     */
    val room = HlSpace.s

    /**
     * Room a wall keeps above its first line, where the grid cuts the glow once faded to about 1 %: no more than
     * the gap between its lines ([HlSpace.l]), so the line scrolled above stays out of view.
     */
    val wallRoom = HlSpace.l

    /** The focused card's siblings dim and lose colour through their image's filter: partly, so the wall stays inviting. */
    const val SIBLING_BRIGHTNESS = 0.64f
    const val SIBLING_SATURATION = 0.65f
    /** Colour steps of a receding card, bounding its image's recompositions. */
    const val RECEDE_STEPS = 3

    /** The colour step for how far a card has stepped back: 0 in front, [RECEDE_STEPS] behind. */
    fun recedeStep(recede: Float): Int = (recede.coerceIn(0f, 1f) * RECEDE_STEPS).roundToInt()

    /** How much of its colour an image keeps at [step]: all of it in front, [SIBLING_SATURATION] behind. */
    fun saturationAt(step: Int): Float = 1f - step.toFloat() / RECEDE_STEPS * (1f - SIBLING_SATURATION)

    /** How much of its light an image keeps at [step]: all of it in front, [SIBLING_BRIGHTNESS] behind. */
    fun brightnessAt(step: Int): Float = 1f - step.toFloat() / RECEDE_STEPS * (1f - SIBLING_BRIGHTNESS)

    /** The light a focused picture spreads around it, in its own tone. */
    val glow = 28.dp
    /** Focus keyline from a list's top and bottom: the safe area, so a focused picture's frame lies in the margin. */
    val edge = HlSpace.safeV
}
