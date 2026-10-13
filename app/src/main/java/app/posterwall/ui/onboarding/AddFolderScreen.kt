package app.posterwall.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.posterwall.R
import app.posterwall.core.SourceConfig
import app.posterwall.providers.local.SmbBrowser
import app.posterwall.providers.local.SourceEntity
import app.posterwall.providers.local.SourceKind
import app.posterwall.ui.components.ActionButton
import app.posterwall.ui.components.Chip
import app.posterwall.ui.components.ImmersiveBackdrop
import app.posterwall.ui.components.TvTextField
import app.posterwall.ui.components.appContainer
import app.posterwall.ui.theme.Palette
import kotlinx.coroutines.launch

/** Adds an SMB folder to the local library, after an optional connection test. */
@Composable
fun AddFolderScreen(onAdded: () -> Unit) {
    val app = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(SourceKind.MOVIES) }
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun source() = SourceEntity(
        name = path.trim('/').substringAfterLast('/').ifBlank { share.trim('/') },
        kind = kind,
        host = host.trim().removePrefix("smb://").trimEnd('/'),
        share = share.trim().trim('/'),
        path = path.trim().trim('/'),
        username = user.trim(),
        password = password,
    )

    fun test(then: (() -> Unit)? = null) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            runCatching { SmbBrowser.test(source()) }
                .onSuccess { count ->
                    message = context.getString(R.string.test_ok, count)
                    then?.invoke()
                }
                .onFailure { e -> message = context.getString(R.string.test_failed, e.message ?: e.javaClass.simpleName) }
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

    Box(Modifier.fillMaxSize()) {
        ImmersiveBackdrop(null)
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 96.dp)
                .width(560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.add_folder), style = MaterialTheme.typography.displayMedium, color = Palette.Text)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.folder_kind), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.library_movies), kind == SourceKind.MOVIES, { kind = SourceKind.MOVIES }, Modifier.focusRequester(focus))
                Chip(stringResource(R.string.library_shows), kind == SourceKind.SHOWS, { kind = SourceKind.SHOWS })
            }
            Spacer(Modifier.height(16.dp))
            TvTextField(stringResource(R.string.field_host), host, { host = it }, placeholder = "192.168.1.10", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(stringResource(R.string.field_share), share, { share = it }, placeholder = "media", modifier = Modifier.width(220.dp))
                TvTextField(stringResource(R.string.field_path), path, { path = it }, placeholder = "films", modifier = Modifier.width(328.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(stringResource(R.string.field_user), user, { user = it }, modifier = Modifier.width(274.dp))
                TvTextField(
                    stringResource(R.string.field_password), password, { password = it },
                    password = true, imeAction = ImeAction.Done, modifier = Modifier.width(274.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(stringResource(R.string.add), { add() }, primary = true)
                ActionButton(stringResource(R.string.test_connection), { test() })
            }
            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Accent)
            }
        }
    }
}
