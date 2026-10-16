package app.houselights.providers.local

import android.os.SystemClock
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Lists video files of an NFSv3 export; made once, by [RemoteBrowsers]. No credentials: the NAS
 * trusts the client IP. The export must allow non-privileged ports, as a regular app cannot bind
 * ports below 1024.
 */
internal class NfsBrowser(
    /** Mounts a folder's export; making an Nfs3 asks the NAS for it. */
    private val mount: (SourceEntity) -> Nfs3 = { Nfs3(it.host, export(it), CredentialUnix(), RETRIES) },
    clock: () -> Long = SystemClock::elapsedRealtime,
) : RemoteBrowser {
    /** One mount per export, reused across files. nfs-client keeps its own connections: a mount closes by being dropped. */
    private val mounts = KeptConnections<Nfs3>(
        close = { _, _ -> },
        isOpen = { true },
        // A mount can go stale (the NAS restarted, its address changed): it is made again.
        isBroken = { it is IOException },
        clock = clock,
    )

    override suspend fun list(source: SourceEntity): Listing = withMount(source) { nfs ->
        val videos = mutableListOf<RemoteFile>()
        val sidecars = mutableListOf<RemoteFile>()
        walk(root(nfs, source), "", 0, videos, sidecars)
        Listing(videos, sidecars)
    }

    override suspend fun test(source: SourceEntity): Int =
        withMount(source) { nfs -> root(nfs, source).list().count { !RemoteBrowser.skip(it) } }

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray {
        val path = "/" + listOf(source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("/")
        return withMount(source) { nfs -> NfsFileInputStream(Nfs3File(nfs, path), offset, BUFFER_BYTES).use { it.readUpTo(length) } }
    }

    private fun key(source: SourceEntity) = "${source.host}:${export(source)}"

    private suspend fun <T> withMount(source: SourceEntity, block: (Nfs3) -> T): T = withContext(Dispatchers.IO) {
        mounts.use(key(source), { mount(source) }, block)
    }

    /** Drops the mount of [source]'s export: the folder's address changed. */
    fun forget(source: SourceEntity) {
        val key = key(source)
        mounts.forget { it == key }
    }

    /** Drops the mounts no operation uses; called when a scan ends. */
    fun closeIdle() = mounts.closeIdle()

    private fun root(nfs: Nfs3, source: SourceEntity): Nfs3File = Nfs3File(nfs, "/" + source.path.trim('/'))

    private fun walk(dir: Nfs3File, relative: String, depth: Int, out: MutableList<RemoteFile>, sidecars: MutableList<RemoteFile>) {
        if (depth > RemoteBrowser.MAX_DEPTH) return
        for (entry in dir.listFiles()) {
            val name = entry.name
            if (RemoteBrowser.skip(name)) continue
            val rel = if (relative.isEmpty()) name else "$relative/$name"
            when {
                entry.isDirectory -> walk(entry, rel, depth + 1, out, sidecars)
                RemoteBrowser.isWanted(name) -> out += RemoteFile(rel, entry.length())
                RemoteBrowser.isSidecar(name) -> sidecars += RemoteFile(rel, entry.length())
            }
        }
    }

    private companion object {
        /** nfs-client tries a request this many times before it fails. */
        const val RETRIES = 3

        /** Bytes a read asks the NAS for at once. */
        const val BUFFER_BYTES = 256 * 1024

        fun export(source: SourceEntity) = "/" + source.share.trim('/')
    }
}
