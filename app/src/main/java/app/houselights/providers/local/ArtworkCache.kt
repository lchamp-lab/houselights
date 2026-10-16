package app.houselights.providers.local

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import app.houselights.core.suspendRunCatching
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.security.MessageDigest
import kotlin.math.roundToInt

/** True for artwork copied from the NAS into the cache ("file://..."), rather than a TMDB or NFO address. */
internal fun String?.isCachedArtwork(): Boolean = this?.startsWith("file:") == true

/** What a cleanup removed. */
data class CleanupResult(val deletedFiles: Int, val freedBytes: Long)

/** Files and bytes held by the cache. */
data class CacheUsage(val files: Int, val bytes: Long)

/**
 * Local artwork copied once from the NAS into app storage, so Coil can load it like any image: a
 * block at a time, and no larger than the screen shows it ([MAX_WIDTH] × [MAX_HEIGHT]). Copies no
 * longer referenced by the library are removed by [sweep].
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
        val smaller = File(target.parentFile, "${target.name}.small.part")
        return try {
            suspendRunCatching {
                target.parentFile?.mkdirs()
                stream(source, file, browser, partial)
                val kept = if (shrink(partial, smaller)) smaller else partial
                check(kept.renameTo(target)) { "Could not store ${target.name}" }
                uri
            }.getOrElse { e ->
                if (ScanErrors.isUnreachable(e) || ScanErrors.classify(e).kind == ScanErrorKind.STORAGE_FULL) throw e
                Log.w(TAG, "Could not copy ${file.relativePath}", e)
                null
            }
        } finally {
            // Copied, failed, or cut short by the scan stopping between two blocks: nothing half written stays.
            partial.delete()
            smaller.delete()
        }
    }

    /** Copies [file] into [into] a block at a time ([BLOCK_BYTES]): never more of it in memory at once. */
    private suspend fun stream(source: SourceEntity, file: RemoteFile, browser: RemoteBrowser, into: File) {
        FileOutputStream(into).use { out ->
            var offset = 0L
            while (offset < file.size) {
                val block = browser.readAt(source, file.relativePath, offset, minOf(BLOCK_BYTES.toLong(), file.size - offset).toInt())
                // Shorter than listed: rewritten since, and copied as it is now.
                if (block.isEmpty()) break
                out.write(block)
                offset += block.size
            }
        }
    }

    /**
     * Writes [from] into [to] at the largest size the screen shows it, when it is larger: a 4K fanart,
     * a poster of 3000 px. A picture with transparency (a clear logo) stays a PNG, the others become
     * JPEGs; the name keeps the extension the NAS gave it, as decoders read what the bytes are. False
     * when it fits as it is, is no picture Android reads, or is turned by its EXIF orientation (a
     * phone's photo for a home video, which Coil shows upright and a new encoding would lose): it is
     * kept byte for byte.
     */
    private fun shrink(from: File, to: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(from.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        val scale = minOf(MAX_WIDTH.toFloat() / width, MAX_HEIGHT.toFloat() / height)
        if (width <= 0 || height <= 0 || scale >= 1f) return false
        if (turned(from)) return false
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        // Decoded at the smallest power-of-two fraction still as large as the target, then scaled to it.
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        val decoded = BitmapFactory.decodeFile(from.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        if (scaled !== decoded) decoded.recycle()
        try {
            val format = if (scaled.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            FileOutputStream(to).use { check(scaled.compress(format, JPEG_QUALITY, it)) { "Could not encode ${to.name}" } }
        } finally {
            scaled.recycle()
        }
        return true
    }

    /**
     * True when [file]'s EXIF says it is shown turned or mirrored; a picture without EXIF, or none it
     * reads, is not. Read by the platform's reader: the support library's is no dependency of the app.
     */
    @SuppressLint("ExifInterface")
    private fun turned(file: File): Boolean {
        val upright = android.media.ExifInterface.ORIENTATION_NORMAL
        val orientation = runCatching {
            android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, upright)
        }.getOrDefault(upright)
        return orientation != upright && orientation != android.media.ExifInterface.ORIENTATION_UNDEFINED
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

        /** How much of a picture is asked of the NAS, and held, at once. */
        private const val BLOCK_BYTES = 256 shl 10

        /** The largest a picture is shown: a backdrop over the whole of a 1080p screen, the Zidoo's interface. */
        private const val MAX_WIDTH = 1920
        private const val MAX_HEIGHT = 1080

        /** A picture made smaller is encoded once: as close to the original as the eye can tell. */
        private const val JPEG_QUALITY = 90

        /** Left free when copying an image: room for the library's database to grow, and for the system. */
        const val MIN_FREE_BYTES = 200L shl 20

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }.take(24)
    }
}
