package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A language has one code whatever its source: a file, an NFO or a server. */
class LanguagesTest {
    @Test
    fun aLanguageGoesByOneCode() {
        listOf("fr", "fre", "fra", "fr-CA", "FR_fr").forEach { assertEquals(it, "fr", Languages.code(it)) }
        assertEquals("de", Languages.code("ger"))
        assertEquals("de", Languages.code("deu"))
        assertEquals("pt", Languages.code("pt-BR"))
        assertNull(Languages.code(null))
        assertNull(Languages.code(" "))
    }

    @Test
    fun aBibliographicCodeIsItsLanguageToo() {
        // Burmese, Georgian, Macedonian, Maori, Tibetan and Welsh: their B codes differ from their T codes.
        mapOf("bur" to "my", "geo" to "ka", "mac" to "mk", "mao" to "mi", "tib" to "bo", "wel" to "cy")
            .forEach { (bibliographic, code) -> assertEquals(bibliographic, code, Languages.code(bibliographic)) }
    }

    /** Subtitle files as Bazarr, Plex or Kodi name them beside the video, and the player's own titles. */
    @Test
    fun aFileNamesItsLanguageByItsLastWord() {
        listOf("Film (2020).fr.srt", "Film.fre.forced.srt", "Film.FRA.sdh.srt", "Film.french.srt", "Film.Français.srt", "Film.fr-CA.srt")
            .forEach { assertEquals(it, "fr", Languages.named(it)) }
        assertEquals("pt", Languages.named("Film.pt-BR.hi.srt"))
        assertEquals("en", Languages.named("Film.eng.default.srt"))
        listOf("Film (2020).srt", "Désactivé", "Spider-Man.srt", "", null).forEach { assertNull(it, Languages.named(it)) }
    }

    @Test
    fun aCodeWithoutATwoLetterFormStaysAsItIs() {
        assertEquals("und", Languages.code("und"))
        assertEquals("zxx", Languages.code("ZXX"))
    }
}
