package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.LocalAmbient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HomeGridDefaults
import app.houselights.ui.design.focusKey
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Home's grid (DESIGN.md, Layout): its head, where its doors lie and how focus moves between them.

/**
 * Home's head over the grid: the lockup at the safe area's start, a passing [status] by its [statusState]'s light (the
 * quiet dot of Back once, the white one of an update installed, the attention mark of one that was not) then [end] (a
 * tile where home shows no grid) at its end, then the gap to the grid.
 */
@Composable
fun HomeHead(
    status: String?,
    modifier: Modifier = Modifier,
    statusState: StatusState = StatusState.Pending,
    end: (@Composable () -> Unit)? = null,
) {
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
        if (status != null) Box(Modifier.wrapContentHeight(unbounded = true)) { PassingStatus(status, statusState) }
        if (end != null) {
            Spacer(Modifier.width(HlSpace.s))
            Box(Modifier.wrapContentHeight(unbounded = true)) { HeaderControls(end) }
        }
    }
}

/**
 * A passing line (Back once on home, an update), by its [state]'s light, on a tile label's plate since a picture may be
 * white there; read out as it comes.
 */
@Composable
internal fun PassingStatus(text: String, state: StatusState = StatusState.Pending) {
    val c = Hl.colors
    val light = LocalAmbient.current
    HlStatus(
        state,
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
internal val DEFAULT_WIDTH = HlLayout.CANVAS_WIDTH - HlSpace.safeH.value * 2 - HomeGridDefaults.case.value - HlSpace.s.value

/** How wide a door of [span] parts of four stands in a row of the grid on the TV canvas. */
internal fun doorWidth(span: Int): Dp {
    val gap = HlSpace.s.value
    val unit = (DEFAULT_WIDTH - gap * (PARTS - 1)) / PARTS
    return (unit * span + gap * (span - 1)).dp
}

private const val PARTS = 4

/**
 * The grid's rows' heights, top to bottom, together as tall as the case: what is watched, the libraries, the tools;
 * a row left out gives its room to the one under it.
 */
internal fun gridHeights(watch: Boolean, libraries: Boolean): List<Dp> {
    val gap = HlSpace.s
    val (w, l, t) = Triple(HomeGridDefaults.watchRow, HomeGridDefaults.libraryRow, HomeGridDefaults.toolRow)
    return when {
        watch && libraries -> listOf(w, l, t)
        libraries -> listOf(w + gap + l, t)
        watch -> listOf(w, l + gap + t)
        else -> listOf(caseHeight())
    }
}
