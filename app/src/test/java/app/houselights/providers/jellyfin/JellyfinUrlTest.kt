package app.houselights.providers.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The addresses a typed server name becomes: its scheme and Jellyfin's ports, in the order they are tried. */
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

    /** An IPv6 address's colons are not a port. */
    @Test
    fun anIpv6AddressGetsJellyfinsPorts() {
        assertEquals("http://[fd00::5]:8096", JellyfinClient.normalizeUrl("[fd00::5]"))
        assertEquals("http://[fd00::5]:9000", JellyfinClient.normalizeUrl("[fd00::5]:9000"))
        assertEquals(listOf("https://[fd00::5]:8920", "http://[fd00::5]:8096"), JellyfinClient.candidateUrls("[fd00::5]"))
    }

    @Test
    fun onlyAnHttpsAddressIsEncrypted() {
        assertTrue(JellyfinClient.isEncrypted("https://nas:8920"))
        assertFalse(JellyfinClient.isEncrypted("http://nas:8096"))
    }
}
