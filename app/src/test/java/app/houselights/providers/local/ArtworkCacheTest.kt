package app.houselights.providers.local

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
}
