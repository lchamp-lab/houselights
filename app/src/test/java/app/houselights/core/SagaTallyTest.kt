package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a saga's page says of its films, on the boards' sagas: the years of the films out, the films held out of them,
 * their length, the films announced apart, what is watched and left, and the film « À suivre ».
 */
class SagaTallyTest {
    @Test
    fun duneInProgress() {
        val dune = held("dune", 2021, length = 155, played = true)
        val two = held("dune-2", 2024, length = 166, leftAt = 92)
        val tally = tally(listOf(part(dune), part(two), upcoming("Dune : Troisième partie", 2026)))
        // « 2021 – 2024   2 films   5 h 21   1 à venir », « 1 film vu sur 2   Il reste 1 h 14 ».
        assertEquals(2021..2024, tally.years)
        assertEquals(2 to 2, tally.owned.size to tally.releasedCount)
        assertEquals(minutes(321), tally.lengthMs)
        assertEquals(1, tally.upcoming)
        assertEquals(1, tally.watched)
        assertTrue(tally.begun)
        assertEquals(minutes(74), tally.leftMs)
        assertEquals("Lire plays the film in progress", two, tally.nextPart?.item)
        assertNull("the film in progress says what is left, not « À suivre »", tally.nextUp)
    }

    @Test
    fun avatarLacksOneAndAwaitsTwo() {
        val avatar = held("avatar", 2009, length = 162, played = true)
        val fire = held("avatar-3", 2025, length = 197)
        val parts = listOf(
            part(avatar),
            absent("Avatar : La Voie de l'eau", 2022),
            part(fire),
            upcoming("Avatar 4", 2029),
            upcoming("Avatar 5", 2031),
        )
        val tally = tally(parts)
        // « 2009 – 2025   2 films sur 3   5 h 59   2 à venir », « 1 film vu sur 2   Il reste 3 h 17 ».
        assertEquals("the films announced are counted apart, not in the years", 2009..2025, tally.years)
        assertEquals(2 to 3, tally.owned.size to tally.releasedCount)
        assertEquals(minutes(359), tally.lengthMs)
        assertEquals(2, tally.upcoming)
        assertEquals(1, tally.watched)
        assertEquals(minutes(197), tally.leftMs)
        assertEquals("« À suivre »: not started, once a film is watched", fire, tally.nextUp)
        assertEquals(parts[2], tally.nextPart)
    }

    @Test
    fun missionImpossibleLacksTwoOfEight() {
        val films = listOf(
            held("mi-1", 1996, length = 110, played = true),
            held("mi-4", 2011, length = 133, played = true),
            held("mi-5", 2015, length = 131, played = true),
            held("mi-6", 2018, length = 147, played = true),
            held("mi-7", 2023, length = 164),
            held("mi-8", 2025, length = 170),
        )
        val parts = listOf(part(films[0]), absent("Mission : Impossible 2", 2000), absent("Mission : Impossible 3", 2006)) +
            films.drop(1).map(::part)
        val tally = tally(parts)
        // « 1996 – 2025   6 films sur 8   14 h 15 », « 4 films vus sur 6   Il reste 5 h 34 ».
        assertEquals(1996..2025, tally.years)
        assertEquals(6 to 8, tally.owned.size to tally.releasedCount)
        assertEquals(minutes(855), tally.lengthMs)
        assertEquals(0, tally.upcoming)
        assertEquals(4, tally.watched)
        assertEquals(minutes(334), tally.leftMs)
        assertEquals("the film after the last one watched", films[4], tally.nextUp)
    }

    /** A box set a source lists by the films it holds alone (Jellyfin): nothing lacking, nothing announced, never begun. */
    @Test
    fun johnWickIsABoxSet() {
        val films = listOf(
            held("jw-1", 2014, length = 101),
            held("jw-2", 2017, length = 122),
            held("jw-3", 2019, length = 131),
            held("jw-4", 2023, length = 169),
        )
        val tally = tally(films.map(::part))
        // « 2014 – 2023   4 films   8 h 43 », no progress line, « Lire   John Wick ».
        assertEquals(2014..2023, tally.years)
        assertEquals(4 to 4, tally.owned.size to tally.releasedCount)
        assertEquals(minutes(523), tally.lengthMs)
        assertEquals(0, tally.upcoming)
        assertFalse(tally.begun)
        assertEquals(films.first(), tally.nextPart?.item)
        assertNull("a saga never begun: Lire says where it starts", tally.nextUp)
    }

    @Test
    fun aSagaAllWatched() {
        val films = listOf(held("dune", 2021, length = 155, played = true), held("dune-2", 2024, length = 166, played = true))
        val tally = tally(films.map(::part) + upcoming("Dune : Troisième partie", 2026))
        // « 2 films vus », nothing left, and « Lire   Dune », the first again.
        assertEquals(2, tally.watched)
        assertTrue(tally.begun)
        assertEquals(0L, tally.leftMs)
        assertEquals(films.first(), tally.nextPart?.item)
        assertNull(tally.nextUp)
    }

    @Test
    fun aLengthUnknownIsNoLength() {
        val tally = tally(listOf(part(held("a", 2001, length = 100)), part(MediaItem("b", MediaKind.MOVIE, "B", year = 2003))))
        assertNull(tally.lengthMs)
        assertEquals("one year", 2001..2001, tally(listOf(part(held("a", 2001, length = 100)))).years)
        assertNull("nothing released, no years", tally(listOf(upcoming("Annoncé", null))).years)
    }

    /** The tally as the page reads it: Lire's film by the saga's films held ([nextUpIn]). */
    private fun tally(parts: List<SagaPart>) = SagaTally(parts, nextUpIn(parts.mapNotNull { it.item }))

    /** A film held, [length] minutes long, left [leftAt] minutes in. */
    private fun held(id: String, year: Int, length: Int, played: Boolean = false, leftAt: Int = 0) = MediaItem(
        id,
        MediaKind.MOVIE,
        id,
        year = year,
        runtimeMs = minutes(length),
        user = UserState(played = played, positionMs = minutes(leftAt)),
    )

    private fun part(film: MediaItem) = SagaPart(film, film.title, film.year)

    private fun absent(title: String, year: Int) = SagaPart(null, title, year)

    private fun upcoming(title: String, year: Int?) = SagaPart(null, title, year, released = false)

    private fun minutes(count: Int) = count * MINUTE_MS

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
