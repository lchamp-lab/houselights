package app.houselights.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactTest {
    @Test
    fun hidesSmbCredentials() {
        assertEquals(
            "smb://***@192.168.1.10/media/films/Top Gun.mkv",
            Redact.uri("smb://zidoo:p@ss:w0rd@192.168.1.10/media/films/Top Gun.mkv"),
        )
    }

    @Test
    fun hidesTokens() {
        assertEquals(
            "http://nas:8096/Videos/1/stream.mkv?static=true&api_key=***",
            Redact.uri("http://nas:8096/Videos/1/stream.mkv?static=true&api_key=abc123"),
        )
    }

    @Test
    fun keepsPlainUris() {
        assertEquals("nfs://nas/export/film.mkv", Redact.uri("nfs://nas/export/film.mkv"))
    }
}
