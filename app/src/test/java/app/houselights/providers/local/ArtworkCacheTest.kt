package app.houselights.providers.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtworkCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(dir: File, path: String, size: Int) = File(dir, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(size))
    }

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
        val source = SourceEntity(
            id = 1, name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "nas", share = "media",
            path = "films", username = "", password = Secret(""),
        )
        val copied = cache.copy(source, RemoteFile("Dune (2021)-poster.jpg", 100), nas)
        assertTrue(copied.orEmpty(), copied?.let { File(it.removePrefix("file://")).isFile } == true)
    }
}
