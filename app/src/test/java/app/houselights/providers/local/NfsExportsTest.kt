package app.houselights.providers.local

import app.houselights.core.MalformedAnswerException
import app.houselights.testing.hex
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.Xdr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * An NFS server's exports as its mount service answers EXPORT (RFC 1813, appendix I), read from the answer's bytes,
 * and whether an export's clients take in this player.
 */
class NfsExportsTest {
    /** RPC's accepted reply (RFC 5531): its call's id, REPLY, MSG_ACCEPTED, an empty AUTH_NONE verifier, then [status]. */
    private fun reply(status: String = "00000000") = arrayOf("5e3c9a21", "00000001", "00000000", "00000000", "00000000", status)

    /**
     * As a Synology (DSM 7, Linux's mountd) answers `showmount -e` with three shared folders: video for the home
     * network, photo for two players, the USB disk for anyone. Each string is its length, then its bytes padded to four.
     */
    private val synology = hex(
        *reply(),
        "00000001", "0000000e", "2f766f6c756d65312f766964656f0000", // an export follows: "/volume1/video"
        "00000001", "0000000e", "3139322e3136382e312e302f32340000", // a group follows: "192.168.1.0/24"
        "00000000", // no more groups
        "00000001", "0000000e", "2f766f6c756d65312f70686f746f0000", // an export follows: "/volume1/photo"
        "00000001", "0000000c", "3139322e3136382e312e3432", // a group follows: "192.168.1.42"
        "00000001", "0000000c", "3139322e3136382e312e3433", // a group follows: "192.168.1.43"
        "00000000", // no more groups
        "00000001", "00000014", "2f766f6c756d65555342312f7573627368617265", // an export follows: "/volumeUSB1/usbshare"
        "00000001", "00000001", "2a000000", // a group follows: "*"
        "00000000", // no more groups
        "00000000", // no more exports
    )

    private fun answered(bytes: ByteArray) = NfsExports.ExportResponse().apply { unmarshalling(Xdr(bytes)) }.exports

    @Test
    fun theExportsAreReadWithTheirClients() {
        assertEquals(
            listOf(
                ExportedFolder("/volume1/video", listOf("192.168.1.0/24")),
                ExportedFolder("/volume1/photo", listOf("192.168.1.42", "192.168.1.43")),
                ExportedFolder("/volumeUSB1/usbshare", listOf("*")),
            ),
            answered(synology),
        )
        assertEquals("a list calls each by its last name", listOf("video", "photo", "usbshare"), answered(synology).map { it.name })
        assertEquals("/", ExportedFolder("/", emptyList()).name)
    }

    /** A server exporting nothing, and an export without a group, open to everyone as showmount says « (everyone) ». */
    @Test
    fun anAnswerMayHoldNoExportAndAnExportNoGroup() {
        assertEquals(emptyList<ExportedFolder>(), answered(hex(*reply(), "00000000")))
        val open = hex(*reply(), "00000001", "00000006", "2f6d656469610000", "00000000", "00000000")
        assertEquals(listOf(ExportedFolder("/media", emptyList())), answered(open))
    }

    /** A server whose mount service will not run the procedure: RPC's own failure, as a mount's. */
    @Test
    fun aCallTheServerDoesNotRunFails() {
        val failure = runCatching { answered(hex(*reply(status = "00000003"))) }.exceptionOrNull()
        assertTrue("$failure", failure is RpcException)
    }

    /**
     * An answer cut short, a length past its end or below zero, or more exports than any server holds (a list that never
     * ends) fails rather than read what follows it.
     */
    @Test
    fun aMalformedAnswerFails() {
        val cut = synology.copyOf(synology.size - 30)
        val tooLong = hex(*reply(), "00000001", "7fffffff", "2f766f6c")
        val negative = hex(*reply(), "00000001", "ffffffff", "2f766f6c")
        // Exports of an empty path and no group, one past the most read.
        val endless = hex(*reply(), *Array(1_001) { arrayOf("00000001", "00000000", "00000000") }.flatten().toTypedArray(), "00000000")
        listOf(cut, tooLong, negative, endless, hex(*reply(), "00000001")).forEach { bytes ->
            val failure = runCatching { answered(bytes) }.exceptionOrNull()
            assertTrue("$failure", failure is MalformedAnswerException)
        }
    }

    /** The call asks the mount service (100005), version 3, EXPORT (5), as AUTH_SYS root, and holds nothing more. */
    @Test
    fun theCallAsksTheMountServiceForItsExports() {
        val call = Xdr(512).also { NfsExports.ExportRequest().marshalling(it) }
        val end = call.offset
        call.setOffset(0)
        val head = List(7) { call.int }
        assertEquals(listOf(0, 2, 100_005, 3, 5, 1), head.drop(1))
        // AUTH_SYS's body (RFC 5531 appendix A): a stamp, the machine's name, then root's user, group and no other group.
        val credential = Xdr(call.byteArray)
        credential.int
        credential.string
        assertEquals("uid 0, gid 0, no group besides", listOf(0, 0, 0), List(3) { credential.int })
        assertEquals("nothing after its groups", credential.size, credential.offset)
        assertEquals("AUTH_NONE's verifier, empty", listOf(0, 0), List(2) { call.int })
        assertEquals(end, call.offset)
    }

    /** The browsers ask the server at the address as typed, without its spaces. */
    @Test
    fun theBrowsersListTheExportsOfTheAddressTyped() = runBlocking {
        val asked = mutableListOf<String>()
        val nfs = NfsBrowser(mount = { _, _, _ -> error("No mount here") }, clock = { 0L }) { host ->
            asked += host
            listOf(ExportedFolder("/volume1/video", listOf("*")))
        }
        val browsers = RemoteBrowsers(SmbBrowser(connect = { error("No SMB here") }, clock = { 0L }), nfs)
        assertEquals(listOf(ExportedFolder("/volume1/video", listOf("*"))), browsers.exports(" 192.168.1.20 "))
        assertEquals(listOf("192.168.1.20"), asked)
    }

    private val player = InetAddress.getByName("192.168.1.42") as Inet4Address

    private fun access(vararg groups: String) = exportAccess(groups.toList(), player)

    @Test
    fun anExportTakesInThePlayerByItsAddressOrItsNetwork() {
        listOf("*", "192.168.1.42", "192.168.1.0/24", "192.168.0.0/16", "192.168.1.0/255.255.255.0", "0.0.0.0/0", " 192.168.1.42 ")
            .forEach { assertEquals(it, ExportAccess.ALLOWED, access(it)) }
        assertEquals("no group: everyone", ExportAccess.ALLOWED, access())
    }

    @Test
    fun anExportForOthersRefusesThePlayer() {
        listOf("192.168.1.4", "192.168.1.43", "192.168.2.0/24", "10.0.0.0/8", "192.168.1.0/255.255.255.252")
            .forEach { assertEquals(it, ExportAccess.REFUSED, access(it)) }
        assertEquals("IPv6 is never this address", ExportAccess.REFUSED, access("fe80::/10", "2a01:cb00::1"))
    }

    /**
     * Names are the server's to resolve: never looked up here, they leave the answer open unless another group settles
     * it. So are wildcards, even of digits: Linux's mountd matches them against the player's name, not its address.
     */
    @Test
    fun aHostNameANetgroupOrAWildcardLeavesItUnknown() {
        listOf("zidoo", "zidoo.home", "*.home", "@media", "192.168.1.0/33").forEach { assertEquals(it, ExportAccess.UNKNOWN, access(it)) }
        listOf("192.168.1.*", "192.168.*", "192.168.1.4?", "10.0.0.*", "192.168.1.4?2", "192.168.1.[0-9]*").forEach {
            assertEquals(it, ExportAccess.UNKNOWN, access(it))
        }
        assertEquals(ExportAccess.ALLOWED, access("zidoo.home", "192.168.1.0/24"))
        assertEquals(ExportAccess.UNKNOWN, access("10.0.0.0/8", "zidoo.home"))
        assertEquals("an address settles it whatever the wildcard", ExportAccess.ALLOWED, access("10.0.0.*", "192.168.1.42"))
    }
}
