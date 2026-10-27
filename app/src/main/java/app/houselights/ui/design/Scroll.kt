package app.houselights.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import kotlin.math.abs

/**
 * Scrolls only as far as needed to bring an element into [leading, containerSize − trailing] (px), not to a
 * third of the screen as the TV default does. With [toTop] ([scrollsAsBlock] pages), the block always comes
 * to the start: the distance is asked again every frame, so it holds until the block arrives.
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

/**
 * A sheet's scrolling: focus stops [edge] from its ends, as in a list, and goes all the way to an end of what the
 * sheet holds once within [near] of it, so a group's title above the first row or a note under the last row is
 * never left under a fade.
 */
@OptIn(ExperimentalFoundationApi::class)
class SheetScroll(private val scroll: ScrollState, edge: Float, private val near: Float) : BringIntoViewSpec {
    private val list = EdgeScroll(edge, edge)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val fromStart = scroll.value + offset
        val toEnd = scroll.maxValue + containerSize - (fromStart + size)
        return when {
            fromStart <= near -> -scroll.value.toFloat()
            toEnd <= near -> (scroll.maxValue - scroll.value).toFloat()
            else -> list.calculateScrollDistance(offset, size, containerSize)
        }
    }
}

/** Within half a pixel of its target, a block has arrived. */
private const val SETTLED = 0.5f

/** Vertical list scrolling app-wide ([HouselightsTheme]): focus stops [top] and [HlFocus.edge] from the ends. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberListScroll(top: Dp = HlFocus.edge, toTop: Boolean = false): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density, top, toTop) { with(density) { EdgeScroll(top.toPx(), HlFocus.edge.toPx(), toTop) } }
}

/**
 * For a list starting below a hero or header: rows come back to [top], where the first rests, so the row above
 * leaves whole. With [toTop] (a detail page's blocks), every moving block comes to [top].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListScroll(top: Dp, toTop: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberListScroll(top, toTop), content = content)
}

/** Rows keep focus [ROW_MARGIN] from both screen ends, so a row returns to its rest and the next card peeks in. */
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
 * Fades content into [color] over [HlSpace.fadeEdge] at an end with more beyond, so a cut line reads as more
 * to come. Drawn without a layer, so the surface must be opaque there. Not for poster rows ([RowScroll]).
 */
fun Modifier.scrollEdges(state: ScrollableState, color: Color): Modifier = scrollEdges(state) { color }

/** [scrollEdges] into a surface whose [color] is read while drawing (a sheet in its film's light). */
fun Modifier.scrollEdges(state: ScrollableState, color: () -> Color): Modifier = composed {
    val fade = tween<Float>(HlMotion.FAST, easing = HlMotion.fallBack)
    val top by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, fade, label = "fadeTop")
    val bottom by animateFloatAsState(if (state.canScrollForward) 1f else 0f, fade, label = "fadeBottom")
    drawWithContent {
        drawContent()
        if (top <= 0f && bottom <= 0f) return@drawWithContent
        val into = color()
        val length = HlSpace.fadeEdge.toPx().coerceAtMost(size.height / 2)
        val edge = Size(size.width, length)
        val clear = into.copy(alpha = 0f)
        if (top > 0f) {
            drawRect(Brush.verticalGradient(0f to into.copy(alpha = into.alpha * top), 1f to clear, endY = length), size = edge)
        }
        if (bottom > 0f) {
            val from = size.height - length
            drawRect(
                Brush.verticalGradient(0f to clear, 1f to into.copy(alpha = into.alpha * bottom), startY = from, endY = size.height),
                topLeft = Offset(0f, from),
                size = edge,
            )
        }
    }
}

/**
 * Fades the content itself out into what lies under it, a field of posters, over [HlSpace.fadeEdge] at an end with
 * more beyond, so a plate cut by the list's edge reads as more to come where a fade into black would not show over
 * plates as dark: while there is something to fade, the content is composed in a layer of its own (offscreen, set
 * while drawing) whose edge a gradient erases. The layer reaches [start] and [end] past the element's sides, so the
 * plates and focus frames hanging past them are not cut. Focus stays clear of the fade, as with [scrollEdges].
 */
fun Modifier.fadeIntoField(state: ScrollableState, start: Dp, end: Dp): Modifier = composed {
    val fade = tween<Float>(HlMotion.FAST, easing = HlMotion.fallBack)
    val top by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, fade, label = "fieldTop")
    val bottom by animateFloatAsState(if (state.canScrollForward) 1f else 0f, fade, label = "fieldBottom")
    this
        .layout { measurable, constraints ->
            val before = start.roundToPx()
            val after = end.roundToPx()
            val wider = if (constraints.hasBoundedWidth) {
                constraints.copy(minWidth = constraints.minWidth + before + after, maxWidth = constraints.maxWidth + before + after)
            } else {
                constraints
            }
            val placeable = measurable.measure(wider)
            layout((placeable.width - before - after).coerceAtLeast(0), placeable.height) { placeable.place(-before, 0) }
        }
        .graphicsLayer { compositingStrategy = if (top > 0f || bottom > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
        .drawWithContent {
            drawContent()
            if (top <= 0f && bottom <= 0f) return@drawWithContent
            val length = HlSpace.fadeEdge.toPx().coerceAtMost(size.height / 2)
            val edge = Size(size.width, length)
            if (top > 0f) {
                val erase = Brush.verticalGradient(0f to Color.Black.copy(alpha = top), 1f to Color.Transparent, endY = length)
                drawRect(erase, size = edge, blendMode = BlendMode.DstOut)
            }
            if (bottom > 0f) {
                val from = size.height - length
                val erase = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = bottom),
                    startY = from,
                    endY = size.height,
                )
                drawRect(erase, topLeft = Offset(0f, from), size = edge, blendMode = BlendMode.DstOut)
            }
        }
        .padding(start = start, end = end)
}

/**
 * Fades a line of text out across the safe-area margin, where an overscanning TV crops, so no word meets
 * the screen's edge. Set as a layer's alpha while placing: no offscreen buffer, no recomposition.
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

    /** Kept across placements: the root coordinates, and the margins in px for the density [marginsAt]. */
    private var root: LayoutCoordinates? = null
    private var marginsAt = 0f
    private var marginH = 0f
    private var marginV = 0f

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
        val root = root?.takeIf { it.isAttached } ?: coordinates.findRootCoordinates().also { root = it }
        val density = requireDensity()
        if (density.density != marginsAt) {
            marginsAt = density.density
            marginH = with(density) { HlSpace.safeH.toPx() }
            marginV = with(density) { HlSpace.safeV.toPx() }
        }
        // Its position in the root, unclipped by the lists it scrolls in, found in one walk up the tree.
        shown = safeAreaAlpha(coordinates.positionInRoot(), coordinates.size.toSize(), root.size.toSize(), marginH, marginV)
    }

    override fun onDetach() {
        root = null
    }
}

/** Alpha of a text at [origin]: 1 inside the safe area, 0 at the screen's edge, linear across the margin. */
internal fun safeAreaAlpha(origin: Offset, size: Size, screen: Size, marginH: Float, marginV: Float): Float = minOf(
    marginShare(origin.x, marginH),
    marginShare(screen.width - (origin.x + size.width), marginH),
    marginShare(origin.y, marginV),
    marginShare(screen.height - (origin.y + size.height), marginV),
)

/** How far into its margin a side stands, from 0 at the screen's edge to 1 at the safe area. */
private fun marginShare(distance: Float, margin: Float): Float =
    if (margin <= 0f) 1f else (distance / margin).coerceIn(0f, 1f)

/** Keeps up and down inside this column instead of jumping to a neighbour; left and right still leave it. */
fun Modifier.keepVerticalFocus(): Modifier = this
    .focusProperties {
        onExit = {
            if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
        }
    }
    .focusGroup()

/** Scrolls as one block: whatever inside asks to be shown, the list brings in the whole block, header included. */
fun Modifier.scrollsAsBlock(): Modifier = this then BlockElement

private data object BlockElement : ModifierNodeElement<BlockNode>() {
    override fun create() = BlockNode()

    override fun update(node: BlockNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "scrollsAsBlock"
    }
}

/** Answers a request from inside by asking the list around it for the whole block, which it then scrolls to. */
private class BlockNode : Modifier.Node(), BringIntoViewModifierNode {
    // The extension's bringIntoView, with no bounds: the node's own, the whole block.
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) = bringIntoView()
}

/** Scrolls the whole element into view on focus, for groups whose title sits above their first focusable. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.revealOnFocus(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    this
        .bringIntoViewRequester(requester)
        .onFocusChanged { if (it.hasFocus) scope.launch { requester.bringIntoView() } }
}

/** Scrolls the whole element into view as it [opens], once laid out at its new size: a block unfolding under its row. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.revealOnOpen(opens: Boolean): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var was by remember { mutableStateOf(opens) }
    LaunchedEffect(opens) {
        if (opens && !was) {
            withFrameNanos { }
            requester.bringIntoView()
        }
        was = opens
    }
    bringIntoViewRequester(requester)
}

/**
 * State of a row whose content changes while shown: a row at its start stays there, so titles arriving in
 * front show instead of landing off screen; a row the viewer moved along stays put.
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
