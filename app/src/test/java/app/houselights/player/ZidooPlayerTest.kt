package app.houselights.player

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.houselights.player.FakeApps.Companion.FIRMWARE_SCHEMES
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
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true, byType = true, schemes = emptyList())
        assertEquals(ZIDOO_PLAYER_ACTIVITY, choose(ZIDOO_BUILD).playerClass())
    }

    /**
     * A firmware's player under another name answers as the Z1000 Pro's does, read on the device: at
     * an http, https, file or content address only. Each one finds it.
     */
    @Test
    fun aPlayerThatAnswersAsTheZ1000ProsIsFound() {
        FIRMWARE_SCHEMES.forEach { scheme ->
            apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true, schemes = listOf(scheme))
            assertEquals(scheme, ZIDOO_PLAYER_ACTIVITY, choose(ZIDOO_BUILD).playerClass())
            apps.uninstall(ZIDOO_PLAYER)
        }
    }

    /**
     * A firmware without the old player, whose player is a system app under a name of its own, beside
     * Zidoo's poster wall, which opens files only (as on the Z1000 Pro): the player is the one that opens
     * a share or a stream, not the poster wall, though its name is Zidoo's.
     */
    @Test
    fun aPosterWallOfZidoosIsNoPlayerBesideOneThatStreams() {
        apps.install("com.zidoo.poster", "com.zidoo.poster.main.PosterWallActivity", system = true, schemes = listOf("file"))
        apps.install("com.rtk.video", "com.rtk.video.VideoPlayer", system = true, schemes = listOf("http", "file", "content"))
        assertEquals("com.rtk.video.VideoPlayer", choose(ZIDOO_BUILD).playerClass())
    }

    @Test
    fun theOldPlayerComesFirst() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true)
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
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

    /** An activity other apps may not start, or only with a permission this one lacks, is no player: starting it would throw. */
    @Test
    fun aPlayerTheAppMayNotStartIsNone() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true, exported = false)
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList(), exported = false)
        assertNull(choose(ZIDOO_BUILD).player)
        apps.install("com.zidoo.vplayer", "com.zidoo.vplayer.PlayActivity", system = true, permission = "com.zidoo.permission.PLAY")
        assertNull(choose(ZIDOO_BUILD).player)
        val startable = apps.install("com.rtk.media", "com.rtk.media.MoviePlayer", system = true)
        assertEquals(startable, choose(ZIDOO_BUILD).player?.component)
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
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        assertEquals(MOVIE_ACTIVITY, choose(OTHER_BUILD).playerClass())
    }

    @Test
    fun nothingThatOpensAVideoIsNoPlayer() {
        apps.install("com.zidoo.poster", system = true)
        val choice = choose(ZIDOO_BUILD)
        assertNull(choice.player)
        assertTrue(choice.candidates.isEmpty())
    }

    /**
     * The system's apps first: the old player, then an app of Zidoo's, then any other; within each, the
     * system's default, then an activity named for films. An ordinary app of Zidoo's name comes after.
     */
    @Test
    fun playersAreTakenInOrder() {
        val browser = apps.install("com.rtk.browser", "com.rtk.browser.Browse", system = true)!!
        val movies = apps.install("com.rtk.media", "com.rtk.media.MoviePlayer", system = true)!!
        assertEquals("named for films", movies, choose(ZIDOO_BUILD).player?.component)
        apps.makeDefault(browser)
        assertEquals("the default", browser, choose(ZIDOO_BUILD).player?.component)
        apps.install("com.zidoo.files", "com.zidoo.files.Open")
        assertEquals("an ordinary app of Zidoo's name after", browser, choose(ZIDOO_BUILD).player?.component)
        val zidoo = apps.install("com.zidoo.media", "com.zidoo.media.Open", system = true)!!
        assertEquals("Zidoo's", zidoo, choose(ZIDOO_BUILD).player?.component)
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        assertEquals("the old player", MOVIE_ACTIVITY, choose(ZIDOO_BUILD).playerClass())
    }

    /** Before any password went to a player, a system player under another name beats an ordinary app under the old player's. */
    @Test
    fun aSystemPlayerComesBeforeAnOrdinaryOneUnderTheOldPlayersName() {
        val system = apps.install("com.rtk.media", "com.rtk.media.MoviePlayer", system = true)
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, schemes = emptyList())
        val choice = choose(ZIDOO_BUILD)
        assertEquals(system, choice.player?.component)
        assertEquals(Decision.FIRMWARE, choice.decision)
    }

    /** Nor an ordinary app under a name of Zidoo's: any app may take one. */
    @Test
    fun aSystemPlayerComesBeforeAnOrdinaryOneUnderAZidooName() {
        val system = apps.install("com.rtk.media", "com.rtk.media.MoviePlayer", system = true)
        apps.install("com.zidoo.x", "com.zidoo.x.VideoPlayer", certificate = OTHER_KEY)
        val choice = choose(ZIDOO_BUILD)
        assertEquals(system, choice.player?.component)
        assertEquals(Decision.FIRMWARE, choice.decision)
    }

    /** The player a password went to comes first while it keeps its certificate; changed, a system player does. */
    @Test
    fun aRememberedPlayerComesFirstOnlyWithItsCertificate() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY)
        val players = ZidooPlayer(context) { ZIDOO_BUILD }
        players.remember(players.choose())
        val system = apps.install("com.rtk.media", "com.rtk.media.MoviePlayer", system = true)
        assertEquals(ZIDOO_PLAYER_ACTIVITY, players.choose().playerClass())
        assertEquals(Decision.REMEMBERED, players.choose().decision)

        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = "a-third-key")
        val choice = players.choose()
        assertEquals(system, choice.player?.component)
        assertEquals(Decision.FIRMWARE, choice.decision)
    }

    @Test
    fun aSystemPlayerOnAZidooIsTheFirmwares() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true)
        assertEquals(Decision.FIRMWARE, choose(ZIDOO_BUILD).decision)
    }

    /** Elsewhere, no app gets a password, the system's own com.android.gallery3d (AOSP's gallery) neither. */
    @Test
    fun elsewhereNoPlayerIsTrusted() {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        val choice = choose(OTHER_BUILD)
        assertEquals(MOVIE_ACTIVITY, choice.playerClass())
        assertEquals(Decision.NOT_A_ZIDOO, choice.decision)
        assertFalse(choice.decision.trusted)
    }

    @Test
    fun aPlayerSignedLikeAFirmwareAppOfZidoosIsTrusted() {
        apps.install("com.zidoo.poster", system = true, certificate = ZIDOO_KEY)
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = ZIDOO_KEY, schemes = emptyList())
        val choice = choose(ZIDOO_BUILD)
        assertEquals(Decision.SIGNED_LIKE_FIRMWARE, choice.decision)
        assertEquals("the certificate is told, by its SHA-256", sha256(ZIDOO_KEY), choice.certificate)
    }

    /**
     * An ordinary app under any name, signed like a system app of Zidoo's, is the firmware's as well:
     * on a build of its own keys, no one else signs with them. It comes before an ordinary app under
     * the old player's name, which anyone may take.
     */
    @Test
    fun aPlayerSignedLikeTheFirmwareIsFoundUnderAnyName() {
        apps.install("com.zidoo.poster", system = true, certificate = ZIDOO_KEY)
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, schemes = emptyList())
        val player = apps.install("com.rtk.player", "com.rtk.player.Play", certificate = ZIDOO_KEY)
        val choice = choose(ZIDOO_BUILD)
        assertEquals(player, choice.player?.component)
        assertEquals(Decision.SIGNED_LIKE_FIRMWARE, choice.decision)
    }

    /** On a build of AOSP's test keys, as the Z1000 Pro's is, it proves nothing: an ordinary app under another name is no player. */
    @Test
    fun onTestKeysAPlayerSignedLikeTheFirmwareIsNotFound() {
        apps.install("com.zidoo.poster", system = true, certificate = ZIDOO_KEY)
        apps.install("com.rtk.player", "com.rtk.player.Play", certificate = ZIDOO_KEY)
        assertNull(choose(ZIDOO_BUILD.copy(tags = "test-keys")).player)
    }

    /** Built with AOSP's test keys, which anyone has: a certificate shared with the firmware proves nothing. */
    @Test
    fun onTestKeysASharedCertificateProvesNothing() {
        apps.install("com.zidoo.poster", system = true, certificate = ZIDOO_KEY)
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = ZIDOO_KEY, schemes = emptyList())
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
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, schemes = emptyList())
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
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, schemes = emptyList())
        assertEquals(Decision.CHANGED, players.choose().decision)
    }

    /** A player no password may go to is not remembered. */
    @Test
    fun anUntrustedPlayerIsNotRemembered() {
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, schemes = emptyList())
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

    /** About says what playback decides, from the same code: found and trusted, found and not, or none. */
    @Test
    fun aboutSaysWhatPlaybackDecides() {
        assertEquals(PlayerStatus.NotDetected, ZidooPlayer(context) { ZIDOO_BUILD }.status())
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        assertEquals(PlayerStatus.Detected(GALLERY), ZidooPlayer(context) { ZIDOO_BUILD }.status())
        assertEquals(PlayerStatus.Unrecognized(GALLERY, Decision.NOT_A_ZIDOO), ZidooPlayer(context) { OTHER_BUILD }.status())
    }

    /** Whether a player opens a stream is asked of its address alone: never its user's part, path or query, where a secret is. */
    @Test
    fun aPlayerIsAskedOfAStreamsAddressAlone() {
        val asked = ZidooPlayer.probe(Uri.parse("https://zidoo:secret@nas:8920/Videos/1/stream.mkv?api_key=token"))
        assertEquals("https://nas:8920", asked.dataString)
        assertEquals("video/*", asked.type)
    }

    @Test
    fun aDeviceIsNamedByItsMakerAndModel() {
        assertEquals("ZIDOO Z9X Pro", ZIDOO_BUILD.name)
        assertEquals("the maker said once", "Zidoo Z1000 Pro", ZIDOO_BUILD.copy(model = "Zidoo Z1000 Pro").name)
    }

    private fun sha256(certificate: String) =
        MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString("") { "%02x".format(it) }
}
