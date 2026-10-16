package app.houselights.providers.local

import android.os.SystemClock
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.nfs.NfsType
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketException

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
    /**
     * One mount per export, reused across files. nfs-client keeps its own connections: a mount closes
     * by being dropped, so dropping one waits for no operation.
     */
    private val mounts = KeptConnections<Nfs3>(
        close = { _, _ -> },
        isOpen = { true },
        isBroken = ::isBroken,
        clock = clock,
        closingWaits = false,
    )

    override suspend fun list(source: SourceEntity): Listing = withMount(source) { nfs ->
        walk(folder(root(nfs, source))) { ScanErrors.isUnreachable(it) || isBroken(it) }
    }

    /** On a mount of its own: the NAS says now whether it lets the device in. */
    override suspend fun test(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        mounts.once({ mount(source) }) { nfs -> root(nfs, source).list().count { !RemoteBrowser.skip(it) } }
    }

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray {
        val path = "/" + listOf(source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("/")
        return withMount(source) { nfs -> NfsFileInputStream(Nfs3File(nfs, path), offset, BUFFER_BYTES).use { it.readUpTo(length) } }
    }

    private fun key(source: SourceEntity) = "${source.host}:${export(source)}"

    private suspend fun <T> withMount(source: SourceEntity, block: (Nfs3) -> T): T = withContext(Dispatchers.IO) {
        mounts.use(key(source), { mount(source) }, block)
    }

    /** Drops the mount of [source]'s export: the folder's address changed. */
    suspend fun forget(source: SourceEntity) {
        val key = key(source)
        mounts.forget { it == key }
    }

    /** Drops the mounts no operation uses; called when a scan ends. */
    fun closeIdle() = mounts.closeIdle()

    private fun root(nfs: Nfs3, source: SourceEntity): Nfs3File = Nfs3File(nfs, "/" + source.path.trim('/'))

    /**
     * A listing names the entries; what each is, its size and its date are asked of the NAS when needed,
     * in one GETATTR.
     */
    private fun folder(dir: Nfs3File): RemoteFolder = RemoteFolder {
        dir.listFiles().map { file ->
            object : RemoteEntry {
                override val name: String = file.name

                // One GETATTR. It throws when it fails (a file gone, a NAS silent), where length() would answer
                // 0; a failed one is asked again by the next question.
                private val attributes by lazy { file.attributes }

                override fun folder() = if (attributes.type == NfsType.NFS_DIR) folder(file) else null

                override fun size() = attributes.size

                override fun lastModified() = attributes.mtime?.timeInMillis
            }
        }
    }

    companion object {
        /** nfs-client tries a request this many times before it fails. */
        private const val RETRIES = 3

        /** Bytes a read asks the NAS for at once. */
        private const val BUFFER_BYTES = 256 * 1024

        private fun export(source: SourceEntity) = "/" + source.share.trim('/')

        /**
         * A failure of the mount, after which it is made again: not one of the file. The network failed
         * (an RpcException of status NETWORK_ERROR, inside the request's NfsException), or the NAS no
         * longer knows the mount's handles: it restarted, or the export moved.
         */
        internal fun isBroken(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
            it is SocketException || it is RpcException && it.status === RpcStatus.NETWORK_ERROR ||
                it is NfsException && it.status?.value in STALE
        }

        private val STALE = setOf(NfsStatus.NFS3ERR_STALE.value, NfsStatus.NFS3ERR_BADHANDLE.value)
    }
}
