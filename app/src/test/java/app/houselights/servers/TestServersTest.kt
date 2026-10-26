package app.houselights.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the servers' tests find nfs-fragments, the NFS NAS sending its replies in fragments: on this machine unless an
 * address is named for it, and nowhere known when the servers are named on another machine without it, so that
 * NfsFragmentsServerTest is skipped there rather than failing against this machine's 127.0.0.2. Needs no server.
 */
class TestServersTest {
    private fun fragmentsHost(vararg env: Pair<String, String>) = TestServers.fragmentsHostIn(mapOf(*env)::get)

    @Test
    fun theFragmentsNasIsOnThisMachineUnlessNamed() {
        assertEquals("127.0.0.2", fragmentsHost())
        assertEquals("127.0.0.2", fragmentsHost(HOST to " ", FRAGMENTS_HOST to ""))
        assertEquals("192.168.1.20", fragmentsHost(FRAGMENTS_HOST to "192.168.1.20"))
    }

    @Test
    fun serversOnAnotherMachineLeaveTheFragmentsNasUnknownUnlessNamed() {
        assertNull(fragmentsHost(HOST to "desktop"))
        assertEquals("192.168.1.21", fragmentsHost(HOST to "desktop", FRAGMENTS_HOST to "192.168.1.21"))
    }

    private companion object {
        const val HOST = "HOUSELIGHTS_TEST_SERVERS_HOST"
        const val FRAGMENTS_HOST = "HOUSELIGHTS_TEST_SERVERS_FRAGMENTS_HOST"
    }
}
