package app.houselights.providers.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val source = SourceEntity(
        id = 1, name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "nas", share = "media",
        path = "films", username = "", password = Secret(""),
    )

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

    /** Copies [bytes], put on a NAS at [path], into a cache of its own: the copy, and the NAS. */
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
    fun missingFolderIsFine() {
        assertEquals(CleanupResult(0, 0), ArtworkCache(File(temp.root, "none")).sweep(emptyList()))
    }

    @Test
    fun theFirstImageIsCopiedThoughTheCacheFolderIsNotThereYet() = runBlocking {
        // A folder that does not exist has no space at all, as far as File.usableSpace goes.
        val cache = ArtworkCache(File(temp.root, "artwork"))
        val nas = FakeRemoteBrowser().apply { put("Dune (2021)-poster.jpg", size = 100, bytes = ByteArray(100)) }
        val copied = cache.copy(source, RemoteFile("Dune (2021)-poster.jpg", 100), nas)
        assertTrue(copied.orEmpty(), copied?.let { File(it.removePrefix("file://")).isFile } == true)
    }

    /** A fanart of several megabytes never sits whole in memory: it comes over a block at a time, straight to the file. */
    @Test
    fun aLargePictureIsCopiedABlockAtATime() {
        val bytes = ByteArray(1_000_000) { (it % 251).toByte() }
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

    /** What the screen can show as it is stays byte for byte as the NAS has it. */
    @Test
    fun aPictureThatFitsIsCopiedAsItIs() {
        val logo = picture(800, 310, Bitmap.CompressFormat.PNG, alpha = true)
        assertArrayEquals(logo, copied("Dune (2021)/clearlogo.png", logo)?.readBytes())
    }

    /** A NAS that stops in the middle of a copy leaves no picture, whole or not, in the cache. */
    @Test
    fun aCopyCutOffLeavesNothingBehind() = runBlocking {
        val dir = temp.newFolder("artwork")
        val bytes = ByteArray(1_000_000)
        val nas = FakeRemoteBrowser().apply {
            put("Dune (2021)/fanart.jpg", size = bytes.size.toLong(), bytes = bytes)
            onRead = { _, offset, _ -> if (offset > 0) throw IOException("Connection reset by peer") }
        }
        assertNull(ArtworkCache(dir).copy(source, RemoteFile("Dune (2021)/fanart.jpg", bytes.size.toLong()), nas))
        assertEquals(emptyList<File>(), dir.walkTopDown().filter { it.isFile }.toList())
    }

    /** A scan stopped between two blocks (the folder removed, the app closing) leaves no half copy either. */
    @Test
    fun aCopyCancelledLeavesNothingBehind() = runBlocking {
        val dir = temp.newFolder("artwork")
        val bytes = ByteArray(1_000_000)
        val nas = FakeRemoteBrowser().apply {
            put("Dune (2021)/fanart.jpg", size = bytes.size.toLong(), bytes = bytes)
            onRead = { _, offset, _ -> if (offset > 0) throw CancellationException("The scan stopped") }
        }
        val cancelled = runCatching { ArtworkCache(dir).copy(source, RemoteFile("Dune (2021)/fanart.jpg", bytes.size.toLong()), nas) }
        assertTrue(cancelled.toString(), cancelled.exceptionOrNull() is CancellationException)
        assertEquals(emptyList<File>(), dir.walkTopDown().filter { it.isFile }.toList())
    }
}
