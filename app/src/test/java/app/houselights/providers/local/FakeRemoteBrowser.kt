package app.houselights.providers.local

import java.io.FileNotFoundException
import java.nio.charset.Charset
import java.util.Locale

/** An in-memory NAS for scans: files with a size, first bytes and date; it counts reads and can fail on demand. */
class FakeRemoteBrowser : RemoteBrowser {
    private class Entry(val size: Long, val bytes: ByteArray, val lastModified: Long?)

    private val files = linkedMapOf<String, Entry>()

    /** Thrown by listings instead of an answer, while set. */
    var listFailure: Throwable? = null

    /** Folders a listing cannot read, while set: their files are not listed, and the folders are named. */
    var unreadable: List<String> = emptyList()

    /** Runs during a listing, to simulate what happens on the device mid-scan. */
    var onList: (suspend () -> Unit)? = null

    /** Thrown by reads instead of bytes, while set. */
    var readFailure: Throwable? = null

    /** Runs at each read, before its bytes are given: it may throw, to fail that read alone, or wait. */
    var onRead: (suspend (path: String, offset: Long, length: Int) -> Unit)? = null

    /** Paths read, in order, failed reads included. */
    val reads = mutableListOf<String>()

    /** [bytes] stand for the file's start; past them, the file reads as ending there. [lastModified]: as SMB or NFS lists it. */
    fun put(path: String, size: Long = DEFAULT_SIZE, bytes: ByteArray = ByteArray(0), lastModified: Long? = null) {
        files[path] = Entry(size, bytes, lastModified)
    }

    /** A text file, an NFO say, read whole. */
    fun putText(path: String, text: String, lastModified: Long? = null, charset: Charset = Charsets.UTF_8) {
        val bytes = text.toByteArray(charset)
        put(path, size = bytes.size.toLong(), bytes = bytes, lastModified = lastModified)
    }

    /** An image of [size] bytes, read whole. */
    fun putImage(path: String, size: Int = 100, lastModified: Long? = null) =
        put(path, size = size.toLong(), bytes = ByteArray(size), lastModified = lastModified)

    fun remove(path: String) {
        files.remove(path)
    }

    fun rename(from: String, to: String) {
        files[to] = checkNotNull(files.remove(from)) { "No $from" }
    }

    fun clear() = files.clear()

    override suspend fun list(source: SourceEntity): Listing {
        listFailure?.let { throw it }
        onList?.invoke()
        val all = files.filterKeys { path -> unreadable.none { path.startsWith("$it/") } }
            .map { (path, entry) -> RemoteFile(path, entry.size, entry.lastModified) }
        fun name(file: RemoteFile) = file.relativePath.substringAfterLast('/')
        return Listing(
            videos = all.filter { RemoteBrowser.isWanted(name(it)) },
            sidecars = all.filter { RemoteBrowser.isSidecar(name(it)) },
            unreadable = unreadable,
        )
    }

    override suspend fun test(source: SourceEntity): Int = files.size

    /** The folders the files put imply under [path], hidden ones left out, and its own videos; a folder without files is not there. */
    override suspend fun folders(source: SourceEntity, path: String): FolderLevel {
        listFailure?.let { throw it }
        val folder = cleanPath(path)
        val under = files.keys.filter { folder.isEmpty() || it.startsWith("$folder/") }.map { it.removePrefix("$folder/") }
        if (folder.isNotEmpty() && under.isEmpty()) throw FileNotFoundException(folder)
        val folders = under.filter { '/' in it }.map { it.substringBefore('/') }.distinct().filterNot { RemoteBrowser.skip(it) }
        return FolderLevel(folders.sortedWith(byName(Locale.ROOT)), under.count { '/' !in it && RemoteBrowser.isWanted(it) })
    }

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray {
        reads += relativePath
        readFailure?.let { throw it }
        onRead?.invoke(relativePath, offset, length)
        val bytes = files[relativePath]?.bytes ?: throw FileNotFoundException(relativePath)
        val from = offset.coerceAtMost(bytes.size.toLong()).toInt()
        return bytes.copyOfRange(from, (from.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt())
    }

    companion object {
        private const val DEFAULT_SIZE = 4_000_000_000L

        /** Two modification dates, as SMB or NFS list them. */
        const val T1 = 1_760_000_000_000L
        const val T2 = T1 + 60_000L
    }
}
