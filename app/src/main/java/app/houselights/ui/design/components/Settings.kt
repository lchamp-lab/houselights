package app.houselights.ui.design.components

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.HlSettingRowDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

/**
 * A place in the settings' list (DESIGN.md, Secondary screens): its icon and its name alone, grey until it is the
 * open one ([selected]: white, its icon lit, raspberry on focus's white); focus whitens it and tells [onFocus], OK
 * [onClick]. Its plate hangs [HlSpace.s] past the column's start, so its icon lines up with the title above.
 */
@Composable
fun HlNavItem(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = onFocus,
) {
    val c = Hl.colors
    HlRowSurface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .hang(HlSpace.s)
            .onFocusChanged { if (it.isFocused) onFocus() }
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
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(HlSize.icon))
            Spacer(Modifier.width(HlSpace.s))
            Text(
                title,
                style = Hl.type.entry,
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
 * One setting, [HlSize.row] tall: its name in `entry` and its [description] in secondary `meta` on one line, its
 * value on the right, in `meta` or, for a format or a figure, `jaquette` ([valueStyle]); with [onClick] a control,
 * white with focus, else a value it only states. [valueLit] (a user choice) and [valueAttention] set the value in
 * ember; [trailing] replaces the value. Disabled, it sits in a well, in a pane's plate only: sheets omit
 * unavailable actions. With [valueBreaks], a value of parts (" · ") takes a line each where on one line it would cut
 * the name or the description. With [progress], its description is a count after the ember line, in the words' ink
 * (the Counter, as [ScanProgress] draws it): a raspberry mark on focus's white, the part to come pale. With
 * [valueLight], the value is a state its light comes before ("Vérification…", "Ne répond pas", "Connexion…"), ranged
 * right as values are, or the light alone without a value (a search under way): quiet, never ember.
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
    progress: Float? = null,
    valueLight: StatusState? = null,
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
                Text(title, style = Hl.type.entry, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (description != null) RowDescription(description, progress, focused, enabled)
            }
            Spacer(Modifier.width(HlSpace.l))
            if (trailing != null) trailing() else RowEnd(shown, valueStyle, valueLit, valueAttention, focused, enabled, valueLight)
        }
    }

    @Composable
    fun Content(focused: Boolean) {
        val shown = value?.takeIf { valueBreaks && VALUE_PARTS in it }
        if (shown == null) return Line(focused, value)
        ValueLines(shown, valueStyle, title, description, icon != null) { Line(focused, it) }
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

/**
 * A row's [description] under its name, on one line so rows keep one height; with [progress], a count at the end of its
 * line, in the words' ink: a figure that changes, not a description.
 */
@Composable
private fun ColumnScope.RowDescription(description: String, progress: Float?, focused: Boolean, enabled: Boolean) {
    Spacer(Modifier.height(HlSpace.xxs))
    if (progress == null) {
        Text(
            description,
            style = Hl.type.meta,
            color = FocusStyle.quietInk(focused, enabled, lit = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProgressLine(progress.coerceAtLeast(PROGRESS_SLIVER), Modifier.width(HlSize.progressShort), focused = focused)
        Spacer(Modifier.width(HlSpace.s))
        Text(description, style = Hl.type.meta, color = FocusStyle.ink(focused), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Between the parts of a value that may take a line each ([HlSettingRow]'s valueBreaks). */
private const val VALUE_PARTS = " · "

/**
 * [line] with [value], of parts, as a row shows it beside its [title] and [description]: on one line, or its parts on
 * a line each where one line would cut them.
 */
@Composable
private fun ValueLines(
    value: String,
    style: TextStyle,
    title: String,
    description: String?,
    icon: Boolean,
    line: @Composable (String) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    fun wide(text: String, style: TextStyle) = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
    val words = maxOf(wide(title, Hl.type.entry), description?.let { wide(it, Hl.type.meta) } ?: 0)
    // The row's sides, the room before its value, and its icon.
    val beside = HlSpace.m * 2 + HlSpace.l + if (icon) HlSize.icon + HlSpace.m else 0.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room = with(LocalDensity.current) { (maxWidth - beside).roundToPx() }
        line(if (words + wide(value, style) <= room) value else value.replace(VALUE_PARTS, "\n"))
    }
}

/** What ends a row: its value ([SettingValue]), else a state's [light] alone (a search under way), else nothing. */
@Composable
private fun RowEnd(
    value: String?,
    style: TextStyle,
    lit: Boolean,
    attention: Boolean,
    focused: Boolean,
    enabled: Boolean,
    light: StatusState?,
) {
    when {
        value != null -> SettingValue(value, style, lit, attention, focused, enabled, light)
        light != null -> StatusLight(light, ink = if (focused) Hl.colors.onFocusSecondary else null)
    }
}

/**
 * A setting's value in [style], quiet; in ember when [lit], or after the attention mark; after a state's [light]
 * otherwise, in its own quiet inks at rest as a connection's ([ConnectionState]); a line per line it has.
 */
@Composable
private fun SettingValue(
    value: String,
    style: TextStyle,
    lit: Boolean,
    attention: Boolean,
    focused: Boolean,
    enabled: Boolean,
    light: StatusState? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (attention) {
            StatusLight(StatusState.Attention, ink = if (focused) LocalContentColor.current else null)
            Spacer(Modifier.width(HlSpace.xs))
        } else if (light != null) {
            StatusLight(light, ink = if (focused) Hl.colors.onFocusSecondary else null)
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

/**
 * A setting's choices in a side sheet, what they are about in its [facts] (a show's title and year), focus on the
 * current one; choosing applies and closes, Back cancels.
 */
@Composable
fun HlChoiceSheet(
    title: String,
    options: List<ChoiceOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    facts: List<String?> = emptyList(),
) {
    val current = remember { FocusRequester() }
    HlSideSheet(title, onDismiss, modifier, facts = facts, description = description, initialFocus = current) { sheet ->

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

/** The current choice's check after what a row states, [HlSpace.m] from it, its place kept on rows not [chosen]. */
@Composable
internal fun RowScope.ChoicePlace(chosen: Boolean) {
    Spacer(Modifier.width(HlSpace.m))
    Box(Modifier.size(HlSize.icon)) { if (chosen) ChoiceCheck() }
}

/** A connection's security as a row states it: encrypted, plain, being checked, or no answer. */
enum class ConnectionSecurity { Encrypted, Plain, Checking, Silent }

/**
 * A connection's [security] beside a row's name, its words in `meta` after its mark: the lock, closed and white when
 * encrypted, open and grey when plain; the quiet dot while it is checked, the ring when nothing answers. Never ember:
 * none is an error or a choice. As wide as the longest of the four at the viewer's text size, so states line up
 * from row to row; focus's inks on a focused row.
 */
@Composable
fun ConnectionState(security: ConnectionSecurity, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val focused = LocalContentColor.current == c.onFocus
    val encrypted = security == ConnectionSecurity.Encrypted
    val ink = when {
        focused -> if (encrypted) c.onFocus else c.onFocusSecondary
        encrypted -> c.content
        else -> c.contentSecondary
    }
    val words = ConnectionSecurity.entries.map { stringResource(it.words) }
    val style = Hl.type.meta
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(words, style, density) {
        with(density) { words.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }.toDp() }
    }
    Row(modifier.width(HlSize.statusLight + HlSpace.xs + widest), verticalAlignment = Alignment.CenterVertically) {
        when (security) {
            ConnectionSecurity.Encrypted, ConnectionSecurity.Plain -> Icon(
                if (encrypted) HlIcons.Lock else HlIcons.LockOpen,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(HlSize.statusLight),
            )
            // Their own quiet inks at rest, as a state's light.
            ConnectionSecurity.Checking -> StatusLight(StatusState.Pending, ink = ink.takeIf { focused })
            ConnectionSecurity.Silent -> StatusLight(StatusState.Off, ink = ink.takeIf { focused })
        }
        Spacer(Modifier.width(HlSpace.xs))
        Text(words[security.ordinal], style = style, color = ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@get:StringRes
private val ConnectionSecurity.words: Int
    get() = when (this) {
        ConnectionSecurity.Encrypted -> R.string.server_encrypted
        ConnectionSecurity.Plain -> R.string.server_plain
        ConnectionSecurity.Checking -> R.string.checking
        ConnectionSecurity.Silent -> R.string.server_silent
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
