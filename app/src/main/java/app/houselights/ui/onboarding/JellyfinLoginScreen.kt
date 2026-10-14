package app.houselights.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import app.houselights.R
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FieldLabel
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlListItem
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.util.appContainer

@Composable
fun JellyfinLoginScreen(onLoggedIn: () -> Unit) {
    val app = appContainer()
    val vm = viewModel { JellyfinLoginViewModel(app) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }
    val server = vm.server
    val discovered = vm.discovered
    val error = when (val e = vm.error) {
        null -> null
        LoginError.Refused -> stringResource(R.string.error_credentials)
        is LoginError.Unreachable -> stringResource(R.string.error_server_unreachable, e.address)
        is LoginError.NotJellyfin -> stringResource(R.string.error_not_jellyfin, e.address)
        is LoginError.Failed -> stringResource(R.string.error_connect, e.address, e.detail)
    }

    fun login() = vm.login(onLoggedIn)

    HlFormScaffold(
        title = stringResource(R.string.jellyfin_title),
        description = stringResource(R.string.jellyfin_desc),
        aside = {
            StatusPanel(
                state = if (vm.busy) StatusState.Pending else StatusState.Off,
                stateText = stringResource(loginState(vm.busy, failed = error != null)),
                label = stringResource(R.string.field_server),
                value = server.ifBlank { stringResource(R.string.placeholder_pending) },
                badges = listOf("Jellyfin"),
                result = error,
                resultIsError = true,
            )
            if (discovered.isNotEmpty()) {
                Spacer(Modifier.height(HlSpace.l))
                FieldLabel(stringResource(R.string.servers_found))
                Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                    discovered.forEach { s -> HlListItem(s.name ?: s.address, s.address, { vm.server = s.address }, icon = HlIcons.Server) }
                }
            }
        },
    ) {
        HlTextField(
            label = stringResource(R.string.field_server),
            value = server,
            onValueChange = { vm.server = it },
            placeholder = "192.168.1.10:8096",
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.focusRequester(firstField),
        )
        HlTextField(stringResource(R.string.field_user), vm.user, { vm.user = it })
        HlTextField(
            stringResource(R.string.field_password), vm.password, { vm.password = it },
            password = true, imeAction = ImeAction.Done, onImeAction = ::login,
        )
        HlButton(
            stringResource(if (vm.busy) R.string.signing_in else R.string.sign_in),
            ::login,
            icon = Icons.Filled.Check,
            emphasis = Emphasis.Primary,
        )
    }
}

/** How to say where signing in stands. */
private fun loginState(busy: Boolean, failed: Boolean): Int = when {
    busy -> R.string.connection_testing
    failed -> R.string.connection_failed
    else -> R.string.connection_untested
}
