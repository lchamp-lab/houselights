package app.houselights.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.PathMapping
import app.houselights.player.PathResolver
import app.houselights.player.Redact
import app.houselights.settings.AppSettings
import app.houselights.ui.design.Hl
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.ChoiceCheck
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.revealOnFocus

/** How the player reaches the server's files, the chosen way checked in ember, then the path mappings, a line each. */
internal fun LazyListScope.playbackPane(s: AppSettings) {
    item {
        val vm = settingsViewModel()
        val failure = rowFailure(vm.errorOf(SettingAction.FILE_ACCESS))
        Section(stringResource(R.string.section_playback), sentence = stringResource(R.string.playback_desc)) {
            HlSettingGroup(stringResource(R.string.file_access)) {
                listOf(
                    false to (R.string.network_path to R.string.network_path_desc),
                    true to (R.string.http_stream to R.string.http_stream_desc),
                ).forEach { (http, words) ->
                    val current = s.forceHttp == http
                    HlSettingRow(
                        stringResource(words.first),
                        Modifier.semantics { selected = current },
                        description = stringResource(words.second),
                        value = failure?.value.takeIf { current },
                        valueAttention = failure != null,
                        onClick = { vm.setForceHttp(http) },
                        trailing = if (current && failure == null) ({ ChoiceCheck() }) else null,
                    )
                }
                RowFailureNote(failure)
            }
        }
    }
    item { HlSettingGroup(stringResource(R.string.path_mappings), Modifier.revealOnFocus()) { PathMappings() } }
}

/** The mappings, each read as a sentence ("/media/films devient nfs://…"); OK opens it in a sheet, as Add does a new one. */
@Composable
private fun PathMappings() {
    val vm = settingsViewModel()
    // The mapping open in the sheet: its line, or -1 for a new one; null with the sheet closed.
    var editing by remember { mutableStateOf<Int?>(null) }
    vm.mappings.forEachIndexed { index, m ->
        HlSettingRow(
            m.from,
            description = stringResource(R.string.mapping_becomes, Redact.uri(m.to)),
            onClick = { editing = index },
        )
    }
    HlSettingRow(
        stringResource(R.string.add_mapping),
        description = stringResource(R.string.add_mapping_desc),
        onClick = { editing = NEW },
    )
    // Mappings save as the sheet closes, so the error shows under them, where focus returns.
    vm.errorOf(SettingAction.PATH_MAPPINGS)?.let { error ->
        AttentionNote(stringResource(R.string.mappings_not_saved, stringResource(error.message)))
    }
    editing?.let { index -> MappingSheet(index.takeIf { it != NEW }, onDismiss = { editing = null }) }
}

/**
 * A mapping's two paths, the server's then the network's, with what they do to a file the server names; Back
 * saves them once both are filled in. An existing one can be removed. Its password is never shown ([shownTo]).
 */
@Composable
private fun MappingSheet(index: Int?, onDismiss: () -> Unit) {
    val vm = settingsViewModel()
    val was = index?.let { vm.mappings.getOrNull(it) }
    var from by remember { mutableStateOf(was?.from.orEmpty()) }
    var to by remember { mutableStateOf(shownTo(was)) }
    var samplePath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { samplePath = vm.samplePath() }
    val first = remember { FocusRequester() }
    var removed by remember { mutableStateOf(false) }
    HlSideSheet(
        stringResource(if (index == null) R.string.add_mapping else R.string.edit_mapping),
        onDismiss = {
            if (!removed) vm.putMapping(index, editedMapping(was, from, to))
            onDismiss()
        },
        description = stringResource(R.string.path_mapping_help),
        initialFocus = first,
    ) { sheet ->
        // Its parts the sheet's own gap apart.
        HlTextField(
            stringResource(R.string.server_path), from, { from = it },
            Modifier.fillMaxWidth().focusRequester(first),
            placeholder = stringResource(R.string.placeholder_server_path),
        )
        HlTextField(
            stringResource(R.string.network_path), to, { to = it },
            Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.placeholder_network_path),
            onImeAction = { sheet.dismiss() },
        )
        samplePath?.let { path ->
            val resolved = PathResolver.resolve(path, listOf(PathMapping(from, to)).filter { it.from.isNotBlank() && it.to.isNotBlank() })
            val result = stringResource(R.string.mapping_result, resolved?.let(Redact::uri) ?: stringResource(R.string.mapping_none))
            // The example and what it becomes, one line under the other.
            Text(
                stringResource(R.string.sample_path, path) + "\n" + result,
                style = Hl.type.meta,
                color = Hl.colors.contentSecondary,
            )
        }
        if (index != null) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.remove),
                    onClick = {
                        removed = true
                        sheet.dismiss { vm.removeMapping(index) }
                    },
                )
            }
        }
    }
}

/** [was]'s network path as its sheet shows it: masked like the list's line, no password on the TV. */
internal fun shownTo(was: PathMapping?): String = was?.to?.let(Redact::uri).orEmpty()

/** The mapping the sheet saves from [from] and [to]: the mask left in [to] keeps [was]'s user and password. */
internal fun editedMapping(was: PathMapping?, from: String, to: String) =
    PathMapping(from.trim(), Redact.unmask(to.trim(), was?.to.orEmpty()))

/** [MappingSheet]'s line for a mapping not yet added. */
private const val NEW = -1
