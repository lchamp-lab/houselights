package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.rememberTextMeasurer
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
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.screenWidthPx
import kotlin.math.roundToInt

enum class Emphasis { Primary, Secondary }

/** Set by [HeaderControls]: buttons and tiles take their compact sizes. */
private val LocalInHeader = staticCompositionLocalOf { false }

/** Lays [content] out as a screen's header: its buttons and tiles 40 tall. */
@Composable
fun HeaderControls(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInHeader provides true, content = content)
}

/**
 * Button. The primary one is the main action: the ember light in black ink, framed by focus. A secondary one is
 * a plate turning white. Disabled, it keeps focus, ringed, and ignores OK; [busy] shows the pending dot and
 * [busyLabel] and ignores OK. A button with a busy label of its own takes from the start the width of the wider of
 * its words at rest and busy, [reserveFor] included (another busy label it shows in turn), so nothing beside it moves
 * as they change. [detailLit] lights the detail for a state it names (an active filter, the sort chosen); a detail
 * [detailWhole] goes whole where the button has less room than it needs, never cut (a length).
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
    reserveFor: String? = null,
    detailWhole: Boolean = false,
) = ButtonPlate(
    text,
    onClick,
    modifier,
    icon,
    detail,
    emphasis,
    detailLit,
    enabled,
    busy,
    busyLabel,
    reserveFor = reserveFor,
    detailWhole = detailWhole,
)

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
    reserveFor: String? = null,
    detailWhole: Boolean = false,
) {
    val c = Hl.colors
    val busyState = stringResource(R.string.control_busy)
    val height = if (LocalInHeader.current) HlSize.controlCompact else HlSize.control
    val surface = modifier.height(height).semantics { if (busy) stateDescription = busyState }
    val words = ButtonWords(text, icon, detail?.let { DetailWords(it, detailLit, detailWhole) }, busy, busyLabel)
    val reserved = reservedWidth(words, reserveFor)
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
            ButtonRow(words, reserved, primary = true, lit = lit, enabled = enabled)
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
        ) { lit -> ButtonRow(words, reserved, primary = false, lit = lit, enabled = enabled) }
    }
}

/** What a button says. */
@Immutable
private class ButtonWords(
    val text: String,
    val icon: ImageVector?,
    val detail: DetailWords?,
    val busy: Boolean,
    val busyLabel: String,
)

/** A button's detail: its [text], [lit] for a state it names, going [whole] where the button has no room for it. */
@Immutable
private class DetailWords(val text: String, val lit: Boolean, val whole: Boolean)

@Composable
private fun BoxScope.ButtonRow(words: ButtonWords, reserved: Dp, primary: Boolean, lit: Boolean, enabled: Boolean) {
    Row(
        Modifier
            .align(Alignment.Center)
            .keepWidthWhile(words.busy)
            .widthIn(min = reserved)
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
                ButtonDetail(it, primary, lit, enabled)
            }
        }
    }
}

/**
 * A button's detail: on the main action, a duration in the technical voice and its black ink; on a plate,
 * a value, quiet, lit in ember when it names a state (a disabled button keeps it quiet: no ember on a well).
 * Facts [FACT_GAP] apart (a film, then its time left) drop whole from the end where the button has less room than
 * they need, as a line of facts does; the first alone ends in an ellipsis, unless the detail goes whole: then it does.
 */
@Composable
private fun ButtonDetail(detail: DetailWords, primary: Boolean, lit: Boolean, enabled: Boolean) {
    val text = detail.text
    val whole = detail.whole
    val c = Hl.colors
    val t = Hl.type
    val chosen = remember(c, t.entry) { t.entry.emberLit(c) }
    val style = when {
        primary -> t.jaquette
        detail.lit && enabled && !lit -> chosen
        else -> t.entry
    }
    val color = if (primary) Color.Unspecified else FocusStyle.quietInk(lit && enabled, enabled, lit = false)
    val facts = remember(text) { text.split(FACT_GAP) }
    if (facts.size == 1 && !whole) {
        Text(text, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    } else {
        OneLine(squeezeFirst = !whole) {
            facts.forEachIndexed { i, fact ->
                Text(
                    if (i == 0) fact else FACT_GAP + fact,
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (i == 0) Modifier else Modifier.layoutId(Continued),
                )
            }
        }
    }
}

/**
 * The wider of a button's row at rest and busy, its icon or pending dot and paddings included, for a button whose
 * words change (its busy label, and [reserveFor] another it shows in turn), so it takes that width from the start;
 * unspecified for one that keeps its words, or says a detail.
 */
@Composable
private fun reservedWidth(words: ButtonWords, reserveFor: String?): Dp {
    val changes = words.busyLabel != words.text || reserveFor != null
    if (!changes || words.detail != null) return Dp.Unspecified
    val style = Hl.type.entry
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val icon = words.icon != null
    return remember(words.text, words.busyLabel, reserveFor, icon, style, density) {
        with(density) {
            fun wide(label: String) =
                measurer.measure(label, style, softWrap = false, maxLines = 1).size.width.toDp().coerceAtMost(HlLayout.readableNarrow)
            val lead = HlControl.buttonPaddingIcon + HlSize.icon + HlControl.iconGap
            val rest = (if (icon) lead else HlControl.buttonPaddingH) + wide(words.text) + HlControl.buttonPaddingH
            val said = maxOf(wide(words.busyLabel), reserveFor?.let(::wide) ?: wide(words.busyLabel))
            maxOf(rest, lead + said + HlControl.buttonPaddingH)
        }
    }
}

/** Records the content width at rest and keeps at least that width while [busy], so the row does not shift. */
private fun Modifier.keepWidthWhile(busy: Boolean): Modifier = this then KeepWidthElement(busy)

private data class KeepWidthElement(val busy: Boolean) : ModifierNodeElement<KeepWidthNode>() {
    override fun create() = KeepWidthNode(busy)

    override fun update(node: KeepWidthNode) {
        node.busy = busy
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "keepWidthWhile"
        properties["busy"] = busy
    }
}

/** Measures the content as it comes while not [busy], keeping its width; while busy, at least as wide as that. */
private class KeepWidthNode(var busy: Boolean) : Modifier.Node(), LayoutModifierNode {
    private var rest = 0

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = if (busy) {
            measurable.measure(constraints.copy(minWidth = rest.coerceIn(constraints.minWidth, constraints.maxWidth)))
        } else {
            measurable.measure(constraints).also { rest = it.width }
        }
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
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
 * A square tile on its plate, white with focus; 40 in a header ([HeaderControls]), else 44. [chosen] lights
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
 * on the label's second line; without options, that line is its [hint], quieter. Within a [labelRoom], the label keeps
 * to its edges as it does to the screen's.
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
    labelRoom: LabelRoom? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // A sheet is a separate window: the tile keeps focus under it but hides its label.
    val shown = focused && LocalWindowInfo.current.isWindowFocused
    val labelAlpha by animateFloatAsState(
        if (shown) 1f else 0f,
        HlMotion.inOut(shown, HlMotion.PLATE_IN, HlMotion.PLATE_OUT),
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
                    .clearAndSetSemantics { testTag = TILE_LABEL }
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
        val room = labelRoom?.takeIf { it.known }
        val widest = room?.let { minOf(tileLabelWidth(screenWidth), it.width) } ?: tileLabelWidth(screenWidth)
        val text = measurables[1].measure(Constraints(maxWidth = widest))
        val gap = HlSpace.xs.roundToPx()
        val edge = HlIconButtonDefaults.labelEdge.roundToPx()
        layout(button.width, button.height) {
            button.place(0, 0)
            val x = coordinates?.let { here ->
                val screen = here.findRootCoordinates()
                val left = screen.localPositionOf(here, Offset.Zero).x.roundToInt()
                // Within what holds it, its edges then the edges, else within the screen's.
                when (room) {
                    null -> labelX(button.width, text.width, left, screen.size.width, edge)
                    else -> labelX(button.width, text.width, left - room.start, room.width, 0)
                }
            } ?: ((button.width - text.width) / 2)
            text.place(x, button.height + gap)
        }
    }
}

/**
 * Where the labels of the tiles in what holds them may lie across the screen ([labelRoom]): from [start] to
 * [start] + [width] px, as it is placed; unknown until then.
 */
@Stable
class LabelRoom {
    var start by mutableIntStateOf(0)
        private set
    var width by mutableIntStateOf(-1)
        private set

    /** Whether it was placed. */
    val known: Boolean get() = width > 0

    internal fun placed(start: Int, width: Int) {
        this.start = start
        this.width = width
    }
}

/** This element as the [room] its tiles' labels lie within, their edges its own ([HlIconButton]'s `labelRoom`). */
internal fun Modifier.labelRoom(room: LabelRoom): Modifier = onPlaced { here ->
    val bounds = here.boundsInRoot()
    room.placed(bounds.left.roundToInt(), bounds.width.roundToInt())
}

/** Finds a tile's label in tests: it is read as the tile, never on its own. */
internal const val TILE_LABEL = "tile-label"

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
 * The lines the longest of [descriptions] takes in an [HlListItem] [width] wide, as its description is set at the
 * viewer's text size: choices side by side or stacked reserve as many ([HlListItem]'s `descriptionLines`), so they keep
 * one height.
 */
@Composable
fun rememberDescriptionLines(descriptions: List<String>, width: Dp): Int {
    val style = Hl.type.meta
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(descriptions, width, style, density) {
        val room = with(density) { (width - HlInset.card * 2).roundToPx() }.coerceAtLeast(0)
        descriptions.maxOfOrNull { measurer.measure(it, style, constraints = Constraints(maxWidth = room)).lineCount } ?: 1
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
                        onFocus = { if (selectOnFocus) onSelect(i) },
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
private fun Modifier.enterOn(requesters: List<FocusRequester>, current: Int): Modifier = this
    .focusProperties { onEnter = { requesters.getOrNull(current)?.requestFocus() } }
    .focusGroup()

@Composable
private fun SegmentItem(
    text: String,
    detail: SegmentDetail?,
    current: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
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
            .onFocusChanged { if (it.isFocused) onFocus() },
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
 * A track's word: the current one heavier in the ember light, black under focus; each as wide as it is current and
 * set from its start, so becoming current moves neither it nor the words after it.
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
        // Measured, never drawn: choosing a word, which sets it heavier, moves none of the others.
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
