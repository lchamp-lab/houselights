package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.ui.design.Ground
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlGap
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSettingGroupDefaults
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.LocalGround
import app.houselights.ui.design.edge
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.glassPlate
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.design.groundWell
import app.houselights.ui.design.plate
import app.houselights.ui.design.plateEdge
import app.houselights.ui.design.roomStep
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
        // Raised, on glass; else on the ground it lies on, the slot composed in one place either way.
        CompositionLocalProvider(LocalGround provides if (raised) Ground.Glass else LocalGround.current) { content() }
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
    onFocus: () -> Unit = {},
    lit: Boolean = false,
    enabled: Boolean = true,
    level: PanelLevel = PanelLevel.Quiet,
    /** Its options (Menu, or OK held), named to accessibility services by [onLongClickLabel] ("Plus d’options"). */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val c = Hl.colors
    val raised = level == PanelLevel.Raised
    HlFocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        look = FocusStyle.frame(
            rest = if (raised) c.glass else groundStepFill(),
            restInk = c.content,
            edge = BorderStroke(HlSize.hairline, if (lit && enabled) c.emberLight() else SolidColor(c.edge)),
            disabled = groundWell(),
            disabledInk = c.contentTertiary,
        ),
        shape = HlShapes.panel,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        enabled = enabled,
    ) { focused ->
        CompositionLocalProvider(LocalGround provides if (raised) Ground.Glass else LocalGround.current) { content(focused) }
    }
}

/**
 * A full-width selectable row: its words on what holds it, white-filled on focus; disabled, it sits in its
 * [disabled] well and keeps focus, ringed. Without [onClick] it only reads: focus reaches it, OK does nothing.
 */
@Composable
internal fun HlRowSurface(
    onClick: (() -> Unit)?,
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

/**
 * A plate holding a group (DESIGN.md, Secondary screens), [HlShapes.panel] edged by `plateEdge`, read while drawing:
 * on a field of posters ([PosterField]) its dark, as a plate over a picture; in a sheet the film's [glassPlate]; the
 * room's one step ([roomStep]) on the bare room. What it holds lies on glass, its words [HlInset.plate] from its
 * sides and [HlInset.card] from its top and foot.
 */
@Composable
fun HlPlate(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = HlInset.plate, vertical = HlInset.card),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.plate(BorderStroke(HlSize.hairline, Hl.colors.plateEdge)).padding(padding)) {
        CompositionLocalProvider(LocalGround provides Ground.Glass) { content() }
    }
}

/** A group's plate on what it lies on ([HlPlate]), [edge] around it. */
@Composable
internal fun Modifier.plate(edge: BorderStroke): Modifier {
    val c = Hl.colors
    val inSheet = LocalGround.current == Ground.Glass
    val onField = LocalRoomField.current != null
    val light = LocalAmbient.current
    return drawWithCache {
        val area = HlShapes.panel.createOutline(size, layoutDirection, this)
        onDrawBehind {
            val fill = when {
                inSheet -> c.glassPlate(light)
                onField -> c.plate(light)
                else -> c.roomStep
            }
            drawOutline(area, fill)
        }
    }.border(edge, HlShapes.panel)
}

/**
 * One row of a group's plate in a lazy list, as [HlSettingGroup] lays its rows on one plate, each row composed only when
 * shown (a level of hundreds of folders): the plate's top corners round on the [first] row, its bottom ones on the
 * [last], its edge on the outside only, so the rows read as one plate with no rule between them.
 */
@Composable
internal fun PlateSlice(first: Boolean, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val c = Hl.colors
    val inSheet = LocalGround.current == Ground.Glass
    val onField = LocalRoomField.current != null
    val light = LocalAmbient.current
    val edge = c.plateEdge
    val slice = Modifier.fillMaxWidth().drawWithCache {
        val stroke = HlSize.hairline.toPx()
        val radius = HlShapes.panel.topStart.toPx(size, this)
        // The whole plate the row is part of, running past the row's ends far enough to round them out of sight.
        val above = if (first) 0f else radius + stroke
        val below = if (last) 0f else radius + stroke
        val outline = HlShapes.panel.createOutline(Size(size.width - stroke, size.height + above + below - stroke), layoutDirection, this)
        onDrawBehind {
            val fill = when {
                inSheet -> c.glassPlate(light)
                onField -> c.plate(light)
                else -> c.roomStep
            }
            clipRect {
                translate(left = stroke / 2, top = stroke / 2 - above) {
                    drawOutline(outline, fill)
                    drawOutline(outline, edge, style = Stroke(stroke))
                }
            }
        }
    }
    val group = HlSettingGroupDefaults.inset
    val inset = PaddingValues(
        start = group,
        end = group,
        top = if (first) group else HlSpace.none,
        bottom = if (last) group else HlSpace.none,
    )
    Column(slice.padding(inset)) {
        CompositionLocalProvider(LocalGround provides Ground.Glass) { content() }
    }
}

/**
 * What is read in a sheet, focused to be read (a technical sheet's block): a group's plate ([HlPlate]) whose
 * hairline becomes focus's ring ([FocusStyle.ring]); OK does nothing. [readAs] is what it says, else its words.
 */
@Composable
fun ReadingPlate(
    modifier: Modifier = Modifier,
    readAs: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val edge = if (focused) FocusStyle.ring() else BorderStroke(HlSize.hairline, Hl.colors.plateEdge)
    val reading = if (readAs != null) {
        Modifier.clearAndSetSemantics {
            contentDescription = readAs
            this.focused = focused
        }
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .then(reading)
            .focusable()
            .plate(edge)
            .padding(start = HlInset.plate, end = HlInset.plate, top = HlSpace.m, bottom = HlInset.card),
    ) {
        CompositionLocalProvider(LocalGround provides Ground.Glass) { content() }
    }
}

/** A hairline between a menu's sections or a panel's parts; never between a pane's or a sheet's groups. */
@Composable
fun HlDivider(modifier: Modifier = Modifier, vertical: Boolean = false) {
    val color = Hl.colors.contentDecorative
    if (vertical) {
        Box(modifier.width(HlSize.hairline).fillMaxHeight().background(color))
    } else {
        Box(modifier.fillMaxWidth().height(HlSize.hairline).background(color))
    }
}

/**
 * Facts on one line, space-separated ("2026   2 h 02   12"); those that do not fit drop whole from the end. [lead]
 * inks the first, the name the line is about.
 */
@Composable
fun FactRow(
    facts: List<String?>,
    modifier: Modifier = Modifier,
    style: TextStyle = Hl.type.meta,
    color: Color = Hl.colors.contentSecondary,
    lead: Color = color,
) {
    OneLine(modifier) {
        facts.filterNotNull().filter { it.isNotBlank() }.forEachIndexed { i, fact ->
            Text(fact, style = style, color = if (i == 0) lead else color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Printed [badges] (a protocol), then [facts], the first inked [lead], on one line [gap] apart (a fact's space). */
@Composable
internal fun BadgedFactRow(
    badges: List<String>,
    facts: List<String?>,
    modifier: Modifier = Modifier,
    lead: Color = Hl.colors.contentSecondary,
    gap: Dp = HlSpace.fact,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        badges.forEach { Badge(it) }
        if (facts.any { !it.isNullOrBlank() }) FactRow(facts, Modifier.weight(1f, fill = false), lead = lead)
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
            action != null -> (
                {
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
                }
                )
            backHint -> ({ BackHint() })
            else -> null
        },
    )
}

/**
 * The state of something the app depends on, said by a dot of projector white: [On] active (connected, in use)
 * and [Done] an established fact, the dot; [Off] absent, its ring; [Pending] in progress, a quiet dot;
 * [Attention] something went wrong, its circled mark in ember.
 */
enum class StatusState { On, Done, Off, Pending, Attention }

/**
 * A state in words with its light ([StatusLight]); [detail] explains why, below and aligned with the words, closer on
 * one line than on several. Each holds on [maxLines] at most, ending in an ellipsis. Said in the place of text to read
 * ([reads]: an episode's overview), its state is an `entry` and its reason `body`, as the text it replaces; else `meta`.
 */
@Composable
fun HlStatus(
    state: StatusState,
    text: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    maxLines: Int = Int.MAX_VALUE,
    reads: Boolean = false,
) {
    val c = Hl.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLight(state)
            Spacer(Modifier.width(HlGap.markToWord))
            val strong = state != StatusState.Off && state != StatusState.Pending
            Text(
                text,
                style = if (reads) Hl.type.entry else Hl.type.meta,
                color = if (strong) c.content else c.contentSecondary,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (detail != null) {
            Spacer(Modifier.height(if (maxLines == 1) HlGap.nameToNote else HlGap.nameToNoteWrapped))
            Text(
                detail,
                style = if (reads) Hl.type.body else Hl.type.meta,
                color = c.contentSecondary,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = HlSize.statusLight + HlGap.markToWord),
            )
        }
    }
}

/**
 * The light of a [StatusState] in a fixed-width box: the white dot for on and done, a quiet one for pending, a
 * ring for off, and a circled exclamation mark in ember for attention. [ink] overrides colours.
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
                StatusState.On, StatusState.Done -> dot.background(ink ?: c.content)
                StatusState.Pending -> dot.background(ink ?: c.contentTertiary)
                else -> dot.border(HlSize.dotRing, ink ?: c.contentSecondary, CircleShape)
            },
        )
    }
}

/**
 * An attention note: the attention light, then [text] in ember, on [maxLines], in `meta` as a pane's words are;
 * screens never set ember themselves. On a [focused] plate the words take focus's ink and the light stays lit, raspberry.
 */
@Composable
fun AttentionNote(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Hl.type.meta,
    focused: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(modifier) {
        // As tall as the first line, so the light sits beside it.
        val line = with(LocalDensity.current) { style.lineHeight.toDp() }
        Box(Modifier.height(line), contentAlignment = Alignment.Center) {
            StatusLight(StatusState.Attention, ink = FocusStyle.emberMark(focused))
        }
        Spacer(Modifier.width(HlSpace.xs))
        Text(
            text,
            style = style,
            color = FocusStyle.ink(focused, rest = Hl.colors.ember),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [HlMeter] part colours by rank; a share of capacity is not a state, so a meter never lights. */
enum class MeterInk { Primary, Secondary, Tertiary }

@Composable
internal fun MeterInk.color(): Color = when (this) {
    MeterInk.Primary -> Hl.colors.content
    MeterInk.Secondary -> Hl.colors.contentSecondary
    MeterInk.Tertiary -> Hl.colors.contentTertiary
}

/** One part of a [HlMeter]. */
data class MeterSegment(val fraction: Float, val ink: MeterInk)

/** A segmented gauge of how a capacity is shared, [height] tall ([HlSize.meterLarge] where a pane states it); the rest is free. */
@Composable
fun HlMeter(segments: List<MeterSegment>, modifier: Modifier = Modifier, height: Dp = HlSize.meter) {
    val c = Hl.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(HlShapes.badge)
            .background(groundStepFill()),
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
