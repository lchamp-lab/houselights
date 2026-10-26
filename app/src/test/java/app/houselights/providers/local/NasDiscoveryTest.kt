package app.houselights.providers.local

import app.houselights.testing.hex
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.net.InetAddress

/** NAS found over mDNS: the answers' bytes as responders send them, and the discovery's flow on a scripted network. */
class NasDiscoveryTest {
    private fun at(address: String): InetAddress = InetAddress.getByName(address)

    /**
     * As avahi (Synology's, TrueNAS's, Unraid's) answers PTR _smb._tcp.local: the instance in the answer, its SRV, TXT
     * and A records in the additional section, each name after the first pointing back into the message (RFC 1035 4.1.4).
     */
    private val avahi = hex(
        "0000 8400 0000 0001 0000 0003", // id 0, an authoritative answer, one answer, three additional records
        "045f736d62 045f746370 056c6f63616c 00", // _smb._tcp.local, at byte 12
        "000c 0001 00001194 0008", // PTR, IN, 4500 s, 8 bytes:
        "054453393230 c00c", // DS920, at byte 39, then _smb._tcp.local
        "c027 0021 8001 00000078 000e", // DS920._smb._tcp.local SRV, IN with cache-flush, 120 s, 14 bytes:
        "0000 0000 01bd 054453393230 c016", // priority 0, weight 0, port 445, DS920 (at byte 65) then local
        "c027 0010 8001 00001194 0001 00", // its TXT: empty
        "c041 0001 8001 00000078 0004 c0a80114", // DS920.local A 192.168.1.20
    )

    /** A DNS answer of [records] (RFC 1035 4.1), names written whole; [flags] an authoritative answer's unless given. */
    private fun answer(vararg records: ByteArray, flags: Int = 0x8400): ByteArray = bytes {
        writeShort(0)
        writeShort(flags)
        writeShort(0)
        writeShort(records.size)
        writeShort(0)
        writeShort(0)
        records.forEach { write(it) }
    }

    private fun name(vararg labels: String) = bytes {
        labels.forEach {
            val label = it.toByteArray()
            writeByte(label.size)
            write(label)
        }
        writeByte(0)
    }

    private fun record(owner: ByteArray, type: Int, data: ByteArray, ttl: Int = 120) = bytes {
        write(owner)
        writeShort(type)
        writeShort(1)
        writeInt(ttl)
        writeShort(data.size)
        write(data)
    }

    private fun ptr(instance: String, service: String = "_smb", ttl: Int = 4500) =
        record(name(service, "_tcp", "local"), 12, name(instance, service, "_tcp", "local"), ttl)

    /** Priority 0, weight 0, port 445, then [host].local. */
    private fun srv(instance: String, host: String) = record(
        name(instance, "_smb", "_tcp", "local"),
        33,
        bytes {
            writeShort(0)
            writeShort(0)
            writeShort(445)
            write(name(host, "local"))
        },
    )

    private fun a(host: String, address: String) = record(name(host, "local"), 1, at(address).address)

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).apply(write).flush() }.toByteArray()

    @Test
    fun aNasIsReadFromItsAnswer() {
        assertEquals(listOf(FoundNas("DS920", "192.168.1.20")), Mdns.announced(avahi, at("192.168.1.20")))
    }

    /** A reflector between networks relays the answer from its own address: the NAS is at the address its records give. */
    @Test
    fun aRelayedAnswerGivesTheNasItsOwnAddress() {
        assertEquals(listOf(FoundNas("DS920", "192.168.1.20")), Mdns.announced(avahi, at("192.168.1.1")))
    }

    /** Docker's bridge among a Synology's addresses: the one it answered from is the one this player reaches. */
    @Test
    fun aNasWithSeveralAddressesIsAtTheOneItAnsweredFrom() {
        val docker = answer(ptr("DS920"), srv("DS920", "DS920"), a("DS920", "172.17.0.1"), a("DS920", "192.168.1.20"))
        assertEquals(listOf(FoundNas("DS920", "192.168.1.20")), Mdns.announced(docker, at("192.168.1.20")))
    }

    /** Without SRV and A records, the NAS is where its answer came from; several in one answer, and any name, are read. */
    @Test
    fun anAnswerWithoutAddressIsTheSenders() {
        val two = answer(ptr("Médiathèque"), ptr("TrueNAS"))
        assertEquals(
            listOf(FoundNas("Médiathèque", "192.168.1.30"), FoundNas("TrueNAS", "192.168.1.30")),
            Mdns.announced(two, at("192.168.1.30")),
        )
    }

    /** A question (another player's, or the app's own heard back), a goodbye, another service, or bytes that do not read. */
    @Test
    fun whatAnnouncesNoNasIsPassedOver() {
        val pointingForward = avahi.copyOf().also { it[46] = 0x60 }
        listOf(
            Mdns.query,
            answer(ptr("DS920", ttl = 0)),
            answer(ptr("Apple TV", service = "_airplay")),
            answer(ptr("DS920"), flags = 0x0000),
            avahi.copyOf(50),
            pointingForward,
            ByteArray(0),
        ).forEach { assertEquals(emptyList<FoundNas>(), Mdns.announced(it, at("192.168.1.20"))) }
    }

    /** One question for PTR _smb._tcp.local, from id 0, asking for answers sent to everyone. */
    @Test
    fun theQuestionIsMdnssOwn() {
        assertArrayEquals(hex("0000 0000 0001 0000 0000 0000", "045f736d62 045f746370 056c6f63616c 00", "000c 0001"), Mdns.query)
    }

    /** The network as a script: each answer comes at its time, a fake clock moved on by each wait. */
    private class Network(private val answers: List<Pair<Long, MdnsPacket>>) : MdnsSocket {
        var now = 0L
        val asked = mutableListOf<Long>()
        var closed = false
        private var next = 0

        override fun send(query: ByteArray) {
            assertArrayEquals(Mdns.query, query)
            asked += now
        }

        override fun receive(timeoutMs: Int): MdnsPacket? {
            val answer = answers.getOrNull(next)?.takeIf { (time, _) -> time <= now + timeoutMs }
            if (answer == null) {
                now += timeoutMs
                return null
            }
            next++
            now = maxOf(now, answer.first)
            return answer.second
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * Each NAS comes as it answers, once whatever it repeats, then again with the protocols whose ports answered; the
     * question goes at once and a second later; five seconds on, the socket closes, the lock goes, and an answer later
     * is not heard. A NAS known otherwise (one the library uses) is probed the same way.
     */
    @Test
    fun theNasComeAsTheyAnswerThenWithTheirProtocols() {
        val network = Network(
            listOf(
                40L to MdnsPacket(avahi, at("192.168.1.20")),
                60L to MdnsPacket(avahi, at("192.168.1.20")),
                1_500L to MdnsPacket(answer(ptr("TrueNAS")), at("192.168.1.30")),
                2_000L to MdnsPacket(Mdns.query, at("192.168.1.42")),
                5_500L to MdnsPacket(answer(ptr("Late")), at("192.168.1.40")),
            ),
        )
        var held = true
        val discovery = NasDiscovery(
            open = { network },
            probe = { address -> if (address == "192.168.1.20") setOf(Protocol.SMB, Protocol.NFS) else setOf(Protocol.SMB) },
            hold = { Closeable { held = false } },
            clock = { network.now },
        )
        val found = runBlocking { discovery.discover().toList() }

        assertEquals(FoundNas("DS920", "192.168.1.20"), found.first())
        assertEquals(listOf(FoundNas("DS920", "192.168.1.20"), FoundNas("TrueNAS", "192.168.1.30")), found.filter { it.protocols == null })
        assertEquals(
            setOf(
                FoundNas("DS920", "192.168.1.20", setOf(Protocol.SMB, Protocol.NFS)),
                FoundNas("TrueNAS", "192.168.1.30", setOf(Protocol.SMB)),
            ),
            found.filter { it.protocols != null }.toSet(),
        )
        assertEquals(4, found.size)
        assertEquals(listOf(0L, 1_000L), network.asked)
        assertTrue(network.closed)
        assertFalse("the multicast lock released", held)
        assertEquals(setOf(Protocol.SMB), runBlocking { discovery.protocols("192.168.1.31") })
    }
}
