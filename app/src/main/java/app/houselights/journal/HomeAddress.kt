package app.houselights.journal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** The player's private IPv4 address on the home network (10/8, 172.16/12, 192.168/16), where a phone reaches it. */
object HomeAddress {
    /** What carries a network, as the system says it. */
    enum class Transport { ETHERNET, WIFI, VPN, OTHER }

    /** A network the system is on: its [addresses], its [transport], whether it is the [active] one. */
    data class Network(val addresses: List<InetAddress>, val transport: Transport, val active: Boolean = false)

    /** An interface that is up, by its [name] ("eth0", "wlan0", "tun0"). */
    data class Interface(val name: String, val addresses: List<InetAddress>)

    /** The address, from the system's networks, else from the interfaces; null without one. */
    fun find(context: Context): Inet4Address? = choose(networks(context), interfaces())

    /**
     * The active network's address unless it is a VPN; else an Ethernet network's, then a Wi-Fi one's. Without one,
     * an interface's, Ethernet first, then Wi-Fi, never a VPN's, a hotspot's, a mobile one's or a Wi-Fi Direct one's.
     */
    internal fun choose(networks: List<Network>, interfaces: List<Interface>): Inet4Address? =
        networks.mapNotNull { network -> rank(network)?.let { it to network.addresses } }.best()
            ?: interfaces.mapNotNull { face -> rank(face.name)?.let { it to face.addresses } }.best()

    private fun rank(network: Network): Int? = when {
        network.transport == Transport.VPN -> null
        network.active -> 0
        network.transport == Transport.ETHERNET -> 1
        network.transport == Transport.WIFI -> 2
        else -> null
    }

    private fun rank(name: String): Int? = when {
        OUTSIDE.any(name::startsWith) -> null
        name.startsWith("eth") -> 0
        name.startsWith("wlan") -> 1
        else -> 2
    }

    /** The first private IPv4 address of the best ranked addresses that hold one. */
    private fun List<Pair<Int, List<InetAddress>>>.best(): Inet4Address? =
        sortedBy { it.first }.firstNotNullOfOrNull { (_, addresses) -> privateIpv4(addresses) }

    private fun privateIpv4(addresses: List<InetAddress>): Inet4Address? =
        addresses.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }

    /** VPNs, PPP links, the player's own hotspot, mobile data and Wi-Fi Direct: no home network a phone shares. */
    private val OUTSIDE = listOf("tun", "ppp", "ap", "rmnet", "p2p")

    private fun networks(context: Context): List<Network> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return try {
            val active = manager.activeNetwork

            // Listed at once, as a callback would tell them later; still answered on every Android version.
            @Suppress("DEPRECATION")
            val all = manager.allNetworks
            all.mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                val link = manager.getLinkProperties(network) ?: return@mapNotNull null
                Network(link.linkAddresses.map { it.address }, transport(capabilities), network == active)
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun transport(capabilities: NetworkCapabilities) = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
        else -> Transport.OTHER
    }

    /** The interfaces that are up; none when the system cannot list them. */
    private fun interfaces(): List<Interface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .map { Interface(it.name, it.inetAddresses.toList()) }
    } catch (_: SocketException) {
        emptyList()
    }
}
