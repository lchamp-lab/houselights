package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.ui.design.HlMenuDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.scrollEdges
import app.houselights.ui.design.tryRequestFocus
import kotlin.math.roundToInt

data class MenuOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

data class MenuSection(val title: String?, val options: List<MenuOption>)

/**
 * A button opening a small menu of exclusive options, below it or above when space is short. With
 * [closeOnSelect] (sorting) a choice applies once the menu has gone; without it (filters) it stays open.
 */
@Composable
fun HlMenuButton(
    text: String,
    icon: ImageVector,
    sections: List<MenuSection>,
    modifier: Modifier = Modifier,
    detail: String? = null,
    emphasis: Emphasis = Emphasis.Secondary,
    detailLit: Boolean = false,
    closeOnSelect: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    // The larger space available to the menu, above or below the button.
    var room by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val position = remember(density) { MenuPosition(density) }
    Box(modifier.onGloballyPositioned { room = position.room(it) }) {
        HlButton(text, onClick = { open = true }, icon = icon, detail = detail, emphasis = emphasis, detailLit = detailLit)
        if (open) {
            val motion = rememberSheetMotion(onDismiss = { open = false })
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { motion.sheet.dismiss() },
                properties = PopupProperties(focusable = true),
            ) {
                MenuPanel(sections, maxHeight = with(density) { room.toDp() }, motion, position, closeOnSelect)
            }
        }
    }
}

/** Places a menu at its button's end edge, below if it fits, else above; capped at the larger space. */
private class MenuPosition(density: Density) : PopupPositionProvider {
    private val gap = with(density) { HlSpace.xs.roundToPx() }
    private val safeH = with(density) { HlSpace.safeH.roundToPx() }
    private val safeV = with(density) { HlSpace.safeV.roundToPx() }

    /** The button corner the menu grows from: the end corner, at the bottom or the top. */
    var origin by mutableStateOf(TransformOrigin(1f, 0f))
        private set

    /** The larger of the spaces above and below the button within the safe area, in px. */
    fun room(button: LayoutCoordinates): Int {
        val bounds = button.boundsInRoot()
        val above = bounds.top.roundToInt() - gap - safeV
        val below = button.findRootCoordinates().size.height - safeV - bounds.bottom.roundToInt() - gap
        return maxOf(above, below, 0)
    }

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val below = windowSize.height - safeV - anchorBounds.bottom - gap
        val opensBelow = popupContentSize.height <= below
        val y = if (opensBelow) anchorBounds.bottom + gap else anchorBounds.top - gap - popupContentSize.height
        val ltr = layoutDirection == LayoutDirection.Ltr
        val x = if (ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val corner = TransformOrigin(if (ltr) 1f else 0f, if (opensBelow) 0f else 1f)
        if (corner != origin) origin = corner
        return IntOffset(
            x.coerceIn(safeH, maxOf(safeH, windowSize.width - safeH - popupContentSize.width)),
            y.coerceIn(safeV, maxOf(safeV, windowSize.height - safeV - popupContentSize.height)),
        )
    }
}

@Composable
private fun MenuPanel(sections: List<MenuSection>, maxHeight: Dp, motion: SheetMotion, position: MenuPosition, closeOnSelect: Boolean) {
    val c = Hl.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    HlPanel(
        Modifier
            .width(HlMenuDefaults.width)
            .heightIn(max = maxHeight)
            .stillWhileLeaving(motion)
            .graphicsLayer {
                val scale = HlMotion.ENTER_SCALE + (1f - HlMotion.ENTER_SCALE) * motion.travel
                alpha = motion.opacity
                scaleX = scale
                scaleY = scale
                transformOrigin = position.origin
                // Each drawing fades on its own: no offscreen pass.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        level = PanelLevel.Raised,
        padding = HlMenuDefaults.inset,
        shape = HlShapes.panel,
    ) {
        val scroll = rememberScrollState()
        Column(Modifier.scrollEdges(scroll, c.glass).verticalScroll(scroll)) {
        sections.forEachIndexed { s, section ->
            if (s > 0) {
                Spacer(Modifier.height(HlSpace.s))
                HlDivider()
                Spacer(Modifier.height(HlSpace.s))
            }
            section.title?.let {
                Text(
                    it,
                    style = Hl.type.group,
                    color = c.contentSecondary,
                    modifier = Modifier.padding(horizontal = HlSpace.s, vertical = HlSpace.xxs),
                )
            }
            val initial = section.options.indexOfFirst { it.selected }.coerceAtLeast(0)
            section.options.forEachIndexed { i, option ->
                // Focus starts on the current choice of the first section.
                val mod = if (s == 0 && i == initial) Modifier.focusRequester(first) else Modifier
                // A closing choice applies after the menu has gone, so the change is seen on its own.
                val choose = if (closeOnSelect) ({ motion.sheet.dismiss(option.onSelect) }) else option.onSelect
                MenuItem(option.label, option.selected, onClick = choose, modifier = mod)
            }
        }
        }
    }
}

/** An option: the chosen one white with its ember check, raspberry on focus's white; the others grey. */
@Composable
private fun MenuItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Hl.colors
    HlRowSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(HlSize.controlCompact).semantics { this.selected = selected },
        restContent = if (selected) c.content else c.contentSecondary,
        shape = HlShapes.control,
    ) { focused ->
        Row(Modifier.fillMaxHeight().padding(horizontal = HlSpace.s), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(HlSize.icon)) {
                if (selected) {
                    val ink = if (focused) c.emberOnLight else c.ember
                    Icon(HlIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(HlSize.icon))
                }
            }
            Spacer(Modifier.width(HlSpace.s))
            Text(label, style = Hl.type.entry)
        }
    }
}
