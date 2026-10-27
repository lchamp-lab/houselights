package app.houselights.ui.design.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.Version
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.formatBytes
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.versionName

/**
 * A film's versions in a side sheet, on one plate: edition ("Version longue"), formats, size and runtime; the one
 * Lire plays has the ember check. OK chooses a version ([onChoose]); Back closes. Without [onChoose], for a server
 * that keeps no choice, the sheet only reads: it says the server plays its first copy, and OK does nothing.
 */
@Composable
fun VersionsSheet(
    item: MediaItem,
    versions: List<Version>,
    onChoose: ((Version) -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    HlSideSheet(
        stringResource(R.string.versions),
        onDismiss = onDismiss,
        modifier = modifier,

        facts = listOf(item.title, item.year?.toString()),
        description = if (onChoose == null) stringResource(R.string.versions_server_first) else null,
        initialFocus = first.takeIf { versions.isNotEmpty() },
    ) { sheet ->
        HlSettingGroup(null) {
            // Focus starts on the copy Lire plays.
            val start = versions.indexOfFirst { it.chosen }.coerceAtLeast(0)
            versions.forEachIndexed { i, version ->
                VersionRow(
                    name = versionName(version.edition, version.label, i),
                    version = version,
                    onChoose = onChoose?.let { choose -> { sheet.dismiss { choose(version) } } },
                    modifier = if (i == start) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

/**
 * One film version: [name], formats ([FormatLines]), size and runtime; the chosen one adds "Votre choix". Without
 * [onChoose], nothing was chosen and nothing is marked.
 */
@Composable
fun VersionRow(name: String, version: Version, onChoose: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val chosen = stringResource(R.string.version_chosen)
    val marked = version.chosen && onChoose != null
    val size = version.sizeBytes?.let { formatBytes(it) }
    val length = version.runtimeMs?.let(::formatDuration)
    val readAs = listOfNotNull(name, size, length, chosen.takeIf { marked }).joinToString(", ")
    HlRowSurface(
        onClick = onChoose,
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = readAs
            this.focused = focused
            if (onChoose != null) onClick { onChoose(); true }
        },
        interactionSource = interaction,
    ) { _ ->
        val ink = LocalContentColor.current
        Column(Modifier.fillMaxWidth().padding(horizontal = HlSpace.m, vertical = HlSpace.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = Hl.type.entry,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (marked) {
                    Spacer(Modifier.width(HlSpace.s))
                    Icon(
                        HlIcons.Check,
                        contentDescription = null,
                        tint = if (focused) c.emberOnLight else c.ember,
                        modifier = Modifier.size(HlSize.inlineIcon),
                    )
                    Spacer(Modifier.width(HlSpace.xxs))
                    Text(chosen, style = Hl.type.meta, color = if (focused) ink else c.content, maxLines = 1)
                }
            }
            Spacer(Modifier.height(HlSpace.xs))
            FormatLines(version.item.copy(media = version.media ?: version.item.media), ink = if (focused) ink else null, plate = false)
            Spacer(Modifier.height(HlSpace.xxs))
            FactRow(listOf(size, length), color = if (focused) ink else c.contentSecondary)
        }
    }
}
