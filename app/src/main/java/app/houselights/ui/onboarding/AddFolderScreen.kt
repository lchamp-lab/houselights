package app.houselights.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.RemoteBrowser
import app.houselights.providers.local.ScanErrors
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
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
import kotlinx.coroutines.launch

/** Displayed order of the options: NFS first, since it needs no password. */
private val PROTOCOLS = listOf(Protocol.NFS, Protocol.SMB)
private val KINDS = listOf(SourceKind.MOVIES, SourceKind.SHOWS)

/** Adds a network folder to the local library, after an optional connection test. */
@Composable
fun AddFolderScreen(onAdded: () -> Unit) {
    val app = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(SourceKind.MOVIES) }
    var protocol by remember { mutableStateOf(Protocol.NFS) }
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun source() = SourceEntity(
        name = path.trim('/').substringAfterLast('/').ifBlank { share.trim('/').substringAfterLast('/') },
        kind = kind,
        protocol = protocol,
        host = host.trim().removePrefix("smb://").removePrefix("nfs://").trimEnd('/'),
        share = share.trim().trim('/'),
        path = path.trim().trim('/'),
        username = if (protocol == Protocol.SMB) user.trim() else "",
        password = if (protocol == Protocol.SMB) password else "",
    )

    fun test(then: (() -> Unit)? = null) {
        if (busy) return
        // Check the form before reaching the network, so errors speak about the form.
        val missing = when {
            host.isBlank() -> R.string.error_host_missing
            share.isBlank() -> if (protocol == Protocol.NFS) R.string.error_export_missing else R.string.error_share_missing
            else -> null
        }
        if (missing != null) {
            success = false
            result = context.getString(missing)
            return
        }
        busy = true
        result = null
        scope.launch {
            val s = source()
            runCatching { RemoteBrowser.of(s).test(s) }
                .onSuccess { count ->
                    success = true
                    result = context.getString(R.string.test_ok, count)
                    then?.invoke()
                }
                .onFailure { e ->
                    success = false
                    result = scanErrorText(context, ScanErrors.classify(e), s.host.ifBlank { "?" })
                }
            busy = false
        }
    }

    fun add() = test {
        scope.launch {
            app.database.dao().insertSource(source())
            app.settings.setSource(SourceConfig.Local)
            app.scanner.scanAll()
            onAdded()
        }
    }

    // Live preview of the address the player will open, without credentials.
    val s = source()
    val preview = "${protocol.name.lowercase()}://${s.host.ifBlank { "…" }}/" +
        listOf(s.share, s.path).filter { it.isNotEmpty() }.joinToString("/")

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
                    onSelect = { kind = KINDS[it] },
                    modifier = Modifier.focusRequester(focus),
                )
            }
            Column {
                FieldLabel(stringResource(R.string.field_protocol))
                HlSegmented(
                    PROTOCOLS.map { it.name },
                    selected = PROTOCOLS.indexOf(protocol),
                    onSelect = { protocol = PROTOCOLS[it]; success = false; result = null },
                )
            }
        }
        HlTextField(stringResource(R.string.field_host), host, { host = it; success = false }, placeholder = "192.168.1.10", keyboardType = KeyboardType.Uri)
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            HlTextField(
                stringResource(if (protocol == Protocol.NFS) R.string.field_export else R.string.field_share),
                share, { share = it; success = false },
                placeholder = if (protocol == Protocol.NFS) "/volume1/media" else "media",
                modifier = Modifier.width(260.dp),
            )
            HlTextField(stringResource(R.string.field_path), path, { path = it; success = false }, placeholder = "films", modifier = Modifier.width(320.dp))
        }
        if (protocol == Protocol.SMB) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                HlTextField(stringResource(R.string.field_user), user, { user = it; success = false }, modifier = Modifier.width(260.dp))
                HlTextField(
                    stringResource(R.string.field_password), password, { password = it; success = false },
                    password = true, imeAction = ImeAction.Done, modifier = Modifier.width(320.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            HlButton(stringResource(R.string.add), { add() }, icon = Icons.Filled.Add, emphasis = Emphasis.Primary)
            HlButton(stringResource(R.string.test_connection), { test() }, icon = Icons.Filled.Refresh)
        }
    }
}
