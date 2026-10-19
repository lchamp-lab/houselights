package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Edition names, known whatever their spelling and found among a file name's words. */
class CatalogRulesTest {
    @Test
    fun editionsAreKnownWhateverTheirSpelling() {
        assertEquals(Editions.DIRECTORS_CUT, Editions.of("Director's Cut"))
        assertEquals(Editions.DIRECTORS_CUT, Editions.of("DIRECTORS_CUT"))
        assertEquals(Editions.DIRECTORS_CUT, Editions.of("Montage du réalisateur"))
        assertEquals(Editions.EXTENDED, Editions.of("Version longue"))
        assertEquals(Editions.EXTENDED, Editions.of("Extended Edition"))
        assertEquals(Editions.THEATRICAL, Editions.of("Theatrical"))
        assertEquals(Editions.FINAL_CUT, Editions.of("The Final Cut"))
        assertEquals(Editions.REMASTERED, Editions.of("Remastered Edition"))
        assertEquals("one no token knows stays as written", "Fan Cut", Editions.of(" Fan Cut "))
        assertNull(Editions.of(""))
    }

    @Test
    fun anEditionIsFoundAmongWordsAsWholeWordsOnly() {
        assertEquals(Editions.SPECIAL, Editions.find("Special Edition 1080p BluRay"))
        assertEquals(Editions.IMAX, Editions.find("IMAX.2160p"))
        assertEquals(Editions.UNRATED, Editions.find(" - Unrated"))
        assertNull(Editions.find("Imaxx 1080p"))
        assertNull(Editions.find("1080p x265"))
    }
}
