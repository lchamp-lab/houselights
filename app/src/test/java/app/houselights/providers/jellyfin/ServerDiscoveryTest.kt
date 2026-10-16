package app.houselights.providers.jellyfin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class ServerDiscoveryTest {
    /** A device that answers again and again, as fast as it can, until the test ends. */
    @Test
    fun aDeviceAnsweringOverAndOverNeverHoldsTheDiscoveryLonger() {
        val loopback = InetAddress.getLoopbackAddress()
        val device = DatagramSocket(0, loopback)
        val answering = thread {
            val ask = DatagramPacket(ByteArray(256), 256)
            device.receive(ask)
            val answer = """{"Address":"http://192.168.1.5:8096","Name":"Maison","Id":"s1"}""".toByteArray()
            while (!device.isClosed) {
                runCatching { device.send(DatagramPacket(answer, answer.size, ask.socketAddress)) }
                Thread.sleep(20)
            }
        }
        try {
            val started = System.nanoTime()
            val found = runBlocking { ServerDiscovery.discover(timeoutMs = 500, target = InetSocketAddress(loopback, device.localPort)) }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(listOf("Maison"), found.map { it.name })
            assertTrue("Took $tookMs ms", tookMs < 1_500)
        } finally {
            device.close()
            answering.join(1_000)
        }
    }
}
