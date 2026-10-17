package app.houselights.ui.design.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
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
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.formatBytes
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.versionName

/**
 * The copies of a film, beside its page ([HlSideSheet]): each by its edition ("Version longue"),
 * what its file holds in the technical voice, its size and length; the one Lire plays, your choice,
 * with its ember check. OK on a copy makes it your choice ([onChoose]); Back closes.
 */
@Composable
fun VersionsSheet(item: MediaItem, versions: List<Version>, onChoose: (Version) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    HlSideSheet(
        stringResource(R.string.versions),
        onDismiss = onDismiss,
        facts = listOf(item.title, item.year?.toString()),
        artwork = item.poster?.let { poster -> { PosterThumb(poster, width = HlSize.posterThumbLarge) } },
        initialFocus = first.takeIf { versions.isNotEmpty() },
    ) { sheet ->
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
            // Focus starts on the copy Lire plays.
            val start = versions.indexOfFirst { it.chosen }.coerceAtLeast(0)
            versions.forEachIndexed { i, version ->
                VersionRow(
                    name = versionName(version.edition, version.label, i),
                    version = version,
                    onChoose = { sheet.dismiss { onChoose(version) } },
                    modifier = if (i == start) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

/**
 * One copy of a film: its [name] in `entry`, its picture and its sound in the technical voice
 * ([FormatLines]), its size and its length; the one chosen says it, the ember check and "Votre
 * choix". White with focus, as every row; read as one line.
 */
@Composable
fun VersionRow(name: String, version: Version, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val chosen = stringResource(R.string.version_chosen)
    val size = version.sizeBytes?.let { formatBytes(it) }
    val length = version.runtimeMs?.let(::formatDuration)
    val readAs = listOfNotNull(name, size, length, chosen.takeIf { version.chosen }).joinToString(", ")
    HlRowSurface(
        onClick = onChoose,
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = readAs
            this.focused = focused
            onClick { onChoose(); true }
        },
        interactionSource = interaction,
    ) { _ ->
        val ink = LocalContentColor.current
        Column(Modifier.fillMaxWidth().padding(horizontal = HlInset.card, vertical = HlSpace.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = Hl.type.entry,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (version.chosen) {
                    Spacer(Modifier.width(HlSpace.s))
                    // Your choice, written one way: the check in ember, and its word.
                    Icon(
                        HlIcons.Check,
                        contentDescription = null,
                        tint = if (focused) ink else c.ember,
                        modifier = Modifier.size(HlSize.inlineIcon),
                    )
                    Spacer(Modifier.width(HlSpace.xxs))
                    Text(chosen, style = Hl.type.meta, color = if (focused) ink else c.content, maxLines = 1)
                }
            }
            Spacer(Modifier.height(HlSpace.xs))
            FormatLines(version.item.copy(media = version.media ?: version.item.media), ink = if (focused) ink else null)
            Spacer(Modifier.height(HlSpace.xxs))
            FactRow(listOf(size, length), color = if (focused) ink else c.contentSecondary)
        }
    }
}
