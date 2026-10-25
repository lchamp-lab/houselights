package app.houselights.journal

import app.houselights.journal.HomeAddress.Interface
import app.houselights.journal.HomeAddress.Network
import app.houselights.journal.HomeAddress.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

/** The address a phone at home reaches: the home network's, never a VPN's, a hotspot's or a mobile one's. */
class HomeAddressTest {
    private fun address(text: String) = InetAddress.getByName(text)

    private val wifi = Network(listOf(address("fe80::1"), address("192.168.1.53")), Transport.WIFI)
    private val ethernet = Network(listOf(address("192.168.1.10")), Transport.ETHERNET)
    private val vpn = Network(listOf(address("10.8.0.2")), Transport.VPN)

    @Test
    fun theActiveNetworksAddressComesFirst() {
        assertEquals(address("192.168.1.53"), HomeAddress.choose(listOf(ethernet, wifi.copy(active = true)), emptyList()))
        assertEquals(address("192.168.1.10"), HomeAddress.choose(listOf(wifi, ethernet.copy(active = true)), emptyList()))
    }

    @Test
    fun behindAVpnItIsTheHomeNetworksEthernetFirst() {
        assertEquals(address("192.168.1.53"), HomeAddress.choose(listOf(vpn.copy(active = true), wifi), emptyList()))
        assertEquals(address("192.168.1.10"), HomeAddress.choose(listOf(vpn.copy(active = true), wifi, ethernet), emptyList()))
        assertNull(HomeAddress.choose(listOf(vpn.copy(active = true)), emptyList()))
    }

    @Test
    fun aNetworkWithoutAPrivateIpv4AddressLeavesItToTheNext() {
        val public = Network(listOf(address("82.64.1.2"), address("2a01:e0a::1")), Transport.ETHERNET, active = true)
        assertEquals(address("192.168.1.53"), HomeAddress.choose(listOf(public, wifi), emptyList()))
        // A loopback or link-local address is no home network.
        val local = Network(listOf(address("127.0.0.1"), address("169.254.3.4"), address("::1")), Transport.WIFI, active = true)
        assertNull(HomeAddress.choose(listOf(local), emptyList()))
    }

    @Test
    fun aMobileNetworkIsNoHomeNetworkUnlessActive() {
        val mobile = Network(listOf(address("10.64.0.1")), Transport.OTHER)
        assertNull(HomeAddress.choose(listOf(mobile), emptyList()))
        assertEquals(address("10.64.0.1"), HomeAddress.choose(listOf(mobile.copy(active = true)), emptyList()))
    }

    @Test
    fun withoutTheSystemsNetworksTheInterfacesTellItEthernetFirst() {
        val faces = listOf(
            Interface("tun0", listOf(address("10.8.0.2"))),
            Interface("ap0", listOf(address("192.168.43.1"))),
            Interface("wlan0", listOf(address("192.168.1.53"))),
            Interface("rmnet0", listOf(address("10.1.2.3"))),
            Interface("eth0", listOf(address("172.20.1.2"))),
        )
        assertEquals(address("172.20.1.2"), HomeAddress.choose(emptyList(), faces))
        assertEquals(address("192.168.1.53"), HomeAddress.choose(emptyList(), faces.filter { it.name != "eth0" }))
        assertEquals(address("192.168.1.2"), HomeAddress.choose(emptyList(), listOf(Interface("br0", listOf(address("192.168.1.2"))))))
        // A VPN, a PPP link, the player's hotspot, mobile data or Wi-Fi Direct is no home network.
        val outside = listOf("tun0", "ppp0", "ap0", "rmnet0", "p2p0").map { Interface(it, listOf(address("192.168.49.1"))) }
        assertNull(HomeAddress.choose(emptyList(), outside))
    }

    @Test
    fun theSystemsNetworksComeBeforeTheInterfaces() {
        val faces = listOf(Interface("eth0", listOf(address("172.20.1.2"))))
        assertEquals(address("192.168.1.53"), HomeAddress.choose(listOf(wifi.copy(active = true)), faces))
        assertEquals(address("172.20.1.2"), HomeAddress.choose(listOf(vpn.copy(active = true)), faces))
    }
}
