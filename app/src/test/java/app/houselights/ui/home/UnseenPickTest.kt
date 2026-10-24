package app.houselights.ui.home

import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.film
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the case proposes with nothing in progress: a film not seen, else a show not seen, else any title. */
class UnseenPickTest {
    private val films = Library("films", LibraryKind.MOVIES, "Films")
    private val shows = Library("shows", LibraryKind.SHOWS, "Séries")
    private val sagas = Library("sagas", LibraryKind.COLLECTIONS, "Sagas")

    private fun seen(item: MediaItem) = item.copy(user = UserState(played = true))

    private val show = MediaItem("severance", MediaKind.SERIES, "Severance")

    /** A source whose walls are [walls], each title readable whole. */
    private fun source(walls: Map<String, List<MediaItem>>) = FakeProvider().apply {
        this.walls = walls
        items.putAll(walls.values.flatten().associateBy { it.id })
    }

    @Test
    fun aFilmNotSeenFirst() = runTest {
        val source = source(mapOf("films" to listOf(seen(film("heat")), film("alien")), "shows" to listOf(show)))
        assertEquals("alien", UnseenPick().of(source, listOf(films, shows, sagas))?.id)
    }

    @Test
    fun everyFilmSeenAShowNotSeen() = runTest {
        val source = source(mapOf("films" to listOf(seen(film("heat"))), "shows" to listOf(show)))
        val proposed = UnseenPick().of(source, listOf(films, shows))
        assertEquals("severance", proposed?.id)
        assertEquals("its page opens", true, proposed?.let { CaseShows.Proposed(it).opensPage })
    }

    /** Everything seen: any title, said without "Pas encore vu", and kept while nothing not seen comes. */
    @Test
    fun everythingSeenAnyTitleKeptUntilOneNotSeenComes() = runTest {
        val source = source(mapOf("films" to listOf(seen(film("heat"))), "shows" to listOf(seen(show))))
        val pick = UnseenPick()
        val proposed = checkNotNull(pick.of(source, listOf(films, shows)))
        assertEquals("heat", proposed.id)
        assertEquals(false, CaseShows.Proposed(proposed).notSeen)
        assertEquals("kept", "heat", pick.of(source, listOf(films, shows))?.id)

        source.walls = mapOf("films" to listOf(seen(film("heat")), film("alien")))
        source.items["alien"] = film("alien")
        assertEquals("a film not seen takes its place", "alien", pick.of(source, listOf(films, shows))?.id)
    }

    @Test
    fun aFilmNotSeenStaysUntilSeen() = runTest {
        val source = source(mapOf("films" to (1..20).map { film("f$it") }))
        val pick = UnseenPick()
        val first = checkNotNull(pick.of(source, listOf(films)))
        assertEquals(first.id, pick.of(source, listOf(films))?.id)
        source.items[first.id] = seen(first)
        source.walls = mapOf("films" to (1..20).map { source.items.getValue("f$it") })
        val next = checkNotNull(pick.of(source, listOf(films)))
        assertEquals(true, next.id != first.id && CaseShows.Proposed(next).notSeen)
    }

    @Test
    fun anEmptyLibraryHasNothingToPropose() = runTest {
        assertNull(UnseenPick().of(source(emptyMap()), listOf(films, shows, sagas)))
    }
}
