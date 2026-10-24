package app.houselights.ui.design.components

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.LocalAmbient
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HomeGridDefaults
import app.houselights.ui.design.Scrub
import app.houselights.ui.design.colorStops
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.figureAt
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.plateEdge
import app.houselights.ui.design.textRoom
import app.houselights.ui.util.timeLeft
import app.houselights.ui.util.toPx
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Home's grid (DESIGN.md, Layout): the case and the doors, their pictures and the measures they share.

/**
 * Home's head over the grid: the lockup at the safe area's start, a passing [status] then [end] (a tile where home
 * shows no grid) at its end, then the gap to the grid.
 */
@Composable
fun HomeHead(status: String?, modifier: Modifier = Modifier, end: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV, bottom = HomeGridDefaults.headGap)
            .height(HomeGridDefaults.head),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lockup(HomeGridDefaults.lockup)
        Spacer(Modifier.weight(1f))
        // Taller than the band, they stand over it, never squeezed.
        if (status != null) Box(Modifier.wrapContentHeight(unbounded = true)) { PassingStatus(status) }
        if (end != null) {
            Spacer(Modifier.width(HlSpace.s))
            Box(Modifier.wrapContentHeight(unbounded = true)) { HeaderControls(end) }
        }
    }
}

/** A passing line (Back once on home), quiet, on a tile label's plate since a picture may be white there; read out as it comes. */
@Composable
internal fun PassingStatus(text: String) {
    val c = Hl.colors
    val light = LocalAmbient.current
    HlStatus(
        StatusState.Pending,
        text,
        Modifier
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .labelPlate(c, light)
            .padding(horizontal = HlControl.labelPaddingH, vertical = HlControl.labelPaddingV),
    )
}

/** A door in a row of the grid: [span] of the row's four parts, named [key] in the screen's focus memory. */
@Immutable
data class DoorCell(val key: String, val span: Int = 1, val content: @Composable (Modifier) -> Unit)

/**
 * Home's grid (DESIGN.md, Layout): the [case] down the left, named [caseKey] in [memory] (focusable while [hasCase]),
 * then [rows] of doors as tall as [heights], each row sharing its width by parts ([placeDoors]). Focus moves as the grid
 * reads: right from the case to the door [rightOfCase] names, left from a row's first door to the case, up and down to
 * the door over or under, the one last visited among several ([doorBelow]).
 */
@Composable
fun DoorGrid(
    memory: FocusMemory,
    caseKey: String,
    hasCase: Boolean,
    rightOfCase: String?,
    rows: List<List<DoorCell>>,
    heights: List<Dp>,
    modifier: Modifier = Modifier,
    case: @Composable (Modifier) -> Unit,
) {
    // Where focus is in the grid and the doors it visited, latest last: Down from a door over two goes to the one visited.
    val held = remember { GridFocus() }
    val gap = HlSpace.s
    val density = LocalDensity.current
    val placed = remember(rows.map { row -> row.map { it.key to it.span } }, heights, density) {
        with(density) { placeDoors(rows.map { row -> row.map { it.span } }, heights.map { it.toPx() }, gap.toPx()) }
    }
    val links = remember(rows, placed, memory) { DoorLinks(rows.map { row -> row.map { it.key } }, placed, memory) }
    FocusLeftBehind(held, rows.flatMap { row -> row.map { it.key } }, caseKey, hasCase, memory)
    Layout(
        content = {
            val right = rightOfCase?.let { memory.requester(it) }
            val toCase = memory.requester(caseKey)
            case(
                Modifier
                    .focusKey(caseKey, memory)
                    .onFocusChanged { if (it.isFocused) held.visit(caseKey) }
                    .focusProperties { if (right != null) this.right = right },
            )
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { i, cell ->
                    // By its key: a door that stays keeps its node, and focus, when others come or go.
                    key(cell.key) {
                        cell.content(
                            Modifier
                                .focusKey(cell.key, memory)
                                .onFocusChanged { if (it.isFocused) held.visit(cell.key) }
                                .focusProperties {
                                    if (i == 0) left = toCase
                                    up = links.next(placed[r][i], r - 1, held.recent)
                                    down = links.next(placed[r][i], r + 1, held.recent)
                                },
                        )
                    }
                }
            }
        },
        modifier = modifier.onFocusChanged { if (!it.hasFocus) held.at = null },
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val caseWidth = HomeGridDefaults.case.roundToPx()
        val left = caseWidth + gapPx
        val width = constraints.maxWidth - left
        val height = heights.sumOf { it.roundToPx() } + gapPx * (heights.size - 1).coerceAtLeast(0)
        val doors = placeDoors(rows.map { row -> row.map { it.span } }, heights.map { it.toPx() }, gapPx.toFloat(), width.toFloat())
        val caseP = measurables.first().measure(Constraints.fixed(caseWidth, height))
        val cells = measurables.drop(1).zip(doors.flatten()).map { (m, at) ->
            m.measure(Constraints.fixed(at.width.roundToInt(), at.height.roundToInt())) to at
        }
        layout(constraints.maxWidth, height) {
            caseP.place(0, 0)
            cells.forEach { (p, at) -> p.place(left + at.left.roundToInt(), at.top.roundToInt()) }
        }
    }
}

/**
 * Where focus goes when what held it leaves with a reload, wherever the removal put it: from a door, to the door last
 * visited still there, else the case; from the case, nothing left to propose, to the first door. Read as the grid
 * recomposes, before it goes.
 */
@Composable
private fun FocusLeftBehind(held: GridFocus, keys: List<String>, caseKey: String, hasCase: Boolean, memory: FocusMemory) {
    val gone = held.at
    val visited = held.recent
    LaunchedEffect(keys) {
        if (gone == null || gone == caseKey || gone in keys) return@LaunchedEffect
        withFrameNanos { }
        val back = visited.lastOrNull { it in keys }
        if (back == null || !memory.focus(back)) memory.focus(caseKey)
    }
    LaunchedEffect(hasCase) {
        if (hasCase || gone != caseKey) return@LaunchedEffect
        withFrameNanos { }
        keys.firstOrNull()?.let { memory.focus(it) }
    }
}

/** The door with focus ([at], none outside the grid) and the doors visited, latest last; plain fields, never observed. */
private class GridFocus {
    var at: String? = null
    var recent: List<String> = emptyList()

    fun visit(key: String) {
        at = key
        recent = recent - key + key
    }
}

/** The doors of a grid by their [keys] in [memory], where they are [placed]: which one up or down leads to. */
private class DoorLinks(private val keys: List<List<String>>, private val placed: List<List<Rect>>, private val memory: FocusMemory) {
    /** The door of row [to] reached from [from]: the one last visited among those it overlaps ([recent], latest last). */
    fun next(from: Rect, to: Int, recent: List<String>): FocusRequester {
        val row = keys.getOrNull(to) ?: return FocusRequester.Default
        val at = doorBelow(from, placed[to], recent.map { row.indexOf(it) }.filter { it >= 0 }) ?: return FocusRequester.Default
        return memory.requester(row[at])
    }
}

/**
 * Of the doors [row], the one reached from [from] going up or down: among those it overlaps across, the last
 * visited ([recent], indices latest last), else the one it overlaps most, the first of equals.
 */
internal fun doorBelow(from: Rect, row: List<Rect>, recent: List<Int>): Int? {
    val overlaps = row.map { min(from.right, it.right) - max(from.left, it.left) }
    val candidates = overlaps.indices.filter { overlaps[it] > 0f }
    if (candidates.isEmpty()) return null
    recent.lastOrNull { it in candidates }?.let { return it }
    return candidates.maxBy { overlaps[it] }
}

/**
 * Where each door of [spans] (per row, in parts of four) lies in a [width] beside the case, its rows [heights] tall,
 * [gap] apart: a row's parts are as wide as four would be with their gaps, so a door of two parts is as wide as
 * two of one and their gap, and doors of different rows line up. A row of three equal doors shares the width alike.
 */
internal fun placeDoors(spans: List<List<Int>>, heights: List<Float>, gap: Float, width: Float = DEFAULT_WIDTH): List<List<Rect>> {
    var top = 0f
    return spans.mapIndexed { r, row ->
        val parts = row.sum().coerceAtLeast(1)
        val unit = (width - gap * (parts - 1)) / parts
        var left = 0f
        val height = heights.getOrElse(r) { 0f }
        val rects = row.map { span ->
            val w = unit * span + gap * (span - 1)
            Rect(left, top, left + w, top + height).also { left += w + gap }
        }
        top += height + gap
        rects
    }
}

/** The grid's width beside the case on the TV canvas (600), for neighbours worked out before it is measured. */
private val DEFAULT_WIDTH = HlLayout.CANVAS_WIDTH - HlSpace.safeH.value * 2 - HomeGridDefaults.case.value - HlSpace.s.value

/** How wide a door of [span] parts of four stands in a row of the grid on the TV canvas. */
fun doorWidth(span: Int): Dp {
    val gap = HlSpace.s.value
    val unit = (DEFAULT_WIDTH - gap * (PARTS - 1)) / PARTS
    return (unit * span + gap * (span - 1)).dp
}

private const val PARTS = 4

/** What a door's band says after its name. */
@Immutable
sealed interface DoorNote {
    /** Plain words, quiet ("Il reste 48 min"). */
    data class Words(val text: String) : DoorNote

    /** New titles, after the ember dot ("4 nouveaux"). */
    data class News(val text: String) : DoorNote

    /** A mark printed as on a case's back ("S1 · É4", a count). */
    data class Mark(val text: String) : DoorNote
}

/** Along a door's band's top edge: the episodes of a season, those watched lit, or a title's progress. */
@Immutable
sealed interface DoorProgress {
    data class Episodes(val watched: List<Boolean>) : DoorProgress

    data class Seen(val fraction: Float) : DoorProgress
}

/**
 * A door of home's grid (DESIGN.md, Layout): its [picture] over a band of the room's glass holding its [icon], its
 * [name] in the poster's voice and its [note], [progress] along the band's edge. Framed with focus, it glows in its
 * picture's tone ([glow]) or across its posters' ([glowTones]); [picture] is told when it is lit.
 */
@Composable
fun HomeDoor(
    name: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: DoorNote? = null,
    progress: DoorProgress? = null,
    glow: (() -> Color)? = null,
    glowTones: (() -> List<Color>)? = null,
    picture: @Composable BoxScope.(lit: Boolean) -> Unit,
) {
    val c = Hl.colors
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.frame(rest = c.glass, restInk = c.content, edge = BorderStroke(HlSize.hairline, c.plateEdge)),
        shape = HlShapes.control,
        modifier = modifier,
        glow = glow,
        glowTones = glowTones,
    ) { lit ->
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) { picture(lit) }
            DoorBand(name, icon, note, progress)
        }
    }
}

/** A door's band: its edge, icon, name and note, the note dropped whole when the name leaves it no room. */
@Composable
private fun DoorBand(name: String, icon: ImageVector, note: DoorNote?, progress: DoorProgress?) {
    val c = Hl.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(textRoom(HomeGridDefaults.band))
            .background(c.ambientDeep)
            .drawBehind { drawRect(c.plateEdge, size = Size(size.width, HlSize.hairline.toPx())) },
    ) {
        BandLine(Modifier.align(Alignment.CenterStart).padding(horizontal = HlSpace.s)) {
            Icon(icon, contentDescription = null, tint = c.content, modifier = Modifier.size(HomeGridDefaults.bandIcon))
            Text(name, style = Hl.type.cardName, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { NoteWords(it) }
        }
        progress?.let { BandEdge(it) }
    }
}

/**
 * A band's icon, then its name, then at its end its note while the three fit; a name too long for its icon goes without
 * it. A door of one part ([doorWidth]) keeps them closer.
 */
@Composable
private fun BandLine(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val narrow = constraints.maxWidth < (doorWidth(2) - HlSpace.s * 2).roundToPx()
        val gap = (if (narrow) HomeGridDefaults.bandGapNarrow else HlSpace.xs).roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nameWidth = measurables[1].maxIntrinsicWidth(loose.maxHeight)
        val icon = measurables[0].measure(loose)
        val iconed = nameWidth + icon.width + gap <= loose.maxWidth
        val start = if (iconed) icon.width + gap else 0
        val note = measurables.getOrNull(2)
        val noteWidth = note?.maxIntrinsicWidth(loose.maxHeight) ?: 0
        // The note keeps a word's room after the name, or goes.
        val noted = note != null && start + nameWidth + gap * 2 + noteWidth <= loose.maxWidth
        val name = measurables[1].measure(loose.copy(maxWidth = (loose.maxWidth - start).coerceAtLeast(0)))
        val placedNote = if (noted) note?.measure(loose) else null
        val height = maxOf(icon.height, name.height, placedNote?.height ?: 0)
        layout(loose.maxWidth, height) {
            if (iconed) icon.placeRelative(0, (height - icon.height) / 2)
            name.placeRelative(start, (height - name.height) / 2)
            placedNote?.placeRelative(loose.maxWidth - placedNote.width, (height - placedNote.height) / 2)
        }
    }
}

@Composable
private fun NoteWords(note: DoorNote) {
    val c = Hl.colors
    when (note) {
        is DoorNote.Words -> Text(note.text, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1)
        is DoorNote.Mark -> Text(note.text, style = Hl.type.jaquette, color = c.contentSecondary, maxLines = 1)
        is DoorNote.News -> Row(verticalAlignment = Alignment.CenterVertically) {
            // The ember dot says new; the words say how many.
            Box(Modifier.size(HlSize.doorDot).background(c.ember, CircleShape))
            Spacer(Modifier.width(HlSpace.xs))
            Text(note.text, style = Hl.type.meta, color = c.content, maxLines = 1)
        }
    }
}

/** The band's top edge: a season's episodes, those watched in the ember light across the whole edge, or a title's part seen. */
@Composable
private fun BoxScope.BandEdge(progress: DoorProgress) {
    val c = Hl.colors
    Spacer(
        Modifier.align(Alignment.TopStart).fillMaxWidth().height(HomeGridDefaults.segment).drawWithCache {
            val light = c.emberLight(across = size.width)
            val track = c.content.copy(alpha = HlAlpha.FILL_STRONG)
            onDrawBehind {
                when (progress) {
                    is DoorProgress.Seen -> {
                        drawRect(track)
                        drawRect(light, size = Size(size.width * progress.fraction.coerceIn(0f, 1f), size.height))
                    }
                    is DoorProgress.Episodes -> {
                        val n = progress.watched.size
                        val gap = HomeGridDefaults.segmentGap.toPx()
                        val w = (size.width - gap * (n - 1)) / n
                        progress.watched.forEachIndexed { k, seen ->
                            val at = Offset(k * (w + gap), 0f)
                            if (seen) drawRect(light, at, Size(w, size.height)) else drawRect(track, at, Size(w, size.height))
                        }
                    }
                }
            }
        },
    )
}

/**
 * Home's case (DESIGN.md, Layout): a title's backdrop cropped upright (else its poster) melting into its dark, under
 * its [label], its logo or name, its facts, then its progress and time left or its genres, and its [action], a plate
 * the case's focus fills white, after the Play glyph when it [plays]. OK does [onClick]; Menu or a long OK,
 * [onLongClick], which the Menu key says at the plate's end while the case has focus.
 */
@Composable
fun ResumeCase(
    item: MediaItem,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    plays: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val picture = caseUrl(item)
    val glow = rememberGlowColor(picture, focused && !Scrub.active)
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.frame(rest = c.ambientDeep, restInk = c.content, edge = BorderStroke(HlSize.hairline, c.plateEdge)),
        shape = HlShapes.control,
        modifier = modifier,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        glow = { glow.value },
        interactionSource = interaction,
    ) { lit ->
        Box(Modifier.fillMaxSize()) {
            CasePicture(item, picture)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(HlSpace.m)) {
                label?.let {
                    Text(it, style = Hl.type.jaquette, color = c.contentSecondary, maxLines = 1)
                    Spacer(Modifier.height(HlSpace.xs))
                }
                val episode = item.kind == MediaKind.EPISODE
                TitleArt(
                    title = if (episode) item.seriesTitle ?: item.title else item.title,
                    logo = item.logo,
                    maxWidth = HomeGridDefaults.caseLogoWidth,
                    maxHeight = HomeGridDefaults.caseLogoHeight,
                    style = Hl.type.headline,
                )
                Spacer(Modifier.height(HlSpace.s))
                if (episode) {
                    EpisodeHeading(item.episodeLabel, item.title, style = Hl.type.entry)
                } else {
                    // Closer than a page's facts: the case's column is narrow, and its score stays.
                    MetaLine(item, showGenres = false, spacing = HlSpace.s)
                }
                Spacer(Modifier.height(HlSpace.s))
                val left = timeLeft(item)
                if (item.progress > 0f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(item.progress, Modifier.weight(1f), thickness = HlSize.heroProgressWeight)
                        left?.let {
                            Spacer(Modifier.width(HlSpace.s))
                            Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1)
                        }
                    }
                } else if (item.genres.isNotEmpty()) {
                    Text(
                        item.genres.take(CASE_GENRES).joinToString(FACT_JOIN),
                        style = Hl.type.meta,
                        color = c.contentSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(HlSpace.s))
                CaseAction(action, lit, plays, menu = onLongClick != null)
            }
        }
    }
}

/** The case's picture: its backdrop cropped upright from its top, else its poster; a title alone set as a one-sheet. */
@Composable
private fun BoxScope.CasePicture(item: MediaItem, picture: String?) {
    val c = Hl.colors
    val width = HomeGridDefaults.case.toPx()
    val height = caseHeight().toPx()
    ArtworkImage(
        picture,
        null,
        Modifier.fillMaxSize(),
        size = IntSize(width, height),
        alignment = Alignment.TopCenter,
    ) { FallbackArtwork(item.title, item.year) }
    // The picture melts into the case's dark under its words.
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(*HlScrim.CASE_FOOT.colorStops(c.ambientDeep))))
}

/**
 * The case's action: its words on a quiet plate, white with black ink once the case is [lit], then with the Menu key at
 * its end when it has options ([menu]); nothing moves for it.
 */
@Composable
private fun CaseAction(action: String, lit: Boolean, plays: Boolean, menu: Boolean) {
    val c = Hl.colors
    val ink = FocusStyle.ink(lit)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HomeGridDefaults.action)
            .background(FocusStyle.heldPlate(lit, c.content.copy(alpha = HlAlpha.FILL)), HlShapes.control)
            .then(if (lit) Modifier else Modifier.border(HlSize.hairline, c.plateEdge, HlShapes.control)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (plays) {
                Icon(HlIcons.Play, contentDescription = null, tint = ink, modifier = Modifier.size(HomeGridDefaults.actionIcon))
                Spacer(Modifier.width(HlSpace.xs))
            }
            Text(action, style = Hl.type.cardName, color = ink, maxLines = 1)
        }
        if (menu && lit) MenuKey(Modifier.align(Alignment.CenterEnd).padding(end = HlSpace.xs), ink = ink)
    }
}

/** How tall the case stands: the grid's three rows and their gaps. */
internal fun caseHeight(): Dp = HomeGridDefaults.watchRow + HomeGridDefaults.libraryRow + HomeGridDefaults.toolRow + HlSpace.s * 2

/** The case's picture: its backdrop, asked as wide as it is cropped from, else its poster. */
@Composable
private fun caseUrl(item: MediaItem): String? {
    val height = caseHeight().toPx()
    val width = HomeGridDefaults.case.toPx()
    return item.backdrop?.url(height * WIDE_W / WIDE_H) ?: item.poster?.url(width)
}

/** The request [ResumeCase] makes for [item] on a canvas of [density] px a dp, as a preload must make it. */
internal fun caseRequest(context: Context, item: MediaItem, px: (Dp) -> Int): ImageRequest? {
    val height = px(caseHeight())
    val url = item.backdrop?.url(height * WIDE_W / WIDE_H) ?: item.poster?.url(px(HomeGridDefaults.case)) ?: return null
    return doorRequest(context, url, px(HomeGridDefaults.case), height)
}

/** The request of a picture laid out [widthPx] by [heightPx], cropped to that shape, as [ArtworkImage] makes it. */
private fun doorRequest(context: Context, url: String, widthPx: Int, heightPx: Int): ImageRequest =
    ImageRequest.Builder(context).data(url).size(widthPx, heightPx).scale(Scale.FILL).precision(Precision.INEXACT).build()

private const val WIDE_W = 16
private const val WIDE_H = 9
private const val CASE_GENRES = 3
private const val FACT_JOIN = " · "

/**
 * A door's still (the next episode's): its backdrop at the door's width, cropped about its upper middle, black rising
 * under its logo or name at its foot.
 */
@Composable
fun DoorStill(item: MediaItem, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val show = item.kind == MediaKind.EPISODE
    Box(modifier.fillMaxSize()) {
        val width = stillWidth().toPx()
        ArtworkImage(
            item.backdrop?.url(width),
            null,
            Modifier.fillMaxSize(),
            size = IntSize(width, width * WIDE_H / WIDE_W),
        ) { FallbackArtwork(if (show) item.seriesTitle ?: item.title else item.title, null) }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(*HlScrim.STILL_FOOT.colorStops(c.background))))
        TitleArt(
            title = if (show) item.seriesTitle ?: item.title else item.title,
            logo = item.logo,
            maxWidth = HomeGridDefaults.stillLogoWidth,
            maxHeight = HomeGridDefaults.stillLogoHeight,
            style = Hl.type.rowTitle,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = HlSpace.s, bottom = HlSpace.xs),
        )
    }
}

/** The width a door's still is asked at: half the grid beside the case, as the row of what is watched lays it. */
private fun stillWidth(): Dp = doorWidth(2)

/** The request [DoorStill] makes for [item], as a preload must make it. */
internal fun stillDoorRequest(context: Context, item: MediaItem, px: (Dp) -> Int): ImageRequest? {
    val width = px(stillWidth())
    return item.backdrop?.let { doorRequest(context, it.url(width), width, width * WIDE_H / WIDE_W) }
}

/** A band of [items]' posters side by side from the door's start, as tall as its picture, the last cut by its edge. */
@Composable
fun DoorPosters(items: List<MediaItem>, modifier: Modifier = Modifier) {
    val width = HomeGridDefaults.stripPoster.toPx()
    val decoded = IntSize(width, (width / HlSize.POSTER_RATIO).roundToInt())
    Layout(
        content = {
            items.forEach { item ->
                ArtworkImage(item.poster?.url(width), null, Modifier.fillMaxSize(), size = decoded) {
                    FallbackArtwork(item.title, item.year)
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val gap = HlSpace.xxs.roundToPx()
        val h = constraints.maxHeight
        val w = (h * HlSize.POSTER_RATIO).roundToInt()
        val placeables = measurables.map { it.measure(Constraints.fixed(w, h)) }
        layout(constraints.maxWidth, h) {
            placeables.forEachIndexed { k, p -> (k * (w + gap)).takeIf { it < constraints.maxWidth }?.let { p.place(it, 0) } }
        }
    }
}

/** Two sagas' posters, the first in front, the second behind it in its shade. */
@Composable
fun DoorSagas(items: List<MediaItem>, modifier: Modifier = Modifier) {
    val width = HomeGridDefaults.sagaPoster.toPx()
    val decoded = IntSize(width, (width / HlSize.POSTER_RATIO).roundToInt())
    val shade = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(SAGA, SAGA, SAGA, 1f) }) }
    Box(modifier.fillMaxSize()) {
        items.getOrNull(1)?.let { back ->
            SagaPoster(back, width, decoded, Modifier.offset(HomeGridDefaults.sagaBack, HomeGridDefaults.sagaBackTop), shade)
        }
        items.firstOrNull()?.let { front ->
            SagaPoster(front, width, decoded, Modifier.offset(HomeGridDefaults.sagaFront, HomeGridDefaults.sagaFrontTop), null)
        }
    }
}

private const val SAGA = HlScrim.SAGA_BEHIND

@Composable
private fun SagaPoster(item: MediaItem, px: Int, decoded: IntSize, modifier: Modifier, filter: ColorFilter?) {
    ArtworkImage(
        item.poster?.url(px),
        null,
        modifier.width(HomeGridDefaults.sagaPoster).height(HomeGridDefaults.sagaPoster / HlSize.POSTER_RATIO).clip(HlShapes.thumb),
        size = decoded,
        filter = filter,
    ) { FallbackArtwork(item.title, item.year) }
}

/**
 * A wall of [items]' small posters, out, from the door's top, fading into its glass at its foot: what a search reaches.
 * It fills the door, [items] taken again in turn when fewer than it holds ([wallThumbs]).
 */
@Composable
fun DoorWall(items: List<MediaItem>, modifier: Modifier = Modifier) {
    val width = HomeGridDefaults.wallThumb.toPx()
    val decoded = IntSize(width, (width / HlSize.POSTER_RATIO).roundToInt())
    val out = remember {
        val m = ColorMatrix().apply { setToSaturation(HlScrim.WALL_THUMB_SATURATION) }
        m *= ColorMatrix().apply { setToScale(WALL, WALL, WALL, 1f) }
        ColorFilter.colorMatrix(m)
    }
    val c = Hl.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        val shown = remember(items, maxWidth, maxHeight) { items.cycledTo(wallThumbs(maxWidth, maxHeight)) }
        WallThumbs(shown, width, decoded, out)
        // The wall fades into the door's glass towards its band.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(*HlScrim.WALL_FOOT.colorStops(c.glass))))
    }
}

@Composable
private fun WallThumbs(items: List<MediaItem>, width: Int, decoded: IntSize, out: ColorFilter) {
    Layout(
        content = {
            items.forEach { item ->
                ArtworkImage(item.poster?.url(width), null, Modifier.clip(HlShapes.thumb), size = decoded, filter = out) {
                    Box(Modifier.fillMaxSize().background(Hl.colors.content.copy(alpha = HlAlpha.FILL_FAINT)))
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val gap = HlSpace.xxs.roundToPx()
        val thumb = HomeGridDefaults.wallThumb.roundToPx()
        val columns = ((constraints.maxWidth + gap) / (thumb + gap)).coerceAtLeast(1)
        val w = (constraints.maxWidth - gap * (columns - 1)) / columns
        val h = (w / HlSize.POSTER_RATIO).roundToInt()
        val placeables = measurables.map { it.measure(Constraints.fixed(w, h)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { k, p ->
                val y = (k / columns) * (h + gap)
                if (y < constraints.maxHeight) p.place((k % columns) * (w + gap), y)
            }
        }
    }
}

private const val WALL = HlScrim.WALL_THUMB_LIGHT

/** [count] of these, from the first, taken again in turn when there are fewer; none when there are none. */
internal fun <T> List<T>.cycledTo(count: Int): List<T> = if (isEmpty()) emptyList() else List(count) { this[it % size] }

/** How many small posters [DoorWall] lays across [width] and down [height] (dp). */
internal fun wallThumbs(width: Dp, height: Dp): Int {
    val gap = HlSpace.xxs.value
    val thumb = HomeGridDefaults.wallThumb.value
    val columns = floor((width.value + gap) / (thumb + gap)).toInt().coerceAtLeast(1)
    val w = (width.value - gap * (columns - 1)) / columns
    val rows = ceil((height.value + gap) / (w / HlSize.POSTER_RATIO + gap)).toInt()
    return columns * rows
}

/** A poster a count is cut in, read for its pixels, with its tone for the door's glow. */
@Immutable
class CutPoster(val bitmap: Bitmap, val tone: Color) {
    @Volatile
    private var scaled: Bitmap? = null

    /** This poster [height] px tall, as its digit draws it, if made already ([scaledTo]). */
    internal fun scaledOrNull(height: Int): Bitmap? = if (height == bitmap.height) bitmap else scaled?.takeIf { it.height == height }

    /** This poster [height] px tall, made once and kept: never on the main thread, as the door draws on. */
    internal fun scaledTo(height: Int): Bitmap = scaledOrNull(height) ?: synchronized(this) {
        scaledOrNull(height) ?: run {
            val width = (bitmap.width * height / bitmap.height.toFloat()).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, width, height, true).also { scaled = it }
        }
    }
}

/**
 * The posters a door's count is cut in, their [tones] and their tint ([doorTint]), loaded; empty until they are. Let go
 * ([CutPosterCache.clear]), only the tones and the tint stay, the digits ink until the posters are cut again.
 */
@Immutable
class CutPosters(
    val posters: List<CutPoster?> = emptyList(),
    val tint: Bitmap? = null,
    val tones: List<Color> = posters.mapNotNull { it?.tone },
) {
    internal val whole: Boolean get() = posters.any { it != null }

    internal fun faded() = CutPosters(tint = tint, tones = tones)
}

/** The request of a poster a count is cut in, its pixels readable and kept apart from its card's in memory. */
internal fun cutPosterRequest(context: Context, item: MediaItem, px: (Dp) -> Int): ImageRequest? {
    val width = px(HlSize.poster)
    val url = item.poster?.url(width) ?: return null
    return doorRequest(context, url, width, (width / HlSize.POSTER_RATIO).roundToInt()).newBuilder()
        .allowHardware(false)
        .setParameter(CUT, true)
        .build()
}

/** Added to a cut poster's memory cache key: its software copy never stands for its card's. */
private const val CUT = "houselights.cut"

/**
 * The posters of [items] a door's count is cut in, with their tint: those made last at once, else loaded off the main
 * thread, the posters shown until then staying, so a count reloaded never falls back to ink; let go, their colours at
 * once. Kept in the app's [LocalCutPosters], else in the door's own.
 */
@Composable
fun rememberCutPosters(items: List<MediaItem>): CutPosters {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cuts = LocalCutPosters.current ?: remember { CutPosterCache() }
    val keys = remember(items) { cutKeys(items) }
    var shown by remember { mutableStateOf(cuts.shown(keys) ?: CutPosters()) }
    LaunchedEffect(keys) {
        shown = cuts.of(keys)
            ?: if (items.isEmpty()) CutPosters() else cuts.make(context, items) { with(density) { it.roundToPx() } }
    }
    return shown
}

/** The titles and their posters a cut is made of: a poster changed is another cut. */
internal fun cutKeys(items: List<MediaItem>): List<Any?> = items.flatMap { listOf(it.id, it.poster) }

/** The cut posters the app keeps ([CutPosterCache]); null outside it, each door keeping its own. */
val LocalCutPosters = staticCompositionLocalOf<CutPosterCache?> { null }

/**
 * The cut posters made last, by their titles and posters: a door coming back, or opened under the launch screen, draws
 * them at once. [kept]: home's library doors, and one more for a door whose posters changed, so it never takes another's.
 * The app holds one ([app.houselights.AppContainer.cutPosters]) and lets its posters go when the player needs the memory.
 */
class CutPosterCache(private val kept: Int = HomeGridDefaults.SHELF_DOORS + 1) {
    private val made = object : LinkedHashMap<List<Any?>, CutPosters>(kept, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, CutPosters>) = size > kept
    }
    private val underWay = HashMap<List<Any?>, CompletableDeferred<CutPosters>>()

    /** The cut of [keys], its posters loaded; null when not made, or let go. */
    @Synchronized
    internal fun of(keys: List<Any?>): CutPosters? = made[keys]?.takeIf { it.whole }

    /** What a door draws of [keys] at once: its cut, else its tones and tint once let go ([clear]). */
    @Synchronized
    internal fun shown(keys: List<Any?>): CutPosters? = made[keys]

    @Synchronized
    internal fun keep(keys: List<Any?>, posters: CutPosters) {
        made[keys] = posters
    }

    /** Lets every poster go, software copies the memory clear of Coil does not reach; tones and tints stay. */
    @Synchronized
    fun clear() {
        made.replaceAll { _, cut -> cut.faded() }
    }

    /** As Coil trims its own memory: in the background, short of memory, the posters go ([clear]). */
    fun trim(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) clear()
    }

    /**
     * Loads the posters of [items] a door's count is cut in and keeps them, so the door shows them at once; for preloads
     * too. A making already under way for the same posters is awaited, not made again.
     */
    suspend fun make(context: Context, items: List<MediaItem>, px: (Dp) -> Int): CutPosters {
        val keys = cutKeys(items)
        of(keys)?.let { return it }
        val (making, mine) = making(keys)
        if (!mine) {
            return try {
                making.await()
            } catch (gone: CancellationException) {
                // Its maker went away: this one makes them, unless it is going away itself.
                if (!currentCoroutineContext().isActive) throw gone
                make(context, items, px)
            }
        }
        return try {
            loadCutPosters(context, items, px).also { made ->
                if (made.whole) keep(keys, made)
                making.complete(made)
            }
        } finally {
            // Cancelled or failed: whoever waits makes them again.
            if (!making.isCompleted) making.cancel()
            done(keys, making)
        }
    }

    /**
     * The making of [keys] under way, and whether the caller is to make it. One cancelled is never handed out: a waiter
     * its cancelling resumes before its maker is [done] makes them itself rather than await it again.
     */
    @Synchronized
    private fun making(keys: List<Any?>): Pair<CompletableDeferred<CutPosters>, Boolean> =
        underWay[keys]?.takeIf { !it.isCancelled }?.let { it to false }
            ?: CompletableDeferred<CutPosters>().also { underWay[keys] = it }.let { it to true }

    @Synchronized
    private fun done(keys: List<Any?>, making: CompletableDeferred<CutPosters>) {
        if (underWay[keys] === making) underWay.remove(keys)
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}

/** Each poster of [items] loaded once, though a count may be cut in one twice. */
private suspend fun loadCutPosters(context: Context, items: List<MediaItem>, px: (Dp) -> Int): CutPosters = coroutineScope {
    val distinct = items.distinctBy { it.id }
    val bitmaps = distinct.map { item ->
        async {
            cutPosterRequest(context, item, px)?.let { request ->
                (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            }
        }
    }.awaitAll()
    withContext(Dispatchers.Default) {
        val loaded = distinct.zip(bitmaps).associate { (item, bitmap) ->
            item.id to bitmap?.let { CutPoster(it, lightOf(paletteOf(it)).glow) }
        }
        val posters = items.map { loaded[it.id] }
        CutPosters(posters, doorTint(posters.mapNotNull { it?.bitmap }))
    }
}

/**
 * A door's tint: its [posters] side by side, each cropped to fill its slot about its upper part, averaged down to
 * [TINT_W] × [TINT_H] points; enlarged with bilinear filtering, it reads as their colours blurred. Null without any.
 */
internal fun doorTint(posters: List<Bitmap>): Bitmap? {
    if (posters.isEmpty()) return null
    val points = posters.map { poster ->
        val argb = IntArray(poster.width * poster.height)
        poster.getPixels(argb, 0, poster.width, 0, 0, poster.width, poster.height)
        Points(argb, poster.width, poster.height)
    }
    return Bitmap.createBitmap(averagedStrip(points, TINT_W, TINT_H), TINT_W, TINT_H, Bitmap.Config.ARGB_8888)
}

/** [posters] side by side across [width] × [height], each point the mean of what it covers, a poster cropped about its top fifth. */
internal fun averagedStrip(posters: List<Points>, width: Int, height: Int): IntArray {
    val strip = IntArray(width * height)
    posters.forEachIndexed { i, poster ->
        val from = i * width / posters.size
        val slot = (i + 1) * width / posters.size - from
        if (slot <= 0) return@forEachIndexed
        val scale = max(slot / poster.width.toFloat(), height / poster.height.toFloat())
        val cropW = slot / scale
        val cropH = height / scale
        val left = (poster.width - cropW) / 2
        val top = (poster.height - cropH) * TINT_TOP
        for (y in 0 until height) {
            for (x in 0 until slot) {
                val x0 = (left + x * cropW / slot).toInt().coerceIn(0, poster.width - 1)
                val x1 = (left + (x + 1) * cropW / slot).toInt().coerceIn(x0 + 1, poster.width)
                val y0 = (top + y * cropH / height).toInt().coerceIn(0, poster.height - 1)
                val y1 = (top + (y + 1) * cropH / height).toInt().coerceIn(y0 + 1, poster.height)
                strip[y * width + from + x] = meanOf(poster, x0, x1, y0, y1)
            }
        }
    }
    return strip
}

private fun meanOf(poster: Points, x0: Int, x1: Int, y0: Int, y1: Int): Int {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val p = poster.argb[y * poster.width + x]
            r += (p shr RED) and LEVEL
            g += (p shr GREEN) and LEVEL
            b += p and LEVEL
            n++
        }
    }
    if (n == 0L) return OPAQUE_BLACK
    return OPAQUE_BLACK or ((r / n).toInt() shl RED) or ((g / n).toInt() shl GREEN) or (b / n).toInt()
}

private const val TINT_W = 24
private const val TINT_H = 9
private const val TINT_TOP = 0.15f
private const val RED = 16
private const val GREEN = 8
private const val LEVEL = 0xFF
private const val OPAQUE_BLACK = -0x1000000

/**
 * A library's door picture: [count], each digit cut in one of [posters] (the next poster for each, its upper half),
 * edged by a fine line of ink, at the [size] every library's door shares ([figureSize]); a digit without its poster
 * is ink. Under it, the posters' tint ([CutPosters.tint]), dark at rest and lit whole once [lit].
 */
@Composable
fun DoorFigures(count: String, posters: CutPosters, size: TextUnit, lit: Boolean, modifier: Modifier = Modifier, readAs: String? = null) {
    val c = Hl.colors
    // Laid out and cut once for a count, its posters and its size: focus only lights the tint.
    val figure = rememberCutFigure(count, posters, size)
    val tints = remember { ScaledTint() }
    val tint = animateFloatAsState(
        if (lit) 1f else HlAlpha.DOOR_TINT,
        if (lit) tween(HlMotion.FOCUS_IN, easing = HlMotion.focusPull) else tween(HlMotion.FOCUS_OUT, easing = HlMotion.fallBack),
        label = "doorTint",
    )
    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics { (readAs ?: count).let { contentDescription = it } }
            .drawWithCache {
                val start = HomeGridDefaults.figureStart.toPx()
                val origin = figure?.let { Offset(start, (this.size.height - it.layout.size.height) / 2) } ?: Offset.Zero
                val edge = Stroke(HlSize.hairline.toPx())
                val ink = SolidColor(c.content)
                val line = SolidColor(c.content.copy(alpha = HlAlpha.FIGURE_EDGE))
                val tintPaint = posters.tint?.let { tintPaint(tints.at(it, this.size)) }
                onDrawBehind {
                    tintPaint?.let { paint ->
                        // Its light read while drawing: focus redraws this door only.
                        paint.alpha = (tint.value * OPAQUE).roundToInt()
                        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, this.size.width, this.size.height, paint) }
                    }
                    val cut = figure ?: return@onDrawBehind
                    // The layout's paint keeps what it was last given: each pass says its brush, its opacity and its style.
                    cut.digits.forEach { digit ->
                        clipRect(origin.x + digit.left, 0f, origin.x + digit.right, this.size.height) {
                            drawText(cut.layout, digit.brush ?: ink, origin, alpha = 1f, drawStyle = Fill)
                        }
                    }
                    drawText(cut.layout, line, origin, alpha = 1f, drawStyle = edge)
                }
            },
    )
}

/**
 * [count] laid out at [size] and cut in [posters]: at once when they are scaled to its line already; else they are, off
 * the main thread, the last cut drawn meanwhile. Null until there is a size.
 */
@Composable
private fun rememberCutFigure(count: String, posters: CutPosters, size: TextUnit): CutFigure? {
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val style = Hl.type.figureSignature
    val layout = remember(count, size, style, measurer) {
        if (size.isSpecified) measurer.measure(count, style.figureAt(size), softWrap = false, maxLines = 1) else null
    }
    val ready = remember(layout, posters) { layout?.let { cutFigure(it, posters.posters, scale = false) } }
    var last by remember { mutableStateOf<CutFigure?>(null) }
    LaunchedEffect(layout, posters) {
        last = ready ?: layout?.let { withContext(Dispatchers.Default) { cutFigure(it, posters.posters, scale = true) } }
    }
    return if (layout == null) null else ready ?: last
}

/** A count laid out, and its digits cut. */
private class CutFigure(val layout: TextLayoutResult, val digits: List<Cut>)

/** One digit of a cut count: from [left] to [right] across its line, and its poster's brush (none: ink). */
private class Cut(val left: Float, val right: Float, val brush: Brush?)

/**
 * [layout]'s digits, each with the next of [posters] as its brush, in the layout's own coordinates: the poster at the
 * size that makes it 2.1 times the line's height, centred across the digit and from a tenth down, so its upper half
 * shows. Each digit reaches half way to its neighbours, so no stroke of it is lost. The posters are scaled to that
 * size once when [scale]s (off the main thread); without, null until they are.
 */
private fun cutFigure(layout: TextLayoutResult, posters: List<CutPoster?>, scale: Boolean): CutFigure? {
    val text = layout.layoutInput.text.text
    val boxes = text.indices.filter { text[it].isDigit() }.map { layout.getBoundingBox(it) }
    val line = layout.size.height.toFloat()
    val height = (line * POSTER_OVER_LINE).roundToInt().coerceAtLeast(1)
    val digits = boxes.mapIndexed { k, box ->
        val left = if (k == 0) -BIG else (boxes[k - 1].right + box.left) / 2
        val right = if (k == boxes.lastIndex) BIG else (box.right + boxes[k + 1].left) / 2
        val brush = posters.getOrNull(k)?.let { poster ->
            val drawn = if (scale) poster.scaledTo(height) else poster.scaledOrNull(height) ?: return null
            posterBrush(drawn, Rect(box.left, 0f, box.right, line))
        }
        Cut(left, right, brush)
    }
    return CutFigure(layout, digits)
}

/** A poster at its drawn size ([CutPoster.scaledTo]) on its [digit]: the text paint, which does not filter, draws it point for point. */
private fun posterBrush(drawn: Bitmap, digit: Rect): Brush {
    val shader = BitmapShader(drawn, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        val x = digit.left + (digit.width - drawn.width) * POSTER_ACROSS
        val y = digit.top + (digit.height - drawn.height) * POSTER_DOWN
        setLocalMatrix(Matrix().apply { setTranslate(x.roundToInt().toFloat(), y.roundToInt().toFloat()) })
    }
    return ShaderBrush(shader)
}

/** A door's tint enlarged once to the size it is drawn at, with bilinear filtering: drawn point for point, it never shows its points. */
private class ScaledTint {
    private var made: Triple<Bitmap, Int, Int>? = null
    private var scaled: Bitmap? = null

    fun at(tint: Bitmap, size: Size): Bitmap {
        val w = size.width.roundToInt().coerceAtLeast(1)
        val h = size.height.roundToInt().coerceAtLeast(1)
        val key = Triple(tint, w, h)
        scaled?.takeIf { made == key }?.let { return it }
        return Bitmap.createScaledBitmap(tint, w, h, true).also {
            made = key
            scaled = it
        }
    }
}

/** The paint of a [tint] drawn at its size: lowered and coloured as a door's dark. */
private fun tintPaint(tint: Bitmap): Paint {
    val shader = BitmapShader(tint, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val light = HlScrim.DOOR_TINT_LIGHT
    val matrix = android.graphics.ColorMatrix().apply { setSaturation(HlScrim.DOOR_TINT_SATURATION) }
    matrix.postConcat(android.graphics.ColorMatrix().apply { setScale(light, light, light, 1f) })
    return Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        this.shader = shader
        colorFilter = ColorMatrixColorFilter(matrix)
    }
}

private const val POSTER_OVER_LINE = 2.1f
private const val POSTER_ACROSS = 0.5f
private const val POSTER_DOWN = 0.1f
private const val BIG = 1e6f
private const val OPAQUE = 255

/** How tall a figure stands in its line, as a share of its size: Archivo's lining figures. */
private const val FIGURE_HEIGHT = 0.7f

/**
 * The one size every library's door gives its count (DESIGN.md, Layout): the largest that leaves [airX] across and
 * [airY] down for the longest. [widths] are the counts' widths at [reference] px; [boxes], their doors' pictures (px).
 */
internal fun figureSize(widths: List<Float>, boxes: List<Size>, reference: Float, airX: Float, airY: Float): Float =
    widths.zip(boxes).minOfOrNull { (w, box) ->
        min(if (w > 0f) (box.width - 2 * airX) / w * reference else Float.MAX_VALUE, (box.height - 2 * airY) / FIGURE_HEIGHT)
    }?.coerceAtLeast(0f) ?: 0f

/**
 * The size the library doors' [counts] share, in a row of [spans] (parts of four) [rowHeight] tall: measured once at
 * the signature's size, then scaled to their pictures ([figureSize]); unspecified until there is a count.
 */
@Composable
fun rememberFigureSize(counts: List<String>, spans: List<Int>, rowHeight: Dp): TextUnit {
    val measurer = rememberTextMeasurer(cacheSize = FIGURE_MEASURES)
    val style = Hl.type.figureSignature
    val density = LocalDensity.current
    val band = textRoom(HomeGridDefaults.band)
    return remember(counts, spans, rowHeight, band, style, density) {
        if (counts.isEmpty() || counts.size != spans.size) return@remember TextUnit.Unspecified
        with(density) {
            val reference = style.fontSize.toPx()
            val widths = counts.map { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width.toFloat() }
            val doors = placeDoors(listOf(spans), listOf((rowHeight - band).toPx()), HlSpace.s.toPx(), DEFAULT_WIDTH.dp.toPx()).first()
            val px = figureSize(
                widths,
                doors.map { Size(it.width, it.height) },
                reference,
                HomeGridDefaults.figureAirX.toPx(),
                HomeGridDefaults.figureAirY.toPx(),
            )
            px.toSp()
        }
    }
}

/**
 * The grid's rows' heights, top to bottom, together as tall as the case: what is watched, the libraries, the tools;
 * a row left out gives its room to the one under it.
 */
fun gridHeights(watch: Boolean, libraries: Boolean): List<Dp> {
    val gap = HlSpace.s
    val (w, l, t) = Triple(HomeGridDefaults.watchRow, HomeGridDefaults.libraryRow, HomeGridDefaults.toolRow)
    return when {
        watch && libraries -> listOf(w, l, t)
        libraries -> listOf(w + gap + l, t)
        watch -> listOf(w, l + gap + t)
        else -> listOf(caseHeight())
    }
}

private const val FIGURE_MEASURES = 8
