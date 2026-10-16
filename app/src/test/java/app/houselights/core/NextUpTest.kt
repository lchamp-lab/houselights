package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The film Lire starts in a collection, among its films in release order. */
class NextUpTest {
    private fun film(id: String, user: UserState = UserState()) = MediaItem(id, MediaKind.MOVIE, id, user = user)

    @Test
    fun theFilmInProgressComesFirst() {
        val films = listOf(film("a"), film("b", UserState(positionMs = 1_000)))
        assertEquals("b", nextUpIn(films)?.id)
    }

    @Test
    fun elseTheFirstNotSeen() {
        val films = listOf(film("a", UserState(played = true)), film("b"), film("c"))
        assertEquals("b", nextUpIn(films)?.id)
    }

    @Test
    fun aFilmSeenWithAPositionLeftIsNotInProgress() {
        val films = listOf(film("a", UserState(played = true, positionMs = 1_000)), film("b"))
        assertEquals("b", nextUpIn(films)?.id)
    }

    @Test
    fun allSeenStartsTheFirstAgainAndNoneStartsNothing() {
        assertEquals("a", nextUpIn(listOf(film("a", UserState(played = true)), film("b", UserState(played = true))))?.id)
        assertNull(nextUpIn(emptyList()))
    }
}
