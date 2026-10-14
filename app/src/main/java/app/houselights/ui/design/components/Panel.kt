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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlInset
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
    padding: Dp = HlInset.card,
    shape: Shape = HlShapes.panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Hl.colors
    Column(
        modifier
            .clip(shape)
            .background(if (level == PanelLevel.Raised) c.surfaceRaised else c.surface.copy(alpha = HlAlpha.QUIET_SURFACE))
            .border(HlSize.hairline, if (lit) c.tungsten.copy(alpha = HlAlpha.BORDER) else c.outline.copy(alpha = HlAlpha.BORDER), shape)
            .padding(padding),
        content = content,
    )
}

/**
 * A panel that can be chosen (list entries, results, source cards): quiet at rest, raised with a
 * projector-white outline when focused. [lit] edges it in tungsten at rest: the choice in use.
 * Not [enabled], it sits in a faint well with tertiary content (what [content] draws in
 * `LocalContentColor`), keeps focus and ignores OK. [content] receives the focus state.
 */
@Composable
fun HlPanelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    lit: Boolean = false,
    enabled: Boolean = true,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline = Border(BorderStroke(HlSize.focusBorder, c.focus), shape = HlShapes.panel)
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(HlShapes.panel),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.PANEL_SCALE, focusedDisabledScale = HlFocus.PANEL_SCALE),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(HlSize.hairline, (if (lit) c.tungsten else c.outline).copy(alpha = HlAlpha.BORDER)),
                shape = HlShapes.panel,
            ),
            focusedBorder = outline,
            disabledBorder = Border(BorderStroke(HlSize.hairline, c.outline.copy(alpha = HlAlpha.BORDER)), shape = HlShapes.panel),
            focusedDisabledBorder = outline,
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface.copy(alpha = HlAlpha.QUIET_SURFACE),
            contentColor = c.content,
            focusedContainerColor = c.surfaceRaised,
            focusedContentColor = c.content,
            pressedContainerColor = c.surfaceRaised,
            pressedContentColor = c.content,
            disabledContainerColor = c.content.copy(alpha = HlAlpha.WELL),
            disabledContentColor = c.contentTertiary,
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
 * facts are left out, and so are those past the room, whole and from the last: put the ones that
 * matter first. A single fact too long for the line ends in an ellipsis.
 */
@Composable
fun FactRow(
    facts: List<String?>,
    modifier: Modifier = Modifier,
    style: TextStyle = Hl.type.meta,
    color: Color = Hl.colors.contentSecondary,
) {
    OneLine(modifier) {
        facts.filterNotNull().filter { it.isNotBlank() }.forEach {
            Text(it, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * What a screen says when it has nothing to show or could not load: a sentence that says what
 * happened, and the action that fixes it, focused. [secondaryAction] is a quieter way out beside it
 * (where to look more closely). With no action, [backHint] says that Back is the way out.
 */
@Composable
fun HlEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    action: String? = null,
    actionIcon: ImageVector? = null,
    onAction: () -> Unit = {},
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
    backHint: Boolean = false,
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
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                HlButton(action, onAction, Modifier.focusRequester(focus), icon = actionIcon, emphasis = Emphasis.Primary)
                if (secondaryAction != null) HlButton(secondaryAction, onSecondaryAction)
            }
        } else if (backHint) {
            Spacer(Modifier.height(HlSpace.l))
            BackHint()
        }
    }
}

/** The state of something the app depends on; [Attention] when something went wrong with it. */
enum class StatusState { On, Off, Pending, Attention }

/**
 * A state in words with its light: a tungsten dot when on or calling for attention, an empty one
 * when off. [detail] says why, under the words and aligned with them.
 */
@Composable
fun HlStatus(state: StatusState, text: String, modifier: Modifier = Modifier, detail: String? = null) {
    val c = Hl.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(state)
            Spacer(Modifier.width(HlSpace.xs))
            val strong = state == StatusState.On || state == StatusState.Attention
            Text(text, style = Hl.type.meta, color = if (strong) c.content else c.contentSecondary)
        }
        if (detail != null) {
            Spacer(Modifier.height(HlSpace.xxs))
            Text(detail, style = Hl.type.detail, color = c.contentSecondary, modifier = Modifier.padding(start = HlSize.dot + HlSpace.xs))
        }
    }
}

/**
 * The light of a [StatusState]: tungsten when on or calling for attention, quiet while pending,
 * an empty ring when off. Also where a busy button's icon was, and before a field's error.
 */
@Composable
internal fun StatusDot(state: StatusState, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Box(
        modifier
            .size(HlSize.dot)
            .clip(HlShapes.avatar)
            .then(
                when (state) {
                    StatusState.On, StatusState.Attention -> Modifier.background(c.tungsten)
                    StatusState.Pending -> Modifier.background(c.contentDecorative)
                    StatusState.Off -> Modifier.border(HlSize.hairline, c.contentTertiary, HlShapes.avatar)
                }
            )
    )
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

/** A line of a meter's legend: its colour, what it is, how much. */
@Composable
fun MeterLegend(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(HlSize.dot).clip(HlShapes.avatar).background(color))
        Spacer(Modifier.width(HlSpace.s))
        Text(label, style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = Modifier.weight(1f))
        Text(value, style = Hl.type.meta, color = Hl.colors.content)
    }
}
