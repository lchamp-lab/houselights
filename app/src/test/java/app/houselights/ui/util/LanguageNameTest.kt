package app.houselights.ui.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** A track's language as the screen names it, whatever code the file wrote. */
class LanguageNameTest {
    private val before = Locale.getDefault()

    @Before
    fun french() = Locale.setDefault(Locale.FRENCH)

    @After
    fun restore() = Locale.setDefault(before)

    @Test
    fun everyCodeOfALanguageNamesIt() {
        listOf("fr", "fre", "fra", "fr-CA", "fre-CA", "FR_fr").forEach { assertEquals(it, "Français", languageName(it)) }
        listOf("cy", "wel", "cym").forEach { assertEquals(it, "Gallois", languageName(it)) }
    }

    @Test
    fun aCodeNoLanguageAnswersToIsShownAsItIs() {
        assertEquals("qaa", languageName("qaa"))
        assertNull(languageName(" "))
        assertNull(languageName(null))
    }
}
