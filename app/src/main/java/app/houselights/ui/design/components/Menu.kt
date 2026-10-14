package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

data class MenuOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

data class MenuSection(val title: String?, val options: List<MenuOption>)

/**
 * A button that opens a small menu of exclusive options, grouped in sections.
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
    var anchorHeight by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { HlSpace.xs.roundToPx() }
    Box(modifier.onSizeChanged { anchorHeight = it.height }) {
        HlButton(text, onClick = { open = true }, icon = icon, detail = detail, emphasis = emphasis)
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, anchorHeight + gap),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                MenuPanel(sections, onDone = { if (closeOnSelect) open = false })
            }
        }
    }
}

@Composable
private fun MenuPanel(sections: List<MenuSection>, onDone: () -> Unit) {
    val c = Hl.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    HlPanel(
        Modifier.width(HlLayout.menuWidth).heightIn(max = HlLayout.menuMaxHeight),
        level = PanelLevel.Raised,
        padding = HlSpace.s,
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
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(HlShapes.field),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) c.content else c.contentSecondary,
            focusedContainerColor = c.focus,
            focusedContentColor = c.onFocus,
        ),
        interactionSource = interaction,
    ) {
        Row(Modifier.padding(horizontal = HlSpace.s, vertical = HlControl.menuItemPaddingV), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(HlSize.icon)) {
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = if (focused) c.onFocus else c.tungsten, modifier = Modifier.size(HlSize.icon))
                }
            }
            Spacer(Modifier.width(HlSpace.s))
            Text(label, style = Hl.type.label)
        }
    }
}
