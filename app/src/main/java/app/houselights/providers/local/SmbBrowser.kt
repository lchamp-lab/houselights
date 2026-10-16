package app.houselights.providers.local

import android.os.SystemClock
import android.util.Log
import app.houselights.core.suspendRunCatching
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Lists video files of an SMB folder; made once, by [RemoteBrowsers]. */
internal class SmbBrowser(
    /** Signs in to a folder's share; tests hand in a NAS of their own. */
    private val connect: (SourceEntity) -> SmbShare = { SmbjShare.open(it) },
    clock: () -> Long = SystemClock::elapsedRealtime,
) : RemoteBrowser {
    private val sessions = KeptConnections<SmbShare>(
        close = { share, broken -> share.close(broken) },
        isOpen = { it.isOpen },
        isBroken = ::isBroken,
        clock = clock,
    )

    override suspend fun list(source: SourceEntity): Listing = withSession(source) { share ->
        walk(folder(share, root(source).replace('/', '\\'))) { ScanErrors.isUnreachable(it) || isBroken(it) }
    }

    /** Signs in of its own, with what the form says: a session kept, signed in with the password stored, would let a wrong one through. */
    override suspend fun test(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        sessions.once({ connect(source) }) { share ->
            share.list(root(source).replace('/', '\\')).count { !RemoteBrowser.skip(it.name) }
        }
    }

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray =
        withSession(source) { share ->
            val path = listOf(root(source), relativePath).filter { it.isNotEmpty() }.joinToString("\\") { it.replace('/', '\\') }
            share.read(path, offset, length)
        }

    private fun root(source: SourceEntity) = source.path.trim('/')

    /** Who signs in where: the folders of one share and account share a session. */
    private fun key(source: SourceEntity) = "${source.username}@${source.domain}/${source.host}/${source.share}"

    private suspend fun <T> withSession(source: SourceEntity, block: (SmbShare) -> T): T = withContext(Dispatchers.IO) {
        sessions.use(key(source), { connect(source) }, block)
    }

    /**
     * Closes the sessions kept open on [source]'s share, whoever signed in: the folder's address or
     * password changed, and a session opened before must not stand for the new one.
     */
    fun forget(source: SourceEntity) {
        val share = "/${source.host}/${source.share}"
        sessions.forget { it.endsWith(share) }
    }

    /** Closes the sessions no operation uses; called when a scan ends. */
    fun closeIdle() = sessions.closeIdle()

    /** The folder at [path] in the share, "\" separators; a listing gives each entry, nothing more to ask. */
    private fun folder(share: SmbShare, path: String): RemoteFolder = RemoteFolder {
        share.list(path).map { entry ->
            object : RemoteEntry {
                override val name = entry.name

                override fun folder() = if (entry.isFolder) folder(share, if (path.isEmpty()) name else "$path\\$name") else null

                override fun size() = entry.size
            }
        }
    }

    companion object {
        /**
         * A failure of the session or its connection, after which it is made again: not one of the file.
         * smbj gives what the NAS answered as SMBApiException, a file refused, gone or locked among them;
         * it is also an SMBRuntimeException, as are the failures of the connection.
         */
        internal fun isBroken(e: Throwable): Boolean {
            val answer = generateSequence(e) { it.cause }.filterIsInstance<SMBApiException>().firstOrNull()
            return if (answer != null) answer.status in SESSION_GONE else e is SMBRuntimeException || e is IOException
        }

        /** Answers that end the session, not the request. */
        private val SESSION_GONE = setOf(
            NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            NtStatus.STATUS_USER_SESSION_DELETED,
            NtStatus.STATUS_NETWORK_NAME_DELETED,
        )
    }
}

/** An entry of a share's folder. */
internal class SmbEntry(val name: String, val isFolder: Boolean, val size: Long)

/** A share signed in to, as [SmbBrowser] uses it: [SmbjShare] on a NAS, a fake in tests. */
internal interface SmbShare {
    /** The entries of a folder, by its path in the share with "\" separators, "" for the share itself. */
    fun list(path: String): List<SmbEntry>

    /** [length] bytes of a file from [offset], fewer past its end. */
    fun read(path: String, offset: Long, length: Int): ByteArray

    /** False once the NAS hung up. */
    val isOpen: Boolean

    /** Signs off, then hangs up; [broken]: only hangs up, as the NAS would not answer. Never throws. */
    fun close(broken: Boolean)
}

/**
 * A share signed in to with smbj, on a client of its own. smbj hands out the connection open to a
 * host to whoever connects to it again, one lease each, and only hangs up on the last lease: two
 * shares of one NAS on one client would share a connection that neither could close.
 */
private class SmbjShare(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : SmbShare {
    override fun list(path: String): List<SmbEntry> = share.list(path).map {
        SmbEntry(it.fileName, it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L, it.endOfFile)
    }

    override fun read(path: String, offset: Long, length: Int): ByteArray = share.openFile(
        path,
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null,
    ).use { file ->
        val bytes = ByteArray(length)
        var total = 0
        while (total < length) {
            val n = file.read(bytes, offset + total, total, length - total)
            if (n <= 0) break
            total += n
        }
        if (total == length) bytes else bytes.copyOf(total)
    }

    override val isOpen: Boolean get() = connection.isConnected && share.isConnected

    /** Share, session, then connection: the NAS forgets each in turn, rather than holding a session for good. */
    override fun close(broken: Boolean) {
        if (!broken) {
            quietly("share") { share.close() }
            quietly("session") { session.close() }
        }
        // Its only lease: this hangs up. Forced, it does not sign off sessions first.
        quietly("connection") { connection.close(broken) }
        client.close()
    }

    private inline fun quietly(what: String, close: () -> Unit) {
        suspendRunCatching(close).onFailure { Log.d(TAG, "Could not close the $what", it) }
    }

    companion object {
        private const val TAG = "SmbBrowser"

        /**
         * A request waits this long for its answer. Nothing more: smbj's socket timeout would also stop
         * reading a connection left idle that long, leaving it open, unread; idle sessions are closed
         * by [KeptConnections] instead.
         */
        private val CONFIG = SmbConfig.builder().withTimeout(15, TimeUnit.SECONDS).build()

        fun open(source: SourceEntity): SmbShare {
            val client = SMBClient(CONFIG)
            var opened: SmbShare? = null
            try {
                val connection = client.connect(source.host)
                val auth = if (source.username.isBlank()) {
                    AuthenticationContext.guest()
                } else {
                    AuthenticationContext(source.username, source.password.value.toCharArray(), source.domain.ifBlank { null })
                }
                val session = connection.authenticate(auth)
                val share = session.connectShare(source.share) as DiskShare
                return SmbjShare(client, connection, session, share).also { opened = it }
            } finally {
                // Refused or unreachable: nothing stays open.
                if (opened == null) client.close()
            }
        }
    }
}
