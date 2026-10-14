package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

/** How far a panel stands out from the room. */
enum class PanelLevel {
    /** On the room itself: lists, status, information. */
    Quiet,

    /** Above other content: dialogs and menus. */
    Raised,
}

/**
 * The one panel of the design system: a rounded, quietly lit area with a hairline edge. [lit]
 * draws the edge in tungsten, for something that is on or chosen.
 */
@Composable
fun HlPanel(
    modifier: Modifier = Modifier,
    level: PanelLevel = PanelLevel.Quiet,
    lit: Boolean = false,
    padding: Dp = HlSpace.l,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Hl.colors
    Column(
        modifier
            .clip(HlShapes.panel)
            .background(if (level == PanelLevel.Raised) c.surfaceRaised else c.surface.copy(alpha = HlAlpha.QUIET_SURFACE))
            .border(HlSize.hairline, if (lit) c.tungsten.copy(alpha = HlAlpha.BORDER) else c.outline.copy(alpha = HlAlpha.BORDER), HlShapes.panel)
            .padding(padding),
        content = content,
    )
}

/**
 * A panel that can be chosen (list entries, results, source cards): quiet at rest, raised with a
 * projector-white outline when focused. [content] receives the focus state.
 */
@Composable
fun HlPanelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    content: @Composable (focused: Boolean) -> Unit,
) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(HlShapes.panel),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(HlSize.hairline, c.outline.copy(alpha = HlAlpha.BORDER)), shape = HlShapes.panel),
            focusedBorder = Border(BorderStroke(HlSize.focusBorder, c.focus), shape = HlShapes.panel),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface.copy(alpha = HlAlpha.QUIET_SURFACE),
            contentColor = c.content,
            focusedContainerColor = c.surfaceRaised,
            focusedContentColor = c.content,
            pressedContainerColor = c.surfaceRaised,
            pressedContentColor = c.content,
        ),
        interactionSource = interaction,
    ) {
        content(focused)
    }
}

/** An icon in a disc; [lit] in tungsten for the thing that is on or focused. */
@Composable
fun HlIconDisc(icon: ImageVector, modifier: Modifier = Modifier, lit: Boolean = false) {
    val c = Hl.colors
    Box(
        modifier
            .size(HlSize.disc)
            .clip(HlShapes.avatar)
            .background(if (lit) c.tungsten.copy(alpha = HlAlpha.LIT_DISC) else c.content.copy(alpha = HlAlpha.WELL)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (lit) c.tungsten else c.contentSecondary, modifier = Modifier.size(HlSize.discIcon))
    }
}

/** A hairline that separates groups. */
@Composable
fun HlDivider(modifier: Modifier = Modifier, vertical: Boolean = false) {
    val color = Hl.colors.outline.copy(alpha = HlAlpha.DIVIDER)
    if (vertical) {
        Box(modifier.width(HlSize.hairline).fillMaxHeight().background(color))
    } else {
        Box(modifier.fillMaxWidth().height(HlSize.hairline).background(color))
    }
}

/**
 * Facts on one line, separated by space and never by punctuation: "2026   2 h 02   12". Empty
 * facts are left out.
 */
@Composable
fun FactRow(
    facts: List<String?>,
    modifier: Modifier = Modifier,
    style: TextStyle = Hl.type.meta,
    color: Color = Hl.colors.contentSecondary,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HlSpace.fact), verticalAlignment = Alignment.CenterVertically) {
        facts.filterNotNull().filter { it.isNotBlank() }.forEach { Text(it, style = style, color = color, maxLines = 1) }
    }
}

/**
 * What a screen says when it has nothing to show or could not load: a sentence that says what
 * happened, and the action that fixes it, focused.
 */
@Composable
fun HlEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    action: String? = null,
    actionIcon: ImageVector? = null,
    onAction: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(action != null) { if (action != null) runCatching { focus.requestFocus() } }
    Column(modifier.widthIn(max = HlLayout.readable)) {
        if (title != null) {
            Text(title, style = Hl.type.lead, color = Hl.colors.content)
            Spacer(Modifier.height(HlSpace.xs))
        }
        Text(message, style = Hl.type.body, color = Hl.colors.contentSecondary)
        if (action != null) {
            Spacer(Modifier.height(HlSpace.l))
            HlButton(action, onAction, Modifier.focusRequester(focus), icon = actionIcon, emphasis = Emphasis.Primary)
        }
    }
}

/** The state of something the app depends on. */
enum class StatusState { On, Off, Pending }

/** A state in words with its light: a tungsten dot when on, an empty one when off. */
@Composable
fun HlStatus(state: StatusState, text: String, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(HlSize.dot)
                .clip(HlShapes.avatar)
                .then(
                    when (state) {
                        StatusState.On -> Modifier.background(c.tungsten)
                        StatusState.Pending -> Modifier.background(c.contentDecorative)
                        StatusState.Off -> Modifier.border(HlSize.hairline, c.contentTertiary, HlShapes.avatar)
                    }
                )
        )
        Spacer(Modifier.width(HlSpace.xs))
        Text(text, style = Hl.type.meta, color = if (state == StatusState.On) c.content else c.contentSecondary)
    }
}

/** One part of a [HlMeter]. */
data class MeterSegment(val fraction: Float, val color: Color)

/**
 * A segmented gauge: how a capacity is shared. Segments are drawn left to right on a faint
 * track; what is left of the track is free.
 */
@Composable
fun HlMeter(segments: List<MeterSegment>, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(HlSize.meter)
            .clip(HlShapes.control)
            .background(c.content.copy(alpha = HlAlpha.WELL)),
    ) {
        segments.filter { it.fraction > 0f }.forEach { s ->
            // A sliver stays visible however small the part.
            Box(Modifier.fillMaxHeight().weight(s.fraction.coerceAtLeast(0.006f)).background(s.color))
        }
        val free = 1f - segments.sumOf { it.fraction.toDouble() }.toFloat()
        if (free > 0f) Spacer(Modifier.weight(free))
    }
}
