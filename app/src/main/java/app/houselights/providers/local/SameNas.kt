package app.houselights.providers.local

import java.util.Locale

/**
 * How a folder the add-folder flow shows stands to the library's folders, said on its row before anything is chosen
 * ([standing]): apart from them, one of them, holding some, or inside one.
 */
sealed interface Standing {
    data object Apart : Standing

    /** It is [folder], however spelled: « Déjà dans la bibliothèque ». */
    data class Added(val folder: SourceEntity) : Standing

    /** It holds [folders], which their titles would show twice: « Contient « Films » ». */
    data class Holds(val folders: List<SourceEntity>) : Standing

    /** It is inside [folder], whose titles it would show again: « Dans « Video » ». */
    data class Inside(val folder: SourceEntity) : Standing
}

/**
 * How this folder stands to [folders], as adding it would find ([LocalLibrary.addFolder], [overlaps]): the one it is,
 * else the one it is inside, else those it holds, by the same protocol on the same NAS.
 */
fun SourceEntity.standing(folders: List<SourceEntity>): Standing {
    val overlapping = folders.filter { overlaps(it) }
    overlapping.firstOrNull { samePlace(it) }?.let { return Standing.Added(it) }
    val mine = place().size
    overlapping.firstOrNull { it.place().size < mine }?.let { return Standing.Inside(it) }
    return if (overlapping.isEmpty()) Standing.Apart else Standing.Holds(overlapping)
}

/** [sources] on the NAS at [host], however spelled (case, spaces), the latest added first: what a new folder there reuses. */
internal fun onNas(sources: List<SourceEntity>, host: String): List<SourceEntity> {
    val nas = host.trim().lowercase(Locale.ROOT)
    return sources.filter { it.host.trim().lowercase(Locale.ROOT) == nas }.sortedByDescending { it.id }
}

/**
 * The latest SMB folder of [folders] (one NAS's, [onNas]): the account another folder on it signs in with, its password
 * reused and never shown, or a guest when its user is blank; null when none of them is SMB.
 */
fun List<SourceEntity>.smbAccess(): SourceEntity? = firstOrNull { it.protocol == Protocol.SMB }

/** The user and group the latest NFS folder of [folders] presents to its NAS; null for root, or without an NFS folder. */
fun List<SourceEntity>.nfsIdentity(): Pair<Int, Int>? {
    val latest = firstOrNull { it.protocol == Protocol.NFS } ?: return null
    val user = latest.uid ?: return null
    val group = latest.gid ?: return null
    return user to group
}
