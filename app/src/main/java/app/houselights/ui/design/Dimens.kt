package app.houselights.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    /** How far what scrolls fades into its surface, at an edge with more beyond it (`scrollEdges`). */
    val fadeEdge = l
}

/**
 * Corner radii follow hierarchy: printed artwork is nearly square, rows less round than the
 * panels that hold them, panels less round than the sheets above the room; interactive controls
 * are pills, people are circles. A shape set close inside another keeps their corners parallel:
 * outer radius = inner radius + the gap between them (a menu: 8 + 8 = 16; a result: 4 + 8 = 12).
 */
object HlShapes {
    val badge = RoundedCornerShape(3.dp)
    val artwork = RoundedCornerShape(4.dp)
    val field = RoundedCornerShape(6.dp)
    /** Rows that can be chosen: settings, navigation entries, menu options. */
    val row = RoundedCornerShape(8.dp)
    val panel = RoundedCornerShape(12.dp)
    /** Sheets and menus above the room. */
    val sheet = RoundedCornerShape(16.dp)
    val control = RoundedCornerShape(percent = 50)
    val avatar = CircleShape
}

/**
 * Inner padding of containers, by how much they hold. Each container takes the one of its kind,
 * so the same kind of thing breathes the same everywhere.
 */
object HlInset {
    /** Containers whose rows carry their own padding (menus), or that hold a thumbnail (results). */
    val tight = HlSpace.xs
    /** Dense rows and lines inside a container. */
    val compact = HlSpace.s
    /** Panels and cards. */
    val card = HlSpace.l
    /** Sheets above the room. */
    val sheet = HlSpace.xl
}

object HlSize {
    val poster = 112.dp
    /** Poster on the welcome wall. */
    val posterWall = 148.dp
    /** Poster thumbnail in a list (search results, source cards). */
    val posterThumb = 64.dp
    /** Poster of the title in use, a step above the results listed under it. */
    val posterThumbLarge = 72.dp
    val landscape = 220.dp
    val episode = 264.dp
    val avatar = 76.dp
    /** Column of a cast member: the portrait, then a name over two lines at most. */
    val personColumn = 120.dp
    /** Height of every control: pill buttons and round icon buttons line up. */
    val control = 48.dp
    val iconButton = control
    val icon = 20.dp
    /** Disc holding an icon in lists, status panels and settings. */
    val disc = 52.dp
    val discIcon = 24.dp
    /** Round mark over artwork (watched, selected). */
    val mark = 20.dp
    val markIcon = 14.dp
    /** Status dot (default track, online). */
    val dot = 8.dp
    /** The box a state's light sits in, so words line up after a dot or the attention mark. */
    val statusLight = 16.dp
    /** An icon set in a line of text (rating star). */
    val inlineIcon = 16.dp
    val meter = 10.dp
    /** Between two parts of a meter, so two greys never touch. */
    val meterGap = 2.dp
    /** How far one press of up or down scrolls a long text. */
    val scrollStep = 120.dp
    /** Minimum height of a setting row. */
    val row = 64.dp
    /**
     * An image previewed at the head of a row: its height, the width of a poster at that height,
     * and the width of its slot (a backdrop).
     */
    val rowPreview = 48.dp
    val rowPreviewPoster = 32.dp
    val rowPreviewWide = 84.dp
    /** Progress shown beside text (time left). */
    val progressShort = 120.dp
    /** The short rules standing for a title and its facts while a page loads. */
    val skeletonTitle = 48.dp
    val skeletonFacts = 24.dp
    val progress = 3.dp
    /** The short rule heading a card that speaks, like an intertitle: a question asked in a sheet. */
    val intertitleRule = 24.dp
    val intertitleRuleWeight = 2.dp
    /** An option inside a segmented track: with the track's padding, as tall as a control. */
    val segment = 40.dp
    val focusBorder = 2.dp
    /** Space between a control and the focus ring drawn around it. */
    val focusRingGap = 3.dp
    val hairline = 1.dp
}

/** Widths and heights of layout parts. */
object HlLayout {
    /**
     * The width every screen is laid out for, in dp: the TV canvas, 960 × 540. The density follows
     * the screen's real width (×2 for a 1080p interface), not what the device declares: the Zidoo
     * declares 240 dpi, which would draw everything a quarter smaller than designed.
     */
    const val CANVAS_WIDTH = 960f
    /** Longest comfortable line of reading text. */
    val readable = 640.dp
    /** Text set beside artwork (heroes, detail pages). */
    val readableNarrow = 520.dp
    /**
     * Longest line of a caption: about 66 characters at 14 sp (Archivo sets them about 6 dp apart),
     * the measure the eye follows best. Read from three metres, a longer line loses the eye on its
     * way back to the next one.
     */
    val captionMeasure = 400.dp
    /** How far a backdrop's left veil takes to fade out, past the text column. */
    val veilFade = 240.dp
    /** Side column of two-pane screens: navigation (settings), and explanation (setup forms). */
    val sideColumn = 280.dp
    val sideColumnWide = 340.dp
    /** Where the content of a two-pane screen starts, level with its first navigation entry. */
    val paneTop = 72.dp
    /** Text fields of a form. */
    val field = 400.dp
    val fieldShort = 120.dp
    /** Clear logo or title of a detail page, and of a browsing hero. */
    val titleArtWidth = 440.dp
    val titleArtHeight = 112.dp
    val heroTitleWidth = 380.dp
    val heroTitleHeight = 76.dp
    /**
     * The browsing hero's band (home), between the bar and the rows, [HlSpace.l] from each: a logo
     * at most [heroTitleHeight] tall, a line of facts, two lines of overview, [HlSpace.s] apart
     * (76 + 12 + 20 + 12 + 48). Under it, a focused poster's foot stays inside the safe area.
     */
    val hero = 168.dp
    /** The line naming the focused title above a poster wall (library). */
    val infoLine = 24.dp
    /** Overview of the focused episode under the episode row: three lines of caption (3 × 18), on the grid. */
    val episodeOverview = 56.dp
    /**
     * Between the blocks of a detail page (its hero, its rows): wider than the keyline a block
     * comes to ([HlFocus.edge]), so the block above has left the screen when the next one is there.
     */
    val detailGap = HlSpace.xl
    /**
     * What shows of a detail page's first row under its hero: the gap, the row's header whole
     * (`lead`, 26), its gap ([HlSpace.xxs]) and padding ([HlSpace.s]), then a strip of its images
     * ([HlSpace.s]), which ends at the screen's edge. The header stays inside the safe area.
     */
    val detailPeek = detailGap + 26.dp + HlSpace.xxs + HlSpace.s + HlSpace.s
    /** The least a detail page's hero takes of a [screen] that tall: all of it but [detailPeek]. */
    fun detailHeroMin(screen: Dp): Dp = screen - detailPeek
    /** Where an error sits on an otherwise empty detail page. */
    val errorTop = 96.dp
    /**
     * Choice cards of the artwork pickers, at the shape they are used in. A poster is its own
     * preview, so its choices take the room a preview would have: 180 wide, 270 tall, with their
     * two lines under them, below the header and inside the safe area.
     */
    val posterChoice = 180.dp
    val landscapeChoice = 240.dp
    /** The two columns of the welcome screen. */
    val welcomeTagline = 380.dp
    val welcomeChoices = 430.dp
    /** A sheet to read: as wide as the safe area allows (960 dp screen less the side margins). */
    val dialog = 864.dp
    /** A side sheet: 440 dp of content between its inset and the safe area, the page still in view. */
    val sideSheet = 520.dp
    /** Widest value on the right of a setting row. */
    val rowValue = 360.dp
    /** Widest label under a round icon button; a longer one ends in an ellipsis. */
    val iconLabel = 200.dp
    /**
     * Room a row of round buttons keeps under itself for the label of the one with focus: the gap
     * ([HlSpace.xs]) and the label on its plate (one caption line, 18, and its padding, 2 × 4), on the grid. The
     * label takes none of its own.
     */
    val iconLabelRoom = 36.dp

    /**
     * How close to a side of the screen a round button's label may come: brief and passing, it may
     * go past the safe area ([HlSpace.safeH]) so a button at its edge keeps its label centred.
     */
    val iconLabelEdge = HlSpace.l
    /** Dropdown menus (sort, filters); their height is the room the screen leaves them. */
    val menuWidth = 320.dp
    /** Columns of the technical sheet's track tables. */
    val specLanguage = 120.dp
    val specFormat = 136.dp
    val specLayout = 48.dp
    /** Height of long text panels (NFO). */
    val textPanel = 440.dp
    /** Label column of label and value lists, and its short form inside a narrow panel. */
    val definitionLabel = 220.dp
    val definitionLabelShort = 120.dp
}

/** Inner padding of pill controls and of the options of a segmented track. */
object HlControl {
    /** A round button's label on its plate: room around the words, so the plate reads as a pill. */
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

/**
 * How much a focused element grows, by family: artwork the most, wide things the least. Growth is
 * a share of the size, so the wider the element, the less it may grow: 1.04 takes a 500 dp row
 * 10 dp past its inset and the safe area ([reach]).
 */
object HlFocus {
    const val ARTWORK_SCALE = 1.1f
    const val CARD_SCALE = 1.06f
    /** Pills and discs: buttons, round buttons, the options of a track. */
    const val CONTROL_SCALE = 1.04f
    /** Panels that can be chosen: folder cards, list items, results. */
    const val PANEL_SCALE = 1.02f
    /**
     * Rows as wide as their column (setting rows, navigation entries, menu options) do not grow:
     * their white fill says focus.
     */
    const val ROW_SCALE = 1f

    /**
     * How far an element [size] long reaches past each of its ends when it grows to [scale]: the
     * room the container around it keeps.
     */
    fun reach(size: Dp, scale: Float): Dp = size * (scale - 1f) / 2

    /** How far a focus ring stands outside what it surrounds: its gap, then its width. */
    val ring = HlSize.focusRingGap + HlSize.focusBorder

    /**
     * The other cards of a row step back while one of them has focus: darker by this much, and
     * with this much of their colour left. Partly, not grey: the wall stays inviting.
     */
    const val SIBLING_DIM = 0.3f
    const val SIBLING_SATURATION = 0.3f
    /**
     * The colour of a card stepping back goes in this many steps, so its image recomposes this
     * many times at most over the 250 ms; the dimming stays continuous, read while drawing.
     */
    const val RECEDE_STEPS = 3

    /** The step of colour lost for how far a card has stepped back: 0 in front, [RECEDE_STEPS] behind. */
    fun recedeStep(recede: Float): Int = (recede.coerceIn(0f, 1f) * RECEDE_STEPS).roundToInt()

    /** How much of its colour an image keeps at [step]: all of it in front, [SIBLING_SATURATION] behind. */
    fun saturationAt(step: Int): Float = 1f - step.toFloat() / RECEDE_STEPS * (1f - SIBLING_SATURATION)
    val glow = 20.dp
    /**
     * How far a vertical list keeps what has focus from its top and bottom: the safe area, so
     * the last row stops inside it and a focused poster grows into the margin, not off the screen.
     */
    val edge = HlSpace.safeV
}
