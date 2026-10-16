package app.houselights.providers.local

import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's NAS browsers, and the sessions and mounts they keep from one file to the next. Made once,
 * by the app's container: scans read folders through [of]; a folder's address changed or a scan over
 * closes what they keep.
 */
class RemoteBrowsers internal constructor(private val smb: SmbBrowser, private val nfs: NfsBrowser) {
    constructor() : this(SmbBrowser(), NfsBrowser())

    fun of(source: SourceEntity): RemoteBrowser = when (source.protocol) {
        Protocol.SMB -> smb
        Protocol.NFS -> nfs
    }

    /**
     * Closes the sessions or mounts kept on [source]'s address, whoever signed in: its address or
     * password changed, and one opened before must not stand for the new one. An SMB session waits
     * for the operation under way, to sign off after it; an NFS mount waits for nothing.
     */
    suspend fun forget(source: SourceEntity) = withContext(Dispatchers.IO) {
        when (source.protocol) {
            Protocol.SMB -> smb.forget(source)
            Protocol.NFS -> nfs.forget(source)
        }
    }

    /** Closes the sessions and mounts no operation uses: a scan is over, and the NAS need not hold them. */
    fun closeIdle() {
        smb.closeIdle()
        nfs.closeIdle()
    }
}

/**
 * Connections kept from one operation to the next, one per key (an address and who signs in): a scan
 * reads several files per title, and signing in or mounting for each would cost more than the reads.
 * One operation at a time, whatever the key, as a scan reads one file at a time.
 */
internal class KeptConnections<C>(
    /** Closes a connection, and never throws; broken: it failed, and is not asked to sign off first. */
    private val close: (connection: C, broken: Boolean) -> Unit,
    /** False once the connection is known to be closed: the NAS hung up. */
    private val isOpen: (C) -> Boolean,
    /** A failure of the connection itself, after which it is made again: not one of the file. */
    private val isBroken: (Throwable) -> Boolean,
    /** Milliseconds that only go forward (elapsedRealtime): a clock set back keeps no session for good. */
    private val clock: () -> Long,
    /**
     * Whether closing waits for the operation under way: an SMB session signs off, which must not
     * cut a read short. An NFS mount closes by being dropped: a folder's new address is saved without
     * waiting for a listing of the old one, whose mount is then closed rather than kept.
     */
    private val closingWaits: Boolean = true,
) {
    private class Kept<C>(val connection: C, val lastUse: Long)

    /** Held for each operation's length: one at a time. */
    private val operation = Any()

    /** Guarded by itself, held only to read or change the map and [using]. */
    private val kept = mutableMapOf<String, Kept<C>>()

    /** The key of the operation under way, null once it is forgotten: its connection is closed after, not kept. */
    private var using: String? = null

    /**
     * Runs [block] on the connection kept for [key], or on one [connect] makes when none is kept, or
     * the one kept was not used for [IDLE_MS] (the NAS may have dropped it) or hung up: that one is
     * closed, not dropped, or the NAS would hold its session. A kept connection that breaks is made
     * again, once. After a failure of the file (not found, refused), the connection stays kept.
     * A connection the NAS hung up is closed as broken: nothing would answer its sign-off.
     */
    fun <T> use(key: String, connect: () -> C, block: (C) -> T): T = synchronized(operation) {
        val entry = synchronized(kept) {
            using = key
            kept.remove(key)
        }
        try {
            val reused = entry?.let {
                if (clock() - it.lastUse < IDLE_MS && isOpen(it.connection)) {
                    it.connection
                } else {
                    close(it.connection, !isOpen(it.connection))
                    null
                }
            }
            if (reused != null) {
                val result = attempt(key, reused, block)
                if (result.exceptionOrNull()?.let(isBroken) != true) return result.getOrThrow()
            }
            attempt(key, connect(), block).getOrThrow()
        } finally {
            synchronized(kept) { using = null }
        }
    }

    /** Runs [block] on a connection of its own, closed after: what it answers owes nothing to one kept. */
    fun <T> once(connect: () -> C, block: (C) -> T): T {
        val connection = connect()
        val result = suspendRunCatching { block(connection) }
        close(connection, result.exceptionOrNull()?.let(isBroken) == true || !isOpen(connection))
        return result.getOrThrow()
    }

    /**
     * Closes the connections kept for the keys [which] accepts. One in use for such a key is closed
     * as its operation ends, rather than kept; when [closingWaits], this waits for that end.
     */
    fun forget(which: (String) -> Boolean) = if (closingWaits) synchronized(operation) { drop(which) } else drop(which)

    /** Closes every connection kept: between operations, none is in use. */
    fun closeIdle() = forget { true }

    private fun drop(which: (String) -> Boolean) {
        val dropped = synchronized(kept) {
            if (using?.let(which) == true) using = null
            kept.keys.filter(which).mapNotNull { kept.remove(it) }
        }
        dropped.forEach { close(it.connection, !isOpen(it.connection)) }
    }

    /** Runs [block] on [connection], kept after it unless it broke or [key] was forgotten meanwhile: then it is closed. */
    private fun <T> attempt(key: String, connection: C, block: (C) -> T): Result<T> =
        suspendRunCatching { block(connection) }.also { result ->
            val broken = result.exceptionOrNull()?.let(isBroken) == true
            val keep = !broken && synchronized(kept) { (using == key).also { if (it) kept[key] = Kept(connection, clock()) } }
            if (!keep) close(connection, broken || !isOpen(connection))
        }

    companion object {
        /** A connection unused this long is closed rather than reused: the NAS may have dropped it. */
        const val IDLE_MS = 60_000L
    }
}
