package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A language goes by one code, whatever wrote it: a file, an NFO, a server. */
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
        // Burmese, Georgian, Macedonian, Maori, Tibetan and Welsh: what the sheet's own table had left out.
        mapOf("bur" to "my", "geo" to "ka", "mac" to "mk", "mao" to "mi", "tib" to "bo", "wel" to "cy")
            .forEach { (bibliographic, code) -> assertEquals(bibliographic, code, Languages.code(bibliographic)) }
    }

    @Test
    fun aCodeWithoutATwoLetterFormStaysAsItIs() {
        assertEquals("und", Languages.code("und"))
        assertEquals("zxx", Languages.code("ZXX"))
    }
}
