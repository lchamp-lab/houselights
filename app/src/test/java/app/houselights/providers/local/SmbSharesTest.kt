package app.houselights.providers.local

import app.houselights.core.MalformedAnswerException
import app.houselights.testing.hex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Locale

/**
 * A NAS's shares through srvsvc's pipe, from the bytes a server answers: a DCE/RPC bind (C706 12.6.4.3 and 12.6.4.4),
 * then NetrShareEnum at level 1 (MS-SRVS 3.1.4.8), its answer's NDR written field by field as the specification lays
 * it out. On Robolectric for the failures' classification.
 */
@RunWith(RobolectricTestRunner::class)
class SmbSharesTest {
    /** The server's end of the pipe: each exchange answers the next of [answers], its first part at once, the rest read. */
    private class Pipe(vararg answers: List<ByteArray>) : RpcPipe {
        private val left = ArrayDeque(answers.toList())
        private var reading = ArrayDeque<ByteArray>()
        val sent = mutableListOf<ByteArray>()

        override fun transact(request: ByteArray): ByteArray {
            sent += request
            reading = ArrayDeque(left.removeFirst())
            return reading.removeFirst()
        }

        override fun read(): ByteArray = reading.removeFirstOrNull() ?: ByteArray(0)
    }

    /** As Samba accepts the bind: its fragment sizes, an association, the pipe's address, then NDR accepted. */
    private val bound = hex(
        "05000c03 10000000 4400 0000 01000000", // version 5.0, bind_ack, first and last, little-endian, 68 bytes, call 1
        "b810 b810 f0530000", // max_xmit_frag and max_recv_frag 4280, assoc_group_id
        "0d00 5c504950455c73727673766300 00", // "\PIPE\srvsvc" and its zero, then a byte to align to four
        "01000000 0000 0000", // one result: acceptance, no reason
        "045d888aeb1cc9119fe808002b104860 02000000", // NDR 2.0
    )

    /**
     * NetrShareEnum's answer from a Synology: video and photo, IPC$ (IPC, special), backup$ (a disk share hidden by its
     * name), a printer and homes. Each SHARE_INFO_1 is its name's pointer, its type and its remark's (null without
     * one); the strings follow in turn, each its maximum count, offset and actual count, then UTF-16 and a zero.
     */
    private val enumerated = hex(
        "01000000 01000000 00000200", // Level 1, the union's discriminant 1, SHARE_INFO_1_CONTAINER*
        "06000000 04000200 06000000", // EntriesRead 6, Buffer*, the array's conformance 6
        "08000200 00000000 0c000200", // video: STYPE_DISKTREE, a remark
        "10000200 00000000 00000000", // photo: STYPE_DISKTREE, no remark
        "14000200 03000080 18000200", // IPC$: STYPE_IPC | STYPE_SPECIAL, a remark
        "1c000200 00000000 00000000", // backup$: STYPE_DISKTREE
        "20000200 01000000 24000200", // printer: STYPE_PRINTQ, a remark
        "28000200 00000000 2c000200", // homes: STYPE_DISKTREE, a remark
        "06000000 00000000 06000000 76006900640065006f000000", // "video"
        "14000000 00000000 14000000", // a remark of 19 characters and its zero
        "560069006400e9006f00730020006400650020006c00610020006d006100690073006f006e000000", // "Vidéos de la maison"
        "06000000 00000000 06000000 700068006f0074006f000000", // "photo"
        "05000000 00000000 05000000 49005000430024000000 0000", // "IPC$", then two bytes to align to four
        "14000000 00000000 14000000", // a remark of 19 characters
        "49005000430020005300650072007600690063006500200028004400530039003200300029000000", // "IPC Service (DS920)"
        "08000000 00000000 08000000 6200610063006b007500700024000000", // "backup$"
        "08000000 00000000 08000000 7000720069006e007400650072000000", // "printer"
        "03000000 00000000 03000000 480050000000 0000", // "HP", aligned
        "06000000 00000000 06000000 68006f006d00650073000000", // "homes"
        "0a000000 00000000 0a000000 7500730065007200200068006f006d0065000000", // "user home"
        "06000000 30000200 00000000", // TotalEntries 6, ResumeHandle* and its value
        "00000000", // success
    )

    /** A response to call 2 carrying [stub] (C706 12.6.4.10): [flags] 3 alone, 1 its first fragment, 2 its last. */
    private fun response(stub: ByteArray, flags: Int = 3, left: Int = stub.size): ByteArray {
        val length = 24 + stub.size
        return hex("0500 02", byte(flags), "10000000", le16(length), "0000 02000000", le16(left), "0000", "0000 00 00") + stub
    }

    private fun byte(value: Int) = (value and 0xFF).toString(16).padStart(2, '0')

    /** A count below 65536 as two bytes, little-endian. */
    private fun le16(value: Int) = byte(value) + byte(value shr 8)

    private fun shares(vararg answer: ByteArray) = SmbShares.list(Pipe(listOf(bound), answer.toList()), "NAS", Locale.FRENCH)

    private val expected = listOf(
        SharedFolder("homes", "user home"),
        SharedFolder("photo", ""),
        SharedFolder("video", "Vidéos de la maison"),
    )

    /** Disk shares alone, none ending with "$", by name. */
    @Test
    fun theSharesAreTheServersDiskSharesByName() {
        assertEquals(expected, shares(response(enumerated)))
    }

    /** Every share as listed, with its type: only folder trees that are not special are disk shares. */
    @Test
    fun theEnumerationIsReadAsTheNdrLaysItOut() {
        val listed = SmbShares.enumerated(enumerated)
        assertEquals(listOf("video", "photo", "IPC$", "backup$", "printer", "homes"), listed.map { it.name })
        assertEquals(listOf(true, true, false, true, false, true), listed.map { it.isDisk })
        assertEquals("IPC Service (DS920)", listed[2].remark)
    }

    /** The bind and the call, byte for byte as Windows's `net view` sends them, but for the server's name. */
    @Test
    fun theBindAndTheCallAreThoseWindowsSends() {
        val pipe = Pipe(listOf(bound), listOf(response(enumerated)))
        SmbShares.list(pipe, "NAS")
        val bind = hex(
            "05000b03 10000000 4800 0000 01000000", // bind, 72 bytes, call 1
            "b810 b810 00000000", // fragments of 4280 bytes at most, a new association
            "01000000 0000 0100", // one context, its id 0, one transfer syntax
            "c84f324b7016d30112785a47bf6ee188 03000000", // srvsvc 3.0
            "045d888aeb1cc9119fe808002b104860 02000000", // NDR 2.0
        )
        val call = hex(
            "05000003 10000000 5400 0000 02000000", // request, 84 bytes, call 2
            "3c000000 0000 0f00", // alloc_hint 60, context 0, opnum 15: NetrShareEnum
            "00000200 06000000 00000000 06000000 5c005c004e00410053000000", // ServerName "\\NAS"
            "01000000 01000000 04000200 00000000 00000000", // InfoStruct: level 1, its container, empty
            "ffffffff", // PreferedMaximumLength: MAX_PREFERRED_LENGTH
            "08000200 00000000", // ResumeHandle 0
        )
        assertArrayEquals(bind, pipe.sent[0])
        assertArrayEquals(call, pipe.sent[1])
    }

    /** A long answer comes in fragments, and a fragment in more than one read: joined, then read as one stub. */
    @Test
    fun anAnswerInFragmentsIsJoined() {
        val first = response(enumerated.copyOf(200), flags = 1, left = enumerated.size)
        val last = response(enumerated.copyOfRange(200, enumerated.size), flags = 2)
        assertEquals(expected, shares(first, last))
        val reads = listOf(first.copyOf(10), first.copyOfRange(10, first.size) + last.copyOf(30), last.copyOfRange(30, last.size))
        assertEquals(expected, shares(*reads.toTypedArray()))
    }

    /** The server refused the account its list (a fault, or NetrShareEnum's own status): access denied, as for a share. */
    @Test
    fun aServerRefusingTheAccountIsAccessDenied() {
        // A fault (C706 12.6.4.7): alloc_hint, context, cancel count, a reserved byte, the status 5, four reserved bytes.
        val fault = hex("05000303 10000000 2000 0000 02000000", "20000000 0000 00 00", "05000000 00000000")
        val status = enumerated.copyOf(enumerated.size - 4) + hex("05000000") // ERROR_ACCESS_DENIED
        listOf(fault, response(status)).forEach { answer ->
            val failure = runCatching { shares(answer) }.exceptionOrNull()
            assertEquals("$failure", ScanErrorKind.ACCESS_DENIED, failure?.let { ScanErrors.classify(it).kind })
        }
    }

    /** A server that will not serve srvsvc in NDR, or at all: a failure of its own, not a malformed answer. */
    @Test
    fun aBindRefusedFails() {
        val nak = hex("05000d03 10000000 1200 0000 01000000", "0400") // bind_nak: local limit exceeded
        val rejected = bound.copyOf().also { it[44] = 2 } // provider rejection
        listOf(nak, rejected).forEach { answer ->
            val failure = runCatching { SmbShares.list(Pipe(listOf(answer)), "NAS") }.exceptionOrNull()
            assertTrue("$failure", failure is IOException && failure !is MalformedAnswerException)
        }
    }

    /**
     * Cut short (the PDU, the shares' array, a string's code units), a string's count past the stub's end, a count
     * beyond any share's, another call's answer, big-endian: refused rather than read on.
     */
    @Test
    fun aMalformedAnswerFails() {
        val whole = response(enumerated)
        // The first string's counts, maximum and actual, 0x0fff units: a share's name could be that long, not this stub.
        val stringPastTheEnd = enumerated.copyOf().also { it[96] = 0xff.toByte(); it[97] = 0x0f; it[104] = 0xff.toByte(); it[105] = 0x0f }
        // Its actual count alone, 0x7f7f units: more than its maximum, and than any share's name.
        val countBeyondAnyName = enumerated.copyOf().also { it[104] = 0x7f; it[105] = 0x7f }
        val anotherCall = whole.copyOf().also { it[12] = 9 }
        val bigEndian = whole.copyOf().also { it[4] = 0 }
        // The stub ending in its array, then inside "video"'s code units (bytes 108 to 119).
        val cut = listOf(whole.copyOf(100), response(enumerated.copyOf(64)), response(enumerated.copyOf(110)))
        (cut + listOf(response(stringPastTheEnd), response(countBeyondAnyName), anotherCall, bigEndian)).forEach { answer ->
            val failure = runCatching { shares(answer) }.exceptionOrNull()
            assertTrue("$failure", failure is MalformedAnswerException)
        }
    }

    /** The browsers list the shares of the NAS a folder is on, signed in as it is, through the SMB browser. */
    @Test
    fun theBrowsersListTheSharesOfAFoldersNas() = runTest {
        val asked = mutableListOf<SourceEntity>()
        val smb = SmbBrowser(connect = { error("No share opened") }, clock = { 0L }) { source ->
            asked += source
            expected
        }
        val nas = SourceEntity(
            name = "", kind = SourceKind.MOVIES, protocol = Protocol.SMB, host = "192.168.1.20", share = "", path = "",
            username = "kodi", password = Secret("secret"),
        )
        assertEquals(expected, RemoteBrowsers(smb, NfsBrowser()).shares(nas))
        assertEquals(listOf(nas), asked)
        assertFalse(SmbShares.Listed("IPC$", Int.MIN_VALUE or 3, "").isDisk)
    }
}
