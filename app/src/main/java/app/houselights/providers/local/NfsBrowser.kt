package app.houselights.providers.local

import android.os.SystemClock
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsGetAttributes
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.nfs.NfsType
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.Credential
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.net.SocketException

/**
 * Lists and reads an NFSv3 export; a single instance lives in [RemoteBrowsers]. The NAS trusts the client IP (no
 * password) and the user the folder presents itself as ([credential]), and the export must allow non-privileged
 * ports, as an app cannot bind ports below 1024.
 */
internal class NfsBrowser(
    /** Mounts an export as a user and group (a request to the NAS); replaceable in tests. */
    private val mount: (host: String, export: String, credential: Credential) -> NfsExport = { host, export, credential ->
        Nfs3Export(Nfs3(host, export, credential, RETRIES))
    },
    clock: () -> Long = SystemClock::elapsedRealtime,
    /** Lists a server's exports (a request to the NAS); replaceable in tests. */
    private val listExports: (host: String) -> List<ExportedFolder> = NfsExports::list,
) : RemoteBrowser {
    /** One mount per export and identity; nfs-client owns its sockets, so closing a mount is just dropping it. */
    private val mounts = KeptConnections<NfsMount>(
        close = { _, _ -> },
        isOpen = { true },
        isBroken = ::isBroken,
        clock = clock,
        closingWaits = false,
    )

    private val unreadable = UnreadablePaths()

    /**
     * About a READDIRPLUS a folder: the count moves a folder at a time. On a mount of its own, as a walk of thousands of
     * folders would otherwise hold the export's kept mount, and the add-folder flow's levels would wait for its end.
     */
    override suspend fun list(source: SourceEntity, found: (Int) -> Unit): Listing = withContext(Dispatchers.IO) {
        mounts.once({ mounted(source) }) { mounted ->
            val export = mounted.export
            val folder = Folders(export).folder(remotePath(source), export.folder(root(source)))
            walk(folder, unreadable.of(source), found) { ScanErrors.isUnreachable(it) || isBroken(it) }
        }
    }

    /** Uses a fresh mount so the NAS checks access now; links are followed as a scan follows them. */
    override suspend fun test(source: SourceEntity): FolderLevel = withContext(Dispatchers.IO) {
        mounts.once({ mounted(source) }) { mounted ->
            val export = mounted.export
            val folder = Folders(export).folder(remotePath(source), export.folder(root(source)))
            level(folder) { ScanErrors.isUnreachable(it) || isBroken(it) }
        }
    }

    /** On the export's kept mount: a mount that failed is not kept, so trying again after the NAS's rules changed mounts anew. */
    override suspend fun folders(source: SourceEntity, path: String): FolderLevel = withMount(source) { mounted ->
        val export = mounted.export
        val at = remotePath(source, cleanPath(path))
        level(Folders(export).folder(at, export.folder("/$at"))) { ScanErrors.isUnreachable(it) || isBroken(it) }
    }

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray =
        withMount(source) { it.read(remotePath(source, relativePath), offset, length) }

    /** The identity, then the export: "1026:100@nas:/volume1/media". */
    private fun key(source: SourceEntity) = "${source.uid ?: 0}:${source.gid ?: 0}@${place(source)}"

    private fun place(source: SourceEntity) = "${source.host}:${export(source)}"

    private suspend fun <T> withMount(source: SourceEntity, block: (NfsMount) -> T): T = withContext(Dispatchers.IO) {
        mounts.use(key(source), { mounted(source) }, block)
    }

    /** [source]'s export, mounted as [source] presents itself. */
    private fun mounted(source: SourceEntity) = NfsMount(mount(source.host, export(source), credential(source)))

    /** The exports of the server at [host], each with the clients it allows ([exportAccess]); throws as a mount does. */
    suspend fun exports(host: String): List<ExportedFolder> = withContext(Dispatchers.IO) { listExports(host.trim()) }

    /** Drops the mounts of [source]'s export, under any identity, after its address or identity changed. */
    suspend fun forget(source: SourceEntity) {
        val place = place(source)
        mounts.forget { it.substringAfter('@') == place }
    }

    /** Drops unused mounts; called when a scan ends. */
    fun closeIdle() = mounts.closeIdle()

    private fun root(source: SourceEntity) = "/" + remotePath(source)

    /**
     * Folders of one listing, each by its path from the export root without links. Symbolic links are followed
     * ([NfsLinks]), except to a folder the walk is in, which would list it again down to [RemoteBrowser.MAX_DEPTH].
     */
    private class Folders(private val export: NfsExport) {
        private val handles = HashMap<String, ByteArray>()
        private val links = NfsLinks(export) { path -> handles.getOrPut(path) { export.folder("/$path") } }

        /**
         * The folder at [path] with handle [dir], within the folders [above]. READDIRPLUS returns each entry's type,
         * size, date and handle in about one request per folder; entries without them (a NAS may omit them, READDIR
         * never has them) are looked up lazily.
         */
        fun folder(path: String, dir: ByteArray, above: Set<String> = emptySet()): RemoteFolder {
            handles[path] = dir
            val within = above + path
            return RemoteFolder {
                entries(export, dir).map { listed ->
                    object : RemoteEntry {
                        override val name: String = listed.name

                        // A failed lookup throws (file gone, NAS silent, broken link) and is retried on the next access.
                        private val target by lazy { links.reached(path, if (listed.isWhole) listed else export.lookup(dir, listed.name)) }

                        override fun folder() = target.entry.handle?.takeIf { target.entry.attributes?.isFolder == true }?.let {
                            if (target.path in within) throw UnfollowedLinkException("it leads back up the walk")
                            folder(target.path, it, within)
                        }

                        override fun size() = target.entry.attributes?.size ?: 0L

                        override fun lastModified() = target.entry.attributes?.lastModified
                    }
                }
            }
        }
    }

    companion object {
        /** Attempts per request in nfs-client. */
        private const val RETRIES = 3

        private fun export(source: SourceEntity) = "/" + source.share.trim('/')

        /** AUTH_SYS as [source] presents itself: its user and group, root (0, 0) unless it has its own. */
        private fun credential(source: SourceEntity) = CredentialUnix(source.uid ?: 0, source.gid ?: 0, null)

        /** All entries of [dir] except "." and "..", paging READDIRPLUS by cookie. */
        private fun entries(export: NfsExport, dir: ByteArray): List<NfsEntry> {
            val entries = mutableListOf<NfsEntry>()
            var page = export.readdirplus(dir, cookie = 0, verifier = 0)
            while (true) {
                page.entries.filterTo(entries) { it.name != "." && it.name != ".." }
                if (page.isLast) return entries
                // Repeating the same cookie would loop forever.
                check(page.entries.isNotEmpty()) { "READDIRPLUS answered no entry before the end of the folder" }
                page = export.readdirplus(dir, page.cookie, page.verifier)
            }
        }

        /**
         * A failure of the mount rather than the file, so the mount is recreated: a network error (an RpcException
         * NETWORK_ERROR wrapped in NfsException) or stale handles (NAS restarted, export moved).
         */
        internal fun isBroken(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
            it is SocketException || it is RpcException && it.status === RpcStatus.NETWORK_ERROR || isStale(it)
        }

        /** A handle the NAS no longer knows: its target was removed, or the NAS forgot the mount's handles. */
        internal fun isStale(e: Throwable): Boolean = e is NfsException && e.status?.value in STALE

        private val STALE = setOf(NfsStatus.NFS3ERR_STALE.value, NfsStatus.NFS3ERR_BADHANDLE.value)
    }
}

/**
 * A mounted export with a cache of recent file and folder handles by path, since a probe reads a file several
 * times and sidecars sit next to the video; without it every read would LOOKUP each path segment. Lives as long
 * as the mount; [KeptConnections] runs one operation at a time.
 */
private class NfsMount(val export: NfsExport) {
    /** LRU of what paths from the export root lead to ("" for the root), capped at [MAX_HANDLES]. */
    private val handles = object : LinkedHashMap<String, Reached>(MAX_HANDLES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Reached>) = size > MAX_HANDLES
    }

    /** A path's handle, and its path without links, from which a link beneath it is followed. */
    private class Reached(val handle: ByteArray, val path: String)

    private val links = NfsLinks(export) { path -> handle(path) }

    /**
     * Reads up to [length] bytes at [offset]. A stale cached handle (file replaced by a Radarr upgrade, folder
     * moved) is looked up once more; if still stale, the mount is at fault ([NfsBrowser.isBroken]) and recreated.
     */
    fun read(path: String, offset: Long, length: Int): ByteArray =
        try {
            export.read(handle(path), offset, length)
        } catch (e: NfsException) {
            if (!NfsBrowser.isStale(e)) throw e
            forget(path)
            export.read(handle(path), offset, length)
        }

    /** Handle of [path], with a LOOKUP per segment below the deepest cached folder, links followed ([NfsLinks]). */
    private fun handle(path: String): ByteArray {
        val names = path.split('/').filter { it.isNotEmpty() }
        val paths = names.runningFold("") { folder, name -> NfsLinks.joined(folder, name) }
        val known = paths.indexOfLast { it in handles }
        var reached = if (known < 0) Reached(export.folder("/"), "").also { handles[""] = it } else handles.getValue(paths[known])
        for (depth in maxOf(known, 0) + 1 until paths.size) {
            val target = links.reached(reached.path, export.lookup(reached.handle, names[depth - 1]))
            reached = Reached(target.entry.handle ?: throw FileNotFoundException(path), target.path)
            handles[paths[depth]] = reached
        }
        return reached.handle
    }

    /** Drops the cached handles of [path], its folders and the root. */
    private fun forget(path: String) {
        handles.keys.removeAll { it.isEmpty() || it == path || path.startsWith("$it/") }
    }

    private companion object {
        /** Enough for a title's folders and files, plus those of a few previous titles. */
        const val MAX_HANDLES = 64
        const val LOAD_FACTOR = 0.75f
    }
}

/** Entry attributes from READDIRPLUS, LOOKUP or GETATTR; a symbolic link's are its own, not its target's. */
internal class NfsAttributes(val isFolder: Boolean, val size: Long, val lastModified: Long?, val isLink: Boolean = false)

/** A folder entry; [handle] and [attributes] are null when the NAS omitted them. */
internal class NfsEntry(val name: String, val handle: ByteArray?, val attributes: NfsAttributes?) {
    /** True when no LOOKUP is needed: attributes are present, and a folder or link has its handle. */
    val isWhole: Boolean get() = attributes != null && (!attributes.isFolder && !attributes.isLink || handle != null)
}

/** One READDIRPLUS page; the next starts from [cookie] and [verifier] unless [isLast]. */
internal class NfsPage(val entries: List<NfsEntry>, val cookie: Long, val verifier: Long, val isLast: Boolean)

/** A mounted export as [NfsBrowser] uses it: [Nfs3Export] on a NAS, a fake in tests. */
internal interface NfsExport {
    /** Handle of the folder at [path] from the export root; throws when missing. */
    fun folder(path: String): ByteArray

    /** One READDIRPLUS call from [cookie] (0 for the start); includes "." and "..". */
    fun readdirplus(dir: ByteArray, cookie: Long, verifier: Long): NfsPage

    /** LOOKUP of [name] in [dir], plus GETATTR when the LOOKUP returned no attributes. */
    fun lookup(dir: ByteArray, name: String): NfsEntry

    fun read(file: ByteArray, offset: Long, length: Int): ByteArray

    /** READLINK: the target of the symbolic link [link], as written. */
    fun readlink(link: ByteArray): String
}

/** nfs-client export; falls back to READDIR with per-entry lookups when READDIRPLUS is unsupported. */
private class Nfs3Export(private val nfs: Nfs3) : NfsExport {
    /** Cleared once the NAS answers NFS3ERR_NOTSUPP to READDIRPLUS. */
    @Volatile
    private var plus = true

    /** Resolves like Nfs3File: symbolic links followed, FileNotFoundException when missing. */
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
     * READs from the handle in chunks of at most [READ_BYTES], continuing after short answers. Bypasses Nfs3File,
     * which queries access, server limits and file size before reading, and the size again per block.
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

    override fun readlink(link: ByteArray): String = nfs.wrapped_getReadlink(nfs.makeReadlinkRequest(link)).data

    private fun attributes(told: NfsGetAttributes) =
        NfsAttributes(told.type == NfsType.NFS_DIR, told.size, told.mtime?.timeInMillis, isLink = told.type == NfsType.NFS_LNK)

    companion object {
        /** READDIRPLUS dircount and maxcount limits: about 150 entries per answer. */
        private const val DIR_BYTES = 8 * 1024
        private const val ANSWER_BYTES = 32 * 1024

        private const val READ_BYTES = 256 * 1024
    }
}
