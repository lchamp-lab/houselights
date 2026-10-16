package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanState
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.HlMeter
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.MeterInk
import app.houselights.ui.design.components.MeterLegend
import app.houselights.ui.design.components.MeterSegment
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.formatBytes
import kotlinx.coroutines.launch

internal fun LazyListScope.storagePane(storage: StorageStats?) {
    item { Section(stringResource(R.string.section_storage)) { StorageSummary(storage) } }
    item { StorageActions(storage) }
}

/** "12 fichiers · 3,4 Mo", or that there are none. */
@Composable
private fun artworkUse(st: StorageStats): String =
    if (st.artworkFiles == 0) stringResource(R.string.storage_none)
    else pluralStringResource(R.plurals.file_count, st.artworkFiles, st.artworkFiles) + FACT_GAP + formatBytes(st.artworkBytes)

/** How much is used, and by what: a figure, a gauge against the image cache, a legend. */
@Composable
private fun StorageSummary(st: StorageStats?) {
    val c = Hl.colors
    val dash = stringResource(R.string.placeholder_none)
    Column(Modifier.widthIn(max = HlLayout.readable)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(st?.let { formatBytes(it.total) } ?: dash, style = Hl.type.subject, color = c.content)
            Spacer(Modifier.width(HlSpace.s))
            Text(
                stringResource(R.string.storage_used),
                style = Hl.type.caption,
                color = c.contentSecondary,
                modifier = Modifier.padding(bottom = HlSpace.xxs),
            )
        }
        Spacer(Modifier.height(HlSpace.m))
        val capacity = st?.let { maxOf(it.imageCacheMax, it.total, 1L).toFloat() } ?: 1f
        HlMeter(
            st?.let {
                listOf(
                    MeterSegment(it.imageCacheBytes / capacity, MeterInk.Primary),
                    MeterSegment(it.artworkBytes / capacity, MeterInk.Secondary),
                    MeterSegment(it.databaseBytes / capacity, MeterInk.Tertiary),
                )
            }.orEmpty(),
        )
        Spacer(Modifier.height(HlSpace.m))
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
            MeterLegend(stringResource(R.string.storage_image_cache), st?.let { formatBytes(it.imageCacheBytes) } ?: dash, MeterInk.Primary)
            MeterLegend(stringResource(R.string.storage_artwork), st?.let { artworkUse(it) } ?: dash, MeterInk.Secondary)
            MeterLegend(stringResource(R.string.storage_database), st?.let { formatBytes(it.databaseBytes) } ?: dash, MeterInk.Tertiary)
        }
        st?.let {
            Spacer(Modifier.height(HlSpace.m))
            Hint(stringResource(R.string.storage_cache_limit, formatBytes(it.imageCacheMax)))
        }
        // The last scan stopped for want of space: said here, above what frees some.
        val scan by settingsViewModel().library.scan.collectAsStateWithLifecycle()
        if (scan.storageFull) {
            Spacer(Modifier.height(HlSpace.m))
            AttentionNote(stringResource(R.string.storage_full))
        }
    }
}

/** The last scan stopped because the device had no space left. */
internal val ScanState.storageFull: Boolean get() = errors.values.any { it.kind == ScanErrorKind.STORAGE_FULL }

@Composable
private fun StorageActions(storage: StorageStats?) {
    val vm = settingsViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cleaned by remember { mutableStateOf<String?>(null) }
    var cleared by remember { mutableStateOf(false) }
    HlSettingGroup(null, Modifier.revealOnFocus()) {
        HlSettingRow(
            stringResource(R.string.cleanup_now),
            description = stringResource(R.string.cleanup_desc),
            value = cleaned,
            onClick = {
                scope.launch {
                    val result = vm.cleanup()
                    cleaned = if (result.deletedFiles == 0) context.getString(R.string.cleanup_nothing)
                    else context.getString(
                        R.string.cleanup_done,
                        context.resources.getQuantityString(R.plurals.file_count, result.deletedFiles, result.deletedFiles),
                        formatBytes(context, result.freedBytes),
                    )
                }
            },
            chevron = false,
        )
        HlSettingRow(
            stringResource(R.string.clear_image_cache),
            description = stringResource(R.string.clear_cache_desc),
            value = if (cleared) stringResource(R.string.image_cache_cleared) else storage?.let { formatBytes(it.imageCacheBytes) },
            onClick = {
                scope.launch {
                    vm.clearImageCache()
                    cleared = true
                }
            },
            chevron = false,
        )
    }
}
