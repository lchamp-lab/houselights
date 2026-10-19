package app.houselights.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs on Robolectric: a URI is read as the player's [android.net.Uri] reads it. */
@RunWith(RobolectricTestRunner::class)
class RedactTest {
    /** Asserts [check]'s answer for each URI, the URI as the message. */
    private fun assertEach(check: (String) -> Boolean, vararg answers: Pair<String, Boolean>) =
        answers.forEach { (uri, answer) -> assertEquals(uri, answer, check(uri)) }

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
    fun findsCredentials() = assertEach(
        Redact::hasUserInfo,
        "smb://zidoo:secret@nas/media/film.mkv" to true,
        "smb://zidoo@nas/media/film.mkv" to true,
        "smb://nas/media/film@home.mkv" to false,
        "nfs://nas/export/film.mkv" to false,
    )

    /** A raw password may contain a space or a slash, which end the pattern; the player's Uri still parses it. */
    @Test
    fun findsAPasswordTypedRaw() {
        assertEach(
            Redact::hasUserInfo,
            "smb://zidoo:p w@nas/media/film.mkv" to true,
            "smb://zidoo:p/ss@nas/media/film.mkv" to true,
            "smb://zidoo:12/34@nas/media/film.mkv" to true,
            "smb://zidoo:p#ss@nas/media/film.mkv" to true,
            "smb://nas:445/media/film@home.mkv" to false,
            "smb://[fe80::1]/media/film.mkv" to false,
            "http://nas:8096/Videos/1/stream.mkv?static=true" to false,
        )
        assertTrue(Redact.hasCredentials("smb://zidoo:p w@nas/media/film.mkv"))
    }

    /** A password of digits with several slashes, or a slash then a ? or a #, such as a date. */
    @Test
    fun findsAPasswordOfDigitsAndSlashes() {
        listOf(
            "smb://zidoo:2024/10/17@nas/media/film.mkv",
            "smb://zidoo:12/3?4@nas/media/film.mkv",
            "smb://zidoo:12/3#4@nas/media/film.mkv",
        ).forEach {
            assertTrue(it, Redact.hasUserInfo(it))
            assertEquals("smb://***@nas/media/film.mkv", Redact.uri(it))
        }
        assertFalse(Redact.hasUserInfo("smb://nas/media/@eaDir/film.mkv"))
    }

    /** A raw password is hidden whole, up to the last @, which no password goes past. */
    @Test
    fun hidesAPasswordTypedRaw() = mapOf(
        "smb://zidoo:p w@nas/media/film.mkv" to "smb://***@nas/media/film.mkv",
        "smb://zidoo:p/ss@nas/media/film.mkv" to "smb://***@nas/media/film.mkv",
        "smb://zidoo:p@ss w@nas/media/film.mkv" to "smb://***@nas/media/film.mkv",
        "smb://zidoo:p w@nas/media/film@home.mkv" to "smb://***@home.mkv",
        // When the pattern hides the password, an @ in the file name is kept.
        "smb://zidoo:secret@nas/media/film@home.mkv" to "smb://***@nas/media/film@home.mkv",
    ).forEach { (uri, logged) -> assertEquals(uri, logged, Redact.uri(uri)) }

    /** Jellyfin's token is a credential too: its stream URL opens the whole account. */
    @Test
    fun aTokenInTheQueryIsACredential() = assertEach(
        Redact::hasCredentials,
        "http://nas:8096/Videos/1/stream.mkv?static=true&api_key=abc123" to true,
        "http://plex:32400/library/parts/1/file.mkv?X-Plex-Token=abc" to true,
        "smb://zidoo:secret@nas/media/film.mkv" to true,
        "http://nas:8096/Videos/1/stream.mkv?static=true" to false,
        "nfs://nas/export/token=film.mkv" to false,
    )
}
