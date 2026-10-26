package com.emc.ecs.nfsclient.network

import app.houselights.providers.local.NfsBrowser
import com.emc.ecs.nfsclient.rpc.Xdr
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.handler.codec.embedder.CodecEmbedderException
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder
import org.jboss.netty.handler.codec.frame.TooLongFrameException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The app's RPCRecordDecoder, in place of nfs-client 1.1.0's (issue #88): an RPC reply read whole whatever record
 * fragments it comes in (RFC 5531, section 11) and however the connection's reads cut them, then handed on in a single
 * fragment, as nfs-client reads it; a record longer than any reply refused, a reply cut short never handed on; and
 * nfs-client reading through it, its own decoder gone.
 */
class RPCRecordDecoderTest {
    /** A reply of [size] bytes: its call's id first, which nfs-client matches it by, then bytes that differ along it. */
    private fun reply(size: Int, xid: Int = XID) = ByteArray(size) { (it * 7 + it / 251).toByte() }.also { ByteBuffer.wrap(it).putInt(xid) }

    /** A record fragment: its mark, [data]'s length with the top bit set when [last], then [data]. */
    private fun fragment(data: ByteArray, last: Boolean): ByteArray =
        ByteBuffer.allocate(MARK_BYTES + data.size).putInt((if (last) LAST else 0) or data.size).put(data).array()

    /** [data] in one fragment: how a NAS sends a reply, and how the decoder hands every reply on. */
    private fun whole(data: ByteArray) = fragment(data, last = true)

    /** [data] in fragments of [sizes] bytes, then a last one with what remains. */
    private fun fragmented(data: ByteArray, vararg sizes: Int): ByteArray {
        val starts = sizes.runningFold(0, Int::plus)
        val fragments = sizes.mapIndexed { i, size -> fragment(data.copyOfRange(starts[i], starts[i] + size), last = false) }
        return (fragments + whole(data.copyOfRange(starts.last(), data.size))).reduce { joined, next -> joined + next }
    }

    /** What the decoder hands on from [bytes] coming in reads of [read] bytes, the connection closed after them. */
    private fun decoded(bytes: ByteArray, read: Int = bytes.size): List<ByteArray> {
        val decoder = DecoderEmbedder<ByteArray>(RPCRecordDecoder())
        for (at in bytes.indices step read) decoder.offer(ChannelBuffers.wrappedBuffer(bytes, at, minOf(read, bytes.size - at)))
        decoder.finish()
        return generateSequence { decoder.poll() }.toList()
    }

    private fun assertHandedOn(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals("replies handed on", expected.size, actual.size)
        expected.zip(actual).forEach { (reply, handedOn) -> assertArrayEquals(reply, handedOn) }
    }

    /** As a NAS sends every reply, and the decoder hands it on: as it came, read at once or a byte at a time. */
    @Test
    fun aReplyInOneFragmentIsHandedOnAsItCame() {
        val sent = whole(reply(SMALL))
        assertHandedOn(listOf(sent), decoded(sent))
        assertHandedOn(listOf(sent), decoded(sent, read = 1))
    }

    /**
     * A read of 65,536 bytes as unfsd answers it: 64 KB, then the rest. Its second fragment comes in the same read as
     * the first, in a read of its own or with its mark cut between two reads; in those two, nfs-client's own decoder
     * lost the first fragment.
     */
    @Test
    fun aReplyInTwoFragmentsIsHandedOnInOne() {
        val reply = reply(FRAGMENT + SMALL)
        val sent = fragmented(reply, FRAGMENT)
        for (read in listOf(sent.size, MARK_BYTES + FRAGMENT, MARK_BYTES + FRAGMENT + 2, ODD_READ)) {
            assertHandedOn(listOf(whole(reply)), decoded(sent, read))
        }
    }

    /**
     * A probe's 256 KB in fragments of 64 KB, as unfsd sends it, and in two hundred fragments of every size, an empty
     * one among them: joined whatever the reads, Netty's own (64 KB at most) or reads cutting through the marks. A short
     * reply in fragments of a few bytes, read a byte at a time, has every mark cut.
     */
    @Test
    fun aReplyInManyFragmentsIsJoinedWhateverTheReads() {
        val reply = reply(PROBE + SMALL)
        val inFragments = listOf(
            fragmented(reply, FRAGMENT, FRAGMENT, FRAGMENT, FRAGMENT),
            fragmented(reply, 1, 0, 2, 3, MARK_BYTES, FRAGMENT - 1, *IntArray((PROBE - FRAGMENT) / ODD_READ) { ODD_READ }),
        )
        for (sent in inFragments) {
            for (read in listOf(sent.size, FRAGMENT, ODD_READ)) {
                assertHandedOn(listOf(whole(reply)), decoded(sent, read))
            }
        }
        val short = reply(SMALL)
        assertHandedOn(listOf(whole(short)), decoded(fragmented(short, *IntArray(SMALL / 3) { it % 5 }), read = 1))
    }

    /** Replies come one after another on a connection: each is handed on by itself, in order, the next one's start in the same read. */
    @Test
    fun repliesInARowAreEachHandedOnInTheirOrder() {
        val first = reply(FRAGMENT + SMALL, xid = 1)
        val second = reply(SMALL, xid = 2)
        val third = reply(2 * FRAGMENT, xid = 3)
        val sent = fragmented(first, FRAGMENT, 1) + whole(second) + fragmented(third, FRAGMENT)
        val expected = listOf(whole(first), whole(second), whole(third))
        assertHandedOn(expected, decoded(sent))
        assertHandedOn(expected, decoded(sent, read = ODD_READ))
    }

    /** A mark announcing more than any reply holds is a fault of the stream: refused before its bytes come, nothing handed on. */
    @Test
    fun aFragmentLongerThanAnyReplyIsRefusedAtOnce() {
        for (mark in listOf(Int.MAX_VALUE, LAST or (RPCRecordDecoder.MAX_RECORD_BYTES - MARK_BYTES + 1))) {
            val decoder = DecoderEmbedder<ByteArray>(RPCRecordDecoder())
            val refused = assertThrows(CodecEmbedderException::class.java) {
                decoder.offer(ChannelBuffers.wrappedBuffer(ByteBuffer.allocate(SMALL).putInt(mark).array()))
            }
            assertTrue("$refused", refused.cause is TooLongFrameException)
            assertEquals(0, decoder.size())
        }
    }

    /** Fragments whose last bit never comes are refused once they pass the longest record, rather than kept without end. */
    @Test
    fun aReplyWhoseLastFragmentNeverComesIsRefused() {
        val decoder = DecoderEmbedder<ByteArray>(RPCRecordDecoder())
        val notLast = fragment(ByteArray(FRAGMENT), last = false)
        val refused = assertThrows(CodecEmbedderException::class.java) {
            repeat(RPCRecordDecoder.MAX_RECORD_BYTES / notLast.size + 1) { decoder.offer(ChannelBuffers.wrappedBuffer(notLast)) }
        }
        assertTrue("$refused", refused.cause is TooLongFrameException)
        assertEquals(0, decoder.size())
    }

    /** A fragment announced longer than what came before the connection closed: the reply is never handed on, part of it or none. */
    @Test
    fun aReplyCutShortIsNeverHandedOn() {
        val reply = reply(FRAGMENT + SMALL)
        val sent = fragmented(reply, FRAGMENT)
        for (cut in listOf(2, MARK_BYTES + 1, MARK_BYTES + FRAGMENT, MARK_BYTES + FRAGMENT + MARK_BYTES, sent.size - 1)) {
            assertHandedOn(emptyList(), decoded(sent.copyOf(cut)))
        }
    }

    /**
     * nfs-client's own connection, its pipeline built with this decoder, reads a reply sent in 64 KB fragments, which
     * reach it in several reads, whole: with nfs-client's decoder, its call failed with « tcp IO error ».
     */
    @Test
    fun nfsClientReadsAReplyInFragmentsWhole() {
        val reply = reply(PROBE + SMALL)
        val answered = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val nas = thread(isDaemon = true) {
                server.accept().use { connection ->
                    val call = DataInputStream(connection.getInputStream())
                    call.readFully(ByteArray(call.readInt() and LENGTH))
                    connection.getOutputStream().apply {
                        write(fragmented(reply, FRAGMENT, FRAGMENT, FRAGMENT, FRAGMENT))
                        flush()
                    }
                    answered.await(TIMEOUT_S, TimeUnit.SECONDS)
                }
            }
            val call = Xdr(SMALL).apply {
                xid = XID
                putInt(XID)
                putInt(0)
            }
            val answer = NetMgr.getInstance().sendAndWait(server.inetAddress.hostAddress, server.localPort, false, call, TIMEOUT_S.toInt())
            answered.countDown()
            nas.join()
            assertArrayEquals(reply, answer.buffer.copyOf(answer.offset))
        }
    }

    /**
     * The build takes nfs-client's own decoder out of its jar: the app's is the only one left, the one nfs-client builds,
     * loaded from where the app's own classes are, a folder or a jar, whatever the checkout's path is named.
     */
    @Test
    fun nfsClientsOwnDecoderIsGone() {
        val loader = requireNotNull(RPCRecordDecoder::class.java.classLoader)
        val copies = loader.getResources("com/emc/ecs/nfsclient/network/RPCRecordDecoder.class").toList()
        assertEquals("$copies", 1, copies.size)
        assertEquals("$copies", locationOf(NfsBrowser::class.java), locationOf(RPCRecordDecoder::class.java))
    }

    /** Where [type] was loaded from: a folder of classes or a jar. */
    private fun locationOf(type: Class<*>) = requireNotNull(type.protectionDomain.codeSource?.location?.toURI()) { "$type" }

    private companion object {
        const val XID = 0x5e3c9a21
        const val MARK_BYTES = 4
        const val LAST = Int.MIN_VALUE
        const val LENGTH = Int.MAX_VALUE

        /** The size of unfsd's fragments. */
        const val FRAGMENT = 64 * 1024

        /** What a scan's probe of a film reads at once. */
        const val PROBE = 256 * 1024

        /** A reply's header, or a short one. */
        const val SMALL = 120

        /** Reads that fall anywhere in a fragment or its mark. */
        const val ODD_READ = 1000

        const val TIMEOUT_S = 10L
    }
}
