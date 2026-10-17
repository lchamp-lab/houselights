package app.houselights.player

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.PlaybackPlan
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Which app a film goes to, and what it gets: the extras, and a password only where it belongs. On
 * Android 9, the Z1000 Pro's, and on Android 11, where an app sees only the apps it declares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30])
class PlayerLaunchTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val apps = FakeApps(context)

    /** In the background: what follows a player once it has started ends with the test. */
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

    /** The Z1000 Pro's firmware: the old player, installed with the system, as before. */
    @Test
    fun theOldPlayerStillGetsTheFilm() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        val manager = manager(ZIDOO_BUILD)
        assertEquals(MOVIE_ACTIVITY, manager.intentFor(plan(NFS)).component?.className)
        assertEquals(SMB, manager.intentFor(plan(SMB)).dataString)
    }

    /**
     * The Z1000 Pro as read on the device: a test-keys build, whose player, a system app, answers a
     * video at http, file and content addresses only, its poster wall at file ones, and Zidoo's other
     * apps at none. Beside them, an app under a name of Zidoo's that answers every address, and one
     * signed like Zidoo's apps: the old player still gets every film, with what it is told of it, and
     * every secret.
     */
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

    /** Nothing answers a video: a plain file goes to whatever the system finds, a password nowhere. */
    @Test
    fun noPlayerNoPassword() = runTest {
        val manager = manager(ZIDOO_BUILD)
        val nfs = manager.intentFor(plan(NFS))
        assertEquals(Intent.ACTION_VIEW, nfs.action)
        assertNull(nfs.component)
        assertRefused { manager.intentFor(plan(SMB)) }
    }

    /** A player other apps may not start: a plain file goes to whatever the system finds, as before, not to it. */
    @Test
    fun aPlayerTheAppMayNotStartIsNotHandedTheFilm() = runTest {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true, exported = false)
        val manager = manager(ZIDOO_BUILD)
        assertNull(manager.intentFor(plan(NFS)).component)
        assertRefused { manager.intentFor(plan(SMB)) }
    }

    @Test
    fun aPlainFileGoesToAnyPlayerWhereThereIsNoZidoo() = runTest {
        val intent = manager(OTHER_BUILD).intentFor(plan(NFS))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertNull(intent.component)
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

    @Test
    fun aPasswordNeverGoesToAnotherPlayer() = runTest {
        assertRefused { manager(OTHER_BUILD).intentFor(plan(SMB)) }
    }

    /** The Zidoo player installed as an ordinary app, as some Zidoo firmwares do: on a Zidoo, it gets the password. */
    @Test
    fun onAZidooItsPlayerGetsThePasswordEvenNotASystemApp() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, schemes = emptyList())
        val intent = manager(ZIDOO_BUILD).intentFor(plan(SMB))
        assertEquals(MOVIE_ACTIVITY, intent.component?.className)
        assertEquals(SMB, intent.dataString)
    }

    /** Elsewhere, an app that took the Zidoo player's package name, not a system app, gets no password. */
    @Test
    fun elsewhereAnAppUnderThePlayersNameGetsNoPassword() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, schemes = emptyList())
        assertRefused { manager(OTHER_BUILD).intentFor(plan(SMB)) }
    }

    /**
     * Elsewhere, even a system app under the Zidoo player's name gets no password: only a Zidoo's
     * player does. A plain file still goes to it, with what it is told of the film.
     */
    @Test
    fun elsewhereNoPlayerGetsAPassword() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        val manager = manager(OTHER_BUILD)
        assertRefused { manager.intentFor(plan(SMB)) }
        val nfs = manager.intentFor(plan(NFS))
        assertEquals(MOVIE_ACTIVITY, nfs.component?.className)
        assertTrue(nfs.getBooleanExtra("return_result", false))
    }

    /** Elsewhere, the system's video apps are not looked for: a plain file goes to whatever the system finds. */
    @Test
    fun elsewhereAPlainFileGoesToTheSystemsChoice() = runTest {
        apps.install("com.android.tv.player", "com.android.tv.player.VideoActivity", system = true)
        val manager = manager(OTHER_BUILD)
        assertRefused { manager.intentFor(plan(SMB)) }
        assertNull(manager.intentFor(plan(NFS)).component)
    }

    /** A player installed as an ordinary app, signed with the key of an app of Zidoo's the firmware installed. */
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
        assertRefused { manager.intentFor(plan(SMB)) }
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

    /** An ordinary player of another key is trusted the first time, then refused once its certificate changes. */
    @Test
    fun aPlayerIsTrustedOnFirstUseUntilItChanges() = runTest {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY)
        val manager = manager(ZIDOO_BUILD)
        assertEquals(SMB, manager.intentFor(plan(SMB)).dataString)
        manager.onPlayerLaunched()
        assertEquals("the same player, again", SMB, manager.intentFor(plan(SMB)).dataString)

        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = "a-third-key")
        assertTrue("said as such", assertRefused { manager.intentFor(plan(SMB)) }.playerChanged)
        // A plain file, which holds no secret, still plays there.
        assertEquals(ZIDOO_PLAYER_ACTIVITY, manager.intentFor(plan(NFS)).component?.className)
        // Nor does a token go through a chooser: the app put in the player's place would be in it, alone perhaps.
        assertTrue("said as such", assertRefused { manager.intentFor(plan(TOKEN)) }.playerChanged)
    }

    /**
     * The player a password goes to is remembered once it has started, not when it could not be: an
     * app that took its name and failed to start would otherwise be the one trusted.
     */
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

    /**
     * A player found under another name may open shares alone: it gets a stream only if it answers
     * its address. Else the stream goes as it would to any player: a token through the chooser, a
     * password typed in an http path mapping nowhere.
     */
    @Test
    fun aPlayerFoundGetsAStreamOnlyIfItAnswersItsAddress() = runTest {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true)
        val manager = manager(ZIDOO_BUILD)
        assertNull(manager.intentFor(plan(STREAM)).component)
        assertEquals(Intent.ACTION_CHOOSER, manager.intentFor(plan(TOKEN)).action)
        assertRefused { manager.intentFor(plan(HTTP_PASSWORD)) }

        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true, schemes = listOf("http"))
        listOf(STREAM, TOKEN, HTTP_PASSWORD).forEach {
            val intent = manager.intentFor(plan(it))
            assertEquals(it, ZIDOO_PLAYER_ACTIVITY, intent.component?.className)
            assertEquals(it, intent.dataString)
        }
    }

    /** The Z1000 Pro's player gets a stream by its name, as before: it opens one, read on the device. */
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
     * Each launch of a URI with a secret says in one warning, kept in every build, which player was
     * chosen among which, on what device, and why the secret went or not; never the secret.
     */
    @Test
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

        // Without an app of Zidoo's, the other box is no Zidoo.
        apps.uninstall("com.zidoo.poster")
        assertRefused { manager(OTHER_BUILD).intentFor(plan(SMB)) }
        val refused = warnings().last()
        assertTrue(refused, "NOT_A_ZIDOO" in refused && "Chromecast" in refused)
        assertFalse(refused, "secret" in refused)
    }

    private fun warnings() = ShadowLog.getLogsForTag("Playback").filter { it.type == Log.WARN }.map { it.msg }

    /** The Zidoo player is another app: a file of ours is out of its reach, a web address is not. */
    @Test
    fun theZidooPlayerGetsTheCredentialsAndOnlyWebArtwork() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        val manager = manager(ZIDOO_BUILD)

        val ours = "file:///data/user/0/app.houselights/files/artwork/b.jpg"
        val local = manager.intentFor(plan(SMB, backdrop = ours))
        assertEquals(MOVIE_ACTIVITY, local.component?.className)
        assertFalse(local.hasExtra("loading_image"))

        val remote = manager.intentFor(plan(NFS, backdrop = "https://image.tmdb.org/t/p/w1280/b.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", remote.getStringExtra("loading_image"))
    }

    /** [launch] gives the password to no app: the refusal. */
    private fun assertRefused(launch: () -> Unit): PlaybackManager.CredentialsNotShared {
        try {
            launch()
        } catch (refused: PlaybackManager.CredentialsNotShared) {
            return refused
        }
        throw AssertionError("The password went to the player")
    }

    private companion object {
        const val NFS = "nfs://nas/films/Heat.mkv"
        const val SMB = "smb://zidoo:secret@nas/films/Heat.mkv"
        const val TOKEN = "http://nas:8096/Videos/1/stream.mkv?static=true&api_key=secret"
        const val STREAM = "http://nas/films/Heat.mkv"
        const val HTTP_PASSWORD = "http://zidoo:secret@nas/films/Heat.mkv"
    }
}
