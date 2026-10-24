package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/**
 * A place in the settings' list (DESIGN.md, Secondary screens): its icon and its name alone, grey until it is the
 * open one ([selected]: white, its icon lit, raspberry on focus's white); focus whitens it and tells [onFocused], OK
 * [onClick]. Its plate hangs [HlSpace.s] past the column's start, so its icon lines up with the title above.
 */
@Composable
fun HlNavItem(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = onFocused,
) {
    val c = Hl.colors
    HlRowSurface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .hang(HlSpace.s)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { this.selected = selected },
    ) { focused ->
        Row(
            Modifier.heightIn(min = HlSize.navRow).padding(horizontal = HlSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = when {
                selected && focused -> c.emberOnLight
                selected -> c.ember
                focused -> LocalContentColor.current
                else -> c.contentTertiary
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(HlSize.tileIcon))
            Spacer(Modifier.width(HlSpace.s))
            Text(
                title,
                style = Hl.type.lead,
                color = if (focused || selected) LocalContentColor.current else c.contentSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Setting rows on one plate ([HlPlate]), [title] in `group` over it: glass on the room, the film's glass in a sheet.
 * Rows lie in it [HlInset.group] from its edge, one after the other, no rule between them.
 */
@Composable
fun HlSettingGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) GroupTitle(title)
        HlPlate(Modifier.fillMaxWidth(), padding = PaddingValues(HlInset.group), content = content)
    }
}

/**
 * One setting, [HlSize.row] tall: its name in `lead` and its [description] in secondary `meta` on one line, its
 * value on the right, in `meta` or, for a format or a figure, `jaquette` ([valueStyle]); with [onClick] a control,
 * white with focus, else a value it only states. [valueLit] (a user choice) and [valueAttention] set the value in
 * ember; [trailing] replaces the value. Disabled, it sits in a well, in a pane's plate only: sheets omit
 * unavailable actions. With [valueBreaks], a value of parts (" · ") takes a line each where on one line it would cut
 * the name or the description.
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
    valueStyle: TextStyle = Hl.type.meta,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    valueBreaks: Boolean = false,
) {
    val c = Hl.colors
    val least = if (onClick == null) HlSize.control else HlSize.row
    @Composable
    fun Line(focused: Boolean, shown: String?) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = least).padding(horizontal = HlSpace.m, vertical = HlSettingRowDefaults.paddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                // Quiet at rest.
                val tint = if (focused || !enabled) LocalContentColor.current else c.contentSecondary
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(HlSize.icon))
                Spacer(Modifier.width(HlSpace.m))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = Hl.type.lead, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (description != null) {
                    Spacer(Modifier.height(HlSpace.xxs))
                    // One line, so rows keep one height.
                    Text(
                        description,
                        style = Hl.type.meta,
                        color = FocusStyle.quietInk(focused, enabled, lit = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(HlSpace.l))
            when {
                trailing != null -> trailing()
                shown != null -> SettingValue(shown, valueStyle, valueLit, valueAttention, focused, enabled)
            }
        }
    }

    @Composable
    fun Content(focused: Boolean) {
        if (valueBreaks && value != null && VALUE_PARTS in value) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Line(focused, valueLines(value, valueStyle, title, description, icon != null, maxWidth))
            }
        } else {
            Line(focused, value)
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

/** Between the parts of a value that may take a line each ([HlSettingRow]'s valueBreaks). */
private const val VALUE_PARTS = " · "

/**
 * [value] as a row [width] wide shows it beside its [title] and [description]: on one line, or its parts on a line each
 * where one line would cut them.
 */
@Composable
private fun valueLines(value: String, style: TextStyle, title: String, description: String?, icon: Boolean, width: Dp): String {
    val measurer = rememberTextMeasurer()
    fun wide(text: String, style: TextStyle) = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
    val words = maxOf(wide(title, Hl.type.lead), description?.let { wide(it, Hl.type.meta) } ?: 0)
    // The row's sides, the room before its value, and its icon.
    val beside = HlSpace.m * 2 + HlSpace.l + if (icon) HlSize.icon + HlSpace.m else 0.dp
    val room = with(LocalDensity.current) { (width - beside).roundToPx() }
    return if (words + wide(value, style) <= room) value else value.replace(VALUE_PARTS, "\n")
}

/** A setting's value in [style], quiet; in ember when [lit], or after the attention mark; a line per line it has. */
@Composable
private fun SettingValue(value: String, style: TextStyle, lit: Boolean, attention: Boolean, focused: Boolean, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (attention) {
            StatusLight(StatusState.Attention, ink = if (focused) LocalContentColor.current else null)
            Spacer(Modifier.width(HlSpace.xs))
        }
        Text(
            value,
            style = style,
            color = FocusStyle.quietInk(focused, enabled, lit || attention, onFocus = LocalContentColor.current),
            textAlign = TextAlign.End,
            maxLines = value.lines().size,
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
        HlSettingGroup(null) {
            options.forEachIndexed { i, option ->
                HlSettingRow(
                    title = option.label,
                    description = option.description,
                    onClick = {
                        onSelect(i)
                        sheet.dismiss()
                    },
                    trailing = if (i == selected) ({ ChoiceCheck() }) else null,
                    modifier = (if (i == selected) Modifier.focusRequester(current) else Modifier)
                        .semantics { this.selected = i == selected },
                )
            }
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

/**
 * A group's [title] in `group`, set from its baseline: [HlSpace.m] under what is above it (a block [HlSpace.l] away),
 * its plate [HlSpace.s] under it.
 */
@Composable
internal fun GroupTitle(title: String) {
    Text(
        title,
        style = Hl.type.group,
        color = Hl.colors.contentSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.paddingFromBaseline(top = HlSpace.m, bottom = HlSpace.s),
    )
}
