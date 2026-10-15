package app.houselights.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlin.math.abs

/**
 * Scrolls only by what is needed to bring an element into the window [leading, containerSize −
 * trailing] (px): never to a third of the screen as the TV default does, which scrolls titles and
 * heroes away for no reason. An element longer than the window aligns its start with the window's.
 *
 * With [toTop], a page of blocks ([scrollsAsBlock]): the block asked for always comes to the start
 * of the window, so the page turns by whole blocks and the one above leaves whole rather than as a
 * sliver of text. The distance is asked again on every frame of the motion, so it must hold all the
 * way: a block on its way up keeps moving until it reaches the start, even once it is inside.
 */
@OptIn(ExperimentalFoundationApi::class)
class EdgeScroll(
    private val leading: Float,
    private val trailing: Float,
    private val toTop: Boolean = false,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = leading
        val end = containerSize - trailing
        val itemEnd = offset + size
        return when {
            toTop -> if (abs(offset - start) < SETTLED) 0f else offset - start
            offset >= start && itemEnd <= end -> 0f
            offset < start || size > end - start -> offset - start
            else -> itemEnd - end
        }
    }
}

/** Under half a pixel from where it goes, a block has arrived. */
private const val SETTLED = 0.5f

/**
 * How vertical lists scroll, provided for the whole app by [HouselightsTheme]: what has focus
 * stops [HlFocus.edge] away from the bottom, so the last row rests inside the safe area and a
 * focused poster grows into the margin rather than off the screen, and [top] away from the top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberListScroll(top: Dp = HlFocus.edge, toTop: Boolean = false): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density, top, toTop) { with(density) { EdgeScroll(top.toPx(), HlFocus.edge.toPx(), toTop) } }
}

/**
 * For a list that starts below the top of the screen, under a hero or a header, already inside
 * the safe area: its rows come back to [top], where its first row rests (its top padding), and
 * the row above leaves the list whole instead of lingering as a sliver. With [toTop], a page of
 * blocks (a detail page: its hero, then its rows): every block that has to move comes to [top],
 * so the page shows whole blocks; with a gap between them at least [top] wide, the one above has
 * left the screen entirely.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListScroll(top: Dp, toTop: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberListScroll(top, toTop), content = content)
}

/**
 * Rows keep what has focus [ROW_MARGIN] away from both ends of the screen. With no margin, a row
 * scrolled back to its first card would stop with that card against the screen edge; with a
 * margin wider than the row's padding, the row comes back to where it rests, and moving right
 * always lets the next card peek in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowScroll(content: @Composable () -> Unit) {
    val margin = with(LocalDensity.current) { ROW_MARGIN.toPx() }
    val spec = remember(margin) { EdgeScroll(margin, margin) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Wider than any row's start padding, so the first card always scrolls fully home. */
private val ROW_MARGIN = HlSpace.safeH + HlSpace.xl

/**
 * What scrolls fades into [color], the surface it scrolls on, over [HlSpace.fadeEdge] at the top
 * and the bottom, on each side only while there is more that way: a line cut at the edge reads as
 * more to come, not as a defect. A gradient of the surface's own colour over the content, without
 * a layer, so the surface must be opaque there (the black room, a raised sheet). An edge comes and
 * goes over [HlMotion.FAST] as an end is reached. Focus stays clear of it: a list keeps
 * [HlFocus.edge] (wider) from its ends, and one that rests against its top comes back to
 * [HlSpace.fadeEdge] (`ListScroll`). Not for rows of posters: [RowScroll] keeps their ends open.
 */
fun Modifier.scrollEdges(state: ScrollableState, color: Color): Modifier = composed {
    val top by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, tween(HlMotion.FAST), label = "fadeTop")
    val bottom by animateFloatAsState(if (state.canScrollForward) 1f else 0f, tween(HlMotion.FAST), label = "fadeBottom")
    drawWithContent {
        drawContent()
        val length = HlSpace.fadeEdge.toPx().coerceAtMost(size.height / 2)
        val edge = Size(size.width, length)
        val clear = color.copy(alpha = 0f)
        if (top > 0f) {
            drawRect(Brush.verticalGradient(0f to color.copy(alpha = color.alpha * top), 1f to clear, endY = length), size = edge)
        }
        if (bottom > 0f) {
            val from = size.height - length
            drawRect(
                Brush.verticalGradient(0f to clear, 1f to color.copy(alpha = color.alpha * bottom), startY = from, endY = size.height),
                topLeft = Offset(0f, from),
                size = edge,
            )
        }
    }
}

/**
 * Words never meet the edge of the screen: what this modifier holds (a line of text) is opaque
 * inside the safe area and fades out across its margin ([HlSpace.safeH] at the sides,
 * [HlSpace.safeV] at the top and the bottom), gone where it would touch the edge. Pictures may
 * peek past the safe area and invite the eye on; a word cut by the screen reads as a defect, and
 * the margin is where an overscanning TV crops. Read from where the text sits on the screen as
 * rows and pages scroll: a layer's alpha, set while placing, with no offscreen buffer and no
 * recomposition. For text only, inside components: a row's header, a card's caption.
 */
fun Modifier.fadeOutsideSafeArea(): Modifier = this then FadeOutsideSafeArea

private data object FadeOutsideSafeArea : ModifierNodeElement<SafeAreaFadeNode>() {
    override fun create() = SafeAreaFadeNode()
    override fun update(node: SafeAreaFadeNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "fadeOutsideSafeArea"
    }
}

private class SafeAreaFadeNode : Modifier.Node(), LayoutModifierNode, GlobalPositionAwareModifierNode {
    private var shown by mutableFloatStateOf(1f)

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                alpha = shown
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val root = coordinates.findRootCoordinates()
        // Where the text really is, cut or not: bounds in the root unclipped by the lists it scrolls in.
        val origin = root.localPositionOf(coordinates, Offset.Zero)
        val density = requireDensity()
        shown = safeAreaAlpha(
            left = origin.x,
            top = origin.y,
            right = origin.x + coordinates.size.width,
            bottom = origin.y + coordinates.size.height,
            screenWidth = root.size.width.toFloat(),
            screenHeight = root.size.height.toFloat(),
            marginH = with(density) { HlSpace.safeH.toPx() },
            marginV = with(density) { HlSpace.safeV.toPx() },
        )
    }
}

/**
 * How much of a text placed at [left], [top], [right], [bottom] on a screen [screenWidth] by
 * [screenHeight] shows: all of it inside the safe area, none where it reaches the screen's edge,
 * in between across the margin, by the side that comes closest.
 */
internal fun safeAreaAlpha(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    screenWidth: Float,
    screenHeight: Float,
    marginH: Float,
    marginV: Float,
): Float = minOf(
    marginShare(left, marginH),
    marginShare(screenWidth - right, marginH),
    marginShare(top, marginV),
    marginShare(screenHeight - bottom, marginV),
)

/** How far into its margin a side stands, from 0 at the screen's edge to 1 at the safe area. */
private fun marginShare(distance: Float, margin: Float): Float =
    if (margin <= 0f) 1f else (distance / margin).coerceIn(0f, 1f)

/**
 * Keeps vertical navigation inside this column: up or down at its edges does nothing instead
 * of jumping to a neighbouring column. Left and right still leave it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.keepVerticalFocus(): Modifier = this
    .focusProperties {
        exit = { direction ->
            if (direction == FocusDirection.Up || direction == FocusDirection.Down) FocusRequester.Cancel else FocusRequester.Default
        }
    }
    .focusGroup()

/**
 * This element scrolls as one block: whatever inside it asks to be seen (a card of a row, a button
 * of a hero), the list around it brings the whole block, its header included. A row then comes in
 * with its title whichever card focus lands on, and moving along it asks nothing more of the page.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.scrollsAsBlock(): Modifier = composed {
    val block = remember { Block() }
    this.onSizeChanged { block.size = it }.bringIntoViewResponder(block)
}

@OptIn(ExperimentalFoundationApi::class)
private class Block : BringIntoViewResponder {
    var size = IntSize.Zero

    override fun calculateRectForParent(localRect: Rect): Rect = Rect(Offset.Zero, size.toSize())

    // The block does not scroll itself: the list around it does, for the rectangle above.
    override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit
}

/**
 * When focus enters this element, scroll it fully into view, not only the focused child.
 * Use it on groups whose title or context sits above their first focusable element
 * (a row and its header, a hero and its buttons, a settings section).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.revealOnFocus(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    this
        .bringIntoViewRequester(requester)
        .onFocusChanged { if (it.hasFocus) scope.launch { requester.bringIntoView() } }
}

/**
 * State of a row whose content can change while it is shown (new titles found by a scan). A list
 * keeps showing the item it was showing first, so titles arriving in front would land out of
 * sight on the left. If the row was at its start, it stays there and the new titles show; if the
 * viewer had moved along it, nothing moves under them.
 */
@Composable
fun rememberRowState(keys: List<Any>): LazyListState {
    val state = rememberLazyListState()
    val first = keys.firstOrNull()
    var shownFirst by remember { mutableStateOf(first) }
    LaunchedEffect(first) {
        // Let the list place itself with the new content first.
        withFrameNanos { }
        val was = keys.indexOf(shownFirst)
        if (was > 0 && state.firstVisibleItemIndex == was) state.scrollToItem(0)
        shownFirst = first
    }
    return state
}
