package app.houselights.providers.local

import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists video files of an NFSv3 export. No credentials: the NAS trusts the client IP.
 * The export must allow non-privileged ports, as a regular app cannot bind ports below 1024.
 */
object NfsBrowser : RemoteBrowser {
    override suspend fun list(source: SourceEntity): Listing = withContext(Dispatchers.IO) {
        val videos = mutableListOf<RemoteFile>()
        val sidecars = mutableListOf<RemoteFile>()
        walk(root(source), "", 0, videos, sidecars)
        Listing(videos, sidecars)
    }

    override suspend fun test(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        root(source).list().count { !RemoteBrowser.skip(it) }
    }

    override suspend fun readPrefix(source: SourceEntity, relativePath: String, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val path = "/" + listOf(source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("/")
        NfsFileInputStream(Nfs3File(mount(source), path), 0L, 256 * 1024).use { it.readUpTo(length) }
    }

    /** One mount per export, reused across files. */
    private val mounts = mutableMapOf<String, Nfs3>()

    @Synchronized
    private fun mount(source: SourceEntity): Nfs3 {
        val export = "/" + source.share.trim('/')
        return mounts.getOrPut("${source.host}:$export") { Nfs3(source.host, export, CredentialUnix(), 3) }
    }

    private fun root(source: SourceEntity): Nfs3File = Nfs3File(mount(source), "/" + source.path.trim('/'))

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
