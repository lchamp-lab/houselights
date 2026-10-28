package app.houselights.ui.onboarding

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.houselights.AppContainer
import app.houselights.R
import app.houselights.core.FailureKind
import app.houselights.core.failureKind
import app.houselights.core.isMountRefused
import app.houselights.journal.HomeAddress
import app.houselights.providers.local.DiskAccess
import app.houselights.providers.local.ExportedFolder
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.FoundNas
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SharedFolder
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.Standing
import app.houselights.providers.local.TypedPaths
import app.houselights.providers.local.UsedNas
import app.houselights.providers.local.Volume
import kotlinx.coroutines.flow.Flow
import java.net.Inet4Address
import java.text.Normalizer
import java.util.Locale

/** A step of « Ajouter un dossier » (DESIGN.md, Secondary screens), kept by its name across a stopped process. */
enum class FlowStep {
    /** Where the videos are: the NAS already used, those found on the network, an address to type. */
    WHERE,

    /** « Saisir une adresse ». */
    ADDRESS,

    /** SMB or NFS, for a NAS never used that answers both. */
    PROTOCOLS,

    /** The SMB account. */
    ACCOUNT,

    /** The shared folders: SMB shares or NFS exports. */
    SHARES,

    /** « Saisir un chemin », in the shared folders' place when the NAS lists none ([FolderFlow.noList]). */
    TYPE_PATH,

    /** « Compte NFS ». */
    NFS_ACCOUNT,

    /** A level of the shared folder, [FolderFlow.path] under its root. */
    BROWSE,

    /** What the folder chosen holds. */
    KIND,
}

/** The NAS chosen: the [host] its folder will keep, the [name] it announces on the network, if any. */
@Immutable
data class FlowNas(val host: String, val name: String? = null)

/**
 * A shared folder of the NAS chosen: an SMB share by its name, an NFS export by its [path] from the server's root
 * ("/volume1/video"), [refused] when its clients leave this player out ([exportAccess]).
 */
@Immutable
data class FlowShare(val path: String, val remark: String = "", val refused: Boolean = false) {
    /** "video", for an SMB share as for the export "/volume1/video"; "/" for an export of the server's root. */
    val name: String get() = path.trim('/').substringAfterLast('/').ifEmpty { "/" }
}

/** What a NAS's ports answered: still asked, nothing, or the protocols that answer. */
sealed interface NasAnswer {
    data object Checking : NasAnswer

    data object Silent : NasAnswer

    data class Answers(val protocols: Set<Protocol>) : NasAnswer
}

/** Where focus goes next, by the key of what takes it; a new one each time, so the same row can be asked again. */
@Immutable
data class Aim(val key: String, val serial: Int)

/**
 * The row or button under way ([key]) and what it says meanwhile: « Connexion… », « Ouverture… », « Ajout… »,
 * « Enregistrement… ».
 */
@Immutable
data class Pressed(val key: String, @StringRes val words: Int) {
    /** The folder is being written to the library, added or edited: nothing interrupts it. */
    val writes: Boolean get() = words == R.string.adding || words == R.string.saving
}

/** What a row says of its last failure, in two words, by the ring. */
enum class RowFailure(@StringRes val words: Int) {
    SILENT(R.string.server_silent),
    NOT_ALLOWED(R.string.not_allowed),
    REFUSED(R.string.access_refused),
    FAILED(R.string.failed_short),
}

/** A failure's sentence: in the panel for a row, under its field for a typing step. */
sealed interface FlowMessage {
    data class Silent(val host: String) : FlowMessage

    data object LoginRefused : FlowMessage

    data object GuestRefused : FlowMessage

    /**
     * The password of the account kept, which the device's Keystore did not open for now: kept, never tried, the NAS not
     * asked, so never a refusal; typed again, or tried again once the Keystore answers.
     */
    data object PasswordUnread : FlowMessage

    /** NFS: the NAS refuses this player ([player], its address, null when unknown) on [export]. */
    data class NotAllowed(val player: String?, val export: String) : FlowMessage

    /** NFS: mounted, but root may not read [path]. */
    data class RootRefused(val path: String) : FlowMessage

    /** NFS: mounted, but the user [uid] « Compte NFS » presents may not read [path]. */
    data class AccountRefused(val uid: String, val path: String) : FlowMessage

    /** SMB: [user] may not open [share]. */
    data class Denied(val user: String, val share: String) : FlowMessage

    /** SMB: [share] is not open to guests. */
    data class GuestShare(val share: String) : FlowMessage

    data class NoShare(val host: String, val share: String) : FlowMessage

    data class NoExport(val typed: String) : FlowMessage

    /** The folder [at] answers but holds no [name]; [smb] says [at] as a share's, in quotes. */
    data class NoFolder(val at: String, val smb: Boolean, val name: String) : FlowMessage

    /** The folder chosen was added meanwhile, or one it overlaps: [existing], which it is, holds or is inside ([standing]). */
    data class Overlaps(val existing: String, val standing: Standing) : FlowMessage

    data class Missing(@StringRes val words: Int) : FlowMessage

    /** The app may not read this player's disks: what to allow, in Android's [settings] or in its window. */
    data class DiskRefused(val settings: Boolean) : FlowMessage

    /** The player's [disk] is not mounted: unplugged since, or not plugged back in yet. */
    data class DiskAbsent(val disk: FlowNas) : FlowMessage

    /** The folder edited is no longer in the library: removed meanwhile. */
    data object Gone : FlowMessage

    data object Other : FlowMessage
}

/** Why « Modifier le dossier » has no folder to open: the player's storage did not give it, or the library no longer has it. */
enum class EditLost { UNREAD, GONE }

/** A failure said under the field [field] (its key), which takes focus. */
@Immutable
data class FieldError(val field: String, val message: FlowMessage)

/** How the flow ended: the folder [folderId] was added or edited, and [switched] whether the source became the folders. */
data class FlowDone(val switched: Boolean, val folderId: Long?)

/** What failed, as the flow tells it apart ([flowFailure]); [PASSWORD_UNREAD], the account's password kept but not read for now. */
enum class FlowFailure { SILENT, LOGIN_REFUSED, PASSWORD_UNREAD, MOUNT_REFUSED, READ_REFUSED, MISSING, OTHER }

/** A failure the flow tells itself, before asking the network more: the NAS answered on no port. */
class FlowRefusal(val failure: FlowFailure) : Exception(failure.name)

/**
 * What failed, from its cause chain: a refused mount (MNT3ERR_ACCES) told from a refused read (NFS3ERR_ACCES), a password
 * the NAS refused from one the Keystore did not open for now, which the NAS was never asked.
 */
fun Throwable.flowFailure(): FlowFailure {
    if (this is FlowRefusal) return failure
    return when (failureKind()) {
        FailureKind.UNREACHABLE -> FlowFailure.SILENT
        FailureKind.LOGIN_FAILED -> FlowFailure.LOGIN_REFUSED
        FailureKind.SECRET_UNREAD -> FlowFailure.PASSWORD_UNREAD
        FailureKind.ACCESS_DENIED -> if (isMountRefused()) FlowFailure.MOUNT_REFUSED else FlowFailure.READ_REFUSED
        FailureKind.MISSING -> FlowFailure.MISSING
        else -> FlowFailure.OTHER
    }
}

/** What « Ajouter un dossier » asks of the network: the player's ([AppFlowNetwork]), or a test's. */
interface FlowNetwork {
    /** The NAS announcing themselves, each as it answers, then again with its protocols ([NasDiscovery.discover]). */
    fun discover(): Flow<FoundNas>

    /** Which of [host]'s protocols answer, by their ports; none when it is silent. */
    suspend fun protocols(host: String): Set<Protocol>

    /** The disk shares of [source]'s NAS, signed in as it is: listing them is the account's test. */
    suspend fun shares(source: SourceEntity): List<SharedFolder>

    /** The exports of the NFS server at [host], with the clients each allows. */
    suspend fun exports(host: String): List<ExportedFolder>

    /** One level of [source]'s folder, [path] under it. */
    suspend fun level(source: SourceEntity, path: String): FolderLevel

    /** This player's address on the home network, which an NFS export must allow; null when unknown. */
    fun player(): Inet4Address?

    /** The player's own disks mounted now, its storage first ([app.houselights.providers.local.Volumes]); none by default. */
    fun disks(): List<Volume> = emptyList()

    /**
     * Whether the app may read those disks ([app.houselights.providers.local.DiskPermission]); [refusedForGood] once
     * Android's window was refused for good.
     */
    fun diskAccess(refusedForGood: Boolean = false): DiskAccess = DiskAccess.GRANTED
}

/** The network on a player: [AppContainer.nasDiscovery], the NAS browsers, the player's own address. */
internal class AppFlowNetwork(private val app: AppContainer, private val context: Context) : FlowNetwork {
    override fun discover(): Flow<FoundNas> = app.nasDiscovery.discover()

    override suspend fun protocols(host: String): Set<Protocol> = app.nasDiscovery.protocols(host)

    override suspend fun shares(source: SourceEntity): List<SharedFolder> = app.remoteBrowsers.shares(source)

    override suspend fun exports(host: String): List<ExportedFolder> = app.remoteBrowsers.exports(host)

    override suspend fun level(source: SourceEntity, path: String): FolderLevel = app.remoteBrowsers.of(source).folders(source, path)

    override fun player(): Inet4Address? = HomeAddress.find(context)

    override fun disks(): List<Volume> = app.volumes.mounted().sortedByDescending { it.internal }

    override fun diskAccess(refusedForGood: Boolean): DiskAccess = app.diskPermission.access(refusedForGood)
}

/** Whether [names] hold a folder named for each kind: « Contient Films et Séries ». */
internal fun holdsBoth(names: List<String>): Boolean {
    val kinds = names.mapNotNull(::kindNamed).toSet()
    return SourceKind.MOVIES in kinds && SourceKind.SHOWS in kinds
}

/**
 * The kind a folder's name says: one that is or starts with films, film or movies, séries, series, serie, tv or shows,
 * case and accents aside; null for any other.
 */
internal fun kindNamed(name: String): SourceKind? {
    val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)
    return when {
        MOVIE_NAMES.any { plain.startsWith(it) } -> SourceKind.MOVIES
        SHOW_NAMES.any { plain.startsWith(it) } -> SourceKind.SHOWS
        else -> null
    }
}

private val MARKS = Regex("\\p{M}+")
private val MOVIE_NAMES = listOf("films", "film", "movies")
private val SHOW_NAMES = listOf("series", "serie", "tv", "shows")

/** The keys focus is asked by in « Ajouter un dossier » ([Aim]), each row's or field's. */
object FlowKeys {
    const val NET_FIRST = "net:first"

    /** A place in the network's group, the row in it whichever NAS or search it shows: "net:2". */
    const val NET_SLOT = "net:"
    const val SEARCH = "net:search"
    const val TYPE_ADDRESS = "type-address"
    const val TYPE_PATH = "type-path"
    const val CHANGE_ACCOUNT = "change-account"
    const val NFS_ACCOUNT = "nfs-account"
    const val CONTINUE = "continue"
    const val SIGN_IN = "sign-in"
    const val GUEST = "guest"
    const val SUGGESTION = "suggestion"
    const val CHOOSE = "choose"
    const val FIELD_HOST = "field:host"
    const val FIELD_USER = "field:user"
    const val FIELD_PASSWORD = "field:password"
    const val FIELD_PATH = "field:path"
    const val FIELD_UID = "field:uid"
    const val FIELD_GID = "field:gid"
    const val NAS = "nas:"
    const val SHARE = "share:"
    const val FOLDER = "folder:"
    const val DISK = "disk:"

    fun nasKey(host: String) = "$NAS$host"
    fun diskKey(id: String) = "$DISK$id"
    fun protocolKey(protocol: Protocol) = "protocol:${protocol.name}"
    fun shareKey(share: FlowShare) = "$SHARE${share.path}"
    fun folderKey(name: String) = "$FOLDER$name"
    fun kindKey(kind: SourceKind) = "kind:${kind.name}"
}

/** Whether [found] is [nas]: the same address, or a name it announces that names its host ([TypedPaths.namesNas]). */
internal fun matches(nas: UsedNas, found: FoundNas): Boolean =
    nas.host.trim().equals(found.address, ignoreCase = true) || found.name.isNotBlank() && TypedPaths.namesNas(found.name, nas.host.trim())

/**
 * Who asked the NAS what failed: its [host], over NFS or SMB, as a [guest] or as [user], from this [player]; over NFS,
 * as the user [uid] « Compte NFS » presents, null for root.
 */
internal class Asking(
    val host: String,
    val nfs: Boolean,
    val guest: Boolean,
    val user: String,
    val player: String?,
    val uid: String? = null,
)

/** A row's two words for this failure. */
internal fun FlowFailure.words(): RowFailure = when (this) {
    FlowFailure.SILENT -> RowFailure.SILENT
    FlowFailure.MOUNT_REFUSED -> RowFailure.NOT_ALLOWED
    FlowFailure.READ_REFUSED, FlowFailure.LOGIN_REFUSED -> RowFailure.REFUSED
    FlowFailure.PASSWORD_UNREAD, FlowFailure.MISSING, FlowFailure.OTHER -> RowFailure.FAILED
}

/**
 * The sentence for [failure] on the shared folder or folder [name], at [where] (a path from the NAS's root, a share's
 * name, or the path as typed): what the NAS refused and what to do, never « introuvable » for a refusal (#59).
 */
internal fun Asking.refusal(failure: FlowFailure, name: String, where: String): FlowMessage = when (failure) {
    FlowFailure.SILENT -> FlowMessage.Silent(host)
    FlowFailure.MOUNT_REFUSED -> FlowMessage.NotAllowed(player, where)
    FlowFailure.READ_REFUSED -> when {
        nfs && uid != null -> FlowMessage.AccountRefused(uid, where)
        nfs -> FlowMessage.RootRefused(where)
        guest -> FlowMessage.GuestShare(name)
        else -> FlowMessage.Denied(user, name)
    }
    FlowFailure.LOGIN_REFUSED -> if (guest) FlowMessage.GuestRefused else FlowMessage.LoginRefused
    FlowFailure.PASSWORD_UNREAD -> FlowMessage.PasswordUnread
    FlowFailure.MISSING -> if (nfs) FlowMessage.NoExport(where) else FlowMessage.NoShare(host, name)
    FlowFailure.OTHER -> FlowMessage.Other
}
