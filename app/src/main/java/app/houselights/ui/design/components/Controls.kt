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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.layout.layout
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceScale
import app.houselights.R
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.ui.design.HlIconButtonDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.groundPlate
import app.houselights.ui.design.RowScroll

enum class Emphasis { Primary, Secondary }

/**
 * Pill button. Every button turns projector white when focused; the primary one is brighter at
 * rest so the main action reads first. The label stops at [HlLayout.readableNarrow] and the
 * [detail] at one line, both with an ellipsis; [detailLit] sets the detail in ember at rest,
 * for a state it names (an active filter). Not [enabled], the button sits in a faint well with
 * tertiary words and ignores OK; it keeps focus, outlined, so the remote still reaches it.
 * [busy], it is at work: the quiet dot of a pending state in place of its icon, [busyLabel] in
 * place of its label ("Connexion…"), its width kept, OK ignored. No spinner: nothing turns.
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
) = PillButton(text, onClick, modifier, icon, detail, emphasis, detailLit, enabled, busy, busyLabel)

/**
 * The pill of [HlButton]. [caution] sets the label in ember at rest: the action a question
 * asks about, which cannot be undone ([SheetConfirm]). Attention, never red.
 */
@Composable
internal fun PillButton(
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
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val busyState = stringResource(R.string.control_busy)
    val fill = if (emphasis == Emphasis.Primary) HlAlpha.FILL_STRONG else HlAlpha.FILL
    // A Surface rather than tv-material's Button, whose content keeps a minimum height of its own
    // at the top of the control: here the content is centred in exactly the control height.
    Surface(
        onClick = { if (!busy) onClick() },
        modifier = modifier.height(HlSize.control).semantics { if (busy) stateDescription = busyState },
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(HlShapes.control),
        colors = FocusStyle.fill(
            rest = c.content.copy(alpha = fill),
            restContent = if (caution) c.ember else c.content,
            disabled = c.content.copy(alpha = HlAlpha.FILL_FAINT),
            disabledContent = c.contentTertiary,
        ),
        border = FocusStyle.disabledRing(HlShapes.control),
        scale = FocusStyle.scale(FocusStyle.Fill.Pill),
        interactionSource = interaction,
    ) {
        Row(
            Modifier
                .align(Alignment.Center)
                .keepWidthWhile(busy)
                .padding(
                    start = if (icon != null || busy) HlControl.buttonPaddingIcon else HlControl.buttonPaddingH,
                    end = HlControl.buttonPaddingH,
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                Box(Modifier.size(HlSize.icon), contentAlignment = Alignment.Center) { StatusLight(StatusState.Pending) }
                Spacer(Modifier.width(HlControl.iconGap))
                ButtonLabel(busyLabel)
            } else {
                // A disabled button keeps its well under focus: white would offer an action.
                ButtonContent(text, icon, detail, detailColor(focused && enabled, enabled, detailLit))
            }
        }
    }
}

/** What a button says at rest: its icon, its label, its detail. */
@Composable
private fun ButtonContent(text: String, icon: ImageVector?, detail: String?, detailColor: Color) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(HlSize.icon))
        Spacer(Modifier.width(HlControl.iconGap))
    }
    ButtonLabel(text)
    if (detail != null) {
        Spacer(Modifier.width(HlSpace.s))
        Text(detail, style = Hl.type.meta, color = detailColor, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** The line under a button's name, by state. Secondary at rest: tertiary falls to 3.9:1 on a primary button. */
@Composable
private fun detailColor(onWhite: Boolean, enabled: Boolean, lit: Boolean): Color {
    val c = Hl.colors
    return when {
        onWhite -> c.onFocusSecondary
        !enabled -> c.contentTertiary
        lit -> c.ember
        else -> c.contentSecondary
    }
}

/** Measures the content at rest, and keeps at least that width while [busy]: the row around it does not move. */
private fun Modifier.keepWidthWhile(busy: Boolean): Modifier = composed {
    var rest by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    if (busy) widthIn(min = with(density) { rest.toDp() }) else onSizeChanged { rest = it.width }
}

/** The words of a button, on one line, no wider than a reading column. */
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

/**
 * A row of actions (a pill, then round buttons) that keeps room under itself for the label a round
 * button shows with focus, so nothing moves when it shows and what follows never lies under it.
 * The screen does not need to know the label is there.
 */
@Composable
fun HlActionRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(HlSpace.s),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.padding(bottom = HlIconButtonDefaults.labelRoom).then(modifier),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

/**
 * Round icon button; its label appears under it while focused, keeping the row quiet. The label
 * takes no room: it overflows under the button, so a row that has nothing below it (the home bar)
 * stays as tall as its controls, and a row that has something below is an [HlActionRow]. The label
 * is centred under the button, even at the edge of the safe area; only where it would come closer
 * than [HlIconButtonDefaults.labelEdge] to the side of the screen does it align with the button's edge.
 * Under a sheet, which has the window, it hides. The icon carries the name for accessibility. The
 * label always sits on a dark plate; on a picture ([OnArtwork]) the button sits on one too, so it
 * reads over any image. Not [enabled], its icon goes quiet and OK does nothing; focus still
 * reaches it, outlined, and still shows its label. A toggle names what OK does ("Marquer comme
 * vu") and shows where things stand with its icon: [lit], ember at rest.
 */
@Composable
fun HlIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lit: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // A sheet opens a window of its own: the button keeps its focus under it, not its label.
    val shown = focused && LocalWindowInfo.current.isWindowFocused
    val labelAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(HlMotion.FAST), label = "label")
    val c = Hl.colors
    val plate = groundPlate()
    val restFill = plate ?: c.content.copy(alpha = HlAlpha.FILL)
    Layout(
        modifier = modifier,
        content = {
            Surface(
                onClick = onClick,
                modifier = Modifier.size(HlSize.iconButton),
                enabled = enabled,
                shape = ClickableSurfaceDefaults.shape(HlShapes.avatar),
                scale = FocusStyle.scale(FocusStyle.Fill.Pill),
                border = FocusStyle.disabledRing(HlShapes.avatar),
                colors = FocusStyle.fill(
                    rest = restFill,
                    restContent = if (lit) c.ember else c.content,
                    // On its plate over an image, the quiet icon is secondary, as all quiet things on artwork.
                    disabled = plate ?: c.content.copy(alpha = HlAlpha.FILL_FAINT),
                    disabledContent = if (plate != null) c.contentSecondary else c.contentTertiary,
                ),
                interactionSource = interaction,
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(HlSize.icon).align(Alignment.Center))
            }
            Text(
                label,
                style = Hl.type.caption,
                color = c.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Always on its plate: the label may fall on any picture, on home as on a detail page.
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .graphicsLayer { alpha = labelAlpha }
                    .background(c.background.copy(alpha = HlAlpha.VEIL), HlShapes.control)
                    .padding(horizontal = HlControl.labelPaddingH, vertical = HlControl.labelPaddingV),
            )
        },
    ) { measurables, constraints ->
        val button = measurables[0].measure(constraints)
        val text = measurables[1].measure(Constraints(maxWidth = HlIconButtonDefaults.labelWidth.roundToPx()))
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

/**
 * Where a round button's label starts, from the button's left: centred under it; when that would
 * come closer than [edge] to a side of the screen, aligned with the button's edge on that side;
 * past that, held at [edge].
 */
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
 * A choice in a vertical list (sources, servers): a quiet panel, lifted with a projector-white
 * outline when focused. Its icon stands in line with the name, turning white with focus; [badges]
 * say what the choice brings, in the technical voice, under a hairline; a chevron shows on focus
 * that it leads on. [descriptionLines] keeps choices shown side by side the same height.
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
) {
    val c = Hl.colors
    HlPanelButton(onClick, modifier.fillMaxWidth()) { focused ->
        Column(Modifier.padding(HlInset.card)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (focused) c.content else c.contentSecondary,
                        modifier = Modifier.size(HlSize.discIcon),
                    )
                    Spacer(Modifier.width(HlSpace.s))
                }
                Text(title, style = Hl.type.lead, color = c.content, modifier = Modifier.weight(1f))
                Icon(
                    HlIcons.Chevron,
                    contentDescription = null,
                    tint = c.content,
                    modifier = Modifier.size(HlSize.icon).alpha(if (focused) 1f else 0f),
                )
            }
            if (description != null) {
                Spacer(Modifier.height(HlSpace.xs))
                Text(description, style = Hl.type.caption, color = c.contentSecondary, minLines = descriptionLines)
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
 * Exclusive options in one pill track: where you are (home navigation, seasons, NFO views) or
 * what is chosen (protocol, content type). Focus is projector white, as everywhere: the option
 * under focus is the white pill. The current option sits on the track's quiet pill, one fill step
 * up, its words in projector white while the others read in grey; that pill slides to the option
 * chosen. Focus entering the track, from any side, lands on the current option, never on the
 * nearest one; to accessibility services the options are tabs, the current one selected. With
 * [selectOnFocus], moving onto an option selects it, as tabs over content do on TV. [scrollable]
 * lets a long track (seasons) scroll within the screen. On a picture ([OnArtwork]) the track is
 * a dark plate, so it reads over any image. [enterAt] is where focus enters when it is not the current
 * option: the option a screen sends you back to (the library just left).
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
) {
    val places = remember(options.size) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    // A long track (seasons) scrolls as one piece: the pill moves with the options. Its ends keep
    // room for an option growing under focus.
    val scroll = rememberScrollState()
    val content = if (scrollable) Modifier.horizontalScroll(scroll).padding(horizontal = HlSpace.xxs) else Modifier

    val well = groundPlate() ?: Hl.colors.content.copy(alpha = HlAlpha.FILL_FAINT)
    val track = modifier
        .background(well, HlShapes.control)
        .padding(vertical = HlSpace.xxs, horizontal = if (scrollable) HlSpace.none else HlSpace.xxs)
    RowScroll {
        Box(track) {
            Box(content) {
                // One quiet pill, under the options, that slides to the option chosen: where you are.
                places[selected]?.let { (x, w) ->
                    val slide = tween<Float>(HlMotion.STANDARD, easing = HlMotion.emphasized)
                    val left by animateFloatAsState(x, slide, label = "pillX")
                    val width by animateFloatAsState(w, slide, label = "pillWidth")
                    Box(
                        Modifier
                            .offset { IntOffset(left.roundToInt(), 0) }
                            // Its width read while measuring, as its place is while placing: the pill
                            // slides without recomposing the track. As `width()` measures it.
                            .layout { measurable, constraints ->
                                val px = width.toDp().roundToPx().coerceAtLeast(0)
                                val placeable = measurable.measure(constraints.constrain(Constraints(minWidth = px, maxWidth = px)))
                                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                            }
                            .height(HlSize.segment)
                            .background(Hl.colors.content.copy(alpha = HlAlpha.FILL_STRONG), HlShapes.control),
                    )
                }
                Row(Modifier.enterOn(requesters, enterAt).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                    options.forEachIndexed { i, label ->
                        SegmentItem(
                            label,
                            selected = i == selected,
                            onClick = { onSelect(i) },
                            onFocused = { if (selectOnFocus) onSelect(i) },
                            modifier = Modifier
                                .focusRequester(requesters[i])
                                .onPlaced { places[i] = it.positionInParent().x to it.size.width.toFloat() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A group that focus enters on the option at [current], from any side: where you are, not the
 * nearest option. With no current option, the nearest one.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.enterOn(requesters: List<FocusRequester>, current: Int): Modifier = this
    .focusProperties { enter = { requesters.getOrNull(current) ?: FocusRequester.Default } }
    .focusGroup()

/**
 * One option of a [HlSegmented] track; never used alone. Focus is the white pill, as on every
 * control. At rest the current option's words are projector white, on the track's quiet pill
 * drawn under the options; the others are grey, on the room or on the track's plate over an image.
 */
@Composable
private fun SegmentItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    val fade = tween<Color>(HlMotion.STANDARD, easing = HlMotion.emphasized)
    val rest by animateColorAsState(if (selected) c.content else c.contentSecondary, fade, label = "segmentText")
    Surface(
        onClick = onClick,
        modifier = modifier
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .height(HlSize.segment)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(HlShapes.control),
        scale = FocusStyle.scale(FocusStyle.Fill.Pill),
        colors = FocusStyle.fill(rest = Color.Transparent, restContent = rest),
    ) {
        Text(
            text,
            style = Hl.type.entry,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = HlControl.tabPaddingH),
        )
    }
}
