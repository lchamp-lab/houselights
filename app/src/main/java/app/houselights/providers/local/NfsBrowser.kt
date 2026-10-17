package app.houselights.providers.local

import android.os.SystemClock
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsGetAttributes
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.nfs.NfsType
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
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
    private val mounts = KeptConnections<NfsMount>(
        close = { _, _ -> },
        isOpen = { true },
        isBroken = ::isBroken,
        clock = clock,
        closingWaits = false,
    )

    override suspend fun list(source: SourceEntity): Listing = withMount(source) { mounted ->
        val export = mounted.export
        walk(folder(export, export.folder(root(source)))) { ScanErrors.isUnreachable(it) || isBroken(it) }
    }

    /** On a mount of its own: the NAS says now whether it lets the device in. */
    override suspend fun test(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        mounts.once({ NfsMount(mount(source)) }) { mounted ->
            entries(mounted.export, mounted.export.folder(root(source))).count { !RemoteBrowser.skip(it.name) }
        }
    }

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray =
        withMount(source) { it.read(remotePath(source, relativePath), offset, length) }

    private fun key(source: SourceEntity) = "${source.host}:${export(source)}"

    private suspend fun <T> withMount(source: SourceEntity, block: (NfsMount) -> T): T = withContext(Dispatchers.IO) {
        mounts.use(key(source), { NfsMount(mount(source)) }, block)
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
            it is SocketException || it is RpcException && it.status === RpcStatus.NETWORK_ERROR || isStale(it)
        }

        /** A handle the NAS no longer knows: what it named was removed, or the NAS forgot the mount's handles. */
        internal fun isStale(e: Throwable): Boolean = e is NfsException && e.status?.value in STALE

        private val STALE = setOf(NfsStatus.NFS3ERR_STALE.value, NfsStatus.NFS3ERR_BADHANDLE.value)
    }
}

/**
 * An export mounted, and the handles of the files read on it lately and of their folders, by path: a
 * probe reads a file several times, and a title's NFO and artwork lie beside its video, where each
 * read looked every name of its path up again. Dropped with the mount, so kept no longer than a scan
 * keeps it. One operation at a time, as [KeptConnections] runs them.
 */
private class NfsMount(val export: NfsExport) {
    /** By path from the export's root, "" for the root; past [MAX_HANDLES], the one least lately used goes. */
    private val handles = object : LinkedHashMap<String, ByteArray>(MAX_HANDLES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX_HANDLES
    }

    /**
     * [length] bytes of the file at [path] from [offset], fewer past its end. A handle kept from before
     * that the NAS no longer knows (the file replaced, as Radarr upgrades it; a folder moved) is looked up
     * once more: still unknown, the mount is at fault ([NfsBrowser.isBroken]), and made again.
     */
    fun read(path: String, offset: Long, length: Int): ByteArray =
        try {
            export.read(handle(path), offset, length)
        } catch (e: NfsException) {
            if (!NfsBrowser.isStale(e)) throw e
            forget(path)
            export.read(handle(path), offset, length)
        }

    /** The handle of the file at [path]: a LOOKUP for each name below the deepest of its folders known. */
    private fun handle(path: String): ByteArray {
        val names = path.split('/').filter { it.isNotEmpty() }
        // "", "films", "films/Dune (2021)", "films/Dune (2021)/movie.nfo": the root, each folder, the file.
        val paths = names.runningFold("") { folder, name -> if (folder.isEmpty()) name else "$folder/$name" }
        val known = paths.indexOfLast { it in handles }
        var handle = if (known < 0) export.folder("/").also { handles[""] = it } else handles.getValue(paths[known])
        for (depth in maxOf(known, 0) + 1 until paths.size) {
            handle = export.lookup(handle, names[depth - 1]).handle ?: throw FileNotFoundException(path)
            handles[paths[depth]] = handle
        }
        return handle
    }

    /** Drops the handles of [path], of its folders and of the root: what its read went through. */
    private fun forget(path: String) {
        handles.keys.removeAll { it.isEmpty() || it == path || path.startsWith("$it/") }
    }

    private companion object {
        /** Handles kept: a title's folders and files, and those of the few titles read before it. */
        const val MAX_HANDLES = 64
        const val LOAD_FACTOR = 0.75f
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

    /** [length] bytes of the file whose handle is [file] from [offset], fewer past its end. */
    fun read(file: ByteArray, offset: Long, length: Int): ByteArray
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

    /**
     * READs straight from the handle, [READ_BYTES] at most each; a NAS answers less past its own most,
     * and is asked on from there. Not through Nfs3File: it asks the NAS for access, its maximums and the
     * file's size before reading, and the size again at each block.
     */
    override fun read(file: ByteArray, offset: Long, length: Int): ByteArray {
        var atEnd = false
        return readFully(length) { bytes, at, count ->
            if (atEnd) return@readFully 0
            val answer = nfs.wrapped_getRead(nfs.makeReadRequest(file, offset + at, minOf(count, READ_BYTES)), bytes, at)
            atEnd = answer.isEof
            answer.bytesRead
        }
    }

    private fun attributes(told: NfsGetAttributes) = NfsAttributes(told.type == NfsType.NFS_DIR, told.size, told.mtime?.timeInMillis)

    companion object {
        /** Bytes of names and cookies a READDIRPLUS answer holds at most, then of the whole answer: about 150 entries. */
        private const val DIR_BYTES = 8 * 1024
        private const val ANSWER_BYTES = 32 * 1024

        /** Bytes a read asks the NAS for at once. */
        private const val READ_BYTES = 256 * 1024
    }
}
