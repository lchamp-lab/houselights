package app.houselights.providers.local

import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Lists video files of an NFSv3 export. No credentials: the NAS trusts the client IP.
 * The export must allow non-privileged ports, as a regular app cannot bind ports below 1024.
 */
object NfsBrowser : RemoteBrowser {
    override suspend fun list(source: SourceEntity): Listing = withContext(Dispatchers.IO) {
        withMount(source) { nfs ->
            val videos = mutableListOf<RemoteFile>()
            val sidecars = mutableListOf<RemoteFile>()
            walk(root(nfs, source), "", 0, videos, sidecars)
            Listing(videos, sidecars)
        }
    }

    override suspend fun test(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        withMount(source) { nfs -> root(nfs, source).list().count { !RemoteBrowser.skip(it) } }
    }

    override suspend fun readPrefix(source: SourceEntity, relativePath: String, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val path = "/" + listOf(source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("/")
        withMount(source) { nfs -> NfsFileInputStream(Nfs3File(nfs, path), 0L, 256 * 1024).use { it.readUpTo(length) } }
    }

    /** One mount per export, reused across files. */
    private val mounts = mutableMapOf<String, Nfs3>()

    private fun key(source: SourceEntity) = "${source.host}:/${source.share.trim('/')}"

    @Synchronized
    private fun mount(source: SourceEntity): Nfs3 =
        mounts.getOrPut(key(source)) { Nfs3(source.host, "/" + source.share.trim('/'), CredentialUnix(), 3) }

    @Synchronized
    private fun forget(source: SourceEntity) {
        mounts.remove(key(source))
    }

    /**
     * Runs [block] on the export's mount. A mount can go stale (the NAS restarted, its address
     * changed): after a failure it is made again, and the block tried once more.
     */
    private fun <T> withMount(source: SourceEntity, block: (Nfs3) -> T): T = try {
        block(mount(source))
    } catch (_: IOException) {
        forget(source)
        block(mount(source))
    }

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
}
