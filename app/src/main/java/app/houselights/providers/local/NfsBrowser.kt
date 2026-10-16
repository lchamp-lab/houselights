package app.houselights.providers.local

import android.os.SystemClock
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsGetAttributes
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
import java.io.FileNotFoundException
import java.net.SocketException

/**
 * Lists video files of an NFSv3 export; made once, by [RemoteBrowsers]. No credentials: the NAS
 * trusts the client IP. The export must allow non-privileged ports, as a regular app cannot bind
 * ports below 1024.
 */
internal class NfsBrowser(
    /** Mounts a folder's export; mounting asks the NAS for it. Tests hand in a NAS of their own. */
    private val mount: (SourceEntity) -> NfsExport = { Nfs3Export(Nfs3(it.host, export(it), CredentialUnix(), RETRIES)) },
    clock: () -> Long = SystemClock::elapsedRealtime,
) : RemoteBrowser {
    /**
     * One mount per export, reused across files. nfs-client keeps its own connections: a mount closes
     * by being dropped, so dropping one waits for no operation.
     */
    private val mounts = KeptConnections<NfsExport>(
        close = { _, _ -> },
        isOpen = { true },
        isBroken = ::isBroken,
        clock = clock,
        closingWaits = false,
    )

    override suspend fun list(source: SourceEntity): Listing = withMount(source) { export ->
        walk(folder(export, export.folder(root(source)))) { ScanErrors.isUnreachable(it) || isBroken(it) }
    }

    /** On a mount of its own: the NAS says now whether it lets the device in. */
    override suspend fun test(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        mounts.once({ mount(source) }) { export -> entries(export, export.folder(root(source))).count { !RemoteBrowser.skip(it.name) } }
    }

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray =
        withMount(source) { it.read("/" + remotePath(source, relativePath), offset, length) }

    private fun key(source: SourceEntity) = "${source.host}:${export(source)}"

    private suspend fun <T> withMount(source: SourceEntity, block: (NfsExport) -> T): T = withContext(Dispatchers.IO) {
        mounts.use(key(source), { mount(source) }, block)
    }

    /** Drops the mount of [source]'s export: the folder's address changed. */
    suspend fun forget(source: SourceEntity) {
        val key = key(source)
        mounts.forget { it == key }
    }

    /** Drops the mounts no operation uses; called when a scan ends. */
    fun closeIdle() = mounts.closeIdle()

    private fun root(source: SourceEntity) = "/" + remotePath(source)

    /**
     * The folder whose handle is [dir]. READDIRPLUS tells of each entry what it is, its size, its date
     * and its handle, a request or so for the whole folder. An entry told without them (a NAS may
     * leave them out) is looked up when first asked about, as every entry of a READDIR listing was.
     */
    private fun folder(export: NfsExport, dir: ByteArray): RemoteFolder = RemoteFolder {
        entries(export, dir).map { listed ->
            object : RemoteEntry {
                override val name: String = listed.name

                // A lookup that fails throws (a file gone, a NAS silent), and is asked again by the next question.
                private val entry by lazy { if (listed.isWhole) listed else export.lookup(dir, listed.name) }

                override fun folder() = entry.handle?.takeIf { entry.attributes?.isFolder == true }?.let { folder(export, it) }

                override fun size() = entry.attributes?.size ?: 0L

                override fun lastModified() = entry.attributes?.lastModified
            }
        }
    }

    companion object {
        /** nfs-client tries a request this many times before it fails. */
        private const val RETRIES = 3

        private fun export(source: SourceEntity) = "/" + source.share.trim('/')

        /**
         * Every entry of the folder [dir] but "." and "..", over as many READDIRPLUS answers as it takes:
         * each goes on from the cookie of the one before.
         */
        private fun entries(export: NfsExport, dir: ByteArray): List<NfsEntry> {
            val entries = mutableListOf<NfsEntry>()
            var page = export.readdirplus(dir, cookie = 0, verifier = 0)
            while (true) {
                page.entries.filterTo(entries) { it.name != "." && it.name != ".." }
                if (page.isLast) return entries
                // Asked again from the same cookie, it would answer the same, for ever.
                check(page.entries.isNotEmpty()) { "READDIRPLUS answered no entry before the end of the folder" }
                page = export.readdirplus(dir, page.cookie, page.verifier)
            }
        }

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

/** What a folder's entry is, as a READDIRPLUS answer, a LOOKUP or a GETATTR tells it. */
internal class NfsAttributes(val isFolder: Boolean, val size: Long, val lastModified: Long?)

/** An entry of a folder; [handle] and [attributes]: null when the NAS left them out of its answer. */
internal class NfsEntry(val name: String, val handle: ByteArray?, val attributes: NfsAttributes?) {
    /** Whether it tells all that is asked of it: what it is, its size, and a folder's handle, to list it. */
    val isWhole: Boolean get() = attributes != null && (!attributes.isFolder || handle != null)
}

/** One answer of READDIRPLUS: its [entries], and the [cookie] and [verifier] the next one starts from, unless [isLast]. */
internal class NfsPage(val entries: List<NfsEntry>, val cookie: Long, val verifier: Long, val isLast: Boolean)

/** An export mounted, as [NfsBrowser] lists and reads it: [Nfs3Export] on a NAS, a fake in tests. */
internal interface NfsExport {
    /** The handle of the folder at [path], from the export's root, "/" separators; throws when it is not there. */
    fun folder(path: String): ByteArray

    /** One READDIRPLUS: the entries of the folder [dir] from [cookie] on (0: from the start), "." and ".." among them. */
    fun readdirplus(dir: ByteArray, cookie: Long, verifier: Long): NfsPage

    /** [name] in the folder [dir], whole: a LOOKUP, then a GETATTR if the LOOKUP told no attributes. */
    fun lookup(dir: ByteArray, name: String): NfsEntry

    /** [length] bytes of the file at [path] (as [folder] takes it) from [offset], fewer past its end. */
    fun read(path: String, offset: Long, length: Int): ByteArray
}

/** An export mounted with nfs-client. A NAS that knows no READDIRPLUS is listed by READDIR, each entry looked up. */
private class Nfs3Export(private val nfs: Nfs3) : NfsExport {
    /** False once the NAS answered NFS3ERR_NOTSUPP to a READDIRPLUS. */
    @Volatile
    private var plus = true

    /** As Nfs3File lists a folder: its path's symbolic links followed, a folder that is not there a FileNotFoundException. */
    override fun folder(path: String): ByteArray = Nfs3File(nfs, path).followLinks().fileHandle ?: throw FileNotFoundException(path)

    override fun readdirplus(dir: ByteArray, cookie: Long, verifier: Long): NfsPage {
        if (plus) {
            try {
                val answer = nfs.wrapped_getReaddirplus(nfs.makeReaddirplusRequest(dir, cookie, verifier, DIR_BYTES, ANSWER_BYTES))
                val entries = answer.entries.map { entry ->
                    NfsEntry(entry.fileName, entry.fileHandle, entry.attributes?.takeIf { it.isLoaded }?.let(::attributes))
                }
                return NfsPage(entries, answer.cookie, answer.cookieverf, answer.isEof)
            } catch (e: NfsException) {
                if (e.status?.value != NfsStatus.NFS3ERR_NOTSUPP.value) throw e
                plus = false
            }
        }
        val answer = nfs.wrapped_getReaddir(nfs.makeReaddirRequest(dir, cookie, verifier, DIR_BYTES))
        return NfsPage(answer.entries.map { NfsEntry(it.fileName, null, null) }, answer.cookie, answer.cookieverf, answer.isEof)
    }

    override fun lookup(dir: ByteArray, name: String): NfsEntry {
        val found = nfs.wrapped_getLookup(nfs.makeLookupRequest(dir, name))
        val handle = found.fileHandle
        val told = found.attributes?.takeIf { it.isLoaded } ?: nfs.wrapped_getAttr(nfs.makeGetAttrRequest(handle)).attributes
        return NfsEntry(name, handle, attributes(told))
    }

    override fun read(path: String, offset: Long, length: Int): ByteArray =
        NfsFileInputStream(Nfs3File(nfs, path), offset, READ_BYTES).use { it.readUpTo(length) }

    private fun attributes(told: NfsGetAttributes) = NfsAttributes(told.type == NfsType.NFS_DIR, told.size, told.mtime?.timeInMillis)

    companion object {
        /** Bytes of names and cookies a READDIRPLUS answer holds at most, then of the whole answer: about 150 entries. */
        private const val DIR_BYTES = 8 * 1024
        private const val ANSWER_BYTES = 32 * 1024

        /** Bytes a read asks the NAS for at once. */
        private const val READ_BYTES = 256 * 1024
    }
}
