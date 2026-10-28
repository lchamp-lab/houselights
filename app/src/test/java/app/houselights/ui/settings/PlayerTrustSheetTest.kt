package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.player.PlayerFacts
import app.houselights.ui.design.HlMotion
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.keyInSheet
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.ErrorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * DESIGN.md, Sheets: « Faire confiance au nouveau lecteur » names the player playback refuses as changed and asks, the
 * answer that changes nothing first and focused; trusting takes a Down then an OK given once focus has settled, and pins
 * only the player the sheet named; Back answers nothing; Jellyfin is named only while it is the source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class PlayerTrustSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    /** The player playback refuses as changed ([now]), as `ZidooPlayer` finds it, trusted only as it is now. */
    private class FakeTrust(var now: PlayerFacts?) : PlayerTrust {
        val trusted = mutableListOf<PlayerFacts>()

        override fun changed(): PlayerFacts? = now

        override fun trust(seen: PlayerFacts): Boolean {
            if (seen != now) return false
            trusted += seen
            now = null
            return true
        }
    }

    private var jellyfin by mutableStateOf(false)

    /** How many times the sheet said it trusted, once gone. */
    private var trusts = 0

    private fun show(player: FakeTrust): TrustAsked {
        val asked = TrustAsked(player)
        asked.ask()
        compose.setTvContent { PlayerTrustSheet(asked, jellyfin) { trusts++ } }
        compose.waitForIdle()
        return asked
    }

    private fun said(text: String) = compose.onNodeWithText(text, substring = true)

    @Test
    fun theSheetNamesThePlayerAndFocusesTheAnswerThatChangesNothing() {
        show(FakeTrust(UPDATED))
        compose.onNodeWithText("Faire confiance au nouveau lecteur").assertExists()
        said("Lecteur vidéo").assertExists()
        said("6.4.42").assertExists()
        // The day Android installed the update, in the sentence, held to its « le ».
        said("lecteur le${NB}12${NB}octobre${NB}2026$NB: jusqu’à sa prochaine mise à jour").assertExists()
        said("les mots de passe de vos dossiers. Sinon, désinstallez les mises à jour du lecteur.").assertExists()
        compose.onNodeWithText("Ne pas lui faire confiance").assertIsFocused()
        said("Il ne recevra aucun mot de passe").assertExists()
        compose.onNodeWithText("Faire confiance à ce lecteur").assertExists()
        said("Il recevra vos mots de passe").assertExists()
        said("Autre signature").assertDoesNotExist()
        said(UPDATED.packageName).assertDoesNotExist()
    }

    @Test
    fun backAndTheAnswerThatChangesNothingTrustNothing() {
        val player = FakeTrust(UPDATED)
        val asked = show(player)
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        assertNull(asked.facts)

        asked.ask()
        compose.waitForIdle()
        compose.onNodeWithText("Ne pas lui faire confiance").assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertNull(asked.facts)
        assertEquals(emptyList<PlayerFacts>(), player.trusted)
        assertEquals(0, trusts)
    }

    @Test
    fun downThenOkTrustsThePlayerTheSheetNames() {
        val player = FakeTrust(UPDATED)
        val asked = show(player)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Faire confiance à ce lecteur").assertIsFocused()
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(UPDATED), player.trusted)
        assertNull("the sheet is gone", asked.facts)
        assertEquals(1, trusts)
    }

    /** As `UpdateSheet`'s row: OK right after focus arrives, a Down and an OK given in one go, does nothing. */
    @Test
    fun anOkTooSoonAfterFocusArrivesTrustsNothing() {
        val player = FakeTrust(UPDATED)
        show(player)
        compose.mainClock.autoAdvance = false
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Faire confiance à ce lecteur").assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("too soon", emptyList<PlayerFacts>(), player.trusted)

        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(UPDATED), player.trusted)
    }

    /** OK pressed on « Ne pas lui faire confiance », then Down, let go on « Faire confiance à ce lecteur »: nothing. */
    @Test
    fun anOkLetGoThereThatWentDownElsewhereTrustsNothing() {
        val player = FakeTrust(UPDATED)
        val asked = show(player)
        compose.keyInSheet(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Faire confiance à ce lecteur").assertIsFocused()
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        compose.keyInSheet(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(emptyList<PlayerFacts>(), player.trusted)
        assertEquals("still asking", UPDATED, asked.facts)
    }

    /** Updated again before the answer: nothing is pinned, and the sheet names the player found now, read anew. */
    @Test
    fun aPlayerChangedAgainMeanwhileIsNamedAnewAndNotTrusted() {
        val player = FakeTrust(UPDATED)
        val asked = show(player)
        player.now = AGAIN
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(emptyList<PlayerFacts>(), player.trusted)
        assertEquals(AGAIN, asked.facts)
        said("6.4.43").assertExists()
        compose.onNodeWithText("Ne pas lui faire confiance").assertIsFocused()

        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(AGAIN), player.trusted)
        assertNull(asked.facts)
    }

    /** Jellyfin's access is named only while Jellyfin is the source, the one case its token goes to the player. */
    @Test
    fun jellyfinIsNamedOnlyWhileItIsTheSource() {
        show(FakeTrust(UPDATED))
        said("Jellyfin").assertDoesNotExist()
        jellyfin = true
        compose.waitForIdle()
        said("les mots de passe de vos dossiers et l’accès à Jellyfin. Sinon").assertExists()
    }

    /** An app under the player's name signed by someone else: « Autre signature », and what to do about it. */
    @Test
    fun aPlayerSignedOtherwiseSaysSoAndItsOwnSentence() {
        show(FakeTrust(SIGNED_OTHERWISE))
        said("Autre signature").assertExists()
        said("Ce n’est plus le lecteur qui a reçu vos mots de passe.").assertExists()
        said("installé vous-même le${NB}12${NB}octobre${NB}2026$NB:").assertExists()
        said("Sinon, réinstallez le lecteur fourni avec la Zidoo.").assertExists()
        said("jusqu’à sa prochaine mise à jour").assertDoesNotExist()
    }

    /** Only « Faire confiance au nouveau lecteur » opens the sheet, on a player changed alone; it trusts nothing itself. */
    @Test
    fun onlyItsFixOpensTheSheetAndOnAChangedPlayerAlone() {
        val fixed = mutableListOf<ErrorAction>()
        val player = FakeTrust(null)
        val asked = TrustAsked(player)
        val fix = asked.fixing { fixed += it }
        fix(ErrorAction.RECONNECT)
        assertEquals(listOf(ErrorAction.RECONNECT), fixed)
        fix(ErrorAction.TRUST_PLAYER)
        assertNull("no player changed: nothing to ask", asked.facts)
        player.now = UPDATED
        fix(ErrorAction.TRUST_PLAYER)
        assertEquals(UPDATED, asked.facts)
        assertEquals(listOf(ErrorAction.RECONNECT), fixed)
        assertEquals(emptyList<PlayerFacts>(), player.trusted)
    }

    private companion object {
        const val NB = " "
        const val FRAME_MS = 32L

        /** Noon on 12 October 2026 on this machine's clock: the day the sheet says. */
        val OCTOBER_12 = LocalDate.of(2026, 10, 12).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        /** The Z1000 Pro's player updated since the last password went to it. */
        val UPDATED = PlayerFacts(
            packageName = "com.android.gallery3d",
            label = "Lecteur vidéo",
            version = "6.4.42",
            updatedAt = OCTOBER_12,
            system = true,
            otherSignature = false,
            certificate = "c0ffee",
            build = "6442@$OCTOBER_12",
        )

        /** The same, updated again while the sheet was open. */
        val AGAIN = UPDATED.copy(version = "6.4.43", build = "6443@$OCTOBER_12")

        /** An ordinary app under the player's name, signed by someone else. */
        val SIGNED_OTHERWISE = UPDATED.copy(system = false, otherSignature = true, certificate = "badc0de", build = null)
    }
}
