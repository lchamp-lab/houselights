package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

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
        assertEquals("fr", Languages.named("French(FR [PGS]) PGS"))
        listOf("Film (2020).srt", "Désactivé", "Spider-Man.srt", "Film.VO.srt", "Film VOSTFR", "", null)
            .forEach { assertNull(it, Languages.named(it)) }
    }

    /** A file's word is folded as titles are: « Føroyskt » and « foroyskt » alike. */
    @Test
    fun aLanguageNameReadsLigaturesAsTheyAreSpelledOut() {
        listOf("Film.føroyskt.srt", "Film.Føroyskt.srt", "Film.foroyskt.srt").forEach { assertEquals(it, "fo", Languages.named(it)) }
    }

    /** Spelling ligatures out merges no two languages' names: each name folds with the same languages as before. */
    @Test
    fun spellingLigaturesOutMergesNoLanguages() {
        val names = Locale.getISOLanguages().flatMap { code ->
            val language = Locale.forLanguageTag(code)
            listOf(Locale.ENGLISH, Locale.FRENCH, language).map { language.getDisplayLanguage(it) to code }
        }
        fun languagesBy(fold: (String) -> String) = names.groupBy({ fold(it.first) }, { it.second }).values.map { it.toSet() }.toSet()
        assertEquals(languagesBy(SortKeys::foldAccents), languagesBy(SortKeys::fold))
    }

    @Test
    fun aCodeWithoutATwoLetterFormStaysAsItIs() {
        assertEquals("und", Languages.code("und"))
        assertEquals("zxx", Languages.code("ZXX"))
    }
}
