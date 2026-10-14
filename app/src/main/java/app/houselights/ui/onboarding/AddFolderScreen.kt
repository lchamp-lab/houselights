package app.houselights.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.address
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FieldLabel
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.scanErrorText

/** Displayed order of the options: NFS first, since it needs no password. */
private val PROTOCOLS = listOf(Protocol.NFS, Protocol.SMB)
private val KINDS = listOf(SourceKind.MOVIES, SourceKind.SHOWS)

/** Adds a network folder to the local library, after an optional connection test. */
@Composable
fun AddFolderScreen(onAdded: () -> Unit) {
    val app = appContainer()
    val vm = viewModel { AddFolderViewModel(app) }
    val form = vm.form
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val check = vm.check
    val result = when (check) {
        null -> null
        is FolderCheck.Incomplete -> stringResource(check.message)
        is FolderCheck.Reachable -> stringResource(R.string.test_ok, check.entries)
        is FolderCheck.Failed -> scanErrorText(check.error, check.host)
    }
    val success = check is FolderCheck.Reachable
    val kind = form.kind
    val protocol = form.protocol

    // Live preview of the address the player will open, without credentials.
    val s = form.toSource()
    val preview = s.copy(host = s.host.ifBlank { stringResource(R.string.placeholder_pending) }).address()

    HlFormScaffold(
        title = stringResource(R.string.add_folder),
        description = stringResource(R.string.add_folder_desc),
        aside = {
            StatusPanel(
                icon = HlIcons.Folder,
                label = stringResource(R.string.label_address),
                value = preview,
                lit = success,
                result = result,
                resultIsError = !success,
            )
            Spacer(Modifier.height(HlSpace.l))
            Text(
                stringResource(if (protocol == Protocol.NFS) R.string.nfs_hint else R.string.smb_hint),
                style = Hl.type.caption,
                color = Hl.colors.contentTertiary,
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
            Column {
                FieldLabel(stringResource(R.string.folder_kind))
                HlSegmented(
                    listOf(stringResource(R.string.library_movies), stringResource(R.string.library_shows)),
                    selected = KINDS.indexOf(kind),
                    onSelect = { i -> vm.edit { copy(kind = KINDS[i]) } },
                    modifier = Modifier.focusRequester(focus),
                )
            }
            Column {
                FieldLabel(stringResource(R.string.field_protocol))
                HlSegmented(
                    PROTOCOLS.map { it.name },
                    selected = PROTOCOLS.indexOf(protocol),
                    onSelect = { i -> vm.edit { copy(protocol = PROTOCOLS[i]) } },
                )
            }
        }
        HlTextField(
            stringResource(R.string.field_host), form.host, { v -> vm.edit { copy(host = v) } },
            placeholder = "192.168.1.10", keyboardType = KeyboardType.Uri,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            HlTextField(
                stringResource(if (protocol == Protocol.NFS) R.string.field_export else R.string.field_share),
                form.share, { v -> vm.edit { copy(share = v) } },
                placeholder = if (protocol == Protocol.NFS) "/volume1/media" else "media",
                modifier = Modifier.weight(1f),
            )
            HlTextField(
                stringResource(R.string.field_path), form.path, { v -> vm.edit { copy(path = v) } },
                placeholder = if (kind == SourceKind.SHOWS) "series" else "films", modifier = Modifier.weight(1f),
            )
        }
        if (protocol == Protocol.SMB) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                HlTextField(
                    stringResource(R.string.field_user), form.user, { v -> vm.edit { copy(user = v) } },
                    modifier = Modifier.weight(1f),
                )
                HlTextField(
                    stringResource(R.string.field_password), form.password, { v -> vm.edit { copy(password = v) } },
                    password = true, imeAction = ImeAction.Done, modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            HlButton(stringResource(R.string.add), { vm.add(onAdded) }, icon = Icons.Filled.Add, emphasis = Emphasis.Primary)
            HlButton(stringResource(R.string.test_connection), { vm.test() }, icon = Icons.Filled.Refresh)
        }
    }
}
