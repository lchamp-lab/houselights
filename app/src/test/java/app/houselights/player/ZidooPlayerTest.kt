package app.houselights.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.houselights.player.FakeApps.Companion.GALLERY
import app.houselights.player.FakeApps.Companion.MOVIE_ACTIVITY
import app.houselights.player.FakeApps.Companion.OTHER_BUILD
import app.houselights.player.FakeApps.Companion.REALTEK_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_PLAYER
import app.houselights.player.FakeApps.Companion.ZIDOO_PLAYER_ACTIVITY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Finding the Zidoo player among the apps that open a video, on Android 9 and on Android 11. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30])
class ZidooPlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val apps = FakeApps(context)

    private fun choose(device: DeviceIdentity) = ZidooPlayer(context) { device }.choose()

    private fun ZidooPlayer.Choice.playerClass() = player?.component?.className

    @Test
    fun aZidooIsToldByAnyFieldOfItsBuild() {
        assertTrue(ZIDOO_BUILD.namesZidoo)
        assertFalse(REALTEK_BUILD.namesZidoo)
        assertFalse(OTHER_BUILD.namesZidoo)
        assertTrue(REALTEK_BUILD.copy(model = "Zidoo Z2000 Pro").namesZidoo)
        assertTrue(REALTEK_BUILD.copy(fingerprint = "ZIDOO/Z9X/rtd1619:9/PPR1/1:user/release-keys").namesZidoo)
    }

    /** A build that names only its chip: an app of Zidoo's, installed with the system, still tells a Zidoo. */
    @Test
    fun aZidooIsToldByAnAppItsFirmwareInstalled() {
        apps.install("com.zidoo.poster")
        assertFalse("an ordinary app may take any name", choose(REALTEK_BUILD).device.zidoo)
        apps.install("com.zidoo.poster", system = true)
        val device = choose(REALTEK_BUILD).device
        assertTrue(device.zidoo)
        assertEquals(listOf("com.zidoo.poster"), device.zidooApps)
    }

    /** A newer firmware: its own player opens videos, at the addresses the app hands over. */
    @Test
    fun onAZidooTheFirmwaresVideoAppIsFound() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true)
        assertEquals(ZIDOO_PLAYER_ACTIVITY, choose(ZIDOO_BUILD).playerClass())
    }

    /** One that answers a video by its type only is found as well. */
    @Test
    fun aPlayerThatAnswersByTypeIsFound() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true, byType = true, bySchemes = false)
        assertEquals(ZIDOO_PLAYER_ACTIVITY, choose(ZIDOO_BUILD).playerClass())
    }

    @Test
    fun theOldPlayerComesFirst() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true)
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, bySchemes = false)
        val choice = choose(ZIDOO_BUILD)
        assertEquals(MOVIE_ACTIVITY, choice.playerClass())
        val seen = choice.candidates.map { it.component.className }
        assertEquals("every app seen is told", listOf(MOVIE_ACTIVITY, ZIDOO_PLAYER_ACTIVITY), seen)
    }

    /** The old player's package without its activity is no player: only what resolves is. */
    @Test
    fun theOldPlayerIsOnlyWhatResolves() {
        apps.install(GALLERY, system = true)
        assertNull(choose(ZIDOO_BUILD).player)
    }

    /** An app the owner installed (a media centre) is not the firmware's player. */
    @Test
    fun anOrdinaryAppIsNotTheFirmwaresPlayer() {
        apps.install("org.videolan.vlc", "org.videolan.vlc.StartActivity", byType = true)
        assertNull(choose(ZIDOO_BUILD).player)
    }

    @Test
    fun ourOwnAppIsNeverThePlayer() {
        apps.install("app.houselights.bench", "app.houselights.MainActivity", system = true)
        assertNull(choose(ZIDOO_BUILD).player)
    }

    /** Elsewhere only the old player is taken: the system's video apps are not looked for. */
    @Test
    fun elsewhereOnlyTheOldPlayerIsFound() {
        apps.install("com.android.tv.player", "com.android.tv.player.VideoActivity", system = true)
        assertNull(choose(OTHER_BUILD).player)
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, bySchemes = false)
        assertEquals(MOVIE_ACTIVITY, choose(OTHER_BUILD).playerClass())
    }

    @Test
    fun nothingThatOpensAVideoIsNoPlayer() {
        apps.install("com.zidoo.poster", system = true)
        val choice = choose(ZIDOO_BUILD)
        assertNull(choice.player)
        assertTrue(choice.candidates.isEmpty())
    }

    /** An app of Zidoo's comes first, then the system's default, then an activity named for films. */
    @Test
    fun playersAreTakenInOrder() {
        val browser = apps.install("com.rtk.browser", "com.rtk.browser.Browse", system = true)!!
        val movies = apps.install("com.rtk.media", "com.rtk.media.MoviePlayer", system = true)!!
        assertEquals("named for films", movies, choose(ZIDOO_BUILD).player?.component)
        apps.makeDefault(browser)
        assertEquals("the default", browser, choose(ZIDOO_BUILD).player?.component)
        val zidoo = apps.install("com.zidoo.files", "com.zidoo.files.Open")!!
        assertEquals("Zidoo's", zidoo, choose(ZIDOO_BUILD).player?.component)
    }
}
