package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.houselights.ui.design.HlSettingRowDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.Ground
import app.houselights.ui.design.LocalGround

/**
 * A settings navigation entry: its icon, name and current value below ("8 films", "4K 60 Hz"), on the room
 * until focus whitens it; focus selects it. The [selected] pane's icon is lit, raspberry on focus's white.
 */
@Composable
fun HlNavItem(
    icon: ImageVector,
    title: String,
    summary: String?,
    selected: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    HlRowSurface(
        onClick = onFocused,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() }.semantics { this.selected = selected },
    ) { focused ->
        Row(
            Modifier.heightIn(min = HlSize.row).padding(horizontal = HlSpace.m, vertical = HlSettingRowDefaults.paddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = when {
                selected && focused -> c.emberOnLight
                selected -> c.ember
                focused -> LocalContentColor.current
                else -> c.contentSecondary
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(HlSize.icon))
            Spacer(Modifier.width(HlSpace.m))
            Column {
                Text(title, style = Hl.type.entry, maxLines = 1)
                if (summary != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(
                        summary,
                        style = Hl.type.caption,
                        color = FocusStyle.quietInk(focused, enabled = true, lit = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A titled group of setting rows. */
@Composable
fun HlSettingGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
        if (title != null) {
            Text(
                title,
                style = Hl.type.group,
                color = Hl.colors.contentSecondary,
                modifier = Modifier.padding(start = HlSpace.l, bottom = HlSpace.xs),
            )
        }
        content()
    }
}

/**
 * One setting: name and description left, value right, on what holds it; with [onClick] a control, else
 * read-only. [valueLit] (a user choice) and [valueAttention] set the value in ember; [trailing] replaces the
 * value, [leading] the icon ([RowPreview]); a [chevron] shows with focus. Disabled rows belong on the room
 * only, in a well: sheets omit unavailable actions. In a sheet, an action row is [HlSize.sheetRow] tall.
 */
@Composable
fun HlSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    description: String? = null,
    icon: ImageVector? = null,
    valueLit: Boolean = false,
    valueAttention: Boolean = false,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = Hl.colors
    val least = when {
        onClick == null -> HlSize.control
        LocalGround.current == Ground.Glass -> HlSize.sheetRow
        else -> HlSize.row
    }
    @Composable
    fun Content(focused: Boolean) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = least).padding(horizontal = HlInset.card, vertical = HlSettingRowDefaults.paddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingLead(leading, icon, focused, enabled)
            Column(Modifier.weight(1f)) {
                Text(title, style = Hl.type.entry, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (description != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(
                        description,
                        style = Hl.type.caption,
                        color = FocusStyle.quietInk(focused, enabled, lit = false),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(HlSpace.l))
            when {
                trailing != null -> trailing()
                value != null -> SettingValue(value, valueLit, valueAttention, focused, enabled)
            }
            if (chevron) {
                Spacer(Modifier.width(HlSpace.s))
                // Where it leads shows with focus; its room is kept, so the value stays put.
                Box(Modifier.size(HlSize.icon)) {
                    if (focused) Icon(HlIcons.Chevron, contentDescription = null, modifier = Modifier.size(HlSize.icon))
                }
            }
        }
    }

    if (onClick == null) {
        CompositionLocalProvider(LocalContentColor provides c.content) {
            Box(modifier.fillMaxWidth()) { Content(focused = false) }
        }
        return
    }
    HlRowSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        disabled = c.content.copy(alpha = HlAlpha.FILL_FAINT),
        disabledContent = c.contentTertiary,
    ) { focused ->
        // A disabled row keeps its rest colours under focus.
        Content(focused && enabled)
    }
}

/** A setting's [leading] picture or its [icon], before its name; the icon quiet at rest. */
@Composable
private fun SettingLead(leading: (@Composable () -> Unit)?, icon: ImageVector?, focused: Boolean, enabled: Boolean) {
    if (leading != null) {
        leading()
        Spacer(Modifier.width(HlSpace.m))
    } else if (icon != null) {
        val tint = if (focused || !enabled) LocalContentColor.current else Hl.colors.contentSecondary
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(HlSize.icon))
        Spacer(Modifier.width(HlSpace.m))
    }
}

/** A setting's value, quiet; in ember when [lit], or after the attention mark. */
@Composable
private fun SettingValue(value: String, lit: Boolean, attention: Boolean, focused: Boolean, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (attention) {
            StatusLight(StatusState.Attention, ink = if (focused) LocalContentColor.current else null)
            Spacer(Modifier.width(HlSpace.xs))
        }
        Text(
            value,
            style = Hl.type.meta,
            color = FocusStyle.quietInk(focused, enabled, lit || attention, onFocus = LocalContentColor.current),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = HlSettingRowDefaults.valueWidth),
        )
    }
}

/** One choice of a [HlChoiceSheet]. */
data class ChoiceOption(val label: String, val description: String? = null)

/** A setting's choices in a side sheet, focus on the current one; choosing applies and closes, Back cancels. */
@Composable
fun HlChoiceSheet(
    title: String,
    options: List<ChoiceOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
) {
    val current = remember { FocusRequester() }
    HlSideSheet(title, onDismiss, description = description, initialFocus = current) { sheet ->
        options.forEachIndexed { i, option ->
            HlSettingRow(
                title = option.label,
                description = option.description,
                onClick = {
                    onSelect(i)
                    sheet.dismiss()
                },
                chevron = false,
                trailing = if (i == selected) ({ ChoiceCheck() }) else null,
                modifier = (if (i == selected) Modifier.focusRequester(current) else Modifier)
                    .semantics { this.selected = i == selected },
            )
        }
    }
}

/** The current choice's check: ember at rest, raspberry on focus's white. */
@Composable
internal fun ChoiceCheck() {
    val c = Hl.colors
    val tint = if (LocalContentColor.current == c.onFocus) c.emberOnLight else c.ember
    Icon(HlIcons.Check, contentDescription = null, tint = tint, modifier = Modifier.size(HlSize.icon))
}
