package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.player.PathResolver
import app.houselights.player.Redact
import app.houselights.settings.AppSettings
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.FACT_GAP

internal fun LazyListScope.playbackPane(s: AppSettings) {
    item {
        val vm = settingsViewModel()
        var choose by remember { mutableStateOf(false) }
        val failure = rowFailure(vm.errorOf(SettingAction.FILE_ACCESS))
        Section(stringResource(R.string.section_playback)) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.file_access),
                    value = failure?.value ?: stringResource(if (s.forceHttp) R.string.http_stream else R.string.network_path),
                    description = stringResource(R.string.playback_desc),
                    valueAttention = failure != null,
                    onClick = { choose = true },
                )
                RowFailureNote(failure)
            }
        }
        if (choose) {
            HlChoiceSheet(
                title = stringResource(R.string.file_access),
                options = listOf(
                    ChoiceOption(stringResource(R.string.network_path), stringResource(R.string.network_path_desc)),
                    ChoiceOption(stringResource(R.string.http_stream), stringResource(R.string.http_stream_desc)),
                ),
                selected = if (s.forceHttp) 1 else 0,
                onSelect = { vm.setForceHttp(it == 1) },
                onDismiss = { choose = false },
                description = stringResource(R.string.playback_desc),
            )
        }
    }
    item { HlSettingGroup(stringResource(R.string.path_mappings), Modifier.revealOnFocus()) { PathMappings() } }
}

/**
 * The path mappings, one line each, kept by the ViewModel. A field saves when it is left (its
 * keyboard action, or focus moving on), so there is no Save button; the result line says when
 * it is done. A new line takes focus; a line deleted hands it to the line above, else to Add.
 */
@Composable
private fun PathMappings() {
    val vm = settingsViewModel()
    val mappings = vm.mappings
    var samplePath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { samplePath = vm.samplePath() }
    val add = remember { FocusRequester() }
    val servers = remember { mutableMapOf<Int, FocusRequester>() }
    val networks = remember { mutableMapOf<Int, FocusRequester>() }
    var added by remember { mutableStateOf(false) }
    LaunchedEffect(mappings.size) {
        if (added) {
            added = false
            runCatching { servers.getValue(mappings.lastIndex).requestFocus() }
        }
    }
    val save = Modifier.onFocusChanged { if (!it.hasFocus) vm.saveMappings() }

    Column(Modifier.padding(horizontal = HlSpace.l), verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        Hint(stringResource(R.string.path_mapping_help))
        samplePath?.let { Hint(stringResource(R.string.sample_path, it)) }
        mappings.forEachIndexed { index, m ->
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.Bottom) {
                HlTextField(
                    stringResource(R.string.server_path), m.from, { vm.editMapping(index, m.copy(from = it)) },
                    placeholder = "/media/movies", onImeAction = vm::saveMappings,
                    modifier = Modifier.weight(1f).focusRequester(servers.getOrPut(index) { FocusRequester() }).then(save),
                )
                HlTextField(
                    stringResource(R.string.network_path), m.to, { vm.editMapping(index, m.copy(to = it)) },
                    placeholder = "nfs://192.168.1.10/volume1/movies", onImeAction = vm::saveMappings,
                    modifier = Modifier.weight(1.3f).focusRequester(networks.getOrPut(index) { FocusRequester() }).then(save),
                )
                HlIconButton(HlIcons.Close, stringResource(R.string.remove), onClick = {
                    // Focus moves first, while the line above is where it was.
                    runCatching { (if (index > 0) networks.getValue(index - 1) else add).requestFocus() }
                    vm.removeMapping(index)
                })
            }
        }
        HlButton(
            stringResource(R.string.add_mapping),
            onClick = {
                vm.addMapping()
                added = true
            },
            icon = HlIcons.Add,
            modifier = Modifier.focusRequester(add),
        )
        val result = samplePath?.let { path ->
            val resolved = PathResolver.resolve(path, mappings)?.let(Redact::uri)
            stringResource(R.string.mapping_result, resolved ?: stringResource(R.string.mapping_none))
        }
        val saved = if (vm.mappingsSaved) stringResource(R.string.saved) else null
        listOfNotNull(result, saved).takeIf { it.isNotEmpty() }?.let { Hint(it.joinToString(FACT_GAP)) }
        // Saved as a field is left: said under the fields, where focus still is.
        vm.errorOf(SettingAction.PATH_MAPPINGS)?.let { error ->
            AttentionNote(stringResource(R.string.mappings_not_saved, stringResource(error.message)))
        }
    }
}
