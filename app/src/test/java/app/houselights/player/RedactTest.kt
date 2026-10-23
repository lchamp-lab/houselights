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

    /** A log line keeps what it says and loses its secrets, wherever they are written. */
    @Test
    fun aLogLineLosesItsSecrets() = mapOf(
        "$LINE I Playback: Launch S01E02 -> smb://zidoo:p4ss@nas/series/MobLand/S01E02.mkv" to
            "$LINE I Playback: Launch S01E02 -> smb://•••@nas/series/MobLand/S01E02.mkv",
        "$LINE W Images  : Image smb://zidoo:p/ss@nas/media/poster.jpg failed" to
            "$LINE W Images  : Image smb://•••@nas/media/poster.jpg failed",
        "$LINE I Playback: Launch smb://***@nas/media/film.mkv" to "$LINE I Playback: Launch smb://•••@nas/media/film.mkv",
        "$LINE D Jellyfin: GET http://lea:s3cret@nas:8096/Items?api_key=0123abcd&ParentId=42 (120 ms)" to
            "$LINE D Jellyfin: GET http://•••@nas:8096/Items?api_key=•••&ParentId=42 (120 ms)",
        "$LINE D Jellyfin: --> GET https://nas:8920/Videos/1/stream.mkv?static=true&ApiKey=0123abcd" to
            "$LINE D Jellyfin: --> GET https://nas:8920/Videos/1/stream.mkv?static=true&ApiKey=•••",
        "$LINE D OkHttp  : X-Emby-Token: 0123abcd" to "$LINE D OkHttp  : X-Emby-Token: •••",
        "$LINE D OkHttp  : Authorization: MediaBrowser Client=\"Houselights\", DeviceId=\"d1\", Version=\"0.3.0\", Token=\"0123abcd\"" to
            "$LINE D OkHttp  : Authorization: MediaBrowser Client=\"Houselights\", DeviceId=\"d1\", Version=\"0.3.0\", Token=\"•••\"",
        "$LINE D OkHttp  : Authorization: Bearer tr4kt-acc3ss" to "$LINE D OkHttp  : Authorization: Bearer •••",
        "$LINE D OkHttp  : Authorization: Basic emlkb286cDRzcw==" to "$LINE D OkHttp  : Authorization: Basic •••",
        "$LINE D OkHttp  : {\"access_token\":\"tr4kt-acc3ss\",\"token_type\":\"bearer\",\"refresh_token\":\"tr4kt-r3fresh\"}" to
            "$LINE D OkHttp  : {\"access_token\":\"•••\",\"token_type\":\"bearer\",\"refresh_token\":\"•••\"}",
        "$LINE D OkHttp  : {\"device_code\": \"d3v1c3\", \"user_code\": \"5055CC52\", \"interval\": 5}" to
            "$LINE D OkHttp  : {\"device_code\": \"•••\", \"user_code\": \"•••\", \"interval\": 5}",
        "$LINE D OkHttp  : {\"Username\":\"lea\",\"Pw\":\"s3cret\"}" to "$LINE D OkHttp  : {\"Username\":\"lea\",\"Pw\":\"•••\"}",
        "$LINE I Trakt   : Back from houselights://trakt?code=c0d3&state=s1" to
            "$LINE I Trakt   : Back from houselights://trakt?code=•••&state=s1",
        "$LINE D Trakt   : refresh_token=tr4kt-r3fresh&grant_type=refresh_token" to
            "$LINE D Trakt   : refresh_token=•••&grant_type=refresh_token",
    ).forEach { (line, logged) -> assertEquals(line, logged, Redact.text(line)) }

    /** What is not a secret is read as written: a code that is a status, a word, a share named with an @. */
    @Test
    fun aLogLineWithoutSecretsIsKept() = listOf(
        "$LINE W Tmdb    : Answer 429, waiting 5 s",
        "$LINE W Trakt   : Trakt answered code 404 for sync/history",
        "$LINE I Trakt   : Trakt token refreshed, valid 90 days",
        "$LINE I Scan    : Read nfs://nas/volume1/media/@eaDir/film.jpg",
        "$LINE I Scan    : Pass done: 2 folders, 11 titles, 0 changed",
        "$LINE E Houselights: Background task failed",
    ).forEach { assertEquals(it, Redact.text(it)) }

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

    private companion object {
        /** How logcat -v threadtime starts a line: date, time, process and thread. */
        const val LINE = "10-23 10:42:11.123  4321  4350"
    }
}
