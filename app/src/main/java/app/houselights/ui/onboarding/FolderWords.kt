package app.houselights.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.util.wholePath

// The words the guided flow and « Modifier le dossier » share: what the NAS answered, what a folder holds, a folder
// that could not be read. Paths are kept whole ([wholePath]).

/** The NAS at [host] does not answer. */
@Composable
internal fun unreachableText(host: String): String = stringResource(R.string.error_nas_unreachable, host)

/** The NAS at [host] has no share [share]. */
@Composable
internal fun shareMissingText(host: String, share: String): String = stringResource(R.string.error_share_not_found, host, share)

/** The NAS has no NFS export [export]. */
@Composable
internal fun exportMissingText(export: String): String = stringResource(R.string.error_export_not_found, wholePath(export))

/** No folder [name] in [within]: an export's path, or a share's ([nfs] false), which is said as typed. */
@Composable
internal fun subfolderMissingText(within: String, name: String, nfs: Boolean): String = if (nfs) {
    stringResource(R.string.error_subfolder_not_found, wholePath(within), name)
} else {
    stringResource(R.string.error_subfolder_not_found_share, within, name)
}

/** The NFS [export] refuses this player, named by its address [player] when it has one. */
@Composable
internal fun playerRefusedText(player: String?, export: String): String = if (player != null) {
    stringResource(R.string.error_nfs_refused, player, wholePath(export))
} else {
    stringResource(R.string.error_nfs_refused_no_address, wholePath(export))
}

/** Reading [folder] is refused to the NFS user presented: the account [uid], else root. */
@Composable
internal fun readRefusedText(folder: String, uid: String?): String = if (uid != null) {
    stringResource(R.string.error_nfs_account_refused, uid, wholePath(folder))
} else {
    stringResource(R.string.error_nfs_root_refused, wholePath(folder))
}

/** The SMB account [user] may not open [share]. */
@Composable
internal fun accountDeniedText(user: String, share: String): String = stringResource(R.string.error_smb_denied, user, share)

/** What a folder's first level holds: « 412 dossiers et 3 vidéos », « 412 dossiers », « 3 vidéos ». */
@Composable
internal fun folderContentText(folders: Int, videos: Int): String {
    val folderCount = pluralStringResource(R.plurals.folder_count, folders, folders)
    val videoCount = pluralStringResource(R.plurals.video_count, videos, videos)
    return when {
        folders > 0 && videos > 0 -> stringResource(R.string.folder_content, folderCount, videoCount)
        folders > 0 -> folderCount
        else -> videoCount
    }
}

/**
 * In the form's place, a folder edited that could not be read: none of the form would be the folder's, and saving it
 * would erase the folder's password; Réessayer reads it again ([onReadAgain]), busy while [rereading].
 */
@Composable
internal fun UnreadFolder(rereading: Boolean, onReadAgain: () -> Unit, modifier: Modifier = Modifier) {
    HlEmptyState(
        stringResource(R.string.folder_unread),
        modifier,
        // The library is read from the player's storage, as the settings are.
        message = stringResource(R.string.error_settings),
        attention = true,
        action = stringResource(R.string.retry),
        actionIcon = HlIcons.Refresh,
        onAction = onReadAgain,
        busy = stringResource(R.string.retrying).takeIf { rereading },
    )
}
