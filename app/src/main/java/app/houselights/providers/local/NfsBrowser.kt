package app.houselights.providers.local

import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists video files of an NFSv3 export. No credentials: the NAS trusts the client IP.
 * The export must allow non-privileged ports, as a regular app cannot bind ports below 1024.
 */
object NfsBrowser : RemoteBrowser {
    override suspend fun listVideos(source: SourceEntity): List<RemoteFile> = withContext(Dispatchers.IO) {
        val results = mutableListOf<RemoteFile>()
        walk(root(source), "", 0, results)
        results
    }

    override suspend fun test(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        root(source).list().count { !RemoteBrowser.skip(it) }
    }

    private fun root(source: SourceEntity): Nfs3File {
        val nfs = Nfs3(source.host, "/" + source.share.trim('/'), CredentialUnix(), 3)
        return Nfs3File(nfs, "/" + source.path.trim('/'))
    }

    private fun walk(dir: Nfs3File, relative: String, depth: Int, out: MutableList<RemoteFile>) {
        if (depth > RemoteBrowser.MAX_DEPTH) return
        for (entry in dir.listFiles()) {
            val name = entry.name
            if (RemoteBrowser.skip(name)) continue
            val rel = if (relative.isEmpty()) name else "$relative/$name"
            when {
                entry.isDirectory -> walk(entry, rel, depth + 1, out)
                RemoteBrowser.isWanted(name) -> out += RemoteFile(rel, entry.length())
            }
        }
    }
}
