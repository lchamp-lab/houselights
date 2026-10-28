package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.FirstScanDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.textRoom
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.formatCount
import app.houselights.ui.util.scanCount

/**
 * The folder home's first scan reads, set as an opening title (DESIGN.md, Layout), in its column
 * ([FirstScanDefaults.column]): what is happening in `lead` (« Lecture du dossier… » while it is listed, « Analyse du
 * dossier… » once its files are counted); its [name] set to its measure (`titleSignature` on one line, else `display`
 * on two balanced lines, white, never ember); where it is, [place] and its printed [protocol] (none for the player's
 * own disks); then the Counter, its line [FirstScanDefaults.line] long, the count after it, its thousands grouped, the
 * file read under it in `meta`, [current]. While the folder is listed, the videos [listed] so far stand in the file's
 * place (« 1 240 vidéos trouvées… »), nothing before the first. Each line keeps its room, so nothing moves when the
 * files are counted; read out as one, a progress, each change unannounced.
 */
@Composable
fun FolderBlock(
    name: String,
    place: String,
    processed: Int,
    total: Int,
    modifier: Modifier = Modifier,
    protocol: String? = null,
    current: String? = null,
    listed: Int = 0,
) {
    val c = Hl.colors
    val reading = total > 0
    val fraction = if (reading) processed.toFloat() / total else 0f
    Column(
        modifier
            .width(FirstScanDefaults.column)
            .semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        Text(
            stringResource(if (reading) R.string.first_scan_reading else R.string.first_scan_listing),
            style = Hl.type.lead,
            color = c.contentSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.paddingFromBaseline(bottom = HlSpace.s),
        )
        TitleArt(
            name,
            logo = null,
            maxWidth = FirstScanDefaults.column,
            maxHeight = HlLayout.titleArtHeight,
            modifier = Modifier.paddingFromBaseline(bottom = HlSpace.m),
            measured = true,
        )
        PlaceLine(place, protocol)
        Spacer(Modifier.height(HlSpace.s))
        // As tall with its count as without, so nothing moves when the files are counted.
        Row(Modifier.heightIn(min = textRoom(Hl.type.meta)), verticalAlignment = Alignment.CenterVertically) {
            ProgressLine(fraction.coerceAtLeast(PROGRESS_SLIVER), Modifier.width(FirstScanDefaults.line))
            if (reading) {
                Spacer(Modifier.width(HlSpace.s))
                Text(scanCount(processed, total), style = Hl.type.meta, color = c.content, maxLines = 1)
            }
        }
        Spacer(Modifier.height(HlSpace.xs))
        val found = listed.takeIf { !reading && it > 0 }?.let { videos ->
            pluralStringResource(R.plurals.first_scan_found, videos, formatCount(videos.toLong()))
        }
        // Its room kept from the start, so nothing moves when the first file comes.
        Box(Modifier.fillMaxWidth().heightIn(min = textRoom(Hl.type.meta))) {
            (current ?: found)?.let { line ->
                Text(line, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Where a folder is, in `meta`, then its protocol printed: a NAS by its name, a disk of the player's unprinted. */
@Composable
private fun PlaceLine(place: String, protocol: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            place,
            style = Hl.type.meta,
            color = Hl.colors.contentSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (protocol != null) {
            Spacer(Modifier.width(HlSpace.s))
            Badge(protocol)
        }
    }
}

/**
 * « Aller à l'accueil » on home's first scan: a house button (secondary, a plate focus fills white), then what goes on
 * meanwhile, « L'analyse continue en arrière-plan », in `meta`, read out with it as one. It takes focus as it comes
 * only while nothing has it ([focusFree]): what arrives never moves a focus the viewer placed.
 */
@Composable
fun GoHomeButton(onClick: () -> Unit, modifier: Modifier = Modifier, focusFree: () -> Boolean = { false }) {
    val words = stringResource(R.string.first_scan_home)
    val goesOn = stringResource(R.string.first_scan_home_goes_on)
    val requester = remember { FocusRequester() }
    val free by rememberUpdatedState(focusFree)
    LaunchedEffect(requester) { if (free()) requester.tryRequestFocus() }
    Column(modifier) {
        HlButton(
            words,
            onClick = onClick,
            modifier = Modifier.focusRequester(requester).semantics { contentDescription = "$words. $goesOn" },
        )
        Spacer(Modifier.height(HlSpace.xxs))
        Text(
            goesOn,
            style = Hl.type.meta,
            color = Hl.colors.contentSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
