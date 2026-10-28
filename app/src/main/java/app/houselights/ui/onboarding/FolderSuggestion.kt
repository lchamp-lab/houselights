package app.houselights.ui.onboarding

import app.houselights.providers.local.ExportedFolder
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.TypedPaths

/**
 * A place on the NAS that a folder's path it lacks may mean (#59): [share], an NFS export's path from the NAS's root or
 * an SMB share's name, and the folder [path] inside it, both as a folder keeps them ([SourceEntity.share], [SourceEntity.path]);
 * [named], the share or folder whose name it changes, null when it gives the export instead; [only] when nothing near
 * was typed and it is the one folder the NAS shares (« Le seul dossier partagé du NAS »).
 */
data class FolderSuggestion(val share: String, val path: String, val named: String? = null, val only: Boolean = false) {
    /** The export it gives, from the NAS's root, as « Utiliser /volume1/media » names it. */
    val export: String get() = "/" + share.trim('/')
}

/**
 * « Choisir un dossier partagé » (#59): the guided flow's step 4 opened over the form of the library's [folder], on the
 * [exports] its test just read from the NAS at [host], each with the clients it allows, the NFS account typed in the
 * form ([uid], [gid], both empty for root) in its panel; the export chosen goes back to the form, which tests it.
 */
data class SharePick(val folder: Long, val host: String, val exports: List<ExportedFolder>, val uid: String = "", val gid: String = "")

/** Each of [exports]' clients, a space apart (none holds one), as a route or a saved state keeps them ([exportsOf]). */
internal fun clientsOf(exports: List<ExportedFolder>): List<String> = exports.map { it.groups.joinToString(" ") }

/** The exports at [paths], each with the clients [clientsOf] wrote at its place in [clients]. */
internal fun exportsOf(paths: List<String>, clients: List<String>): List<ExportedFolder> =
    paths.mapIndexed { i, path -> ExportedFolder(path, clients.getOrNull(i).orEmpty().split(' ').filter(String::isNotEmpty)) }

/**
 * What the NAS has that [source]'s path, which [trouble] says it lacks, may mean, read from [network] as « Ajouter un
 * dossier » reads it: for an NFS export it does not have, the export it lists whose path the one typed is the end or the
 * start of, case and accents aside ("/volume1/media" for « /media », [TypedPaths.meant]); for an SMB share, or a folder
 * inside the shared folder, the one at that level spelled alike or within one letter of it ([TypedPaths.closest]),
 * [FolderSuggestion.named] by it. Null when nothing is near; throws when the NAS cannot say.
 */
internal suspend fun suggestionFor(source: SourceEntity, trouble: FolderTrouble, network: FlowNetwork): FolderSuggestion? =
    when (trouble) {
        is FolderTrouble.ExportMissing -> exportMeant(source, network)
        is FolderTrouble.ShareMissing -> TypedPaths.closest(source.share, network.shares(source).map { it.name })
            ?.let { FolderSuggestion(it, source.path.trim('/'), named = it) }
        is FolderTrouble.SubfolderMissing -> folderMeant(source, network)
        else -> null
    }

/** The export [source]'s path may mean among those its NAS lists, the rest of the path inside it. */
private suspend fun exportMeant(source: SourceEntity, network: FlowNetwork): FolderSuggestion? {
    val names = TypedPaths.names(source.share, Protocol.NFS) + TypedPaths.names(source.path, Protocol.NFS)
    val listed = network.exports(source.host).map { it.path }
    return TypedPaths.meant(names, listed).firstOrNull()?.let { FolderSuggestion(it.share, it.path) }
}

/**
 * The folder [source]'s path may mean in its shared folder, read a level at a time from the shared folder's root: at the
 * first name the level lacks (SMB ignoring case), the one there within one letter of it, the names after it kept.
 */
private suspend fun folderMeant(source: SourceEntity, network: FlowNetwork): FolderSuggestion? {
    val smb = source.protocol == Protocol.SMB
    val names = TypedPaths.names(source.path, source.protocol)
    val shared = source.copy(path = "")
    for (i in names.indices) {
        val level = network.level(shared, names.take(i).joinToString("/")).folders
        val name = names[i]
        if (level.none { it == name || smb && it.equals(name, ignoreCase = true) }) {
            val near = TypedPaths.closest(name, level) ?: return null
            return FolderSuggestion(source.share.trim('/'), (names.take(i) + near + names.drop(i + 1)).joinToString("/"), near)
        }
    }
    return null
}
