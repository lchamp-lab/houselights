package app.houselights.core

import app.houselights.providers.local.EpisodeState
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.SagaFilmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which film Lire starts in a collection, its films taken in release order, and the one rule it shares. */
class NextUpTest {
    private fun film(id: String, user: UserState = UserState()) = MediaItem(id, MediaKind.MOVIE, id, user = user)

    /**
     * One rule for a show's next episode, a saga's Lire and its film on home's À suivre: in progress, else the one
     * after the last watched, else the first not seen. Each title is watched (W), in progress (P) or not seen (U).
     */
    @Test
    fun episodesSagasAndHomeFollowOneRule() {
        val table = listOf(
            "WUWU" to 3,
            "UW" to 0,
            "WPU" to 1,
            "UWP" to 2,
            "UU" to 0,
            "WW" to null,
        )
        for ((titles, next) in table) {
            fun position(c: Char) = if (c == 'P') 60_000L else 0L
            val films = titles.mapIndexed { i, c -> film("$i", UserState(played = c == 'W', positionMs = position(c))) }
            // All seen, Lire starts the first again.
            assertEquals("Lire of $titles", (next ?: 0).toString(), nextUpIn(films)?.id)
            val episodes = titles.mapIndexed { i, c -> EpisodeState(i.toLong(), 1, 1, i + 1, c == 'W', position(c)) }
            assertEquals("episode of $titles", next?.toLong(), LocalProvider.nextOf(episodes)?.id)
            val saga = titles.mapIndexed { i, c -> SagaFilmState(i.toLong(), 7, c == 'W', position(c), i.toLong().takeIf { c != 'U' }) }
            // In progress, home's Reprendre shows it.
            val home = next?.takeIf { titles[it] != 'P' }?.toLong()
            assertEquals("À suivre of $titles", home, LocalProvider.nextInSagas(saga).singleOrNull()?.id)
        }
    }

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
