package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.providers.local.CleanupResult
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanState
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.HlMeter
import app.houselights.ui.design.components.HlPlate
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.MeterFact
import app.houselights.ui.design.components.MeterInk
import app.houselights.ui.design.components.MeterSegment
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.formatBytes
import kotlinx.coroutines.launch

internal fun LazyListScope.storagePane(storage: StorageStats?) {
    item(key = "storage") {
        Section(stringResource(R.string.section_storage), sentence = stringResource(R.string.storage_desc)) { StorageSummary(storage) }
    }
    item(key = "storage-actions") { StorageActions(storage) }
}

/**
 * Space used, as a case's back states it: the total, the meter of the image cache's limit across the plate with
 * that limit, and each part's figure under its name; a part with nothing in it says so in words.
 */
@Composable
private fun StorageSummary(st: StorageStats?) {
    val c = Hl.colors
    val dash = stringResource(R.string.placeholder_none)
    // A line's room under the figures, as the total keeps its own above the meter.
    val room = PaddingValues(start = HlInset.card, end = HlInset.card, top = HlInset.plate, bottom = HlSpace.xl)
    HlPlate(Modifier.fillMaxWidth(), padding = room) {
        Row(Modifier.fillMaxWidth()) {
            val total = st?.let { formatBytes(it.total) } ?: dash
            Text(total, style = Hl.type.subject, color = c.content, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(HlSpace.s))
            val used = stringResource(R.string.storage_used)
            Text(used, style = Hl.type.meta, color = c.contentSecondary, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            st?.let {
                Text(
                    stringResource(R.string.storage_cache_limit, formatBytes(it.imageCacheMax)),
                    style = Hl.type.meta,
                    color = c.contentTertiary,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Spacer(Modifier.height(HlSpace.s))
        StorageMeter(st)
        Spacer(Modifier.height(HlSpace.l))
        StorageParts(st, dash)
        // The last scan stopped for lack of space: say so here, above the actions that free some.
        val scan by settingsViewModel().library.scan.collectAsStateWithLifecycle()
        if (scan.storageFull) {
            Spacer(Modifier.height(HlSpace.l))
            AttentionNote(stringResource(R.string.storage_full))
        }
    }
}

/** The meter of the image cache's limit, its parts in the order their figures read. */
@Composable
private fun StorageMeter(st: StorageStats?) {
    val capacity = st?.let { maxOf(it.imageCacheMax, it.total, 1L).toFloat() } ?: 1f
    HlMeter(
        st?.let {
            listOf(
                MeterSegment(it.imageCacheBytes / capacity, MeterInk.Primary),
                MeterSegment(it.databaseBytes / capacity, MeterInk.Tertiary),
                MeterSegment(it.artworkBytes / capacity, MeterInk.Secondary),
            )
        }.orEmpty(),
        height = HlSize.meterLarge,
    )
}

/** Each part's figure under its name; local images, when there are none, say so in a word. */
@Composable
private fun StorageParts(st: StorageStats?, dash: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MeterFact(stringResource(R.string.storage_image_cache), st?.let { formatBytes(it.imageCacheBytes) } ?: dash, MeterInk.Primary)
        MeterFact(stringResource(R.string.storage_database), st?.let { formatBytes(it.databaseBytes) } ?: dash, MeterInk.Tertiary)
        val none = st != null && st.artworkFiles == 0
        MeterFact(
            stringResource(R.string.storage_artwork),
            if (none) stringResource(R.string.storage_none) else st?.let { formatBytes(it.artworkBytes) } ?: dash,
            MeterInk.Secondary,
            technical = !none,
            empty = none,
        )
    }
}

/** What a cleanup did ([result]), in words: the files it deleted and the room they freed, or that there was nothing. */
@Composable
private fun cleanedWords(result: CleanupResult): String = if (result.deletedFiles == 0) {
    stringResource(R.string.cleanup_nothing)
} else {
    stringResource(
        R.string.cleanup_done,
        pluralStringResource(R.plurals.file_count, result.deletedFiles, result.deletedFiles),
        formatBytes(result.freedBytes),
    )
}

/** The last scan stopped because the device had no space left. */
internal val ScanState.storageFull: Boolean get() = errors.values.any { it.kind == ScanErrorKind.STORAGE_FULL }

@Composable
private fun StorageActions(storage: StorageStats?) {
    val vm = settingsViewModel()
    val scope = rememberCoroutineScope()
    val cleaned = vm.cleaned?.let { cleanedWords(it) }
    var cleared by remember { mutableStateOf(false) }
    // A failed action is reported on its row, where focus is.
    val cleanupFailure = rowFailure(vm.errorOf(SettingAction.CLEANUP), notDone = true)
    val cacheFailure = rowFailure(vm.errorOf(SettingAction.CLEAR_IMAGE_CACHE), notDone = true)
    HlSettingGroup(null, Modifier.revealOnFocus()) {
        HlSettingRow(
            stringResource(R.string.cleanup_now),
            description = stringResource(R.string.cleanup_desc),
            value = cleanupFailure?.value ?: cleaned,
            valueAttention = cleanupFailure != null,
            onClick = vm::cleanup,
        )
        RowFailureNote(cleanupFailure)
        HlSettingRow(
            stringResource(R.string.clear_image_cache),
            description = stringResource(R.string.clear_cache_desc),
            value = cacheFailure?.value
                ?: if (cleared) stringResource(R.string.image_cache_cleared) else storage?.let { formatBytes(it.imageCacheBytes) },
            valueAttention = cacheFailure != null,
            // What it frees is a figure; what it did, words.
            valueStyle = if (cacheFailure == null && !cleared) Hl.type.spec else Hl.type.body,
            onClick = {
                scope.launch { cleared = vm.clearImageCache() }
            },
        )
        RowFailureNote(cacheFailure)
    }
}
