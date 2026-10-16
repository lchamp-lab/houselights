package app.houselights.providers.local

import android.util.Log
import app.houselights.core.suspendRunCatching

data class RemoteFile(
    /** Path relative to the source folder, with "/" separators. */
    val relativePath: String,
    val size: Long,
)

/**
 * Videos of a folder, and the files that describe them (NFO, artwork). [unreadable]: its subfolders
 * that could not be read, by path relative to it; what the library holds under them is not gone.
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

        /** Hidden files, Synology/QNAP system folders, recycle bins, and lost+found, root's alone at an ext4 volume's root. */
        fun skip(name: String) =
            name == "." || name == ".." || name.startsWith(".") || name.startsWith("@") || name.startsWith("#") ||
                name.equals("\$RECYCLE.BIN", ignoreCase = true) || name.equals("System Volume Information", ignoreCase = true) ||
                name == "lost+found"

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
}

/**
 * The videos and sidecars under [root], [RemoteBrowser.MAX_DEPTH] folders deep at most. A subfolder
 * that cannot be read (lost+found, a folder of another user) is passed over and named in
 * [Listing.unreadable]. [root] failing, or a failure [isFatal] tells (the NAS gone, the connection
 * broken), fails the listing: what follows could not be read either.
 */
internal fun walk(root: RemoteFolder, isFatal: (Throwable) -> Boolean): Listing {
    val videos = mutableListOf<RemoteFile>()
    val sidecars = mutableListOf<RemoteFile>()
    val unreadable = mutableListOf<String>()
    fun visit(folder: RemoteFolder, relative: String, depth: Int) {
        if (depth > RemoteBrowser.MAX_DEPTH) return
        val entries = suspendRunCatching { folder.entries() }.getOrElse { e ->
            if (relative.isEmpty() || isFatal(e)) throw e
            Log.w("RemoteBrowser", "Could not read $relative", e)
            unreadable += relative
            return
        }
        for (entry in entries) {
            val name = entry.name
            if (RemoteBrowser.skip(name)) continue
            val path = if (relative.isEmpty()) name else "$relative/$name"
            val subfolder = entry.folder()
            when {
                subfolder != null -> visit(subfolder, path, depth + 1)
                RemoteBrowser.isWanted(name) -> videos += RemoteFile(path, entry.size())
                RemoteBrowser.isSidecar(name) -> sidecars += RemoteFile(path, entry.size())
            }
        }
    }
    visit(root, "", 0)
    return Listing(videos, sidecars, unreadable)
}

/** Reads until [length] bytes or the end of the stream, straight into the array it returns. */
internal fun java.io.InputStream.readUpTo(length: Int): ByteArray {
    val bytes = ByteArray(length)
    var total = 0
    while (total < length) {
        val n = read(bytes, total, length - total)
        if (n < 0) break
        total += n
    }
    return if (total == length) bytes else bytes.copyOf(total)
}
