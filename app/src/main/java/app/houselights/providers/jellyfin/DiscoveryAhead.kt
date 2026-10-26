package app.houselights.providers.jellyfin

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [network] whose discovery may start ahead of the sign-in ([lookAhead]: « Serveur Jellyfin » taking focus on Welcome,
 * as DESIGN.md prepares the next screen during the gesture before it). A sign-in opening within [KEEP_MS] of that start
 * is given the servers found so far at once, then those still answering as they answer; later, it asks the network
 * again. Discovery runs in [scope], the app's, so leaving Welcome does not stop it.
 */
class DiscoveryAhead(
    private val network: JellyfinNetwork,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : JellyfinNetwork by network {
    /** What a discovery found so far, and whether it ended. */
    private data class Found(val servers: List<DiscoveredServer> = emptyList(), val done: Boolean = false)

    /** A discovery started at [startedAt] ([clock]). */
    private class Run(val startedAt: Long, val found: StateFlow<Found>)

    private var last: Run? = null

    /** Starts looking for servers now, unless a discovery started within [KEEP_MS] still serves the sign-in. */
    fun lookAhead() = keepLooking()

    override fun discover(): Flow<DiscoveredServer> = flow {
        var sent = 0
        emitAll(
            running().transformWhile { found ->
                found.servers.drop(sent).forEach { emit(it) }
                sent = found.servers.size
                !found.done
            },
        )
    }

    /** What the discovery serving a sign-in now found: the last one, while recent, else one started now. */
    @Synchronized
    private fun running(): StateFlow<Found> {
        keepLooking()
        return checkNotNull(last).found
    }

    /** Starts a discovery unless the last one started within [KEEP_MS]. */
    @Synchronized
    private fun keepLooking() {
        val now = clock()
        if (last?.let { now - it.startedAt < KEEP_MS } == true) return
        val found = MutableStateFlow(Found())
        scope.launch {
            try {
                network.discover().collect { server -> found.update { it.copy(servers = it.servers + server) } }
            } finally {
                found.update { it.copy(done = true) }
            }
        }
        last = Run(now, found)
    }

    private companion object {
        /** How long a discovery serves the sign-ins opening after its start: past it, servers may have come or gone. */
        const val KEEP_MS = 10_000L
    }
}
