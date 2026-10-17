package app.houselights.ui.library

import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** What the wall's folio says of the title selected, as each sort files it. */
class WallFolioTest {
    private fun item(title: String, year: Int? = null, addedAt: Long? = null, rating: Double? = null) =
        MediaItem(id = title, kind = MediaKind.MOVIE, title = title, year = year, addedAt = addedAt, communityRating = rating)

    @Test
    fun byTitleTheLetterItIsFiledUnder() {
        assertEquals("B", folioOf(item("The Batman"), SortOption.TITLE))
        assertEquals("A", folioOf(item("L'Arnaque"), SortOption.TITLE))
        assertEquals("C", folioOf(item("Ça"), SortOption.TITLE))
        assertEquals("O", folioOf(item("Œdipe roi"), SortOption.TITLE))
        assertEquals("#", folioOf(item("2001 : l'Odyssée de l'espace"), SortOption.TITLE))
    }

    @Test
    fun byYearMonthAddedOrScore() {
        assertEquals("1994", folioOf(item("Léon", year = 1994), SortOption.YEAR))
        assertNull(folioOf(item("Sans date"), SortOption.YEAR))
        val october = Calendar.getInstance(TimeZone.getDefault()).apply { set(2026, Calendar.OCTOBER, 16, 12, 0) }.timeInMillis
        assertEquals("Octobre 2026", folioOf(item("Ajouté", addedAt = october), SortOption.ADDED, Locale.FRENCH))
        assertEquals("7,9", folioOf(item("Noté", rating = 7.9), SortOption.RATING, Locale.FRENCH))
    }
}
