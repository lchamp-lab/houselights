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
     * password changed, and one opened before must not stand for the new one. Waits for the
     * operation under way.
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
 * One operation at a time, whatever the key, as a scan reads one file at a time: closing waits for
 * the operation under way.
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
) {
    private class Kept<C>(val connection: C, val lastUse: Long)

    /** Guarded by itself. */
    private val kept = mutableMapOf<String, Kept<C>>()

    /**
     * Runs [block] on the connection kept for [key], or on one [connect] makes when none is kept, or
     * the one kept was not used for [IDLE_MS] (the NAS may have dropped it) or hung up: that one is
     * closed, not dropped, or the NAS would hold its session. A kept connection that breaks is made
     * again, once. After a failure of the file (not found, refused), the connection stays kept.
     */
    fun <T> use(key: String, connect: () -> C, block: (C) -> T): T = synchronized(kept) {
        val reused = kept.remove(key)?.let { entry ->
            if (clock() - entry.lastUse < IDLE_MS && isOpen(entry.connection)) {
                entry.connection
            } else {
                close(entry.connection, false)
                null
            }
        }
        if (reused != null) {
            val result = attempt(key, reused, block)
            if (result.exceptionOrNull()?.let(isBroken) != true) return result.getOrThrow()
        }
        attempt(key, connect(), block).getOrThrow()
    }

    /** Runs [block] on a connection of its own, closed after: what it answers owes nothing to one kept. */
    fun <T> once(connect: () -> C, block: (C) -> T): T {
        val connection = connect()
        val result = suspendRunCatching { block(connection) }
        close(connection, result.exceptionOrNull()?.let(isBroken) == true)
        return result.getOrThrow()
    }

    /** Closes the connections kept for the keys [which] accepts. */
    fun forget(which: (String) -> Boolean) = synchronized(kept) {
        kept.keys.filter(which).forEach { key -> kept.remove(key)?.let { close(it.connection, false) } }
    }

    /** Closes every connection kept: between operations, none is in use. */
    fun closeIdle() = forget { true }

    /** Runs [block] on [connection], kept after it unless it broke: then it is closed. */
    private fun <T> attempt(key: String, connection: C, block: (C) -> T): Result<T> =
        suspendRunCatching { block(connection) }.also { result ->
            if (result.exceptionOrNull()?.let(isBroken) == true) close(connection, true) else kept[key] = Kept(connection, clock())
        }

    companion object {
        /** A connection unused this long is closed rather than reused: the NAS may have dropped it. */
        const val IDLE_MS = 60_000L
    }
}
