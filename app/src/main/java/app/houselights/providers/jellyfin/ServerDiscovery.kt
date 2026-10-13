package app.houselights.providers.jellyfin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

@Serializable
data class DiscoveredServer(
    @SerialName("Address") val address: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Id") val id: String? = null,
)

/** Finds Jellyfin servers on the local network (UDP broadcast on port 7359). */
object ServerDiscovery {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(timeoutMs: Int = 2500): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, DiscoveredServer>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMs
                val msg = "who is JellyfinServer?".toByteArray()
                socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), 7359))
                val buf = ByteArray(4096)
                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val text = String(packet.data, 0, packet.length)
                    runCatching { json.decodeFromString<DiscoveredServer>(text) }
                        .getOrNull()
                        ?.let { found[it.id ?: it.address] = it }
                }
            }
        }
        found.values.toList()
    }
}
