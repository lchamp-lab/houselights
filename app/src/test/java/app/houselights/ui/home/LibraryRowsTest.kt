package app.houselights.ui.home

import app.houselights.core.Job
import app.houselights.core.Person
import app.houselights.core.PersonPage
import app.houselights.core.PersonTitle
import app.houselights.core.SectionType
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.film
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The library's rows under home's: a director's films and the best rated, picked daily, changing at 6. */
class LibraryRowsTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun director(n: Int) = Person("Director $n", null, null, id = "tmdb:$n", titleCount = 3)
    private fun page(n: Int) = PersonPage(
        director(n),
        mapOf(Job.DIRECTOR to 3),
        (1..3).map { PersonTitle(film("d$n-$it"), listOf(Job.DIRECTOR), null) },
    )

    private val catalog = FakeCatalog().apply {
        directors = (1..3).map(::director)
        pages = (1..3).associate { "tmdb:$it" to page(it) }
        best = (1..6).map { film("best$it") }
    }
    private var clock = at(17, 20)
    private var source = FakeProvider().also { it.catalog = catalog }
    private val rows = LibraryRows({ source }, now = { clock }, zone = { zone })

    @Test
    fun theDayTurnsAtSixInTheMorning() {
        assertEquals(LibraryRows.dayOf(at(17, 20), zone), LibraryRows.dayOf(at(18, 2), zone))
        assertTrue(LibraryRows.dayOf(at(18, 7), zone) > LibraryRows.dayOf(at(18, 2), zone))
    }

    @Test
    fun aDirectorsFilmsThenTheBestRated() = runTest {
        val drawn = rows.today()
        assertEquals(listOf(SectionType.DIRECTOR, SectionType.BEST_RATED), drawn.map { it.type })
        val shown = drawn.first()
        assertEquals("the director's own films", catalog.pages.getValue(shown.person!!.id!!).titles.map { it.item }, shown.items)
    }

    @Test
    fun theSameAllEveningAndAnotherDirectorTheNextDay() = runTest {
        val tonight = rows.today().first().person
        catalog.calls.clear()
        clock = at(18, 1)
        assertEquals(tonight, rows.today().first().person)
        assertEquals("drawn once", emptyList<String>(), catalog.calls)
        clock = at(18, 9)
        assertTrue(rows.today().first().person != tonight)
    }

    /** Folders, then a server the same evening: its own rows, never the folders' titles. */
    @Test
    fun anotherSourceTheSameDayDrawsItsOwn() = runTest {
        rows.today()
        val server = FakeCatalog().apply { best = (1..6).map { film("server$it") } }
        source = FakeProvider().also { it.catalog = server }
        assertEquals(listOf(server.best), rows.today().map { it.items })
    }

    @Test
    fun aRowTooShortIsLeftOut() = runTest {
        catalog.best = listOf(film("only"))
        catalog.pages = catalog.pages.mapValues { (_, p) -> p.copy(titles = p.titles.take(1)) }
        assertEquals(emptyList<SectionType>(), rows.today().map { it.type })
    }

    @Test
    fun aSourceWithoutCatalogWritesNone() = runTest {
        assertEquals(emptyList<Any>(), LibraryRows({ FakeProvider() }).today())
    }
}
