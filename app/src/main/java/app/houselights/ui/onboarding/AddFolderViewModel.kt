package app.houselights.ui.onboarding

import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
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
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
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
import app.houselights.providers.local.samePlace
import app.houselights.ui.util.UiError
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.toWriteError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
     * The share (or export) and the folder inside it, however much of the NAS's path each field holds. The folder's
     * first segments are dropped when they repeat the share whole, compared as the library compares folders (SMB
     * ignoring case): "/volume1/video" and "/volume1/video/films2" read "films2", as "video" and "video/films2" do. For
     * SMB, "\" reads as "/", the NAS's address before the share ("\\nas\", "smb://nas/") is dropped when it is the
     * address field's ([withoutAddress]), and what the share's field holds after the share goes before the folder; an
     * address before the folder is dropped only when what follows it repeats the share. Nothing else changes: a name
     * that repeats no share, or names another NAS, is read as typed.
     */
    private fun place(): Pair<String, String> {
        val smb = protocol == Protocol.SMB
        val shared = segments(if (smb) withoutAddress(share, address()) else share, smb)
        val written = segments(path, smb)
        val unc = if (smb) segments(withoutAddress(path, address()), smb) else written
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

/** A NAS's name before an SMB path, its "\" read as "/": "//nas/" or "smb://nas/", or the name alone ("//nas"). */
private val SMB_ADDRESS = Regex("^(smb:)?//([^/]+)(/|$)", RegexOption.IGNORE_CASE)

/**
 * [text] as typed for SMB, "\" read as "/", without the NAS's name in front ([SMB_ADDRESS]) when it names [host]
 * ([TypedPaths.namesNas], as the add-folder flow reads a typed path). Any other name is kept as the share it reads as,
 * so "\\video\Films" stays share video, and another NAS's path is never read on this one.
 */
private fun withoutAddress(text: String, host: String): String {
    val typed = text.trim().replace('\\', '/')
    val address = SMB_ADDRESS.find(typed) ?: return typed
    val (scheme, name) = address.destructured
    return if (TypedPaths.namesNas(name, host)) typed.substring(address.range.last + 1) else typed.removePrefix(scheme)
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

    data class Reachable(val entries: Int) : FolderCheck

    data class Failed(val error: ScanError, val host: String) : FolderCheck

    /** [existing] is this folder ([same]) or nests with it, so titles would show twice; nothing was tested or saved. */
    data class Overlaps(val existing: SourceEntity, val same: Boolean) : FolderCheck

    /** The folder answered, but writing it failed (storage or Keystore): nothing was saved. */
    data class SaveFailed(val error: UiError) : FolderCheck
}

/** The form field focus starts on, the one the error that led there likely asks to change; kept as a route argument. */
@Keep
enum class FolderField {
    /** The first control, for a new folder or a plain edit. */
    FIRST,
    HOST,
    PASSWORD,
    ;

    companion object {
        /** After a scan failed with [error]: the address if the NAS or share is gone, the password if sign-in failed. */
        fun after(error: ScanErrorKind?): FolderField = when (error) {
            ScanErrorKind.LOGIN_FAILED -> PASSWORD
            ScanErrorKind.UNREACHABLE, ScanErrorKind.NOT_FOUND -> HOST
            else -> FIRST
        }
    }
}

/**
 * Adds a folder, or edits folder [folderId] with the same form. What was typed, password aside, is kept in [saved]
 * in case Android kills the app while the NAS's address or share is looked up in another app.
 */
class AddFolderViewModel(
    private val library: LocalLibrary,
    private val folderId: Long?,
    /** The app's scope: a tested folder is saved and scanned even if Back leaves the form meanwhile. */
    private val background: CoroutineScope,
    /** Makes the folders the source; true when it changed (it was a server, or nothing yet). */
    private val useFolders: suspend () -> Boolean,
    /** Lists a folder's first level and returns its entry count; throws when it cannot. */
    private val probe: suspend (SourceEntity) -> Int,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    constructor(app: AppContainer, folderId: Long?, saved: SavedStateHandle) : this(
        library = app.library,
        folderId = folderId,
        background = app.scope,
        useFolders = {
            // The app sees the folder first: until then, the folders are no library and give way to Welcome (shownSource).
            app.hasFolders.first { it == true }
            val switched = app.settings.state.value.source != SourceConfig.Local
            if (switched) app.sources.switchTo(SourceConfig.Local)
            switched
        },
        probe = { app.remoteBrowsers.of(it).test(it) },
        saved = saved,
    )

    /** The form as typed before Android killed the app, password aside; null if none. */
    private val kept: FolderForm? = saved.folderForm()

    var form by mutableStateOf(kept ?: FolderForm())
        private set
    var check by mutableStateOf<FolderCheck?>(null)
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

    /** The edited folder as stored; null while it is read, and when adding. */
    private var editing: SourceEntity? = null

    val edits = folderId != null

    /** Whether saving is allowed: an edited folder must be read first, or an empty field would erase its password. */
    var ready by mutableStateOf(!edits)
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
            if (folderId != null) {
                read(folderId)
            } else if (kept == null) {
                // A second folder is most often on the same NAS: start from the last one added. Its share only if
                // it was a folder inside: the same whole share again could only be refused as already added. Over
                // NFS, as the same user and group too: a NAS that squashes root reads the next one as it read the last.
                suspendRunCatching { library.lastFolder() }
                    .onFailure { Log.w(TAG, "Reading the last folder failed", it) }
                    .getOrNull()
                    ?.let { last ->
                        val nfs = last.protocol == Protocol.NFS
                        form = form.copy(
                            protocol = last.protocol,
                            host = last.host,
                            share = if (last.path.isNotBlank()) FolderForm.exportOrShare(last) else "",
                            user = last.username,
                            uid = last.uid?.takeIf { nfs }?.toString().orEmpty(),
                            gid = last.gid?.takeIf { nfs }?.toString().orEmpty(),
                        )
                    }
            }
        }
    }

    /** Reads the edited folder [id]; unread, it stays unsaveable, as an empty password field would erase its own. */
    private suspend fun read(id: Long) {
        suspendRunCatching { library.folder(id) }
            .onSuccess { folder ->
                if (folder != null) {
                    editing = folder
                    form = kept ?: FolderForm.of(folder)
                    keepsPassword = folder.password.value.isNotEmpty() || folder.password.unopened != null
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
        val id = folderId
        if (id == null || !unread || rereading) return
        rereading = true
        viewModelScope.launch {
            try {
                read(id)
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
        if (!form.testsAs(before)) check = null
    }

    /** Whether a test reads the same folder: the forms differ at most in kind. */
    private fun FolderForm.testsAs(other: FolderForm) = copy(kind = other.kind) == other

    /** The described folder; an edited one keeps its id, kind and, if the field is empty, its password. */
    private fun source(): SourceEntity = editing?.let(form::toSource) ?: form.toSource()

    /** The folder to test, or null when the form lacks something, which [check] then reports. */
    private fun toTest(): SourceEntity? {
        form.missing()?.let {
            check = FolderCheck.Incomplete(it)
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
                if (form.testsAs(typed)) check = FolderCheck.Overlaps(held, same = held.samePlace(source))
            }
        }
    }

    /**
     * Tests, then adds the folder and makes the folders the source, or saves the edited folder under its id (its
     * titles keep their watch state). An overlapping folder is refused, unless it is this one, added earlier when
     * switching the source failed: only the switch is left. [onDone] gets whether the source changed; a failed
     * write shows in [check]; if a field a test reads changes mid-test, nothing is saved.
     */
    fun submit(onDone: (switched: Boolean) -> Unit) {
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
                    addedBefore(held, source) -> show(saveInBackground(source), source, onDone)
                    else -> check = FolderCheck.Overlaps(held, same = held.samePlace(source))
                }
            } finally {
                submitting = false
            }
        }
    }

    /** Whether [held] is this very folder, added earlier (when adding, not editing). */
    private fun addedBefore(held: SourceEntity, source: SourceEntity) = folderId == null && held.samePlace(source)

    private fun show(outcome: Saved, source: SourceEntity, onDone: (switched: Boolean) -> Unit) {
        when (outcome) {
            is Saved.Done -> onDone(outcome.switched)
            Saved.Gone -> gone = true
            is Saved.Overlaps -> check = FolderCheck.Overlaps(outcome.existing, same = outcome.existing.samePlace(source))
        }
    }

    private fun saveFailed(error: UiError) {
        check = FolderCheck.SaveFailed(error)
    }

    private sealed interface Saved {
        data class Done(val switched: Boolean) : Saved

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

    private suspend fun save(folder: SourceEntity): Saved {
        val result = if (folderId == null) library.addFolder(folder) else library.updateFolder(folderId, folder)
        return when (result) {
            FolderSave.Saved -> Saved.Done(switched = folderId == null && useFolders())
            FolderSave.Gone -> Saved.Gone
            // Added earlier when switching the source failed: switch now; if already switched, it is a duplicate.
            is FolderSave.Overlaps -> if (addedBefore(result.existing, folder) && useFolders()) {
                Saved.Done(switched = true)
            } else {
                Saved.Overlaps(result.existing)
            }
        }
    }

    /** Tests [source] (the form as [typed]); false, result dropped, if a field a test reads changed meanwhile. */
    private suspend fun reaches(source: SourceEntity, typed: FolderForm): Boolean {
        testing = true
        check = null
        // The browser signs in on its own for a test, so the form's own credentials are what is tested.
        val answer = suspendRunCatching { probe(source) }
        answer.onFailure { e -> Log.w(TAG, "Test of ${source.host} failed", e) }
        val current = form.testsAs(typed)
        if (current) {
            check = answer.fold({ FolderCheck.Reachable(it) }, { FolderCheck.Failed(ScanErrors.classify(it), source.host) })
        }
        testing = false
        return current && answer.isSuccess
    }

    companion object {
        fun factory(app: AppContainer, folderId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer { AddFolderViewModel(app, folderId, createSavedStateHandle()) }
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
