package app.houselights.ui.home

import app.houselights.core.HomeSection
import app.houselights.core.ImageRef
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.SectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's grid as what home read composes it (DESIGN.md, Layout). */
class HomePlanTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun poster(id: String, addedAt: Long? = null) =
        title(id).copy(poster = ImageRef("p-$id") { "https://img.test/$id?w=$it" }, addedAt = addedAt)

    private val continuing = HomeSection(SectionType.CONTINUE, resuming, landscape = true)
    private val next = HomeSection(SectionType.NEXT_UP, listOf(nextEpisode), landscape = true)
    private val latestFilms = HomeSection(SectionType.LATEST, (1..10).map { poster("film$it") }, landscape = false, libraryId = films.id)

    private fun plan(
        sections: List<HomeSection>,
        doors: HomeDoors?,
        libraries: List<Library> = listOf(films, shows),
        sagas: Library? = sagaLibrary,
        search: Boolean = true,
    ) = homePlan(sections, libraries, sagas, doors, search, now)

    private fun shelvesOf(libraries: List<Library>) =
        HomeDoors(shelves = libraries.mapIndexed { i, library -> Shelf(library, i + 1, emptyList()) })

    private val doors = HomeDoors(
        shelves = listOf(
            Shelf(films, 412, (1..12).map { poster("best$it") }),
            Shelf(shows, 38, (1..6).map { poster("show$it") }, newEpisodes = 3),
        ),
        sagas = Shelf(sagaLibrary, 24, listOf(poster("saga1"), poster("saga2"))),
    )

    private fun HomePlan.row(kind: RowKind) = rows.first { it.kind == kind }.doors

    @Test
    fun everythingReadMakesThreeRows() {
        val plan = plan(listOf(continuing, next, latestFilms), doors)
        assertEquals(CaseShows.Resume(resuming[0]), plan.case)
        assertEquals(listOf(RowKind.WATCH, RowKind.LIBRARIES, RowKind.TOOLS), plan.rows.map { it.kind })
        assertEquals(listOf(Door.Next(nextEpisode, null) to 2), plan.row(RowKind.WATCH).take(1))
        assertTrue(plan.row(RowKind.WATCH)[1].first is Door.News)
        assertEquals(listOf(1, 1), plan.row(RowKind.LIBRARIES).map { it.second })
        assertEquals(listOf(1, 1, 2), plan.row(RowKind.TOOLS).map { it.second })
        assertEquals(listOf(Door.Sagas::class, Door.Search::class, Door.Settings::class), plan.row(RowKind.TOOLS).map { it.first::class })
        assertEquals("Right from the case", Door.Library::class, plan.firstLibrary!!::class)
    }

    @Test
    fun nothingInProgressProposesAFilmNotSeen() {
        val unseen = poster("fallguy")
        val plan = plan(listOf(next, latestFilms), doors.copy(unseen = unseen))
        assertEquals(CaseShows.Unseen(unseen), plan.case)
        assertNull("nothing to propose", plan(listOf(next, latestFilms), doors).case)
    }

    @Test
    fun withoutANextEpisodeTheSecondTitleInProgressFollows() {
        val plan = plan(listOf(continuing, latestFilms), doors)
        assertEquals(Door.AlsoResume(resuming[1]), plan.row(RowKind.WATCH)[0].first)
    }

    @Test
    fun withNeitherNewsTakesTheWholeRow() {
        val single = HomeSection(SectionType.CONTINUE, resuming.take(1), landscape = true)
        val watch = plan(listOf(single, latestFilms), doors).row(RowKind.WATCH)
        assertEquals(1, watch.size)
        assertEquals(4, watch[0].second)
        assertEquals("a strip as wide as the row", 9, (watch[0].first as Door.News).posters.size)
    }

    @Test
    fun withoutSagasSearchTakesTheirPlace() {
        val tools = plan(listOf(continuing, latestFilms), doors.copy(sagas = null), sagas = null).row(RowKind.TOOLS)
        assertEquals(listOf(Door.Search::class, Door.Settings::class), tools.map { it.first::class })
        assertEquals(listOf(2, 2), tools.map { it.second })
        assertEquals("its wall twice as wide", 16, (tools[0].first as Door.Search).posters.size)
        // Sagas hidden by the setting: the source lists none, though a door was read before.
        assertEquals(Door.Search::class, plan(listOf(continuing), doors, sagas = null).row(RowKind.TOOLS)[0].first::class)
    }

    @Test
    fun fromOneLibraryToFourEachHasItsDoor() {
        (1..4).forEach { n ->
            val libraries = (1..n).map { Library("l$it", LibraryKind.MIXED, "L$it") }
            val row = plan(listOf(continuing), shelvesOf(libraries), libraries).row(RowKind.LIBRARIES)
            assertEquals("$n libraries", libraries, row.map { (it.first as Door.Library).shelf.library })
            assertEquals(List(n) { 1 }, row.map { it.second })
        }
    }

    @Test
    fun aFifthLibraryGathersTheLastTwoBehindOneDoor() {
        val libraries = (1..5).map { Library("l$it", LibraryKind.MIXED, "L$it") }
        val row = plan(listOf(continuing), shelvesOf(libraries), libraries).row(RowKind.LIBRARIES)
        assertEquals(4, row.size)
        val gathered = row.last().first as Door.Libraries
        assertEquals(libraries.drop(3), gathered.shelves.map { it.library })
    }

    @Test
    fun aCountIsCutInPostersShownNowhereElse() {
        val shared = Shelf(films, 412, latestFilms.items + doors.shelves[0].posters)
        val plan = plan(listOf(continuing, next, latestFilms), doors.copy(shelves = listOf(shared)))
        val strip = (plan.row(RowKind.WATCH)[1].first as Door.News).posters.map { it.id }
        val cut = (plan.row(RowKind.LIBRARIES)[0].first as Door.Library).posters.map { it.id }
        val wall = (plan.row(RowKind.TOOLS)[1].first as Door.Search).posters.map { it.id }
        assertEquals("a poster per digit", 3, cut.size)
        assertTrue("none in the strip: $cut, $strip", cut.none { it in strip })
        assertTrue("none on the wall: $cut, $wall", cut.none { it in wall })
        assertTrue("the wall shows none of the strip", wall.none { it in strip })
    }

    @Test
    fun aDoorSaysWhatIsNewInIt() {
        val fresh = HomeSection(
            SectionType.LATEST,
            listOf(poster("new1", now - day), poster("new2", now - 2 * day), poster("old", now - 30 * day)),
            landscape = false,
            libraryId = films.id,
        )
        val plan = homePlan(listOf(continuing, fresh), listOf(films, shows), sagaLibrary, doors, true, now)
        assertEquals("joined this week", 2, (plan.row(RowKind.WATCH)[1].first as Door.News).thisWeek)
        val libraries = plan.row(RowKind.LIBRARIES).map { it.first as Door.Library }
        assertEquals(3, libraries[1].newEpisodes)
    }

    @Test
    fun theNewestComeFirstAcrossLibraries() {
        val a = HomeSection(SectionType.LATEST, listOf(poster("a1", 10), poster("a2", 1)), landscape = false, libraryId = "a")
        val b = HomeSection(SectionType.LATEST, listOf(poster("b1", 5), poster("b2")), landscape = false, libraryId = "b")
        assertEquals(listOf("a1", "b1", "a2", "b2"), newest(listOf(a, b)).map(MediaItem::id))
    }
}
