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
import app.houselights.providers.jellyfin.UnsupportedServer
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toWriteError
import kotlinx.coroutines.launch
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Why signing in to Jellyfin failed. */
sealed interface LoginError {
    /** Wrong user name or password. */
    data object Refused : LoginError

    /** No server was given. */
    data object NoAddress : LoginError

    /** What was typed ([typed]) is not a server address: nothing was asked of the network. */
    data class InvalidAddress(val typed: String) : LoginError

    /** Nothing answered at [address]. */
    data class Unreachable(val address: String) : LoginError

    /** The server at [address] answers, but TLS refuses its certificate. */
    data class Certificate(val address: String) : LoginError

    /** Something answered at [address], but not a Jellyfin server. */
    data class NotJellyfin(val address: String) : LoginError

    /** The server at [address] is a Jellyfin before 10.9 ([version]), or another [product]. */
    data class Unsupported(val address: String, val product: String?, val version: String?) : LoginError

    /** Signed in, but the device's Keystore refused to seal the server's token: nothing was saved. */
    data object Keystore : LoginError

    /** The device's storage refused to write the device's id or the server chosen: nothing was saved. */
    data object NotSaved : LoginError

    /** Anything else: the details are in the log. */
    data object Failed : LoginError
}

/**
 * What signing in at [address] (where the server was reached, else what was typed) failed on, in
 * words the user can act on. A failure to reach the server names the address whose failure says
 * the most ([ReachFailure]).
 */
internal fun loginError(e: Throwable, address: String): LoginError = when (e) {
    is SavingFailed -> e.loginError()
    is InvalidServerAddress -> if (e.input.isBlank()) LoginError.NoAddress else LoginError.InvalidAddress(e.input)
    is UnsupportedServer -> LoginError.Unsupported(e.url, e.product, e.version)
    else -> answerError(e, address)
}

/**
 * What the network or the server answered at [address], or where the server was reached
 * ([ReachFailure], read by its cause): what the failure comes down to, in sign-in's words.
 */
private fun answerError(e: Throwable, address: String): LoginError {
    val reach = e as? ReachFailure
    val at = reach?.url ?: address
    return when ((reach?.cause ?: e).failureKind()) {
        // A password refused, or an account the server keeps out (disabled, or not allowed now).
        FailureKind.REFUSED -> LoginError.Refused
        FailureKind.CERTIFICATE -> LoginError.Certificate(at)
        FailureKind.UNEXPECTED_ANSWER -> LoginError.NotJellyfin(at)
        // Something answered where the server was looked for, but not as Jellyfin does.
        FailureKind.GONE, FailureKind.SERVER_FAILED -> if (reach != null) LoginError.NotJellyfin(at) else LoginError.Failed
        FailureKind.UNREACHABLE, FailureKind.IO -> LoginError.Unreachable(at)
        FailureKind.KEYSTORE -> LoginError.Keystore
        FailureKind.STORAGE_FULL, FailureKind.STORAGE_FAILED -> LoginError.NotSaved
        else -> LoginError.Failed
    }
}

/**
 * A write to the device itself failed while signing in ([cause]): its storage or its Keystore, never
 * the server, whatever the exception (a full disk is an IOException too).
 */
internal class SavingFailed(override val cause: Throwable) : Exception(cause)

/** What the device refused: its Keystore, its storage, or something else, which the log tells. */
private fun SavingFailed.loginError(): LoginError = when (cause.toWriteError()) {
    UiError.KEYSTORE -> LoginError.Keystore
    UiError.NOT_SAVED -> LoginError.NotSaved
    else -> LoginError.Failed
}

/** [block], a write to the device itself: its failure is a [SavingFailed]. */
private suspend fun <T> saving(block: suspend () -> T): T = suspendRunCatching { block() }.getOrElse { throw SavingFailed(it) }

/** Signing in to a Jellyfin server, which becomes the source once [sources] switched to it. */
class JellyfinLoginViewModel(
    private val settings: SettingsStore,
    private val sources: SourceSwitcher,
    private val network: JellyfinNetwork = JellyfinNetwork.Lan,
) : ViewModel() {
    var server by Field()
    var user by Field()
    var password by Field()
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<LoginError?>(null)
        private set

    /** Servers that answered on the network, for the user to choose: any device can answer a discovery. */
    var discovered by mutableStateOf<List<DiscoveredServer>>(emptyList())
        private set

    /** Signing in again to the server in use: its address and the user are filled in, only the password is asked. */
    val again: Boolean

    init {
        val current = settings.state.value.source as? SourceConfig.Jellyfin
        again = current != null
        if (current != null) {
            server = current.serverUrl
            user = current.userName.orEmpty()
        }
        viewModelScope.launch { discovered = network.discover() }
    }

    /** A field of the form: changed, it makes what signing in said of the last try stale, and no longer said. */
    private inner class Field : ReadWriteProperty<Any?, String> {
        private var typed by mutableStateOf("")

        override fun getValue(thisRef: Any?, property: KProperty<*>): String = typed

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            if (value != typed) error = null
            typed = value
        }
    }

    fun login(onLoggedIn: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            var address = JellyfinClient.normalizeUrl(server)
            suspendRunCatching {
                val deviceId = saving { settings.ensureDeviceId() }
                val (reached, info) = network.reach(server, deviceId)
                address = reached
                val auth = network.authenticate(reached, deviceId, AuthRequest(user.trim(), password))
                saving { sources.switchTo(SourceConfig.Jellyfin(reached, info.serverName, auth.user.id, auth.user.name, auth.accessToken)) }
            }.onSuccess {
                onLoggedIn()
            }.onFailure { e ->
                Log.w("Jellyfin", "Sign-in to $address failed", e)
                error = loginError(e, address)
            }
            busy = false
        }
    }

    companion object {
        fun factory(app: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { JellyfinLoginViewModel(app.settings, app.sources) }
        }
    }
}
