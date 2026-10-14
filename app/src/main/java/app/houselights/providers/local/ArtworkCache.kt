package app.houselights.providers.local

import app.houselights.core.suspendRunCatching
import java.io.File
import java.util.Locale
import java.security.MessageDigest

/** True for artwork copied from the NAS into the cache ("file://..."), rather than a TMDB or NFO address. */
internal fun String?.isCachedArtwork(): Boolean = this?.startsWith("file:") == true

/** What a cleanup removed. */
data class CleanupResult(val deletedFiles: Int, val freedBytes: Long)

/** Files and bytes held by the cache. */
data class CacheUsage(val files: Int, val bytes: Long)

/**
 * Local artwork copied once from the NAS into app storage, so Coil can load it like any image.
 * Copies no longer referenced by the library are removed by [sweep].
 */
class ArtworkCache(private val dir: File) {
    /** "file://…" path of the cached copy; read again only when the file changes size. */
    suspend fun copy(source: SourceEntity, file: RemoteFile): String? = suspendRunCatching {
        val ext = file.relativePath.substringAfterLast('.', "jpg").lowercase().let { if (it == "tbn") "jpg" else it }
        // An image too large for the cache is left to TMDB rather than kept cut off.
        if (file.size !in 1..MAX_BYTES.toLong()) return null
        val target = File(dir, "${source.id}/${hash("${file.relativePath}:${file.size}")}.$ext")
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            val bytes = RemoteBrowser.of(source).readPrefix(source, file.relativePath, file.size.toInt())
            // Written aside then renamed: an interrupted copy never passes for a cached image.
            val partial = File(target.parentFile, "${target.name}.part")
            partial.writeBytes(bytes)
            if (!partial.renameTo(target)) {
                partial.delete()
                error("Could not store ${target.name}")
            }
        }
        "file://${target.absolutePath}"
    }.getOrNull()

    fun usage(): CacheUsage {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return CacheUsage(files.size, files.sumOf { it.length() })
    }

    /**
     * Deletes every cached file that [referenced] ("file://…" paths, as stored in the
     * library) does not mention, then the folders left empty. Never touches anything
     * outside the cache folder.
     */
    fun sweep(referenced: Collection<String>): CleanupResult {
        if (!dir.isDirectory) return CleanupResult(0, 0)
        val root = dir.canonicalFile
        val keep = referenced.mapNotNull { path ->
            runCatching { File(path.removePrefix("file://")).canonicalPath }.getOrNull()
        }.toSet()
        var deleted = 0
        var freed = 0L
        root.walkBottomUp().forEach { file ->
            when {
                file == root -> Unit
                !file.canonicalPath.startsWith(root.path + File.separator) -> Unit
                file.isFile && file.canonicalPath !in keep -> {
                    val size = file.length()
                    if (file.delete()) {
                        deleted++
                        freed += size
                    }
                }
                file.isDirectory && file.list().isNullOrEmpty() -> file.delete()
            }
        }
        return CleanupResult(deleted, freed)
    }

    companion object {
        private const val MAX_BYTES = 30 shl 20

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }.take(24)
    }
}
