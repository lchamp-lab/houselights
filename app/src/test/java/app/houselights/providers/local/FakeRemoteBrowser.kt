package app.houselights.providers.local

import java.io.FileNotFoundException

/**
 * A NAS in memory, for scans: files with a size and, when given, their first bytes. Every read is
 * counted, and listings or reads can be made to fail.
 */
class FakeRemoteBrowser : RemoteBrowser {
    private class Entry(val size: Long, val bytes: ByteArray)

    private val files = linkedMapOf<String, Entry>()

    /** Thrown by listings instead of an answer, while set. */
    var listFailure: Throwable? = null

    /** Runs while a listing is under way: what happens on the device during a scan. */
    var onList: (suspend () -> Unit)? = null

    /** Thrown by reads instead of bytes, while set. */
    var readFailure: Throwable? = null

    /** Runs at each read, before its bytes are given: it may throw, to fail that read alone. */
    var onRead: ((path: String, offset: Long, length: Int) -> Unit)? = null

    /** Paths read, in order, failed reads included. */
    val reads = mutableListOf<String>()

    /** [bytes] stand for the file's start; past them, the file reads as ending there. */
    fun put(path: String, size: Long = DEFAULT_SIZE, bytes: ByteArray = ByteArray(0)) {
        files[path] = Entry(size, bytes)
    }

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
        val all = files.map { (path, entry) -> RemoteFile(path, entry.size) }
        fun name(file: RemoteFile) = file.relativePath.substringAfterLast('/')
        return Listing(
            videos = all.filter { RemoteBrowser.isWanted(name(it)) },
            sidecars = all.filter { RemoteBrowser.isSidecar(name(it)) },
        )
    }

    override suspend fun test(source: SourceEntity): Int = files.size

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray {
        reads += relativePath
        readFailure?.let { throw it }
        onRead?.invoke(relativePath, offset, length)
        val bytes = files[relativePath]?.bytes ?: throw FileNotFoundException(relativePath)
        val from = offset.coerceAtMost(bytes.size.toLong()).toInt()
        return bytes.copyOfRange(from, (from.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt())
    }

    companion object {
        const val DEFAULT_SIZE = 4_000_000_000L
    }
}
