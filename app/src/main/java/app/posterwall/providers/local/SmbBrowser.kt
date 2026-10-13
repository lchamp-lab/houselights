package app.posterwall.providers.local

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class RemoteFile(
    /** Path relative to the source folder, with "/" separators. */
    val relativePath: String,
    val size: Long,
)

/** Lists video files of an SMB folder. */
object SmbBrowser {
    private const val MAX_DEPTH = 6

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    suspend fun listVideos(source: SourceEntity): List<RemoteFile> = withSession(source) { share ->
        val results = mutableListOf<RemoteFile>()
        walk(share, source.path.trim('/'), "", 0, results)
        results
    }

    /** Number of entries at the source root; throws with a readable message on failure. */
    suspend fun test(source: SourceEntity): Int = withSession(source) { share ->
        share.list(source.path.trim('/').replace('/', '\\')).count { !skip(it.fileName) }
    }

    private suspend fun <T> withSession(source: SourceEntity, block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        client.connect(source.host).use { connection ->
            val auth = if (source.username.isBlank()) AuthenticationContext.guest()
            else AuthenticationContext(source.username, source.password.toCharArray(), source.domain.ifBlank { null })
            val session = connection.authenticate(auth)
            (session.connectShare(source.share) as DiskShare).use(block)
        }
    }

    private fun walk(share: DiskShare, root: String, relative: String, depth: Int, out: MutableList<RemoteFile>) {
        if (depth > MAX_DEPTH) return
        val dir = listOf(root, relative).filter { it.isNotEmpty() }.joinToString("/")
        for (entry in share.list(dir.replace('/', '\\'))) {
            val name = entry.fileName
            if (skip(name)) continue
            val rel = if (relative.isEmpty()) name else "$relative/$name"
            val isDir = entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            when {
                isDir -> walk(share, root, rel, depth + 1, out)
                FilenameParser.isVideo(name) && !isSample(name) -> out += RemoteFile(rel, entry.endOfFile)
            }
        }
    }

    /** Hidden files, Synology/QNAP system folders, recycle bins. */
    private fun skip(name: String) =
        name == "." || name == ".." || name.startsWith(".") || name.startsWith("@") || name.startsWith("#") ||
            name.equals("\$RECYCLE.BIN", ignoreCase = true) || name.equals("System Volume Information", ignoreCase = true)

    private fun isSample(name: String) = Regex("""(?i)(^|[ ._-])sample([ ._-]|$)""").containsMatchIn(name.substringBeforeLast('.'))
}
