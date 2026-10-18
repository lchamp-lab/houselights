package app.houselights.ui.design

import androidx.compose.foundation.shape.CircleShape
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

/** Radii follow hierarchy; a nested shape keeps corners parallel: outer = inner + gap (a menu: 8 + 8 = 16). */
object HlShapes {
    val badge = RoundedCornerShape(3.dp)
    val artwork = RoundedCornerShape(4.dp)
    val field = RoundedCornerShape(6.dp)
    /** Selectable rows: settings, navigation entries, menu options. */
    val row = RoundedCornerShape(8.dp)
    val panel = RoundedCornerShape(12.dp)
    /** Sheets and menus above the room. */
    val sheet = RoundedCornerShape(16.dp)
    val control = RoundedCornerShape(percent = 50)
    val avatar = CircleShape
}

/** Inner padding of containers by kind, so the same kind of thing breathes the same everywhere. */
object HlInset {
    /** Menus (their rows carry their own padding) and results holding a thumbnail. */
    val tight = HlSpace.xs
    /** Dense rows and lines inside a container. */
    val compact = HlSpace.s
    /** Panels and cards. */
    val card = HlSpace.l
    val sheet = HlSpace.xl
}

object HlSize {
    val poster = 112.dp
    val posterWall = 148.dp
    /** Poster in a list (search results, source cards). */
    val posterThumb = 64.dp
    /** Poster of the title in use, above the results listed under it. */
    val posterThumbLarge = 72.dp
    val landscape = 220.dp
    val episode = 264.dp
    val avatar = 76.dp
    /** A person's portrait at the head of their page. */
    val portrait = 128.dp
    /** Cast member column: the portrait, then a name of at most two lines. */
    val personColumn = 120.dp
    /** Height of every control, so pill buttons and round icon buttons line up. */
    val control = 48.dp
    val iconButton = control
    val icon = 20.dp
    /** An icon larger than a line's: a panel button's, an empty preview's. */
    val discIcon = 24.dp
    /** Round mark over artwork (watched, selected). */
    val mark = 20.dp
    val markIcon = 14.dp
    /** Status dot (default track, online). */
    val dot = 8.dp
    /** The box a state's light sits in, so text lines up after a dot or the attention mark. */
    val statusLight = 16.dp
    /** An icon in a line of text (rating star). */
    val inlineIcon = 16.dp
    val meter = 10.dp
    /** Between two parts of a meter, so two greys never touch. */
    val meterGap = 2.dp
    /** How far one press of up or down scrolls a long text. */
    val scrollStep = 120.dp
    /** Minimum height of a setting row. */
    val row = 64.dp
    /** Image at the head of a row: its height, a poster's width at that height, its slot's width. */
    val rowPreview = 48.dp
    val rowPreviewPoster = 32.dp
    val rowPreviewWide = 84.dp
    /** Progress shown beside text (time left). */
    val progressShort = 120.dp
    /** Placeholder rules for a title and its facts while a page loads. */
    val skeletonTitle = 48.dp
    val skeletonFacts = 24.dp
    val progress = 3.dp
    /** The short rule heading an intertitle card: a question asked in a sheet. */
    val intertitleRule = 24.dp
    val intertitleRuleWeight = 2.dp
    /** An option inside a segmented track: with the track's padding, as tall as a control. */
    val segment = 40.dp
    val focusBorder = 2.dp
    val hairline = 1.dp
    /** Wordmark cap height: in About (144 wide) and over the welcome wall (359 wide). */
    val wordmark = 12.dp
    val wordmarkHero = 30.dp
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
    val heroTitleHeight = 76.dp
    /**
     * Home hero band, [HlSpace.l] from the bar and the rows: logo, facts, two overview lines, [HlSpace.s]
     * apart (76 + 12 + 20 + 12 + 48), so a focused poster's foot stays inside the safe area.
     */
    val hero = 168.dp
    /** Title line above a poster wall: 24 at the default text size, growing with it. */
    val infoLine: Dp
        @Composable @ReadOnlyComposable
        get() = textRoom(infoLineAtDefault)
    private val infoLineAtDefault = 24.dp
    /** Overview of the focused episode under the episode row: three caption lines (3 × 18), on the grid. */
    val episodeOverview = 56.dp
    /** Between detail page blocks: wider than [HlFocus.edge], so the block above leaves as the next arrives. */
    val detailGap = HlSpace.xl
    /** The first row's visible part under a detail hero: gap, header, its gap and padding, a strip of images. */
    private fun detailPeek(header: Dp) = detailGap + header + HlSpace.xxs + HlSpace.s + HlSpace.s
    /** Least height of a detail hero on a [screen]: all but [detailPeek], keeping a [header] in the safe area. */
    internal fun detailHeroMin(screen: Dp, header: Dp): Dp = screen - detailPeek(header)
    /** [detailHeroMin] with the header at the viewer's text size (26 at the default one). */
    @Composable
    @ReadOnlyComposable
    fun detailHeroMin(screen: Dp): Dp = detailHeroMin(screen, textRoom(Hl.type.lead))
    /** Where an error sits on an otherwise empty detail page. */
    val errorTop = 96.dp
    /** Artwork picker choices; a poster, being its own preview, takes a preview's room (180 × 270). */
    val posterChoice = 180.dp
    val landscapeChoice = 240.dp
    /** The two columns of the welcome screen. */
    val welcomeTagline = 380.dp
    val welcomeChoices = 430.dp
    /** Columns of the technical sheet's track tables. */
    val specLanguage = 120.dp
    val specFormat = 136.dp
    /** Height of long text panels (NFO). */
    val textPanel = 440.dp
    /** Label column of label and value lists, and its short form inside a narrow panel. */
    val definitionLabel = 220.dp
    val definitionLabelShort = 120.dp
}

// Component parts: measures a component keeps to itself; screens lay out with HlLayout.

/** A side sheet: 440 dp of content between its inset and the safe area, the page still in view. */
internal object HlSideSheetDefaults {
    val width = 520.dp
}

/** The widest value on the right of a setting row. */
internal object HlSettingRowDefaults {
    val valueWidth = 360.dp
}

/** The label a round button shows under it with focus. */
internal object HlIconButtonDefaults {
    /** Widest label; a longer one ends in an ellipsis. */
    val labelWidth = 200.dp

    /**
     * Room below a row of round buttons for the focused label (gap, plate, one caption line), on the grid:
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
}

/** Tech sheet rows: least height (as a menu option) and widest value ("Dolby Digital Plus Atmos   7.1"). */
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

/** Left veil fade past the text column: slow, as a quicker fade over a light picture reads as a panel edge. */
internal object BackdropDefaults {
    val veilFade = 420.dp
}

/** Inner padding of pill controls and of the options of a segmented track. */
object HlControl {
    /** A round button's label plate: room around the words, so the plate reads as a pill. */
    val labelPaddingH = 12.dp
    val labelPaddingV = 4.dp
    val tabPaddingH = 16.dp
    val buttonPaddingH = 24.dp
    val buttonPaddingIcon = 20.dp
    /** Between an icon and its text inside a control. */
    val iconGap = 8.dp
    val badgePaddingH = 6.dp
    val badgePaddingV = 2.dp
}

/** Focus growth by family, as a share of size: 1.04 takes a 500 dp row 10 dp past its inset ([reach]). */
object HlFocus {
    const val ARTWORK_SCALE = 1.1f
    const val CARD_SCALE = 1.06f
    /** Pills and discs: buttons, round buttons, the options of a track. */
    const val CONTROL_SCALE = 1.04f
    /** Selectable panels: folder cards, list items, results. */
    const val PANEL_SCALE = 1.02f
    /** Full-width rows (settings, navigation, menu options) do not grow: their white fill shows focus. */
    const val ROW_SCALE = 1f

    /** How far an element [size] long reaches past each end at [scale]: the room its container keeps. */
    fun reach(size: Dp, scale: Float): Dp = size * (scale - 1f) / 2

    /** The focused card's siblings darken and lose this much colour: partly, so the wall stays inviting. */
    const val SIBLING_DIM = 0.3f
    const val SIBLING_SATURATION = 0.3f
    /** Colour steps of a receding card, bounding its image's recompositions; the dimming stays continuous. */
    const val RECEDE_STEPS = 3

    /** The colour step for how far a card has stepped back: 0 in front, [RECEDE_STEPS] behind. */
    fun recedeStep(recede: Float): Int = (recede.coerceIn(0f, 1f) * RECEDE_STEPS).roundToInt()

    /** How much of its colour an image keeps at [step]: all of it in front, [SIBLING_SATURATION] behind. */
    fun saturationAt(step: Int): Float = 1f - step.toFloat() / RECEDE_STEPS * (1f - SIBLING_SATURATION)
    val glow = 20.dp
    /** Focus keyline from a list's top and bottom: the safe area, so a focused poster grows into the margin. */
    val edge = HlSpace.safeV
}
