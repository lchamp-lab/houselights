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
            Modifier.padding(
                horizontal = if (icon != null && text.isEmpty()) HlControl.tabPaddingIcon else HlControl.tabPaddingH,
                vertical = HlControl.tabPaddingV,
            ),
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
                    .offset(y = -HlSize.footlightHeight)
                    .size(width = HlSize.footlightWidth, height = HlSize.footlightHeight)
                    .clip(HlShapes.control)
                    .background(c.tungsten)
            )
        }
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
    HlPanelButton(onClick, modifier) { focused ->
        Row(Modifier.padding(HlSpace.l), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                HlIconDisc(icon, lit = focused)
                Spacer(Modifier.width(HlSpace.l))
            }
            Column {
                Text(title, style = Hl.type.lead, color = Hl.colors.content)
                if (description != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(description, style = Hl.type.caption, color = Hl.colors.contentSecondary)
                }
            }
        }
    }
}

/**
 * Exclusive options in one pill track (content type, protocol, display mode). With
 * [selectOnFocus], moving onto an option selects it, as tabs over content do on TV.
 */
@Composable
fun HlSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectOnFocus: Boolean = false,
) {
    Row(
        modifier
            .clip(HlShapes.control)
            .background(Hl.colors.content.copy(alpha = HlAlpha.WELL))
            .padding(HlSpace.xxs),
        horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs),
    ) {
        options.forEachIndexed { i, label ->
            HlTab(label, selected = i == selected, onClick = { onSelect(i) }, onFocused = { if (selectOnFocus) onSelect(i) })
        }
    }
}
