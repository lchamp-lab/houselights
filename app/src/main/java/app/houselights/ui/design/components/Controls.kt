package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.RowScroll

enum class Emphasis { Primary, Secondary }

/**
 * Pill button. Every button turns projector white when focused; the primary one is
 * brighter at rest so the main action reads first.
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
) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // A Surface rather than tv-material's Button, whose content keeps a minimum height of its own
    // at the top of the control: here the content is centred in exactly the control height.
    Surface(
        onClick = onClick,
        modifier = modifier.height(HlSize.control),
        shape = ClickableSurfaceDefaults.shape(HlShapes.control),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.content.copy(alpha = if (emphasis == Emphasis.Primary) HlAlpha.CONTROL_FILL_STRONG else HlAlpha.CONTROL_FILL),
            contentColor = c.content,
            focusedContainerColor = c.focus,
            focusedContentColor = c.onFocus,
            pressedContainerColor = c.focus,
            pressedContentColor = c.onFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
        interactionSource = interaction,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(
                start = if (icon != null) HlControl.buttonPaddingIcon else HlControl.buttonPaddingH,
                end = HlControl.buttonPaddingH,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(HlSize.icon))
                Spacer(Modifier.width(HlControl.iconGap))
            }
            Text(text, style = Hl.type.label, maxLines = 1, softWrap = false)
            if (detail != null) {
                Spacer(Modifier.width(HlSpace.s))
                // Secondary at rest: tertiary would fall to 3.66:1 on a primary button.
                Text(detail, style = Hl.type.meta, color = if (focused) c.onFocusSecondary else c.contentSecondary)
            }
        }
    }
}

/**
 * Round icon button; its label appears under it while focused, keeping the row quiet. The label
 * takes no room: it overflows under the button, so a row that has nothing below it (the home bar)
 * stays as tall as its controls, and a row that does reserves [HlLayout.iconLabelRoom]. Near the
 * side of the screen, the label slides along to stay inside the safe area; under a sheet, which
 * has the window, it hides. The icon carries the name for accessibility. [onArtwork] sets the
 * button and its label on a dark plate, so they read over any image.
 */
@Composable
fun HlIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onArtwork: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // A sheet opens a window of its own: the button keeps its focus under it, not its label.
    val shown = focused && LocalWindowInfo.current.isWindowFocused
    val labelAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(HlMotion.FAST), label = "label")
    val c = Hl.colors
    val restFill = if (onArtwork) c.background.copy(alpha = HlAlpha.PLATE) else c.content.copy(alpha = HlAlpha.CONTROL_FILL)
    Layout(
        modifier = modifier,
        content = {
            Surface(
                onClick = onClick,
                modifier = Modifier.size(HlSize.iconButton),
                shape = ClickableSurfaceDefaults.shape(HlShapes.avatar),
                scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = restFill,
                    contentColor = c.content,
                    focusedContainerColor = c.focus,
                    focusedContentColor = c.onFocus,
                    pressedContainerColor = c.focus,
                    pressedContentColor = c.onFocus,
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
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .graphicsLayer { alpha = labelAlpha }
                    .then(
                        if (onArtwork) {
                            Modifier
                                .background(c.background.copy(alpha = HlAlpha.PLATE), HlShapes.control)
                                .padding(horizontal = HlSpace.xs)
                        } else {
                            Modifier
                        },
                    ),
            )
        },
    ) { measurables, constraints ->
        val button = measurables[0].measure(constraints)
        val text = measurables[1].measure(Constraints(maxWidth = HlLayout.iconLabel.roundToPx()))
        val gap = HlSpace.xs.roundToPx()
        val safe = HlSpace.safeH.roundToPx()
        layout(button.width, button.height) {
            button.place(0, 0)
            val centred = (button.width - text.width) / 2
            val x = coordinates?.let { here ->
                val screen = here.findRootCoordinates()
                val left = screen.localPositionOf(here, Offset.Zero).x.roundToInt()
                centred.coerceAtMost(screen.size.width - safe - left - text.width).coerceAtLeast(safe - left)
            } ?: centred
            text.place(x, button.height + gap)
        }
    }
}

/**
 * A choice in a vertical list (sources, servers): a quiet panel, lifted with a projector-white
 * outline when focused. Its icon stands in line with the name and lights up with focus; [badges]
 * say what the choice brings, in the technical voice, under a hairline; a chevron shows on focus
 * that it leads on. [descriptionLines] keeps choices shown side by side the same height.
 */
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
                        tint = if (focused) c.tungsten else c.contentSecondary,
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
                Text(description, style = Hl.type.detail, color = c.contentSecondary, minLines = descriptionLines)
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
 * Exclusive options in one pill track: where you are (home navigation, seasons, NFO views) or
 * what is chosen (protocol, content type). The current option sits on the track's one white
 * pill; focus on another option is a grey pill, focus on the current one a ring around it. Focus
 * entering the track, from any side, lands on the current option, never on the nearest one; to
 * accessibility services the options are tabs, the current one selected. With [selectOnFocus],
 * moving onto an option selects it, as tabs over content do on TV. [scrollable] lets a long
 * track (seasons) scroll within the screen. [onArtwork] lays the track on a dark plate and its
 * options in projector white, so it reads over any image.
 */
@Composable
fun HlSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectOnFocus: Boolean = false,
    scrollable: Boolean = false,
    onArtwork: Boolean = false,
) {
    val density = LocalDensity.current
    val places = remember(options.size) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    var focusedIndex by remember { mutableIntStateOf(NONE) }
    // A long track (seasons) scrolls as one piece: the pill moves with the options.
    val scroll = rememberScrollState()
    val ringReach = HlSize.focusRingGap + HlSize.focusBorder
    val content = if (scrollable) Modifier.horizontalScroll(scroll).padding(horizontal = ringReach) else Modifier

    // Not clipped: the ring of a focused current option may reach past the track's edge.
    val well = if (onArtwork) Hl.colors.background.copy(alpha = HlAlpha.PLATE) else Hl.colors.content.copy(alpha = HlAlpha.WELL)
    val track = modifier
        .background(well, HlShapes.control)
        .padding(vertical = HlSpace.xxs, horizontal = if (scrollable) HlSpace.none else HlSpace.xxs)
    RowScroll {
        Box(track) {
            Box(content) {
                // One white pill, under the options, that slides to the option chosen.
                places[selected]?.let { (x, w) ->
                    val slide = tween<Float>(HlMotion.STANDARD, easing = HlMotion.emphasized)
                    val left by animateFloatAsState(x, slide, label = "pillX")
                    val width by animateFloatAsState(w, slide, label = "pillWidth")
                    val onCurrent = focusedIndex == selected
                    val grow by animateFloatAsState(if (onCurrent) HlFocus.CONTROL_SCALE else 1f, tween(HlMotion.FAST), label = "pillScale")
                    // Focus on the current option: a ring around the pill, travelling with it.
                    val ring by animateFloatAsState(if (onCurrent) 1f else 0f, tween(HlMotion.FAST), label = "pillRing")
                    val ringGap = with(density) { HlSize.focusRingGap.toPx() }
                    val ringWidth = with(density) { HlSize.focusBorder.toPx() }
                    val white = Hl.colors.focus
                    Box(
                        Modifier
                            .offset { IntOffset(left.roundToInt(), 0) }
                            .width(with(density) { width.toDp() })
                            .height(HlSize.segment)
                            .graphicsLayer { scaleX = grow; scaleY = grow }
                            .drawBehind {
                                if (ring > 0f) {
                                    val outset = ringGap + ringWidth / 2
                                    val ringSize = Size(size.width + outset * 2, size.height + outset * 2)
                                    drawRoundRect(
                                        color = white.copy(alpha = ring),
                                        topLeft = Offset(-outset, -outset),
                                        size = ringSize,
                                        cornerRadius = CornerRadius(ringSize.height / 2),
                                        style = Stroke(ringWidth),
                                    )
                                }
                            }
                            .background(white, HlShapes.control),
                    )
                }
                Row(Modifier.enterOn(requesters, selected).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                    options.forEachIndexed { i, label ->
                        SegmentItem(
                            label,
                            selected = i == selected,
                            onArtwork = onArtwork,
                            onClick = { onSelect(i) },
                            onFocused = { if (selectOnFocus) onSelect(i) },
                            modifier = Modifier
                                .focusRequester(requesters[i])
                                .onPlaced { places[i] = it.positionInParent().x to it.size.width.toFloat() },
                            onFocusChange = { has -> if (has) focusedIndex = i else if (focusedIndex == i) focusedIndex = NONE },
                        )
                    }
                }
            }
        }
    }
}

private const val NONE = -1

/**
 * A group that focus enters on the option at [current], from any side: where you are, not the
 * nearest option. With no current option, the nearest one.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.enterOn(requesters: List<FocusRequester>, current: Int): Modifier = this
    .focusProperties { enter = { requesters.getOrNull(current) ?: FocusRequester.Default } }
    .focusGroup()

/**
 * One option of a [HlSegmented] track; never used alone. In a track, projector white says where
 * you are: the current option is white whether or not it has focus. Moving along the track lights
 * the option under focus in grey until it is chosen; focus on the current option draws a ring
 * around it. (Everywhere else, focus is white; the track is the one place where white means
 * "current".)
 */
@Composable
private fun SegmentItem(
    text: String,
    selected: Boolean,
    onArtwork: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val c = Hl.colors
    val fade = tween<Color>(HlMotion.STANDARD, easing = HlMotion.emphasized)
    // On the plate over an image, grey would fall to 3.5:1: the options at rest are white there.
    val rest = if (onArtwork) c.content else c.contentSecondary
    val restContent by animateColorAsState(if (selected) c.onFocus else rest, fade, label = "segmentText")
    val focusedContent by animateColorAsState(if (selected) c.onFocus else c.content, fade, label = "segmentFocusedText")
    // The white of the current option is the track's pill, under the options.
    val currentFill = Color.Transparent
    Surface(
        onClick = onClick,
        modifier = modifier
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .height(HlSize.segment)
            .onFocusChanged {
                onFocusChange(it.isFocused)
                if (it.isFocused) onFocused()
            },
        shape = ClickableSurfaceDefaults.shape(HlShapes.control),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) currentFill else Color.Transparent,
            contentColor = restContent,
            focusedContainerColor = if (selected) currentFill else c.content.copy(alpha = HlAlpha.FOCUS_QUIET),
            focusedContentColor = focusedContent,
            pressedContainerColor = if (selected) currentFill else c.content.copy(alpha = HlAlpha.FOCUS_QUIET),
            pressedContentColor = focusedContent,
        ),
    ) {
        Text(
            text,
            style = Hl.type.label,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = HlControl.tabPaddingH),
        )
    }
}
