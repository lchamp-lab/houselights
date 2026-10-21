package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.Ground
import app.houselights.ui.design.LocalGround
import app.houselights.ui.design.edge
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.design.tryRequestFocus

/** How far a panel stands out from the room. */
enum class PanelLevel {
    /** On the room itself: lists, status, information. */
    Quiet,

    /** Above other content: menus, on glass. */
    Raised,
}

/** The design system's panel: rounded, quietly lit, hairline edge; [lit] draws the edge in ember. */
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
    val raised = level == PanelLevel.Raised
    Column(
        modifier
            .panel(
                shape,
                fill = if (raised) c.glass else groundStepFill(),
                edge = if (lit) BorderStroke(HlSize.hairline, c.emberLight()) else BorderStroke(HlSize.hairline, c.edge),
            )
            .padding(padding),
    ) {
        if (raised) CompositionLocalProvider(LocalGround provides Ground.Glass) { content() } else content()
    }
}

/** A panel's surface, shared with [HlScrollArea]: [fill] clipped to [shape], [edge] around it. */
internal fun Modifier.panel(shape: Shape, fill: Color, edge: BorderStroke): Modifier =
    clip(shape).background(fill).border(edge, shape)

/** A selectable panel (list entries, source cards): framed on focus; [lit] edges it in ember at rest, [level] raises it on glass. */
@Composable
fun HlPanelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    lit: Boolean = false,
    enabled: Boolean = true,
    level: PanelLevel = PanelLevel.Quiet,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val c = Hl.colors
    val raised = level == PanelLevel.Raised
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.frame(
            rest = if (raised) c.glass else groundStepFill(),
            restInk = c.content,
            edge = BorderStroke(HlSize.hairline, if (lit && enabled) c.emberLight() else SolidColor(c.edge)),
            disabled = c.content.copy(alpha = HlAlpha.FILL_FAINT),
            disabledInk = c.contentTertiary,
        ),
        shape = HlShapes.panel,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        enabled = enabled,
    ) { focused ->
        if (raised) CompositionLocalProvider(LocalGround provides Ground.Glass) { content(focused) } else content(focused)
    }
}

/**
 * A full-width selectable row: its words on what holds it, white-filled on focus; disabled, it sits in its
 * [disabled] well and keeps focus, ringed.
 */
@Composable
internal fun HlRowSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rest: Color = Color.Transparent,
    restContent: Color = Hl.colors.content,
    enabled: Boolean = true,
    disabled: Color = rest,
    disabledContent: Color = restContent,
    shape: Shape = HlShapes.row,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable (focused: Boolean) -> Unit,
) {
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.fill(rest = rest, restInk = restContent, disabled = disabled, disabledInk = disabledContent),
        shape = shape,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
    ) { lit -> content(lit) }
}

/** A hairline that separates groups, a rule like an empty frame's. */
@Composable
fun HlDivider(modifier: Modifier = Modifier, vertical: Boolean = false) {
    val color = Hl.colors.contentDecorative
    if (vertical) {
        Box(modifier.width(HlSize.hairline).fillMaxHeight().background(color))
    } else {
        Box(modifier.fillMaxWidth().height(HlSize.hairline).background(color))
    }
}

/** Facts on one line, space-separated ("2026   2 h 02   12"); those that do not fit drop whole from the end. */
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

/** Printed [badges] (a protocol), then [facts], on one line a fact's space apart. */
@Composable
internal fun BadgedFactRow(badges: List<String>, facts: List<String?>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HlSpace.fact)) {
        badges.forEach { Badge(it) }
        if (facts.any { !it.isNullOrBlank() }) FactRow(facts, Modifier.weight(1f, fill = false))
    }
}

/**
 * An [Intertitle] for a screen with nothing to show or a failure: [title], [message] and a focused [action]
 * (labelled [busy] while it runs), or [backHint] when nothing fixes it. Pass [focusOnAppear] false for a
 * specimen among others (the catalogue).
 */
@Composable
fun HlEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    attention: Boolean = false,
    action: String? = null,
    actionIcon: ImageVector? = null,
    onAction: () -> Unit = {},
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
    backHint: Boolean = false,
    busy: String? = null,
    focusOnAppear: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(action != null) { if (action != null && focusOnAppear) focus.tryRequestFocus() }
    Intertitle(
        title,
        modifier,
        message = message,
        attention = attention,
        actions = when {
            action != null -> ({
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    HlButton(
                        action,
                        onAction,
                        Modifier.focusRequester(focus),
                        icon = actionIcon,
                        busy = busy != null,
                        busyLabel = busy ?: action,
                    )
                    if (secondaryAction != null) HlButton(secondaryAction, onSecondaryAction)
                }
            })
            backHint -> ({ BackHint() })
            else -> null
        },
    )
}

/**
 * The state of something the app depends on: [On] in use or chosen (ember), [Done] an established fact
 * (white), [Off] absent, [Pending] in progress, [Attention] something went wrong.
 */
enum class StatusState { On, Done, Off, Pending, Attention }

/** A state in words with its light ([StatusLight]); [detail] explains why, below and aligned with the words. */
@Composable
fun HlStatus(state: StatusState, text: String, modifier: Modifier = Modifier, detail: String? = null) {
    val c = Hl.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLight(state)
            Spacer(Modifier.width(HlSpace.xs))
            val strong = state != StatusState.Off && state != StatusState.Pending
            Text(text, style = Hl.type.meta, color = if (strong) c.content else c.contentSecondary)
        }
        if (detail != null) {
            Spacer(Modifier.height(HlSpace.xxs))
            Text(
                detail,
                style = Hl.type.caption,
                color = c.contentSecondary,
                modifier = Modifier.padding(start = HlSize.statusLight + HlSpace.xs),
            )
        }
    }
}

/**
 * The light of a [StatusState] in a fixed-width box: dots for on, done and pending, a ring for off, and a
 * circled exclamation mark for attention, so it differs from on without colour. [ink] overrides colours.
 */
@Composable
internal fun StatusLight(state: StatusState, modifier: Modifier = Modifier, ink: Color? = null) {
    val c = Hl.colors
    Box(modifier.size(HlSize.statusLight), contentAlignment = Alignment.Center) {
        if (state == StatusState.Attention) {
            Icon(HlIcons.Attention, contentDescription = null, tint = ink ?: c.ember, modifier = Modifier.size(HlSize.statusLight))
            return@Box
        }
        // Dots are the only round things.
        val dot = Modifier.size(HlSize.dot).clip(CircleShape)
        Box(
            when (state) {
                StatusState.On -> dot.background(ink ?: c.ember)
                StatusState.Done -> dot.background(ink ?: c.content)
                StatusState.Pending -> dot.background(ink ?: c.contentTertiary)
                else -> dot.border(HlSize.hairline, ink ?: c.contentTertiary, CircleShape)
            }
        )
    }
}

/**
 * An attention note: the attention light, then [text] in ember; screens never set ember themselves. On a
 * [focused] plate the words take focus's ink and the light stays lit, raspberry.
 */
@Composable
fun AttentionNote(text: String, modifier: Modifier = Modifier, style: TextStyle = Hl.type.caption, focused: Boolean = false) {
    Row(modifier) {
        // As tall as the first line, so the light sits beside it.
        val line = with(LocalDensity.current) { style.lineHeight.toDp() }
        Box(Modifier.height(line), contentAlignment = Alignment.Center) {
            StatusLight(StatusState.Attention, ink = FocusStyle.emberMark(focused))
        }
        Spacer(Modifier.width(HlSpace.xs))
        Text(text, style = style, color = FocusStyle.ink(focused, rest = Hl.colors.ember))
    }
}

/** [HlMeter] part colours by rank; a share of capacity is not a state, so a meter never lights. */
enum class MeterInk { Primary, Secondary, Tertiary }

@Composable
private fun MeterInk.color(): Color = when (this) {
    MeterInk.Primary -> Hl.colors.content
    MeterInk.Secondary -> Hl.colors.contentSecondary
    MeterInk.Tertiary -> Hl.colors.contentTertiary
}

/** One part of a [HlMeter]. */
data class MeterSegment(val fraction: Float, val ink: MeterInk)

/** A segmented gauge of how a capacity is shared; the rest of the track is free. */
@Composable
fun HlMeter(segments: List<MeterSegment>, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(HlSize.meter)
            .clip(HlShapes.badge)
            .background(c.content.copy(alpha = HlAlpha.FILL_FAINT)),
        horizontalArrangement = Arrangement.spacedBy(HlSize.meterGap),
    ) {
        segments.filter { it.fraction > 0f }.forEach { s ->
            // A sliver stays visible however small the part.
            Box(Modifier.fillMaxHeight().weight(s.fraction.coerceAtLeast(0.006f)).background(s.ink.color()))
        }
        val free = 1f - segments.sumOf { it.fraction.toDouble() }.toFloat()
        if (free > 0f) Spacer(Modifier.weight(free))
    }
}

/** A meter legend line: colour dot, label column, then the value in tabular figures. */
@Composable
fun MeterLegend(label: String, value: String, ink: MeterInk, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        // Dots are the only round things.
        Box(Modifier.align(Alignment.CenterVertically).size(HlSize.dot).clip(CircleShape).background(ink.color()))
        Spacer(Modifier.width(HlSpace.s))
        Text(
            label,
            style = Hl.type.caption,
            color = Hl.colors.contentSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(HlLayout.definitionLabel).alignByBaseline(),
        )
        Text(value, style = Hl.type.meta, color = Hl.colors.content, modifier = Modifier.alignByBaseline())
    }
}
