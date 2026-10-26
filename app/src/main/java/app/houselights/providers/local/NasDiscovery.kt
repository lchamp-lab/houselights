package app.houselights.providers.local

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import app.houselights.core.MalformedAnswerException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer

/** A NAS that answered on the local network ([NasDiscovery]). */
data class FoundNas(
    /** As it announces itself: "DS920". */
    val name: String,
    /** Its IPv4 address, as a folder keeps it: "192.168.1.20". */
    val address: String,
    /** The protocols whose port answered a connection (SMB 445, NFS 2049); null until both were tried. */
    val protocols: Set<Protocol>? = null,
)

/** A UDP socket for mDNS ([NasDiscovery]): the network's on a player, a script in tests. */
internal interface MdnsSocket : Closeable {
    /** Sends [query] to mDNS's group, 224.0.0.251 port 5353. */
    fun send(query: ByteArray)

    /** The next packet within [timeoutMs], with who sent it; null when none came. */
    fun receive(timeoutMs: Int): MdnsPacket?
}

internal class MdnsPacket(val bytes: ByteArray, val from: InetAddress)

/**
 * Finds the NAS on the local network that announce SMB over mDNS (DNS-SD's _smb._tcp, RFC 6763), as Synology, QNAP,
 * TrueNAS and Unraid do by default or once Bonjour is on, without NsdManager, which resolves one service at a time
 * before Android 14: one question to mDNS's group, asked again a second later as RFC 6762 5.2 asks, each answer read
 * whole (its PTR, SRV and A records). Nothing else is sent: no WS-Discovery, no NetBIOS, no scan of the network; a NAS
 * that does not announce itself is typed.
 */
class NasDiscovery internal constructor(
    /** Opens the socket the question goes from and answers come to. */
    private val open: () -> MdnsSocket,
    /** Which of a NAS's protocols answer, by their ports. */
    private val probe: suspend (host: String) -> Set<Protocol>,
    /** Held while discovering: a multicast lock on Wi-Fi. */
    private val hold: () -> Closeable,
    /** Monotonic milliseconds. */
    private val clock: () -> Long,
) {
    constructor(context: Context) : this(
        open = ::openSocket,
        probe = { probePorts(it) },
        hold = { multicastLock(context.applicationContext) },
        clock = SystemClock::elapsedRealtime,
    )

    /**
     * The NAS answering within [timeoutMs], each emitted when it first answers (by its address), then once more with
     * the protocols whose port answered, so a list shows it at once and its protocols a moment later; [MAX_NAS] at
     * most, and as many probes, even when one answer announces more. A socket that cannot open fails the flow; an
     * answer that cannot be read is passed over.
     */
    fun discover(timeoutMs: Long = DISCOVERY_MS): Flow<FoundNas> = channelFlow {
        hold().use { open().use { socket -> listen(this, socket, timeoutMs) } }
    }.flowOn(Dispatchers.IO)

    /**
     * Which of [host]'s protocols answer, by their ports (SMB 445, NFS 2049, both tried at once, each for a moment):
     * as [discover] tells a NAS found, for one known otherwise, a NAS already used, by its address or its name, a name
     * looked up once and for a moment too ([probePorts]).
     */
    suspend fun protocols(host: String): Set<Protocol> = probe(host)

    /** Asks on [socket], then listens to it for [timeoutMs], sending each NAS found to [found], then launching its probe. */
    private suspend fun listen(found: ProducerScope<FoundNas>, socket: MdnsSocket, timeoutMs: Long) {
        val seen = mutableSetOf<String>()
        val started = clock()
        var asked = 0
        while (found.isActive && seen.size < MAX_NAS && clock() - started < timeoutMs) {
            val elapsed = clock() - started
            if (asked < ASKS && elapsed >= asked * ASK_AGAIN_MS) {
                socket.send(Mdns.query)
                asked++
            }
            val until = if (asked < ASKS) minOf(asked * ASK_AGAIN_MS, timeoutMs) else timeoutMs
            val heard = socket.receive((until - elapsed).coerceIn(1, RECEIVE_SLICE_MS).toInt())?.let { Mdns.announced(it.bytes, it.from) }
            // One answer may announce more than a home holds: the cap holds inside it too.
            heard.orEmpty().filter { seen.size < MAX_NAS && seen.add(it.address) }.forEach { nas ->
                found.send(nas)
                found.launch { found.send(nas.copy(protocols = probe(nas.address))) }
            }
        }
    }

    companion object {
        /** How long discovering lasts: a NAS answers within a second, a sleeping one's network card for it. */
        const val DISCOVERY_MS = 5_000L

        /** More than a home holds: past it, the list is no use. */
        const val MAX_NAS = 16

        /** The question, sent at once then a second later. */
        private const val ASKS = 2
        private const val ASK_AGAIN_MS = 1_000L

        /** A receive's longest wait, so that cancelling is heard soon. */
        private const val RECEIVE_SLICE_MS = 250L

        /** A port's wait for a connection, and a name's for its lookup. */
        private const val PROBE_MS = 1_500

        private const val SMB_PORT = 445
        private const val NFS_PORT = 2049

        private const val TAG = "NasDiscovery"

        /**
         * SMB and NFS, as their ports accept a connection, both tried at once on [host] [resolve]d once beforehand (an
         * address is read as it is, a name looked up: "ds920.local", "NAS"), so that a resolver slow to answer, as a
         * router's may be for a name it does not know, takes [PROBE_MS] at most, once, rather than its own seconds
         * before each port's wait even starts. None when the lookup finds nothing or does not end in time.
         */
        internal suspend fun probePorts(host: String, resolve: (String) -> InetAddress = InetAddress::getByName): Set<Protocol> {
            val address = lookUp(host, resolve) ?: return emptySet()
            return coroutineScope {
                val smb = async(Dispatchers.IO) { accepts(address, SMB_PORT) }
                val nfs = async(Dispatchers.IO) { accepts(address, NFS_PORT) }
                setOfNotNull(Protocol.SMB.takeIf { smb.await() }, Protocol.NFS.takeIf { nfs.await() })
            }
        }

        /**
         * [host]'s address, [PROBE_MS] at most: a lookup cannot be cut short, so it runs outside the caller's scope and
         * is left to end on its own thread when the wait does.
         */
        private suspend fun lookUp(host: String, resolve: (String) -> InetAddress): InetAddress? {
            val lookup = CoroutineScope(Dispatchers.IO).async {
                try {
                    resolve(host)
                } catch (_: UnknownHostException) {
                    null
                }
            }
            return withTimeoutOrNull(PROBE_MS.toLong()) { lookup.await() }
        }

        /** Whether [address] accepts a connection on [port] within [PROBE_MS]. */
        private fun accepts(address: InetAddress, port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(address, port), PROBE_MS) }
            true
        } catch (_: IOException) {
            false
        }

        /**
         * On mDNS's own port in its group, to hear answers sent to everyone; when the system's mDNS service holds the
         * port alone, on a port of its own, which responders answer directly (RFC 6762 6.7).
         */
        private fun openSocket(): MdnsSocket {
            val group = InetAddress.getByName(Mdns.GROUP)
            val shared = MulticastSocket(null)
            return try {
                shared.reuseAddress = true
                shared.bind(InetSocketAddress(Mdns.PORT))
                shared.joinGroup(InetSocketAddress(group, Mdns.PORT), null)
                UdpMdnsSocket(shared, group)
            } catch (e: IOException) {
                shared.close()
                Log.i(TAG, "mDNS's port is not free: asking from a port of its own", e)
                UdpMdnsSocket(MulticastSocket(), group)
            }
        }

        /**
         * Lets multicast through the Wi-Fi driver, which may drop it to save power, while held
         * (CHANGE_WIFI_MULTICAST_STATE); Ethernet needs none.
         */
        private fun multicastLock(context: Context): Closeable {
            val wifi = context.getSystemService(WifiManager::class.java) ?: return Closeable {}
            val lock = wifi.createMulticastLock(TAG).apply { setReferenceCounted(false) }
            return try {
                lock.acquire()
                Closeable { lock.release() }
            } catch (e: SecurityException) {
                Log.w(TAG, "No multicast lock: over Wi-Fi, answers may not come", e)
                Closeable {}
            }
        }
    }
}

/** A multicast socket asking mDNS's [group]. */
private class UdpMdnsSocket(private val socket: MulticastSocket, private val group: InetAddress) : MdnsSocket {
    private val buffer = ByteArray(Mdns.MAX_PACKET)

    init {
        // RFC 6762 11: mDNS packets go with an IP time to live of 255.
        socket.timeToLive = Mdns.TTL
    }

    override fun send(query: ByteArray) = socket.send(DatagramPacket(query, query.size, group, Mdns.PORT))

    override fun receive(timeoutMs: Int): MdnsPacket? {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            MdnsPacket(packet.data.copyOf(packet.length), packet.address)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() = socket.close()
}

/** mDNS's messages, DNS's (RFC 1035 4) on 224.0.0.251:5353 (RFC 6762), as [NasDiscovery] sends and reads them. */
internal object Mdns {
    const val GROUP = "224.0.0.251"
    const val PORT = 5353
    const val TTL = 255

    /** An mDNS packet's largest size, Ethernet's jumbo frame (RFC 6762 17). */
    const val MAX_PACKET = 9_000

    private val SERVICE = listOf("_smb", "_tcp", "local")

    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_SRV = 33
    private const val CLASS_IN = 1

    /** The flags' QR bit, set in an answer, and its opcode, a standard query's 0. */
    private const val ANSWER = 0x8000
    private const val OPCODE = 0x7800

    /** The header's id, flags and four counts. */
    private const val HEADER_FIELDS = 6

    /** The counts of answers, authority and additional records, after the questions'. */
    private const val RECORD_COUNTS = 3

    /** An SRV record's priority, weight and port, before its target. */
    private const val SRV_FIELDS = 3

    /**
     * One question, PTR _smb._tcp.local, from id 0 as RFC 6762 18.1 asks, its class's top bit clear: answers sent to
     * everyone (5.4), which a socket sharing mDNS's port hears whoever else holds it.
     */
    val query: ByteArray = ByteBuffer.allocate(Short.SIZE_BYTES * HEADER_FIELDS + nameBytes(SERVICE) + Int.SIZE_BYTES).apply {
        putShort(0.toShort()) // id
        putShort(0.toShort()) // flags: a standard query
        putShort(1.toShort()) // one question
        putShort(0.toShort()) // no answer
        putShort(0.toShort()) // no authority record
        putShort(0.toShort()) // no additional record
        SERVICE.forEach {
            val label = it.toByteArray()
            put(label.size.toByte())
            put(label)
        }
        put(0.toByte())
        putShort(TYPE_PTR.toShort())
        putShort(CLASS_IN.toShort())
    }.array()

    /**
     * The NAS [bytes], an answer from [from], announce: each _smb._tcp instance it points to, by its name, at the
     * address its SRV and A records give, the sender's when they give none or name the sender among others (a
     * Docker bridge beside the network's), another only when the sender relays for it (a reflector between networks)
     * and that address is a local network's ([isLocal]): an instance pointing elsewhere is no NAS of this network, and
     * its address is never probed. A query, a goodbye (time to live 0), another service or bytes that cannot be read
     * announce nothing.
     */
    fun announced(bytes: ByteArray, from: InetAddress): List<FoundNas> = try {
        Records(Dns(bytes)).run { instances.mapNotNull { (name, instance) -> nas(name, instance, from) } }
    } catch (_: MalformedAnswerException) {
        emptyList()
    }

    private fun nameBytes(labels: List<String>) = labels.sumOf { it.toByteArray().size + 1 } + 1

    private fun key(labels: List<String>) = labels.joinToString(".") { it.lowercase() }

    /** An answer's records that [announced] reads, by their owners' names. */
    private class Records(dns: Dns) {
        /** Each instance of _smb._tcp, its own name and its full name's key. */
        val instances = mutableListOf<Pair<String, String>>()

        private val targets = mutableMapOf<String, String>()
        private val addresses = mutableMapOf<String, MutableList<InetAddress>>()

        init {
            val flags = dns.u16()
            if (flags and ANSWER == 0 || flags and OPCODE != 0) throw MalformedAnswerException("not an answer")
            val questions = dns.u16()
            val records = (1..RECORD_COUNTS).sumOf { dns.u16() }
            repeat(questions) { dns.question() }
            repeat(records) { read(dns) }
        }

        private fun read(dns: Dns) {
            val owner = dns.name()
            val type = dns.u16()
            dns.u16() // class, whose cache-flush bit changes nothing here
            val live = dns.u32() != 0L
            val end = dns.u16() + dns.at
            when {
                !live -> Unit
                type == TYPE_PTR && key(owner) == key(SERVICE) -> dns.name().let { name ->
                    name.firstOrNull()?.let { instances += it to key(name) }
                }
                type == TYPE_SRV -> {
                    repeat(SRV_FIELDS) { dns.u16() }
                    targets[key(owner)] = key(dns.name())
                }
                type == TYPE_A && end - dns.at == Int.SIZE_BYTES ->
                    addresses.getOrPut(key(owner)) { mutableListOf() } += InetAddress.getByAddress(dns.bytes(Int.SIZE_BYTES))
            }
            dns.at = end
        }

        fun nas(name: String, instance: String, from: InetAddress): FoundNas? {
            val known = targets[instance]?.let { addresses[it] }.orEmpty()
            val address = when {
                known.isEmpty() || from in known -> from
                else -> known.firstOrNull { isLocal(it) } ?: return null
            }
            return (address as? Inet4Address)?.let { FoundNas(name, it.hostAddress.orEmpty()) }
        }
    }

    /** A private network's address (10/8, 172.16/12, 192.168/16) or a link's own (169.254/16), where a reflector relays. */
    private fun isLocal(address: InetAddress) = address.isSiteLocalAddress || address.isLinkLocalAddress

    /** DNS's wire format read from [message], its names' compression followed (RFC 1035 4.1.4), never past its end. */
    private class Dns(private val message: ByteArray) {
        /** Where the next read starts; the id is not read, mDNS's being 0. */
        var at = Short.SIZE_BYTES

        fun u8(): Int = byteAt(at++)

        fun u16(): Int = u8() shl Byte.SIZE_BITS or u8()

        fun u32(): Long = u16().toLong() shl Short.SIZE_BITS or u16().toLong()

        fun bytes(count: Int): ByteArray {
            if (at + count > message.size) cut()
            return message.copyOfRange(at, at + count).also { at += count }
        }

        /** A question: its name, type and class. */
        fun question() {
            name()
            u32()
        }

        /** A name's labels, through its pointers, each to an earlier place so that none loops. */
        fun name(): List<String> {
            val labels = mutableListOf<String>()
            var from = at
            var jumped = false
            var length = byteAt(from)
            while (length != 0) {
                if (length and POINTER == POINTER) {
                    val target = (length and POINTER.inv()) shl Byte.SIZE_BITS or byteAt(from + 1)
                    if (target >= from) throw MalformedAnswerException("a name pointing forward")
                    if (!jumped) at = from + 2
                    jumped = true
                    from = target
                } else {
                    if (from + 1 + length > message.size || labels.sumOf { it.length } > MAX_NAME) cut()
                    labels += String(message, from + 1, length, Charsets.UTF_8)
                    from += 1 + length
                }
                length = byteAt(from)
            }
            if (!jumped) at = from + 1
            return labels
        }

        private fun byteAt(index: Int): Int {
            if (index >= message.size) cut()
            return message[index].toInt() and BYTE
        }

        private fun cut(): Nothing = throw MalformedAnswerException("a DNS message cut short")

        private companion object {
            const val POINTER = 0xC0
            const val BYTE = 0xFF
            const val MAX_NAME = 255
        }
    }
}
