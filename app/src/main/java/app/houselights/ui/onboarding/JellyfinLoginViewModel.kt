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

/** Signs in to a Jellyfin server, which becomes the source once [sources] switches to it. */
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

    /** Servers that answered discovery on the network, offered as choices: any device can answer one. */
    var discovered by mutableStateOf<List<DiscoveredServer>>(emptyList())
        private set

    /** Signing in again to the current server: its address and user are prefilled, only the password is asked. */
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

    /** A form field: changing it clears the last attempt's error, which no longer applies. */
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
        val asked = typed()
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
                // Report only if the form is unchanged: edited fields are an attempt not yet made.
                if (typed() == asked) error = loginError(e, address)
            }
            busy = false
        }
    }

    private fun typed() = listOf(server, user, password)

    companion object {
        fun factory(app: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { JellyfinLoginViewModel(app.settings, app.sources) }
        }
    }
}
