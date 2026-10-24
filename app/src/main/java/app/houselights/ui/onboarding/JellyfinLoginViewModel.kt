package app.houselights.ui.onboarding

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.FailureKind
import app.houselights.core.SourceConfig
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.InvalidServerAddress
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.jellyfin.JellyfinNetwork
import app.houselights.providers.jellyfin.ReachFailure
import app.houselights.providers.jellyfin.ServerCheck
import app.houselights.providers.jellyfin.UnsupportedServer
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toWriteError
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Why signing in to Jellyfin failed. */
sealed interface LoginError {
    /** Wrong user name or password. */
    data object Refused : LoginError

    /** No server was given. */
    data object NoAddress : LoginError

    /** [typed] is not a server address; no network request was made. */
    data class InvalidAddress(val typed: String) : LoginError

    /** Nothing answered at [address]. */
    data class Unreachable(val address: String) : LoginError

    /** The server at [address] answers, but TLS refuses its certificate. */
    data class Certificate(val address: String) : LoginError

    /** Something answered at [address], but not a Jellyfin server. */
    data class NotJellyfin(val address: String) : LoginError

    /** The server at [address] is Jellyfin older than 10.9 ([version]), or another [product]. */
    data class Unsupported(val address: String, val product: String?, val version: String?) : LoginError

    /** Signed in, but the Keystore refused to encrypt the server's token: nothing was saved. */
    data object Keystore : LoginError

    /** Storage refused to write the device id or the chosen server: nothing was saved. */
    data object NotSaved : LoginError

    /** Anything else; the details are in the log. */
    data object Failed : LoginError
}

/** The actionable [LoginError] for a sign-in at [address] (where the server was reached, else what was typed). */
internal fun loginError(e: Throwable, address: String): LoginError = when (e) {
    is SavingFailed -> e.loginError()
    is InvalidServerAddress -> if (e.input.isBlank()) LoginError.NoAddress else LoginError.InvalidAddress(e.input)
    is UnsupportedServer -> LoginError.Unsupported(e.url, e.product, e.version)
    else -> answerError(e, address)
}

/** Maps a network or server failure at [address], or at a [ReachFailure]'s own address, to a sign-in error. */
private fun answerError(e: Throwable, address: String): LoginError {
    val reach = e as? ReachFailure
    val at = reach?.url ?: address
    return when ((reach?.cause ?: e).failureKind()) {
        // Wrong password, or an account the server blocks (disabled, or not allowed at this time).
        FailureKind.REFUSED -> LoginError.Refused
        FailureKind.CERTIFICATE -> LoginError.Certificate(at)
        FailureKind.UNEXPECTED_ANSWER -> LoginError.NotJellyfin(at)
        // Something answered at a probed address, but not as Jellyfin does.
        FailureKind.GONE, FailureKind.SERVER_FAILED -> if (reach != null) LoginError.NotJellyfin(at) else LoginError.Failed
        FailureKind.UNREACHABLE, FailureKind.IO -> LoginError.Unreachable(at)
        FailureKind.KEYSTORE -> LoginError.Keystore
        FailureKind.STORAGE_FULL, FailureKind.STORAGE_FAILED -> LoginError.NotSaved
        else -> LoginError.Failed
    }
}

/** A failed device write during sign-in, never a server error, whatever [cause] (a full disk is an IOException too). */
internal class SavingFailed(override val cause: Throwable) : Exception(cause)

/** Which part of the device refused the write: Keystore, storage, or something else (see the log). */
private fun SavingFailed.loginError(): LoginError = when (cause.toWriteError()) {
    UiError.KEYSTORE -> LoginError.Keystore
    UiError.NOT_SAVED -> LoginError.NotSaved
    else -> LoginError.Failed
}

/** Runs [block], a write to the device itself, wrapping any failure in [SavingFailed]. */
private suspend fun <T> saving(block: suspend () -> T): T = suspendRunCatching { block() }.getOrElse { throw SavingFailed(it) }

/** Whether this address and [typed] name one server: the same once both are written in full. */
private fun String.sameServer(typed: String): Boolean =
    typed.isNotBlank() && JellyfinClient.normalizeUrl(this) == JellyfinClient.normalizeUrl(typed)

/** What the sign-in form starts with. */
sealed interface LoginFill {
    /** The current server's address and user, to sign in to it again. */
    data object Current : LoginFill

    /** Nothing: another server, or another account. */
    data object Blank : LoginFill

    /** A server that no longer takes its token: its [address] and [user]. */
    data class Server(val address: String, val user: String?) : LoginFill
}

/** The address and user the form starts with for [fill] while [current] is the source; null for an empty form. */
internal fun prefill(fill: LoginFill, current: SourceConfig?): Pair<String, String>? = when (fill) {
    LoginFill.Current -> (current as? SourceConfig.Jellyfin)?.let { it.serverUrl to it.userName.orEmpty() }
    LoginFill.Blank -> null
    is LoginFill.Server -> fill.address to fill.user.orEmpty()
}

/** Signs in to a Jellyfin server, which becomes the source once [sources] switches to it. */
class JellyfinLoginViewModel(
    private val settings: SettingsStore,
    private val sources: SourceSwitcher,
    private val network: JellyfinNetwork = JellyfinNetwork.Lan,
    fill: LoginFill = LoginFill.Current,
) : ViewModel() {
    var server by Field()
    var user by Field()
    var password by Field()
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<LoginError?>(null)
        private set

    /** Servers that answered discovery on the network, offered as choices: any device can answer one. */
    var discovered by mutableStateOf<List<DiscoveredServer>>(emptyList())
        private set

    /** What checking each server found said; one still checked is missing. */
    private var checks by mutableStateOf<Map<DiscoveredServer, ServerCheck>>(emptyMap())

    /** Each server found's check, which signing in to it waits for. */
    private val checking = mutableMapOf<DiscoveredServer, Deferred<ServerCheck>>()

    /** Signing in again to a server known here: its address and user are prefilled, only the password is asked. */
    val again: Boolean

    /** The server last picked among [discovered], and what picking it, then its check, put in the address field. */
    private var pick by mutableStateOf<DiscoveredServer?>(null)
    private var picked by mutableStateOf<String?>(null)

    init {
        val known = prefill(fill, settings.state.value.source)
        again = known != null
        if (known != null) {
            server = known.first
            user = known.second
        }
        viewModelScope.launch {
            discovered = network.discover()
            // Each checked at once, in the background, so its row says how it will be reached before it is picked.
            discovered.forEach { found -> checking[found] = async { check(found) } }
        }
    }

    private suspend fun check(found: DiscoveredServer): ServerCheck {
        val result = suspendRunCatching { network.check(found) }
            .onFailure { Log.w("Jellyfin", "Checking ${found.address} failed", it) }
            .getOrDefault(ServerCheck.Silent)
        checks += found to result
        // The address the check verified, in place of the one picking gave, unless it was edited meanwhile.
        if (found == pick && server == picked) {
            server = addressOf(found)
            picked = server
        }
        return result
    }

    /** What checking [found] said, [ServerCheck.Checking] until it has. */
    fun checkOf(found: DiscoveredServer): ServerCheck = checks[found] ?: ServerCheck.Checking

    /** Where signing in reaches [found]: the address its check verified, else the one it announced. */
    fun addressOf(found: DiscoveredServer): String = checkOf(found).url ?: found.address

    /** The server picked while the address field holds what picking it, then its check, put there. */
    val pickedServer: DiscoveredServer?
        get() = pick?.takeIf { server == picked }

    /** The check of [pickedServer], which signing in follows. */
    val pickedCheck: ServerCheck?
        get() = pickedServer?.let(::checkOf)

    /** The server picked, else the one found that the address names, as announced or where its check reaches it. */
    val chosen: DiscoveredServer?
        get() = pickedServer ?: discovered.find { addressOf(it).sameServer(server) || it.address.sameServer(server) }

    /** A form field: changing it clears the last attempt's error, which no longer applies. */
    private inner class Field : ReadWriteProperty<Any?, String> {
        private var typed by mutableStateOf("")

        override fun getValue(thisRef: Any?, property: KProperty<*>): String = typed

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            if (value != typed) error = null
            typed = value
        }
    }

    /** Picks a server found on the network: its address where it will be reached, without a protocol while it is checked. */
    fun choose(found: DiscoveredServer) {
        pick = found
        server = if (checkOf(found) == ServerCheck.Checking) found.address.withoutScheme() else addressOf(found)
        picked = server
    }

    fun login(onLoggedIn: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        val asked = typed()
        val found = pickedServer
        viewModelScope.launch {
            var address = JellyfinClient.normalizeUrl(server)
            suspendRunCatching {
                val deviceId = saving { settings.ensureDeviceId() }
                // A server picked is reached where its check says, over HTTPS alone once encrypted; else as typed.
                val input = found?.let { target(it) } ?: server
                address = JellyfinClient.normalizeUrl(input)
                val (reached, info) = network.reach(input, deviceId)
                address = reached
                val auth = network.authenticate(reached, deviceId, AuthRequest(user.trim(), password))
                saving { sources.switchTo(SourceConfig.Jellyfin(reached, info.serverName, auth.user.id, auth.user.name, auth.accessToken)) }
            }.onSuccess {
                onLoggedIn()
            }.onFailure { e ->
                Log.w("Jellyfin", "Sign-in to $address failed", e)
                // Report only if the form is unchanged: edited fields are an attempt not yet made.
                if (typed() == asked) error = loginError(e, address)
            }
            busy = false
        }
    }

    /**
     * Where signing in reaches [found], once its check has said: checked again first if it did not answer, so its
     * HTTPS address is never passed over for the plain one; still silent, the address it announced.
     */
    private suspend fun target(found: DiscoveredServer): String {
        var result = checks[found] ?: checking[found]?.await() ?: ServerCheck.Silent
        if (result == ServerCheck.Silent) {
            checks -= found
            result = check(found)
        }
        return result.url ?: found.address
    }

    /** What signing in used: a server picked stays the same while the field holds its address, its check's included. */
    private fun typed() = listOf(pickedServer ?: server, user, password)

    companion object {
        fun factory(app: AppContainer, fill: LoginFill): ViewModelProvider.Factory = viewModelFactory {
            initializer { JellyfinLoginViewModel(app.settings, app.sources, fill = fill) }
        }
    }
}
