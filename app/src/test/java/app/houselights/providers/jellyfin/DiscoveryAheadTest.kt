package app.houselights.providers.jellyfin

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Jellyfin's discovery started ahead, as « Serveur Jellyfin » takes focus on Welcome: the sign-in opening soon after
 * gets the servers already found at once, then those answering later, from that one discovery; one opening later asks
 * the network again.
 */
class DiscoveryAheadTest {
    /** A network whose discoveries answer as the test sends servers, each ending once closed. */
    private class Network : JellyfinNetwork {
        val runs = mutableListOf<Channel<DiscoveredServer>>()

        override fun discover(): Flow<DiscoveredServer> = flow {
            val answers = Channel<DiscoveredServer>(Channel.UNLIMITED)
            runs += answers
            emitAll(answers)
        }

        override suspend fun check(found: DiscoveredServer): ServerCheck = ServerCheck.Silent

        override suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo> = throw IOException("offline")

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult = throw IOException("offline")
    }

    @Test
    fun aSignInSoonAfterGetsTheServersFoundAheadThenTheOthers() = runTest {
        val network = Network()
        var now = 0L
        val ahead = DiscoveryAhead(network, backgroundScope, clock = { now })
        ahead.lookAhead()
        ahead.lookAhead()
        runCurrent()
        network.runs.single().send(SALON)
        runCurrent()

        now = 3_000L
        val found = mutableListOf<String?>()
        val signIn = launch { ahead.discover().collect { found += it.name } }
        runCurrent()
        assertEquals("found ahead, at once", listOf("Salon"), found)
        network.runs.single().send(BUREAU)
        runCurrent()
        assertEquals(listOf("Salon", "Bureau"), found)
        network.runs.single().close()
        runCurrent()
        assertTrue("it ends with the discovery", signIn.isCompleted)
        assertEquals("one discovery for both", 1, network.runs.size)

        now = 60_000L
        val later = launch { ahead.discover().collect { found += it.name } }
        runCurrent()
        assertEquals("a sign-in long after asks again", 2, network.runs.size)
        later.cancel()
    }

    private companion object {
        val SALON = DiscoveredServer("http://192.168.1.20:8096", "Salon", "a")
        val BUREAU = DiscoveredServer("http://192.168.1.31:8096", "Bureau", "b")
    }
}
