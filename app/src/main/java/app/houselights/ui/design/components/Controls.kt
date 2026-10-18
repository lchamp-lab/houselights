package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import app.houselights.R
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.ui.design.HlIconButtonDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.groundPlate
import app.houselights.ui.design.RowScroll

enum class Emphasis { Primary, Secondary }

/**
 * Pill button, projector white on focus; the primary one is brighter at rest. Disabled, it keeps focus,
 * outlined, and ignores OK; [busy] shows the pending dot and [busyLabel] at the same width and ignores OK.
 * [detailLit] sets the detail in ember for a state it names (an active filter).
 */
@Composable
fun HlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** Secondary information inside the button: runtime, resume point. */
    detail: String? = null,
    emphasis: Emphasis = Emphasis.Secondary,
    detailLit: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String = text,
) = PillButton(text, onClick, modifier, icon, detail, emphasis, detailLit, enabled, busy, busyLabel)

/** The pill of [HlButton]; [caution] sets the label in ember for an irreversible action ([SheetConfirm]). */
@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    detail: String? = null,
    emphasis: Emphasis = Emphasis.Secondary,
    detailLit: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String = text,
    caution: Boolean = false,
) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val busyState = stringResource(R.string.control_busy)
    val fill = if (emphasis == Emphasis.Primary) HlAlpha.FILL_STRONG else HlAlpha.FILL
    // A Surface: tv-material's Button forces a minimum content height and would not centre the content.
    Surface(
        onClick = { if (!busy) onClick() },
        modifier = modifier.height(HlSize.control).semantics { if (busy) stateDescription = busyState },
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(HlShapes.control),
        colors = FocusStyle.fill(
            rest = c.content.copy(alpha = fill),
            restContent = if (caution) c.ember else c.content,
            disabled = c.content.copy(alpha = HlAlpha.FILL_FAINT),
            disabledContent = c.contentTertiary,
        ),
        border = FocusStyle.disabledRing(HlShapes.control),
        scale = FocusStyle.scale(FocusStyle.Fill.Pill),
        interactionSource = interaction,
    ) {
        Row(
            Modifier
                .align(Alignment.Center)
                .keepWidthWhile(busy)
                .padding(
                    start = if (icon != null || busy) HlControl.buttonPaddingIcon else HlControl.buttonPaddingH,
                    end = HlControl.buttonPaddingH,
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                Box(Modifier.size(HlSize.icon), contentAlignment = Alignment.Center) { StatusLight(StatusState.Pending) }
                Spacer(Modifier.width(HlControl.iconGap))
                ButtonLabel(busyLabel)
            } else {
                // A disabled button keeps its well under focus: white would offer an action.
                ButtonContent(text, icon, detail, FocusStyle.quietInk(focused && enabled, enabled, detailLit))
            }
        }
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, detail: String?, detailColor: Color) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(HlSize.icon))
        Spacer(Modifier.width(HlControl.iconGap))
    }
    ButtonLabel(text)
    if (detail != null) {
        Spacer(Modifier.width(HlSpace.s))
        Text(detail, style = Hl.type.meta, color = detailColor, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** Records the content width at rest and keeps at least that width while [busy], so the row does not shift. */
private fun Modifier.keepWidthWhile(busy: Boolean): Modifier = composed {
    var rest by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    if (busy) widthIn(min = with(density) { rest.toDp() }) else onSizeChanged { rest = it.width }
}

@Composable
private fun ButtonLabel(text: String) {
    Text(
        text,
        style = Hl.type.entry,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = HlLayout.readableNarrow),
    )
}

/** A screen title naming one of two views, then a ⇄ disc switching to the other; [label] names that one. */
@Composable
fun HlTitleSwitch(title: String, label: String, onSwitch: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ScreenTitle(title)
        Spacer(Modifier.width(HlSpace.xs))
        Surface(
            onClick = onSwitch,
            modifier = Modifier.size(HlSize.control),
            shape = ClickableSurfaceDefaults.shape(HlShapes.avatar),
            colors = FocusStyle.fill(rest = Color.Transparent, restContent = Hl.colors.contentSecondary),
            scale = FocusStyle.scale(FocusStyle.Fill.Pill),
        ) {
            Icon(HlIcons.Swap, contentDescription = label, modifier = Modifier.size(HlSize.icon).align(Alignment.Center))
        }
    }
}

/** A row of actions that reserves room below for a round button's focus label, so nothing moves. */
@Composable
fun HlActionRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(HlSpace.s),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.padding(bottom = HlIconButtonDefaults.labelRoom).then(modifier),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

/**
 * Round icon button whose focus label overflows below it without taking layout space (an [HlActionRow]
 * reserves it), centred unless near the screen side ([HlIconButtonDefaults.labelEdge]), hidden under a
 * sheet. A toggle names its action ("Marquer comme vu") and shows its state by [lit].
 */
@Composable
fun HlIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lit: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // A sheet is a separate window: the button keeps focus under it but hides its label.
    val shown = focused && LocalWindowInfo.current.isWindowFocused
    val labelAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(HlMotion.FAST), label = "label")
    val c = Hl.colors
    val plate = groundPlate()
    val restFill = plate ?: c.content.copy(alpha = HlAlpha.FILL)
    Layout(
        modifier = modifier,
        content = {
            Surface(
                onClick = onClick,
                modifier = Modifier.size(HlSize.iconButton),
                enabled = enabled,
                shape = ClickableSurfaceDefaults.shape(HlShapes.avatar),
                scale = FocusStyle.scale(FocusStyle.Fill.Pill),
                border = FocusStyle.disabledRing(HlShapes.avatar),
                colors = FocusStyle.fill(
                    rest = restFill,
                    restContent = if (lit) c.ember else c.content,
                    // Over artwork, a disabled icon is secondary like all quiet content there.
                    disabled = plate ?: c.content.copy(alpha = HlAlpha.FILL_FAINT),
                    disabledContent = if (plate != null) c.contentSecondary else c.contentTertiary,
                ),
                interactionSource = interaction,
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(HlSize.icon).align(Alignment.Center))
            }
            Text(
                label,
                style = Hl.type.caption,
                color = c.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Always on a plate: the label may fall over any picture.
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .graphicsLayer { alpha = labelAlpha }
                    .background(c.background.copy(alpha = HlAlpha.VEIL), HlShapes.control)
                    .padding(horizontal = HlControl.labelPaddingH, vertical = HlControl.labelPaddingV),
            )
        },
    ) { measurables, constraints ->
        val button = measurables[0].measure(constraints)
        val text = measurables[1].measure(Constraints(maxWidth = HlIconButtonDefaults.labelWidth.roundToPx()))
        val gap = HlSpace.xs.roundToPx()
        val edge = HlIconButtonDefaults.labelEdge.roundToPx()
        layout(button.width, button.height) {
            button.place(0, 0)
            val x = coordinates?.let { here ->
                val screen = here.findRootCoordinates()
                val left = screen.localPositionOf(here, Offset.Zero).x.roundToInt()
                labelX(button.width, text.width, left, screen.size.width, edge)
            } ?: ((button.width - text.width) / 2)
            text.place(x, button.height + gap)
        }
    }
}

/** The label's x offset from the button: centred, else aligned to the side nearing [edge], never past it. */
internal fun labelX(button: Int, label: Int, left: Int, screen: Int, edge: Int): Int {
    val centred = (button - label) / 2
    val x = when {
        left + centred + label > screen - edge -> button - label
        left + centred < edge -> 0
        else -> centred
    }
    return x.coerceAtMost(screen - edge - left - label).coerceAtLeast(edge - left)
}

/** A choice in a vertical list (sources, servers): a quiet panel with icon, [badges] and a focus chevron. */
@Composable
fun HlListItem(
    title: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    badges: List<String> = emptyList(),
    descriptionLines: Int = 1,
) {
    val c = Hl.colors
    HlPanelButton(onClick, modifier.fillMaxWidth()) { focused ->
        Column(Modifier.padding(HlInset.card)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (focused) c.content else c.contentSecondary,
                        modifier = Modifier.size(HlSize.discIcon),
                    )
                    Spacer(Modifier.width(HlSpace.s))
                }
                Text(title, style = Hl.type.lead, color = c.content, modifier = Modifier.weight(1f))
                Icon(
                    HlIcons.Chevron,
                    contentDescription = null,
                    tint = c.content,
                    modifier = Modifier.size(HlSize.icon).alpha(if (focused) 1f else 0f),
                )
            }
            if (description != null) {
                Spacer(Modifier.height(HlSpace.xs))
                Text(description, style = Hl.type.caption, color = c.contentSecondary, minLines = descriptionLines)
            }
            if (badges.isNotEmpty()) {
                Spacer(Modifier.height(HlSpace.m))
                HlDivider()
                Spacer(Modifier.height(HlSpace.s))
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) { badges.forEach { Badge(it) } }
            }
        }
    }
}

/**
 * Exclusive options in one pill track: the current one on a quiet sliding pill, the focused one white.
 * Focus enters on [enterAt] (the current option by default), never the nearest; options read as tabs.
 * [selectOnFocus] selects on move, as TV tabs do; [details] add a check or "6 / 10".
 */
@Composable
fun HlSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectOnFocus: Boolean = false,
    scrollable: Boolean = false,
    enterAt: Int = selected,
    details: List<SegmentDetail?> = emptyList(),
) {
    val places = remember(options.size) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    // A long track scrolls with its pill; end padding leaves room for a focused option to grow.
    val scroll = rememberScrollState()
    val content = if (scrollable) Modifier.horizontalScroll(scroll).padding(horizontal = HlSpace.xxs) else Modifier

    val well = groundPlate() ?: Hl.colors.content.copy(alpha = HlAlpha.FILL_FAINT)
    val track = modifier
        .background(well, HlShapes.control)
        .padding(vertical = HlSpace.xxs, horizontal = if (scrollable) HlSpace.none else HlSpace.xxs)
    RowScroll {
        Box(track) {
            Box(content) {
                // The quiet pill under the options, sliding to the selected one.
                places[selected]?.let { (x, w) ->
                    val slide = tween<Float>(HlMotion.STANDARD, easing = HlMotion.emphasized)
                    val left by animateFloatAsState(x, slide, label = "pillX")
                    val width by animateFloatAsState(w, slide, label = "pillWidth")
                    Box(
                        Modifier
                            .offset { IntOffset(left.roundToInt(), 0) }
                            // Width read at measure, offset at placement: the pill slides without recomposing.
                            .layout { measurable, constraints ->
                                val px = width.toDp().roundToPx().coerceAtLeast(0)
                                val placeable = measurable.measure(constraints.constrain(Constraints(minWidth = px, maxWidth = px)))
                                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                            }
                            .height(HlSize.segment)
                            .background(Hl.colors.content.copy(alpha = HlAlpha.FILL_STRONG), HlShapes.control),
                    )
                }
                Row(Modifier.enterOn(requesters, enterAt).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                    options.forEachIndexed { i, label ->
                        SegmentItem(
                            label,
                            detail = details.getOrNull(i),
                            selected = i == selected,
                            onClick = { onSelect(i) },
                            onFocused = { if (selectOnFocus) onSelect(i) },
                            modifier = Modifier
                                .focusRequester(requesters[i])
                                .onPlaced { places[i] = it.positionInParent().x to it.size.width.toFloat() },
                        )
                    }
                }
            }
        }
    }
}

/** A focus group entered on [current] from any side; on the nearest option when it is out of range. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.enterOn(requesters: List<FocusRequester>, current: Int): Modifier = this
    .focusProperties { enter = { requesters.getOrNull(current) ?: FocusRequester.Default } }
    .focusGroup()

@Composable
private fun SegmentItem(
    text: String,
    detail: SegmentDetail?,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fade = tween<Color>(HlMotion.STANDARD, easing = HlMotion.emphasized)
    val rest by animateColorAsState(if (selected) c.content else c.contentSecondary, fade, label = "segmentText")
    Surface(
        onClick = onClick,
        modifier = modifier
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .height(HlSize.segment)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(HlShapes.control),
        scale = FocusStyle.scale(FocusStyle.Fill.Pill),
        colors = FocusStyle.fill(rest = Color.Transparent, restContent = rest),
        interactionSource = interaction,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = HlControl.tabPaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = Hl.type.entry, maxLines = 1, softWrap = false)
            when (detail) {
                null -> Unit
                SegmentDetail.Done -> {
                    Spacer(Modifier.width(HlSpace.xs))
                    // A white check, dark under focus, as on the watched button.
                    Icon(
                        HlIcons.Check,
                        contentDescription = null,
                        tint = if (focused) c.onFocus else c.content,
                        modifier = Modifier.size(HlSize.inlineIcon),
                    )
                }
                is SegmentDetail.Count -> {
                    Spacer(Modifier.width(HlSpace.xs))
                    val ink = FocusStyle.quietInk(focused, enabled = true, lit = false)
                    Text(detail.text, style = Hl.type.meta, color = ink, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Progress shown after an [HlSegmented] option's name: a check when [Done], else figures ("6 / 10"). */
sealed interface SegmentDetail {
    data object Done : SegmentDetail

    data class Count(val text: String) : SegmentDetail
}
