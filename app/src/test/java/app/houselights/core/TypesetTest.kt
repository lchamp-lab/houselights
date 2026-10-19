package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Text typeset for the screen: apostrophes, ellipses and, in French, spaced punctuation and quotes. */
class TypesetTest {
    private val nb = Typography.nbsp

    @Test
    fun punctuationStaysWithItsWord() {
        assertEquals("un «${nb}fixer${nb}», connaît", Typeset.text("un « fixer », connaît", french = true))
        assertEquals("Attention${nb}: danger${nb}!", Typeset.text("Attention : danger !", french = true))
    }

    @Test
    fun nothingStaysNothing() {
        assertNull(Typeset.text(null as String?))
    }

    @Test
    fun anApostropheBetweenLettersIsTypographic() {
        assertEquals("Deux familles s’affrontent", Typeset.text("Deux familles s'affrontent", french = true))
        assertEquals("Peter O’Toole", Typeset.text("Peter O'Toole", french = false))
        assertEquals("rock’n’roll", Typeset.text("rock'n'roll", french = false))
    }

    @Test
    fun singleQuotesAreNeverPaired() {
        // Apostrophes of elision, not quotation marks.
        listOf("Rock 'n' Roll", "'til Death", "The Smiths' Last Song", "Isabella 'Bella' Hawthorne", "'90s", "5'10\"").forEach {
            assertEquals(it, Typeset.text(it, french = true))
        }
    }

    @Test
    fun threeDotsAreAnEllipsis() {
        assertEquals("Et puis…", Typeset.text("Et puis...", french = true))
        assertEquals("Wait… what?", Typeset.text("Wait... what?", french = false))
    }

    @Test
    fun straightDoubleQuotesBecomeGuillemetsInFrench() {
        assertEquals("Surnommé «${nb}le Boucher${nb}», il…", Typeset.text("Surnommé \"le Boucher\", il...", french = true))
        assertEquals("«${nb}Bella${nb}» et «${nb}Bo${nb}»", Typeset.text("\" Bella \" et \"Bo\"", french = true))
        assertEquals("«${nb}Silence${nb}!${nb}»", Typeset.text("\"Silence !\"", french = true))
    }

    @Test
    fun doubleQuotesStayWhenNotAFrenchPair() {
        assertEquals("Known as \"the Butcher\"", Typeset.text("Known as \"the Butcher\"", french = false))
        // Unbalanced, or measures in inches: no pair to set.
        assertEquals("un \"fixer, connaît", Typeset.text("un \"fixer, connaît", french = true))
        assertEquals("Un écran 65\" et \"Bella\" plus", Typeset.text("Un écran 65\" et \"Bella\" plus", french = true))
        assertEquals("Des vinyles 12\" et 7\"", Typeset.text("Des vinyles 12\" et 7\"", french = true))
    }
}
