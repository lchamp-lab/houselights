package app.houselights.providers.local

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Lists video files of an SMB folder. */
object SmbBrowser : RemoteBrowser {
    /** A session unused this long is closed rather than reused: the NAS may have dropped it. */
    private const val IDLE_MS = 60_000L

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    override suspend fun list(source: SourceEntity): Listing = withSession(source) { share ->
        val videos = mutableListOf<RemoteFile>()
        val sidecars = mutableListOf<RemoteFile>()
        walk(share, source.path.trim('/'), "", 0, videos, sidecars)
        Listing(videos, sidecars)
    }

    override suspend fun test(source: SourceEntity): Int = withSession(source) { share ->
        share.list(source.path.trim('/').replace('/', '\\')).count { !RemoteBrowser.skip(it.fileName) }
    }

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray =
        withSession(source) { share ->
        val path = listOf(source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("\\") { it.replace('/', '\\') }
        share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { file ->
            val bytes = ByteArray(length)
            var total = 0
            while (total < length) {
                val n = file.read(bytes, offset + total, total, length - total)
                if (n <= 0) break
                total += n
            }
            if (total == length) bytes else bytes.copyOf(total)
        }
    }

    /** An open share, kept for the next file: a scan reads several files per title. */
    private class OpenShare(val connection: Connection, val share: DiskShare) {
        var lastUse = System.currentTimeMillis()

        fun close() {
            try {
                connection.close()
            } catch (_: IOException) {
                // Already gone.
            }
        }
    }

    /** Guarded by itself; one operation at a time per share. */
    private val open = mutableMapOf<String, OpenShare>()

    private fun key(source: SourceEntity) = "${source.username}@${source.domain}/${source.host}/${source.share}"

    /**
     * Runs [block] on the folder's share, connecting and signing in only when no recent session is open.
     * A session kept from before may have been dropped by the NAS: then a new one is opened, once.
     */
    private suspend fun <T> withSession(source: SourceEntity, block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        val key = key(source)
        synchronized(open) {
            val kept = open.remove(key)?.takeIf { it.share.isConnected && System.currentTimeMillis() - it.lastUse < IDLE_MS }
            if (kept != null) {
                try {
                    return@withContext block(kept.share).also { kept.lastUse = System.currentTimeMillis(); open[key] = kept }
                } catch (_: IOException) {
                    kept.close()
                } catch (_: SMBRuntimeException) {
                    kept.close()
                }
            }
            val fresh = connect(source)
            var stored = false
            try {
                block(fresh.share).also {
                    open[key] = fresh
                    stored = true
                }
            } finally {
                if (!stored) fresh.close()
            }
        }
    }

    private fun connect(source: SourceEntity): OpenShare {
        val connection = client.connect(source.host)
        var opened: OpenShare? = null
        try {
            val auth = if (source.username.isBlank()) AuthenticationContext.guest()
            else AuthenticationContext(source.username, source.password.value.toCharArray(), source.domain.ifBlank { null })
            val share = connection.authenticate(auth).connectShare(source.share) as DiskShare
            opened = OpenShare(connection, share)
            return opened
        } finally {
            if (opened == null) connection.close()
        }
    }

    /** Closes the sessions kept open; called when a scan ends. */
    fun closeAll() {
        synchronized(open) {
            open.values.forEach { it.close() }
            open.clear()
        }
    }

    private fun walk(
        share: DiskShare,
        root: String,
        relative: String,
        depth: Int,
        out: MutableList<RemoteFile>,
        sidecars: MutableList<RemoteFile>,
    ) {
        if (depth > RemoteBrowser.MAX_DEPTH) return
        val dir = listOf(root, relative).filter { it.isNotEmpty() }.joinToString("/")
        for (entry in share.list(dir.replace('/', '\\'))) {
            val name = entry.fileName
            if (RemoteBrowser.skip(name)) continue
            val rel = if (relative.isEmpty()) name else "$relative/$name"
            val isDir = entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            when {
                isDir -> walk(share, root, rel, depth + 1, out, sidecars)
                RemoteBrowser.isWanted(name) -> out += RemoteFile(rel, entry.endOfFile)
                RemoteBrowser.isSidecar(name) -> sidecars += RemoteFile(rel, entry.endOfFile)
            }
        }
    }
}
