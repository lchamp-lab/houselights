package app.houselights.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.jellyfin.ServerDiscovery
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FieldLabel
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlListItem
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.util.appContainer
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
        // Listed for the user to choose: any device on the network can answer a discovery.
        discovered = ServerDiscovery.discover()
    }

    fun login() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            var url = JellyfinClient.normalizeUrl(server)
            suspendRunCatching {
                val deviceId = app.settings.ensureDeviceId()
                val (reached, info) = JellyfinClient.reach(server, deviceId)
                url = reached
                val auth = JellyfinClient(url, deviceId, token = null).api.authenticate(AuthRequest(user.trim(), password))
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

    HlFormScaffold(
        title = stringResource(R.string.jellyfin_title),
        description = stringResource(R.string.jellyfin_desc),
        aside = {
            StatusPanel(
                icon = HlIcons.Server,
                label = stringResource(R.string.field_server),
                value = server.ifBlank { stringResource(R.string.placeholder_pending) },
                lit = false,
                result = error,
                resultIsError = true,
            )
            if (discovered.isNotEmpty()) {
                Spacer(Modifier.height(HlSpace.l))
                FieldLabel(stringResource(R.string.servers_found))
                Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                    discovered.forEach { s -> HlListItem(s.name ?: s.address, s.address, { server = s.address }, icon = HlIcons.Server) }
                }
            }
        },
    ) {
        HlTextField(
            label = stringResource(R.string.field_server),
            value = server,
            onValueChange = { server = it },
            placeholder = "192.168.1.10:8096",
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.focusRequester(firstField),
        )
        HlTextField(stringResource(R.string.field_user), user, { user = it })
        HlTextField(
            stringResource(R.string.field_password), password, { password = it },
            password = true, imeAction = ImeAction.Done, onImeAction = ::login,
        )
        HlButton(
            stringResource(if (busy) R.string.signing_in else R.string.sign_in),
            ::login,
            icon = Icons.Filled.Check,
            emphasis = Emphasis.Primary,
        )
    }
}
