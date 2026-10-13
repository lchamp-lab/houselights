package app.houselights.providers.local

data class RemoteFile(
    /** Path relative to the source folder, with "/" separators. */
    val relativePath: String,
    val size: Long,
)

/** Lists the video files of a network folder, whatever the protocol. */
interface RemoteBrowser {
    suspend fun listVideos(source: SourceEntity): List<RemoteFile>

    /** Number of entries at the source root; throws with a readable message on failure. */
    suspend fun test(source: SourceEntity): Int

    companion object {
        const val MAX_DEPTH = 6

        fun of(source: SourceEntity): RemoteBrowser = when (source.protocol) {
            Protocol.SMB -> SmbBrowser
            Protocol.NFS -> NfsBrowser
        }

        /** Hidden files, Synology/QNAP system folders, recycle bins. */
        fun skip(name: String) =
            name == "." || name == ".." || name.startsWith(".") || name.startsWith("@") || name.startsWith("#") ||
                name.equals("\$RECYCLE.BIN", ignoreCase = true) || name.equals("System Volume Information", ignoreCase = true)

        fun isWanted(name: String) = FilenameParser.isVideo(name) && !isSample(name)

        private fun isSample(name: String) =
            Regex("""(?i)(^|[ ._-])sample([ ._-]|$)""").containsMatchIn(name.substringBeforeLast('.'))
    }
}
