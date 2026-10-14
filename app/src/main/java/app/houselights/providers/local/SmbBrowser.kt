package app.houselights.providers.local

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Lists video files of an SMB folder. */
object SmbBrowser : RemoteBrowser {

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    override suspend fun list(source: SourceEntity): Listing = withSession(source) { share ->
        val videos = mutableListOf<RemoteFile>()
        val sidecars = mutableListOf<RemoteFile>()
        walk(share, source.path.trim('/'), "", 0, videos, sidecars)
        Listing(videos, sidecars)
    }

    override suspend fun test(source: SourceEntity): Int = withSession(source) { share ->
        share.list(source.path.trim('/').replace('/', '\\')).count { !RemoteBrowser.skip(it.fileName) }
    }

    override suspend fun readPrefix(source: SourceEntity, relativePath: String, length: Int): ByteArray = withSession(source) { share ->
        val path = listOf(source.path.trim('/'), relativePath).filter { it.isNotEmpty() }.joinToString("\\") { it.replace('/', '\\') }
        share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { file -> file.inputStream.use { it.readUpTo(length) } }
    }

    private suspend fun <T> withSession(source: SourceEntity, block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        client.connect(source.host).use { connection ->
            val auth = if (source.username.isBlank()) AuthenticationContext.guest()
            else AuthenticationContext(source.username, source.password.value.toCharArray(), source.domain.ifBlank { null })
            val session = connection.authenticate(auth)
            (session.connectShare(source.share) as DiskShare).use(block)
        }
    }

    private fun walk(
        share: DiskShare,
        root: String,
        relative: String,
        depth: Int,
        out: MutableList<RemoteFile>,
        sidecars: MutableList<RemoteFile>,
    ) {
        if (depth > RemoteBrowser.MAX_DEPTH) return
        val dir = listOf(root, relative).filter { it.isNotEmpty() }.joinToString("/")
        for (entry in share.list(dir.replace('/', '\\'))) {
            val name = entry.fileName
            if (RemoteBrowser.skip(name)) continue
            val rel = if (relative.isEmpty()) name else "$relative/$name"
            val isDir = entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            when {
                isDir -> walk(share, root, rel, depth + 1, out, sidecars)
                RemoteBrowser.isWanted(name) -> out += RemoteFile(rel, entry.endOfFile)
                RemoteBrowser.isSidecar(name) -> sidecars += RemoteFile(rel, entry.endOfFile)
            }
        }
    }
}
