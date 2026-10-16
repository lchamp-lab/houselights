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
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.jellyfin.JellyfinNetwork
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/** Why signing in to Jellyfin failed. */
sealed interface LoginError {
    /** Wrong user name or password. */
    data object Refused : LoginError

    /** Nothing answered at [address]. */
    data class Unreachable(val address: String) : LoginError

    /** Something answered at [address], but not a Jellyfin server. */
    data class NotJellyfin(val address: String) : LoginError

    /** Anything else: the details are in the log. */
    data object Failed : LoginError
}

/** Signing in to a Jellyfin server, which becomes the source once [sources] switched to it. */
class JellyfinLoginViewModel(
    private val settings: SettingsStore,
    private val sources: SourceSwitcher,
    private val network: JellyfinNetwork = JellyfinNetwork.Lan,
) : ViewModel() {
    var server by mutableStateOf("")
    var user by mutableStateOf("")
    var password by mutableStateOf("")
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

    fun login(onLoggedIn: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            var address = JellyfinClient.normalizeUrl(server)
            suspendRunCatching {
                val deviceId = settings.ensureDeviceId()
                val (reached, info) = network.reach(server, deviceId)
                address = reached
                val auth = network.authenticate(reached, deviceId, AuthRequest(user.trim(), password))
                sources.switchTo(SourceConfig.Jellyfin(reached, info.serverName, auth.user.id, auth.user.name, auth.accessToken))
            }.onSuccess {
                onLoggedIn()
            }.onFailure { e ->
                Log.w("Jellyfin", "Sign-in to $address failed", e)
                error = when {
                    e is HttpException && e.code() == HTTP_UNAUTHORIZED -> LoginError.Refused
                    e is SerializationException -> LoginError.NotJellyfin(address)
                    e is IOException -> LoginError.Unreachable(address)
                    else -> LoginError.Failed
                }
            }
            busy = false
        }
    }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401

        fun factory(app: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { JellyfinLoginViewModel(app.settings, app.sources) }
        }
    }
}
