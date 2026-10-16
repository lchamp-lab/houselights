package app.houselights.providers.jellyfin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

@Serializable
data class DiscoveredServer(
    @SerialName("Address") val address: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Id") val id: String? = null,
)

/** Finds Jellyfin servers on the local network (UDP broadcast on port 7359). */
object ServerDiscovery {
    private val json = Json { ignoreUnknownKeys = true }
    private val BROADCAST = InetSocketAddress("255.255.255.255", 7359)

    /** More answers than this are not servers to choose from. */
    private const val MAX_SERVERS = 16

    /**
     * The servers that answered within [timeoutMs], all answers together: devices answering again
     * and again never hold it longer, and leaving the screen stops it.
     */
    suspend fun discover(timeoutMs: Int = 2500, target: InetSocketAddress = BROADCAST): List<DiscoveredServer> =
        withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, DiscoveredServer>()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val msg = "who is JellyfinServer?".toByteArray()
                    socket.send(DatagramPacket(msg, msg.size, target))
                    val buf = ByteArray(4096)
                    while (isActive && found.size < MAX_SERVERS) {
                        val packet = DatagramPacket(buf, buf.size)
                        if (!socket.receiveBefore(deadline, packet)) break
                        val text = String(packet.data, 0, packet.length)
                        runCatching { json.decodeFromString<DiscoveredServer>(text) }
                            .getOrNull()
                            ?.let { found[it.id ?: it.address] = it }
                    }
                }
            }
            found.values.toList()
        }

    /** Receives one packet into [packet]; false once [deadline] (System.nanoTime) has passed without one. */
    private fun DatagramSocket.receiveBefore(deadline: Long, packet: DatagramPacket): Boolean {
        val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (left <= 0) return false
        soTimeout = left.toInt()
        return try {
            receive(packet)
            true
        } catch (_: SocketTimeoutException) {
            false
        }
    }
}
