package app.houselights.providers.local

import android.util.Log
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
class ArtworkCache(
    private val dir: File,
    /** The bytes the device can still write where the cache lives. */
    private val freeSpace: () -> Long = { generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0 },
) {
    /**
     * "file://…" path of the cached copy; read again only when the file changes size. Null when the
     * image cannot be copied: it is left to TMDB, and logged. A NAS that stops answering throws, as
     * for every read of a scan, so the scan stops instead of waiting on each image; so does a device
     * out of space. Nearly out of it ([MIN_FREE_BYTES]), the image is not copied: the space left is
     * the library's, and the system's.
     */
    suspend fun copy(source: SourceEntity, file: RemoteFile, browser: RemoteBrowser): String? {
        val ext = file.relativePath.substringAfterLast('.', "jpg").lowercase().let { if (it == "tbn") "jpg" else it }
        // An image too large for the cache is left to TMDB rather than kept cut off.
        if (!holds(file)) return null
        val target = File(dir, "${source.id}/${hash("${file.relativePath}:${file.size}")}.$ext")
        val uri = "file://${target.absolutePath}"
        if (target.exists()) return uri
        if (freeSpace() < file.size + MIN_FREE_BYTES) {
            Log.w(TAG, "Not copying ${file.relativePath}: the device is nearly full")
            return null
        }
        // Written aside then renamed: an interrupted copy never passes for a cached image.
        val partial = File(target.parentFile, "${target.name}.part")
        return suspendRunCatching {
            target.parentFile?.mkdirs()
            val bytes = browser.readPrefix(source, file.relativePath, file.size.toInt())
            partial.writeBytes(bytes)
            check(partial.renameTo(target)) { "Could not store ${target.name}" }
            uri
        }.getOrElse { e ->
            partial.delete()
            if (ScanErrors.isUnreachable(e) || ScanErrors.classify(e).kind == ScanErrorKind.STORAGE_FULL) throw e
            Log.w(TAG, "Could not copy ${file.relativePath}", e)
            null
        }
    }

    /** Whether an image of [file]'s size is copied at all: an empty one, or one too large, is left to TMDB for good. */
    fun holds(file: RemoteFile): Boolean = file.size in 1..MAX_BYTES.toLong()

    /** The bytes the device can still write, where the cache and the library live. */
    fun spaceLeft(): Long = freeSpace()

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
        private const val TAG = "ArtworkCache"
        private const val MAX_BYTES = 30 shl 20

        /** Left free when copying an image: room for the library's database to grow, and for the system. */
        const val MIN_FREE_BYTES = 200L shl 20

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }.take(24)
    }
}
