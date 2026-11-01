package app.houselights.ui.design.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.ui.design.GroupFocus
import app.houselights.ui.design.HeroDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HlStroke
import app.houselights.ui.design.LocalGroupFocus
import app.houselights.ui.design.Scrub
import app.houselights.ui.design.VeilStop
import app.houselights.ui.design.VoletDefaults
import app.houselights.ui.design.alphaAt
import app.houselights.ui.design.colorStops
import app.houselights.ui.design.greyOf
import app.houselights.ui.design.keepChildFocus
import app.houselights.ui.design.textRoom
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// A saga's page as a polyptych (« Les volets »): a tall panel a film beside the saga's own, the one with focus open.

/** The sign at a closed panel's foot, over its year: one at most, as a card's ([StatusMark]). */
enum class VoletMark { None, Watched, New, Held, Absent, Upcoming }

/**
 * What one of a saga's panels (« volet ») shows: a film's [number] in release order (null: the saga's own panel, its
 * box), its [poster] (closed) and [backdrop] (open), [grey] for a film the library lacks or not out yet, its [mark] and
 * [year] at its foot (the saga's spine: where focus is among its films), the share of it seen ([progress]); read as
 * [readAs] then [state], then what its words [say] open. The saga's panel sets its [spine] name upright when closed. OK
 * on it [acts] or does nothing, [hint] saying what it does while it has focus. Open, it has actions under its words when
 * [rowed]. Equal when it shows the same, so a step along the polyptych recomposes the panels it concerns alone.
 */
@Immutable
data class VoletPart(
    val key: String,
    val number: Int?,
    val poster: ImageRef?,
    val backdrop: ImageRef?,
    val readAs: String,
    val grey: Boolean = false,
    val mark: VoletMark = VoletMark.None,
    val year: String? = null,
    val progress: Float = 0f,
    val state: String? = null,
    val spine: String? = null,
    val say: String? = null,
    val acts: Boolean = true,
    val rowed: Boolean = false,
    val hint: String? = null,
)

/**
 * A saga's panels across a width and a height: [count] of them, the closed ones [closed] wide whatever the saga's
 * length, [gap] apart, the open one taking what they leave, [open] wide, never narrower than [VoletDefaults.open]; past
 * that, they [scrolls]: the films alone, the saga's spine pinned at the start once a film is open. Pure: the board's
 * numbers, at every text size.
 */
@Immutable
data class VoletPlan(
    val count: Int,
    val open: Dp,
    val closed: Dp,
    val gap: Dp,
    val height: Dp,
    val scrolls: Boolean,
) {
    /**
     * Where panel [i] starts (dp), panel [at] open and the others closed, the films scrolled [by]: the saga's panel at
     * the start, pinned, its films after it.
     */
    fun left(i: Int, at: Int, by: Float = 0f): Float {
        if (i == 0) return 0f
        val step = (closed + gap).value
        val first = (if (at == 0) open else closed).value + gap.value
        val wider = if (at in 1 until i) (open - closed).value else 0f
        return first + step * (i - 1) + wider - if (at > 0) by else 0f
    }

    /**
     * How far a long saga's films scroll from [from] as panel [at] opens, the polyptych [room] wide (dp): none with the
     * saga's own panel open; else the open film kept [KEPT_CLOSED] closed panels from either end of the films' room (the
     * polyptych less the pinned spine) as far as it can, by whole panels, never further than shows the last film whole.
     */
    fun scrollTo(at: Int, from: Float, room: Float): Float {
        if (at <= 0) return 0f
        val step = (closed + gap).value
        val films = room - step
        // The last film may end short of the polyptych's end: a step is a whole panel.
        val end = ceil((open.value + step * (count - 2) - films).coerceAtLeast(0f) / step) * step
        return keptInView(from, step * (at - 1), open.value, step * KEPT_CLOSED, films).coerceIn(0f, end)
    }

    companion object {
        /** The plan of [count] panels in [width] × [height]. */
        fun of(count: Int, width: Dp, height: Dp): VoletPlan {
            val gap = VoletDefaults.gap
            val closed = VoletDefaults.closed
            val others = (count - 1).coerceAtLeast(0)
            val open = maxOf(VoletDefaults.open, width - (closed + gap) * others)
            val scrolls = (open + (closed + gap) * others - width).value > SLACK
            return VoletPlan(count, open, closed, gap, height, scrolls)
        }

        /** A sum of widths within this of the width fits: a dp's rounding is no scroll. */
        private const val SLACK = 0.5f
    }
}

/**
 * Which panel is open, how open each other was as the move to it began, and how far the move has gone ([t]): read while
 * placing, never recomposing. A move cut short goes on from where every panel stood, as a row's frame does.
 */
@Stable
private class VoletMotion(initial: Int) {
    var open by mutableIntStateOf(initial)
    val t = Animatable(1f)

    /** How open the panels still opening or closing were as the move began, by index: none but those. */
    private var starts by mutableStateOf<Map<Int, Float>>(emptyMap())

    /** How far a long saga's polyptych was scrolled as the move began, and how far it is (px). */
    var scrollFrom = 0f
    var scroll = 0f

    /** How open panel [i] is: 1 open, 0 closed, the one reached opening from where it stood, the others closing. */
    fun openness(i: Int): Float {
        val start = starts[i] ?: 0f
        return if (i == open) start + (1f - start) * t.value else start * (1f - t.value)
    }

    /** A move to panel [to]: each panel goes on from how open it is, the scroll from where it is. */
    fun moveTo(to: Int) {
        starts = (starts.keys + open).associateWith(::openness).filterValues { it > 0f }
        scrollFrom = scroll
        open = to
    }
}

/**
 * A saga's polyptych: its [parts] side by side, [open] the one open (the last one focused, kept as focus leaves them),
 * laid out open from the first frame; its picture and words shown once [shown] settles on it ([words]), the others
 * closed, wordless, receding while a panel has focus. A move opens the panel reached as the one left closes over
 * [HlMotion.FOCUS_IN], the total kept; a held arrow, or [VoletDefaults.opensAtOnce], jumps. Focus comes back to the
 * panel it left; Up from a panel goes nowhere, Down into its actions ([row], for a part [VoletPart.rowed]), Up from them
 * back to it, Left from their first and Right from their last on to the panel beside ([VoletSides]); while the open
 * panel itself has focus, what OK does on it ([VoletPart.hint]) shows in its corner, on the
 * line of its neighbours' years. [onFocus] says which panel has focus, null once focus leaves the polyptych; OK does
 * [onOpen]. [keyed] keys a panel's focus. A long saga scrolls its films, the saga's spine pinned at the start; the
 * panels far from the view keep their dark, their posters asked for as they come near ([PosterWindow]).
 */
@Composable
fun Volets(
    parts: List<VoletPart>,
    open: Int,
    shown: Int?,
    onFocus: (Int?) -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    keyed: (Int) -> Modifier = { Modifier },
    row: @Composable (Int, VoletSides) -> Unit = { _, _ -> },
    words: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val plan = VoletPlan.of(parts.size, maxWidth, maxHeight)
        val type = rememberVoletType(plan, parts.firstNotNullOfOrNull { it.spine })
        val group = remember { GroupFocus() }
        val requesters = remember(parts.size) { List(parts.size) { FocusRequester() } }
        val edges = remember(parts.size) { VoletEdges(parts.size) }
        var within by remember { mutableStateOf(false) }
        // A panel itself, not its actions: the others recede.
        var focusedPanel by remember { mutableStateOf<Int?>(null) }
        val motion = rememberVoletMotion(open)
        val policy = remember(plan, motion, edges) { voletsPolicy(plan, motion, edges) }
        val posters = rememberPosters(plan, maxWidth, open)
        CompositionLocalProvider(LocalGroupFocused provides (focusedPanel != null), LocalGroupFocus provides group) {
            Layout(
                content = {
                    parts.forEachIndexed { i, part ->
                        key(part.key) {
                            Volet(
                                // Past the posters asked for, a long saga's panel keeps its dark.
                                if (i in posters) part else part.copy(poster = null),
                                plan,
                                type,
                                opens = i == open,
                                opened = { motion.openness(i) },
                                settled = i == open && i == shown,
                                self = requesters[i],
                                edge = { edges.clip[i].floatValue },
                                whole = { edges.whole[i].value },
                                onClick = { onOpen(i) },
                                onFocus = { focused ->
                                    if (focused) {
                                        focusedPanel = i
                                        onFocus(i)
                                    } else if (focusedPanel == i) {
                                        focusedPanel = null
                                    }
                                },
                                surfaceModifier = keyed(i).focusRequester(requesters[i]),
                                row = { row(i, sidesOf(requesters, i)) },
                            ) { words(i) }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged {
                        if (within && !it.hasFocus) onFocus(null)
                        within = it.hasFocus
                    }
                    .keepChildFocus(group),
                measurePolicy = policy,
            )
        }
    }
}

/**
 * The panels beside an open one, which its actions' ends lead to, Left from the first ([before]) and Right from the
 * last ([after]): focus lands on the panel itself, opening it; [FocusRequester.Cancel] past the polyptych's ends.
 */
@Immutable
class VoletSides(val before: FocusRequester, val after: FocusRequester)

/** The panels beside panel [i], by their [requesters]. */
private fun sidesOf(requesters: List<FocusRequester>, i: Int) =
    VoletSides(requesters.getOrNull(i - 1) ?: FocusRequester.Cancel, requesters.getOrNull(i + 1) ?: FocusRequester.Cancel)

/**
 * The type a polyptych sets: its numbers' one size (`display`), the [band] at the top they stand in (px), the [spine]'s
 * name's style, and how high every closed panel's year rises from the foot ([yearRise], px), so their bands make one line.
 */
@Immutable
private class VoletType(val numbers: TextStyle, val band: Float, val spine: TextStyle, val yearRise: Float)

/** [plan]'s type: its numbers in `display`, its [spine]'s name in them while it fits on one line, else `rowTitle`. */
@Composable
private fun rememberVoletType(plan: VoletPlan, spine: String?): VoletType {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val meta = Hl.type.meta
    val rowTitle = Hl.type.rowTitle
    val numbers = Hl.type.display
    return remember(plan, spine, numbers, meta, rowTitle, density) {
        with(density) {
            val tall = measurer.oneLine(YEAR_SAMPLE, meta, density).height
            val yearRise = HlSpace.m.toPx() + tall
            // Its foot over its band, as the closed films' marks stand over their years.
            val room = plan.height.toPx() - HlSpace.l.toPx() - yearRise - HlSpace.xs.toPx()
            val fits = spine == null || measurer.oneLine(spine, numbers, density).width <= room
            VoletType(
                numbers = numbers,
                band = VoletDefaults.numberTop.toPx() + numbers.lineHeight.toPx(),
                spine = if (fits) numbers else rowTitle,
                yearRise = yearRise,
            )
        }
    }
}

/** [text] as set in [style] on one line, as wide as it runs. */
private fun TextMeasurer.oneLine(text: String, style: TextStyle, density: Density): IntSize =
    measure(text, style, maxLines = 1, softWrap = false, density = density).size

/**
 * The polyptych's motion: [open] laid out open from the start, then the panel reached opening as the one left closes,
 * the total kept (the pictures are never scaled, only cut); before the first frame, held, or set to jump, at once.
 */
@Composable
private fun rememberVoletMotion(open: Int): VoletMotion {
    val motion = remember { VoletMotion(open) }
    // Before its first frame the polyptych is laid out as it comes (back from a film's page): nothing moves.
    var laid by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        laid = true
    }
    LaunchedEffect(open) {
        if (open == motion.open) return@LaunchedEffect
        motion.moveTo(open)
        // Held, or set to jump (« greffe 3 »): both widths at once, the picture and the words once settled.
        if (!laid || Scrub.active || VoletDefaults.opensAtOnce) {
            motion.t.snapTo(1f)
        } else {
            motion.t.snapTo(0f)
            motion.t.animateTo(1f, tween(HlMotion.FOCUS_IN, easing = HlMotion.focusPull))
        }
    }
    return motion
}

/**
 * Where each panel stands against the polyptych's ends, written as it is placed and read while drawing: how far into it
 * from its start nothing draws ([clip], px: a film gone under the pinned spine), and whether it stands [whole] inside the
 * polyptych, its number and year shown; a panel past the end shows its picture alone, no text cut by the screen's edge.
 */
@Stable
private class VoletEdges(count: Int) {
    val clip = Array(count) { mutableFloatStateOf(0f) }
    val whole = Array(count) { mutableStateOf(true) }
}

/**
 * The panels as wide as [motion] has them open, read while placing: the opening relays them out, it never recomposes
 * them. The saga's panel stands at the start; a long saga's films scroll panel by panel after it ([VoletPlan.scrollTo]), the
 * scroll moving with the widths, each film's [edges] told where it stands.
 */
private fun voletsPolicy(plan: VoletPlan, motion: VoletMotion, edges: VoletEdges) = MeasurePolicy { measurables, constraints ->
    val height = constraints.maxHeight
    val gap = plan.gap.toPx()
    val closed = plan.closed.toPx()
    val wide = plan.open.toPx()
    val count = measurables.size
    val widths = FloatArray(count) { i -> closed + (wide - closed) * motion.openness(i) }
    val room = constraints.maxWidth.toFloat()
    motion.scroll = if (plan.scrolls) {
        // Where the move ends, from where it began; the plan reckons in dp.
        val from = motion.scrollFrom
        val to = plan.scrollTo(motion.open, from / density, room / density) * density
        from + (to - from) * motion.t.value
    } else {
        0f
    }
    val placeables = measurables.mapIndexed { i, child ->
        child.measure(Constraints.fixed(widths[i].roundToInt().coerceAtLeast(0), height))
    }
    // The films' start: past the saga's panel, under which a film scrolled away goes.
    val films = (widths.firstOrNull() ?: 0f) + gap
    val xs = FloatArray(count)
    var x = films - motion.scroll
    for (i in 1 until count) {
        xs[i] = x
        x += widths[i] + gap
    }
    for (i in 1 until count) {
        edges.clip[i].floatValue = (films - xs[i]).coerceAtLeast(0f)
        edges.whole[i].value = standsWhole(xs[i], widths[i], films, room)
    }
    layout(constraints.maxWidth, height) {
        placeables.forEachIndexed { i, placeable -> placeable.place(xs[i].roundToInt(), 0) }
    }
}

/**
 * Whether a film's panel [x] from the polyptych's start and [width] wide stands whole between the films' [start] (past
 * the pinned spine) and the polyptych's end, [room] from its start, within half a pixel: else it shows its picture alone,
 * no number or year cut by the screen's edge. In px.
 */
internal fun standsWhole(x: Float, width: Float, start: Float, room: Float): Boolean =
    x >= start - EDGE_SLACK && x + width <= room + EDGE_SLACK

/**
 * Where a long saga's polyptych scrolls to from [from] as a panel opens, [left] from its start and [wide]: no further
 * than keeps [margin] between that panel and either end of the [room]. In px or dp alike.
 */
internal fun keptInView(from: Float, left: Float, wide: Float, margin: Float, room: Float): Float =
    from.coerceIn(minOf(left + wide - (room - margin), left - margin), left - margin)

/**
 * The panels a long saga asks its posters for (#55): those in view once its films have scrolled to the panel open, as
 * [voletsPolicy] scrolls them, those it passes by on its way from the view it leaves, and [POSTERS_AHEAD] more on
 * either side, [POSTERS_KEPT] on the side a held arrow runs to, so that a poster is there before its panel comes into
 * view; a poster asked stays asked while its panel is within [POSTERS_KEPT] of them. A saga in view whole asks them
 * all. The saga's own spine, pinned, has no poster. In dp, the polyptych [room] wide.
 */
internal class PosterWindow(private val plan: VoletPlan, private val room: Float) {
    private val all = (0 until plan.count).toSet()
    private var scroll = 0f
    private var open = -1
    private var view: IntRange? = null
    private var asked = IntRange.EMPTY

    /**
     * The panels whose posters are asked for, panel [to] open, a held arrow running focus while [runs]; asked again for
     * the same panel, the same.
     */
    fun moveTo(to: Int, runs: Boolean = false): Set<Int> {
        if (!plan.scrolls) return all
        if (to != open) go(to, runs)
        return asked.toSet()
    }

    /** The panels in view were panel [at] to open now, the films scrolling from where they are; they stay there. */
    fun viewAt(at: Int): IntRange {
        if (!plan.scrolls) return 0 until plan.count
        val by = plan.scrollTo(at, scroll, room)
        val shown = (0 until plan.count).filter { inView(it, at, by) }
        return (shown.firstOrNull() ?: at)..(shown.lastOrNull() ?: at)
    }

    /** Panel [to] opened, its films scrolled to it; a held arrow that [runs] asks further on the side it goes. */
    private fun go(to: Int, runs: Boolean) {
        val heading = if (runs && open >= 0) to.compareTo(open) else 0
        val now = viewAt(to)
        scroll = plan.scrollTo(to, scroll, room)
        open = to
        // The view it leaves, the one it reaches, and what it passes by between them, whatever the jump.
        val passed = view?.let { minOf(it.first, now.first)..maxOf(it.last, now.last) } ?: now
        view = now
        val near = near(
            passed,
            before = if (heading < 0) POSTERS_KEPT else POSTERS_AHEAD,
            after = if (heading > 0) POSTERS_KEPT else POSTERS_AHEAD,
        )
        val kept = maxOf(asked.first, passed.first - POSTERS_KEPT)..minOf(asked.last, passed.last + POSTERS_KEPT)
        asked = if (asked.isEmpty() || kept.isEmpty()) near else minOf(near.first, kept.first)..maxOf(near.last, kept.last)
    }

    /** [panels] and [before] more before them, [after] more after them, within the polyptych. */
    private fun near(panels: IntRange, before: Int, after: Int): IntRange =
        (panels.first - before).coerceAtLeast(0)..(panels.last + after).coerceAtMost(plan.count - 1)

    /**
     * Whether panel [i] shows, panel [at] open, the films scrolled [by]: a film past the pinned spine and inside the
     * room; the saga's panel in the view's run while its first film is.
     */
    private fun inView(i: Int, at: Int, by: Float): Boolean {
        if (i == 0) return plan.count < 2 || inView(1, at, by)
        val x = plan.left(i, at, by)
        val width = if (i == at) plan.open.value else plan.closed.value
        return x + width > plan.left(1, at) && x < room
    }
}

/**
 * The panels of [plan] whose posters are asked for, panel [open] open, in a polyptych [room] wide ([PosterWindow]);
 * further ahead while a held arrow runs focus.
 */
@Composable
private fun rememberPosters(plan: VoletPlan, room: Dp, open: Int): Set<Int> {
    val window = remember(plan, room) { PosterWindow(plan, room.value) }
    val runs = Scrub.active
    return remember(window, open, runs) { window.moveTo(open, runs) }
}

/** A panel's pictures at the panel's height: its poster at 2:3 and its backdrop at 16:9, in dp and as asked for (px). */
@Immutable
private class VoletFrame(val poster: DpSize, val backdrop: DpSize, val posterPx: IntSize, val backdropPx: IntSize)

@Composable
private fun rememberVoletFrame(height: Dp): VoletFrame {
    val density = LocalDensity.current
    return remember(height, density) {
        val poster = DpSize(height * HlSize.POSTER_RATIO, height)
        val backdrop = DpSize(height * HlSize.PREVIEW_RATIO, height)
        with(density) {
            VoletFrame(
                poster,
                backdrop,
                IntSize(poster.width.roundToPx(), poster.height.roundToPx()),
                IntSize(backdrop.width.roundToPx(), backdrop.height.roundToPx()),
            )
        }
    }
}

/**
 * An open panel's wait for its backdrop: [pictured] once it is there to draw, [whole] once it is and has no see-through
 * part, [waited] once [HlMotion.HOLD] is over.
 */
@Stable
private class VoletWait {
    var pictured by mutableStateOf(false)
    var whole by mutableStateOf(false)
    var waited by mutableStateOf(false)

    /** Whether the words show: [settled], and with their picture, the wait over, or [pictureless]. */
    fun shows(settled: Boolean, pictureless: Boolean): Boolean = settled && (pictureless || pictured || waited)
}

/** The wait for [backdrop], which [settled] starts: the words come with their picture, [HlMotion.HOLD] at most later. */
@Composable
private fun rememberVoletWait(backdrop: String?, settled: Boolean): VoletWait {
    val wait = remember(backdrop) { VoletWait() }
    LaunchedEffect(settled, wait) {
        wait.waited = false
        if (settled) {
            delay(HlMotion.HOLD)
            wait.waited = true
        }
    }
    return wait
}

/** A panel's own dark: its backdrop's once it [leads], else its poster's; none of its own for a [grey] film. */
private fun darkOf(grey: Boolean, leads: Boolean, poster: PictureRead?, backdrop: PictureRead?): Color? = when {
    grey -> null
    leads -> backdrop?.deep ?: poster?.deep
    else -> poster?.deep
}

/**
 * A backdrop coming [up] over its poster, linear over TINT, or in one [cut]; leaving, it falls back over FOCUS_OUT, or
 * goes at once when it jumps.
 */
@Composable
private fun rememberRise(up: Boolean, cut: Boolean): State<Float> = animateFloatAsState(
    if (up) 1f else 0f,
    when {
        up && cut -> snap()
        up -> tween(HlMotion.TINT, easing = LinearEasing)
        VoletDefaults.opensAtOnce || Scrub.active -> snap()
        else -> tween(HlMotion.FOCUS_OUT, easing = HlMotion.fallBack)
    },
    label = "volet",
)

/**
 * What the top melt keeps over the numbers' band: the floor [ink] needs over what shows there of each picture read, the
 * panel [opens] or not, a [grey] one's picture darkened by its filter, the melt in [dark].
 */
@Composable
private fun rememberNumberFloor(
    plan: VoletPlan,
    frame: VoletFrame,
    type: VoletType,
    opens: Boolean,
    ink: Color,
    dark: Color,
    grey: Boolean,
    poster: PictureRead?,
    backdrop: PictureRead?,
): Float {
    val density = LocalDensity.current
    return remember(plan, frame, type, opens, ink, dark, grey, poster, backdrop, density) {
        val across = if (opens) plan.open else plan.closed
        val down = type.band / with(density) { plan.height.toPx() }
        val light = if (grey) HlScrim.ABSENT_BRIGHTNESS else 1f
        val ceiling = HlScrim.ceiling(ink)
        listOf(poster to frame.poster, backdrop to frame.backdrop).mapNotNull { (read, size) ->
            read?.sample?.topFloor(across / size.width, down, ceiling, greyOf(dark), light)
        }.maxOrNull() ?: 0f
    }
}

/**
 * One panel: a case's recipe (a picture with words on its own dark), not a card. Closed, its poster at its natural
 * size cut by its width, its number at the top on its axis, its [VoletPart.mark] and year at its foot while it stands
 * [whole] in the polyptych; open and [settled], its backdrop rising over the poster once drawn, with its [words] at
 * its foot in one cut ([HlMotion.HOLD] at most after it settled), its number at its start, as far as it is [opened];
 * the backdrop leaving it fades out, or goes at once when it jumps. Open, its actions ([row], [VoletPart.rowed]) stand
 * under its words, there from the moment it opens so Down finds them, shown with the words or as soon as they take
 * focus; while the panel itself has focus, what OK does ([VoletPart.hint]) in its bottom corner, on its neighbours'
 * years. Nothing draws [edge] px into it from its start (a film under the pinned spine). The saga's closed panel is
 * its spine, its backdrop receding and its name upright. Framed with focus, glowing in its film's light; a greyed film
 * framed without a glow, as [AbsentCard] is, even while the room still holds the light of the film before it.
 */
@Composable
private fun Volet(
    part: VoletPart,
    plan: VoletPlan,
    type: VoletType,
    opens: Boolean,
    opened: () -> Float,
    settled: Boolean,
    self: FocusRequester,
    edge: () -> Float,
    whole: () -> Boolean,
    onClick: () -> Unit,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    row: @Composable () -> Unit = {},
    words: @Composable () -> Unit,
) {
    val pictures = rememberVoletPictures(part, plan, settled)
    val actions = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // Focus in its actions shows its words at once, the picture or not.
    var acting by remember { mutableStateOf(false) }
    val rowed = opens && part.rowed
    val wide = pictures.wait.shows(settled, pictureless = pictures.backdrop == null) || rowed && acting
    var wordsTop by remember { mutableFloatStateOf(Float.NaN) }
    // Raised over its neighbours as its surface would be, its words and actions over the surface.
    val raise = remember { Raise() }
    Box(modifier.fillMaxSize().raisedBy { raise.level() }.clipStart(edge)) {
        CompositionLocalProvider(LocalRaise provides raise) {
            VoletSurface(
                part,
                plan,
                type,
                pictures,
                VoletShown(opens, settled, wide, opened, whole) { wordsTop },
                onClick,
                surfaceModifier
                    .focusProperties {
                        up = FocusRequester.Cancel
                        down = if (rowed) actions else FocusRequester.Cancel
                    }
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocus(it.isFocused)
                    },
            )
        }
        if (wide || rowed) {
            VoletOverlay(
                rowed = rowed,
                wide = wide,
                foot = with(LocalDensity.current) { type.yearRise.toDp() } + HlSpace.xs,
                focus = VoletRowFocus(actions, self) { acting = it },
                onTop = { wordsTop = it },
                row = row,
                words = words,
            )
        }
        // What OK does, in its corner on the line of its neighbours' years, while it has focus itself.
        val hint = part.hint
        if (wide && hint != null) {
            OkHint(
                hint,
                shown = focused,
                Modifier.align(Alignment.BottomEnd).padding(end = HlInset.card, bottom = HlSpace.m),
            )
        }
    }
}

/** A panel's pictures at its height ([frame]), its poster's and its backdrop's addresses, and its wait for the backdrop. */
@Immutable
private class VoletPictureSet(val frame: VoletFrame, val poster: String?, val backdrop: String?, val wait: VoletWait)

/** [part]'s pictures in [plan]: the saga's spine has no poster, which prints its name; the wait starts once [settled]. */
@Composable
private fun rememberVoletPictures(part: VoletPart, plan: VoletPlan, settled: Boolean): VoletPictureSet {
    val frame = rememberVoletFrame(plan.height)
    val poster = part.poster?.url(frame.posterPx.width)?.takeUnless { part.number == null }
    val backdrop = part.backdrop?.url(frame.backdropPx.width)
    return VoletPictureSet(frame, poster, backdrop, rememberVoletWait(backdrop, settled))
}

/**
 * How a panel shows: it [opens], is [settled] on, shows its words ([wide]), as far [opened] as its move has gone,
 * [whole] in the polyptych, its words starting [wordsTop] px from its top (read while drawing).
 */
@Immutable
private class VoletShown(
    val opens: Boolean,
    val settled: Boolean,
    val wide: Boolean,
    val opened: () -> Float,
    val whole: () -> Boolean,
    val wordsTop: () -> Float,
)

/**
 * A panel's surface: its ground, its melts and its marks, framed and glowing with focus; read as one element, [onClick]
 * on OK. Its own dark from its pictures, its backdrop rising once settled (in one cut back from its page).
 */
@Composable
private fun VoletSurface(
    part: VoletPart,
    plan: VoletPlan,
    type: VoletType,
    pictures: VoletPictureSet,
    shown: VoletShown,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    val saga = part.number == null
    val settled = shown.settled
    // A picture's own dark, read once each from its sample.
    val posterRead by rememberPictureRead(part.poster.takeUnless { saga })
    val backdropRead by rememberPictureRead(part.backdrop.takeIf { settled || saga })
    val dark = darkOf(part.grey, shown.wide || saga, posterRead, backdropRead) ?: c.ambientDeep
    // Settled from its first frame (back from its page), its picture comes in one cut, as the screen does; then it rises.
    var cuts by remember { mutableStateOf(settled) }
    LaunchedEffect(settled) { if (!settled) cuts = false }
    // The backdrop comes up over the poster once settled and drawn; the saga's spine keeps it.
    val rise = rememberRise(saga || settled && pictures.wait.pictured, cut = cuts)
    val ink = if (part.grey) c.contentSecondary else c.content
    val floor = rememberNumberFloor(plan, pictures.frame, type, shown.opens, ink, dark, part.grey, posterRead, backdropRead)
    ArtworkSurface(
        onClick = onClick,
        modifier = modifier.fillMaxSize(),
        shape = HlShapes.wide,
        // Its state, then what it says open: a panel has focus only open.
        stateDescription = listOfNotNull(part.state, part.say).joinToString(", ").ifEmpty { null },
        readAs = part.readAs,
        acts = part.acts,
        light = if (part.grey) null else pictures.backdrop ?: pictures.poster,
        glows = !part.grey,
        // Its ground covers it whole, its dark or a picture over it: no plate under them.
        covered = GroundCovers,
    ) {
        VoletPictures(dark, pictures.poster, pictures.backdrop, part.grey, pictures.frame, shows = saga || settled, rise, pictures.wait)
        if (!saga) TopMelt(dark, floor, type.band)
        if (shown.wide) WordsMelt(dark, shown.wordsTop) else FootMelt(dark, type.yearRise)
        VoletMarks(part, type, ink, closed = !shown.wide, opens = shown.opens, opened = shown.opened, whole = shown.whole)
    }
}

/** The focus of a panel's actions: their [requester], Up back to their panel ([panel]), [onFocus] told whether they hold it. */
@Immutable
private class VoletRowFocus(val requester: FocusRequester, val panel: FocusRequester, val onFocus: (Boolean) -> Unit)

/**
 * An open panel's words at its foot, its title's width, then its actions ([row], when [rowed]) over a tile's label
 * room, down to the foot, so a focused tile's label lies on the band of its neighbours' years; without them, the words
 * over the [foot] band its neighbours' marks and years stand in. Laid out before its words show ([wide]), unseen, so
 * Down finds the actions; [onTop] told where the words begin (px from the panel's top).
 */
@Composable
private fun BoxScope.VoletOverlay(
    rowed: Boolean,
    wide: Boolean,
    foot: Dp,
    focus: VoletRowFocus,
    onTop: (Float) -> Unit,
    row: @Composable () -> Unit,
    words: @Composable () -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(start = HlInset.card, end = HlInset.card)
            // Actions over a tile's label room alone ([HlActionRow]): a focused tile's label lies on the years' band.
            .padding(bottom = if (rowed) HlSpace.none else foot)
            .onPlaced { onTop(it.positionInParent().y) }
            .drawWithContent { if (wide) drawContent() },
    ) {
        // Read as the panel: its words are what it says.
        if (wide) Box(Modifier.width(HeroDefaults.titleWidth).clearAndSetSemantics {}) { words() }
        if (rowed) {
            Spacer(Modifier.height(HlSpace.l))
            Box(
                Modifier
                    .focusRequester(focus.requester)
                    .focusProperties {
                        up = focus.panel
                        down = FocusRequester.Cancel
                    }
                    .onFocusChanged { focus.onFocus(it.hasFocus) },
            ) { row() }
        }
    }
}

/**
 * Nothing drawn [edge] px into this from its start (read while drawing), its focus frame and glow kept on every other
 * side; nothing at all once it is wholly under it, what it draws past its edge included.
 */
private fun Modifier.clipStart(edge: () -> Float): Modifier = drawWithContent {
    val at = edge()
    when {
        at <= 0f -> drawContent()
        at >= size.width -> Unit
        else -> {
            val reach = size.height
            clipRect(
                left = at,
                top = -reach,
                right = size.width + reach,
                bottom = size.height + reach,
            ) { this@drawWithContent.drawContent() }
        }
    }
}

/**
 * A panel's ground: its [dark], its [poster] at its natural size cut by the panel, and its [backdrop] while it [shows]
 * or fades ([up]), loaded while it settles and drawn as it rises, [wait] told once it is there; leaving, it is no
 * longer drawn. A [grey] film's pictures without colour, stepping back further than their siblings. What a picture
 * covers whole is not drawn under it ([voletCover]), read while drawing: the dark under its poster or its backdrop
 * risen, the poster under that backdrop.
 */
@Composable
private fun BoxScope.VoletPictures(
    dark: Color,
    poster: String?,
    backdrop: String?,
    grey: Boolean,
    frame: VoletFrame,
    shows: Boolean,
    up: State<Float>,
    wait: VoletWait,
) {
    val recede = LocalArtworkRecede.current
    val step by remember(recede) { derivedStateOf { HlFocus.recedeStep(recede()) } }
    // Composed while it shows or fades: recomposed only as it starts or ends.
    val drawn by remember(up) { derivedStateOf { up.value > 0f } }
    var posterWhole by remember(poster) { mutableStateOf(false) }
    val cover = { width: Float ->
        voletCover(
            width,
            poster = frame.posterPx.width.takeIf { posterWhole },
            backdrop = frame.backdropPx.width.takeIf { wait.whole && up.value >= 1f },
        )
    }
    Box(Modifier.matchParentSize().drawBehind { if (cover(size.width) == VoletCover.None) drawRect(dark) })
    if (poster != null) {
        // Under its backdrop once whole itself: its loader's fade has run.
        Box(
            Modifier.matchParentSize().drawWithContent { if (!posterWhole || cover(size.width) != VoletCover.Backdrop) drawContent() },
            contentAlignment = Alignment.Center,
        ) {
            ArtworkImage(
                poster,
                null,
                Modifier.requiredSize(frame.poster.width, frame.poster.height),
                size = frame.posterPx,
                filter = if (grey) absentFilters[step] else null,
                onWhole = { posterWhole = true },
            )
        }
    }
    if (backdrop != null && (shows || drawn)) {
        DisposableEffect(backdrop) {
            onDispose {
                wait.pictured = false
                wait.whole = false
            }
        }
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            VoletPicture(
                backdrop,
                frame.backdrop,
                frame.backdropPx,
                grey,
                alpha = { up.value },
                onLoad = { opaque ->
                    wait.pictured = true
                    wait.whole = opaque
                },
            )
        }
    }
}

/** What covers a panel whole, so that nothing under it is drawn: nothing (its dark shows), its poster or its backdrop. */
internal enum class VoletCover { None, Poster, Backdrop }

/**
 * What covers a panel [width] px wide whole: its backdrop, [backdrop] px wide once risen whole with no see-through part
 * (else null), else its poster, [poster] px wide once drawn whole with none, its loader's fade over (else null); each as
 * wide as the panel at least. Opening wider than its poster, a panel shows its dark at its sides again.
 */
internal fun voletCover(width: Float, poster: Int?, backdrop: Int?): VoletCover = when {
    backdrop != null && width <= backdrop -> VoletCover.Backdrop
    poster != null && width <= poster -> VoletCover.Poster
    else -> VoletCover.None
}

/**
 * What a panel shows of itself over its pictures: its number, as far as it is [opened]; [closed], its mark and year at
 * its foot, and the share of a film seen at its very foot; the saga's, unless it [opens], its spine's name upright over
 * its band. Its words only while it stands [whole] in the polyptych.
 */
@Composable
private fun BoxScope.VoletMarks(
    part: VoletPart,
    type: VoletType,
    ink: Color,
    closed: Boolean,
    opens: Boolean,
    opened: () -> Float,
    whole: () -> Boolean,
) {
    val number = part.number
    val shown = Modifier.drawWithContent { if (whole()) drawContent() }
    if (number != null) {
        VoletNumber(number, type.numbers, ink, opened, shown)
        if (closed) VoletFoot(part, shown.align(Alignment.BottomCenter))
    } else if (!opens) {
        part.spine?.let {
            // Its foot where the films' marks stand, over its band.
            val foot = with(LocalDensity.current) { type.yearRise.toDp() } + HlSpace.xs
            VerticalName(it, type.spine, Modifier.align(Alignment.BottomCenter).padding(top = HlSpace.l, bottom = foot))
        }
        if (closed) VoletFoot(part, shown.align(Alignment.BottomCenter))
    }
    // Open, its words say what is left.
    if (closed && part.progress > 0f) ProgressLine(part.progress, Modifier.align(Alignment.BottomCenter), HlStroke.bold)
}

/** A closed panel's foot: its mark, then its year (the spine's: where focus is among the films), [HlSpace.m] from the foot. */
@Composable
private fun VoletFoot(part: VoletPart, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val year = part.year
    Column(modifier.padding(bottom = HlSpace.m), horizontalAlignment = Alignment.CenterHorizontally) {
        FootMark(part.mark)
        if (part.mark != VoletMark.None && year != null) Spacer(Modifier.height(HlSpace.xs))
        if (year != null) {
            Text(
                year,
                style = Hl.type.meta,
                color = if (part.grey) c.contentTertiary else c.contentSecondary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * An open panel's backdrop at its natural size, [size] ([px]), drawn with its [alpha] read while drawing: it fades in
 * and out without a layer, and at rest takes none. Its filter steps back with its siblings, a [grey] one without colour;
 * [onLoad] is told once it is there to draw, and whether it has no see-through part.
 */
@Composable
private fun VoletPicture(
    url: String,
    size: DpSize,
    px: IntSize,
    grey: Boolean,
    alpha: () -> Float,
    onLoad: (opaque: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The panel fades it in itself: no crossfade of the loader's under it.
    val request = remember(url, px, context) { artworkRequest(context, url, px.width, px.height).newBuilder().crossfade(false).build() }
    val loaded by rememberUpdatedState(onLoad)
    val painter = rememberAsyncImagePainter(request, onSuccess = { loaded(it.result.isOpaque()) })
    val recede = LocalArtworkRecede.current
    Spacer(
        modifier.requiredSize(size.width, size.height).drawBehind {
            val shown = alpha()
            if (shown > 0f) {
                val back = recede()
                drawCropped(painter, shown, if (grey) absentFilters[HlFocus.recedeStep(back)] else recedeFilterAt(back))
            }
        },
    )
}

/** [painter] filling the area, cropped about its centre as [ContentScale.Crop] does, at [alpha] through [filter]. */
private fun DrawScope.drawCropped(painter: Painter, alpha: Float, filter: ColorFilter?) {
    val source = painter.intrinsicSize
    if (source.isUnspecified || source.width <= 0f || source.height <= 0f) return
    val scale = ContentScale.Crop.computeScaleFactor(source, size)
    val drawn = Size(source.width * scale.scaleX, source.height * scale.scaleY)
    clipRect {
        translate((size.width - drawn.width) / 2f, (size.height - drawn.height) / 2f) {
            with(painter) { draw(drawn, alpha, filter) }
        }
    }
}

/** The number at the panel's top: on its axis closed, at its start open, as far as it is [opened], read while placing. */
@Composable
private fun VoletNumber(number: Int, style: TextStyle, color: Color, opened: () -> Float, modifier: Modifier = Modifier) {
    Text(
        "$number",
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.clearAndSetSemantics {}.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
            val top = VoletDefaults.numberTop.roundToPx()
            layout(width, placeable.height + top) {
                val centre = (width - placeable.width) / 2f
                val start = HlInset.card.toPx()
                placeable.place((centre + (start - centre) * opened()).roundToInt(), top)
            }
        },
    )
}

/**
 * The top of a film's panel in its picture's [dark], as a hero's top veil ([HlScrim.TOP]), raised to the [floor] its
 * number needs over its [band] (px from the top), so the number reads on any poster, then melting back onto the top
 * veil over [VoletDefaults.melt] under the band: no veil draws a line.
 */
@Composable
private fun TopMelt(dark: Color, floor: Float, band: Float) {
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val screen = HlScrim.SCREEN_HEIGHT.dp.toPx()
            val stops = HlScrim.TOP.raisedOver(band / screen, floor, VoletDefaults.melt.toPx() / screen)
            val brush = Brush.verticalGradient(*stops.colorStops(dark), startY = 0f, endY = screen)
            val veil = DitheredVeil()
            onDrawBehind { drawVeil(veil, brush, stops, 0f, screen) }
        },
    )
}

/**
 * This veil kept at [floor] at least down to [until], as a band of a backdrop's left veil is ([HlScrim.FLOOR_QUANTILE]),
 * then back on its own curve [ease] under it, its stops in between giving way: the floor melts out, never ending on an
 * edge.
 */
internal fun List<VeilStop>.raisedOver(until: Float, floor: Float, ease: Float): List<VeilStop> {
    if (floor <= alphaAt(until) || until <= first().at) return this
    val back = until + ease
    return filter { it.at < until }.map { VeilStop(it.at, maxOf(it.alpha, floor)) } +
        VeilStop(until, floor) +
        VeilStop(back, alphaAt(back)) +
        filter { it.at > back }
}

/**
 * A closed panel's foot in its picture's dark ([HlScrim.VOLET_FOOT]), under its mark and year, then the year's band:
 * [HlScrim.VOLET_YEAR] from [HlSpace.xs] over the year's top, [yearRise] (px) above the foot, and
 * [HlScrim.VOLET_YEAR_FLOOR] from that top down. The spine takes it too, so the band runs along the whole foot.
 */
@Composable
private fun FootMelt(dark: Color, yearRise: Float) {
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val ramp = HlScrim.VOLET_FOOT
            val height = size.height.coerceAtLeast(1f)
            val top = (1f - yearRise / height).coerceIn(ramp.last().at, 1f)
            val band = (top - HlSpace.xs.toPx() / height).coerceIn(ramp.last().at, top)
            val stops = ramp + listOf(
                VeilStop(band, HlScrim.VOLET_YEAR),
                VeilStop(top, HlScrim.VOLET_YEAR_FLOOR),
                VeilStop(1f, HlScrim.VOLET_YEAR_FLOOR),
            )
            val brush = Brush.verticalGradient(*stops.colorStops(dark), startY = 0f, endY = size.height)
            val veil = DitheredVeil()
            onDrawBehind { drawVeil(veil, brush, stops, 0f, size.height) }
        },
    )
}

/**
 * An open panel's words' melt: home's case curve ([HlScrim.CASE_FOOT]) anchored to the words, clear
 * [VoletDefaults.melt] over their top ([top], px, read while drawing) and whole as far under it, down to the foot.
 */
@Composable
private fun WordsMelt(dark: Color, top: () -> Float) {
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val at = top().takeUnless { it.isNaN() } ?: (size.height / 2f)
            val melt = VoletDefaults.melt.toPx()
            val span = melt * 2f / (MELT_END - MELT_START)
            val from = at - melt - MELT_START * span
            val brush = Brush.verticalGradient(*HlScrim.CASE_FOOT.colorStops(dark), startY = from, endY = from + span)
            val veil = DitheredVeil()
            onDrawBehind { drawVeil(veil, brush, HlScrim.CASE_FOOT, from, from + span) }
        },
    )
}

/**
 * [brush], the veil [stops] drawn from [startY] to [endY] (px) by [veil], over the band it is not clear in alone: a
 * transparent rectangle costs the player's GPU a pass all the same (DESIGN.md, Performance). The same pixels, whole ones.
 */
private fun DrawScope.drawVeil(veil: DitheredVeil, brush: Brush, stops: List<VeilStop>, startY: Float, endY: Float) {
    val band = stops.inkedBand(startY, endY)
    val top = floor(band.start).coerceIn(0f, size.height)
    val bottom = ceil(band.endInclusive).coerceIn(top, size.height)
    if (bottom > top) veil.draw(this, brush, Offset(0f, top), Size(size.width, bottom - top))
}

/**
 * Where a veil drawn from [startY] to [endY] (px) is not clear: from its first stop when that one is clear, down to its
 * last when that one is, its gradient holding its end stops' colours past them; unbounded on a side whose stop is not.
 */
internal fun List<VeilStop>.inkedBand(startY: Float, endY: Float): ClosedFloatingPointRange<Float> {
    val span = endY - startY
    val from = if (first().alpha <= 0f) startY + first().at * span else Float.NEGATIVE_INFINITY
    val to = if (last().alpha <= 0f) startY + last().at * span else Float.POSITIVE_INFINITY
    return from..to
}

/** A closed panel's sign: a card's check, its held ring or new dot; a film missing by the ring, one announced by the quiet dot. */
@Composable
private fun FootMark(mark: VoletMark) {
    when (mark) {
        VoletMark.None -> Unit
        VoletMark.Watched -> CheckMark()
        VoletMark.New -> NewDot()
        VoletMark.Held -> HeldMark()
        VoletMark.Absent -> LightMark(StatusState.Off)
        VoletMark.Upcoming -> LightMark(StatusState.Pending)
    }
}

/** A state's light ([StatusLight]) on a mark's dark plate, quiet: tertiary, never ember; it recedes with the picture. */
@Composable
private fun LightMark(state: StatusState) {
    MarkPlate(Modifier.size(HlSize.mark)) { StatusLight(state, ink = Hl.colors.contentTertiary) }
}

/**
 * A name set upright, reading from the foot up as a French spine does, in [style] on one line (a saga's: its numbers'
 * while it fits, else `rowTitle`), ending in an ellipsis past the height it is given: measured on its side, its
 * constraints swapped, and drawn turned, without a layer. The one turned text of the app.
 */
@Composable
internal fun VerticalName(name: String, style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        name,
        style = style,
        color = Hl.colors.content,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clearAndSetSemantics {}
            .drawWithContent { rotate(QUARTER_TURN) { this@drawWithContent.drawContent() } }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight))
                layout(placeable.height, placeable.width) {
                    placeable.place((placeable.height - placeable.width) / 2, (placeable.width - placeable.height) / 2)
                }
            },
    )
}

/** How a film's panel says where it stands, last on its facts' line: [words] after its [light], white when [lit]. */
@Immutable
class VoletState(val words: String, val light: StatusState? = null, val lit: Boolean = false)

/**
 * An open film's words, bottom-anchored in a band ([VoletDefaults.words]) and its title's width: its logo or its name
 * in `headline` on two lines at most, broken at its separator ([titleLines]) ([title], [logo]), its facts
 * ([film]'s, as [MetaLine] gives them without genres, else its [year]) and its [state] last, never dropped first, then
 * two lines of [overview], their room kept without one.
 */
@Composable
fun VoletWords(
    title: String,
    logo: ImageRef?,
    film: MediaItem?,
    year: String?,
    state: VoletState?,
    overview: String?,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    Box(modifier.fillMaxWidth().heightIn(min = textRoom(VoletDefaults.words)), contentAlignment = Alignment.BottomStart) {
        Column {
            TitleArt(
                rememberTitleLines(title),
                logo,
                HeroDefaults.titleWidth,
                HeroDefaults.titleHeight,
                style = Hl.type.headline,
                maxLines = TITLE_LINES,
            )
            Spacer(Modifier.height(HlSpace.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    film != null -> MetaLine(film, Modifier.weight(1f, fill = false), showGenres = false)
                    year != null -> Text(year, style = Hl.type.meta, color = c.content, maxLines = 1)
                }
                if (state != null) {
                    if (film != null || year != null) Spacer(Modifier.width(HlSpace.fact))
                    VoletStateWords(state)
                }
            }
            Spacer(Modifier.height(HlSpace.s))
            Text(
                overview.orEmpty(),
                style = Hl.type.body,
                color = c.contentSecondary,
                maxLines = OVERVIEW_LINES,
                minLines = OVERVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * [title] as an open panel sets it, [fits] saying whether a line holds on one line in its measure: whole when it fits;
 * else broken at a separator (a dash, a French colon), dropped there, where each side fits (« Star Wars, épisode II »
 * / « L'Attaque des clones »); else its separator held to the words around it, so no line ends or begins with it.
 */
internal fun titleLines(title: String, fits: (String) -> Boolean): String {
    if (fits(title)) return title
    for (mark in SPACED_MARK.findAll(title)) {
        val before = title.substring(0, mark.range.first)
        val after = title.substring(mark.range.last + 1)
        if (fits(before) && fits(after)) return "$before\n$after"
    }
    return title.replace(SPACED_MARK) { "$NO_BREAK${it.groupValues[1]}$NO_BREAK" }
}

/** [title] in `headline` as [titleLines] breaks it in a hero's title width, at the viewer's text size. */
@Composable
private fun rememberTitleLines(title: String): String {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = Hl.type.headline
    return remember(title, style, density) {
        val width = with(density) { HeroDefaults.titleWidth.roundToPx() }
        titleLines(title) { line -> measurer.oneLine(line, style, density).width <= width }
    }
}

/** A film's state on its facts' line: by its light ([HlStatus]), else its words, white when lit (« À suivre »). */
@Composable
private fun VoletStateWords(state: VoletState) {
    val light = state.light
    if (light != null) {
        HlStatus(light, state.words, maxLines = 1)
    } else {
        Text(state.words, style = Hl.type.meta, color = if (state.lit) Hl.colors.content else Hl.colors.contentSecondary, maxLines = 1)
    }
}

/**
 * The saga's open panel's words, bottom-anchored in its title's width: its [name] (a server's [logo], else set to its
 * measure: the signature on one line, else `display`), its [facts] on one line (the first white, the last giving way
 * first), then two lines of [overview], [onCut] told whether they cut it (OK then opens it whole).
 */
@Composable
fun SagaVoletWords(
    name: String,
    logo: ImageRef?,
    facts: List<String>,
    overview: String?,
    onCut: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    Column(modifier) {
        TitleArt(name, logo, HeroDefaults.titleWidth, HlLayout.titleArtHeight, measured = true)
        Spacer(Modifier.height(HlSpace.s))
        FactRow(facts, lead = c.content)
        if (!overview.isNullOrBlank()) {
            Spacer(Modifier.height(HlSpace.s))
            Text(
                overview,
                style = Hl.type.body,
                color = c.contentSecondary,
                maxLines = OVERVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { onCut(it.hasVisualOverflow) },
            )
        }
    }
}

/** A panel's ground, its dark or a picture over it, covers it whole: it takes no plate (DESIGN.md, Performance). */
private val GroundCovers: () -> Float = { Float.POSITIVE_INFINITY }

/** Closed panels kept between the open one and either end of a long saga's films as they scroll. */
private const val KEPT_CLOSED = 2

/** Panels past a long saga's view asked for their posters on either side, a few steps before they come into it. */
internal const val POSTERS_AHEAD = 4

/**
 * Panels past a long saga's view whose posters, once asked for, stay asked: going back, they are there. A held arrow
 * asks for as many ahead on the side it runs to, its steps coming every 50 to 110 ms.
 */
internal const val POSTERS_KEPT = 8

/** Overview lines in an open panel, as in a hero. */
private const val OVERVIEW_LINES = 2

/** An open film's title lines without its logo. */
private const val TITLE_LINES = 2

/** A dash or a French colon set between two words; and the space that holds it to them. */
private val SPACED_MARK = Regex(""" ([-–—:]) """)
private const val NO_BREAK = '\u00A0'

/** Half a pixel, which a panel's edge may pass the polyptych's by in rounding. */
private const val EDGE_SLACK = 0.5f

/** A spine's name turned to read from the foot up. */
private const val QUARTER_TURN = -90f

/** A year as wide and as tall as any, to place the years' band. */
private const val YEAR_SAMPLE = "2000"

/** The ramp of home's case curve: clear at its first stop, whole at its first opaque one. */
private val MELT_START = HlScrim.CASE_FOOT.first().at
private val MELT_END = HlScrim.CASE_FOOT.first { it.alpha >= 1f }.at
