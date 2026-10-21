package app.houselights.ui.detail

import app.houselights.core.Credits
import app.houselights.core.Job
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The technical sheet's tile: in its place for a file's facts, last for credits that arrive after the page, else none. */
class TechTileTest {
    @Test
    fun aFileKeepsTheTileInItsPlace() {
        assertEquals(TechTile.InPlace, techTile(file = true, credits = false))
        assertEquals(TechTile.InPlace, techTile(file = true, credits = true))
    }

    @Test
    fun creditsAloneAddItLast() {
        assertEquals(TechTile.Last, techTile(file = false, credits = true))
    }

    @Test
    fun withoutEitherThereIsNoTile() {
        assertNull(techTile(file = false, credits = false))
    }

    @Test
    fun aShowWithAComposerHasItsTileOnceItsCreditsArrive() {
        val show = MediaItem("show", MediaKind.SERIES, "Severance")
        assertNull(techTile(file = show.media != null, credits = endCrafts(show, null).isNotEmpty()))
        val credits = Credits(crew = mapOf(Job.COMPOSER to listOf(Person("Theodore Shapiro", role = null, image = null))))
        assertEquals(TechTile.Last, techTile(file = show.media != null, credits = endCrafts(show, credits).isNotEmpty()))
    }
}
