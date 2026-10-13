package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

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
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(HlShapes.control),
        colors = ButtonDefaults.colors(
            containerColor = c.content.copy(alpha = if (emphasis == Emphasis.Primary) 0.22f else 0.09f),
            contentColor = c.content,
            focusedContainerColor = c.focus,
            focusedContentColor = c.onFocus,
            pressedContainerColor = c.focus,
            pressedContentColor = c.onFocus,
        ),
        scale = ButtonDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
        contentPadding = PaddingValues(start = if (icon != null) 20.dp else 24.dp, end = 24.dp, top = 11.dp, bottom = 11.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(HlSize.icon))
            Spacer(Modifier.width(HlSpace.xs + 2.dp))
        }
        Text(text, style = Hl.type.label, maxLines = 1, softWrap = false)
        if (detail != null) {
            Spacer(Modifier.width(HlSpace.s))
            Text(detail, style = Hl.type.meta, color = LocalContentColor.current.copy(alpha = 0.62f))
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
                containerColor = c.content.copy(alpha = 0.09f),
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
 * Text tab and toggle (navigation, sorting, seasons). Selected shows a short tungsten
 * line, like a lit footlight; focus fills it with projector white.
 */
@Composable
fun HlTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onFocused: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val c = Hl.colors
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(HlShapes.control),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) c.content else c.contentSecondary,
            focusedContainerColor = c.focus,
            focusedContentColor = c.onFocus,
            pressedContainerColor = c.focus,
            pressedContentColor = c.onFocus,
        ),
        interactionSource = interaction,
    ) {
        Row(
            Modifier.padding(horizontal = if (icon != null && text.isEmpty()) 10.dp else 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = text.ifEmpty { null }, modifier = Modifier.size(HlSize.icon))
                if (text.isNotEmpty()) Spacer(Modifier.width(HlSpace.xs))
            }
            if (text.isNotEmpty()) Text(text, style = Hl.type.label)
        }
        if (selected && !focused) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-2).dp)
                    .size(width = 16.dp, height = 2.dp)
                    .clip(HlShapes.control)
                    .background(c.tungsten)
            )
        }
    }
}

/**
 * A choice in a vertical list (sources, options): quiet at rest, lifted onto a panel with
 * a projector-white outline when focused. The optional icon sits in a lit disc.
 */
@Composable
fun HlListItem(
    title: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(HlShapes.panel),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(HlSize.hairline, c.outline.copy(alpha = 0.6f)), shape = HlShapes.panel),
            focusedBorder = Border(BorderStroke(HlSize.focusBorder, c.focus), shape = HlShapes.panel),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface.copy(alpha = 0.72f),
            contentColor = c.content,
            focusedContainerColor = c.surfaceRaised,
            focusedContentColor = c.content,
            pressedContainerColor = c.surfaceRaised,
            pressedContentColor = c.content,
        ),
        interactionSource = interaction,
    ) {
        Row(Modifier.padding(horizontal = HlSpace.l, vertical = HlSpace.l), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(HlShapes.avatar)
                        .background(if (focused) c.tungsten.copy(alpha = 0.18f) else c.content.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = if (focused) c.tungsten else c.contentSecondary, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(HlSpace.l))
            }
            Column {
                Text(title, style = Hl.type.title)
                if (description != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(description, style = Hl.type.bodySmall, color = c.contentSecondary)
                }
            }
        }
    }
}

/** Exclusive options in one pill track (content type, protocol, display mode). */
@Composable
fun HlSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(HlShapes.control)
            .background(Hl.colors.content.copy(alpha = 0.06f))
            .padding(HlSpace.xxs),
        horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs),
    ) {
        options.forEachIndexed { i, label -> HlTab(label, selected = i == selected, onClick = { onSelect(i) }) }
    }
}
