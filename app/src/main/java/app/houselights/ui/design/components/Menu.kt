package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import kotlin.math.roundToInt

data class MenuOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

data class MenuSection(val title: String?, val options: List<MenuOption>)

/**
 * A button that opens a small menu of exclusive options, grouped in sections, against its end
 * edge: below the button when the room down to the safe area holds it, else above. It never
 * leaves the safe area; a longer menu scrolls inside.
 * With [closeOnSelect] (sorting) a choice closes the menu; without it (filters) the menu
 * stays open so choices can be combined. Back always closes it.
 */
@Composable
fun HlMenuButton(
    text: String,
    icon: ImageVector,
    sections: List<MenuSection>,
    modifier: Modifier = Modifier,
    detail: String? = null,
    emphasis: Emphasis = Emphasis.Secondary,
    closeOnSelect: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    // The most room the screen leaves a menu, above or below the button.
    var room by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val position = remember(density) { MenuPosition(density) }
    Box(modifier.onGloballyPositioned { room = position.room(it) }) {
        HlButton(text, onClick = { open = true }, icon = icon, detail = detail, emphasis = emphasis)
        if (open) {
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                MenuPanel(sections, maxHeight = with(density) { room.toDp() }, onDone = { if (closeOnSelect) open = false })
            }
        }
    }
}

/**
 * Where a menu opens: against the end edge of its button, below it when the room down to the
 * safe area holds the menu, else above. The menu is no taller than the larger of the two rooms,
 * so it fits on one side.
 */
private class MenuPosition(density: Density) : PopupPositionProvider {
    private val gap = with(density) { HlSpace.xs.roundToPx() }
    private val safeH = with(density) { HlSpace.safeH.roundToPx() }
    private val safeV = with(density) { HlSpace.safeV.roundToPx() }

    /** The larger of the rooms above and below the button, inside the safe area. */
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
        val y = if (popupContentSize.height <= below) anchorBounds.bottom + gap else anchorBounds.top - gap - popupContentSize.height
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        return IntOffset(
            x.coerceIn(safeH, maxOf(safeH, windowSize.width - safeH - popupContentSize.width)),
            y.coerceIn(safeV, maxOf(safeV, windowSize.height - safeV - popupContentSize.height)),
        )
    }
}

@Composable
private fun MenuPanel(sections: List<MenuSection>, maxHeight: Dp, onDone: () -> Unit) {
    val c = Hl.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    HlPanel(
        Modifier.width(HlLayout.menuWidth).heightIn(max = maxHeight),
        level = PanelLevel.Raised,
        padding = HlInset.tight,
        shape = HlShapes.sheet,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
        sections.forEachIndexed { s, section ->
            if (s > 0) {
                Spacer(Modifier.height(HlSpace.s))
                HlDivider()
                Spacer(Modifier.height(HlSpace.s))
            }
            section.title?.let {
                Text(it, style = Hl.type.caption, color = c.contentSecondary, modifier = Modifier.padding(horizontal = HlSpace.s, vertical = HlSpace.xxs))
            }
            val initial = section.options.indexOfFirst { it.selected }.coerceAtLeast(0)
            section.options.forEachIndexed { i, option ->
                // Focus starts on the current choice of the first section.
                val mod = if (s == 0 && i == initial) Modifier.focusRequester(first) else Modifier
                MenuItem(option.label, option.selected, onClick = { option.onSelect(); onDone() }, modifier = mod)
            }
        }
        }
    }
}

@Composable
private fun MenuItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(HlSize.segment).semantics { this.selected = selected },
        shape = ClickableSurfaceDefaults.shape(HlShapes.row),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) c.content else c.contentSecondary,
            focusedContainerColor = c.focus,
            focusedContentColor = c.onFocus,
        ),
        interactionSource = interaction,
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = HlSpace.s), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(HlSize.icon)) {
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = if (focused) c.onFocus else c.tungsten, modifier = Modifier.size(HlSize.icon))
                }
            }
            Spacer(Modifier.width(HlSpace.s))
            Text(label, style = Hl.type.entry)
        }
    }
}
