package app.houselights.player

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import app.houselights.player.FakeApps.AppBuild
import app.houselights.player.FakeApps.Companion.FIRMWARE_SCHEMES
import app.houselights.player.FakeApps.Companion.GALLERY
import app.houselights.player.FakeApps.Companion.MOVIE_ACTIVITY
import app.houselights.player.FakeApps.Companion.OTHER_BUILD
import app.houselights.player.FakeApps.Companion.OTHER_KEY
import app.houselights.player.FakeApps.Companion.REALTEK_BUILD
import app.houselights.player.FakeApps.Companion.Z1000_PRO_BUILD
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
import org.robolectric.shadows.ShadowLog
import java.security.MessageDigest

/**
 * Finding the Zidoo player among the video apps, and whether a password may go to it. Robolectric applies no
 * <queries> filtering on Android 11: [PlayerQueriesTest] holds the manifest to what is looked for.
 */
@RunWith(RobolectricTestRunner::class)
class ZidooPlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val apps = FakeApps(context)

    private fun choose(device: DeviceIdentity) = ZidooPlayer(context) { device }.choose()

    private fun ZidooPlayer.Choice.playerClass() = player?.component?.className

    /** A build that names only its chip: a Zidoo app installed with the system still identifies a Zidoo. */
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

    @Test
    fun aPlayerThatAnswersByTypeIsFound() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true, byType = true, schemes = emptyList())
        assertEquals(ZIDOO_PLAYER_ACTIVITY, choose(ZIDOO_BUILD).playerClass())
    }

    /** As on the Z1000 Pro: http, https, file or content addresses only. */
    @Test
    fun aPlayerThatAnswersAsTheZ1000ProsIsFound() {
        FIRMWARE_SCHEMES.forEach { scheme ->
            apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, system = true, schemes = listOf(scheme))
            assertEquals(scheme, ZIDOO_PLAYER_ACTIVITY, choose(ZIDOO_BUILD).playerClass())
            apps.uninstall(ZIDOO_PLAYER)
        }
    }

    /** Beside Zidoo's poster wall, which opens files only, the player is the app that opens shares or streams. */
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

    /** An activity this app may not start (unexported, or behind a permission) is no player: it would throw. */
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
        assertEquals("no decision to take", Decision.NO_PLAYER, choice.decision)
    }

    /** System apps first (old player, then Zidoo's, then any); within each, the default, then a film activity. */
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

    @Test
    fun aSystemPlayerComesBeforeAnOrdinaryOneUnderTheOldPlayersName() {
        val system = apps.install("com.rtk.media", "com.rtk.media.MoviePlayer", system = true)
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, schemes = emptyList())
        val choice = choose(ZIDOO_BUILD)
        assertEquals(system, choice.player?.component)
        assertEquals(Decision.FIRMWARE, choice.decision)
    }

    /** Nor does an ordinary app under a Zidoo package name: any app may take one. */
    @Test
    fun aSystemPlayerComesBeforeAnOrdinaryOneUnderAZidooName() {
        val system = apps.install("com.rtk.media", "com.rtk.media.MoviePlayer", system = true)
        apps.install("com.zidoo.x", "com.zidoo.x.VideoPlayer", certificate = OTHER_KEY)
        val choice = choose(ZIDOO_BUILD)
        assertEquals(system, choice.player?.component)
        assertEquals(Decision.FIRMWARE, choice.decision)
    }

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

    /** On a build with its own keys, nobody else signs with them: an app signed like Zidoo's is the firmware's. */
    @Test
    fun aPlayerSignedLikeTheFirmwareIsFoundUnderAnyName() {
        apps.install("com.zidoo.poster", system = true, certificate = ZIDOO_KEY)
        apps.install(GALLERY, MOVIE_ACTIVITY, certificate = OTHER_KEY, schemes = emptyList())
        val player = apps.install("com.rtk.player", "com.rtk.player.Play", certificate = ZIDOO_KEY)
        val choice = choose(ZIDOO_BUILD)
        assertEquals(player, choice.player?.component)
        assertEquals(Decision.SIGNED_LIKE_FIRMWARE, choice.decision)
    }

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

    /** About says what playback decides, from the same code: found and trusted, found and not, or none. */
    @Test
    fun aboutSaysWhatPlaybackDecides() {
        assertEquals(PlayerStatus.NotDetected, ZidooPlayer(context) { ZIDOO_BUILD }.status())
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true, schemes = emptyList())
        assertEquals(PlayerStatus.Detected(GALLERY), ZidooPlayer(context) { ZIDOO_BUILD }.status())
        assertEquals(PlayerStatus.Unrecognized(GALLERY, Decision.NOT_A_ZIDOO), ZidooPlayer(context) { OTHER_BUILD }.status())
    }

    /** The Z1000 Pro's player, a system app, with an update of it installed over it as [build], else as the firmware has it. */
    private fun z1000Player(build: AppBuild? = null) = apps.reinstall(
        GALLERY,
        MOVIE_ACTIVITY,
        system = true,
        certificate = ZIDOO_KEY,
        schemes = emptyList(),
        updated = build != null,
        build = build ?: AppBuild(),
    )

    /** What the players a secret went to are pinned with, as the app keeps them. */
    private val pins get() = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    /**
     * On a test-keys firmware, whose keys anyone has, the firmware's player updated is no longer the firmware's: trusted
     * at its first password, as any player then, then pinned with its build, so that any later update is a change.
     */
    @Test
    fun onTestKeysAnUpdatedSystemPlayerIsPinnedWithItsBuild() {
        z1000Player(FIRST)
        val players = ZidooPlayer(context) { Z1000_PRO_BUILD }
        val first = players.choose()
        assertEquals(Decision.FIRST_USE, first.decision)
        assertEquals("${FIRST.code}@${FIRST.at}", first.build)
        players.remember(first)
        assertEquals(Decision.REMEMBERED, players.choose().decision)

        z1000Player(LATER)
        val changed = players.choose()
        assertEquals(Decision.CHANGED, changed.decision)
        assertFalse(changed.decision.trusted)
        assertEquals("the same player, its certificate the same", sha256(ZIDOO_KEY), changed.certificate)
    }

    /** Its build is the time Android installed the update too: the same versionCode installed again is a change. */
    @Test
    fun theSameVersionInstalledAgainIsAChange() {
        z1000Player(FIRST)
        val players = ZidooPlayer(context) { Z1000_PRO_BUILD }
        players.remember(players.choose())
        z1000Player(FIRST.copy(at = LATER.at))
        assertEquals(Decision.CHANGED, players.choose().decision)
    }

    /** Nothing changes for a firmware with keys of its own, nor for a system player never updated. */
    @Test
    fun anUpdatedPlayerOnReleaseKeysAndOneNeverUpdatedAreTheFirmwares() {
        z1000Player(FIRST)
        val release = ZidooPlayer(context) { ZIDOO_BUILD }.choose()
        assertEquals(Decision.FIRMWARE, release.decision)
        assertNull("no build pinned there", release.build)
        z1000Player()
        val untouched = ZidooPlayer(context) { Z1000_PRO_BUILD }.choose()
        assertEquals(Decision.FIRMWARE, untouched.decision)
        assertNull(untouched.build)
    }

    /** The firmware's player a password went to untouched is pinned without a build: its first update is a change. */
    @Test
    fun aPlayerPinnedNeverUpdatedIsChangedByItsFirstUpdate() {
        z1000Player()
        val players = ZidooPlayer(context) { Z1000_PRO_BUILD }
        players.remember(players.choose())
        assertEquals("", pins.getString("trusted_build", null))
        z1000Player(FIRST)
        assertEquals(Decision.CHANGED, players.choose().decision)
    }

    /**
     * Houselights 0.3 pinned the player without its build, trusting every system player of the firmware: the first time
     * 0.4.0 looks, the same package and certificate take their build once, unasked; a later update is a change.
     */
    @Test
    fun aPlayerPinnedBy03TakesItsBuildOnceThenALaterUpdateIsAChange() {
        pins.edit { putString("trusted_package", GALLERY).putString("trusted_certificate", sha256(ZIDOO_KEY)) }
        z1000Player(FIRST)
        val players = ZidooPlayer(context) { Z1000_PRO_BUILD }
        assertEquals(Decision.REMEMBERED, players.choose().decision)
        assertEquals("${FIRST.code}@${FIRST.at}", pins.getString("trusted_build", null))
        assertEquals("once only", Decision.REMEMBERED, players.choose().decision)

        z1000Player(LATER)
        assertEquals(Decision.CHANGED, players.choose().decision)
    }

    /** A player 0.3 pinned and found untouched keeps no build: a player never updated, whose first update is a change. */
    @Test
    fun aPlayerPinnedBy03FoundUntouchedIsChangedByItsFirstUpdate() {
        pins.edit { putString("trusted_package", GALLERY).putString("trusted_certificate", sha256(ZIDOO_KEY)) }
        z1000Player()
        val players = ZidooPlayer(context) { Z1000_PRO_BUILD }
        assertEquals(Decision.FIRMWARE, players.choose().decision)
        assertEquals("", pins.getString("trusted_build", null))
        z1000Player(FIRST)
        assertEquals(Decision.CHANGED, players.choose().decision)
    }

    /** A pin 0.3 took of another certificate takes nothing: the player is as changed as it was then. */
    @Test
    fun aPlayerPinnedBy03WithAnotherCertificateTakesNoBuild() {
        pins.edit { putString("trusted_package", GALLERY).putString("trusted_certificate", sha256(OTHER_KEY)) }
        z1000Player(FIRST)
        assertEquals(Decision.CHANGED, ZidooPlayer(context) { Z1000_PRO_BUILD }.choose().decision)
        assertFalse(pins.contains("trusted_build"))
    }

    /**
     * The trust sheet names the player as Android gives it: the name and version its update declares, the day Android
     * installed it; the firmware's own, updated, signed as the player passwords went to.
     */
    @Test
    fun aChangedPlayerIsNamedAsAndroidGivesIt() {
        z1000Player(FIRST)
        val players = ZidooPlayer(context) { Z1000_PRO_BUILD }
        assertNull("trusted, it is not asked about", players.facts(players.choose()))
        players.remember(players.choose())
        z1000Player(LATER)
        val facts = checkNotNull(players.facts(players.choose()))
        assertEquals(GALLERY, facts.packageName)
        assertEquals("Lecteur vidéo", facts.label)
        assertEquals("6.4.42", facts.version)
        assertEquals(LATER.at, facts.updatedAt)
        assertTrue(facts.system)
        assertFalse(facts.otherSignature)
        assertEquals(sha256(ZIDOO_KEY), facts.certificate)
        assertEquals("${LATER.code}@${LATER.at}", facts.build)
        assertEquals("About says it", PlayerStatus.Unrecognized(GALLERY, Decision.CHANGED, facts), players.status())
    }

    /** An app under the name of the player passwords went to, signed by someone else: said as such, and no build. */
    @Test
    fun anAppSignedOtherwiseUnderThePlayersNameIsSaidSo() {
        apps.install(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = OTHER_KEY)
        val players = ZidooPlayer(context) { Z1000_PRO_BUILD }
        players.remember(players.choose())
        apps.reinstall(ZIDOO_PLAYER, ZIDOO_PLAYER_ACTIVITY, certificate = "a-third-key", build = LATER)
        val facts = checkNotNull(players.facts(players.choose()))
        assertTrue(facts.otherSignature)
        assertFalse(facts.system)
        assertNull(facts.build)
    }

    /**
     * Trusting re-pins only the player the sheet named: changed again meanwhile, nothing is pinned; as named, it is
     * remembered, and a later update is a change again. The warning it logs holds no secret.
     */
    @Test
    fun trustPinsOnlyThePlayerTheSheetNamed() {
        z1000Player(FIRST)
        val players = ZidooPlayer(context) { Z1000_PRO_BUILD }
        players.remember(players.choose())
        z1000Player(LATER)
        val seen = checkNotNull(players.facts(players.choose()))
        z1000Player(LATER.copy(code = LATER.code + 1, at = LATER.at + 1))
        assertFalse("changed meanwhile", players.trust(seen))
        assertEquals(Decision.CHANGED, players.choose().decision)

        val now = checkNotNull(players.facts(players.choose()))
        assertTrue(players.trust(now))
        assertEquals(Decision.REMEMBERED, players.choose().decision)
        assertFalse("trusted, nothing to trust again", players.trust(now))
        val said = ShadowLog.getLogsForTag("ZidooPlayer").single { it.type == Log.WARN }.msg
        assertTrue(said, GALLERY in said && sha256(ZIDOO_KEY) in said && now.build.orEmpty() in said)

        z1000Player(LATER.copy(code = LATER.code + 2, at = LATER.at + 2))
        assertEquals(Decision.CHANGED, players.choose().decision)
    }

    /** Nothing is trusted that playback does not refuse as changed: a player trusted, or one that is not a Zidoo's. */
    @Test
    fun onlyAChangedPlayerIsTrusted() {
        z1000Player(FIRST)
        val players = ZidooPlayer(context) { Z1000_PRO_BUILD }
        players.remember(players.choose())
        z1000Player(LATER)
        val seen = checkNotNull(players.facts(players.choose()))
        assertFalse("elsewhere", ZidooPlayer(context) { OTHER_BUILD }.trust(seen))
        assertEquals(Decision.CHANGED, players.choose().decision)
    }

    /** Whether a player opens a stream is asked of its address alone: never its user's part, path or query, where a secret is. */
    @Test
    fun aPlayerIsAskedOfAStreamsAddressAlone() {
        val asked = ZidooPlayer.probe(Uri.parse("https://zidoo:secret@nas:8920/Videos/1/stream.mkv?api_key=token"))
        assertEquals("https://nas:8920", asked.dataString)
        assertEquals("video/*", asked.type)
    }

    private fun sha256(certificate: String) =
        MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** The Z1000 Pro's player updated, as Android gives it, then updated again a few days later. */
        val FIRST = AppBuild(code = 6441, name = "6.4.41", label = "Lecteur vidéo", at = 1_789_000_000_000)
        val LATER = AppBuild(code = 6442, name = "6.4.42", label = "Lecteur vidéo", at = 1_791_800_000_000)
    }
}
