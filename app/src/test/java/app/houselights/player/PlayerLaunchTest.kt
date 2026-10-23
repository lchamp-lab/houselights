package app.houselights.player

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.PlaybackPlan
import app.houselights.core.SubtitleMode
import app.houselights.core.SubtitleTrack
import app.houselights.core.TrackPlan
import app.houselights.core.TrackPreferences
import app.houselights.player.FakeApps.Companion.FIRMWARE_SCHEMES
import app.houselights.player.FakeApps.Companion.GALLERY
import app.houselights.player.FakeApps.Companion.MOVIE_ACTIVITY
import app.houselights.player.FakeApps.Companion.OTHER_BUILD
import app.houselights.player.FakeApps.Companion.OTHER_KEY
import app.houselights.player.FakeApps.Companion.SHARES
import app.houselights.player.FakeApps.Companion.Z1000_PRO_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_KEY
import app.houselights.player.FakeApps.Companion.ZIDOO_PLAYER
import app.houselights.player.FakeApps.Companion.ZIDOO_PLAYER_ACTIVITY
import app.houselights.player.PlaybackManager.CredentialsNotShared
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Which app a film goes to and what it gets, a password only where it belongs. */
@RunWith(RobolectricTestRunner::class)
class PlayerLaunchTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val apps = FakeApps(context)

    /** In the background scope, so tracking a started player ends with the test. */
    private fun TestScope.manager(device: DeviceIdentity) =
        PlaybackManager(context, backgroundScope, status = { PlayerAnswer.NoAnswer }, players = ZidooPlayer(context) { device }) { null }

    private fun plan(uri: String, backdrop: String? = null) = PlaybackPlan.Launch(
        uri = uri, container = "mkv", title = "Heat", backdropUrl = backdrop, logoUrl = null, startMs = 0,
        reporter = null, resumeKey = "m1",
    )

    /** A newer firmware: no com.android.gallery3d, its own player answers videos. */
    @Test
    fun aFirmwareWithoutTheOldPlayerHandsTheFilmToItsOwn() = runTest {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true)
        val manager = manager(ZIDOO_BUILD)

        val nfs = manager.intentFor(plan(NFS))
        assertEquals(ZIDOO_PLAYER_ACTIVITY, nfs.component?.className)
        assertEquals("Heat", nfs.getStringExtra("title"))
        assertTrue(nfs.getBooleanExtra("from_start", false))
        assertTrue(nfs.getBooleanExtra("return_result", false))

        val smb = manager.intentFor(plan(SMB))
        assertEquals(ZIDOO_PLAYER_ACTIVITY, smb.component?.className)
        assertEquals(SMB, smb.dataString)
    }

    /** The Z1000 Pro as read on the device, beside impostors: the old player gets every film and every secret. */
    @Test
    fun theZ1000ProHandsEveryFilmToItsOldPlayer() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, certificate = ZIDOO_KEY, schemes = listOf("http", "file", "content"))
        val wall = "com.zidoo.poster.main.PosterWallActivity"
        apps.install("com.zidoo.poster", wall, system = true, certificate = ZIDOO_KEY, schemes = listOf("file"))
        listOf("com.zidoo.fileexplorer", "com.zidoo.control.center", "com.zidoo.audioplayer").forEach {
            apps.install(it, system = true, certificate = ZIDOO_KEY)
        }
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY, byType = true, schemes = SHARES + FIRMWARE_SCHEMES)
        apps.install("com.rtk.player", "com.rtk.player.Play", certificate = ZIDOO_KEY, byType = true, schemes = SHARES + FIRMWARE_SCHEMES)
        val manager = manager(Z1000_PRO_BUILD)

        val artwork = "https://image.tmdb.org/t/p/w1280/b.jpg"
        val nfs = manager.intentFor(plan(NFS, backdrop = artwork))
        assertEquals(MOVIE_ACTIVITY, nfs.component?.className)
        assertEquals("Heat", nfs.getStringExtra("title"))
        assertEquals(artwork, nfs.getStringExtra("loading_image"))
        assertTrue(nfs.getBooleanExtra("from_start", false))
        assertTrue(nfs.getBooleanExtra("return_result", false))
        listOf(SMB, TOKEN, STREAM, HTTP_PASSWORD).forEach {
            val intent = manager.intentFor(plan(it))
            assertEquals(it, MOVIE_ACTIVITY, intent.component?.className)
            assertEquals(it, intent.dataString)
        }
        assertEquals(PlayerStatus.Detected(GALLERY), ZidooPlayer(context) { Z1000_PRO_BUILD }.status())
    }

    /** Numbered as the Zidoo player numbers them: asked of it only, never of another app. */
    @Test
    fun theTracksAreAskedOfTheZidooPlayerOnly() = runTest {
        val media = MediaInfo(
            audio = listOf(AudioTrack(Codecs.EAC3, 6, "fr"), AudioTrack(Codecs.EAC3, 6, "ko")),
            subtitles = listOf(SubtitleTrack("fr", format = Codecs.SRT, forced = true), SubtitleTrack("fr", format = Codecs.SRT)),
            tracksInOrder = true,
        )
        val tracks = TrackPlan(media, TrackPreferences("ko", SubtitleMode.FOREIGN), null, "fr", "FR", "local:1")
        val elsewhere = manager(ZIDOO_BUILD).intentFor(plan(NFS), tracks)
        assertNull(elsewhere.component)
        assertFalse(elsewhere.hasExtra("audio_idx") || elsewhere.hasExtra("subtitle_idx"))

        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, certificate = ZIDOO_KEY, schemes = listOf("http", "file", "content"))
        val manager = manager(Z1000_PRO_BUILD)
        val asked = manager.intentFor(plan(NFS), tracks)
        assertEquals(1 to 2, asked.getIntExtra("audio_idx", -1) to asked.getIntExtra("subtitle_idx", -1))
        val left = manager.intentFor(plan(NFS))
        assertFalse(left.hasExtra("audio_idx") || left.hasExtra("subtitle_idx"))
    }

    @Test
    fun noPlayerNoPassword() = runTest {
        val manager = manager(ZIDOO_BUILD)
        val nfs = manager.intentFor(plan(NFS))
        assertEquals(Intent.ACTION_VIEW, nfs.action)
        assertNull(nfs.component)
        assertThrows(CredentialsNotShared::class.java) { manager.intentFor(plan(SMB)) }
    }

    /** Jellyfin's stream carries its token: another app gets it only when the user picks it. */
    @Test
    fun aTokenGoesElsewhereOnlyThroughAChooser() = runTest {
        val intent = manager(OTHER_BUILD).intentFor(plan("http://nas:8096/Videos/1/stream.mkv?static=true&api_key=secret"))
        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        @Suppress("DEPRECATION")
        val inner = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_VIEW, inner?.action)
        // Android opens the only app a chooser lists without asking, unless told not to.
        assertFalse(intent.getBooleanExtra("android.intent.extra.AUTO_LAUNCH_SINGLE_CHOICE", true))
    }

    /** The Zidoo player installed as an ordinary app, as some Zidoo firmwares do: on a Zidoo, it gets the password. */
    @Test
    fun onAZidooItsPlayerGetsThePasswordEvenNotASystemApp() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, schemes = emptyList())
        val intent = manager(ZIDOO_BUILD).intentFor(plan(SMB))
        assertEquals(MOVIE_ACTIVITY, intent.component?.className)
        assertEquals(SMB, intent.dataString)
    }

    /** Off a Zidoo, even a system app named like the Zidoo player gets no password, only plain files. */
    @Test
    fun elsewhereNoPlayerGetsAPassword() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        val manager = manager(OTHER_BUILD)
        assertThrows(CredentialsNotShared::class.java) { manager.intentFor(plan(SMB)) }
        val nfs = manager.intentFor(plan(NFS))
        assertEquals(MOVIE_ACTIVITY, nfs.component?.className)
        assertTrue(nfs.getBooleanExtra("return_result", false))
    }

    /** A player installed as an ordinary app, signed with the key of a Zidoo app the firmware installed. */
    @Test
    fun aPlayerSignedLikeTheFirmwareGetsThePassword() = runTest {
        apps.install("com.zidoo.poster", system = true, certificate = ZIDOO_KEY)
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = ZIDOO_KEY)
        apps.install("com.zidoo.other", certificate = OTHER_KEY)
        val manager = manager(ZIDOO_BUILD)
        assertEquals(SMB, manager.intentFor(plan(SMB)).dataString)
        manager.onPlayerLaunched()
        // Remembered: another certificate later is refused.
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY)
        assertThrows(CredentialsNotShared::class.java) { manager.intentFor(plan(SMB)) }
    }

    /** Beside a system player under another name, an ordinary app under the old player's gets no first password. */
    @Test
    fun aFirstPasswordGoesToTheSystemsPlayer() = runTest {
        val system = apps.install("com.rtk.media", "com.rtk.media.MoviePlayer", system = true)
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, schemes = emptyList())
        val intent = manager(ZIDOO_BUILD).intentFor(plan(SMB))
        assertEquals(system, intent.component)
        assertEquals(SMB, intent.dataString)
    }

    @Test
    fun aPlayerIsTrustedOnFirstUseUntilItChanges() = runTest {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY)
        val manager = manager(ZIDOO_BUILD)
        assertEquals(SMB, manager.intentFor(plan(SMB)).dataString)
        manager.onPlayerLaunched()
        assertEquals("the same player, again", SMB, manager.intentFor(plan(SMB)).dataString)

        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = "a-third-key")
        assertTrue("said as such", assertThrows(CredentialsNotShared::class.java) { manager.intentFor(plan(SMB)) }.playerChanged)
        // A plain file, which holds no secret, still plays there.
        assertEquals(ZIDOO_PLAYER_ACTIVITY, manager.intentFor(plan(NFS)).component?.className)
        // Nor does a token go through a chooser: the app that replaced the player would be listed, perhaps alone.
        assertTrue("said as such", assertThrows(CredentialsNotShared::class.java) { manager.intentFor(plan(TOKEN)) }.playerChanged)
    }

    /** Remembered only once started: an impostor that failed to start must not become the trusted player. */
    @Test
    fun aPlayerIsRememberedOnlyOnceItHasStarted() = runTest {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY)
        val players = ZidooPlayer(context) { ZIDOO_BUILD }
        val manager = manager(ZIDOO_BUILD)
        manager.intentFor(plan(SMB))
        assertEquals("not before it starts", ZidooPlayer.Decision.FIRST_USE, players.choose().decision)
        manager.onLaunchFailed()
        assertEquals("nor once it could not", ZidooPlayer.Decision.FIRST_USE, players.choose().decision)

        manager.intentFor(plan(SMB))
        manager.onPlayerLaunched()
        assertEquals(ZidooPlayer.Decision.REMEMBERED, players.choose().decision)
    }

    /** A player found under another name gets a stream only if it accepts its address; else as for any player. */
    @Test
    fun aPlayerFoundGetsAStreamOnlyIfItAnswersItsAddress() = runTest {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true)
        val manager = manager(ZIDOO_BUILD)
        assertNull(manager.intentFor(plan(STREAM)).component)
        assertEquals(Intent.ACTION_CHOOSER, manager.intentFor(plan(TOKEN)).action)
        assertThrows(CredentialsNotShared::class.java) { manager.intentFor(plan(HTTP_PASSWORD)) }

        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true, schemes = listOf("http"))
        listOf(STREAM, TOKEN, HTTP_PASSWORD).forEach {
            val intent = manager.intentFor(plan(it))
            assertEquals(it, ZIDOO_PLAYER_ACTIVITY, intent.component?.className)
            assertEquals(it, intent.dataString)
        }
    }

    /** The Z1000 Pro's player gets a stream by package name: it opens one, as checked on the device. */
    @Test
    fun theOldPlayerGetsAStreamAsBefore() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        assertEquals(TOKEN, manager(ZIDOO_BUILD).intentFor(plan(TOKEN)).dataString)
    }

    /** On a Zidoo whose player can be trusted with nothing yet, a token still goes through the chooser. */
    @Test
    fun aTokenGoesThroughAChooserWhenNoPlayerChanged() = runTest {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY)
        val intent = manager(ZIDOO_BUILD).intentFor(plan(TOKEN))
        assertEquals("no certificate: nothing says it changed", Intent.ACTION_CHOOSER, intent.action)
    }

    /**
     * Each launch with a secret logs one warning in every build: which player among which, where, and why.
     * On Android 9 and 11, which ask the installer of an app each their own way.
     */
    @Test
    @Config(sdk = [28, 30])
    fun aLaunchWithASecretSaysWhyInTheLog() = runTest {
        apps.install("com.zidoo.poster", system = true, certificate = ZIDOO_KEY)
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = ZIDOO_KEY, installer = "com.zidoo.update")
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        val manager = manager(ZIDOO_BUILD)
        manager.intentFor(plan(NFS))
        assertTrue("a plain file says nothing", warnings().isEmpty())

        manager.intentFor(plan(SMB))
        val line = warnings().single()
        listOf(
            "FIRMWARE", "com.android.gallery3d/.app.MovieActivity system", "com.zidoo.player/.VideoPlayerActivity ordinary",
            "com.zidoo.update", "Z9X Pro", "rtd1619b", "release-keys", "[com.zidoo.poster]", "smb://***@nas/films/Heat.mkv",
        ).forEach { assertTrue("$it in: $line", it in line) }
        assertFalse(line, "secret" in line)

        // Without a Zidoo app, the other box is not a Zidoo.
        apps.uninstall("com.zidoo.poster")
        assertThrows(CredentialsNotShared::class.java) { manager(OTHER_BUILD).intentFor(plan(SMB)) }
        val refused = warnings().last()
        assertTrue(refused, "NOT_A_ZIDOO" in refused && "Chromecast" in refused)
        assertFalse(refused, "secret" in refused)
    }

    private fun warnings() = ShadowLog.getLogsForTag("Playback").filter { it.type == Log.WARN }.map { it.msg }

    /**
     * The Z1000 Pro's older player, a system app, gets every film; it is another app: it cannot read this app's
     * files, but can open a web address.
     */
    @Test
    fun theZidooPlayerGetsTheCredentialsAndOnlyWebArtwork() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        val manager = manager(ZIDOO_BUILD)

        val ours = "file:///data/user/0/app.houselights/files/artwork/b.jpg"
        val local = manager.intentFor(plan(SMB, backdrop = ours))
        assertEquals(MOVIE_ACTIVITY, local.component?.className)
        assertEquals(SMB, local.dataString)
        assertFalse(local.hasExtra("loading_image"))

        val remote = manager.intentFor(plan(NFS, backdrop = "https://image.tmdb.org/t/p/w1280/b.jpg"))
        assertEquals(MOVIE_ACTIVITY, remote.component?.className)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", remote.getStringExtra("loading_image"))
    }

    private companion object {
        const val NFS = "nfs://nas/films/Heat.mkv"
        const val SMB = "smb://zidoo:secret@nas/films/Heat.mkv"
        const val TOKEN = "http://nas:8096/Videos/1/stream.mkv?static=true&api_key=secret"
        const val STREAM = "http://nas/films/Heat.mkv"
        const val HTTP_PASSWORD = "http://zidoo:secret@nas/films/Heat.mkv"
    }
}
