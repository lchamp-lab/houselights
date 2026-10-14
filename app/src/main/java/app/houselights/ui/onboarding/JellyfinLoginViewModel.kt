package app.houselights.ui.onboarding

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.AppContainer
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.jellyfin.ServerDiscovery
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

    data class Failed(val address: String, val detail: String) : LoginError
}

class JellyfinLoginViewModel(private val app: AppContainer) : ViewModel() {
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
        val current = app.settings.state.value.source as? SourceConfig.Jellyfin
        again = current != null
        if (current != null) {
            server = current.serverUrl
            user = current.userName.orEmpty()
        }
        viewModelScope.launch { discovered = ServerDiscovery.discover() }
    }

    fun login(onLoggedIn: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            var address = JellyfinClient.normalizeUrl(server)
            suspendRunCatching {
                val deviceId = app.settings.ensureDeviceId()
                val (reached, info) = JellyfinClient.reach(server, deviceId)
                address = reached
                val auth = JellyfinClient(reached, deviceId, token = null).api.authenticate(AuthRequest(user.trim(), password))
                app.sources.switchTo(SourceConfig.Jellyfin(reached, info.serverName, auth.user.id, auth.user.name, auth.accessToken))
            }.onSuccess {
                onLoggedIn()
            }.onFailure { e ->
                Log.w("Jellyfin", "Sign-in to $address failed", e)
                error = when {
                    e is HttpException && e.code() == HTTP_UNAUTHORIZED -> LoginError.Refused
                    e is SerializationException -> LoginError.NotJellyfin(address)
                    e is IOException -> LoginError.Unreachable(address)
                    else -> LoginError.Failed(address, e.message ?: e.javaClass.simpleName)
                }
            }
            busy = false
        }
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
