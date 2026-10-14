package app.houselights.providers.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinUrlTest {
    @Test
    fun normalizeAddsSchemeAndPort() {
        assertEquals("http://nas:8096", JellyfinClient.normalizeUrl("nas"))
        assertEquals("http://nas:8096", JellyfinClient.normalizeUrl(" nas/ "))
        assertEquals("http://192.168.1.10:9000", JellyfinClient.normalizeUrl("192.168.1.10:9000"))
        assertEquals("https://jellyfin.example.org", JellyfinClient.normalizeUrl("https://jellyfin.example.org/"))
    }

    @Test
    fun encryptedAddressIsTriedFirst() {
        assertEquals(listOf("https://nas:8920", "http://nas:8096"), JellyfinClient.candidateUrls("nas"))
        assertEquals(listOf("https://nas:8096", "http://nas:8096"), JellyfinClient.candidateUrls("nas:8096"))
        assertEquals(listOf("https://nas:8920/jellyfin", "http://nas:8096/jellyfin"), JellyfinClient.candidateUrls("nas/jellyfin"))
    }

    @Test
    fun typedSchemeIsKept() {
        assertEquals(listOf("http://nas:8096"), JellyfinClient.candidateUrls("http://nas"))
        assertEquals(listOf("https://nas"), JellyfinClient.candidateUrls("https://nas"))
        assertEquals(emptyList<String>(), JellyfinClient.candidateUrls("  "))
    }

    @Test
    fun encryption() {
        assertTrue(JellyfinClient.isEncrypted("https://nas:8920"))
        assertFalse(JellyfinClient.isEncrypted("http://nas:8096"))
    }
}
