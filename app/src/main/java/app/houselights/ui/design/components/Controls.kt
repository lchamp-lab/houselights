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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.focusRestorer
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
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlFocus
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
                Text(detail, style = Hl.type.meta, color = LocalContentColor.current.copy(alpha = HlAlpha.CONTROL_DETAIL))
            }
        }
    }
}

/** Round icon button; its label appears under it while focused, keeping the row quiet. */
@Composable
fun HlIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(HlMotion.FAST), label = "label")
    val c = Hl.colors
    // The column is as wide as the button: the label overflows on both sides instead of
    // pushing the neighbours away.
    Column(modifier.width(HlSize.iconButton), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(HlSize.iconButton),
            shape = ClickableSurfaceDefaults.shape(HlShapes.avatar),
            scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = c.content.copy(alpha = HlAlpha.CONTROL_FILL),
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
        Spacer(Modifier.height(HlSpace.xs))
        Text(
            label,
            style = Hl.type.caption,
            color = c.content,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(unbounded = true).alpha(labelAlpha),
        )
    }
}

/**
 * A choice in a vertical list (sources, options): a quiet panel, lifted with a projector-white
 * outline when focused. The optional icon sits in a disc that lights up with focus.
 */
@Composable
fun HlListItem(
    title: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    HlPanelButton(onClick, modifier.fillMaxWidth()) { focused ->
        Row(Modifier.padding(HlSpace.l), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                HlIconDisc(icon, lit = focused)
                Spacer(Modifier.width(HlSpace.l))
            }
            Column {
                Text(title, style = Hl.type.lead, color = Hl.colors.content)
                if (description != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(description, style = Hl.type.detail, color = Hl.colors.contentSecondary)
                }
            }
        }
    }
}

/**
 * Exclusive options in one pill track: where you are (home navigation, seasons, NFO views) or
 * what is chosen (protocol, content type). The selected option is a softly lit pill; focus turns
 * an option projector white, like every control. With [selectOnFocus], moving onto an option
 * selects it, as tabs over content do on TV. [scrollable] lets a long track (seasons) scroll
 * within the screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HlSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectOnFocus: Boolean = false,
    scrollable: Boolean = false,
) {
    val density = LocalDensity.current
    val places = remember(options.size) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    var focusedIndex by remember { mutableIntStateOf(NONE) }
    // A long track (seasons) scrolls as one piece: the pill moves with the options.
    val scroll = rememberScrollState()
    val ringReach = HlSize.focusRingGap + HlSize.focusBorder
    val content = if (scrollable) Modifier.horizontalScroll(scroll).padding(horizontal = ringReach) else Modifier

    // Not clipped: the ring of a focused current option may reach past the track's edge.
    val track = modifier
        .background(Hl.colors.content.copy(alpha = HlAlpha.WELL), HlShapes.control)
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
                Row(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                    options.forEachIndexed { i, label ->
                        SegmentItem(
                            label,
                            selected = i == selected,
                            onClick = { onSelect(i) },
                            onFocused = { if (selectOnFocus) onSelect(i) },
                            modifier = Modifier.onPlaced { places[i] = it.positionInParent().x to it.size.width.toFloat() },
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
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val c = Hl.colors
    val fade = tween<Color>(HlMotion.STANDARD, easing = HlMotion.emphasized)
    val restContent by animateColorAsState(if (selected) c.onFocus else c.contentSecondary, fade, label = "segmentText")
    val focusedContent by animateColorAsState(if (selected) c.onFocus else c.content, fade, label = "segmentFocusedText")
    // The white of the current option is the track's pill, under the options.
    val currentFill = Color.Transparent
    Surface(
        onClick = onClick,
        modifier = modifier
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
