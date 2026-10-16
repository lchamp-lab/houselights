package app.houselights.ui.onboarding

import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.AppContainer
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.FolderSave
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.RemoteBrowser
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanErrors
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SmbBrowser
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.samePlace
import app.houselights.ui.util.UiError
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.toWriteError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A network folder as the form describes it, as typed. */
data class FolderForm(
    val kind: SourceKind = SourceKind.MOVIES,
    val protocol: Protocol = Protocol.NFS,
    val host: String = "",
    val share: String = "",
    val path: String = "",
    val user: String = "",
    val password: String = "",
) {
    /** The folder, cleaned up: no scheme, no stray slashes, credentials for SMB only. */
    fun toSource() = SourceEntity(
        name = path.trim('/').substringAfterLast('/').ifBlank { share.trim('/').substringAfterLast('/') },
        kind = kind,
        protocol = protocol,
        host = host.trim().removePrefix("smb://").removePrefix("nfs://").trimEnd('/'),
        share = share.trim().trim('/'),
        path = path.trim().trim('/'),
        username = if (protocol == Protocol.SMB) user.trim() else "",
        password = Secret(if (protocol == Protocol.SMB) password else ""),
    )

    /**
     * [stored] as the form changed it. It stays the same folder, of the same kind; its password is
     * never shown, so a field left empty keeps it.
     */
    fun toSource(stored: SourceEntity): SourceEntity {
        val typed = toSource()
        val password = if (protocol == Protocol.SMB && password.isEmpty()) stored.password else typed.password
        return typed.copy(id = stored.id, kind = stored.kind, password = password, domain = stored.domain)
    }

    /** What the form lacks before the network is worth trying, so the message speaks about the form. */
    @StringRes
    fun missing(): Int? = when {
        host.isBlank() -> R.string.error_host_missing
        share.isBlank() -> if (protocol == Protocol.NFS) R.string.error_export_missing else R.string.error_share_missing
        else -> null
    }

    companion object {
        /** The form of a folder already added, to edit it: everything but its password, never shown. */
        fun of(folder: SourceEntity) = FolderForm(
            kind = folder.kind,
            protocol = folder.protocol,
            host = folder.host,
            share = exportOrShare(folder),
            path = folder.path,
            user = folder.username,
        )

        /** An NFS export reads as a path, with its leading slash. */
        fun exportOrShare(folder: SourceEntity) = if (folder.protocol == Protocol.NFS) "/${folder.share}" else folder.share
    }
}

/** How the folder answered a test, or why it was not saved. */
sealed interface FolderCheck {
    data class Incomplete(@StringRes val message: Int) : FolderCheck

    data class Reachable(val entries: Int) : FolderCheck

    data class Failed(val error: ScanError, val host: String) : FolderCheck

    /**
     * [existing], already in the library, is this folder ([same]) or one inside or around it: its
     * titles would show twice. Nothing was asked of the network, nothing was saved.
     */
    data class Overlaps(val existing: SourceEntity, val same: Boolean) : FolderCheck

    /** The folder answered, but writing it failed (the device's storage, its Keystore): nothing was saved. */
    data class SaveFailed(val error: UiError) : FolderCheck
}

/**
 * The field of the folder form that focus starts on: the one the error that led there likely asks
 * to change. Kept: a navigation route carries it.
 */
@Keep
enum class FolderField {
    /** The first one, for a folder to add, or to edit at will. */
    FIRST,
    HOST,
    PASSWORD,
    ;

    companion object {
        /** After a scan that failed with [error]: a NAS silent or a share gone, its address; a sign-in refused, its password. */
        fun after(error: ScanErrorKind?): FolderField = when (error) {
            ScanErrorKind.LOGIN_FAILED -> PASSWORD
            ScanErrorKind.UNREACHABLE, ScanErrorKind.NOT_FOUND -> HOST
            else -> FIRST
        }
    }
}

/** Adds a folder, or edits the folder [folderId]: the same form, filled in with it. */
class AddFolderViewModel(
    private val library: LocalLibrary,
    private val folderId: Long?,
    /**
     * Work that outlives the form (the app's): a folder tested is saved, and scanned, even when Back
     * leaves the form while it is written.
     */
    private val background: CoroutineScope,
    /** Makes the folders the source; true when it changed (it was a server, or nothing yet). */
    private val useFolders: suspend () -> Boolean,
    /** Reads the first level of a folder: how many entries it holds. Throws when it cannot. */
    private val probe: suspend (SourceEntity) -> Int = { RemoteBrowser.of(it).test(it) },
    /** Closes the SMB sessions kept open on a folder's share. */
    private val forgetSessions: suspend (SourceEntity) -> Unit = { withContext(Dispatchers.IO) { SmbBrowser.forget(it) } },
) : ViewModel() {
    constructor(app: AppContainer, folderId: Long? = null) : this(
        library = app.library,
        folderId = folderId,
        background = app.scope,
        useFolders = {
            val switched = app.settings.state.value.source != SourceConfig.Local
            if (switched) app.sources.switchTo(SourceConfig.Local)
            switched
        },
    )

    var form by mutableStateOf(FolderForm())
        private set
    var check by mutableStateOf<FolderCheck?>(null)
        private set

    /** A test of the folder is under way. */
    var testing by mutableStateOf(false)
        private set

    /** The folder tested is being added or saved, until the library has it and has asked for a scan. */
    var saving by mutableStateOf(false)
        private set

    /** Add (or Save) pressed, until it is done or said why not: a second OK meanwhile changes nothing. */
    var submitting by mutableStateOf(false)
        private set

    /** Testing or saving: the other action waits. */
    val busy: Boolean get() = testing || saving || submitting

    /** The folder edited, as stored; null while it is read, and when a folder is added. */
    private var editing: SourceEntity? = null

    val edits = folderId != null

    /** The form can be saved: a folder edited has been read, else an empty field would erase its password. */
    var ready by mutableStateOf(!edits)
        private set

    /** The folder edited is no longer in the library: it was removed meanwhile, and nothing can be saved. */
    var gone by mutableStateOf(false)
        private set

    /** The folder edited has a password, which a field left empty keeps. */
    var keepsPassword by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            if (folderId != null) {
                val folder = library.folder(folderId)
                if (folder != null) {
                    editing = folder
                    form = FolderForm.of(folder)
                    keepsPassword = folder.password.value.isNotEmpty()
                } else {
                    gone = true
                }
                ready = true
            } else {
                // A second folder is most often on the same NAS: start from the last one added.
                library.lastFolder()?.let { last ->
                    form = form.copy(
                        protocol = last.protocol,
                        host = last.host,
                        share = FolderForm.exportOrShare(last),
                        user = last.username,
                    )
                }
            }
        }
    }

    /** A change makes a successful test stale; a problem stays shown until the next test. */
    fun edit(change: FolderForm.() -> FolderForm) {
        val before = form
        form = form.change()
        check = if (form.protocol != before.protocol) null else check.takeUnless { it is FolderCheck.Reachable }
    }

    /** The folder the form describes; an edited one keeps its id, its kind and, left empty, its password. */
    private fun source(): SourceEntity = editing?.let(form::toSource) ?: form.toSource()

    /** The folder to test, or null when the form lacks what a test needs, which [check] then says. */
    private fun toTest(): SourceEntity? {
        form.missing()?.let {
            check = FolderCheck.Incomplete(it)
            return null
        }
        return source()
    }

    /** Tests the folder, and says how it answered. */
    fun test() {
        if (busy || gone) return
        val source = toTest() ?: return
        viewModelScope.launch { reaches(source) }
    }

    /**
     * Tests the folder, then adds it to the library and makes the local library the source, or saves
     * the folder edited under its id: its titles keep what was watched and chosen. A folder the library
     * already holds, or one inside or around it, is refused first. [onDone] hears whether the source
     * changed (it was a server, or nothing yet), unless the form was left meanwhile. A write that fails
     * says why in [check].
     */
    fun submit(onDone: (switched: Boolean) -> Unit) {
        if (busy || !ready || gone) return
        val source = toTest() ?: return
        submitting = true
        viewModelScope.launchAction(TAG, "Saving the folder at ${source.host}", ::saveFailed, Throwable::toWriteError) {
            try {
                library.overlapping(source, except = folderId)?.let {
                    check = FolderCheck.Overlaps(it, same = it.samePlace(source))
                    return@launchAction
                }
                if (!reaches(source)) return@launchAction
                when (val saved = saveInBackground(source)) {
                    is Saved.Done -> onDone(saved.switched)
                    Saved.Gone -> gone = true
                    is Saved.Overlaps -> check = FolderCheck.Overlaps(saved.existing, same = saved.existing.samePlace(source))
                }
            } finally {
                submitting = false
            }
        }
    }

    private fun saveFailed(error: UiError) {
        check = FolderCheck.SaveFailed(error)
    }

    /** What saving did. */
    private sealed interface Saved {
        /** Saved; [switched] when the source changed. */
        data class Done(val switched: Boolean) : Saved

        data object Gone : Saved

        data class Overlaps(val existing: SourceEntity) : Saved
    }

    /**
     * Saves [folder] in the app's scope: Back leaving the form does not stop a folder half saved,
     * before its scan. A failure there is logged even when no form is left to say it.
     */
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
            is FolderSave.Overlaps -> Saved.Overlaps(result.existing)
        }
    }

    /** Tests [source] and says how it answered: true when it did. */
    private suspend fun reaches(source: SourceEntity): Boolean {
        testing = true
        check = null
        val answer = suspendRunCatching {
            // An edited folder signs in with what the form says: a session kept from an earlier test,
            // with the password stored, would let a wrong new one through.
            if (edits && source.protocol == Protocol.SMB) forgetSessions(source)
            probe(source)
        }
        answer
            .onSuccess { count -> check = FolderCheck.Reachable(count) }
            .onFailure { e ->
                Log.w(TAG, "Test of ${source.host} failed", e)
                check = FolderCheck.Failed(ScanErrors.classify(e), source.host)
            }
        testing = false
        return answer.isSuccess
    }
}

private const val TAG = "AddFolder"
