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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
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

    /** Above other content: dialogs and menus. */
    Raised,
}

/**
 * The one panel of the design system: a rounded, quietly lit area with a hairline edge. [lit]
 * draws the edge in ember, for something that is on or chosen.
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
    val raised = level == PanelLevel.Raised
    Column(
        modifier
            .panel(
                shape,
                fill = if (raised) c.surfaceRaised else groundStepFill(),
                edge = if (lit) BorderStroke(HlSize.hairline, c.emberLight()) else BorderStroke(HlSize.hairline, c.edge),
            )
            .padding(padding),
    ) {
        if (raised) CompositionLocalProvider(LocalGround provides Ground.Glass) { content() } else content()
    }
}

/**
 * A panel's surface: [fill] clipped to [shape], [edge] drawn around it. The one recipe, so an area
 * to read on its quiet panel ([HlScrollArea]) is the panel itself.
 */
internal fun Modifier.panel(shape: Shape, fill: Color, edge: BorderStroke): Modifier =
    clip(shape).background(fill).border(edge, shape)

/**
 * A panel that can be chosen (list entries, results, source cards): quiet at rest, raised with a
 * projector-white outline when focused. [lit] edges it in ember at rest: the choice in use.
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
    val edge = Border(BorderStroke(HlSize.hairline, c.edge), shape = HlShapes.panel)
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(HlShapes.panel),
        scale = FocusStyle.scale(Lift.Panel),
        border = FocusStyle.lift(
            HlShapes.panel,
            rest = if (lit) Border(BorderStroke(HlSize.hairline, c.emberLight()), shape = HlShapes.panel) else edge,
            disabled = edge,
        ),
        colors = FocusStyle.still(
            rest = groundStepFill(),
            restContent = c.content,
            disabled = c.content.copy(alpha = HlAlpha.FILL_FAINT),
            disabledContent = c.contentTertiary,
        ),
        interactionSource = interaction,
    ) {
        content(focused)
    }
}

/**
 * A row that can be chosen, as wide as its column (a setting, a navigation entry, a menu option, a
 * fact of the technical sheet): the row's shape, and with focus the white fill, without growing
 * ([FocusStyle.Fill.Row]). [rest] and [restContent] at rest; not [enabled], it keeps focus,
 * outlined, and its words go [disabledContent]. What sets one row apart (its height, what it says
 * to accessibility services) goes in [modifier]. [content] receives the focus state.
 */
@Composable
internal fun HlRowSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rest: Color = Color.Transparent,
    restContent: Color = Hl.colors.content,
    enabled: Boolean = true,
    disabledContent: Color = restContent,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable (focused: Boolean) -> Unit,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(HlShapes.row),
        scale = FocusStyle.scale(FocusStyle.Fill.Row),
        border = FocusStyle.disabledRing(HlShapes.row),
        colors = FocusStyle.fill(rest = rest, restContent = restContent, disabledContent = disabledContent),
        interactionSource = interactionSource,
    ) {
        content(focused)
    }
}

/** An icon in a disc; [lit] in ember while what it stands for is on (in use, chosen), never for focus. */
@Composable
fun HlIconDisc(icon: ImageVector, modifier: Modifier = Modifier, lit: Boolean = false) {
    val c = Hl.colors
    Box(
        modifier
            .size(HlSize.disc)
            .clip(HlShapes.avatar)
            .then(
                if (lit) {
                    Modifier.background(c.emberLight(HlAlpha.FILL_STRONG))
                } else {
                    Modifier.background(c.content.copy(alpha = HlAlpha.FILL_FAINT))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (lit) c.ember else c.contentSecondary, modifier = Modifier.size(HlSize.discIcon))
    }
}

/** A hairline that separates groups. */
@Composable
fun HlDivider(modifier: Modifier = Modifier, vertical: Boolean = false) {
    val color = Hl.colors.edge
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
 * What a screen says when it has nothing to show or could not load, as an intertitle: what
 * happened ([title], in the voice of the poster), what to check ([message]), and the action that
 * fixes it, focused; [secondaryAction] is a quieter way out beside it (where to look more closely).
 * A failure calls for [attention]: its rule is ember. With no action, [backHint] says that Back
 * is the way out. While the action is at work (trying again), [busy] says so in its place: the
 * intertitle stays, and focus with it. It takes the place of what the screen would show, from the
 * top of that place. A specimen among others (the catalogue) leaves focus where it is ([focusOnAppear]).
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
                        emphasis = Emphasis.Primary,
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
 * The state of something the app depends on. [On]: in use or your choice, the ember light.
 * [Done]: a fact that holds (a file read, a match found by the app), projector white, like a title
 * watched. [Off]: not there or not yet. [Pending]: at work. [Attention]: something went wrong.
 */
enum class StatusState { On, Done, Off, Pending, Attention }

/**
 * A state in words with its light (see [StatusLight]). [detail] says why, under the words and
 * aligned with them.
 */
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
 * The light of a [StatusState], in a box of its own width so words line up after any of them: an
 * ember dot when on, a white one when done, a quiet one while pending, an empty ring when off,
 * and for attention the circled exclamation mark in ember: never the same mark as on, so the two
 * are told apart without their colour. Also where a busy button's icon was, and before an error.
 * [ink] replaces every colour (a light on a focused row takes the row's ink).
 */
@Composable
internal fun StatusLight(state: StatusState, modifier: Modifier = Modifier, ink: Color? = null) {
    val c = Hl.colors
    Box(modifier.size(HlSize.statusLight), contentAlignment = Alignment.Center) {
        if (state == StatusState.Attention) {
            Icon(HlIcons.Attention, contentDescription = null, tint = ink ?: c.ember, modifier = Modifier.size(HlSize.statusLight))
            return@Box
        }
        val dot = Modifier.size(HlSize.dot).clip(HlShapes.avatar)
        Box(
            when (state) {
                StatusState.On -> dot.background(ink ?: c.ember)
                StatusState.Done -> dot.background(ink ?: c.content)
                StatusState.Pending -> dot.background(ink ?: c.contentTertiary)
                else -> dot.border(HlSize.hairline, ink ?: c.contentTertiary, HlShapes.avatar)
            }
        )
    }
}

/**
 * What needs attention, beside what it concerns (a folder that could not be read, images that
 * cannot be fetched): the attention light by the first line, then the words in ember, as under
 * a field. A screen never sets ember itself: it says attention through this, or [HlStatus].
 */
@Composable
fun AttentionNote(text: String, modifier: Modifier = Modifier, style: TextStyle = Hl.type.caption) {
    Row(modifier) {
        // As tall as the first line, so the light sits beside it.
        val line = with(LocalDensity.current) { style.lineHeight.toDp() }
        Box(Modifier.height(line), contentAlignment = Alignment.Center) { StatusLight(StatusState.Attention) }
        Spacer(Modifier.width(HlSpace.xs))
        Text(text, style = style, color = Hl.colors.ember)
    }
}

/**
 * The ink of a part of a [HlMeter], by rank: projector white, then the two greys of reading. A
 * share of a capacity is not a state, so a meter never lights: parts are told apart by their
 * order, the gap between them and the legend.
 */
enum class MeterInk { Primary, Secondary, Tertiary }

@Composable
private fun MeterInk.color(): Color = when (this) {
    MeterInk.Primary -> Hl.colors.content
    MeterInk.Secondary -> Hl.colors.contentSecondary
    MeterInk.Tertiary -> Hl.colors.contentTertiary
}

/** One part of a [HlMeter]. */
data class MeterSegment(val fraction: Float, val ink: MeterInk)

/**
 * A segmented gauge: how a capacity is shared. Segments are drawn left to right on a faint
 * track, a gap apart; what is left of the track is free.
 */
@Composable
fun HlMeter(segments: List<MeterSegment>, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(HlSize.meter)
            .clip(HlShapes.control)
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

/**
 * A line of a meter's legend, read as a table like the back of a case: its ink, what it is in a
 * column of its own, then how much, on the same baseline, in tabular figures that line up.
 */
@Composable
fun MeterLegend(label: String, value: String, ink: MeterInk, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Box(Modifier.align(Alignment.CenterVertically).size(HlSize.dot).clip(HlShapes.avatar).background(ink.color()))
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
