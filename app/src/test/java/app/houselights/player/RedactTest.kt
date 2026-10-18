package app.houselights.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs on Robolectric: a URI is read as the player's [android.net.Uri] reads it. */
@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun findsCredentials() {
        assertEquals(true, Redact.hasUserInfo("smb://zidoo:secret@nas/media/film.mkv"))
        assertEquals(true, Redact.hasUserInfo("smb://zidoo@nas/media/film.mkv"))
        assertEquals(false, Redact.hasUserInfo("smb://nas/media/film@home.mkv"))
        assertEquals(false, Redact.hasUserInfo("nfs://nas/export/film.mkv"))
    }

    /** A raw password may contain a space or a slash, which end the pattern; the player's Uri still parses it. */
    @Test
    fun findsAPasswordTypedRaw() {
        assertEquals(true, Redact.hasUserInfo("smb://zidoo:p w@nas/media/film.mkv"))
        assertEquals(true, Redact.hasUserInfo("smb://zidoo:p/ss@nas/media/film.mkv"))
        assertEquals(true, Redact.hasUserInfo("smb://zidoo:12/34@nas/media/film.mkv"))
        assertEquals(true, Redact.hasUserInfo("smb://zidoo:p#ss@nas/media/film.mkv"))
        assertEquals(true, Redact.hasCredentials("smb://zidoo:p w@nas/media/film.mkv"))
        assertEquals(false, Redact.hasUserInfo("smb://nas:445/media/film@home.mkv"))
        assertEquals(false, Redact.hasUserInfo("smb://[fe80::1]/media/film.mkv"))
        assertEquals(false, Redact.hasUserInfo("http://nas:8096/Videos/1/stream.mkv?static=true"))
    }

    /** A password of digits with several slashes, or a slash then a ? or a #, such as a date. */
    @Test
    fun findsAPasswordOfDigitsAndSlashes() {
        listOf(
            "smb://zidoo:2024/10/17@nas/media/film.mkv",
            "smb://zidoo:12/3?4@nas/media/film.mkv",
            "smb://zidoo:12/3#4@nas/media/film.mkv",
        ).forEach {
            assertEquals(it, true, Redact.hasUserInfo(it))
            assertEquals("smb://***@nas/media/film.mkv", Redact.uri(it))
        }
        assertEquals(false, Redact.hasUserInfo("smb://nas/media/@eaDir/film.mkv"))
    }

    /** A raw password is hidden whole, up to the last @, which no password goes past. */
    @Test
    fun hidesAPasswordTypedRaw() {
        assertEquals("smb://***@nas/media/film.mkv", Redact.uri("smb://zidoo:p w@nas/media/film.mkv"))
        assertEquals("smb://***@nas/media/film.mkv", Redact.uri("smb://zidoo:p/ss@nas/media/film.mkv"))
        assertEquals("smb://***@nas/media/film.mkv", Redact.uri("smb://zidoo:p@ss w@nas/media/film.mkv"))
        assertEquals("smb://***@home.mkv", Redact.uri("smb://zidoo:p w@nas/media/film@home.mkv"))
        // When the pattern hides the password, an @ in the file name is kept.
        assertEquals("smb://***@nas/media/film@home.mkv", Redact.uri("smb://zidoo:secret@nas/media/film@home.mkv"))
    }

    /** Jellyfin's token is a credential too: its stream URL opens the whole account. */
    @Test
    fun aTokenInTheQueryIsACredential() {
        assertEquals(true, Redact.hasCredentials("http://nas:8096/Videos/1/stream.mkv?static=true&api_key=abc123"))
        assertEquals(true, Redact.hasCredentials("http://plex:32400/library/parts/1/file.mkv?X-Plex-Token=abc"))
        assertEquals(true, Redact.hasCredentials("smb://zidoo:secret@nas/media/film.mkv"))
        assertEquals(false, Redact.hasCredentials("http://nas:8096/Videos/1/stream.mkv?static=true"))
        assertEquals(false, Redact.hasCredentials("nfs://nas/export/token=film.mkv"))
    }
}
