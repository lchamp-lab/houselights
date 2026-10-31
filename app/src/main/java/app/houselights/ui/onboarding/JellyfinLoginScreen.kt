package app.houselights.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.ServerCheck
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ChoicePlace
import app.houselights.ui.design.components.ConnectionSecurity
import app.houselights.ui.design.components.ConnectionState
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.scrollsAsBlock
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.roomPosters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Signs in to Jellyfin, the form starting as [fill] says. */
@Composable
fun JellyfinLoginScreen(
    fill: LoginFill,
    onLoggedIn: () -> Unit,
    vm: JellyfinLoginViewModel = viewModel(factory = JellyfinLoginViewModel.factory(appContainer(), fill)),
) {
    JellyfinLoginForm(vm, roomPosters(), onLoggedIn)
}

/**
 * The sign-in form of [vm] on [room]'s posters: the servers found on the network, then server, user and password,
 * with the sign-in status beside it.
 */
@Composable
internal fun JellyfinLoginForm(vm: JellyfinLoginViewModel, room: List<ImageRef> = emptyList(), onLoggedIn: () -> Unit) {
    val firstField = remember { FocusRequester() }
    val firstServer = remember { FocusRequester() }
    val userField = remember { FocusRequester() }
    val passwordField = remember { FocusRequester() }
    FirstFocus(vm.again, { vm.discovered.isNotEmpty() }, firstServer, firstField, if (vm.user.isBlank()) userField else passwordField)
    val server = vm.server
    val chosen = vm.chosen
    // While the address is the one picking it gave, signing in follows its check, which the panel says.
    val check = vm.pickedCheck
    val error = vm.error?.let { loginErrorText(it) }

    fun login() = vm.login(onLoggedIn)

    HlFormScaffold(
        title = stringResource(R.string.jellyfin_title),
        description = stringResource(if (vm.discovered.isNotEmpty()) R.string.jellyfin_desc_found else R.string.jellyfin_desc),
        aside = {
            StatusPanel(
                // A failure by the ring and its words: its message carries the one mark.
                state = when {
                    vm.busy -> StatusState.Pending
                    error != null -> StatusState.Off
                    chosen != null -> StatusState.Done
                    else -> StatusState.Off
                },
                stateText = stringResource(loginState(vm.busy, failed = error != null, found = chosen != null)),
                label = stringResource(R.string.field_server),
                // Its protocol is printed beside it: the address says the rest.
                value = server.withoutScheme().ifBlank { stringResource(R.string.placeholder_pending) },
                name = chosen?.name,
                // Its protocol printed once the address says it, or the check verified it, with the connection it makes.
                badges = listOfNotNull(if (check == null) server.schemeName() else check.protocol()),
                facts = listOf(check?.let { stringResource(it.connection()) }),
                result = error,
                resultIsError = true,
            )
        },
        room = room,
    ) {
        FoundServers(vm.discovered, chosen, firstServer, vm::addressOf, vm::checkOf) { s ->
            vm.choose(s)
            (if (vm.user.isBlank()) userField else passwordField).tryRequestFocus()
        }
        HlTextField(
            label = stringResource(R.string.field_server),
            value = server,
            onValueChange = { vm.server = it },
            placeholder = stringResource(R.string.placeholder_server),
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.focusRequester(firstField),
        )
        // The account and the button as one block: a credential focused brings the button into view with it.
        Column(Modifier.scrollsAsBlock(), verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                HlTextField(
                    stringResource(R.string.field_user),
                    vm.user,
                    { vm.user = it },
                    Modifier.weight(1f).focusRequester(userField),
                )
                HlTextField(
                    stringResource(R.string.field_password),
                    vm.password,
                    { vm.password = it },
                    password = true,
                    imeAction = ImeAction.Done,
                    onImeAction = ::login,
                    modifier = Modifier.weight(1f).focusRequester(passwordField),
                )
            }
            HlButton(
                stringResource(R.string.sign_in),
                ::login,
                Modifier.padding(top = HlSpace.xs),
                busy = vm.busy,
                busyLabel = stringResource(R.string.signing_in),
            )
        }
    }
}

/**
 * Where the sign-in's focus starts. Signing in [again], the server and user prefilled, on [account], what is left to
 * type: the password, or the user when it is blank. Else on the first server found ([firstServer]), waiting for
 * discovery's first answer ([anyFound]) up to [FIRST_SERVER_MS], as home's first focus waits for its case, then on the
 * address ([address]); servers answering later come under it, focus left where it is.
 */
@Composable
private fun FirstFocus(
    again: Boolean,
    anyFound: () -> Boolean,
    firstServer: FocusRequester,
    address: FocusRequester,
    account: FocusRequester,
) {
    val currentAnyFound by rememberUpdatedState(anyFound)
    LaunchedEffect(Unit) {
        if (again) {
            account.tryRequestFocus()
            return@LaunchedEffect
        }
        val found = withTimeoutOrNull(FIRST_SERVER_MS) { snapshotFlow { currentAnyFound() }.first { it } }
        // Its row laid out first.
        withFrameNanos { }
        if (found == null || !firstServer.tryRequestFocus()) address.tryRequestFocus()
    }
}

/** The protocol an address names ("HTTPS"), or null when it names none and both will be tried. */
private fun String.schemeName(): String? =
    trim().substringBefore("://", "").uppercase().takeIf { it == HTTP || it == HTTPS }

/** An address without the protocol it names, which is printed beside it: "192.168.1.20:8096". */
internal fun String.withoutScheme(): String = trim().let { if (it.schemeName() != null) it.substringAfter("://") else it }.trimEnd('/')

private const val HTTP = "HTTP"
private const val HTTPS = "HTTPS"

/** The protocol a server's check verified, printed in the panel; none while it is checked or silent. */
private fun ServerCheck.protocol(): String? = when (this) {
    is ServerCheck.Encrypted -> HTTPS
    is ServerCheck.Plain -> HTTP
    else -> null
}

/** What the panel says of the connection to the server picked, in the words of Réglages › Bibliothèque. */
@StringRes
private fun ServerCheck.connection(): Int = when (this) {
    is ServerCheck.Encrypted -> R.string.connection_is_encrypted
    is ServerCheck.Plain -> R.string.connection_is_plain
    ServerCheck.Checking -> R.string.checking
    ServerCheck.Silent -> R.string.server_silent
}

private fun ServerCheck.security(): ConnectionSecurity = when (this) {
    is ServerCheck.Encrypted -> ConnectionSecurity.Encrypted
    is ServerCheck.Plain -> ConnectionSecurity.Plain
    ServerCheck.Checking -> ConnectionSecurity.Checking
    ServerCheck.Silent -> ConnectionSecurity.Silent
}

/**
 * The [servers] found on the network, on one plate, each with its check ([checkOf]) and the address signing in reaches
 * ([addressOf]), the [chosen] one checked; none, nothing.
 */
@Composable
private fun FoundServers(
    servers: List<DiscoveredServer>,
    chosen: DiscoveredServer?,
    first: FocusRequester,
    addressOf: (DiscoveredServer) -> String,
    checkOf: (DiscoveredServer) -> ServerCheck,
    onChoose: (DiscoveredServer) -> Unit,
) {
    if (servers.isEmpty()) return
    HlSettingGroup(stringResource(R.string.servers_found)) {
        servers.forEachIndexed { i, s ->
            val address = addressOf(s).withoutScheme()
            HlSettingRow(
                s.name ?: address,
                description = address.takeIf { s.name != null },
                onClick = { onChoose(s) },
                trailing = {
                    ConnectionState(checkOf(s).security())
                    ChoicePlace(s == chosen)
                },
                modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).semantics { selected = s == chosen },
            )
        }
    }
}

/** The sign-in error message, worded to say what to check. */
@Composable
private fun loginErrorText(e: LoginError): String = when (e) {
    LoginError.Refused -> stringResource(R.string.error_credentials)
    LoginError.NoAddress -> stringResource(R.string.error_server_missing)
    is LoginError.InvalidAddress -> stringResource(R.string.error_server_address, e.typed)
    is LoginError.Unreachable -> stringResource(R.string.error_server_unreachable, e.address)
    is LoginError.Certificate -> stringResource(R.string.error_server_certificate, e.address)
    is LoginError.NotJellyfin -> stringResource(R.string.error_not_jellyfin, e.address)
    is LoginError.Unsupported -> when {
        e.product != null && e.product != JELLYFIN -> stringResource(R.string.error_server_other, e.address, e.product)
        else -> stringResource(R.string.error_server_old, e.address, e.version.orEmpty())
    }
    LoginError.Keystore -> stringResource(R.string.error_keystore_sign_in)
    LoginError.NotSaved -> stringResource(R.string.error_not_saved_player)
    LoginError.Failed -> stringResource(R.string.error_server_unexpected)
}

/** The status text of the sign-in panel: a server [found] on the network is named before any sign-in. */
private fun loginState(busy: Boolean, failed: Boolean, found: Boolean): Int = when {
    busy -> R.string.connection_testing
    failed -> R.string.connection_failed
    found -> R.string.server_found
    else -> R.string.connection_untested
}

/** How long the sign-in's first focus waits for the first server found before going to the address. */
internal const val FIRST_SERVER_MS = 1_000L

/** The product name a Jellyfin server reports. */
private const val JELLYFIN = "Jellyfin Server"
