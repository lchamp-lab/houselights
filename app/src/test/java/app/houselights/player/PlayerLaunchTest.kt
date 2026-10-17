package app.houselights.player

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.PlaybackPlan
import app.houselights.player.FakeApps.Companion.GALLERY
import app.houselights.player.FakeApps.Companion.MOVIE_ACTIVITY
import app.houselights.player.FakeApps.Companion.OTHER_BUILD
import app.houselights.player.FakeApps.Companion.OTHER_KEY
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

/**
 * Which app a film goes to, and what it gets: the extras, and a password only where it belongs. On
 * Android 9, the Z1000 Pro's, and on Android 11, where an app sees only the apps it declares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30])
class PlayerLaunchTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val apps = FakeApps(context)

    private fun TestScope.manager(device: DeviceIdentity) =
        PlaybackManager(context, this, status = { PlayerAnswer.NoAnswer }, players = ZidooPlayer(context) { device }) { null }

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
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, bySchemes = false)
        val manager = manager(ZIDOO_BUILD)
        assertEquals(MOVIE_ACTIVITY, manager.intentFor(plan(NFS)).component?.className)
        assertEquals(SMB, manager.intentFor(plan(SMB)).dataString)
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
    }

    @Test
    fun aPasswordNeverGoesToAnotherPlayer() = runTest {
        assertRefused { manager(OTHER_BUILD).intentFor(plan(SMB)) }
    }

    /** The Zidoo player installed as an ordinary app, as some Zidoo firmwares do: on a Zidoo, it gets the password. */
    @Test
    fun onAZidooItsPlayerGetsThePasswordEvenNotASystemApp() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, bySchemes = false)
        val intent = manager(ZIDOO_BUILD).intentFor(plan(SMB))
        assertEquals(MOVIE_ACTIVITY, intent.component?.className)
        assertEquals(SMB, intent.dataString)
    }

    /** Elsewhere, an app that took the Zidoo player's package name, not a system app, gets no password. */
    @Test
    fun elsewhereAnAppUnderThePlayersNameGetsNoPassword() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, bySchemes = false)
        assertRefused { manager(OTHER_BUILD).intentFor(plan(SMB)) }
    }

    /** Elsewhere, even a system app under the Zidoo player's name gets no password: only a Zidoo's player does. */
    @Test
    fun elsewhereNoPlayerGetsAPassword() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, bySchemes = false)
        assertRefused { manager(OTHER_BUILD).intentFor(plan(SMB)) }
    }

    /** A player installed as an ordinary app, signed with the key of an app of Zidoo's the firmware installed. */
    @Test
    fun aPlayerSignedLikeTheFirmwareGetsThePassword() = runTest {
        apps.install("com.zidoo.poster", system = true, certificate = ZIDOO_KEY)
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = ZIDOO_KEY)
        apps.install("com.zidoo.other", certificate = OTHER_KEY)
        val manager = manager(ZIDOO_BUILD)
        assertEquals(SMB, manager.intentFor(plan(SMB)).dataString)
        // Remembered: another certificate later is refused.
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY)
        assertRefused { manager.intentFor(plan(SMB)) }
    }

    /** An ordinary player of another key is trusted the first time, then refused once its certificate changes. */
    @Test
    fun aPlayerIsTrustedOnFirstUseUntilItChanges() = runTest {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY)
        val manager = manager(ZIDOO_BUILD)
        assertEquals(SMB, manager.intentFor(plan(SMB)).dataString)
        assertEquals("the same player, again", SMB, manager.intentFor(plan(SMB)).dataString)

        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = "a-third-key")
        assertTrue("said as such", assertRefused { manager.intentFor(plan(SMB)) }.playerChanged)
        // A token is not a password: it goes through the chooser, as anywhere else.
        assertEquals(Intent.ACTION_CHOOSER, manager.intentFor(plan(TOKEN)).action)
    }

    /** The Zidoo player is another app: a file of ours is out of its reach, a web address is not. */
    @Test
    fun theZidooPlayerGetsTheCredentialsAndOnlyWebArtwork() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, bySchemes = false)
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
    }
}
