package app.houselights.providers.local

import app.houselights.core.MalformedAnswerException
import com.emc.ecs.nfsclient.nfs.NfsRequestBase
import com.emc.ecs.nfsclient.nfs.NfsResponseBase
import com.emc.ecs.nfsclient.portmap.Portmapper
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import com.emc.ecs.nfsclient.rpc.RpcRequest
import com.emc.ecs.nfsclient.rpc.RpcResponse
import com.emc.ecs.nfsclient.rpc.RpcWrapper
import com.emc.ecs.nfsclient.rpc.Xdr
import java.net.Inet4Address

/** A folder an NFS server exports, as its mount service lists it ([NfsExports]). */
data class ExportedFolder(
    /** From the server's root, as a mount asks for it: "/volume1/video". */
    val path: String,
    /**
     * The clients allowed to mount it, as the server writes them: "192.168.1.0/24", "192.168.1.42", "*", "nas.home",
     * "@media"; none means everyone ([exportAccess]).
     */
    val groups: List<String>,
) {
    /** Its last name, what a list of shared folders calls it: "video"; "/" for the server's root. */
    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
}

/** Whether an export lets this player mount it, as its groups say ([exportAccess]). */
enum class ExportAccess {
    ALLOWED,

    /** No group names the player's address. */
    REFUSED,

    /** None names its address, but one names a host or a netgroup, which only the server resolves. */
    UNKNOWN,
}

/**
 * Lists an NFS server's exports with the mount protocol's EXPORT procedure (RFC 1813, appendix I, MOUNTPROC3_EXPORT),
 * which nfs-client 1.1 lacks, from its own RPC pieces: the mount service's port from the server's portmapper, then
 * one call over TCP from an unprivileged port, as the app's mounts are made. A server serving NFSv4 alone may have no
 * mount service, and the list then fails as a mount would.
 */
internal object NfsExports {
    private const val MOUNT_PROGRAM = 100_005
    private const val MOUNT_V3 = 3
    private const val MOUNTPROC3_EXPORT = 5

    /** The mount's own wait for an answer, in seconds, as nfs-client mounts. */
    private const val TIMEOUT_S = 10

    /** The call: RPC's header and AUTH_SYS, nothing else. */
    private const val CALL_BYTES = 512

    /** More than any NAS exports: past it, the answer is taken for garbage rather than read on. */
    private const val MAX_ITEMS = 1_000

    /** Longer than any path or client name, a protocol's limit (MNTPATHLEN 1024, MNTNAMLEN 255). */
    private const val MAX_TEXT = 1_024

    /** The exports of [host], in the server's order; throws as a mount does when the server does not answer. */
    fun list(host: String): List<ExportedFolder> {
        val port = Portmapper.queryPortFromPortMap(MOUNT_PROGRAM, MOUNT_V3, host)
        val call = Xdr(CALL_BYTES).also { ExportRequest().marshalling(it) }
        // The wrapper's retries and request size serve only NFS calls; callRpc sends once and waits TIMEOUT_S.
        val rpc = RpcWrapper<NfsRequestBase, NfsResponseBase>(host, port, 0, 0, CALL_BYTES, TIMEOUT_S)
        return ExportResponse().apply { unmarshalling(rpc.callRpc(host, call, false)) }.exports
    }

    /** EXPORT's call: no argument, AUTH_SYS as root as the app's mounts present themselves. */
    internal class ExportRequest : RpcRequest(MOUNT_PROGRAM, MOUNT_V3, MOUNTPROC3_EXPORT, CredentialUnix(0, 0, null)) {
        override fun getErrorMessage() = "Listing the exports failed"
    }

    /** EXPORT's answer: RPC's reply, checked as nfs-client checks every reply, then the exports ([parse]). */
    internal class ExportResponse : RpcResponse() {
        var exports: List<ExportedFolder> = emptyList()
            private set

        override fun unmarshalling(xdr: Xdr) {
            super.unmarshalling(xdr)
            exports = parse(xdr)
        }
    }

    /**
     * The exports from [xdr]'s offset: a list of nodes, each behind a "value follows" flag, a node holding the path
     * (ex_dir) and the list of its groups (ex_groups), each behind its own flag. Throws [MalformedAnswerException]
     * rather than read past the answer's end.
     */
    internal fun parse(xdr: Xdr): List<ExportedFolder> {
        val exports = mutableListOf<ExportedFolder>()
        while (xdr.follows()) {
            val path = xdr.text()
            val groups = mutableListOf<String>()
            while (xdr.follows()) groups += xdr.text()
            exports += ExportedFolder(path, groups)
            if (exports.size + groups.size > MAX_ITEMS) throw MalformedAnswerException("more than $MAX_ITEMS exports and groups")
        }
        return exports
    }

    /** XDR's "value follows" flag of an optional item. */
    private fun Xdr.follows(): Boolean {
        need(Int.SIZE_BYTES)
        return boolean
    }

    /** An XDR string: its length, then its UTF-8 bytes, padded to four. */
    private fun Xdr.text(): String {
        need(Int.SIZE_BYTES)
        val length = int
        if (length !in 0..MAX_TEXT) throw MalformedAnswerException("a text of $length bytes")
        need(length)
        setOffset(offset - Int.SIZE_BYTES)
        return string
    }

    /** Fails unless [bytes] are left in the answer: Xdr itself reads on, past it, into its buffer's end. */
    private fun Xdr.need(bytes: Int) {
        if (size - offset < bytes) throw MalformedAnswerException("cut short at byte $offset")
    }
}

/**
 * Whether [address], this player's, may mount an export allowing [groups]: [ExportAccess.ALLOWED] when a group names
 * it (the address itself, a network holding it as "192.168.1.0/24" or "192.168.1.0/255.255.255.0", a wildcard such
 * as "192.168.1.*", or "*"), or when there is no group, which the protocol says means everyone;
 * [ExportAccess.UNKNOWN] when none does but one is a host name, a host wildcard ("*.home") or a netgroup ("@media"),
 * which only the server resolves; [ExportAccess.REFUSED] otherwise. No name is looked up.
 */
fun exportAccess(groups: List<String>, address: Inet4Address): ExportAccess {
    if (groups.isEmpty()) return ExportAccess.ALLOWED
    val answers = groups.map { allows(it.trim(), address) }
    return when {
        true in answers -> ExportAccess.ALLOWED
        null in answers -> ExportAccess.UNKNOWN
        else -> ExportAccess.REFUSED
    }
}

/** Whether [group] names [address]; null when only the server can tell (a host name, a netgroup). */
private fun allows(group: String, address: Inet4Address): Boolean? {
    val mine = address.address.fold(0) { bits, byte -> bits shl Byte.SIZE_BITS or (byte.toInt() and BYTE_MASK) }
    return when {
        group == "*" -> true
        '/' in group -> inNetwork(group.substringBefore('/'), group.substringAfter('/'), mine)
        group.any { it in WILDCARDS } -> matches(group, address)
        // IPv6: never this IPv4 address.
        ':' in group -> false
        else -> ipv4(group)?.let { it == mine }
    }
}

/** An address wildcard ("192.168.1.*", "192.168.1.?") matched as the server would; a host name's ("*.home") is the server's to tell. */
private fun matches(group: String, address: Inet4Address): Boolean? {
    if (!group.all { it.isDigit() || it == '.' || it in WILDCARDS }) return null
    val pattern = group.split('*').joinToString(".*") { run -> run.split('?').joinToString(".") { Regex.escape(it) } }
    return Regex(pattern).matches(address.hostAddress.orEmpty())
}

/** [network], an address, with [length], bits or a mask ("24", "255.255.255.0"), holding [mine]; null when unreadable. */
private fun inNetwork(network: String, length: String, mine: Int): Boolean? {
    // IPv6: never this IPv4 address.
    if (':' in network) return false
    val base = ipv4(network) ?: return null
    val mask = ipv4(length) ?: prefixMask(length) ?: return null
    return base and mask == mine and mask
}

/** The mask of a prefix [length] in bits ("24"), null when it is none. */
private fun prefixMask(length: String): Int? =
    length.toIntOrNull()?.takeIf { it in 0..IPV4_BITS && length.all(Char::isDigit) }?.let { if (it == 0) 0 else -1 shl (IPV4_BITS - it) }

/** A dotted IPv4 address as its 32 bits; null for anything else, a host name included. */
private fun ipv4(text: String): Int? {
    val parts = text.split('.')
    if (parts.size != Int.SIZE_BYTES || parts.any { part -> part.isEmpty() || !part.all(Char::isDigit) }) return null
    val bytes = parts.map { it.toIntOrNull() ?: Int.MAX_VALUE }
    return if (bytes.any { it > BYTE_MASK }) null else bytes.fold(0) { bits, byte -> bits shl Byte.SIZE_BITS or byte }
}

private const val WILDCARDS = "*?"
private const val BYTE_MASK = 0xFF
private const val IPV4_BITS = 32
