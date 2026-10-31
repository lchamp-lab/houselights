package app.houselights.providers.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** On Robolectric's native graphics: pictures are encoded and decoded as on the device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val source = nfsFolder(id = 1)

    private fun file(dir: File, path: String, size: Int) = File(dir, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(size))
    }

    /** A picture [width] × [height], as a NAS holds it. */
    private fun picture(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        alpha: Boolean = false,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (alpha) Color.argb(128, 200, 40, 40) else Color.rgb(200, 40, 40))
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    /** Copies [bytes], stored at [path] on [nas], into a fresh cache, and returns the copy. */
    private fun copied(path: String, bytes: ByteArray, nas: FakeRemoteBrowser = FakeRemoteBrowser()): File? = runBlocking {
        nas.put(path, size = bytes.size.toLong(), bytes = bytes)
        ArtworkCache(temp.newFolder()).copy(source, RemoteFile(path, bytes.size.toLong()), nas)?.let { File(it.removePrefix("file://")) }
    }

    private fun size(file: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeFile(file.path, it) }
        .let { it.outWidth to it.outHeight }

    @Test
    fun sweepKeepsReferencedFilesOnly() {
        val dir = temp.newFolder("artwork")
        val kept = file(dir, "1/kept.jpg", 100)
        val stale = file(dir, "1/stale.jpg", 200)
        val removedSource = file(dir, "2/old.png", 300)
        val outside = file(temp.root, "outside.jpg", 50)

        val result = ArtworkCache(dir).sweep(listOf("file://${kept.absolutePath}", "file://${outside.absolutePath}"))

        assertTrue(kept.exists())
        assertFalse(stale.exists())
        assertFalse(removedSource.exists())
        assertFalse("empty source folders go too", File(dir, "2").exists())
        assertTrue("never touches files outside the cache", outside.exists())
        assertEquals(CleanupResult(2, 500), result)
        assertEquals(CacheUsage(1, 100), ArtworkCache(dir).usage())
    }

    @Test
    fun aCacheFolderNotThereYetIsSweptWithoutFailing() {
        assertEquals(CleanupResult(0, 0), ArtworkCache(File(temp.root, "none")).sweep(emptyList()))
    }

    @Test
    fun theFirstImageIsCopiedThoughTheCacheFolderIsNotThereYet() = runBlocking {
        // A folder that does not exist has no space at all, as far as File.usableSpace goes.
        val cache = ArtworkCache(File(temp.root, "artwork"))
        val bytes = picture(10, 15)
        val nas = FakeRemoteBrowser().apply { put("Dune (2021)-poster.jpg", size = bytes.size.toLong(), bytes = bytes) }
        val copied = cache.copy(source, RemoteFile("Dune (2021)-poster.jpg", bytes.size.toLong()), nas)
        assertTrue(copied.orEmpty(), copied?.let { File(it.removePrefix("file://")).isFile } == true)
    }

    /** A copy no row references yet, made for the file a scan reads, is spared by a sweep until the scan lets it go. */
    @Test
    fun aCopyIsSweptOnlyOnceLetGo() = runBlocking {
        val cache = ArtworkCache(temp.newFolder("artwork"))
        val bytes = picture(10, 15)
        val nas = FakeRemoteBrowser().apply { put("Dune (2021)-poster.jpg", size = bytes.size.toLong(), bytes = bytes) }
        val copied = cache.copy(source, RemoteFile("Dune (2021)-poster.jpg", bytes.size.toLong()), nas)
        val copy = File(checkNotNull(copied).removePrefix("file://"))

        assertEquals(CleanupResult(0, 0), cache.sweep(emptyList()))
        assertTrue(copy.isFile)
        cache.letGo()
        assertEquals(1, cache.sweep(emptyList()).deletedFiles)
        assertFalse(copy.exists())
    }

    /**
     * Android keeps a twentieth of a small volume for itself, 500 MiB at most, and says storage is low below it: nothing is
     * copied into it, nor into the cache's own margin above it, TMDB showing the picture.
     */
    @Test
    fun theSystemsReserveIsLeftFree() = runBlocking {
        val volume = 8_000_000_000L
        var free = volume / 20 + ArtworkCache.MIN_FREE_BYTES
        val cache = ArtworkCache(temp.newFolder(), totalSpace = { volume }) { free }
        val bytes = picture(10, 15)
        val nas = FakeRemoteBrowser().apply { put("Dune (2021)-poster.jpg", size = bytes.size.toLong(), bytes = bytes) }
        val poster = RemoteFile("Dune (2021)-poster.jpg", bytes.size.toLong())
        assertNull("400 MB kept for Android, 200 MiB for the cache", cache.copy(source, poster, nas))

        free += bytes.size
        assertTrue(cache.copy(source, poster, nas).isCachedArtwork())
        assertEquals("a large volume keeps 500 MiB", 500L shl 20, ArtworkCache.systemReserve(64_000_000_000L))
    }

    /** Same size, new date: copied again under a new name, so the screens' image caches never show the old one. */
    @Test
    fun aPictureRewrittenWithTheSameSizeIsCopiedAgain() = runBlocking {
        val cache = ArtworkCache(temp.newFolder())
        val nas = FakeRemoteBrowser()
        val before = picture(10, 15).copyOf(1_000)
        val after = picture(12, 18).copyOf(1_000)
        nas.put("Dune (2021)/poster.jpg", size = 1_000, bytes = before, lastModified = 1_760_000_000_000)
        val first = cache.copy(source, RemoteFile("Dune (2021)/poster.jpg", 1_000, lastModified = 1_760_000_000_000), nas)
        nas.put("Dune (2021)/poster.jpg", size = 1_000, bytes = after, lastModified = 1_760_000_600_000)
        val second = cache.copy(source, RemoteFile("Dune (2021)/poster.jpg", 1_000, lastModified = 1_760_000_600_000), nas)
        assertNotEquals(first, second)
        assertArrayEquals(after, second?.let { File(it.removePrefix("file://")).readBytes() })
    }

    @Test
    fun aPictureUnchangedIsNotCopiedAgain() = runBlocking {
        val cache = ArtworkCache(temp.newFolder())
        val poster = RemoteFile("Dune (2021)/poster.jpg", 1_000, lastModified = 1_760_000_000_000)
        val nas = FakeRemoteBrowser()
        nas.put(poster.relativePath, size = poster.size, bytes = picture(10, 15).copyOf(1_000), lastModified = poster.lastModified)
        val first = cache.copy(source, poster, nas)
        val read = nas.reads.size
        assertEquals(first, cache.copy(source, poster, nas))
        assertEquals(read, nas.reads.size)
    }

    /**
     * A poster.jpg that is no picture (a web page saved under its name) is left to TMDB, never copied, and not read again
     * until it is rewritten.
     */
    @Test
    fun aSidecarThatIsNoPictureLeavesItToTmdb() = runBlocking {
        val cache = ArtworkCache(temp.newFolder())
        val page = "<!DOCTYPE html><html><body>Not found</body></html>".toByteArray()
        val poster = RemoteFile("Dune (2021)/poster.jpg", page.size.toLong(), lastModified = 1_760_000_000_000)
        val nas = FakeRemoteBrowser().apply {
            put(
                poster.relativePath,
                size = poster.size,
                bytes = page,
                lastModified = poster.lastModified,
            )
        }
        assertNull(cache.copy(source, poster, nas))
        assertFalse("never cached", cache.holds(poster))
        val read = nas.reads.size
        assertNull(cache.copy(source, poster, nas))
        assertEquals("not read again", read, nas.reads.size)

        val rewritten = picture(10, 15)
        val fixed = RemoteFile(poster.relativePath, rewritten.size.toLong(), lastModified = 1_760_000_600_000)
        nas.put(fixed.relativePath, size = fixed.size, bytes = rewritten, lastModified = fixed.lastModified)
        assertTrue(cache.copy(source, fixed, nas).isCachedArtwork())
    }

    /** A fanart of several megabytes is never held whole in memory: it is copied a block at a time to the file. */
    @Test
    fun aLargePictureIsCopiedABlockAtATime() {
        // A picture followed by what its decoders never read.
        val bytes = picture(16, 9).copyOf(1_000_000)
        val asked = mutableListOf<Int>()
        val nas = FakeRemoteBrowser().apply { onRead = { _, _, length -> asked += length } }
        val copy = copied("Dune (2021)/fanart.jpg", bytes, nas)
        assertArrayEquals(bytes, copy?.readBytes())
        assertTrue(asked.toString(), asked.size > 1 && asked.all { it <= 256 * 1024 })
    }

    /** A 4K fanart is kept at the size a 1080p screen shows it: no more is ever decoded for it. */
    @Test
    fun aPictureLargerThanTheScreenIsKeptAtTheScreensSize() {
        assertEquals(1920 to 1080, size(copied("Dune (2021)/fanart.jpg", picture(3840, 2160))!!))
    }

    @Test
    fun aTallPosterKeepsItsShape() {
        assertEquals(720 to 1080, size(copied("Dune (2021)/poster.jpg", picture(2000, 3000))!!))
    }

    /** A clear logo stays clear: it is made smaller as a PNG, its transparency kept. */
    @Test
    fun aLargeLogoKeepsItsTransparency() {
        val copy = copied("Dune (2021)/clearlogo.png", picture(3000, 1000, Bitmap.CompressFormat.PNG, alpha = true))!!
        assertEquals(1920 to 640, size(copy))
        assertTrue(BitmapFactory.decodeFile(copy.path).hasAlpha())
    }

    @Test
    fun aPictureThatFitsIsCopiedAsItIs() {
        val logo = picture(800, 310, Bitmap.CompressFormat.PNG, alpha = true)
        assertArrayEquals(logo, copied("Dune (2021)/clearlogo.png", logo)?.readBytes())
    }

    /** Re-encoding would lose the EXIF orientation that Coil uses to show a phone photo upright. */
    @Test
    fun aPhotoTurnedByItsExifIsCopiedAsItIs() {
        val stored = File(temp.newFolder(), "photo.jpg").apply { writeBytes(picture(4032, 3024)) }
        android.media.ExifInterface(stored.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val photo = stored.readBytes()
        assertArrayEquals(photo, copied("Holidays (2024)/poster.jpg", photo)?.readBytes())
    }

    /** Copies a fanart into a fresh cache at [dir] from a NAS whose reads throw [failure] past the first block. */
    private fun breakingAfterFirstBlock(dir: File, failure: Exception): Result<String?> = runBlocking {
        val size = 1_000_000L
        val nas = FakeRemoteBrowser().apply {
            put("Dune (2021)/fanart.jpg", size = size, bytes = ByteArray(size.toInt()))
            onRead = { _, offset, _ -> if (offset > 0) throw failure }
        }
        runCatching { ArtworkCache(dir).copy(source, RemoteFile("Dune (2021)/fanart.jpg", size), nas) }
    }

    @Test
    fun aCopyCutOffLeavesNothingBehind() {
        val dir = temp.newFolder("artwork")
        assertNull(breakingAfterFirstBlock(dir, IOException("Connection reset by peer")).getOrThrow())
        assertEquals(emptyList<File>(), dir.walkTopDown().filter { it.isFile }.toList())
    }

    /** A scan stopped between two blocks (the folder removed, the app closing) leaves no half copy either. */
    @Test
    fun aCopyCancelledLeavesNothingBehind() {
        val dir = temp.newFolder("artwork")
        val cancelled = breakingAfterFirstBlock(dir, CancellationException("The scan stopped"))
        assertTrue(cancelled.toString(), cancelled.exceptionOrNull() is CancellationException)
        assertEquals(emptyList<File>(), dir.walkTopDown().filter { it.isFile }.toList())
    }
}
