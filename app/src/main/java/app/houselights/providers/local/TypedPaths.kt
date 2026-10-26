package app.houselights.providers.local

import app.houselights.core.FailureKind
import app.houselights.core.SortKeys
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import java.io.FileNotFoundException

/** A path typed whole in the add-folder flow ([TypedPaths.read]): the NAS when it names one, its protocol when its form says. */
data class TypedPath(
    /** "NAS" in "\\NAS\video", "nas" in "nfs://nas/…" or "nas:/…"; null for a path alone ("/volume1/video"). */
    val host: String?,
    /** SMB for "\\NAS\…", "//NAS/…" and "smb://", NFS for "nfs://" and "nas:/…"; null when the form does not say. */
    val protocol: Protocol?,
    /** Its folders' names from the NAS's root, as typed: ["volume1", "video", "Films"]. */
    val names: List<String>,
)

/** Where a folder is on its NAS: its share or export as a source keeps it (no slash around), and the folder inside. */
data class SharePath(val share: String, val path: String)

/**
 * A full path typed in the add-folder flow, split by the app into its NAS, its share or export, and the folder inside,
 * so that no one has to know where an export ends (#79): with the NAS's list of exports or shares when it gave one,
 * else for SMB at its first name, a share's, and for NFS at the longest start of the path that mounts.
 */
object TypedPaths {
    /** At most this many starts of a path are mounted to find its export, from the longest: deeper than any NAS's. */
    const val MAX_TRIES = 6

    private val URL = Regex("""^(smb|nfs)://([^/\\]*)(.*)$""", RegexOption.IGNORE_CASE)
    private val UNC = Regex("""^(?:\\\\|//)([^/\\]*)(.*)$""")

    /** "nas:/volume1/video", as `showmount` and fstab write an export; never a Windows drive ("C:\"). */
    private val EXPORT = Regex("""^([^/\\:\s]+):(/.*)$""")

    /**
     * What [typed] says: "/volume1/video/Films" or "video/Films" alone, "\\NAS\video\Films" or "//NAS/video/Films",
     * "smb://nas/video/Films" (an account or port in it dropped), "nfs://nas/volume1/video/Films" or
     * "nas:/volume1/video/Films"; "/" and "\" both separate names, empty ones and "." left out. Null when blank.
     */
    fun read(typed: String): TypedPath? {
        val text = typed.trim()
        if (text.isEmpty()) return null
        val url = URL.matchEntire(text)
        val unc = UNC.matchEntire(text)
        val export = EXPORT.matchEntire(text)
        return when {
            url != null -> {
                val (scheme, authority, path) = url.destructured
                val host = authority.substringAfterLast('@').substringBefore(':')
                TypedPath(host.ifEmpty { null }, Protocol.valueOf(scheme.uppercase()), names(path))
            }
            unc != null -> TypedPath(unc.groupValues[1].ifEmpty { null }, Protocol.SMB, names(unc.groupValues[2]))
            export != null -> TypedPath(export.groupValues[1], Protocol.NFS, names(export.groupValues[2]))
            else -> TypedPath(null, null, names(text))
        }
    }

    /**
     * Where [names] are on [protocol]'s NAS: at the longest of [known], its exports' paths or its shares' names when it
     * gave them ([split]); else SMB's share is the first name and NFS's export the longest start that [mounts]
     * ([mounting]). Null when there is no name, or none of [known] starts the path ([meant] may say what was meant).
     */
    suspend fun locate(
        names: List<String>,
        protocol: Protocol,
        known: List<String>?,
        mounts: suspend (export: String) -> Unit,
    ): SharePath? = when {
        names.isEmpty() -> null
        known != null -> split(names, known, protocol)
        protocol == Protocol.SMB -> SharePath(names.first(), names.drop(1).joinToString("/"))
        else -> mounting(names, mounts)
    }

    /**
     * [names] split at the longest of [known] (NFS exports' paths, "/" the server's root; SMB shares' names) that
     * starts them, SMB's compared ignoring case and kept as the NAS spells it; null when none does.
     */
    fun split(names: List<String>, known: List<String>, protocol: Protocol): SharePath? {
        val smb = protocol == Protocol.SMB
        return known.map(::names)
            .filter { start -> (start.isNotEmpty() || !smb) && starts(names, start, ignoreCase = smb) }
            .maxByOrNull { it.size }
            ?.let { start -> SharePath(start.joinToString("/"), names.drop(start.size).joinToString("/")) }
    }

    /**
     * NFS without the exports' list: the longest start of [names] that [mounts] accepts (it throws when the server
     * refuses one), tried from the longest, [MAX_TRIES] at most. A server out of reach, or refusing the account, ends
     * the tries at once; when none mounts, the longest's failure is thrown, which says what is wrong with the path
     * typed: not found for a misspelled name, refused for an export not open to this player.
     */
    suspend fun mounting(names: List<String>, mounts: suspend (export: String) -> Unit): SharePath {
        var longest: Throwable? = null
        for (length in names.size downTo maxOf(1, names.size - MAX_TRIES + 1)) {
            val export = names.take(length).joinToString("/")
            val failure = suspendRunCatching { mounts("/$export") }.exceptionOrNull()
                ?: return SharePath(export, names.drop(length).joinToString("/"))
            if (failure.failureKind() in FINAL) throw failure
            if (longest == null) longest = failure
        }
        throw longest ?: FileNotFoundException("No folder named")
    }

    /**
     * What [names] may have meant when none of [exports] starts them (#59), case and accents aside: an export starting
     * the path but for them ("/volume1/Video/Films"), else one whose last name is the path's first, the path put after
     * it ("/volume1/video" and "Films" for "/video/Films", "/volume1/media" for "media").
     */
    fun meant(names: List<String>, exports: List<String>): List<SharePath> {
        val folded = names.map(SortKeys::fold)
        return exports.map(::names).filter { it.isNotEmpty() }.mapNotNull { export ->
            val starting = export.size <= names.size && export.map(SortKeys::fold) == folded.take(export.size)
            val rest = when {
                starting -> names.drop(export.size)
                SortKeys.fold(export.last()) == folded.firstOrNull() -> names.drop(1)
                else -> return@mapNotNull null
            }
            SharePath(export.joinToString("/"), rest.joinToString("/"))
        }.distinct()
    }

    /** Failures no other start of the path would change. */
    private val FINAL = setOf(FailureKind.UNREACHABLE, FailureKind.LOGIN_FAILED)

    private fun names(path: String) = path.split('/', '\\').filter { it.isNotEmpty() && it != "." }

    private fun starts(names: List<String>, start: List<String>, ignoreCase: Boolean) =
        start.size <= names.size && start.indices.all { names[it].equals(start[it], ignoreCase) }
}
