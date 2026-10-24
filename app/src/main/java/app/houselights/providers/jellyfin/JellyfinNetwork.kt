package app.houselights.providers.jellyfin

/**
 * Network calls used by Jellyfin sign-in: discovery, reaching a typed address, authentication.
 * An interface so tests can stand in for a server.
 */
interface JellyfinNetwork {
    /** Servers that answered a discovery on the local network. */
    suspend fun discover(): List<DiscoveredServer>

    /** See [JellyfinClient.reach]; [announced] for a server picked among those found. */
    suspend fun reach(input: String, deviceId: String, announced: Boolean = false): Pair<String, PublicSystemInfo>

    suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult

    /** Production implementation. */
    companion object Lan : JellyfinNetwork {
        override suspend fun discover() = ServerDiscovery.discover()

        override suspend fun reach(input: String, deviceId: String, announced: Boolean) =
            JellyfinClient.reach(input, deviceId, announced = announced)

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest) =
            JellyfinClient(url, deviceId, token = null).api.authenticate(request)
    }
}
