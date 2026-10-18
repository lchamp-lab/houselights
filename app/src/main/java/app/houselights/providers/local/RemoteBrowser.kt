package app.houselights.providers.local

import android.util.Log
import app.houselights.core.suspendRunCatching

data class RemoteFile(
    /** Path relative to the source folder, with "/" separators. */
    val relativePath: String,
    val size: Long,
    /** Last write time in epoch ms (SMB last write time, NFS mtime), null if unknown; catches same-size rewrites. */
    val lastModified: Long? = null,
)

/** Videos and sidecars of a source; [unreadable] lists paths that failed, which are not missing. */
data class Listing(val videos: List<RemoteFile>, val sidecars: List<RemoteFile>, val unreadable: List<String> = emptyList())

/** Lists and reads a network source, whatever the protocol; [RemoteBrowsers] holds the app's instances. */
interface RemoteBrowser {
    suspend fun list(source: SourceEntity): Listing

    /** Number of entries at the source root; throws with a readable message on failure. */
    suspend fun test(source: SourceEntity): Int

    /** The first [length] bytes of a file, or the whole file when shorter. */
    suspend fun readPrefix(source: SourceEntity, relativePath: String, length: Int): ByteArray = readAt(source, relativePath, 0, length)

    /** Up to [length] bytes from [offset], fewer past the end of the file. */
    suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray

    companion object {
        const val MAX_DEPTH = 6

        /**
         * Skips dot files (".DS_Store", ".AppleDouble", ".Trash-1000", ".@__thumb", macOS "._" twins) and [SYSTEM]
         * folders only, as "#Alive" and "@Zombie" are films.
         */
        fun skip(name: String) = name.startsWith(".") || SYSTEM.any { it.equals(name, ignoreCase = true) }

        /** Synology and QNAP recycle bins, snapshots, thumbnails, transcodes; Windows folders; lost+found. */
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

/** A remote folder, protocol-independent. */
internal fun interface RemoteFolder {
    /** Throws when the folder cannot be read. */
    fun entries(): List<RemoteEntry>
}

/** An entry of a [RemoteFolder]; its type and size may each cost a request, so they are queried lazily. */
internal interface RemoteEntry {
    val name: String

    /** Null for a file. */
    fun folder(): RemoteFolder?

    fun size(): Long

    /** Last write time from the same request as [size]; null if unknown. */
    fun lastModified(): Long? = null
}

/**
 * Videos and sidecars under [root], at most [RemoteBrowser.MAX_DEPTH] levels deep. Unreadable subfolders
 * (lost+found, another user's) and entries that fail (a file Radarr replaced mid-listing) are skipped and recorded
 * in [Listing.unreadable]. A failure on [root], or one [isFatal] accepts (NAS gone, connection broken), throws.
 */
internal fun walk(root: RemoteFolder, isFatal: (Throwable) -> Boolean): Listing =
    Walk(isFatal).run {
        visit(root, "", 0)
        Listing(videos, sidecars, unreadable)
    }

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
            // Type and size may each cost a request (an NFS GETATTR), so failures are per entry.
            val subfolder = suspendRunCatching { take(entry, path) }.getOrElse { e ->
                passOver(path, e)
                null
            }
            subfolder?.let { visit(it, path, depth + 1) }
        }
    }

    /** Returns a subfolder to walk, or records a file as a video or sidecar. */
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

/** [relativePath] under [source]'s folder, from the share or export root, joined by [separator]; "" for the root. */
internal fun remotePath(source: SourceEntity, relativePath: String = "", separator: Char = '/'): String =
    listOf(source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("/").replace('/', separator)

/** Reads up to [length] bytes via [read], which fills from `at` and returns the byte count, or 0 or less at the end. */
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
