package app.houselights.ui.onboarding

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.R
import app.houselights.core.FailurePlace
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.ExportedFolder
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.FolderSave
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanErrors
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.TypedPaths
import app.houselights.providers.local.holds
import app.houselights.providers.local.samePlace
import app.houselights.ui.util.UiError
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.toWriteError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** A network folder as typed in the form. */
data class FolderForm(
    val kind: SourceKind = SourceKind.MOVIES,
    val protocol: Protocol = Protocol.NFS,
    val host: String = "",
    val share: String = "",
    val path: String = "",
    val user: String = "",
    val password: String = "",
    /** The NFS user and group to present as, as typed ([isId]); both empty for root. */
    val uid: String = "",
    val gid: String = "",
) {
    /**
     * The folder, cleaned up: no scheme, no stray slashes, its share and folder as [place] reads them, credentials for
     * SMB only, an identity for NFS only.
     */
    fun toSource(): SourceEntity {
        val (share, path) = place()
        return SourceEntity(
            name = path.substringAfterLast('/').ifBlank { share.substringAfterLast('/') },
            kind = kind,
            protocol = protocol,
            host = address(),
            share = share,
            path = path,
            username = if (protocol == Protocol.SMB) user.trim() else "",
            password = Secret(if (protocol == Protocol.SMB) password else ""),
            uid = identity()?.first,
            gid = identity()?.second,
        )
    }

    /** The NAS's address, without a scheme or a slash after it. */
    private fun address() = host.trim().removePrefix("smb://").removePrefix("nfs://").trimEnd('/')

    /**
     * The share (or export) and the folder inside it, however much of the NAS's path each field holds, each field read
     * as the add-folder flow reads a path typed whole ([TypedPaths.read], one parser for both). The folder's first
     * segments are dropped when they repeat the share whole, compared as the library compares folders (SMB ignoring
     * case): "/volume1/video" and "/volume1/video/films2" read "films2", as "video" and "video/films2" do. For SMB, "\"
     * reads as "/", the NAS's address before the share ("\\nas\", "smb://nas/", an account or port in it dropped) is
     * dropped when it names the address field's NAS ([TypedPaths.namesNas]), and what the share's field holds after the
     * share goes before the folder; an address before the folder is dropped only when what follows it repeats the share.
     * Nothing else changes: a name that repeats no share, or names another NAS, is read as typed.
     */
    private fun place(): Pair<String, String> {
        val smb = protocol == Protocol.SMB
        val nas = address()
        val shared = TypedPaths.read(share, nas, protocol)?.names.orEmpty()
        val written = TypedPaths.names(path, protocol)
        val unc = if (smb) TypedPaths.read(path, nas, protocol)?.names.orEmpty() else written
        val fromRoot = if (unc.repeats(shared, ignoreCase = smb)) unc else written
        val folder = if (fromRoot.repeats(shared, ignoreCase = smb)) fromRoot.drop(shared.size) else fromRoot
        if (!smb) return shared.joinToString("/") to folder.joinToString("/")
        // A share's name holds no slash: what its field holds after it is a folder inside.
        return shared.firstOrNull().orEmpty() to (shared.drop(1) + folder).joinToString("/")
    }

    /** The NFS user and group typed, both or neither ([missing] asks for the other); null for root, or for SMB. */
    private fun identity(): Pair<Int, Int>? {
        if (protocol != Protocol.NFS) return null
        val user = uid.toIntOrNull() ?: return null
        val group = gid.toIntOrNull() ?: return null
        return user to group
    }

    /**
     * The form with its share and folder as [place] reads them, written as [of] shows a folder so that they read back the
     * same: « /volume1/video/Films » typed in « Sous-dossier » under « /volume1/video » shows « Films », SMB's
     * « \\nas\video\Films2 » shows « video » and « Films2 », a folder inside one named as its share keeps its path
     * ("video/video/Films"). What a test reads is unchanged.
     */
    fun asRead(): FolderForm {
        val read = toSource()
        return copy(share = exportOrShare(read), path = pathOf(read))
    }

    /** [stored] with the form's changes, same id and kind; an empty password field keeps its password. */
    fun toSource(stored: SourceEntity): SourceEntity {
        val typed = toSource()
        val password = if (protocol == Protocol.SMB && password.isEmpty()) stored.password else typed.password
        return typed.copy(id = stored.id, kind = stored.kind, password = password, domain = stored.domain)
    }

    /** What the form lacks before a network test is worth trying, so the message points at the form. */
    @StringRes
    fun missing(): Int? = when {
        host.isBlank() -> R.string.error_host_missing
        protocol == Protocol.NFS && share.isBlank() -> R.string.error_export_missing
        // The NAS's address alone ("\\nas\") names no share; an NFS export may be the NAS's root ("/").
        protocol == Protocol.SMB && place().first.isEmpty() -> R.string.error_share_missing
        protocol == Protocol.NFS && uid.isEmpty() != gid.isEmpty() ->
            if (uid.isEmpty()) R.string.error_uid_missing else R.string.error_gid_missing
        else -> null
    }

    companion object {
        /** The form for editing [folder]: everything but its password, which is never shown; it reads [folder] back. */
        fun of(folder: SourceEntity) = FolderForm(
            kind = folder.kind,
            protocol = folder.protocol,
            host = folder.host,
            share = exportOrShare(folder),
            path = pathOf(folder),
            user = folder.username,
            uid = folder.uid?.toString().orEmpty(),
            gid = folder.gid?.toString().orEmpty(),
        )

        /**
         * [folder]'s path as « Dossier » shows it, so that [place] reads it back: a path starting with its share's
         * names is put after the share again ("video/video/Films"), as [place] reads such a path from the NAS's root.
         */
        private fun pathOf(folder: SourceEntity): String {
            val smb = folder.protocol == Protocol.SMB
            val fromRoot = segments(folder.path, smb).repeats(segments(folder.share, smb), ignoreCase = smb)
            return if (fromRoot) "${exportOrShare(folder)}/${folder.path}" else folder.path
        }

        /** An NFS export reads as a path, with its leading slash. */
        fun exportOrShare(folder: SourceEntity) = if (folder.protocol == Protocol.NFS) "/${folder.share}" else folder.share

        /** Whether [text] can be typed as a user or group: digits alone, a number an NFS identity holds. */
        fun isId(text: String) = text.all { it in '0'..'9' } && (text.isEmpty() || text.toIntOrNull() != null)
    }
}

/** The names of the folders in [path], from the first; for [smb], "\" separates them too. */
private fun segments(path: String, smb: Boolean): List<String> =
    (if (smb) path.trim().replace('\\', '/') else path.trim()).split('/').filter { it.isNotEmpty() }

/** Whether these segments start with all of [share]'s: a path typed from the NAS's root, the share included. */
private fun List<String>.repeats(share: List<String>, ignoreCase: Boolean) =
    share.isNotEmpty() && size >= share.size && share.indices.all { this[it].equals(share[it], ignoreCase) }

/** How the folder answered a test, or why it was not saved. */
sealed interface FolderCheck {
    data class Incomplete(@StringRes val message: Int) : FolderCheck

    /** The folder answered: [folders] subfolders and [videos] videos at its first level, other files not counted. */
    data class Reachable(val folders: Int, val videos: Int) : FolderCheck

    /** The folder answered holding neither a video nor a subfolder: a scan would find nothing, so it is not saved. */
    data object Empty : FolderCheck

    /** The NAS at [host] did not give the folder: [error] as a scan reads it, [trouble] what to change. */
    data class Failed(val error: ScanError, val host: String, val trouble: FolderTrouble = FolderTrouble.Other) : FolderCheck

    /**
     * [existing] is this folder ([same]), or nests with it, so titles would show twice: this one [holds] it, else it is
     * inside it; nothing was tested or saved.
     */
    data class Overlaps(val existing: SourceEntity, val same: Boolean, val holds: Boolean = false) : FolderCheck

    /** The folder answered, but writing it failed (storage or Keystore): nothing was saved. */
    data class SaveFailed(val error: UiError) : FolderCheck
}

/** What a failed test asks to change, as precisely as the NAS's answer says it ([troubleOf]). */
sealed interface FolderTrouble {
    /** The NAS does not answer at its address. */
    data object Unreachable : FolderTrouble

    /** SMB: the NAS has no shared folder [share]. */
    data class ShareMissing(val share: String) : FolderTrouble

    /** NFS: the NAS shares no [export] ("/volume1/video"). */
    data class ExportMissing(val export: String) : FolderTrouble

    /** The shared folder [within] answered (an NFS export's path when [nfs], else an SMB share's name), [name] is not in it. */
    data class SubfolderMissing(val within: String, val name: String, val nfs: Boolean) : FolderTrouble

    /** Something of the folder's place is missing, the NAS not saying what. */
    data object Missing : FolderTrouble

    /** NFS: the NAS refused to mount [export] for this player, whose address on the network is [player] when known. */
    data class PlayerRefused(val export: String, val player: String?) : FolderTrouble

    /** NFS: mounted, but the account presented, user [uid] or root when null, may not read [folder]. */
    data class ReadRefused(val folder: String, val uid: Int?) : FolderTrouble

    /** SMB: [user] signed in, but may not read the shared folder [share]. */
    data class AccountDenied(val user: String, val share: String) : FolderTrouble

    /** SMB: the NAS takes no guest, and no user was typed. */
    data object GuestRefused : FolderTrouble

    data object LoginFailed : FolderTrouble

    /**
     * SMB: the password kept, its field left empty, which the device's Keystore did not open for now: never tried, the
     * NAS not asked; typed again, or tested again once the Keystore answers.
     */
    data object PasswordUnread : FolderTrouble

    data object Other : FolderTrouble
}

/**
 * What [error], the answer to testing [source], asks to change: a missing share or export told from a folder missing
 * inside one that answered, a mount refused to this player ([player], its address) from a read refused to the account
 * presented, an SMB account without the right from a guest refused.
 */
internal fun troubleOf(error: ScanError, source: SourceEntity, player: String?): FolderTrouble {
    val guest = source.protocol == Protocol.SMB && source.username.isBlank()
    return when (error.kind) {
        ScanErrorKind.UNREACHABLE -> FolderTrouble.Unreachable
        ScanErrorKind.LOGIN_FAILED -> if (guest) FolderTrouble.GuestRefused else FolderTrouble.LoginFailed
        // Never a refusal: the NAS was not asked.
        ScanErrorKind.PASSWORD_UNREAD -> FolderTrouble.PasswordUnread
        ScanErrorKind.ACCESS_DENIED -> if (guest) FolderTrouble.GuestRefused else refusalOf(error.place, source, player)
        ScanErrorKind.NOT_FOUND -> missingOf(error.place, source)
        else -> FolderTrouble.Other
    }
}

/** [source] refused at [place]: to its SMB account, to this [player] mounting its export, or to the NFS account reading it. */
private fun refusalOf(place: FailurePlace?, source: SourceEntity, player: String?): FolderTrouble = when {
    source.protocol == Protocol.SMB -> FolderTrouble.AccountDenied(source.username.trim(), source.share)
    place == FailurePlace.FOLDER -> FolderTrouble.ReadRefused(folderOf(source), source.uid)
    else -> FolderTrouble.PlayerRefused(exportOf(source), player)
}

/** What of [source] is missing at [place]: a folder inside its shared folder, or the shared folder itself. */
private fun missingOf(place: FailurePlace?, source: SourceEntity): FolderTrouble {
    val nfs = source.protocol == Protocol.NFS
    val share = if (nfs) exportOf(source) else source.share
    val path = source.path.trim('/')
    return when {
        path.isNotEmpty() && place == FailurePlace.FOLDER -> FolderTrouble.SubfolderMissing(share, path, nfs)
        path.isNotEmpty() && place == null -> FolderTrouble.Missing
        nfs -> FolderTrouble.ExportMissing(share)
        else -> FolderTrouble.ShareMissing(share)
    }
}

/** An NFS export as the NAS names it, from its root: "/volume1/video". */
private fun exportOf(source: SourceEntity) = "/" + source.share.trim('/')

/** [source]'s folder from the NAS's root, as the panel beside the form shows it: "/volume1/video/Films", "/" for the root. */
private fun folderOf(source: SourceEntity) =
    "/" + listOf(source.share, source.path).map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")

/** Where the form says what went wrong: under the field to change, which takes focus, or in the panel beside the form. */
internal enum class FaultSpot { HOST, SHARE, SUBFOLDER, USER, PASSWORD, UID, GID, PANEL }

/**
 * Where [check]'s failure is said in [form] ([FaultSpot]); null when nothing failed. What the path lacks or holds wrong
 * goes under the path's last field filled, « Sous-dossier » when it holds something; a folder holding one of the
 * library's under « Sous-dossier » all the same, where a subfolder is to be chosen. A refusal no field can fix (the
 * NAS's rules, the NFS account) and a folder not saved are the panel's.
 */
internal fun spotOf(check: FolderCheck?, form: FolderForm): FaultSpot? = when (check) {
    null, is FolderCheck.Reachable -> null
    is FolderCheck.Incomplete -> spotOf(check.message)
    is FolderCheck.Failed -> spotOf(check.trouble, form)
    is FolderCheck.Overlaps -> if (check.holds && !check.same) FaultSpot.SUBFOLDER else lastPathField(form)
    FolderCheck.Empty -> lastPathField(form)
    is FolderCheck.SaveFailed -> FaultSpot.PANEL
}

/** Under the field the form lacks ([FolderForm.missing]'s [message]). */
private fun spotOf(@StringRes message: Int): FaultSpot = when (message) {
    R.string.error_host_missing -> FaultSpot.HOST
    R.string.error_uid_missing -> FaultSpot.UID
    R.string.error_gid_missing -> FaultSpot.GID
    else -> FaultSpot.SHARE
}

/** Under the field that changes what [trouble] says, else in the panel. */
private fun spotOf(trouble: FolderTrouble, form: FolderForm): FaultSpot = when (trouble) {
    FolderTrouble.Unreachable -> FaultSpot.HOST
    is FolderTrouble.ShareMissing, is FolderTrouble.ExportMissing, FolderTrouble.Missing -> FaultSpot.SHARE
    is FolderTrouble.SubfolderMissing -> lastPathField(form)
    is FolderTrouble.AccountDenied, FolderTrouble.GuestRefused -> FaultSpot.USER
    FolderTrouble.LoginFailed, FolderTrouble.PasswordUnread -> FaultSpot.PASSWORD
    is FolderTrouble.PlayerRefused, is FolderTrouble.ReadRefused, FolderTrouble.Other -> FaultSpot.PANEL
}

/** « Sous-dossier » when it holds something, else « Chemin de montage » or « Dossier partagé ». */
private fun lastPathField(form: FolderForm) = if (form.path.isNotBlank()) FaultSpot.SUBFOLDER else FaultSpot.SHARE

/** The form field focus starts on, the one the error that led there likely asks to change; kept as a route argument. */
@Keep
enum class FolderField {
    /** The first control, for a plain edit. */
    FIRST,
    HOST,
    PASSWORD,

    /** « Chemin de montage » or « Dossier partagé »: where the folder is on the NAS. */
    PATH,
    ;

    companion object {
        /** After a scan failed with [error]: the address if the NAS is silent, the path if it is gone, the password if sign-in failed. */
        fun after(error: ScanErrorKind?): FolderField = when (error) {
            ScanErrorKind.LOGIN_FAILED -> PASSWORD
            ScanErrorKind.UNREACHABLE -> HOST
            ScanErrorKind.NOT_FOUND -> PATH
            else -> FIRST
        }
    }
}

/**
 * « Modifier le dossier »: edits folder [folderId], a folder being added through « Ajouter un dossier »'s steps
 * ([FolderFlow]). What was typed, password aside, is kept in [saved] in case Android kills the app while the NAS's
 * address or share is looked up in another app.
 */
class AddFolderViewModel(
    private val library: LocalLibrary,
    private val folderId: Long,
    /** The app's scope: a tested folder is saved and scanned even if Back leaves the form meanwhile. */
    private val background: CoroutineScope,
    /** Lists a folder's first level and counts its subfolders and videos; throws when it cannot. */
    private val probe: suspend (SourceEntity) -> FolderLevel,
    // An empty handle for a test that gives none: the factory hands the real one.
    //noinspection VisibleForTests
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** This player's address on the network ([app.houselights.journal.HomeAddress]), which NFS rules allow; null when unknown. */
    private val playerAddress: suspend () -> String? = { null },
    /** What an NFS server at a host exports, each with the clients it allows (MOUNT's EXPORT); null when it does not say. */
    private val exports: suspend (host: String) -> List<ExportedFolder>? = { null },
    /** What the NAS has that a folder's path it lacks ([FolderTrouble]) may mean ([suggestionFor]); null when nothing is near. */
    private val nearby: suspend (SourceEntity, FolderTrouble) -> FolderSuggestion? = { _, _ -> null },
) : ViewModel() {
    constructor(app: AppContainer, context: Context, folderId: Long, saved: SavedStateHandle) : this(
        library = app.library,
        folderId = folderId,
        background = app.scope,
        probe = { app.remoteBrowsers.of(it).test(it) },
        saved = saved,
        playerAddress = app::playerAddress,
        exports = { host -> app.remoteBrowsers.exports(host) },
        nearby = { source, trouble -> suggestionFor(source, trouble, AppFlowNetwork(app, context)) },
    )

    /** The form as typed before Android killed the app, password aside; null if none. */
    private val kept: FolderForm? = saved.folderForm()

    var form by mutableStateOf(kept ?: FolderForm())
        private set
    var check by mutableStateOf<FolderCheck?>(null)
        private set

    /**
     * How many answers a press got ([check]), so the same failure said again sends focus back to its field; a change
     * that only clears the last answer does not count. Kept across a stopped process, as the form keeps the last answer
     * it sent focus for: counted again from 0, the answer reaching that count would send none.
     */
    var answers by mutableIntStateOf(saved.get<Int>(ANSWERS) ?: 0)
        private set

    /** This player's address on the network, which the NAS must allow over NFS; null until read, or when unknown. */
    var player by mutableStateOf<String?>(null)
        private set

    /** What the NAS proposes for the path the last test found it lacks (« Utiliser /volume1/media »); null when nothing. */
    var suggestion by mutableStateOf<FolderSuggestion?>(null)
        private set

    /**
     * The folders the NAS shares, two or more, as the last test read them when it found the path's export missing and
     * nothing near it (#59): « Choisir un dossier partagé » opens the guided flow's step 4 on them ([openPick]). Null
     * otherwise, and never with a [suggestion]; kept across a stopped process with the failure it follows.
     */
    var shared by mutableStateOf<List<ExportedFolder>?>(null)
        private set

    /** The exports the running test read to tell a mount refused ([unexported]); null when it did not. */
    private var listed: List<ExportedFolder>? = null

    /**
     * The flow's step 4 is open over the form to choose a shared folder ([openPick]): back, the form tests the one chosen
     * ([pick]), else focus returns to « Choisir un dossier partagé » ([pickLeft]). Kept across a stopped process.
     */
    var away by mutableStateOf(saved.get<Boolean>(AWAY) ?: false)
        private set

    var testing by mutableStateOf(false)
        private set

    /** Adding or saving the tested folder, until the library has it and has requested a scan. */
    var saving by mutableStateOf(false)
        private set

    /** Add (or Save) pressed and not yet resolved: a second OK meanwhile does nothing. */
    var submitting by mutableStateOf(false)
        private set

    /** Testing or saving: the other action waits. */
    val busy: Boolean get() = testing || saving || submitting

    /** The edited folder as stored; null while it is read. */
    private var editing: SourceEntity? = null

    /** Whether saving is allowed: the folder must be read first, or an empty field would erase its password. */
    var ready by mutableStateOf(false)
        private set

    /** The edited folder could not be read: nothing of it shows, nor can be saved, until [readAgain] reads it. */
    var unread by mutableStateOf(false)
        private set

    /** Reading the edited folder again, after it could not be read. */
    var rereading by mutableStateOf(false)
        private set

    /** The edited folder was removed from the library meanwhile: nothing can be saved. */
    var gone by mutableStateOf(false)
        private set

    /** The edited folder has a password, which an empty field keeps. */
    var keepsPassword by mutableStateOf(false)
        private set

    /** The NFS identity's fields show under its row; folded, the row still says its value. */
    var identityOpen by mutableStateOf(saved.get<Boolean>(IDENTITY_OPEN) ?: false)
        private set

    /** Opens or folds the identity's fields; what they hold stays. */
    fun toggleIdentity() = showIdentity(!identityOpen)

    private fun showIdentity(open: Boolean) {
        identityOpen = open
        saved[IDENTITY_OPEN] = open
    }

    init {
        viewModelScope.launch {
            player = suspendRunCatching { playerAddress() }.onFailure { Log.w(TAG, "Reading this player's address failed", it) }.getOrNull()
        }
        viewModelScope.launch { read(folderId) }
    }

    /** Reads the edited folder [id]; unread, it stays unsaveable, as an empty password field would erase its own. */
    private suspend fun read(id: Long) {
        suspendRunCatching { library.folder(id) }
            .onSuccess { folder ->
                if (folder != null) {
                    editing = folder
                    form = kept ?: FolderForm.of(folder)
                    keepsPassword = folder.password.value.isNotEmpty() || folder.password.unopened != null
                    if (kept != null) restoreShared()
                } else {
                    gone = true
                }
                unread = false
                ready = true
            }
            .onFailure {
                Log.w(TAG, "Reading folder $id failed", it)
                unread = true
            }
    }

    /** Reads the edited folder again (Réessayer), after it could not be read. */
    fun readAgain() {
        if (!unread || rereading) return
        rereading = true
        viewModelScope.launch {
            try {
                read(folderId)
            } finally {
                rereading = false
            }
        }
    }

    /** Applies [change]; the last result goes stale when anything a test reads (all but the kind) changes. */
    fun edit(change: FolderForm.() -> FolderForm) {
        val before = form
        form = form.change()
        saved.keep(form)
        if (!form.testsAs(before)) {
            check = null
            suggestion = null
            keepShared(null)
        }
    }

    /**
     * « Choisir un dossier partagé »: what the flow's step 4 opens on, the folders [shared] as read from the NAS the form
     * names, with its NFS account; the form is [away] until it is back. Null when there is no list to choose from.
     */
    fun openPick(): SharePick? {
        val exports = shared ?: return null
        markAway(true)
        return SharePick(folderId, source().host, exports, form.uid, form.gid)
    }

    /** A shared folder chosen in the flow's step 4: written in « Chemin de montage », the subfolder kept, then tested. */
    fun pick(export: String) {
        markAway(false)
        edit { copy(share = "/" + export.trim('/')) }
        test()
    }

    /** Back from the flow's step 4 with nothing chosen: the form as it was, the failure and its line in place. */
    fun pickLeft() = markAway(false)

    private fun markAway(on: Boolean) {
        away = on
        saved[AWAY] = on
    }

    /**
     * Sets [shared] to [exports], kept with the failure [failed] they follow ([check]) across a stopped process, so the
     * form comes back from the flow's step 4 as it was; null forgets both.
     */
    private fun keepShared(exports: List<ExportedFolder>?, failed: FolderCheck.Failed? = null) {
        shared = exports
        val missing = failed?.trouble as? FolderTrouble.ExportMissing
        if (exports == null || failed == null || missing == null) {
            SHARED_KEYS.forEach { saved.remove<Any>(it) }
            return
        }
        saved[SHARED_PATHS] = ArrayList(exports.map { it.path })
        saved[SHARED_CLIENTS] = ArrayList(clientsOf(exports))
        saved[SHARED_MISSING] = missing.export
        saved[SHARED_KIND] = failed.error.kind.name
        saved[SHARED_PLACE] = failed.error.place?.name
        saved[SHARED_HOST] = failed.host
    }

    /** The failure and the folders to choose from as a stopped process left them over the form it kept. */
    private fun restoreShared() {
        val paths = saved.get<List<String>>(SHARED_PATHS) ?: return
        val clients = saved.get<List<String>>(SHARED_CLIENTS).orEmpty()
        val missing = saved.get<String>(SHARED_MISSING) ?: return
        val kind = ScanErrorKind.entries.find { it.name == saved.get<String>(SHARED_KIND) } ?: return
        val place = FailurePlace.entries.find { it.name == saved.get<String>(SHARED_PLACE) }
        check = FolderCheck.Failed(ScanError(kind, place), saved.get<String>(SHARED_HOST).orEmpty(), FolderTrouble.ExportMissing(missing))
        shared = exportsOf(paths, clients)
    }

    /** « Utiliser /volume1/media »: what the NAS proposes written in the path's fields, then tested again. */
    fun useSuggestion() {
        val proposed = suggestion ?: return
        val placed = FolderForm.of(source().copy(share = proposed.share, path = proposed.path))
        edit { copy(share = placed.share, path = placed.path) }
        test()
    }

    /** Whether a test reads the same folder: the forms differ at most in kind. */
    private fun FolderForm.testsAs(other: FolderForm) = copy(kind = other.kind) == other

    /** The described folder; an edited one keeps its id, kind and, if the field is empty, its password. */
    private fun source(): SourceEntity = editing?.let(form::toSource) ?: form.toSource()

    /** Says [answer] to a press: in [check], counted in [answers]. */
    private fun say(answer: FolderCheck) {
        check = answer
        answers++
        saved[ANSWERS] = answers
    }

    /** The folder to test, or null when the form lacks something, which [check] then reports. */
    private fun toTest(): SourceEntity? {
        form.missing()?.let {
            say(FolderCheck.Incomplete(it))
            // The user or the group asked for: their fields show, to type it.
            if (it == R.string.error_uid_missing || it == R.string.error_gid_missing) showIdentity(true)
            return null
        }
        return source()
    }

    /**
     * Tests the folder and reports the result in [check]. A folder the library holds, or one nesting with it, gets the
     * answer Add would give, without asking the NAS, so a test never passes a folder Add then refuses; when the folders
     * cannot be read, the NAS is asked all the same, and Add says why nothing can be saved.
     */
    fun test() {
        if (busy || gone) return
        val typed = form
        val source = toTest() ?: return
        // Busy from the press: a second one while the folders are read does nothing, as during the test.
        testing = true
        viewModelScope.launch {
            val held = suspendRunCatching { library.overlapping(source, except = folderId) }
                .onFailure { Log.w(TAG, "Reading the folders before testing ${source.host} failed", it) }
                .getOrNull()
            if (held == null) {
                reaches(source, typed)
            } else {
                testing = false
                if (form.testsAs(typed)) say(overlap(held, source))
            }
        }
    }

    /**
     * Tests, then saves the folder under its id (its titles keep their watch state); one overlapping another of the
     * library is refused. [onDone] once saved; a failed write shows in [check]; if a field a test reads changes
     * mid-test, nothing is saved.
     */
    fun submit(onDone: () -> Unit) {
        if (busy || !ready || gone) return
        val typed = form
        val source = toTest() ?: return
        submitting = true
        viewModelScope.launchAction(TAG, "Saving the folder at ${source.host}", ::saveFailed, Throwable::toWriteError) {
            try {
                val held = library.overlapping(source, except = folderId)
                when {
                    // reaches() vouches for the current form, so save it as it is now, a changed kind included.
                    held == null -> if (reaches(source, typed)) show(saveInBackground(source()), source, onDone)
                    else -> say(overlap(held, source))
                }
            } finally {
                submitting = false
            }
        }
    }

    /** [source] nesting with the library's [held], said the right way round: the same, holding it, or inside it. */
    private fun overlap(held: SourceEntity, source: SourceEntity) =
        FolderCheck.Overlaps(held, same = held.samePlace(source), holds = source.holds(held))

    private fun show(outcome: Saved, source: SourceEntity, onDone: () -> Unit) {
        when (outcome) {
            Saved.Done -> onDone()
            Saved.Gone -> gone = true
            is Saved.Overlaps -> say(overlap(outcome.existing, source))
        }
    }

    private fun saveFailed(error: UiError) {
        say(FolderCheck.SaveFailed(error))
    }

    private sealed interface Saved {
        data object Done : Saved

        data object Gone : Saved

        data class Overlaps(val existing: SourceEntity) : Saved
    }

    /** Saves [folder] in the app's scope, so Back cannot half-save it; failures are logged even with no form left. */
    private suspend fun saveInBackground(folder: SourceEntity): Saved {
        saving = true
        try {
            return background.async {
                suspendRunCatching { save(folder) }.onFailure { Log.w(TAG, "The folder at ${folder.host} was not saved", it) }
            }.await().getOrThrow()
        } finally {
            saving = false
        }
    }

    private suspend fun save(folder: SourceEntity): Saved = when (val result = library.updateFolder(folderId, folder)) {
        FolderSave.Saved -> Saved.Done
        FolderSave.Gone -> Saved.Gone
        is FolderSave.Overlaps -> Saved.Overlaps(result.existing)
    }

    /**
     * Tests [source] (the form as [typed]); false, result dropped, if a field a test reads changed meanwhile, and false
     * for a folder holding nothing ([FolderCheck.Empty]), which a scan could not fill. Once the NAS answered, the path's
     * fields show the folder as the test read it ([FolderForm.asRead]); a path it lacks comes with what it proposes
     * ([suggestion]), asked before the answer is said, so that focus goes to it.
     */
    private suspend fun reaches(source: SourceEntity, typed: FolderForm): Boolean {
        testing = true
        check = null
        suggestion = null
        keepShared(null)
        listed = null
        try {
            // The browser signs in on its own for a test, so the form's own credentials are what is tested.
            val answer = suspendRunCatching { probe(source) }
            answer.onFailure { e -> Log.w(TAG, "Test of ${source.host} failed", e) }
            // Checked again once the answer is read: telling an export missing asks the NAS for its exports meanwhile.
            val said = if (form.testsAs(typed)) answer.fold(::found) { e -> failed(e, source) } else null
            val failure = said as? FolderCheck.Failed
            val proposed = failure?.let { proposedFor(source, it.trouble) }
            // Nothing near an export the NAS lacks: its one shared folder proposed, or its list to choose from.
            val sharing = if (failure?.trouble is FolderTrouble.ExportMissing && proposed == null) sharedBy(source) else null
            if (said == null || !form.testsAs(typed)) return false
            form = form.asRead()
            saved.keep(form)
            suggestion = proposed ?: sharing?.singleOrNull()?.let { only -> onlyShare(only, source) }
            keepShared(sharing?.takeIf { it.size > 1 }, failure)
            say(said)
            // A read refused to the account presented: the NFS account is to change, its row opened.
            if ((said as? FolderCheck.Failed)?.trouble is FolderTrouble.ReadRefused) showIdentity(true)
            return answer.getOrNull()?.isEmpty == false
        } finally {
            testing = false
        }
    }

    /**
     * The folders [source]'s NFS server shares, as this test read them to tell a mount refused, else asked now; null when
     * it does not say, or lists none.
     */
    private suspend fun sharedBy(source: SourceEntity): List<ExportedFolder>? {
        val read = listed ?: suspendRunCatching { exports(source.host) }
            .onFailure { Log.w(TAG, "Listing the exports of ${source.host} failed", it) }
            .getOrNull()
        return read?.takeIf { it.isNotEmpty() }
    }

    /** « Utiliser /volume1/video », the [only] folder the NAS shares, [source]'s subfolder kept inside it. */
    private fun onlyShare(only: ExportedFolder, source: SourceEntity) =
        FolderSuggestion(only.path.trim('/'), source.path.trim('/'), only = true)

    /** What the NAS proposes for [source]'s path, which [trouble] says it lacks; null when nothing is near, or it does not say. */
    private suspend fun proposedFor(source: SourceEntity, trouble: FolderTrouble): FolderSuggestion? =
        suspendRunCatching { nearby(source, trouble) }
            .onFailure { Log.w(TAG, "Asking ${source.host} what it has near the path failed", it) }
            .getOrNull()
            ?.takeIf { it.share != source.share.trim('/') || it.path != source.path.trim('/') }

    /** What a test found: the folder's first level, or nothing a scan could fill. */
    private fun found(level: FolderLevel): FolderCheck =
        if (level.isEmpty) FolderCheck.Empty else FolderCheck.Reachable(level.folders.size, level.videos)

    /**
     * [e], the answer to testing [source], as the form says it. A mount refused for an export the NAS does not list is an
     * export it lacks, as Linux's mountd (a Synology's, a QNAP's) refuses a path it does not export: never the player
     * to allow.
     */
    private suspend fun failed(e: Throwable, source: SourceEntity): FolderCheck.Failed {
        val error = ScanErrors.classify(e)
        val trouble = troubleOf(error, source, player)
        val lacking = trouble is FolderTrouble.PlayerRefused && unexported(source)
        return FolderCheck.Failed(error, source.host, if (lacking) FolderTrouble.ExportMissing(exportOf(source)) else trouble)
    }

    /** Whether the NFS server at [source]'s host lists its exports and none holds [source]'s export; false when it does not say. */
    private suspend fun unexported(source: SourceEntity): Boolean {
        val read = suspendRunCatching { exports(source.host) }
            .onFailure { Log.w(TAG, "Listing the exports of ${source.host} failed", it) }
            .getOrNull()
        listed = read
        if (read.isNullOrEmpty()) return false
        val export = exportOf(source)
        return read.map { "/" + it.path.trim('/') }.none { it == "/" || export == it || export.startsWith("$it/") }
    }

    companion object {
        fun factory(app: AppContainer, context: Context, folderId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { AddFolderViewModel(app, context.applicationContext, folderId, createSavedStateHandle()) }
        }
    }
}

private const val TAG = "AddFolder"

private const val KIND = "kind"
private const val PROTOCOL = "protocol"
private const val HOST = "host"
private const val SHARE = "share"
private const val PATH = "path"
private const val USER = "user"
private const val UID = "uid"
private const val GID = "gid"
private const val IDENTITY_OPEN = "identityOpen"
private const val AWAY = "away"
private const val ANSWERS = "answers"
private const val SHARED_PATHS = "sharedPaths"
private const val SHARED_CLIENTS = "sharedClients"
private const val SHARED_MISSING = "sharedMissing"
private const val SHARED_KIND = "sharedKind"
private const val SHARED_PLACE = "sharedPlace"
private const val SHARED_HOST = "sharedHost"

/** What [AddFolderViewModel.shared] keeps across a stopped process. */
private val SHARED_KEYS = listOf(SHARED_PATHS, SHARED_CLIENTS, SHARED_MISSING, SHARED_KIND, SHARED_PLACE, SHARED_HOST)

/** Keeps the form as plain values across a killed process; never the password, as saved state is written in clear. */
private fun SavedStateHandle.keep(form: FolderForm) {
    this[KIND] = form.kind.name
    this[PROTOCOL] = form.protocol.name
    this[HOST] = form.host
    this[SHARE] = form.share
    this[PATH] = form.path
    this[USER] = form.user
    this[UID] = form.uid
    this[GID] = form.gid
}

/** The kept form, password empty; null when none was kept or its values are unknown. */
private fun SavedStateHandle.folderForm(): FolderForm? {
    val kind = get<String>(KIND)?.let { name -> SourceKind.entries.find { it.name == name } } ?: return null
    val protocol = get<String>(PROTOCOL)?.let { name -> Protocol.entries.find { it.name == name } } ?: return null
    return FolderForm(
        kind = kind,
        protocol = protocol,
        host = get<String>(HOST).orEmpty(),
        share = get<String>(SHARE).orEmpty(),
        path = get<String>(PATH).orEmpty(),
        user = get<String>(USER).orEmpty(),
        uid = get<String>(UID).orEmpty(),
        gid = get<String>(GID).orEmpty(),
    )
}
