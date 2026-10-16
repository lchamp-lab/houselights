package app.houselights.providers.jellyfin

/**
 * What signing in to Jellyfin asks of the network: finding servers, reaching the one typed, then
 * signing in on it. Apart from the screen, so a test answers in place of a server.
 */
interface JellyfinNetwork {
    /** Servers that answered a discovery on the local network. */
    suspend fun discover(): List<DiscoveredServer>

    /** See [JellyfinClient.reach]. */
    suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo>

    suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult

    /** The real network. */
    companion object Lan : JellyfinNetwork {
        override suspend fun discover() = ServerDiscovery.discover()

        override suspend fun reach(input: String, deviceId: String) = JellyfinClient.reach(input, deviceId)

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest) =
            JellyfinClient(url, deviceId, token = null).api.authenticate(request)
    }
}
