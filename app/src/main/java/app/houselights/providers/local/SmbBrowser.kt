package app.houselights.providers.local

import android.os.SystemClock
import android.util.Log
import app.houselights.core.suspendRunCatching
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Lists and reads SMB shares; a single instance lives in [RemoteBrowsers]. */
internal class SmbBrowser(
    /** Signs in to a source's share; replaceable in tests. */
    private val connect: (SourceEntity) -> SmbShare = { SmbjShare.open(it) },
    clock: () -> Long = SystemClock::elapsedRealtime,
    /** Lists a NAS's shares, signed in as a source is (requests to the NAS); replaceable in tests. */
    private val listShares: (SourceEntity) -> List<SharedFolder> = { SmbjShare.shares(it) },
) : RemoteBrowser {
    private val sessions = KeptConnections<SmbShare>(
        close = { share, broken -> share.close(broken) },
        isOpen = { it.isOpen },
        isBroken = ::isBroken,
        clock = clock,
    )

    private val unreadable = UnreadablePaths()

    /**
     * A folder a request, each answer holding its entries' details: the count moves a folder at a time. On a session of
     * its own, signed off once listed: a walk of thousands of folders would otherwise hold the share's kept session, and
     * the add-folder flow's levels and a folder's save ([forget]) on the same share would wait for its end.
     */
    override suspend fun list(source: SourceEntity, pauses: () -> Boolean, found: (Int) -> Unit): Listing = withContext(Dispatchers.IO) {
        sessions.once({ connect(source) }) { share ->
            val root = folder(share, remotePath(source, separator = '\\'))
            walk(root, unreadable.of(source), found, pauses) { ScanErrors.isUnreachable(it) || isBroken(it) }
        }
    }

    /** Uses a fresh session with the form's credentials, as a kept session would accept a wrong password. */
    override suspend fun test(source: SourceEntity): FolderLevel = withContext(Dispatchers.IO) {
        sessions.once({ connect(source) }) { share ->
            level(folder(share, remotePath(source, separator = '\\'))) { ScanErrors.isUnreachable(it) || isBroken(it) }
        }
    }

    /**
     * On the share's kept session, as a scan's reads (not its listing), so that going down a level costs no sign-in: the add-folder flow
     * tests the account by listing the NAS's shares ([shares]), on a connection of its own, before any level.
     */
    override suspend fun folders(source: SourceEntity, path: String): FolderLevel = withSession(source) { share ->
        level(folder(share, remotePath(source, cleanPath(path), '\\'))) { ScanErrors.isUnreachable(it) || isBroken(it) }
    }

    override suspend fun readAt(source: SourceEntity, relativePath: String, offset: Long, length: Int): ByteArray =
        withSession(source) { share -> share.read(remotePath(source, relativePath, '\\'), offset, length) }

    /** Sources on the same share and account share a session. */
    private fun key(source: SourceEntity) = "${source.username}@${source.domain}/${source.host}/${source.share}"

    private suspend fun <T> withSession(source: SourceEntity, block: (SmbShare) -> T): T = withContext(Dispatchers.IO) {
        sessions.use(key(source), { connect(source) }, block)
    }

    /**
     * The disk shares of [source]'s NAS ([SmbShares]), its share and folder aside, signed in as it is (a guest without
     * a user name) on a connection of its own, as [test]; throws as signing in or a share refused does.
     */
    suspend fun shares(source: SourceEntity): List<SharedFolder> = withContext(Dispatchers.IO) { listShares(source) }

    /** Closes sessions kept on [source]'s share, for any account, after its address or password changed. */
    suspend fun forget(source: SourceEntity) {
        val share = "/${source.host}/${source.share}"
        sessions.forget { it.endsWith(share) }
    }

    /** Closes unused sessions; called when a scan ends. */
    fun closeIdle() = sessions.closeIdle()

    /** The folder at [path] ("\" separators); the listing has all entry details, so no extra requests. */
    private fun folder(share: SmbShare, path: String): RemoteFolder = RemoteFolder {
        share.list(path).map { entry ->
            object : RemoteEntry {
                override val name = entry.name

                override fun folder() = if (entry.isFolder) folder(share, if (path.isEmpty()) name else "$path\\$name") else null

                override fun size() = entry.size

                override fun lastModified() = entry.lastModified
            }
        }
    }

    companion object {
        /**
         * A session or connection failure (not a file failure), after which the session is recreated. NAS answers
         * arrive as SMBApiException, itself an SMBRuntimeException like connection failures, so it is checked first.
         */
        internal fun isBroken(e: Throwable): Boolean {
            val answer = generateSequence(e) { it.cause }.filterIsInstance<SMBApiException>().firstOrNull()
            return if (answer != null) answer.status in SESSION_GONE else e is SMBRuntimeException || e is IOException
        }

        /** Statuses that end the session, not just the request. */
        private val SESSION_GONE = setOf(
            NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            NtStatus.STATUS_USER_SESSION_DELETED,
            NtStatus.STATUS_NETWORK_NAME_DELETED,
        )

        private const val TAG = "SmbBrowser"

        /**
         * Runs the named sign-off [steps] (share, then session) unless [broken], then [hangUp]; never throws. A step
         * failing without an NT status (timeout, lost connection) means the NAS is gone: the remaining steps would
         * each time out too, so they are skipped and [hangUp] is forced.
         */
        internal fun signOff(broken: Boolean, steps: List<Pair<String, () -> Unit>>, hangUp: (forced: Boolean) -> Unit) {
            var forced = broken
            for ((what, step) in steps) {
                if (forced) break
                suspendRunCatching(step).onFailure { e ->
                    Log.d(TAG, "Could not close the $what", e)
                    forced = generateSequence(e) { it.cause }.none { it is SMBApiException }
                }
            }
            suspendRunCatching { hangUp(forced) }.onFailure { Log.d(TAG, "Could not close the connection", it) }
        }
    }
}

/** A share folder entry; [lastModified] is the last write time in epoch ms. */
internal class SmbEntry(val name: String, val isFolder: Boolean, val size: Long, val lastModified: Long? = null)

/** A connected share as [SmbBrowser] uses it: [SmbjShare] on a NAS, a fake in tests. */
internal interface SmbShare {
    /** Entries of the folder at [path] ("\" separators, "" for the share root). */
    fun list(path: String): List<SmbEntry>

    fun read(path: String, offset: Long, length: Int): ByteArray

    /** False once the NAS hung up. */
    val isOpen: Boolean

    /** Signs off and hangs up, or only hangs up when [broken]; never throws. */
    fun close(broken: Boolean)
}

/**
 * An smbj share on its own SMBClient: smbj reuses one leased connection per host within a client and hangs up only
 * on the last lease, so two shares of one NAS on one client would share a connection neither could close.
 */
private class SmbjShare(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : SmbShare {
    /** FileIdBothDirectoryInformation already includes size and last write time. */
    override fun list(path: String): List<SmbEntry> = share.list(path).map {
        SmbEntry(
            it.fileName,
            it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
            it.endOfFile,
            it.lastWriteTime?.toEpochMillis(),
        )
    }

    override fun read(path: String, offset: Long, length: Int): ByteArray = share.openFile(
        path,
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null,
    ).use { file -> readFully(length) { bytes, at, count -> file.read(bytes, offset + at, at, count) } }

    override val isOpen: Boolean get() = connection.isConnected && share.isConnected

    /** Closes share, session, then connection, so the NAS does not keep the session. */
    override fun close(broken: Boolean) {
        SmbBrowser.signOff(broken, listOf("share" to { share.close() }, "session" to { session.close() })) { forced ->
            // The only lease, so this hangs up; forced, it skips signing off sessions.
            connection.close(forced)
        }
        client.close()
    }

    companion object {
        /**
         * Request timeout only: smbj's socket timeout would also stop reading an idle connection while leaving it
         * open; [KeptConnections] closes idle sessions instead.
         */
        private val CONFIG = SmbConfig.builder().withTimeout(15, TimeUnit.SECONDS).build()

        /** How [source] signs in: as a guest without a user name. A password not opened for now throws, before connecting. */
        private fun authentication(source: SourceEntity) = if (source.username.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(source.username, source.password.forSignIn().toCharArray(), source.domain.ifBlank { null })
        }

        fun open(source: SourceEntity): SmbShare {
            val auth = authentication(source)
            val client = SMBClient(CONFIG)
            var opened: SmbShare? = null
            try {
                val connection = client.connect(source.host)
                val session = connection.authenticate(auth)
                val share = session.connectShare(source.share) as DiskShare
                return SmbjShare(client, connection, session, share).also { opened = it }
            } finally {
                // On failure, close the client so nothing stays open.
                if (opened == null) client.close()
            }
        }

        /**
         * [source]'s NAS's shares through srvsvc's pipe on IPC$, on a client of its own: signed off once listed, as a
         * share is, unless the connection broke; the client closed whatever happened.
         */
        fun shares(source: SourceEntity): List<SharedFolder> {
            val auth = authentication(source)
            val client = SMBClient(CONFIG)
            try {
                val connection = client.connect(source.host)
                val session = connection.authenticate(auth)
                val pipes = session.connectShare(IPC) as PipeShare
                val listed = suspendRunCatching { pipes.openServerService().use { SmbShares.list(SmbjPipe(it), source.host) } }
                val broken = listed.exceptionOrNull()?.let { SmbBrowser.isBroken(it) } == true
                SmbBrowser.signOff(broken, listOf("share" to { pipes.close() }, "session" to { session.close() })) { forced ->
                    connection.close(forced)
                }
                return listed.getOrThrow()
            } finally {
                client.close()
            }
        }

        /** srvsvc's pipe, opened as Windows opens it to list shares. */
        private fun PipeShare.openServerService(): NamedPipe = open(
            SRVSVC,
            SMB2ImpersonationLevel.Impersonation,
            EnumSet.of(AccessMask.MAXIMUM_ALLOWED),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
            SMB2CreateDisposition.FILE_OPEN_IF,
            null,
        )

        private const val IPC = "IPC$"
        private const val SRVSVC = "srvsvc"
    }
}

/** smbj's named pipe as [SmbShares] talks through it. */
private class SmbjPipe(private val pipe: NamedPipe) : RpcPipe {
    override fun transact(request: ByteArray): ByteArray = pipe.transact(request)

    override fun read(): ByteArray {
        val bytes = ByteArray(READ_BYTES)
        return bytes.copyOf(pipe.read(bytes).coerceAtLeast(0))
    }

    private companion object {
        /** More than a fragment, which the bind keeps to 4280 bytes. */
        const val READ_BYTES = 65_536
    }
}
