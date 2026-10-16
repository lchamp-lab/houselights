package app.houselights.ui.onboarding

import android.util.Log
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
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.RemoteBrowser
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrors
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
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

/** How the folder answered a test. */
sealed interface FolderCheck {
    data class Incomplete(@StringRes val message: Int) : FolderCheck

    data class Reachable(val entries: Int) : FolderCheck

    data class Failed(val error: ScanError, val host: String) : FolderCheck
}

/** Adds a folder, or edits the folder [folderId]: the same form, filled in with it. */
class AddFolderViewModel(private val app: AppContainer, private val folderId: Long? = null) : ViewModel() {
    var form by mutableStateOf(FolderForm())
        private set
    var check by mutableStateOf<FolderCheck?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** The folder edited, as stored; null while it is read, and when a folder is added. */
    private var editing: SourceEntity? = null

    val edits = folderId != null

    /** The folder edited has a password, which a field left empty keeps. */
    var keepsPassword by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            if (folderId != null) {
                app.library.folder(folderId)?.let { folder ->
                    editing = folder
                    form = FolderForm.of(folder)
                    keepsPassword = folder.password.value.isNotEmpty()
                }
            } else {
                // A second folder is most often on the same NAS: start from the last one added.
                app.library.lastFolder()?.let { last ->
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

    /** Tests the folder; [then] receives the folder tested, once it answered. */
    fun test(then: ((SourceEntity) -> Unit)? = null) {
        if (busy) return
        form.missing()?.let {
            check = FolderCheck.Incomplete(it)
            return
        }
        busy = true
        check = null
        val source = source()
        viewModelScope.launch {
            suspendRunCatching { RemoteBrowser.of(source).test(source) }
                .onSuccess { count ->
                    check = FolderCheck.Reachable(count)
                    then?.invoke(source)
                }
                .onFailure { e ->
                    Log.w("AddFolder", "Test of ${source.host} failed", e)
                    check = FolderCheck.Failed(ScanErrors.classify(e), source.host)
                }
            busy = false
        }
    }

    /**
     * Tests the folder, then adds it to the library and makes the local library the source, or saves
     * the folder edited under its id: its titles keep what was watched and chosen. [onDone] hears
     * whether the source changed (it was a server, or nothing yet).
     */
    fun submit(onDone: (switched: Boolean) -> Unit) {
        // A folder to edit is saved only once read: an empty field would otherwise erase its password.
        if (edits && editing == null) return
        test { folder ->
            viewModelScope.launch {
                if (folderId != null) {
                    app.library.updateFolder(folderId, folder)
                    onDone(false)
                } else {
                    app.library.addFolder(folder)
                    val switched = app.settings.state.value.source != SourceConfig.Local
                    if (switched) app.sources.switchTo(SourceConfig.Local)
                    onDone(switched)
                }
            }
        }
    }
}
