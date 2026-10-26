package app.houselights.providers.local

import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's browsers, a NAS's and the player's own disks' ([LocalBrowser]), and the sessions and mounts they keep
 * between files; a single instance lives in the app container. Kept connections close when a source's address changes
 * or a scan ends.
 */
class RemoteBrowsers internal constructor(
    private val smb: SmbBrowser,
    private val nfs: NfsBrowser,
    private val local: LocalBrowser = LocalBrowser(Volumes.NONE),
) {
    /** Folders on the player's own disks are read where [volumes] says each is mounted. */
    constructor(volumes: Volumes) : this(SmbBrowser(), NfsBrowser(), LocalBrowser(volumes))

    fun of(source: SourceEntity): RemoteBrowser = when (source.protocol) {
        Protocol.SMB -> smb
        Protocol.NFS -> nfs
        Protocol.LOCAL -> local
    }

    /** The exports of the NFS server at [host], each with the clients it allows ([exportAccess]). */
    suspend fun exports(host: String): List<ExportedFolder> = nfs.exports(host)

    /** The disk shares of [source]'s SMB server, signed in as [source] is; its share and folder are not read. */
    suspend fun shares(source: SourceEntity): List<SharedFolder> = smb.shares(source)

    /**
     * Closes sessions or mounts kept on [source]'s address, for any account or NFS identity, after its address,
     * password or identity changed.
     * An SMB session signs off after its current operation; an NFS mount is dropped at once; a disk's mount is asked of
     * Android again.
     */
    suspend fun forget(source: SourceEntity) = withContext(Dispatchers.IO) {
        when (source.protocol) {
            Protocol.SMB -> smb.forget(source)
            Protocol.NFS -> nfs.forget(source)
            Protocol.LOCAL -> local.forget(source)
        }
    }

    /** Closes idle sessions and mounts after a scan; ones in use close when their operation ends, without waiting. */
    fun closeIdle() {
        smb.closeIdle()
        nfs.closeIdle()
        local.closeIdle()
    }
}

/**
 * Connections reused across operations, one per key (address and account), since a scan reads several files per
 * title and connecting for each would cost more than the reads. One operation at a time per key; other keys
 * never wait on it, even while it connects.
 */
internal class KeptConnections<C>(
    /** Closes without throwing; when broken, skips the sign-off. */
    private val close: (connection: C, broken: Boolean) -> Unit,
    /** False once the NAS has hung up. */
    private val isOpen: (C) -> Boolean,
    /** True for a connection failure (not a file failure), after which the connection is recreated. */
    private val isBroken: (Throwable) -> Boolean,
    /** Monotonic milliseconds (elapsedRealtime), so a clock set back cannot keep a session forever. */
    private val clock: () -> Long,
    /**
     * Whether [forget] waits for the key's running operation: an SMB sign-off must not cut a read short, while an
     * NFS mount is just dropped, so a new address is saved without waiting for a listing of the old one.
     */
    private val closingWaits: Boolean = true,
) {
    private class Kept<C>(val connection: C, val lastUse: Long)

    /** Per-key lock held for a whole operation, connecting included; a Mutex, so waits are cancellable. */
    private val operations = ConcurrentHashMap<String, Mutex>()

    /** Guarded by itself, which also guards [using]. */
    private val kept = mutableMapOf<String, Kept<C>>()

    /** Keys with a running operation; a key forgotten meanwhile is removed, so its connection closes afterwards. */
    private val using = mutableSetOf<String>()

    private fun operation(key: String) = operations.getOrPut(key) { Mutex() }

    /**
     * Runs [block] on [key]'s kept connection, or a new one from [connect] when none is kept, or it idled [IDLE_MS]
     * or hung up; a stale one is closed rather than abandoned, so the NAS frees its session. A broken reused
     * connection is replaced once; after a file failure (not found, denied) the connection stays kept.
     */
    suspend fun <T> use(key: String, connect: () -> C, block: (C) -> T): T = operation(key).withLock {
        val entry = synchronized(kept) {
            using += key
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
            synchronized(kept) { using -= key }
        }
    }

    /** Runs [block] on a fresh connection, closed afterwards, so the result does not depend on a kept one. */
    fun <T> once(connect: () -> C, block: (C) -> T): T {
        val connection = connect()
        val result = suspendRunCatching { block(connection) }
        close(connection, result.exceptionOrNull()?.let(isBroken) == true || !isOpen(connection))
        return result.getOrThrow()
    }

    /**
     * Closes connections of the keys [which] accepts; one in use closes when its operation ends, which this awaits
     * if [closingWaits]. Drops first, so a cancelled wait leaves nothing kept.
     */
    suspend fun forget(which: (String) -> Boolean) {
        drop(which)
        if (closingWaits) for (key in operations.keys.filter(which)) operation(key).withLock { drop { it == key } }
    }

    /** Closes every kept connection now, and those in use when their operation ends, without waiting. */
    fun closeIdle() = drop { true }

    private fun drop(which: (String) -> Boolean) {
        val dropped = synchronized(kept) {
            using.removeAll(which)
            kept.keys.filter(which).mapNotNull { kept.remove(it) }
        }
        dropped.forEach { close(it.connection, !isOpen(it.connection)) }
    }

    /** Runs [block], then keeps [connection] unless it broke or [key] was forgotten meanwhile. */
    private fun <T> attempt(key: String, connection: C, block: (C) -> T): Result<T> =
        suspendRunCatching { block(connection) }.also { result ->
            val broken = result.exceptionOrNull()?.let(isBroken) == true
            val keep = !broken && synchronized(kept) { (key in using).also { if (it) kept[key] = Kept(connection, clock()) } }
            if (!keep) close(connection, broken || !isOpen(connection))
        }

    companion object {
        /** Idle time after which a connection is replaced, as the NAS may have dropped it. */
        const val IDLE_MS = 60_000L
    }
}
