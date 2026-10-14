package app.houselights.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

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
    /** An icon set in a line of text (rating star). */
    val inlineIcon = 16.dp
    val meter = 10.dp
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
    val progress = 3.dp
    /** An option inside a segmented track: with the track's padding, as tall as a control. */
    val segment = 40.dp
    val focusBorder = 2.dp
    /** Space between a control and the focus ring drawn around it. */
    val focusRingGap = 3.dp
    val hairline = 1.dp
}

/** Widths and heights of layout parts. */
object HlLayout {
    /** Longest comfortable line of reading text. */
    val readable = 640.dp
    /** Text set beside artwork (heroes, detail pages). */
    val readableNarrow = 520.dp
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
    val heroTitleHeight = 100.dp
    /** Height of the browsing hero above the rows (home). */
    val hero = 200.dp
    /** The line naming the focused title above a poster wall (library). */
    val infoLine = 24.dp
    /** Overview of the focused episode under the episode row: three lines of caption. */
    val episodeOverview = 64.dp
    /** Share of the screen the top of a detail page takes at least: the next row only peeks in. */
    const val DETAIL_HERO_SHARE = 0.9f
    /** Where an error sits on an otherwise empty detail page. */
    val errorTop = 96.dp
    /** Choice cards of the artwork pickers, at the shape they are used in. */
    val posterChoice = 150.dp
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
    /** Dropdown menus (sort, filters). */
    val menuWidth = 320.dp
    val menuMaxHeight = 460.dp
    /** Columns of the technical sheet's track tables, and the height they scroll in. */
    val specLanguage = 120.dp
    val specFormat = 136.dp
    val specLayout = 48.dp
    val specTables = 240.dp
    /** Height of long text panels (NFO). */
    val textPanel = 440.dp
    /** Label column of label and value lists. */
    val definitionLabel = 220.dp
}

/** Inner padding of pill controls and of the options of a segmented track. */
object HlControl {
    val tabPaddingH = 16.dp
    val buttonPaddingH = 24.dp
    val buttonPaddingIcon = 20.dp
    /** Between an icon and its text inside a control. */
    val iconGap = 8.dp
    val badgePaddingH = 6.dp
    val badgePaddingV = 2.dp
}

/** How much a focused element grows: artwork the most, controls the least. */
object HlFocus {
    const val ARTWORK_SCALE = 1.1f
    const val CARD_SCALE = 1.06f
    const val CONTROL_SCALE = 1.04f
    /**
     * The other cards of a row step back while one of them has focus: darker by this much, and
     * with this much of their colour left. Partly, not grey: the wall stays inviting.
     */
    const val SIBLING_DIM = 0.3f
    const val SIBLING_SATURATION = 0.3f
    val glow = 18.dp
}
