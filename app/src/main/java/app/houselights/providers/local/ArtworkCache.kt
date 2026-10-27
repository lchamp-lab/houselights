package app.houselights.providers.local

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import app.houselights.core.suspendRunCatching
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/** True for artwork cached from the NAS ("file://..."), as opposed to a TMDB or NFO URL. */
internal fun String?.isCachedArtwork(): Boolean = this?.startsWith("file:") == true

data class CleanupResult(val deletedFiles: Int, val freedBytes: Long)

data class CacheUsage(val files: Int, val bytes: Long)

/** Caches NAS artwork in app storage for Coil, read in blocks and downscaled to [MAX_WIDTH] × [MAX_HEIGHT]. */
class ArtworkCache(
    private val dir: File,
    /** Usable bytes on the volume that holds the cache. */
    // What is free now, not what clearing other apps' caches would give.
    //noinspection UsableSpace
    private val freeSpace: () -> Long = { generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0 },
) {
    /**
     * "file://…" path of the cached copy ([version]); null when it cannot be copied, so TMDB is used. An unreachable
     * NAS or full device throws, stopping the scan; below [MIN_FREE_BYTES] free, nothing is copied.
     */
    suspend fun copy(source: SourceEntity, file: RemoteFile, browser: RemoteBrowser): String? {
        val ext = file.relativePath.substringAfterLast('.', "jpg").lowercase().let { if (it == "tbn") "jpg" else it }
        // Images over the size limit fall back to TMDB rather than being truncated.
        if (!holds(file)) return null
        val target = File(dir, "${source.id}/${hash(version(file))}.$ext")
        val uri = "file://${target.absolutePath}"
        if (target.exists()) return uri
        if (freeSpace() < file.size + MIN_FREE_BYTES) {
            Log.w(TAG, "Not copying ${file.relativePath}: the device is nearly full")
            return null
        }
        // Written to a temporary file then renamed, so an interrupted copy never looks cached.
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
            // Success, failure or cancellation between blocks: no partial file is left behind.
            partial.delete()
            smaller.delete()
        }
    }

    /**
     * Cache key: path, size and, when listed, date, which catches same-size rewrites and bypasses image caches. Without
     * a date, existing copies keep their names.
     */
    private fun version(file: RemoteFile): String = listOfNotNull(file.relativePath, file.size, file.lastModified).joinToString(":")

    private suspend fun stream(source: SourceEntity, file: RemoteFile, browser: RemoteBrowser, into: File) {
        FileOutputStream(into).use { out ->
            var offset = 0L
            while (offset < file.size) {
                val block = browser.readAt(source, file.relativePath, offset, minOf(BLOCK_BYTES.toLong(), file.size - offset).toInt())
                // Shorter than listed: the file was rewritten; keep what it holds now.
                if (block.isEmpty()) break
                out.write(block)
                offset += block.size
            }
        }
    }

    /**
     * Downscales [from] into [to] (PNG with alpha, else JPEG; decoders sniff, so the extension stays). False keeps the
     * original: it fits, cannot be decoded, or has an EXIF rotation, which Coil applies and re-encoding would lose.
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
        // Decode at the largest power-of-two subsample still at least the target size, then scale.
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        val decoded = BitmapFactory.decodeFile(from.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scaled = decoded.scale(targetWidth, targetHeight)
        if (scaled !== decoded) decoded.recycle()
        try {
            val format = if (scaled.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            FileOutputStream(to).use { check(scaled.compress(format, JPEG_QUALITY, it)) { "Could not encode ${to.name}" } }
        } finally {
            scaled.recycle()
        }
        return true
    }

    /** EXIF rotation or mirroring, via the platform reader, as AndroidX ExifInterface is not a dependency. */
    @SuppressLint("ExifInterface")
    private fun turned(file: File): Boolean {
        val upright = android.media.ExifInterface.ORIENTATION_NORMAL
        val orientation = runCatching {
            android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, upright)
        }.getOrDefault(upright)
        return orientation != upright && orientation != android.media.ExifInterface.ORIENTATION_UNDEFINED
    }

    /** Whether [file]'s size can be cached at all; empty or oversized images are left to TMDB. */
    fun holds(file: RemoteFile): Boolean = file.size in 1..MAX_BYTES.toLong()

    fun spaceLeft(): Long = freeSpace()

    fun usage(): CacheUsage {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return CacheUsage(files.size, files.sumOf { it.length() })
    }

    /** Deletes cached files not in [referenced], then empty folders; never touches anything outside the cache. */
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

        /** Bytes read from the NAS, and held in memory, per request. */
        private const val BLOCK_BYTES = 256 shl 10

        /** Largest displayed size: a full-screen backdrop at 1080p, the Zidoo's UI resolution. */
        private const val MAX_WIDTH = 1920
        private const val MAX_HEIGHT = 1080

        private const val JPEG_QUALITY = 90

        /** Free space kept when caching, for the database to grow and for the system. */
        const val MIN_FREE_BYTES = 200L shl 20

        /** A cached file's name: the first 24 hex digits of its source's SHA-1, 96 bits. */
        private const val HASH_DIGITS = 24

        private fun hash(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }.take(HASH_DIGITS)
    }
}
