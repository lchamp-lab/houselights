package app.houselights.ui.design.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.ReadOnlyComposable
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
import androidx.compose.ui.draw.clip
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
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalGroupFocus
import app.houselights.ui.design.Scrub
import app.houselights.ui.design.VeilStop
import app.houselights.ui.design.VoletDefaults
import app.houselights.ui.design.alphaAt
import app.houselights.ui.design.colorStops
import app.houselights.ui.design.fadeOutsideSafeArea
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
 * [year] at its foot, the share of it seen ([progress]); read as [readAs] then [state], then what its words [say] open.
 * The saga's panel sets its [spine] name upright when closed. OK on it [acts] or does nothing. Equal when it shows the
 * same, so a step along the polyptych recomposes the two panels it concerns alone.
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
)

/** A saga's numbers, one size for the whole polyptych: the signature (72), `display` (48) or `headline` (36). */
enum class VoletNumbers { Signature, Display, Headline }

/**
 * A saga's panels across a width and a height: [count] of them, one [open] wide, the others [closed] wide, [gap] apart,
 * their numbers in [numbers]; the closed panels show their [years] while two of them stand [VoletDefaults.yearsApart]
 * apart at least (« greffe 2 »), else none does; past eight films they [scrolls]. Pure: the board's numbers, at every
 * text size.
 */
@Immutable
data class VoletPlan(
    val count: Int,
    val open: Dp,
    val closed: Dp,
    val gap: Dp,
    val height: Dp,
    val numbers: VoletNumbers,
    val years: Boolean,
    val scrolls: Boolean,
) {
    companion object {
        /**
         * The plan of [count] panels in [width] × [height], the closed ones sharing what the open one leaves, between
         * [VoletDefaults.narrowest] and their poster whole (the open one then takes the rest); their years [year] wide
         * at most, as measured.
         */
        fun of(count: Int, width: Dp, height: Dp, year: Dp = 0.dp): VoletPlan {
            val gap = VoletDefaults.gap
            val others = count - 1
            if (others <= 0) return VoletPlan(count, width, width, gap, height, numbersAt(width), yearsFit(width, gap, year), false)
            // A closed panel shows its poster whole at most, never more: past that, the open one takes the rest.
            val poster = (height * HlSize.POSTER_RATIO).coerceAtLeast(VoletDefaults.narrowest)
            val closed = ((width - VoletDefaults.open - gap * others) / others).coerceIn(VoletDefaults.narrowest, poster)
            val open = if (closed >= poster) width - (closed + gap) * others else VoletDefaults.open
            val scrolls = (open + (closed + gap) * others - width).value > SLACK
            return VoletPlan(count, open, closed, gap, height, numbersAt(closed), yearsFit(closed, gap, year), scrolls)
        }

        /**
         * Whether years [year] wide, centred on neighbouring panels [closed] wide and [gap] apart, keep
         * [VoletDefaults.yearsApart] between them: the room left on each side of a year, twice, and the gap.
         */
        fun yearsFit(closed: Dp, gap: Dp, year: Dp): Boolean = closed - year + gap >= VoletDefaults.yearsApart

        /** The numbers' size by the closed panels' width: the signature to three films, `display` for four and five. */
        fun numbersAt(closed: Dp): VoletNumbers = when {
            closed >= VoletDefaults.numberLarge -> VoletNumbers.Signature
            closed >= VoletDefaults.numberMedium -> VoletNumbers.Display
            else -> VoletNumbers.Headline
        }

        /** A sum of widths within this of the width fits: a dp's rounding is no scroll. */
        private const val SLACK = 0.5f
    }
}

/** The style of [VoletNumbers], from the type scale: title styles doing a figure's job. */
@Composable
@ReadOnlyComposable
internal fun VoletNumbers.style(): TextStyle = when (this) {
    VoletNumbers.Signature -> Hl.type.titleSignature
    VoletNumbers.Display -> Hl.type.display
    VoletNumbers.Headline -> Hl.type.headline
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
 * closed, wordless, receding while one has focus. A move opens the panel reached as the one left closes over
 * [HlMotion.FOCUS_IN], the total kept; a held arrow, or [VoletDefaults.opensAtOnce], jumps. Focus enters on [enter]
 * the first time (the film Lire plays), then on the panel it left; Up goes to [upTo] (else nowhere), Down to [downTo];
 * [onFocus] says which has focus, null when none; OK does [onOpen]. [keyed] keys a panel's focus. Past eight films, the
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
    enter: Int? = null,
    upTo: FocusRequester? = null,
    downTo: FocusRequester? = null,
    keyed: (Int) -> Modifier = { Modifier },
    words: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val plan = VoletPlan.of(parts.size, maxWidth, maxHeight, rememberYearWidth(parts.mapNotNull { it.year }))
        val type = rememberVoletType(plan, parts.firstNotNullOfOrNull { it.spine })
        val group = remember { GroupFocus() }
        val requesters = remember(parts.size) { List(parts.size) { FocusRequester() } }
        var within by remember { mutableStateOf(false) }
        val motion = rememberVoletMotion(open)
        val policy = remember(plan, motion) { voletsPolicy(plan, motion) }
        val posters = rememberPosters(plan, maxWidth, open)
        CompositionLocalProvider(LocalGroupFocused provides within, LocalGroupFocus provides group) {
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
                                onClick = { onOpen(i) },
                                onFocus = { onFocus(i) },
                                modifier = keyed(i)
                                    .focusRequester(requesters[i])
                                    .focusProperties {
                                        up = upTo ?: FocusRequester.Cancel
                                        if (downTo != null) down = downTo
                                    },
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
                    .keepChildFocus(group, enter?.let(requesters::getOrNull)),
                measurePolicy = policy,
            )
        }
    }
}

/** The widest of [years] as set in `meta`: the plan keeps the closed panels' years only while they stand apart. */
@Composable
private fun rememberYearWidth(years: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val meta = Hl.type.meta
    return remember(years, meta, density) {
        val widest = years.maxOfOrNull { measurer.oneLine(it, meta, density).width }
        with(density) { widest?.toDp() } ?: 0.dp
    }
}

/** [text] as set in [style] on one line, as wide as it runs. */
private fun TextMeasurer.oneLine(text: String, style: TextStyle, density: Density): IntSize =
    measure(text, style, maxLines = 1, softWrap = false, density = density).size

/**
 * The type a polyptych sets: its [numbers]' one size, the [band] at the top they stand in (px), the [spine]'s name's
 * style, and how high every closed panel's year rises from the foot ([yearRise], px), so their bands make one line.
 */
@Immutable
private class VoletType(val numbers: TextStyle, val band: Float, val spine: TextStyle, val yearRise: Float)

/** [plan]'s type: its numbers by its width, its [spine]'s name in them while it fits on one line from `display`, else `rowTitle`. */
@Composable
private fun rememberVoletType(plan: VoletPlan, spine: String?): VoletType {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val meta = Hl.type.meta
    val rowTitle = Hl.type.rowTitle
    val numbers = plan.numbers.style()
    return remember(plan, spine, numbers, meta, rowTitle, density) {
        with(density) {
            val fits = spine == null || measurer.oneLine(spine, numbers, density).width <= (plan.height - HlSpace.l * 2).toPx()
            val tall = measurer.oneLine(YEAR_SAMPLE, meta, density).height
            VoletType(
                numbers = numbers,
                band = VoletDefaults.numberTop.toPx() + numbers.lineHeight.toPx(),
                spine = if (plan.closed >= VoletDefaults.numberMedium && fits) numbers else rowTitle,
                yearRise = HlSpace.m.toPx() + tall,
            )
        }
    }
}

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
 * The panels as wide as [motion] has them open, read while placing: the opening relays them out, it never recomposes
 * them. A long saga scrolls panel by panel, the open one kept [KEPT_CLOSED] closed panels from either end, the scroll
 * moving with the widths.
 */
private fun voletsPolicy(plan: VoletPlan, motion: VoletMotion) = MeasurePolicy { measurables, constraints ->
    val height = constraints.maxHeight
    val gap = plan.gap.toPx()
    val closed = plan.closed.toPx()
    val wide = plan.open.toPx()
    val widths = FloatArray(measurables.size) { i -> closed + (wide - closed) * motion.openness(i) }
    val room = constraints.maxWidth.toFloat()
    val total = widths.sum() + gap * (measurables.size - 1).coerceAtLeast(0)
    motion.scroll = if (total > room + 1f) {
        // Where the move ends: the panel reached open, those before it closed.
        val left = (closed + gap) * motion.open
        val margin = (closed + gap) * KEPT_CLOSED
        val from = motion.scrollFrom
        val to = keptInView(from, left, wide, margin, room).coerceIn(0f, total - room)
        from + (to - from) * motion.t.value
    } else {
        0f
    }
    val placeables = measurables.mapIndexed { i, child ->
        child.measure(
            Constraints.fixed(widths[i].roundToInt().coerceAtLeast(0), height),
        )
    }
    layout(constraints.maxWidth, height) {
        var x = -motion.scroll
        placeables.forEachIndexed { i, placeable ->
            placeable.place(x.roundToInt(), 0)
            x += widths[i] + gap
        }
    }
}

/**
 * Where a long saga's polyptych scrolls to from [from] as a panel opens, [left] from its start and [wide]: no further
 * than keeps [margin] between that panel and either end of the [room]. In px or dp alike.
 */
internal fun keptInView(from: Float, left: Float, wide: Float, margin: Float, room: Float): Float =
    from.coerceIn(minOf(left + wide - (room - margin), left - margin), left - margin)

/**
 * The panels a long saga asks its posters for (#55): those in view once its polyptych has scrolled to the panel open,
 * as [voletsPolicy] scrolls it, those it passes by on its way from the view it leaves, and [POSTERS_AHEAD] more on
 * either side, so that a poster is there before its panel comes into view; a poster asked stays asked while its panel
 * is within [POSTERS_KEPT] of them. A saga in view whole asks them all. In dp, the polyptych [room] wide.
 */
internal class PosterWindow(private val plan: VoletPlan, private val room: Float) {
    private val step = (plan.closed + plan.gap).value
    private var scroll = 0f
    private var open = -1
    private var view: IntRange? = null
    private var asked = IntRange.EMPTY

    /** The panels whose posters are asked for, panel [to] open; asked again for the same panel, the same. */
    fun moveTo(to: Int): IntRange {
        val all = 0 until plan.count
        if (!plan.scrolls) return all
        if (to == open) return asked
        open = to
        val wide = plan.open.value
        val total = wide + step * (plan.count - 1)
        scroll = keptInView(scroll, step * to, wide, step * KEPT_CLOSED, room).coerceIn(0f, total - room)
        val shown = all.filter { inView(it, to) }
        val now = (shown.firstOrNull() ?: to)..(shown.lastOrNull() ?: to)
        // The view it leaves, the one it reaches, and what it passes by between them, whatever the jump.
        val passed = view?.let { minOf(it.first, now.first)..maxOf(it.last, now.last) } ?: now
        view = now
        val near = (passed.first - POSTERS_AHEAD).coerceAtLeast(0)..(passed.last + POSTERS_AHEAD).coerceAtMost(plan.count - 1)
        val kept = maxOf(asked.first, passed.first - POSTERS_KEPT)..minOf(asked.last, passed.last + POSTERS_KEPT)
        asked = if (asked.isEmpty() || kept.isEmpty()) near else minOf(near.first, kept.first)..maxOf(near.last, kept.last)
        return asked
    }

    /** Whether panel [i] shows in the room, panel [at] open and the others closed. */
    private fun inView(i: Int, at: Int): Boolean {
        val x = step * i + (if (i > at) (plan.open - plan.closed).value else 0f) - scroll
        val width = if (i == at) plan.open.value else plan.closed.value
        return x < room && x + width > 0f
    }
}

/** The panels of [plan] whose posters are asked for, panel [open] open, in a polyptych [room] wide ([PosterWindow]). */
@Composable
private fun rememberPosters(plan: VoletPlan, room: Dp, open: Int): IntRange {
    val window = remember(plan, room) { PosterWindow(plan, room.value) }
    return remember(window, open) { window.moveTo(open) }
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
 * One panel: a case's recipe (a picture with words on its own dark), not a card. Closed, its poster at its natural size
 * cut by its width, its number at the top on its axis, its [VoletPart.mark] and year at its foot (the year while the
 * [plan] keeps them); open and [settled], its backdrop rising over the poster once drawn, with its [words] at its foot
 * in one cut ([HlMotion.HOLD] at most after it settled), its number at its start, as far as it is [opened]; the
 * backdrop leaving it fades out, or goes at once when it jumps. The saga's closed panel is its spine, its backdrop
 * receding and its name upright. Framed with focus, glowing in its film's light; a greyed film framed without a glow, as
 * [AbsentCard] is, even while the room still holds the light of the film before it.
 */
@Composable
private fun Volet(
    part: VoletPart,
    plan: VoletPlan,
    type: VoletType,
    opens: Boolean,
    opened: () -> Float,
    settled: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    words: @Composable () -> Unit,
) {
    val c = Hl.colors
    val saga = part.number == null
    val frame = rememberVoletFrame(plan.height)
    val poster = part.poster?.url(frame.posterPx.width)?.takeUnless { saga }
    val backdrop = part.backdrop?.url(frame.backdropPx.width)
    val wait = rememberVoletWait(backdrop, settled)
    val wide = wait.shows(settled, pictureless = backdrop == null)
    // A picture's own dark, read once each from its sample.
    val posterRead by rememberPictureRead(part.poster.takeUnless { saga })
    val backdropRead by rememberPictureRead(part.backdrop.takeIf { settled || saga })
    val dark = darkOf(part.grey, wide || saga, posterRead, backdropRead) ?: c.ambientDeep
    // Settled from its first frame (back from its page), its picture comes in one cut, as the screen does; then it rises.
    var cuts by remember { mutableStateOf(settled) }
    LaunchedEffect(settled) { if (!settled) cuts = false }
    // The backdrop comes up over the poster once settled and drawn; the saga's spine keeps it.
    val up = rememberRise(saga || settled && wait.pictured, cut = cuts)
    val ink = if (part.grey) c.contentSecondary else c.content
    val floor = rememberNumberFloor(plan, frame, type, opens, ink, dark, part.grey, posterRead, backdropRead)
    var wordsTop by remember { mutableFloatStateOf(Float.NaN) }
    ArtworkSurface(
        onClick = onClick,
        modifier = modifier,
        shape = HlShapes.wide,
        onFocus = onFocus,
        // Its state, then what it says open: a panel has focus only open.
        stateDescription = listOfNotNull(part.state, part.say).joinToString(", ").ifEmpty { null },
        readAs = part.readAs,
        acts = part.acts,
        light = if (part.grey) null else backdrop ?: poster,
        glows = !part.grey,
        // Its ground covers it whole, its dark or a picture over it: no plate under them.
        covered = GroundCovers,
    ) {
        VoletPictures(dark, poster, backdrop, part.grey, frame, shows = saga || settled, up, wait)
        if (!saga) TopMelt(dark, floor, type.band)
        if (wide) WordsMelt(dark) { wordsTop } else FootMelt(dark, type.yearRise)
        VoletMarks(part, plan, type, ink, closed = !wide, opens = opens, opened = opened)
        if (wide) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = HlInset.card, bottom = HlInset.card)
                    .width(HeroDefaults.titleWidth)
                    .onPlaced { wordsTop = it.positionInParent().y },
            ) { words() }
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
 * its foot; the saga's, unless it [opens], its spine's name upright; the share of a film seen at its very foot.
 */
@Composable
private fun BoxScope.VoletMarks(
    part: VoletPart,
    plan: VoletPlan,
    type: VoletType,
    ink: Color,
    closed: Boolean,
    opens: Boolean,
    opened: () -> Float,
) {
    val number = part.number
    if (number != null) {
        VoletNumber(number, type.numbers, ink, opened)
        if (closed) VoletFoot(part, plan, Modifier.align(Alignment.BottomCenter))
    } else if (!opens) {
        part.spine?.let { VerticalName(it, type.spine, Modifier.align(Alignment.BottomCenter).padding(vertical = HlSpace.l)) }
    }
    if (part.progress > 0f) ProgressLine(part.progress, Modifier.align(Alignment.BottomCenter), HlSize.progressWide)
}

/**
 * A closed film's foot: its mark, then its year, [HlSpace.m] from the foot; the years go for the whole polyptych when
 * they would touch (« greffe 2 »), the open panel saying its own, and fade over the safe margin when a long saga runs
 * past it.
 */
@Composable
private fun VoletFoot(part: VoletPart, plan: VoletPlan, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val year = part.year?.takeIf { plan.years }
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
                modifier = Modifier.clearAndSetSemantics {}.then(if (plan.scrolls) Modifier.fadeOutsideSafeArea() else Modifier),
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
private fun VoletNumber(number: Int, style: TextStyle, color: Color, opened: () -> Float) {
    Text(
        "$number",
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.clearAndSetSemantics {}.layout { measurable, constraints ->
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
            onDrawBehind { drawVeil(brush, stops, 0f, screen) }
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
            onDrawBehind { drawVeil(brush, stops, 0f, size.height) }
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
            onDrawBehind { drawVeil(brush, HlScrim.CASE_FOOT, from, from + span) }
        },
    )
}

/**
 * [brush], the veil [stops] drawn from [startY] to [endY] (px), over the band it is not clear in alone: a transparent
 * rectangle costs the player's GPU a pass all the same (DESIGN.md, Performance). The same pixels, whole ones.
 */
private fun DrawScope.drawVeil(brush: Brush, stops: List<VeilStop>, startY: Float, endY: Float) {
    val band = stops.inkedBand(startY, endY)
    val top = floor(band.start).coerceIn(0f, size.height)
    val bottom = ceil(band.endInclusive).coerceIn(top, size.height)
    if (bottom > top) drawRect(brush, Offset(0f, top), Size(size.width, bottom - top))
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
    val c = Hl.colors
    Box(
        Modifier
            .size(HlSize.mark)
            .clip(HlShapes.mark)
            .background(c.background.copy(alpha = HlAlpha.VEIL))
            .receding(LocalArtworkRecede.current, c.background),
        contentAlignment = Alignment.Center,
    ) { StatusLight(state, ink = c.contentTertiary) }
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
 * An open film's words, bottom-anchored in a hero's band ([HlLayout.hero]) and its title's width: its logo or its name
 * in `headline` on one line ([title], [logo]), its facts ([film]'s, as [MetaLine] gives them without genres, else its
 * [year]) and its [state] last, never dropped first, then two lines of [overview], their room kept without one.
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
    Box(modifier.fillMaxWidth().heightIn(min = textRoom(HlLayout.hero)), contentAlignment = Alignment.BottomStart) {
        Column {
            TitleArt(title, logo, HeroDefaults.titleWidth, HeroDefaults.titleHeight, style = Hl.type.headline, maxLines = 1)
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
 * measure: the signature on one line, else `display`), its [facts] (the first white), its [progress] in words, then two
 * lines of [overview], [onCut] told whether they cut it (OK then opens it whole).
 */
@Composable
fun SagaVoletWords(
    name: String,
    logo: ImageRef?,
    facts: List<String>,
    progress: List<String>,
    overview: String?,
    onCut: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    Column(modifier) {
        TitleArt(name, logo, HeroDefaults.titleWidth, HlLayout.titleArtHeight, measured = true)
        Spacer(Modifier.height(HlSpace.s))
        FactRow(facts, lead = c.content)
        if (progress.isNotEmpty()) {
            Spacer(Modifier.height(HlSpace.xs))
            OneLine {
                progress.forEach { Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1) }
            }
        }
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

/** Closed panels kept between the open one and either end of a long saga's polyptych as it scrolls. */
private const val KEPT_CLOSED = 2

/** Panels past a long saga's view asked for their posters on either side, a few steps before they come into it. */
private const val POSTERS_AHEAD = 4

/** Panels past a long saga's view whose posters, once asked for, stay asked: going back, they are there. */
private const val POSTERS_KEPT = 8

/** Overview lines in an open panel, as in a hero. */
private const val OVERVIEW_LINES = 2

/** A spine's name turned to read from the foot up. */
private const val QUARTER_TURN = -90f

/** A year as wide and as tall as any, to place the years' band. */
private const val YEAR_SAMPLE = "2000"

/** The ramp of home's case curve: clear at its first stop, whole at its first opaque one. */
private val MELT_START = HlScrim.CASE_FOOT.first().at
private val MELT_END = HlScrim.CASE_FOOT.first { it.alpha >= 1f }.at
