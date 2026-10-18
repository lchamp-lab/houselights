package app.houselights.ui.detail

import app.houselights.core.Credits
import app.houselights.core.Job
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.houselights.core.hasPage

/** A title's people on its page: direction first in the cast row, the crafts of its end credits, who opens a page. */
class DetailPeopleTest {
    private fun person(name: String, role: String? = null, titles: Int? = null, id: String? = "tmdb:$name") =
        Person(name, role, null, id = id, titleCount = titles)

    private val film =
        MediaItem("m1", MediaKind.MOVIE, "Sicario", directors = listOf("Denis Villeneuve"), cast = listOf(person("Emily Blunt", "Kate")))
    private val credits = Credits(
        cast = listOf(person("Emily Blunt", "Kate", 2), person("Benicio del Toro", "Alejandro", 1)),
        crew = mapOf(
            Job.DIRECTOR to listOf(person("Denis Villeneuve", titles = 4)),
            Job.WRITER to listOf(person("Taylor Sheridan")),
            Job.COMPOSER to listOf(person("Jóhann Jóhannsson")),
            Job.CINEMATOGRAPHY to listOf(person("Roger Deakins")),
            Job.EDITING to listOf(person("Joe Walker")),
            Job.CAST to emptyList(),
        ),
    )

    @Test
    fun theCastRowStartsWithDirection() {
        val people = peopleOf(film, credits, "Réalisation", "Création")
        assertEquals(listOf("Denis Villeneuve", "Emily Blunt", "Benicio del Toro"), people.map { it.name })
        assertEquals("Réalisation", people.first().role)
    }

    @Test
    fun aShowStartsWithItsCreators() {
        val show = MediaItem("s1", MediaKind.SERIES, "Twin Peaks")
        val people = peopleOf(show, Credits(crew = mapOf(Job.CREATOR to listOf(person("David Lynch")))), "Réalisation", "Création")
        assertEquals("Création", people.single().role)
    }

    @Test
    fun withoutCreditsTheCastAlone() {
        assertEquals(film.cast, peopleOf(film, null, "Réalisation", "Création"))
    }

    @Test
    fun onlySomeoneOfSeveralTitlesOpensAPage() {
        assertTrue(person("Denis Villeneuve", titles = 4).hasPage)
        assertFalse("one title leads nowhere", person("Benicio del Toro", titles = 1).hasPage)
        assertFalse("no id to open", person("Emily Blunt", titles = 3, id = null).hasPage)
    }

    @Test
    fun theEndCreditsNameTheCraftsTheHeadDoesNotInTheirOrder() {
        val crafts = endCrafts(film, credits)
        assertEquals(listOf(Job.WRITER, Job.COMPOSER, Job.CINEMATOGRAPHY, Job.EDITING), crafts.map { it.first })
    }

    @Test
    fun threeNamesACraftAtMost() {
        val many = Credits(crew = mapOf(Job.WRITER to (1..5).map { person("Writer $it") }))
        assertEquals(3, endCrafts(film, many).single().second.size)
    }

    @Test
    fun aShowsEndCreditsAreItsMusic() {
        val show = MediaItem("s1", MediaKind.SERIES, "Twin Peaks")
        val crew = mapOf(
            Job.CREATOR to listOf(person("David Lynch")),
            Job.COMPOSER to listOf(person("Angelo Badalamenti")),
            Job.WRITER to listOf(person("Mark Frost")),
        )
        assertEquals(listOf(Job.COMPOSER), endCrafts(show, Credits(crew = crew)).map { it.first })
    }

    @Test
    fun withoutCreditsNoCrafts() {
        assertEquals(emptyList<Pair<Job, List<String>>>(), endCrafts(film, null))
    }
}
