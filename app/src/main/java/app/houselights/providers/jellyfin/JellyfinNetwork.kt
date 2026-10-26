package app.houselights.providers.jellyfin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Network calls used by Jellyfin sign-in: discovery, checking a server found, reaching an address, authentication.
 * An interface so tests can stand in for a server.
 */
interface JellyfinNetwork {
    /** Servers answering a discovery on the local network, each as it answers, until the discovery ends. */
    fun discover(): Flow<DiscoveredServer>

    /** See [JellyfinClient.check]. */
    suspend fun check(found: DiscoveredServer): ServerCheck

    /** See [JellyfinClient.reach]. */
    suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo>

    suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult

    /** Production implementation. */
    companion object Lan : JellyfinNetwork {
        override fun discover(): Flow<DiscoveredServer> = channelFlow { ServerDiscovery.discover { trySend(it) } }

        override suspend fun check(found: DiscoveredServer) = JellyfinClient.check(found)

        override suspend fun reach(input: String, deviceId: String) = JellyfinClient.reach(input, deviceId)

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest) =
            JellyfinClient(url, deviceId, token = null).api.authenticate(request)
    }
}
