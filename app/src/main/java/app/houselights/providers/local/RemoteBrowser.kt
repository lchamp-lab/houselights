package app.houselights.providers.local

data class RemoteFile(
    /** Path relative to the source folder, with "/" separators. */
    val relativePath: String,
    val size: Long,
)

/** Videos of a folder, and the files that describe them (NFO, artwork). */
data class Listing(val videos: List<RemoteFile>, val sidecars: List<RemoteFile>)

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

        /** Hidden files, Synology/QNAP system folders, recycle bins. */
        fun skip(name: String) =
            name == "." || name == ".." || name.startsWith(".") || name.startsWith("@") || name.startsWith("#") ||
                name.equals("\$RECYCLE.BIN", ignoreCase = true) || name.equals("System Volume Information", ignoreCase = true)

        fun isWanted(name: String) = FilenameParser.isVideo(name) && !isSample(name)

        private val SIDECAR_EXTENSIONS = setOf("nfo", "jpg", "jpeg", "png", "webp", "tbn")

        fun isSidecar(name: String) = name.substringAfterLast('.', "").lowercase() in SIDECAR_EXTENSIONS

        private val SAMPLE = Regex("""(?i)(^|[ ._-])sample([ ._-]|$)""")

        private fun isSample(name: String) = SAMPLE.containsMatchIn(name.substringBeforeLast('.'))
    }
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
