package app.houselights.ui.settings

import app.houselights.core.TrackPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The default languages' sheets: each option asks for its own language, the player's choice or the app's, and the
 * language saved shows its option selected again; the player then asks for it in every title (audio_idx).
 */
@RunWith(RobolectricTestRunner::class)
class TrackLanguageSheetsTest {
    private val codes = trackLanguages.tags

    /** The languages as the sheets list them after their first options, each under its own name. */
    private val listed = trackLanguages.options(Locale.FRENCH)

    @Test
    fun eachAudioOptionAsksForItsLanguageAndShowsSelectedAgain() {
        assertNull("Lecteur", audioOf(0, codes))
        assertEquals("Langue de l'interface", TrackPreferences.INTERFACE, audioOf(1, codes))
        codes.forEachIndexed { i, code ->
            assertEquals(trackLanguageName(code, Locale.forLanguageTag(code)), listed[i].label)
            assertEquals(code, audioOf(i + 2, codes))
        }
        for (option in 0 until 2 + codes.size) assertEquals("option $option", option, audioOption(audioOf(option, codes), codes))
    }

    @Test
    fun eachSubtitleLanguageOptionAsksForItsLanguageAndShowsSelectedAgain() {
        assertNull("Langue de l'interface", subtitleLanguageOf(0, codes))
        codes.forEachIndexed { i, code -> assertEquals(code, subtitleLanguageOf(i + 1, codes)) }
        for (option in 0 until 1 + codes.size) {
            assertEquals("option $option", option, subtitleLanguageOption(subtitleLanguageOf(option, codes), codes))
        }
    }

    /**
     * A code saved with a region or as files tag it shows its language's option, the sheets listing nothing more:
     * Norwegian Bokmål too, which they list under Norwegian.
     */
    @Test
    fun aLanguageSavedOtherwiseShowsItsOption() {
        for ((language, forms) in mapOf("fr" to listOf("fr-CA", "fre", "FR"), "no" to listOf("nb", "nb-NO", "nob"))) {
            val at = codes.indexOf(language)
            for (saved in forms) {
                assertEquals(saved, codes, trackCodes(saved, codes))
                assertEquals(saved, at + 2, audioOption(saved, codes))
                assertEquals(saved, at + 1, subtitleLanguageOption(saved, codes))
            }
        }
    }

    /**
     * A language saved that the sheets do not list (left by another version, with a region or as files tag it) comes
     * first among their languages by its two letters, shown chosen in both sheets as its row names it, and chosen again
     * it stays; the player's choice and the app's add nothing.
     */
    @Test
    fun aSavedLanguageNoSheetListsIsItsOwnOptionChosen() {
        for ((saved, code) in mapOf("xx" to "xx", "ar-EG" to "ar", "ara" to "ar", "ice" to "is")) {
            assertFalse("$code is not listed", code in codes)
            val listed = trackCodes(saved, codes)
            assertEquals(saved, listOf(code) + codes, listed)
            assertEquals(saved, 2, audioOption(saved, listed))
            assertEquals(saved, code, audioOf(2, listed))
            assertEquals(saved, 1, subtitleLanguageOption(saved, listed))
            assertEquals(saved, code, subtitleLanguageOf(1, listed))
        }
        for (saved in listOf(null, TrackPreferences.INTERFACE)) assertEquals("$saved", codes, trackCodes(saved, codes))
    }
}
