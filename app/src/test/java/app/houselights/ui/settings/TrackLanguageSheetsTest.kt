package app.houselights.ui.settings

import app.houselights.core.TrackPreferences
import org.junit.Assert.assertEquals
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

    /** A code saved with a region or as files tag it shows its language's option, the sheets listing nothing more. */
    @Test
    fun aLanguageSavedOtherwiseShowsItsOption() {
        val french = codes.indexOf("fr")
        for (saved in listOf("fr-CA", "fre", "FR")) {
            assertEquals(saved, codes, trackCodes(saved, codes))
            assertEquals(saved, french + 2, audioOption(saved, codes))
            assertEquals(saved, french + 1, subtitleLanguageOption(saved, codes))
        }
    }

    /**
     * A language saved that the sheets do not list (one left by another version) comes first among their languages,
     * shown chosen as its row names it, and chosen again it stays; the player's choice and the app's add nothing.
     */
    @Test
    fun aSavedLanguageNoSheetListsIsItsOwnOptionChosen() {
        val listed = trackCodes("xx", codes)
        assertEquals(listOf("xx") + codes, listed)
        assertEquals(2, audioOption("xx", listed))
        assertEquals("xx", audioOf(2, listed))
        assertEquals(1, subtitleLanguageOption("xx", listed))
        assertEquals("xx", subtitleLanguageOf(1, listed))
        for (saved in listOf(null, TrackPreferences.INTERFACE)) assertEquals("$saved", codes, trackCodes(saved, codes))
    }
}
