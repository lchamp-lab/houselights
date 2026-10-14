package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.PathMapping
import app.houselights.player.PathResolver
import app.houselights.player.Redact
import app.houselights.settings.AppSettings
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.revealOnFocus

internal fun LazyListScope.playbackPane(s: AppSettings) {
    item {
        val vm = settingsViewModel()
        var choose by remember { mutableStateOf(false) }
        Section(stringResource(R.string.section_playback)) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.file_access),
                    value = stringResource(if (s.forceHttp) R.string.http_stream else R.string.network_path),
                    description = stringResource(R.string.playback_desc),
                    onClick = { choose = true },
                )
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
            )
        }
    }
    item { HlSettingGroup(stringResource(R.string.path_mappings), Modifier.revealOnFocus()) { PathMappings(s) } }
}

@Composable
private fun PathMappings(s: AppSettings) {
    val vm = settingsViewModel()
    val mappings = remember { mutableStateListOf<PathMapping>().apply { addAll(s.pathMappings) } }
    var saved by remember { mutableStateOf(false) }
    var samplePath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { samplePath = vm.samplePath() }

    Column(Modifier.padding(horizontal = HlSpace.l), verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        Hint(stringResource(R.string.path_mapping_help))
        samplePath?.let { Hint(stringResource(R.string.sample_path, it)) }
        mappings.forEachIndexed { index, m ->
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.Bottom) {
                HlTextField(
                    stringResource(R.string.server_path), m.from, { mappings[index] = m.copy(from = it); saved = false },
                    placeholder = "/media/movies", modifier = Modifier.weight(1f),
                )
                HlTextField(
                    stringResource(R.string.network_path), m.to, { mappings[index] = m.copy(to = it); saved = false },
                    placeholder = "nfs://192.168.1.10/volume1/movies", modifier = Modifier.weight(1.3f),
                )
                HlIconButton(Icons.Filled.Close, stringResource(R.string.remove), onClick = { mappings.removeAt(index); saved = false })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            HlButton(stringResource(R.string.add_mapping), onClick = { mappings.add(PathMapping("", "")) }, icon = Icons.Filled.Add)
            HlButton(
                stringResource(if (saved) R.string.saved else R.string.save),
                onClick = { vm.setPathMappings(mappings.toList()) { saved = true } },
                icon = Icons.Filled.Check,
                emphasis = if (saved) Emphasis.Secondary else Emphasis.Primary,
            )
        }
        samplePath?.let { path ->
            val resolved = PathResolver.resolve(path, mappings.toList())?.let(Redact::uri)
            Hint(stringResource(R.string.mapping_result, resolved ?: stringResource(R.string.mapping_none)))
        }
    }
}
