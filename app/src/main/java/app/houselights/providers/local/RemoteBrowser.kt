package app.houselights.providers.local

import android.util.Log
import app.houselights.core.suspendRunCatching

data class RemoteFile(
    /** Path relative to the source folder, with "/" separators. */
    val relativePath: String,
    val size: Long,
    /**
     * When it was last written (ms since the epoch), as the listing gives it: SMB's last write time, NFS's
     * mtime. A sidecar rewritten in place often keeps its size, not its date. Null when not known.
     */
    val lastModified: Long? = null,
)

/**
 * Videos of a folder, and the files that describe them (NFO, artwork). [unreadable]: its subfolders
 * and files that could not be read, by path relative to it; what the library holds there is not gone.
 */
data class Listing(val videos: List<RemoteFile>, val sidecars: List<RemoteFile>, val unreadable: List<String> = emptyList())

/** Lists the video files of a network folder, whatever the protocol; [RemoteBrowsers] has the app's. */
interface RemoteBrowser {
    suspend fun list(source: SourceEntity): Listing

    /** Number of entries at the source root; throws with a readable message on failure. */
    suspend fun test(source: SourceEntity): Int

    /** The first [length] bytes of a file, or all of it when shorter: pass a length close to what is needed. */
    suspend fun readPrefix(source: SourceEntity, relativePath: String, length: Int): ByteArray = readAt(source, relativePath, 0, length)

    /** [length] bytes of a file from [offset], fewer past its end: what a file keeps at its end (its tags). */
    suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray

    companion object {
        const val MAX_DEPTH = 6

        /**
         * Hidden files (".DS_Store", ".AppleDouble", ".Trash-1000", ".@__thumb", and the "._" twin macOS
         * writes beside each video on a share), and the system folders [SYSTEM] names. Only those: "#Alive"
         * and "@Zombie" are films.
         */
        fun skip(name: String) = name.startsWith(".") || SYSTEM.any { it.equals(name, ignoreCase = true) }

        /**
         * Synology's and QNAP's recycle bins (Synology Drive's too), snapshots and thumbnails, the copies
         * QNAP transcodes beside each video, Windows' own folders, and lost+found, root's alone on ext4.
         */
        private val SYSTEM = listOf(
            "#recycle", "#snapshot", "#SynoRecycle", "@eaDir", "@tmp", "@Recycle", "@Recently-Snapshot", "@__thumb", "@Transcode",
            "\$RECYCLE.BIN", "System Volume Information", "lost+found",
        )

        fun isWanted(name: String) = FilenameParser.isVideo(name) && !isSample(name)

        private val SIDECAR_EXTENSIONS = setOf("nfo", "jpg", "jpeg", "png", "webp", "tbn")

        fun isSidecar(name: String) = name.substringAfterLast('.', "").lowercase() in SIDECAR_EXTENSIONS

        private val SAMPLE = Regex("""(?i)(^|[ ._-])sample([ ._-]|$)""")

        private fun isSample(name: String) = SAMPLE.containsMatchIn(name.substringBeforeLast('.'))
    }
}

/** A folder as a protocol lists it. */
internal fun interface RemoteFolder {
    /** Its entries; throws when it cannot be read. */
    fun entries(): List<RemoteEntry>
}

/** An entry of a [RemoteFolder]: what it is and its size may each cost a request, asked only when needed. */
internal interface RemoteEntry {
    val name: String

    /** The entry as a folder to walk into, or null for a file. */
    fun folder(): RemoteFolder?

    fun size(): Long

    /** When it was last written, from what [size] asked: no request of its own. Null when not known. */
    fun lastModified(): Long? = null
}

/**
 * The videos and sidecars under [root], [RemoteBrowser.MAX_DEPTH] folders deep at most. A subfolder
 * that cannot be read (lost+found, a folder of another user), or an entry that cannot be asked what
 * it is or its size (a file Radarr replaced between the listing and the question), is passed over and
 * named in [Listing.unreadable]. [root] failing, or a failure [isFatal] tells (the NAS gone, the
 * connection broken), fails the listing: what follows could not be read either.
 */
internal fun walk(root: RemoteFolder, isFatal: (Throwable) -> Boolean): Listing =
    Walk(isFatal).run {
        visit(root, "", 0)
        Listing(videos, sidecars, unreadable)
    }

/** One [walk], and what it found so far. */
private class Walk(private val isFatal: (Throwable) -> Boolean) {
    val videos = mutableListOf<RemoteFile>()
    val sidecars = mutableListOf<RemoteFile>()
    val unreadable = mutableListOf<String>()

    fun visit(folder: RemoteFolder, relative: String, depth: Int) {
        if (depth > RemoteBrowser.MAX_DEPTH) return
        val entries = suspendRunCatching { folder.entries() }.getOrElse { e ->
            if (relative.isEmpty()) throw e
            passOver(relative, e)
            return
        }
        for (entry in entries) {
            if (RemoteBrowser.skip(entry.name)) continue
            val path = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
            // What an entry is, and its size, may each be a request of their own (an NFS GETATTR).
            val subfolder = suspendRunCatching { take(entry, path) }.getOrElse { e ->
                passOver(path, e)
                null
            }
            subfolder?.let { visit(it, path, depth + 1) }
        }
    }

    /** The entry as a folder to walk into; a file is taken among the videos or the sidecars. */
    private fun take(entry: RemoteEntry, path: String): RemoteFolder? {
        entry.folder()?.let { return it }
        when {
            RemoteBrowser.isWanted(entry.name) -> videos += RemoteFile(path, entry.size(), entry.lastModified())
            RemoteBrowser.isSidecar(entry.name) -> sidecars += RemoteFile(path, entry.size(), entry.lastModified())
        }
        return null
    }

    private fun passOver(path: String, e: Throwable) {
        if (isFatal(e)) throw e
        Log.w("RemoteBrowser", "Could not read $path", e)
        unreadable += path
    }
}

/**
 * [relativePath] in [source]'s folder, as the share or export names it from its root: [separator]
 * between names, none before the first; "" for the root itself.
 */
internal fun remotePath(source: SourceEntity, relativePath: String = "", separator: Char = '/'): String =
    listOf(source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("/").replace('/', separator)

/**
 * Reads until [length] bytes or the end, straight into the array it returns: [read] puts at most
 * count bytes into the array from an index, and says how many it put, none (0 or less) at the end.
 */
internal inline fun readFully(length: Int, read: (bytes: ByteArray, at: Int, count: Int) -> Int): ByteArray {
    val bytes = ByteArray(length)
    var total = 0
    while (total < length) {
        val n = read(bytes, total, length - total)
        if (n <= 0) break
        total += n
    }
    return if (total == length) bytes else bytes.copyOf(total)
}

/** Reads until [length] bytes or the end of the stream, straight into the array it returns. */
internal fun java.io.InputStream.readUpTo(length: Int): ByteArray = readFully(length) { bytes, at, count -> read(bytes, at, count) }
