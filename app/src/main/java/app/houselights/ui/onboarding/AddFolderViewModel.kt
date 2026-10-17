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
import app.houselights.providers.local.samePlace
import app.houselights.ui.util.UiError
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.toWriteError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

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

/**
 * Adds a folder, or edits the folder [folderId]: the same form, filled in with it. What was typed,
 * but the password, is kept in [saved]: Android may stop the app while the NAS's address or share is
 * looked up in another one, and the form comes back as it was left.
 */
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
    private val probe: suspend (SourceEntity) -> Int,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    constructor(app: AppContainer, folderId: Long?, saved: SavedStateHandle) : this(
        library = app.library,
        folderId = folderId,
        background = app.scope,
        useFolders = {
            val switched = app.settings.state.value.source != SourceConfig.Local
            if (switched) app.sources.switchTo(SourceConfig.Local)
            switched
        },
        probe = { app.remoteBrowsers.of(it).test(it) },
        saved = saved,
    )

    /** The form as typed before Android stopped the app, its password aside; null when there was none. */
    private val kept: FolderForm? = saved.folderForm()

    var form by mutableStateOf(kept ?: FolderForm())
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
                    form = kept ?: FolderForm.of(folder)
                    keepsPassword = folder.password.value.isNotEmpty()
                } else {
                    gone = true
                }
                ready = true
            } else if (kept == null) {
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

    /**
     * What the last test said, success or failure, or why the folder was not added, speaks of the
     * folder the form described then: a change to what a test reads (all but the kind) makes it stale.
     */
    fun edit(change: FolderForm.() -> FolderForm) {
        val before = form
        form = form.change()
        saved.keep(form)
        if (form.copy(kind = before.kind) != before) check = null
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
     * already holds, or one inside or around it, is refused first, unless it is this very folder, added
     * before while making the folders the source failed: that is all there is left to do, without a
     * test. [onDone] hears whether the source changed (it was a server, or nothing yet), unless the
     * form was left meanwhile. A write that fails says why in [check].
     */
    fun submit(onDone: (switched: Boolean) -> Unit) {
        if (busy || !ready || gone) return
        val source = toTest() ?: return
        submitting = true
        viewModelScope.launchAction(TAG, "Saving the folder at ${source.host}", ::saveFailed, Throwable::toWriteError) {
            try {
                val held = library.overlapping(source, except = folderId)
                when {
                    held == null -> if (reaches(source)) show(saveInBackground(source), source, onDone)
                    addedBefore(held, source) -> show(saveInBackground(source), source, onDone)
                    else -> check = FolderCheck.Overlaps(held, same = held.samePlace(source))
                }
            } finally {
                submitting = false
            }
        }
    }

    /** [held], in the library, is the very folder added: the form adds it again, the source left unchanged. */
    private fun addedBefore(held: SourceEntity, source: SourceEntity) = folderId == null && held.samePlace(source)

    /** Says what saving [source] did ([outcome]), or leaves the form ([onDone]). */
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
            // Added before, when making the folders the source failed: the folder is there, its scan
            // asked; the source changes now. Already the folders, it is the same folder twice.
            is FolderSave.Overlaps -> if (addedBefore(result.existing, folder) && useFolders()) {
                Saved.Done(switched = true)
            } else {
                Saved.Overlaps(result.existing)
            }
        }
    }

    /** Tests [source] and says how it answered: true when it did. */
    private suspend fun reaches(source: SourceEntity): Boolean {
        testing = true
        check = null
        // The browser signs in of its own for a test: what the form says is what answers.
        val answer = suspendRunCatching { probe(source) }
        answer
            .onSuccess { count -> check = FolderCheck.Reachable(count) }
            .onFailure { e ->
                Log.w(TAG, "Test of ${source.host} failed", e)
                check = FolderCheck.Failed(ScanErrors.classify(e), source.host)
            }
        testing = false
        return answer.isSuccess
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

/**
 * Kept as plain values, which a saved state holds across a stopped process. Never the password: the
 * saved state is written to the device in clear.
 */
private fun SavedStateHandle.keep(form: FolderForm) {
    this[KIND] = form.kind.name
    this[PROTOCOL] = form.protocol.name
    this[HOST] = form.host
    this[SHARE] = form.share
    this[PATH] = form.path
    this[USER] = form.user
}

/** The form kept, its password empty; null when none was, or one a newer version wrote no longer reads. */
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
    )
}
