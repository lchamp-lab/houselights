package app.houselights.ui.detail

import app.houselights.ui.util.NBSP
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * A saga's film not out yet is « Sortie le 15 décembre 2026 »: its day in full in the viewer's language, unbroken in French
 * and Italian.
 */
class LongDateTest {
    @Test
    fun frenchSaysTheFirstAsSuchAndNeverBreaksADate() {
        assertEquals("15${NBSP}décembre${NBSP}2026", longDate(LocalDate.of(2026, 12, 15), Locale.FRANCE))
        assertEquals("1er${NBSP}mai${NBSP}2027", longDate(LocalDate.of(2027, 5, 1), Locale.FRENCH))
    }

    /** Italian says the first as « 1º », and never puts the article before a date (« l’8 », « l’11 »). */
    @Test
    fun italianSaysTheFirstAsSuch() {
        assertEquals("1º${NBSP}dicembre${NBSP}2026", longDate(LocalDate.of(2026, 12, 1), Locale.ITALY))
        assertEquals("8${NBSP}dicembre${NBSP}2026", longDate(LocalDate.of(2026, 12, 8), Locale.ITALIAN))
        assertEquals("11${NBSP}dicembre${NBSP}2026", longDate(LocalDate.of(2026, 12, 11), Locale.ITALY))
    }

    @Test
    fun otherLanguagesSayItTheirWay() {
        assertEquals("December 15, 2026", longDate(LocalDate.of(2026, 12, 15), Locale.US))
        assertEquals("15. Dezember 2026", longDate(LocalDate.of(2026, 12, 15), Locale.GERMANY))
    }
}
