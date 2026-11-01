package app.houselights.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
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
 * parallel: outer = inner + gap (a menu: 6 + 6 = 12; a focus frame: the picture's + its gap).
 */
object HlShapes {
    /** Images up to 64 dp: thumbnails, row previews, a saga's posters on its door. */
    val thumb = RoundedCornerShape(2.dp)
    val badge = RoundedCornerShape(2.dp)
    /** A mark over a picture, a tile's label: in a wide image [HlSpace.xs] in, parallel to its corner (4 + 8 = 12). */
    val mark = RoundedCornerShape(4.dp)
    /** Buttons, tiles, a tab's plate, menu options. */
    val control = RoundedCornerShape(6.dp)
    val field = RoundedCornerShape(6.dp)
    /** Setting and sheet rows. */
    val row = RoundedCornerShape(8.dp)
    /** Posters and portraits. */
    val artwork = RoundedCornerShape(8.dp)
    /** Wide images: stills, a backdrop to choose; a panel's radius. */
    val wide = RoundedCornerShape(12.dp)
    /** Menus, source cards, panels. */
    val panel = RoundedCornerShape(12.dp)
    /** A side sheet: its free corners rounded, square against the screen's edge. */
    val sheet = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
}

/**
 * Inner padding of containers, four steps, so the same kind of thing breathes the same everywhere: [tight] 8, [plate]
 * 20, [card] 24, [sheet] 32. A menu's 6 and a group's 4 follow from radii ([HlMenuDefaults], [HlSettingGroupDefaults]).
 * Words over a picture keep the inset of what holds them: a door's band [HlSpace.s], the case [HlSpace.m], a showcase
 * among a pane's plates [plate], a saga's open panel [card].
 */
object HlInset {
    /** Results holding a thumbnail. */
    val tight = HlSpace.xs

    /**
     * Words from the sides of any plate, whatever it holds (facts, definitions, a status, a table, glass read through, a
     * code, a showcase's posters): where a group's rows set theirs, so text keeps one edge down a pane; a status's state
     * from its top.
     */
    val plate = 20.dp

    /** Panels and cards all round; a plate's top and foot; words over a saga's open panel. */
    val card = HlSpace.l
    val sheet = HlSpace.xl
}

/**
 * Gaps named by the relation they set (DESIGN.md, Layout), read by components and screens instead of a step of
 * [HlSpace]: the same relation keeps the same gap from one screen to the next. Box to box (`Trim.Both`) unless said.
 */
object HlGap {
    /** A name to its note on one line: a setting's description, a role under a portrait. */
    val nameToNote = HlSpace.xxs

    /** A name to a note that may wrap: wider than the note's own lines, so it never lifts off its name. */
    val nameToNoteWrapped = HlSpace.xs

    /** A label over its value (a plate's fact, the settings door's state). */
    val labelToValue = HlSpace.xs

    /** A screen's or a task's title to its content. */
    val headToContent = HlSpace.l

    /** A screen's title to a line that belongs to it: a wall's info line, the film under « Affiche ». */
    val headToOwnLine = HlSpace.xs

    /** Between the lines of one block: facts under a title, a progress under facts. */
    val inBlock = HlSpace.s

    /** Between bands under a fixed hero (« À regarder »'s rows); a titled group from what precedes it. */
    val band = HlSpace.l

    /** Between the blocks of a page that scrolls by blocks (a detail page, search). */
    val block = HlSpace.xl

    /** Between plates without a title that follow one another. */
    val plates = HlSpace.m

    /** Between facts or formats on one line. */
    val fact = HlSpace.fact

    /** A 20 icon to its word in a row, a list, a menu or a field. */
    val iconToWord = HlSpace.s

    /** An icon to its word inside a control: a button, a key, the case's action. */
    val iconInControl = HlSpace.xs

    /** A dot, a light or a mark to its word. */
    val markToWord = HlSpace.xs

    /** A glyph in a line of facts (a star, a check) to its word. */
    val glyphToWord = HlSpace.xxs

    /** A picture to its caption. */
    val imageToCaption = HlSpace.s

    /** One paragraph to the next: more than a line's room. */
    val paragraph = HlSpace.m

    /** A sentence's first baseline under its title's: the title's line height (`subject` 32, `headline` 40). */
    @Composable
    @ReadOnlyComposable
    fun titleToBody(title: TextStyle): Dp = textRoom(title)
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
    /** A poster's shape, width to height: 2:3. */
    const val POSTER_RATIO = 2f / 3f
    /** Cast member column: the portrait, then a name of at most two lines. */
    val personColumn = 120.dp
    /** A button on a detail page; [controlCompact] in a screen's header. */
    val control = 44.dp
    val controlCompact = 40.dp
    /** A square icon button; [tileCompact] in a screen's header. */
    val tile = 44.dp
    val tileCompact = 40.dp
    /** Icons, on one scale (14 · 16 · 20 · 22 · 24): beside words, a place's, the search field's, a door's band's. */
    val icon = 20.dp
    /** An icon on a [tile]. */
    val tileIcon = 22.dp
    /** An icon larger than a line's: a panel button's, an empty preview's. */
    val largeIcon = 24.dp
    /** A mark over a picture (watched, chosen), on its plate. */
    val mark = 22.dp
    val markIcon = 14.dp
    /** The remote's Menu key, drawn where it opens options ([MenuKey]): its frame [keyEdge] wide, its lines [inlineIcon]. */
    val menuKeyWidth = 24.dp
    val menuKeyHeight = 22.dp
    val keyEdge = 1.5.dp
    /**
     * Dots, two: [dot] for a state (default track, online), the ember of new titles before a count, a door's, the current
     * place's word; a state that is off is its ring, [dotRing] wide (3 whole pixels at 1080p).
     */
    val dot = 8.dp
    val dotRing = 1.5.dp
    /** The dot of a title new to the library on its picture, ringed in black (`HlStroke.ring`) to stand off any picture. */
    val newDot = 10.dp
    /** The box a state's light sits in, so text lines up after a dot or the attention mark. */
    val statusLight = 16.dp
    /** An icon in a line of text (rating star). */
    val inlineIcon = 16.dp
    val meter = 10.dp
    /** A meter that is what a pane states (Stockage), across its plate. */
    val meterLarge = 14.dp
    /** The square naming a meter's part beside its figure ([MeterFact]). */
    val swatch = 10.dp
    /** Between two parts of a meter, so two greys never touch. */
    val meterGap = 2.dp
    /** How far one press of up or down scrolls a long text. */
    val scrollStep = 120.dp
    /** Least height of a setting row, in a pane as in a sheet. */
    val row = 52.dp
    /** Least height of a text field and of the search query: a setting row's. */
    val field = row
    /** A place in the settings' list of places: seven fit the safe area under its title. */
    val navRow = 48.dp
    /** A backdrop at its use, a gallery's wide image: 16:9. */
    const val PREVIEW_RATIO = 16f / 9f
    /** Progress shown beside text (time left), `HlStroke.line`. */
    val progressShort = 120.dp
    /** The progress in a hero (« À regarder », a person's page), before its time left, `HlStroke.bold` over its picture. */
    val heroProgress = 220.dp
    /** Placeholder rules for a title and its facts while a page loads. */
    val skeletonTitle = 48.dp
    val skeletonFacts = 24.dp
    /** The short rule heading every intertitle, `HlStroke.ring`. */
    val intertitleRule = 24.dp
    /** A word of a track (`HlSegmented`) on its plate: as tall as a compact tile. */
    val segment = 40.dp
    /** A folder's [Showcase]: four posters across, full height. */
    val showcase = 416.dp
    /**
     * A gallery's wide images beside its poster ([HlLayout.posterChoice]): a backdrop at 16:9, and under it a logo's
     * box at [LOGO_BOX_RATIO], together as tall as the poster with their names.
     */
    val galleryWide = 244.dp
    const val LOGO_BOX_RATIO = 244f / 85f
    /** The lockup's cap height, one size: at the head of About, on the screen title's baseline, and over the welcome wall (364 wide). */
    val wordmark = 26.dp
}

/**
 * Strokes, four weights in whole pixels at 1080p (DESIGN.md, Space and size); focus's frame keeps its own
 * ([HlFocus.frame]), a dot's ring its own ([HlSize.dotRing]).
 */
object HlStroke {
    /** An edge, a divider, a rule. */
    val hair = 1.dp
    /** A ring: focus's on a field, a read-only area or what cannot act; typing's; a new dot's; an intertitle's rule. */
    val ring = 2.dp
    /** A line beside words: progress there, a season's episodes along a door's band. */
    val line = 3.dp
    /** A line on a picture: progress at a poster's, a still's, a hero's and home's case's foot. */
    val bold = 4.dp
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
    val fieldShort = 120.dp
    /** Clear logo or title of a detail page. */
    val titleArtWidth = 440.dp
    val titleArtHeight = 104.dp
    /** Title line above a poster wall, in `rowTitle`: 28 at the default text size, growing with it. */
    val infoLine: Dp
        @Composable @ReadOnlyComposable
        get() = textRoom(infoLineAtDefault)
    private val infoLineAtDefault = 28.dp
    /** Overview of the focused episode under the episode row: three `body` lines (3 × 24). */
    val episodeOverview = 72.dp
    /** Between detail page blocks: wider than [HlFocus.edge], so the block above leaves as the next arrives. */
    val detailGap = HlSpace.xl
    /**
     * The first row's visible part under a detail hero: gap, header, its gap and focus room, a strip of images; and the
     * block gap again, which the actions' label room takes as its own ([HlActionRow]'s gap), so the row rises by it.
     */
    private fun detailPeek(header: Dp) = detailGap * 2 + header + HlSpace.xxs + HlFocus.room + HlSpace.s
    /** Least height of a detail hero on a [screen]: all but [detailPeek], keeping a [header] in the safe area. */
    internal fun detailHeroMin(screen: Dp, header: Dp): Dp = screen - detailPeek(header)

    /** [detailHeroMin] with the row's header at the viewer's text size (28 at the default one). */
    @Composable
    @ReadOnlyComposable
    fun detailHeroMin(screen: Dp): Dp = detailHeroMin(screen, textRoom(Hl.type.rowTitle))
    /** Where an error sits on an otherwise empty detail page. */
    val errorTop = 96.dp
    /** Columns of a poster wall ([app.houselights.ui.design.components.PosterWallGrid]). */
    const val WALL_COLUMNS = 7
    /** Between a wall's columns: [WALL_COLUMNS] posters of [HlSize.wallPoster] across the safe area. */
    val wallGutter = (CANVAS_WIDTH.dp - HlSpace.safeH * 2 - HlSize.wallPoster * WALL_COLUMNS) / (WALL_COLUMNS - 1)
    /** A poster at a gallery's size (180 × 270): Modifier's tile. */
    val posterChoice = 180.dp

    /**
     * The contact sheet where a picture is chosen (« la planche-contact »), its choices by the picture's shape, the wider
     * the fewer a line: [POSTER_CHOICES] posters of [posterChoiceCell] (88 × 132) beside the print ([choicePrint]), the
     * last column 4 short of the safe area's end; across the band at the screen's foot, [BACKDROP_CHOICES] backdrops of
     * [backdropChoiceCell] (160 × 90) and [LOGO_CHOICES] logos [logoChoiceCell] wide in a logo's box (204 × 71,
     * [HlSize.LOGO_BOX_RATIO]), a line starting at the safe area's start, its first frame reaching into the margin as on
     * a wall.
     */
    const val POSTER_CHOICES = 6
    const val BACKDROP_CHOICES = 5
    const val LOGO_CHOICES = 4
    val posterChoiceCell = 88.dp
    val backdropChoiceCell = 160.dp
    val logoChoiceCell = 204.dp

    /** Between the sheet's columns; its lines [HlSpace.l] apart, as a wall's. */
    val choiceGutter = HlSpace.s

    /** Between a section's name and what comes before it: sections stand a block apart. */
    val choiceSectionGap = HlSpace.xl

    /** Between a section's name and its first line: a search row's, its gap and its row's room. */
    val choiceNameGap = HlSpace.xxs + HlFocus.room

    /**
     * The name of the section of the line with focus, held over it as the sheet scrolls under it: laid out over the
     * wall's room the line keeps above it, drawn into it as far as puts it [choiceNameGap] from the line.
     */
    val choiceNameDrop = HlFocus.wallRoom - choiceNameGap

    /**
     * The words under a choice (« Automatique », a saga's film), wider than their picture as a person's name is wider
     * than their portrait: as wide as a [cell] and its gap, or as [across] of them when the next column has none; in the
     * line's [last] column, as wide as its picture, so they stop inside the safe area.
     */
    fun choiceWords(cell: Dp, across: Int, last: Boolean): Dp = if (last) cell else (cell + choiceGutter) * across

    /**
     * The print (« l'épreuve »): the poster with focus at its largest, 240 × 360, nearly three choices tall, beside the
     * sheet [choicePrintGap] away, its words' line under it; at a larger text size it gives up the height the head's
     * words take, never with focus.
     */
    val choicePrint = 240.dp
    val choicePrintGap = HlSpace.xl

    /**
     * The band a backdrop's or a logo's sheet takes at the screen's foot, under the title previewed in the light of the
     * choice with focus, at the viewer's text size: the held name of the section of the line with focus, the wall's room,
     * that line, a [cell] tall, its words (« Automatique », and a saga's film's year with [film]) inside the safe area,
     * then the gap to the next line and its top ([HlSpace.m]), cut by the screen's edge: 214 for a film's backdrops at
     * the default text size.
     */
    @Composable
    @ReadOnlyComposable
    fun choiceBand(cell: Dp, film: Boolean): Dp {
        val words = HlGap.imageToCaption + textRoom(Hl.type.cardName) + if (film) textRoom(Hl.type.meta) else HlSpace.none
        return textRoom(Hl.type.rowTitle) + HlFocus.wallRoom + cell + words + HlSpace.l + HlSpace.m
    }
    /** The two columns of the welcome screen: the choices' holds its title on one line in French. */
    val welcomeTagline = 380.dp
    val welcomeChoices = 456.dp
    /** A source's door on Welcome at its tallest: its picture, then a home door's band. */
    val welcomeDoor = 288.dp
    /** A table's value column ([app.houselights.ui.design.components.SpecTable]): Affichage's frame sizes. */
    val specFormat = 136.dp
    /** Label column of a reading sheet's definitions (an NFO's), its values at 16 beside it. */
    val readingLabel = 180.dp
    /**
     * A saga's polyptych inside the safe area by a focused frame's reach, so the first panel's frame and the last's stay
     * in it: 853 across the canvas, from 33.5 down to 33.5 above the foot (473 tall).
     */
    val voletSide = HlSpace.safeH + HlFocus.reach
    val voletTop = HlSpace.safeV + HlFocus.reach
}

// Component parts: measures a component keeps to itself; screens lay out with HlLayout.

/** A side sheet: 440 dp of content between its inset and the safe area, the page still in view. */
internal object HlSideSheetDefaults {
    val width = 520.dp

    /** A sheet holding the reading measure ([HlLayout.readable]) between the safe areas: an NFO, its XML's lines whole. */
    val wideWidth = 736.dp
}

/** A plate of definitions' label column ([PlateDefinition]): About's credits; a table's names ([SpecTable]). */
internal object PaneDefaults {
    val plateLabel = 172.dp
    val specName = 120.dp
}

/** The side column of two-pane screens: navigation (settings, leaving its pane 560), explanation (forms, leaving theirs 472). */
internal object TwoPaneDefaults {
    val side = 256.dp
    val sideWide = 344.dp
}

/**
 * Home's grid (DESIGN.md, Layout): the lockup's band, then the case on the left and three rows of doors across the
 * safe area: what is watched, the libraries, the tools. A row's doors share its width by spans of four.
 */
internal object HomeGridDefaults {
    /** The lockup's band under the safe area's top, and the gap from it to the grid. */
    val head = 20.dp
    val headGap = 20.dp

    /** The lockup's capitals: its bars as tall as the band less its air (16). */
    val lockup = 11.5.dp

    /** Library doors in their row; past it, the last gathers the rest. */
    const val SHELF_DOORS = 4

    /** The case: the title in progress, or one not seen yet. */
    val case = 252.dp
    val watchRow = 136.dp
    val libraryRow = 148.dp
    val toolRow = 136.dp

    /** A door's band, under its picture: its icon, its name and what it says, at the default text size; on one part, closer. */
    val band = 38.dp
    val bandGapNarrow = 6.dp

    /** The case's logo, and the next episode's over its still. */
    val caseLogoWidth = 188.dp
    val caseLogoHeight = 96.dp
    val stillLogoWidth = 108.dp
    val stillLogoHeight = 40.dp

    /** The case's action, a plate the case's focus fills, its icon `HlSize.inlineIcon`. */
    val action = 40.dp

    /** Between two episodes of a season along a band's top edge (`HlStroke.line`). */
    val segmentGap = 2.dp

    /** A count cut in posters: the air its longest keeps across and down, from the picture's start. */
    val figureAirX = 14.dp
    val figureAirY = 13.dp
    val figureStart = 13.dp

    /** A poster of the news door's band, as wide as its picture's height makes it at the default text size. */
    val stripPoster = 66.dp

    /** Two sagas stacked: their posters' width, and where each stands in the door's picture. */
    val sagaPoster = 54.dp
    val sagaBack = 62.dp
    val sagaFront = 22.dp
    val sagaBackTop = 8.dp
    val sagaFrontTop = 12.dp

    /** A small poster of the search door's wall, at least this wide. */
    val wallThumb = 32.dp

    /** The settings' state: between its two columns and its lines. */
    val stateColumns = 28.dp
    val stateRows = 11.dp
}

/**
 * Home's first scan (DESIGN.md, Layout): the folder's block on the margin and the wall of the titles found at the safe
 * area's end, both standing on its foot; « Aller à l'accueil » over the wall.
 */
internal object FirstScanDefaults {
    /** The wall: [COLUMNS] posters across ([HlSize.wallPoster]), [gap] apart both ways, at the safe area's end. */
    const val COLUMNS = 4
    val gap = HlSpace.xs

    /** Its frames: two whole lines, every one inside the safe area. */
    const val FRAMES = 8

    /** The wall's width: 4 × 112 + 3 × 8 = 472. */
    val wall = HlSize.wallPoster * COLUMNS + gap * (COLUMNS - 1)

    /** The block's column: the safe area less the wall and [HlSpace.xxl] (344), the folder's name set to its measure in it. */
    val column = HlLayout.CANVAS_WIDTH.dp - HlSpace.safeH * 2 - wall - HlSpace.xxl

    /** The block's line, as a hero's ([HlSize.heroProgress]). */
    val line = HlSize.heroProgress

    /** « Aller à l'accueil » over the wall's posters. */
    val buttonGap = HlSpace.m

    /** A step of the wall at most this often: a frame filled or renewed, so each frame keeps a title 16 s at least. */
    const val STEP_MS = 2_000L
}

/** The logo or title of a hero: « À regarder », a person's page; the overview's lines it shows, and its band. */
internal object HeroDefaults {
    val titleWidth = 380.dp
    val titleHeight = 56.dp
    const val OVERVIEW_LINES = 2

    /**
     * The band, [HlSpace.l] from its screen's title and its rows, measured in boxes at the viewer's text size: logo, a
     * line of facts or an episode's heading, two overview lines, [HlGap.inBlock] apart (56 + 12 + 16.3 + 12 + 40.3 =
     * 136.6 at 1), so its words stand on its foot and a focused poster's stays in the safe area.
     */
    val band: Dp
        @Composable get() = titleHeight + HlGap.inBlock * 2 +
            maxOf(textBox(Hl.type.meta), textBox(Hl.type.entry)) + textBox(Hl.type.body, OVERVIEW_LINES)
}

/**
 * A saga's polyptych (« Les volets »): its panels' widths, their numbers' place, the open panel's words' melt, and how a
 * panel opens.
 */
internal object VoletDefaults {
    /** The open panel at its narrowest: a hero's title box and a card's inset on each side (380 + 2 × 24), so its words never reflow. */
    val open = HeroDefaults.titleWidth + HlInset.card * 2

    /** Between two panels: narrow enough that they read as one object, a focused frame passing 2.5 from the next. */
    val gap = HlSpace.xs

    /** A closed panel, whatever the saga's length: a portrait's width, 30 % of its poster, four beside the saga open. */
    val closed = HlSize.portrait

    /** Over [melt] above its words' top an open panel's picture melts into its dark, whole [melt] under it. */
    val melt = 64.dp

    /** Least height of an open panel's words, bottom-anchored: logo, facts, two overview lines in lines (56 + 12 + 20 + 12 + 48). */
    val words = 148.dp

    /** A panel's number under its top edge. */
    val numberTop = HlSpace.m

    /**
     * The panels jump instead of opening (« greffe 3 »): every press does what a held arrow does, both widths at once,
     * then the picture, the words and the room once settled. Decided on the Zidoo before 0.4.0, never a setting.
     */
    const val opensAtOnce = false
}

/** The widest value on the right of a setting row, and the room above and below its words. */
internal object HlSettingRowDefaults {
    val valueWidth = 360.dp

    /** Above and below a row's words; a name over its description is 34.4 tall, so [HlSize.row] holds it at the default size. */
    val paddingV = HlSpace.xs
}

/** A group's plate around its rows: [HlShapes.panel] less [HlShapes.row], so their corners stay parallel. */
internal object HlSettingGroupDefaults {
    val inset = HlSpace.xxs
}

/** A [Showcase]'s words: from its sides and its foot, and where its name sets, over its posters' melted foot. */
internal object ShowcaseDefaults {
    val inset = HlInset.plate
    val nameTop = 126.dp
}

/** The label a tile shows under it with focus. */
internal object HlIconButtonDefaults {
    /** Widest label at the default text size, growing with it ([tileLabelWidth]); a longer one ends in an ellipsis. */
    val labelWidth = 280.dp

    /**
     * Room below a row of tiles for the focused label (gap, plate, one `entry` line): 36 at the default text size.
     * The label takes none of its own; [HlActionRow] keeps it.
     */
    val labelRoom: Dp
        @Composable @ReadOnlyComposable
        get() = HlSpace.xs + HlControl.labelPaddingV * 2 + textRoom(Hl.type.entry)

    /** What [labelRoom] adds for a label's second line, a tile's hint: 20 at the default text size. */
    val hintRoom: Dp
        @Composable @ReadOnlyComposable
        get() = textRoom(Hl.type.meta)

    /** Closest the label may come to a screen side: past the safe area, so an edge button keeps it centred. */
    val labelEdge = HlSpace.l
}

/** Menus anchored to a button (sort); their height is the room the screen leaves them. */
internal object HlMenuDefaults {
    val width = 320.dp
    /** Between the menu's edge and its options: [HlShapes.panel] less [HlShapes.control]. */
    val inset = 6.dp
}

/**
 * The technical sheet's format column: a track's format before its language, as wide as the block's widest format
 * and [HlSpace.m] between [formatColumn] and [formatColumnWide] ("Dolby Digital Plus" whole, a longer one on two
 * lines).
 */
internal object TechSpecsDefaults {
    val formatColumn = 188.dp
    val formatColumnWide = 280.dp
}

/**
 * Module 6.5 wide: whole pixels at 1080p (13) and 4K (26), so no module edge blurs. 29 modules plus the
 * 4-module quiet zone readers need make 240.5, which a phone reads from the sofa.
 */
internal object QrCodeDefaults {
    val module = 6.5.dp

    /** Beside its words in a pane (Trakt's code): 10 and 20 px, 185 dp with its quiet zone. */
    val moduleCompact = 5.dp
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
    /**
     * Steps of a receding card's colour and light, bounding how often its image draws again as it steps back: five, under
     * 8 % of light each; a layer's alpha would let a wall's field or a backdrop show through the card.
     */
    const val RECEDE_STEPS = 5

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
