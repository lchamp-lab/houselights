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
import app.houselights.ui.util.reported
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
        share.isBlank() -> if (protocol == Protocol.NFS) R.string.error_export_missing else R.string.error_share_missing
        else -> null
    }

    companion object {
        /** The form for editing [folder]: everything but its password, which is never shown. */
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
    /** [signOut]: the server the folders replace is signed out of, not kept. */
    constructor(app: AppContainer, folderId: Long?, signOut: Boolean, saved: SavedStateHandle) : this(
        library = app.library,
        folderId = folderId,
        background = app.scope,
        useFolders = {
            val switched = app.settings.state.value.source != SourceConfig.Local
            if (switched) app.sources.switchTo(SourceConfig.Local, keepServer = !signOut)
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

    /** The edited folder was removed from the library meanwhile: nothing can be saved. */
    var gone by mutableStateOf(false)
        private set

    /** The edited folder has a password, which an empty field keeps. */
    var keepsPassword by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            if (folderId != null) {
                // Unread, the folder stays unsaveable: an empty password field would erase its own.
                suspendRunCatching { library.folder(folderId) }
                    .onSuccess { folder ->
                        if (folder != null) {
                            editing = folder
                            form = kept ?: FolderForm.of(folder)
                            keepsPassword = folder.password.value.isNotEmpty()
                        } else {
                            gone = true
                        }
                        ready = true
                    }
                    .onFailure { check = FolderCheck.SaveFailed(it.reported(TAG, "Reading folder $folderId")) }
            } else if (kept == null) {
                // A second folder is most often on the same NAS: start from the last one added.
                suspendRunCatching { library.lastFolder() }
                    .onFailure { Log.w(TAG, "Reading the last folder failed", it) }
                    .getOrNull()
                    ?.let { last ->
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
            return null
        }
        return source()
    }

    /** Tests the folder and reports the result in [check]. */
    fun test() {
        if (busy || gone) return
        val typed = form
        val source = toTest() ?: return
        viewModelScope.launch { reaches(source, typed) }
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
        fun factory(app: AppContainer, folderId: Long?, signOut: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer { AddFolderViewModel(app, folderId, signOut, createSavedStateHandle()) }
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

/** Keeps the form as plain values across a killed process; never the password, as saved state is written in clear. */
private fun SavedStateHandle.keep(form: FolderForm) {
    this[KIND] = form.kind.name
    this[PROTOCOL] = form.protocol.name
    this[HOST] = form.host
    this[SHARE] = form.share
    this[PATH] = form.path
    this[USER] = form.user
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
    )
}
