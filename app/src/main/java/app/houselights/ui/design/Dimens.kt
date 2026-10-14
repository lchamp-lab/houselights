package app.houselights.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 4 dp grid. [safeH]/[safeV] keep content inside the TV overscan-safe area (~5 %). */
object HlSpace {
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
 * Corner radii follow hierarchy: printed artwork is nearly square, interactive controls are
 * pills, people are circles.
 */
object HlShapes {
    val artwork = RoundedCornerShape(4.dp)
    val field = RoundedCornerShape(6.dp)
    val panel = RoundedCornerShape(10.dp)
    val badge = RoundedCornerShape(3.dp)
    val control = RoundedCornerShape(percent = 50)
    val avatar = CircleShape
}

object HlSize {
    val poster = 112.dp
    /** Poster on the welcome wall. */
    val posterWall = 148.dp
    /** Poster thumbnail in a list (search results, source cards). */
    val posterThumb = 64.dp
    val landscape = 220.dp
    val episode = 264.dp
    val avatar = 76.dp
    /** Height of every control: pill buttons and round icon buttons line up. */
    val control = 48.dp
    val iconButton = control
    val icon = 20.dp
    /** Disc holding an icon in lists, status panels and settings. */
    val disc = 52.dp
    val discIcon = 24.dp
    /** Round mark over artwork (watched, selected). */
    val mark = 24.dp
    val markIcon = 16.dp
    /** Status dot (default track, online). */
    val dot = 8.dp
    /** An icon set in a line of text (rating star). */
    val inlineIcon = 16.dp
    val meter = 10.dp
    /** Progress shown beside text (time left). */
    val progressShort = 120.dp
    val progress = 3.dp
    /** The lit line under a selected tab. */
    val footlightWidth = 16.dp
    val footlightHeight = 2.dp
    val focusBorder = 2.dp
    val hairline = 1.dp
}

/** Widths and heights of layout parts. */
object HlLayout {
    /** Longest comfortable line of reading text. */
    val readable = 640.dp
    /** Text set beside artwork (heroes, detail pages). */
    val readableNarrow = 520.dp
    /** Side column of two-pane screens (settings, setup). */
    val sideColumn = 320.dp
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
    /** Width of dialogs. */
    val dialog = 860.dp
    val dialogWide = 1040.dp
    /** Dropdown menus (sort, filters). */
    val menuWidth = 320.dp
    val menuMaxHeight = 460.dp
    /** Label column of label and value lists. */
    val definitionLabel = 220.dp
}

/** Inner padding of tabs and pill controls: rows offset by it so text lines up with the safe area. */
object HlControl {
    val tabPaddingH = 16.dp
    val tabPaddingV = 8.dp
    /** A tab that is only an icon (settings). */
    val tabPaddingIcon = 10.dp
    val buttonPaddingH = 24.dp
    val buttonPaddingIcon = 20.dp
    /** Between an icon and its text inside a control. */
    val iconGap = 10.dp
    val menuItemPaddingV = 10.dp
    val badgePaddingH = 6.dp
    val badgePaddingV = 2.dp
}

/** How much a focused element grows: artwork the most, controls the least. */
object HlFocus {
    const val ARTWORK_SCALE = 1.1f
    const val CARD_SCALE = 1.06f
    const val CONTROL_SCALE = 1.04f
    /** Dim applied to the other cards of a row while one of them has focus. */
    const val SIBLING_DIM = 0.38f
    val glow = 18.dp
}
