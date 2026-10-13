package app.houselights.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.jellyfin.ServerDiscovery
import app.houselights.ui.components.ActionButton
import app.houselights.ui.components.Chip
import app.houselights.ui.components.ImmersiveBackdrop
import app.houselights.ui.components.TvTextField
import app.houselights.ui.components.appContainer
import app.houselights.ui.theme.Palette
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
fun JellyfinLoginScreen(onLoggedIn: () -> Unit) {
    val app = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discovered by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    val firstField = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstField.requestFocus() }
        discovered = ServerDiscovery.discover()
        if (server.isBlank()) discovered.firstOrNull()?.let { server = it.address }
    }

    fun login() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val url = JellyfinClient.normalizeUrl(server)
            runCatching {
                val api = JellyfinClient(url, app.settings.ensureDeviceId(), token = null).api
                val info = api.publicInfo()
                val auth = api.authenticate(AuthRequest(user.trim(), password))
                app.settings.setSource(
                    SourceConfig.Jellyfin(url, info.serverName, auth.user.id, auth.user.name, auth.accessToken)
                )
            }.onSuccess {
                onLoggedIn()
            }.onFailure { e ->
                error = if (e is HttpException && e.code() == 401) context.getString(R.string.error_credentials)
                else context.getString(R.string.error_connect, url, e.message ?: e.javaClass.simpleName)
            }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        ImmersiveBackdrop(null)
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 96.dp)
                .width(460.dp),
        ) {
            Text("HOUSELIGHTS", style = MaterialTheme.typography.titleSmall, color = Palette.Accent)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.jellyfin_title), style = MaterialTheme.typography.displayMedium, color = Palette.Text)
            Spacer(Modifier.height(28.dp))

            if (discovered.isNotEmpty()) {
                Text(stringResource(R.string.servers_found), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    discovered.forEach { s ->
                        Chip(s.name ?: s.address, selected = server == s.address, onClick = { server = s.address })
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            TvTextField(
                label = stringResource(R.string.field_server),
                value = server,
                onValueChange = { server = it },
                placeholder = "192.168.1.10:8096",
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.focusRequester(firstField),
            )
            Spacer(Modifier.height(16.dp))
            TvTextField(stringResource(R.string.field_user), user, { user = it })
            Spacer(Modifier.height(16.dp))
            TvTextField(
                stringResource(R.string.field_password), password, { password = it },
                password = true, imeAction = ImeAction.Done, onImeAction = ::login,
            )
            Spacer(Modifier.height(24.dp))
            ActionButton(stringResource(if (busy) R.string.signing_in else R.string.sign_in), ::login, primary = true)
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Accent)
            }
        }
    }
}
