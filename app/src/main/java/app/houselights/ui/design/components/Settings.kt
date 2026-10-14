package app.houselights.ui.design.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
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
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onFocused,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(HlShapes.row),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) c.content.copy(alpha = HlAlpha.CONTROL_FILL) else Color.Transparent,
            contentColor = if (selected) c.content else c.contentSecondary,
            focusedContainerColor = c.focus,
            focusedContentColor = c.onFocus,
            pressedContainerColor = c.focus,
            pressedContentColor = c.onFocus,
        ),
        interactionSource = interaction,
    ) {
        Row(Modifier.padding(horizontal = HlSpace.m, vertical = HlSpace.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected && !focused) c.tungsten else LocalContentColor.current,
                modifier = Modifier.size(HlSize.icon),
            )
            Spacer(Modifier.width(HlSpace.s))
            Column {
                Text(title, style = Hl.type.entry, maxLines = 1)
                if (summary != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(
                        summary,
                        style = Hl.type.detail,
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
                style = Hl.type.caption,
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
 * the user chose or something that needs attention, in tungsten. [trailing] replaces the value
 * with another element (a status, badges); [leading] replaces the icon with a preview of what the
 * row changes ([RowPreview]).
 */
@Composable
fun HlSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    description: String? = null,
    icon: ImageVector? = null,
    valueLit: Boolean = false,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
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
                    // Secondary at rest: tertiary would fall to 4.09:1 on a row inside a raised sheet.
                    Text(
                        description,
                        style = Hl.type.detail,
                        color = if (focused) c.onFocusSecondary else c.contentSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(HlSpace.l))
            when {
                trailing != null -> trailing()
                value != null -> Text(
                    value,
                    style = Hl.type.meta,
                    color = when {
                        focused -> LocalContentColor.current
                        valueLit -> c.tungsten
                        else -> c.contentSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = HlLayout.rowValue),
                )
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
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(HlShapes.row),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.content.copy(alpha = HlAlpha.WELL),
            contentColor = c.content,
            focusedContainerColor = c.focus,
            focusedContentColor = c.onFocus,
            pressedContainerColor = c.focus,
            pressedContentColor = c.onFocus,
        ),
        interactionSource = interaction,
    ) {
        Content(focused)
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
                trailing = if (i == selected) ({ Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint(), modifier = Modifier.size(HlSize.icon)) }) else null,
                modifier = if (i == selected) Modifier.focusRequester(current) else Modifier,
            )
        }
    }
}

/** The check of the current choice: tungsten at rest, the focused content colour on a lit row. */
@Composable
private fun checkTint(): Color {
    val content = LocalContentColor.current
    return if (content == Hl.colors.onFocus) content else Hl.colors.tungsten
}
