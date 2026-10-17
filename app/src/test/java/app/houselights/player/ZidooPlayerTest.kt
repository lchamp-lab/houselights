package app.houselights.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.houselights.player.FakeApps.Companion.GALLERY
import app.houselights.player.FakeApps.Companion.MOVIE_ACTIVITY
import app.houselights.player.FakeApps.Companion.OTHER_BUILD
import app.houselights.player.FakeApps.Companion.OTHER_KEY
import app.houselights.player.FakeApps.Companion.REALTEK_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_KEY
import app.houselights.player.FakeApps.Companion.ZIDOO_PLAYER
import app.houselights.player.FakeApps.Companion.ZIDOO_PLAYER_ACTIVITY
import app.houselights.player.ZidooPlayer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** Finding the Zidoo player among the apps that open a video, and whether a password may go to it, on Android 9 and 11. */
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

    @Test
    fun aSystemPlayerOnAZidooIsTheFirmwares() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true)
        assertEquals(Decision.FIRMWARE, choose(ZIDOO_BUILD).decision)
    }

    /** Elsewhere, no app gets a password, the system's own com.android.gallery3d (AOSP's gallery) neither. */
    @Test
    fun elsewhereNoPlayerIsTrusted() {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, bySchemes = false)
        val choice = choose(OTHER_BUILD)
        assertEquals(MOVIE_ACTIVITY, choice.playerClass())
        assertEquals(Decision.NOT_A_ZIDOO, choice.decision)
        assertFalse(choice.decision.trusted)
    }

    @Test
    fun aPlayerSignedLikeAFirmwareAppOfZidoosIsTrusted() {
        apps.install("com.zidoo.poster", system = true, certificate = ZIDOO_KEY)
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = ZIDOO_KEY, bySchemes = false)
        val choice = choose(ZIDOO_BUILD)
        assertEquals(Decision.SIGNED_LIKE_FIRMWARE, choice.decision)
        assertEquals("the certificate is told, by its SHA-256", sha256(ZIDOO_KEY), choice.certificate)
    }

    /** Built with AOSP's test keys, which anyone has: a certificate shared with the firmware proves nothing. */
    @Test
    fun onTestKeysASharedCertificateProvesNothing() {
        apps.install("com.zidoo.poster", system = true, certificate = ZIDOO_KEY)
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = ZIDOO_KEY, bySchemes = false)
        assertEquals(Decision.FIRST_USE, choose(ZIDOO_BUILD.copy(tags = "test-keys")).decision)
    }

    /** Neither a system app nor signed like one: the first player a password goes to is the one trusted. */
    @Test
    fun aPlayerIsTrustedOnFirstUse() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY)
        val players = ZidooPlayer(context) { ZIDOO_BUILD }
        val first = players.choose()
        assertEquals(Decision.FIRST_USE, first.decision)
        assertTrue(first.decision.trusted)
        assertEquals("asking again remembers nothing", Decision.FIRST_USE, players.choose().decision)

        players.remember(first)
        assertEquals(Decision.REMEMBERED, players.choose().decision)
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = "a-third-key")
        assertEquals(Decision.CHANGED, players.choose().decision)
        assertFalse(Decision.CHANGED.trusted)
    }

    /** Another player than the one trusted, whatever it is called, is refused as well. */
    @Test
    fun anotherPlayerAfterTheOneTrustedIsRefused() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY)
        val players = ZidooPlayer(context) { ZIDOO_BUILD }
        players.remember(players.choose())
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, bySchemes = false)
        val choice = players.choose()
        assertEquals("the one trusted comes first", ZIDOO_PLAYER_ACTIVITY, choice.playerClass())
        assertEquals(Decision.REMEMBERED, choice.decision)
        apps.uninstall(ZIDOO_PLAYER)
        assertEquals(Decision.CHANGED, players.choose().decision)
    }

    /** The system's player a password went to is remembered too: an ordinary app in its place is no first use. */
    @Test
    fun aSystemPlayerAPasswordWentToIsRememberedToo() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true, certificate = ZIDOO_KEY)
        val players = ZidooPlayer(context) { ZIDOO_BUILD }
        players.remember(players.choose())
        apps.uninstall(ZIDOO_PLAYER)
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, bySchemes = false)
        assertEquals(Decision.CHANGED, players.choose().decision)
    }

    /** A player no password may go to is not remembered. */
    @Test
    fun anUntrustedPlayerIsNotRemembered() {
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, bySchemes = false)
        val elsewhere = ZidooPlayer(context) { OTHER_BUILD }
        elsewhere.remember(elsewhere.choose())
        assertEquals(Decision.FIRST_USE, choose(ZIDOO_BUILD).decision)
    }

    @Test
    fun aPlayerWhoseCertificateCannotBeReadIsNotTrusted() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY)
        assertEquals(Decision.NO_CERTIFICATE, choose(ZIDOO_BUILD).decision)
    }

    @Test
    fun noPlayerIsNoDecisionToTake() {
        assertEquals(Decision.NO_PLAYER, choose(ZIDOO_BUILD).decision)
    }

    private fun sha256(certificate: String) =
        MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString("") { "%02x".format(it) }
}
