package app.houselights.ui.onboarding

import app.houselights.providers.local.DiskAccess
import app.houselights.providers.local.ExportedFolder
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.FoundNas
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SharedFolder
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.Volume
import app.houselights.testing.mountFailure
import com.emc.ecs.nfsclient.mount.MountStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The board's network (« Ajouter un dossier », its fixture): DS920 at 192.168.1.20 answering SMB and NFS, its exports
 * /volume1/video and /volume1/musique open to the home network, /volume1/photo to 192.168.1.10 alone, /volume1/media;
 * its shares video, photo, music and home for the account houselights, and video$, which it never lists; the player at
 * 192.168.1.42, its own storage and a USB disk « Samsung T7 » once a test plugs them in ([mounted]), read as [access]
 * says. A NAS is announced when a test says so ([announce]), a search ending when it says so too ([searchEnds]); a
 * call can be held until a test lets it go ([hold]).
 */
class FakeFlowNetwork : FlowNetwork {
    /** The search under way, or the next one. */
    private var announced = Channel<FoundNas>(Channel.UNLIMITED)

    /** Hosts whose ports answer, and with what; any other is silent. */
    val answering = mutableMapOf(DS920 to setOf(Protocol.SMB, Protocol.NFS))

    /** The exports of DS920; null when it lists none (NFSv4 alone). */
    var exports: List<ExportedFolder>? = listOf(
        ExportedFolder("/volume1/video", listOf("192.168.1.0/24")),
        ExportedFolder("/volume1/photo", listOf("192.168.1.10")),
        ExportedFolder("/volume1/musique", listOf("192.168.1.0/24")),
        ExportedFolder("/volume1/media", listOf("192.168.1.0/24")),
    )

    /** Each folder by its path from the NAS's root (an export's or a share's name first), with what it holds. */
    val folders = mutableMapOf(
        "volume1/video" to FolderLevel(listOf("Archives 2019", "Films", "Films2", "Séries", "Spectacles"), 0),
        "volume1/video/Films" to FolderLevel(listOf("Heat (1995)"), 1),
        "volume1/video/Films2" to FolderLevel((1..FILMS2).map { "Film " + it.toString().padStart(2, '0') }, 2),
        "volume1/video/Séries" to FolderLevel(listOf("One Piece (1999)", "Severance (2022)"), 0),
        "volume1/video/Spectacles" to FolderLevel(emptyList(), 3),
        "volume1/video/Archives 2019" to FolderLevel(emptyList(), 0),
        "volume1/media" to FolderLevel(listOf("Films", "Séries"), 0),
        "volume1/media/Films" to FolderLevel(emptyList(), 4),
        "volume1/media/Séries" to FolderLevel(emptyList(), 0),
        "volume1/musique" to FolderLevel(emptyList(), 0),
        "video" to FolderLevel(listOf("Films", "Films2", "Séries"), 0),
        "video/Films" to FolderLevel(listOf("Heat (1995)"), 1),
        "video/Films2" to FolderLevel(emptyList(), 2),
        "video/Séries" to FolderLevel(emptyList(), 0),
        "photo" to FolderLevel(emptyList(), 0),
        "music" to FolderLevel(emptyList(), 0),
        "home" to FolderLevel(emptyList(), 0),
        "video$" to FolderLevel(listOf("Films"), 0),
        "video$/Films" to FolderLevel(emptyList(), 2),
        // The player's disks, by their id.
        Volume.INTERNAL to FolderLevel(listOf("Download", "Movies"), 0),
        T7 to FolderLevel(listOf("Films", "Séries"), 0),
        "$T7/Films" to FolderLevel(listOf("Alien (1979)"), 1),
    )

    /** The player's own disks mounted: none, unless a test plugs them in ([DISKS]). */
    var mounted = emptyList<Volume>()

    /** Whether the app may read them; one asked and refused for good, only the settings grant it. */
    var access = DiskAccess.GRANTED

    /** The SMB shares a guest may open, the account opening every share. */
    var guestShares = setOf<String>()

    /** Whether the NAS lets guests sign in at all. */
    var guests = true

    /** The folders, by their path from the NAS's root, that its NFS server lets no one but root read. */
    var rootOnly = setOf<String>()

    /** What each call asked, in order: "shares houselights", "level volume1/video". */
    val asked = mutableListOf<String>()

    /** The next call named by [hold]'s key waits for it to complete. */
    private val holds = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Holds the next call [what] ("protocols", "exports", "shares", "level"), until the returned gate is completed. */
    fun hold(what: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { holds[what] = it }

    fun announce(nas: FoundNas) {
        announced.trySend(nas)
    }

    /** The search ends: no other NAS answers; the next search hears those announced after it. */
    fun searchEnds() {
        announced.close()
        announced = Channel(Channel.UNLIMITED)
    }

    private suspend fun call(what: String, detail: String) {
        asked += "$what $detail".trim()
        holds.remove(what)?.await()
    }

    override fun discover(): Flow<FoundNas> = announced.receiveAsFlow()

    override suspend fun protocols(host: String): Set<Protocol> {
        call("protocols", host)
        return answering[host].orEmpty()
    }

    override suspend fun shares(source: SourceEntity): List<SharedFolder> {
        call("shares", source.username)
        if (source.host != DS920) throw IOException("timed out")
        val guest = source.username.isBlank()
        if (guest && !guests) throw IOException("STATUS_LOGON_FAILURE")
        if (!guest && (source.username != USER || source.password.value != PASSWORD)) throw IOException("STATUS_LOGON_FAILURE")
        return listOf(SharedFolder("home"), SharedFolder("music"), SharedFolder("photo", "Photos de famille"), SharedFolder("video"))
    }

    override suspend fun exports(host: String): List<ExportedFolder> {
        call("exports", host)
        return exports ?: throw IOException("Program 100005 is not registered")
    }

    override suspend fun level(source: SourceEntity, path: String): FolderLevel {
        val root = if (source.protocol == Protocol.LOCAL) source.host else source.share.trim('/')
        val at = listOf(root, source.path, path).filter { it.isNotEmpty() }.joinToString("/")
        call("level", at)
        refusal(source, at)?.let { throw it }
        val key = if (source.protocol == Protocol.SMB) folders.keys.firstOrNull { it.equals(at, ignoreCase = true) } else at
        return folders[key] ?: throw FileNotFoundException(at)
    }

    /** What the NAS answers [source] reading [at] when it refuses it: NFS's mount or read, SMB's guest or account. */
    private fun refusal(source: SourceEntity, at: String): Exception? {
        val nfs = source.protocol == Protocol.NFS
        val smb = source.protocol == Protocol.SMB
        val guest = source.username.isBlank()
        val signedIn = source.username == USER && source.password.value == PASSWORD
        return when {
            nfs && source.share == "volume1/photo" -> mountFailure(MountStatus.MNT3ERR_ACCES)
            nfs && source.uid != null && rootOnly.any { at.startsWith(it) } -> IOException("NFS3ERR_ACCES")
            smb && guest && source.share.lowercase() !in guestShares -> IOException("STATUS_ACCESS_DENIED")
            smb && !guest && !signedIn -> IOException("STATUS_LOGON_FAILURE")
            else -> null
        }
    }

    override fun player(): Inet4Address = InetAddress.getByName(PLAYER) as Inet4Address

    override fun disks(): List<Volume> = mounted

    override fun diskAccess(refusedForGood: Boolean): DiskAccess =
        if (access == DiskAccess.TO_ASK && refusedForGood) DiskAccess.IN_SETTINGS else access

    companion object {
        const val DS920 = "192.168.1.20"
        const val SILENT_NAS = "192.168.1.30"
        const val PLAYER = "192.168.1.42"
        const val USER = "houselights"
        const val PASSWORD = "secret"

        /** The USB disk's id, its filesystem's UUID. */
        const val T7 = "1234-ABCD"

        /** The player's own storage and the USB disk « Samsung T7 ». */
        val DISKS = listOf(
            Volume(Volume.INTERNAL, "Internal shared storage", internal = true, File("/storage/emulated/0")),
            Volume(T7, "Samsung T7", internal = false, File("/storage/$T7")),
        )

        /** Films2's folders, one level. */
        const val FILMS2 = 38
    }
}
