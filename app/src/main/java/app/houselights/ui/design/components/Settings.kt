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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.houselights.ui.design.HlSettingRowDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

/**
 * An entry of a settings navigation: its name, and under it what it is set to now ("8 films",
 * "4K 60 Hz"), so the left column alone tells how things stand. Focus selects it.
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
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() },
        rest = if (selected) c.content.copy(alpha = HlAlpha.FILL) else Color.Transparent,
        restContent = if (selected) c.content else c.contentSecondary,
    ) { focused ->
        Row(Modifier.padding(horizontal = HlSpace.m, vertical = HlSpace.s), verticalAlignment = Alignment.CenterVertically) {
            // The current entry is white, as the current option of a track: where you are is not a state.
            Icon(icon, contentDescription = null, modifier = Modifier.size(HlSize.icon))
            Spacer(Modifier.width(HlSpace.s))
            Column {
                Text(title, style = Hl.type.entry, maxLines = 1)
                if (summary != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(
                        summary,
                        style = Hl.type.caption,
                        color = when {
                            focused -> c.onFocusSecondary
                            selected -> c.contentSecondary
                            else -> c.contentTertiary
                        },
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
 * One setting: its name (and a line of description if needed) on the left, what it is set to
 * on the right. With [onClick] the row is a control that turns projector white with focus and
 * leads somewhere ([chevron]); without it, the row only states a value. [valueLit] marks a value
 * the user chose, in ember; [valueAttention] one that needs attention, in ember after its mark. [trailing] replaces the value
 * with another element (a status, badges); [leading] replaces the icon with a preview of what the
 * row changes ([RowPreview]). Not [enabled], its words go tertiary and OK does nothing; focus
 * still reaches it, outlined. Only on the room: a sheet leaves out an action that cannot be taken.
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
    @Composable
    fun Content(focused: Boolean) {
        Row(
            // Rows that can be chosen are taller targets; rows that state a value stay compact.
            Modifier.fillMaxWidth().heightIn(min = if (onClick != null) HlSize.row else HlSize.control)
                .padding(horizontal = HlInset.card, vertical = HlInset.compact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(HlSpace.m))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(HlSize.icon))
                Spacer(Modifier.width(HlSpace.m))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = Hl.type.entry, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (description != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    // Secondary at rest: a description explains.
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
                value != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (valueAttention) {
                        StatusLight(StatusState.Attention, ink = if (focused) LocalContentColor.current else null)
                        Spacer(Modifier.width(HlSpace.xs))
                    }
                    Text(
                        value,
                        style = Hl.type.meta,
                        color = FocusStyle.quietInk(focused, enabled, valueLit || valueAttention, onFocus = LocalContentColor.current),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = HlSettingRowDefaults.valueWidth),
                    )
                }
            }
            if (chevron) {
                Spacer(Modifier.width(HlSpace.s))
                Icon(HlIcons.Chevron, contentDescription = null, modifier = Modifier.size(HlSize.icon))
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
        rest = c.content.copy(alpha = HlAlpha.FILL_FAINT),
        enabled = enabled,
        disabledContent = c.contentTertiary,
    ) { focused ->
        // A disabled row keeps its well under focus: its words stay as they are.
        Content(focused && enabled)
    }
}

/** One choice of a [HlChoiceSheet]. */
data class ChoiceOption(val label: String, val description: String? = null)

/**
 * The choices of a setting, in a side sheet opened from its row and headed by the setting's name
 * and [description]: focus starts on the current one; choosing applies it at once (the check moves)
 * and closes the sheet; Back closes it without changing anything.
 */
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

/**
 * The check of the current choice, at the end of its row: your choice, in ember at rest, the
 * focused content colour on a lit row. Sheets whose choices lead elsewhere mark the current one
 * with it too.
 */
@Composable
internal fun ChoiceCheck() {
    val content = LocalContentColor.current
    val tint = if (content == Hl.colors.onFocus) content else Hl.colors.ember
    Icon(HlIcons.Check, contentDescription = null, tint = tint, modifier = Modifier.size(HlSize.icon))
}
