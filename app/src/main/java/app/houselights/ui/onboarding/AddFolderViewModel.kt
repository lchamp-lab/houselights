package app.houselights.ui.onboarding

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

    /** What the form lacks before the network is worth trying, so the message speaks about the form. */
    @StringRes
    fun missing(): Int? = when {
        host.isBlank() -> R.string.error_host_missing
        share.isBlank() -> if (protocol == Protocol.NFS) R.string.error_export_missing else R.string.error_share_missing
        else -> null
    }
}

/** How the folder answered a test. */
sealed interface FolderCheck {
    data class Incomplete(@StringRes val message: Int) : FolderCheck

    data class Reachable(val entries: Int) : FolderCheck

    data class Failed(val error: ScanError, val host: String) : FolderCheck
}

class AddFolderViewModel(private val app: AppContainer) : ViewModel() {
    var form by mutableStateOf(FolderForm())
        private set
    var check by mutableStateOf<FolderCheck?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    init {
        // A second folder is most often on the same NAS: start from the last one added.
        viewModelScope.launch {
            app.library.lastFolder()?.let { last ->
                form = form.copy(
                    protocol = last.protocol,
                    host = last.host,
                    share = if (last.protocol == Protocol.NFS) "/${last.share}" else last.share,
                    user = last.username,
                )
            }
        }
    }

    /** A change makes a successful test stale; a problem stays shown until the next test. */
    fun edit(change: FolderForm.() -> FolderForm) {
        val before = form
        form = form.change()
        check = if (form.protocol != before.protocol) null else check.takeUnless { it is FolderCheck.Reachable }
    }

    fun test(then: (() -> Unit)? = null) {
        if (busy) return
        form.missing()?.let {
            check = FolderCheck.Incomplete(it)
            return
        }
        busy = true
        check = null
        val source = form.toSource()
        viewModelScope.launch {
            suspendRunCatching { RemoteBrowser.of(source).test(source) }
                .onSuccess { count ->
                    check = FolderCheck.Reachable(count)
                    then?.invoke()
                }
                .onFailure { e -> check = FolderCheck.Failed(ScanErrors.classify(e), source.host) }
            busy = false
        }
    }

    /** Tests the folder, then adds it to the library and makes the local library the source. */
    fun add(onAdded: () -> Unit) = test {
        viewModelScope.launch {
            app.library.addFolder(form.toSource())
            app.settings.setSource(SourceConfig.Local)
            onAdded()
        }
    }
}
