package app.houselights.providers.local

import app.houselights.core.MalformedAnswerException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

/** A disk share of an SMB server, as the server lists them ([SmbShares]). */
data class SharedFolder(
    /** As the server spells it, what a folder's share is: "video". */
    val name: String,
    /** The server's words for it, often none. */
    val remark: String = "",
)

/** The server's end of a named pipe as [SmbShares] talks through it: smbj's on a NAS, bytes in tests. */
internal interface RpcPipe {
    /** Writes [request] and returns what the server answers in the same exchange (FSCTL_PIPE_TRANSCEIVE). */
    fun transact(request: ByteArray): ByteArray

    /** More of an answer longer than the exchange returned; empty when there is none. */
    fun read(): ByteArray
}

/**
 * Lists an SMB server's shares as Windows's `net view` does: a DCE/RPC bind to its server service (srvsvc) on the
 * IPC$ share's named pipe (MS-RPCE 2.1.1.2), then NetrShareEnum at level 1 (MS-SRVS 3.1.4.8), its shares' names, types
 * and remarks. smbj has no DCE/RPC, and the libraries that add it (smbj-rpc and its Guava, jcifs-ng's second SMB stack)
 * are more than one call needs, so the PDUs and their NDR are written here:
 * - the PDUs as C706 (DCE 1.1: Remote Procedure Call) 12.6 has them, connection-oriented: the common header
 *   (12.6.3.1), bind (12.6.4.3), bind_ack (12.6.4.4), bind_nak (12.6.4.5), fault (12.6.4.7), request (12.6.4.9) and
 *   response (12.6.4.10), without authentication, the SMB session having signed in;
 * - their stubs in NDR (C706 chapter 14), little-endian: each value at a multiple of its size, a unique pointer as
 *   its referent's id, the referent after the structure holding it, a string as its counts then its UTF-16 code units;
 * - the call's types from MS-SRVS 2.2.4: SHARE_ENUM_STRUCT (2.2.4.38) holding SHARE_ENUM_UNION's level 1, a
 *   SHARE_INFO_1_CONTAINER (2.2.4.33) of SHARE_INFO_1 (2.2.4.23), whose types are MS-SRVS 2.2.2.4's.
 * A server answering in big-endian, or with authentication, is refused as malformed: neither Samba nor Windows does.
 */
internal object SmbShares {
    /** The shares of the server at [server] through [pipe], srvsvc's, disk shares alone and none ending with "$". */
    fun list(pipe: RpcPipe, server: String, locale: Locale = Locale.getDefault()): List<SharedFolder> {
        bind(pipe)
        val stub = call(pipe, request(ENUM_CALL, enumStub(server)), ENUM_CALL)
        return enumerated(stub)
            .filter { it.isDisk && it.name.isNotBlank() && !it.name.endsWith('$') }
            .map { SharedFolder(it.name, it.remark) }
            .sortedWith(compareBy(byName(locale)) { it.name })
    }

    /** A share as NetrShareEnum lists it, whatever its type ([isDisk]). */
    internal class Listed(val name: String, val type: Int, val remark: String) {
        /** A folder tree (STYPE_DISKTREE), not a printer, a device, IPC$ or an administrative share (STYPE_SPECIAL). */
        val isDisk: Boolean get() = type and BASE_TYPE == STYPE_DISKTREE && type and STYPE_SPECIAL == 0
    }

    /** Binds the pipe to srvsvc 3.0 in NDR, or throws as the server refused. */
    private fun bind(pipe: RpcPipe) {
        val answer = Answer(pipe, pipe.transact(bindPdu())).next()
        answer.check(BIND_CALL)
        val refused = when (answer.type) {
            BIND_ACK -> answer.contextResult().takeIf { it != ACCEPTANCE }?.let { "srvsvc refused NDR: result $it" }
            BIND_NAK -> "The server refused to bind to srvsvc: ${status(answer.u16(HEADER_BYTES))}"
            else -> throw MalformedAnswerException("a bind answered by a PDU of type ${answer.type}")
        }
        refused?.let { throw IOException(it) }
    }

    /** The stub of the answer to [request], its fragments joined; a fault throws ([stubOf]). */
    private fun call(pipe: RpcPipe, request: ByteArray, call: Int): ByteArray {
        val answer = Answer(pipe, pipe.transact(request))
        val stub = ByteArrayOutputStream()
        do {
            val pdu = answer.next()
            stub.write(stubOf(pdu, call))
            if (stub.size() > MAX_STUB_BYTES) throw MalformedAnswerException("an answer over $MAX_STUB_BYTES bytes")
        } while (!pdu.isLast)
        return stub.toByteArray()
    }

    /** The stub a response to [call] carries; a fault throws with its status, worded as [status] says it. */
    private fun stubOf(pdu: Pdu, call: Int): ByteArray {
        pdu.check(call)
        return when (pdu.type) {
            RESPONSE -> pdu.from(RESPONSE_HEADER_BYTES)
            FAULT -> throw IOException("srvsvc's call failed: ${status(pdu.int(RESPONSE_HEADER_BYTES))}")
            else -> throw MalformedAnswerException("a call answered by a PDU of type ${pdu.type}")
        }
    }

    /** The bind (C706 12.6.4.3): one context, srvsvc 3.0 over NDR 2.0, fragments up to [MAX_FRAGMENT_BYTES]. */
    internal fun bindPdu(): ByteArray = pdu(BIND, BIND_CALL) {
        putShort(MAX_FRAGMENT_BYTES.toShort()) // max_xmit_frag
        putShort(MAX_FRAGMENT_BYTES.toShort()) // max_recv_frag
        putInt(0) // assoc_group_id: a new association
        putInt(1) // n_context_elem, then three reserved bytes
        putShort(0.toShort()) // p_cont_id
        putShort(1.toShort()) // n_transfer_syn, then a reserved byte
        uuid(SRVSVC)
        putInt(SRVSVC_VERSION)
        uuid(NDR)
        putInt(NDR_VERSION)
    }

    /** A request (C706 12.6.4.9) to call NetrShareEnum with [stub], in one fragment. */
    internal fun request(call: Int, stub: ByteArray): ByteArray = pdu(REQUEST, call) {
        putInt(stub.size) // alloc_hint
        putShort(0.toShort()) // p_cont_id: the bind's context
        putShort(NETR_SHARE_ENUM) // opnum
        put(stub)
    }

    /**
     * NetrShareEnum's [in] parameters (MS-SRVS 3.1.4.8) at level 1: the server's name; InfoStruct, its level twice
     * (the structure's, then the union's discriminant) and an empty container for the server to fill; the preferred
     * length MAX_PREFERRED_LENGTH, all at once; and a resume handle from the start.
     */
    internal fun enumStub(server: String): ByteArray = Ndr().run {
        pointer()
        string("\\\\$server")
        int(LEVEL)
        int(LEVEL)
        pointer()
        int(0) // EntriesRead
        int(0) // Buffer: null
        int(MAX_PREFERRED_LENGTH)
        pointer()
        int(0) // *ResumeHandle
        bytes()
    }

    /**
     * NetrShareEnum's [out] parameters from [stub]: InfoStruct's level and discriminant, its container (the count,
     * then the array: its conformance, each SHARE_INFO_1's name pointer, type and remark pointer, then each pointer's
     * string in turn), TotalEntries, the resume handle and the status, which throws unless success or ERROR_MORE_DATA,
     * the list then holding what fit.
     */
    internal fun enumerated(stub: ByteArray): List<Listed> {
        val ndr = NdrReader(stub)
        repeat(2) { ndr.int() }
        val listed = if (ndr.int() == 0) emptyList() else container(ndr)
        ndr.int() // TotalEntries
        if (ndr.int() != 0) ndr.int() // *ResumeHandle
        val status = ndr.int()
        if (status != 0 && status != ERROR_MORE_DATA) throw IOException("NetrShareEnum failed: ${status(status)}")
        return listed
    }

    private fun container(ndr: NdrReader): List<Listed> {
        val count = ndr.int()
        if (ndr.int() == 0) return emptyList()
        val conformance = ndr.int()
        if (count !in 0..MAX_SHARES || conformance < count) throw MalformedAnswerException("$count shares in an array of $conformance")
        val fixed = List(count) { Triple(ndr.int(), ndr.int(), ndr.int()) }
        return fixed.map { (name, type, remark) ->
            Listed(if (name != 0) ndr.string() else "", type, if (remark != 0) ndr.string() else "")
        }
    }

    /** A status as the server's: Windows's error names the failures classify by ("ACCESS_DENIED"), else its number. */
    private fun status(value: Int): String = when (value) {
        ERROR_ACCESS_DENIED -> "ERROR_ACCESS_DENIED"
        else -> "0x%08x".format(Locale.ROOT, value)
    }

    /** The common header (C706 12.6.3.1) and [body]: a single fragment, little-endian integers, ASCII, IEEE floats. */
    private fun pdu(type: Int, call: Int, body: ByteBuffer.() -> Unit): ByteArray {
        val content = ByteBuffer.allocate(MAX_FRAGMENT_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply(body)
        val length = HEADER_BYTES + content.position()
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(VERSION, 0, type.toByte(), FIRST_AND_LAST, LITTLE_ENDIAN_ASCII, 0, 0, 0))
            putShort(length.toShort()) // frag_length
            putShort(0.toShort()) // auth_length
            putInt(call)
            put(content.array(), 0, content.position())
        }.array()
    }

    /** A UUID as NDR writes it little-endian: its first three fields byte-swapped, its last eight bytes as written. */
    private fun ByteBuffer.uuid(text: String) {
        val uuid = UUID.fromString(text)
        val high = uuid.mostSignificantBits
        putInt((high ushr Int.SIZE_BITS).toInt()) // time_low
        putShort((high ushr Short.SIZE_BITS).toShort()) // time_mid
        putShort(high.toShort()) // time_hi_and_version
        order(ByteOrder.BIG_ENDIAN).putLong(uuid.leastSignificantBits).order(ByteOrder.LITTLE_ENDIAN) // clock_seq, node
    }

    // PDU types, C706 12.6.3.1.
    private const val REQUEST = 0
    private const val RESPONSE = 2
    private const val FAULT = 3
    private const val BIND = 11
    private const val BIND_ACK = 12
    private const val BIND_NAK = 13

    private const val VERSION: Byte = 5
    private const val FIRST_AND_LAST: Byte = 0x03
    private const val LITTLE_ENDIAN_ASCII: Byte = 0x10

    /** Windows's own, the size Samba accepts: a level-1 list of a NAS's shares fits in one or two. */
    private const val MAX_FRAGMENT_BYTES = 4_280

    private const val BIND_CALL = 1
    private const val ENUM_CALL = 2

    /** srvsvc, MS-SRVS 1.9, version 3.0 (its minor version in the upper half). */
    private const val SRVSVC = "4b324fc8-1670-01d3-1278-5a47bf6ee188"
    private const val SRVSVC_VERSION = 3

    /** NDR's transfer syntax, version 2.0. */
    private const val NDR = "8a885d04-1ceb-11c9-9fe8-08002b104860"
    private const val NDR_VERSION = 2

    /** The bind_ack's result for a context accepted. */
    private const val ACCEPTANCE = 0

    private const val NETR_SHARE_ENUM: Short = 15
    private const val LEVEL = 1
    private const val MAX_PREFERRED_LENGTH = -1

    private const val ERROR_ACCESS_DENIED = 5
    private const val ERROR_MORE_DATA = 234

    /** A share's type is its low byte (MS-SRVS 2.2.2.4), flags above it. */
    private const val BASE_TYPE = 0xFF
    private const val STYPE_DISKTREE = 0
    private const val STYPE_SPECIAL = Int.MIN_VALUE

    /** More than any NAS shares, past which the answer is taken for garbage. */
    private const val MAX_SHARES = 4_096
    private const val MAX_STUB_BYTES = 1_048_576
}

/** The common header's size, before a body. */
private const val HEADER_BYTES = 16

/** A response's and a fault's header: the common one, alloc_hint, p_cont_id, cancel_count and a reserved byte. */
private const val RESPONSE_HEADER_BYTES = 24

/** A whole PDU from the pipe ([Answer]): its common header's fields, read little-endian. */
private class Pdu(val bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val type: Int get() = bytes[TYPE_AT].toInt()

    /** Its fragment ends the answer (PFC_LAST_FRAG). */
    val isLast: Boolean get() = bytes[FLAGS_AT].toInt() and LAST_FRAGMENT != 0

    /** Fails unless it is version 5, little-endian, unauthenticated, and answers [call]. */
    fun check(call: Int) {
        val malformed = when {
            bytes[0].toInt() != PDU_VERSION -> "version ${bytes[0]}"
            bytes[DREP_AT].toInt() and INTEGER_ORDER != LITTLE_ENDIAN -> "big-endian integers"
            u16(AUTH_LENGTH_AT) != 0 -> "authentication"
            buffer.getInt(CALL_AT) != call -> "call ${buffer.getInt(CALL_AT)} answered for call $call"
            else -> return
        }
        throw MalformedAnswerException(malformed)
    }

    /** A bind_ack's result for its first context, after its secondary address aligned to four (C706 12.6.4.4). */
    fun contextResult(): Int {
        val address = u16(BIND_ACK_ADDRESS_AT)
        val results = BIND_ACK_ADDRESS_AT + Short.SIZE_BYTES + address
        val aligned = (results + ALIGN - 1) / ALIGN * ALIGN
        if (aligned + RESULT_AT + Short.SIZE_BYTES > bytes.size || bytes[aligned].toInt() < 1) {
            throw MalformedAnswerException("a bind_ack without a result")
        }
        return u16(aligned + RESULT_AT)
    }

    fun u16(at: Int): Int {
        need(at + Short.SIZE_BYTES)
        return buffer.getShort(at).toUShort().toInt()
    }

    fun int(at: Int): Int {
        need(at + Int.SIZE_BYTES)
        return buffer.getInt(at)
    }

    /** Its bytes from [at] to its end, its body after a header of [at] bytes. */
    fun from(at: Int): ByteArray {
        need(at)
        return bytes.copyOfRange(at, bytes.size)
    }

    private fun need(end: Int) {
        if (end > bytes.size) throw MalformedAnswerException("a PDU of ${bytes.size} bytes read to byte $end")
    }

    private companion object {
        const val PDU_VERSION = 5
        const val TYPE_AT = 2
        const val FLAGS_AT = 3
        const val LAST_FRAGMENT = 0x02
        const val DREP_AT = 4
        const val INTEGER_ORDER = 0xF0
        const val LITTLE_ENDIAN = 0x10
        const val AUTH_LENGTH_AT = 10
        const val CALL_AT = 12
        const val BIND_ACK_ADDRESS_AT = 24

        /** After n_results and its three reserved bytes, the first result. */
        const val RESULT_AT = 4
        const val ALIGN = 4
    }
}

/**
 * An answer coming through the pipe, from what the exchange returned ([first]), read on while a PDU is not whole:
 * a fragment longer than the exchange's buffer, or the next fragment of a long answer.
 */
private class Answer(private val pipe: RpcPipe, first: ByteArray) {
    private var pending = first
    private var reads = 0

    /** The next PDU, whole. */
    fun next(): Pdu {
        while (pending.size < HEADER_BYTES || pending.size < length()) more()
        val pdu = Pdu(pending.copyOf(length()))
        pending = pending.copyOfRange(pdu.bytes.size, pending.size)
        return pdu
    }

    /** The first PDU's frag_length, no shorter than its own header. */
    private fun length(): Int {
        val length = ByteBuffer.wrap(pending).order(ByteOrder.LITTLE_ENDIAN).getShort(FRAG_LENGTH_AT).toUShort().toInt()
        if (length < HEADER_BYTES) throw MalformedAnswerException("a fragment of $length bytes")
        return length
    }

    private fun more() {
        if (++reads > MAX_READS) throw MalformedAnswerException("more than $MAX_READS reads")
        val read = pipe.read()
        if (read.isEmpty()) throw MalformedAnswerException("an answer cut short")
        pending += read
    }

    private companion object {
        const val FRAG_LENGTH_AT = 8

        /** Far more than a list of shares takes. */
        const val MAX_READS = 256
    }
}

/** NDR written little-endian ([SmbShares.enumStub]): each value at a multiple of its size from the stub's start. */
private class Ndr {
    private val buffer = ByteBuffer.allocate(STUB_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var referent = FIRST_REFERENT

    fun int(value: Int) {
        while (buffer.position() % Int.SIZE_BYTES != 0) buffer.put(0.toByte())
        buffer.putInt(value)
    }

    /** A unique pointer, its referent's id as Windows numbers them; its referent comes next. */
    fun pointer() {
        int(referent)
        referent += Int.SIZE_BYTES
    }

    /** A conformant varying string ([string, unique] wchar_t*): its counts, then its code units and a final zero. */
    fun string(text: String) {
        val units = text.length + 1
        int(units)
        int(0)
        int(units)
        text.forEach { buffer.putChar(it) }
        buffer.putChar('\u0000')
    }

    fun bytes(): ByteArray = buffer.array().copyOf(buffer.position())

    private companion object {
        const val STUB_BYTES = 1_024
        const val FIRST_REFERENT = 0x0002_0000
    }
}

/** NDR read little-endian ([SmbShares.enumerated]), never past the stub's end. */
private class NdrReader(stub: ByteArray) {
    private val buffer = ByteBuffer.wrap(stub).order(ByteOrder.LITTLE_ENDIAN)

    fun int(): Int {
        val pad = (Int.SIZE_BYTES - buffer.position() % Int.SIZE_BYTES) % Int.SIZE_BYTES
        need(pad + Int.SIZE_BYTES)
        // Relative reads: ByteBuffer's own position(Int) is newer than Android 9.
        repeat(pad) { buffer.get() }
        return buffer.int
    }

    /** A conformant varying string: maximum count, offset, actual count, then that many code units, the last a zero. */
    fun string(): String {
        val maximum = int()
        val offset = int()
        val count = int()
        if (count !in 0..MAX_UNITS || offset != 0 || count > maximum) throw MalformedAnswerException("a string of $count units")
        need(count * Char.SIZE_BYTES)
        return String(CharArray(count) { buffer.char }).trimEnd('\u0000')
    }

    private fun need(bytes: Int) {
        if (buffer.remaining() < bytes) throw MalformedAnswerException("a stub cut short at byte ${buffer.position()}")
    }

    private companion object {
        /** A share's name or remark: 80 and 256 characters at most on Windows, far less than this. */
        const val MAX_UNITS = 4_096
    }
}
