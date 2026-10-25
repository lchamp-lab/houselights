package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.AmbientLight
import app.houselights.ui.design.Ground
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlColors
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlIconButtonDefaults
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.LocalGround
import app.houselights.ui.design.RowScroll
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.emberLit
import app.houselights.ui.design.plate
import app.houselights.ui.design.plateEdge
import app.houselights.ui.util.screenWidthPx
import kotlin.math.roundToInt

enum class Emphasis { Primary, Secondary }

/** Set by [HeaderControls]: buttons and tiles take their compact sizes. */
private val LocalInHeader = staticCompositionLocalOf { false }

/** Lays [content] out as a screen's header: its buttons 44 tall, its tiles 40 square. */
@Composable
fun HeaderControls(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInHeader provides true, content = content)
}

/**
 * Button. The primary one is the main action: the ember light in black ink, framed by focus. A secondary one is
 * a plate turning white. Disabled, it keeps focus, ringed, and ignores OK; [busy] shows the pending dot and
 * [busyLabel] at the same width and ignores OK. [detailLit] lights the detail for a state it names (an active
 * filter, the sort chosen).
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
) = ButtonPlate(text, onClick, modifier, icon, detail, emphasis, detailLit, enabled, busy, busyLabel)

/** The plate of [HlButton]; [caution] sets the label in ember for an irreversible action ([SheetConfirm]). */
@Composable
internal fun ButtonPlate(
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
    val busyState = stringResource(R.string.control_busy)
    val height = if (LocalInHeader.current) HlSize.controlCompact else HlSize.control
    val surface = modifier.height(height).semantics { if (busy) stateDescription = busyState }
    val words = ButtonWords(text, icon, detail, detailLit, busy, busyLabel)
    if (emphasis == Emphasis.Primary) {
        val light = remember(c) { c.emberLight() }
        HlFocusSurface(
            onClick = { if (!busy) onClick() },
            look = FocusStyle.frame(
                rest = Color.Transparent,
                restInk = c.onEmber,
                disabled = c.content.copy(alpha = HlAlpha.FILL_FAINT),
                disabledInk = c.contentTertiary,
            ),
            shape = HlShapes.control,
            modifier = surface,
            enabled = enabled,
        ) { lit ->
            // A disabled action keeps its well: the light is for what OK starts.
            if (enabled) Box(Modifier.matchParentSize().background(light))
            ButtonRow(words, primary = true, lit = lit, enabled = enabled)
        }
    } else {
        val plate = rememberPlate()
        HlFocusSurface(
            onClick = { if (!busy) onClick() },
            look = FocusStyle.fill(
                rest = plate.fill,
                restInk = if (caution) c.ember else c.content,
                disabled = plate.disabled,
                disabledInk = c.contentTertiary,
                edge = plate.edge,
            ),
            shape = HlShapes.control,
            modifier = surface,
            enabled = enabled,
        ) { lit -> ButtonRow(words, primary = false, lit = lit, enabled = enabled) }
    }
}

/** What a button says. */
@Immutable
private class ButtonWords(
    val text: String,
    val icon: ImageVector?,
    val detail: String?,
    val detailLit: Boolean,
    val busy: Boolean,
    val busyLabel: String,
)

@Composable
private fun BoxScope.ButtonRow(words: ButtonWords, primary: Boolean, lit: Boolean, enabled: Boolean) {
    Row(
        Modifier
            .align(Alignment.Center)
            .keepWidthWhile(words.busy)
            .padding(
                start = if (words.icon != null || words.busy) HlControl.buttonPaddingIcon else HlControl.buttonPaddingH,
                end = HlControl.buttonPaddingH,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (words.busy) {
            // On the ember light, the pending dot takes the button's black ink.
            val ink = LocalContentColor.current.takeIf { primary }
            Box(Modifier.size(HlSize.icon), contentAlignment = Alignment.Center) { StatusLight(StatusState.Pending, ink = ink) }
            Spacer(Modifier.width(HlControl.iconGap))
            ButtonLabel(words.busyLabel)
        } else {
            words.icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(HlSize.icon))
                Spacer(Modifier.width(HlControl.iconGap))
            }
            ButtonLabel(words.text)
            words.detail?.let {
                Spacer(Modifier.width(HlControl.iconGap))
                ButtonDetail(it, primary, lit, enabled, words.detailLit)
            }
        }
    }
}

/**
 * A button's detail: on the main action, a duration in the technical voice and its black ink; on a plate,
 * a value, quiet, lit in ember when it names a state (a disabled button keeps it quiet: no ember on a well).
 */
@Composable
private fun ButtonDetail(text: String, primary: Boolean, lit: Boolean, enabled: Boolean, detailLit: Boolean) {
    val c = Hl.colors
    val t = Hl.type
    val chosen = remember(c, t.entry) { t.entry.emberLit(c) }
    val style = when {
        primary -> t.jaquette
        detailLit && enabled && !lit -> chosen
        else -> t.entry
    }
    val color = if (primary) Color.Unspecified else FocusStyle.quietInk(lit && enabled, enabled, lit = false)
    Text(text, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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

/** A plate under a secondary control, read while drawing, its edge, and its well when it cannot act. */
@Immutable
private class PlateLook(val fill: ColorProducer, val disabled: ColorProducer, val edge: BorderStroke?)

/**
 * By ground: on artwork, the picture's own dark ([plate]) set off by its hairline; on the room, glass; on glass,
 * a step up. A control that cannot act sits in a faint well, but keeps its plate over a picture.
 */
@Composable
private fun rememberPlate(): PlateLook {
    val c = Hl.colors
    val ground = LocalGround.current
    val light = LocalAmbient.current
    return remember(c, ground, light) {
        val well = c.content.copy(alpha = HlAlpha.FILL_FAINT)
        when (ground) {
            Ground.Artwork -> {
                val plate = ColorProducer { c.plate(light) }
                PlateLook(plate, plate, BorderStroke(HlSize.hairline, c.plateEdge))
            }
            Ground.Glass -> {
                val step = c.content.copy(alpha = HlAlpha.FILL)
                PlateLook({ step }, { well }, null)
            }
            Ground.Room -> {
                val glass = c.glass
                PlateLook({ glass }, { well }, null)
            }
        }
    }
}

/**
 * A square tile on its plate, white with focus; 40 in a header ([HeaderControls]), else 48. [chosen] lights
 * its glyph in ember, raspberry on the white of focus.
 */
@Composable
private fun Tile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chosen: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val c = Hl.colors
    val header = LocalInHeader.current
    val plate = rememberPlate()
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.fill(
            rest = plate.fill,
            restInk = c.content,
            disabled = plate.disabled,
            disabledInk = c.contentTertiary,
            edge = plate.edge,
        ),
        shape = HlShapes.control,
        modifier = modifier.size(if (header) HlSize.tileCompact else HlSize.tile),
        enabled = enabled,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        interactionSource = interactionSource,
    ) { lit ->
        val tint = when {
            !chosen || !enabled -> LocalContentColor.current
            lit -> c.emberOnLight
            else -> c.ember
        }
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(if (header) HlSize.icon else HlSize.tileIcon).align(Alignment.Center),
        )
    }
}

/**
 * A row of actions that reserves room below for a tile's focus label, and for its hint's line when [hinted] (a tile
 * may have one on this screen), so nothing moves.
 */
@Composable
fun HlActionRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(HlSpace.s),
    hinted: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val room = HlIconButtonDefaults.labelRoom + if (hinted) HlIconButtonDefaults.hintRoom else HlSpace.none
    Row(
        Modifier.padding(bottom = room).then(modifier),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

/**
 * Square icon tile whose focus label overflows below it on a plate, without taking layout space (an
 * [HlActionRow] reserves it), centred unless near the screen side ([HlIconButtonDefaults.labelEdge]), hidden
 * under a sheet. A toggle names its action ("Marquer comme vu") and shows its state by [lit]. Menu or a long OK
 * opens its options ([onLongClick], which [onLongClickLabel] names to accessibility services), the Menu key saying so
 * on the label's second line; without options, that line is its [hint], quieter.
 */
@Composable
fun HlIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lit: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    hint: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // A sheet is a separate window: the tile keeps focus under it but hides its label.
    val shown = focused && LocalWindowInfo.current.isWindowFocused
    val labelAlpha by animateFloatAsState(
        if (shown) 1f else 0f,
        if (shown) tween(HlMotion.PLATE_IN, easing = HlMotion.focusPull) else tween(HlMotion.PLATE_OUT, easing = HlMotion.fallBack),
        label = "label",
    )
    val c = Hl.colors
    val light = LocalAmbient.current
    val screenWidth = screenWidthPx()
    Layout(
        modifier = modifier,
        content = {
            Tile(
                icon,
                label,
                onClick,
                enabled = enabled,
                chosen = lit,
                interactionSource = interaction,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            )
            // Always on a plate: the label may fall over any picture.
            Column(
                Modifier
                    .clearAndSetSemantics {}
                    .graphicsLayer { alpha = labelAlpha }
                    .labelPlate(c, light)
                    .padding(horizontal = HlControl.labelPaddingH, vertical = HlControl.labelPaddingV),
            ) {
                Text(label, style = Hl.type.meta, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onLongClick != null) {
                    // On a hint's line, as tall as it, at its end.
                    MenuKey(Modifier.align(Alignment.End).height(HlIconButtonDefaults.hintRoom).wrapContentHeight(unbounded = true))
                } else if (hint != null) {
                    Text(hint, style = Hl.type.meta, color = c.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    ) { measurables, constraints ->
        val button = measurables[0].measure(constraints)
        val text = measurables[1].measure(Constraints(maxWidth = tileLabelWidth(screenWidth)))
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

/** A tile's label plate over the picture [light] comes from, and its hairline, read while drawing. */
internal fun Modifier.labelPlate(c: HlColors, light: AmbientLight): Modifier = drawWithCache {
    val outline = HlShapes.mark.createOutline(size, layoutDirection, this)
    val hairline = Stroke(HlSize.hairline.toPx())
    onDrawBehind {
        drawOutline(outline, c.plate(light))
        drawOutline(outline, c.plateEdge, style = hairline)
    }
}

/** A tile's widest label: [HlIconButtonDefaults.labelWidth] grown with the text size, within the [screen]'s label edges. */
internal fun Density.tileLabelWidth(screen: Int): Int {
    val edges = 2 * HlIconButtonDefaults.labelEdge.roundToPx()
    return (HlIconButtonDefaults.labelWidth * fontScale).roundToPx().coerceAtMost(screen - edges)
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

/**
 * A choice in a vertical list (sources, servers): a panel with icon and [badges], quiet on the
 * room, on glass when [level] raises it over a picture.
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
    level: PanelLevel = PanelLevel.Quiet,
) {
    val c = Hl.colors
    HlPanelButton(onClick, modifier.fillMaxWidth(), level = level) { focused ->
        Column(Modifier.padding(HlInset.card)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (focused) c.content else c.contentSecondary,
                        modifier = Modifier.size(HlSize.largeIcon),
                    )
                    Spacer(Modifier.width(HlSpace.s))
                }
                Text(title, style = Hl.type.entry, color = c.content, modifier = Modifier.weight(1f))
            }
            if (description != null) {
                Spacer(Modifier.height(HlSpace.xs))
                Text(description, style = Hl.type.meta, color = c.contentSecondary, minLines = descriptionLines)
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
 * Where you are among a few places, as words in the poster's voice: seasons, NFO views, a form's choice. The
 * current word is lit in ember, the others grey; focus is the white plate, a raspberry dot before the current word.
 * Words line up with the start edge and their plates hang past it, so nothing moves as focus or the choice moves.
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
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    val scroll = rememberScrollState()
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        RowScroll {
            Row(
                Modifier
                    .hang(TrackHang)
                    .then(if (scrollable) Modifier.horizontalScroll(scroll) else Modifier)
                    .padding(start = DotRoom)
                    .enterOn(requesters, enterAt)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(PlateGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { i, label ->
                    SegmentItem(
                        label,
                        detail = details.getOrNull(i),
                        current = i == selected,
                        onClick = { onSelect(i) },
                        onFocused = { if (selectOnFocus) onSelect(i) },
                        modifier = Modifier.focusRequester(requesters[i]),
                    )
                }
            }
        }
    }
}

/** Room before the current word for its dot. */
private val DotRoom: Dp = HlSize.wordDot + HlControl.iconGap

/** How far a track's plates may hang before its first word: a plate's padding and the current word's dot. */
private val TrackHang: Dp = HlControl.tabPaddingH + DotRoom

/** Between two plates of a track, so a current plate's dot room clears the word before it. */
private val PlateGap: Dp = HlSpace.xs

/** Laid out [by] further to the start than its slot, which it fills less [by]: what it draws hangs into the margin. */
internal fun Modifier.hang(by: Dp): Modifier = layout { measurable, constraints ->
    val room = by.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = room))
    layout(constraints.constrainWidth(placeable.width - room), placeable.height) { placeable.place(-room, 0) }
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
    current: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.fill(rest = Color.Transparent, restInk = if (current) c.content else c.contentSecondary),
        shape = HlShapes.control,
        // The current plate takes its dot's room before the word, hanging over the gap: the word stays put.
        modifier = modifier
            .then(if (current) Modifier.hang(DotRoom) else Modifier)
            .height(HlSize.segment)
            .onFocusChanged { if (it.isFocused) onFocused() },
        role = Role.Tab,
        selected = current,
    ) { lit ->
        Row(
            Modifier.align(Alignment.CenterStart).padding(horizontal = HlControl.tabPaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current) {
                Box(Modifier.width(DotRoom), contentAlignment = Alignment.CenterStart) {
                    // On the white of focus, the place stays marked: a raspberry dot.
                    val dot = Modifier.size(HlSize.wordDot)
                    if (lit) Box(dot.background(c.emberOnLight, CircleShape))
                }
            }
            Word(text, current, lit)
            when (detail) {
                null -> Unit
                SegmentDetail.Done -> {
                    Spacer(Modifier.width(HlSpace.xs))
                    // A white check, dark under focus, as on a watched card's mark.
                    Icon(
                        HlIcons.Check,
                        contentDescription = null,
                        tint = if (lit) LocalContentColor.current else c.content,
                        modifier = Modifier.size(HlSize.inlineIcon),
                    )
                }
                is SegmentDetail.Count -> {
                    Spacer(Modifier.width(HlSpace.xs))
                    val ink = FocusStyle.quietInk(lit, enabled = true, lit = false)
                    Text(detail.text, style = Hl.type.meta, color = ink, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * A track's word: the current one bold in the ember light, black under focus; each as wide as it is in bold and
 * set from its start, so turning bold moves neither it nor the words after it.
 */
@Composable
private fun Word(text: String, current: Boolean, lit: Boolean) {
    val c = Hl.colors
    val t = Hl.type
    val here = remember(c, t.placeCurrent) { t.placeCurrent.emberLit(c) }
    val style = when {
        !current -> t.place
        lit -> t.placeCurrent
        else -> here
    }
    Box(contentAlignment = Alignment.CenterStart) {
        // Measured, never drawn: choosing a word, which turns it bold, moves none of the others.
        Text(text, style = t.placeCurrent, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics {}.unplaced())
        Text(text, style = style, maxLines = 1, softWrap = false)
    }
}

/** Takes its room in the layout without being placed, so it is neither drawn nor read. */
private fun Modifier.unplaced(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {}
}

/** Progress shown after an [HlSegmented] option's name: a check when [Done], else figures ("6 / 10"). */
sealed interface SegmentDetail {
    data object Done : SegmentDetail

    data class Count(val text: String) : SegmentDetail
}
