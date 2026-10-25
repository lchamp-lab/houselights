package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is kept of a title whose files left, and to which row it goes back: by TMDB id, else by name in its folder;
 * never to another film of that name, nor from an older removal than the latest.
 */
class KeptStatesTest {
    @Suppress("LongParameterList") // One per column a test sets.
    private fun film(
        id: Long,
        path: String,
        tmdbId: Int? = null,
        userTmdbId: Int? = null,
        versionOf: Long? = null,
        played: Boolean = false,
        positionMs: Long = 0,
        lastPlayedAt: Long? = null,
        favorite: Boolean = false,
        poster: String? = null,
        addedAt: Long = 0,
        parts: List<String> = emptyList(),
    ) = KeptRow(
        state = UserState(id, played, positionMs, null, lastPlayedAt, favorite, userTmdbId, poster, null, null, addedAt),
        kind = ItemKind.MOVIE, sourceId = FOLDER, seriesId = null, filePath = path, parts = parts, tmdbId = tmdbId,
        identity = userTmdbId ?: tmdbId, matchKey = null, seasonNumber = null, episodeNumber = null, lastEpisodeNumber = null,
        versionOf = versionOf, showIdentity = null, showKey = null, audio = null, subtitles = null, tracksAt = null,
    )

    @Suppress("LongParameterList") // One per column a test sets.
    private fun episode(id: Long, show: Int?, season: Int, number: Int, last: Int? = null, played: Boolean = false) = KeptRow(
        state = UserState(id, played, 0, null, null, false, null, null, null, null, 0),
        kind = ItemKind.EPISODE, sourceId = FOLDER, seriesId = SERIES, filePath = "Severance (2022)/Season $season/$id.mkv",
        parts = emptyList(), tmdbId = null, identity = null, matchKey = null, seasonNumber = season, episodeNumber = number,
        lastEpisodeNumber = last, versionOf = null, showIdentity = show, showKey = "severance|2022", audio = null, subtitles = null,
        tracksAt = null,
    )

    private fun kept(row: KeptRow) = checkNotNull(KeptStates.of(row))

    @Test
    fun aFilmIsKeptByItsTmdbIdAndItsNameAndYear() {
        val dune = kept(film(1, "Dune (2021)/Dune (2021) Bluray-1080p.mkv", tmdbId = DUNE, played = true))
        assertEquals("film:tmdb:$DUNE", dune.title)
        assertEquals("dune|2021", dune.nameKey)
        assertEquals(DUNE to "Dune (2021)", dune.identity to dune.folder)
        assertEquals("the user's match first", "film:tmdb:$OTHER", kept(film(1, "Dune (2021).mkv", DUNE, userTmdbId = OTHER)).title)
        assertEquals("film:name:$FOLDER:Dune (2021):dune|2021", kept(film(1, "Dune (2021)/Dune (2021).mkv")).title)
    }

    @Test
    fun aFilmWithoutAYearIsKeptByItsFolder() {
        val holidays = kept(film(1, "Home videos/Holidays.mkv", favorite = true))
        assertEquals("Home videos:holidays", holidays.nameKey)
        assertEquals("film:name:$FOLDER:Home videos:holidays", holidays.title)
        assertNotEquals(holidays.nameKey, kept(film(2, "Other videos/Holidays.mkv")).nameKey)
    }

    /** A file relinks pair as the film it replaces is found under the same name, a remake under another. */
    @Test
    fun aRelinkAndAKeptStateKnowAFilmByOneName() {
        val heat = KnownFile(id = 1, filePath = "Heat (1995) CD1.avi", parts = listOf("Heat (1995) CD2.avi"))
        fun settled(path: String) = MissingFiles.settle(
            SourceKind.MOVIES, listOf(heat), Stacks.of(SourceKind.MOVIES, listOf(RemoteFile(path, 1_000))), MissingFiles.PassTime(NOW),
        )
        val upgrade = "Heat (1995) Remux-1080p.mkv"
        assertEquals(listOf(1L to RemoteFile(upgrade, 1_000)), settled(upgrade).relinked)
        assertEquals(KeptKeys.filmName(heat.filePath, stacked = true), KeptKeys.filmName(upgrade, stacked = false))

        val remake = "Heat (1986).mkv"
        assertEquals(emptyList<Pair<Long, RemoteFile>>(), settled(remake).relinked)
        assertNotEquals(KeptKeys.filmName(heat.filePath, stacked = true), KeptKeys.filmName(remake, stacked = false))
    }

    @Test
    fun entriesNamingTwoFilmsAreNotGivenBack() {
        val disney = kept(film(1, "Pinocchio (2022).mkv", tmdbId = DISNEY, played = true))
        val delToro = kept(film(2, "Films/Pinocchio (2022).mkv", tmdbId = DEL_TORO, played = true))
        assertEquals(disney.nameKey, delToro.nameKey)
        assertNull(KeptStates.usable(listOf(disney, delToro), identity = null))
        assertNull(KeptStates.atBirth(listOf(disney, delToro), FOLDER, "", tmdbOpen = false))
        assertEquals("once TMDB names it", delToro, KeptStates.usable(listOf(disney, delToro), DEL_TORO))
    }

    @Test
    fun anEntryOfAnotherFilmIsNotGivenBack() {
        val disney = kept(film(1, "Pinocchio (2022).mkv", tmdbId = DISNEY, played = true))
        assertNull(KeptStates.usable(listOf(disney), DEL_TORO))
        assertEquals(disney, KeptStates.usable(listOf(disney), DISNEY))
    }

    /** A user matches by hand where a name is ambiguous: given back in its own folder only, as Radarr upgrades in place. */
    @Test
    fun aMatchByHandIsGivenBackAtBirthOnlyInItsFolder() {
        val chosen = listOf(kept(film(1, "Pinocchio (2022)/Pinocchio (2022).mkv", DISNEY, userTmdbId = DEL_TORO, played = true)))
        assertEquals(chosen.single(), KeptStates.atBirth(chosen, FOLDER, "Pinocchio (2022)", tmdbOpen = true))
        assertNull("another folder", KeptStates.atBirth(chosen, FOLDER, "Films", tmdbOpen = false))
        assertNull("another source", KeptStates.atBirth(chosen, FOLDER + 1, "Pinocchio (2022)", tmdbOpen = false))
    }

    @Test
    fun aTitleTmdbMatchedWaitsForItsMatchWhileTmdbCanBeAsked() {
        val matched = listOf(kept(film(1, "Dune (2021).mkv", tmdbId = DUNE, played = true)))
        assertNull(KeptStates.atBirth(matched, FOLDER, "", tmdbOpen = true))
        assertEquals("TMDB out of reach", matched.single(), KeptStates.atBirth(matched, FOLDER, "Elsewhere", tmdbOpen = false))
        val unmatched = listOf(kept(film(1, "Dune (2021).mkv", played = true)))
        assertEquals(unmatched.single(), KeptStates.atBirth(unmatched, FOLDER, "Elsewhere", tmdbOpen = true))

        // TMDB knows the new file by no id: by name, from its own folder only.
        assertEquals(matched.single(), KeptStates.fromHere(matched, FOLDER, ""))
        assertNull(KeptStates.fromHere(matched, FOLDER, "Elsewhere"))
    }

    @Test
    fun copiesKeptTogetherKeepTheLatestPlayAndTheEarliestArrival() {
        val main = film(1, "Dune (2021) 1080p.mkv", DUNE, played = true, lastPlayedAt = 10, poster = "/main.jpg", addedAt = 5)
        val copy = film(
            2, "Dune (2021) 2160p.mkv", DUNE, versionOf = 1, played = true, positionMs = 600_000, lastPlayedAt = 20,
            favorite = true, poster = "/copy.jpg", addedAt = 3,
        )
        val dune = KeptStates.plan(listOf(copy, main), staying = emptyList()).kept.single()
        assertEquals(Triple(true, 600_000L, 20L), Triple(dune.played, dune.positionMs, dune.lastPlayedAt))
        assertTrue(dune.favorite)
        assertEquals("the main copy's", "/main.jpg", dune.userPosterPath)
        assertEquals(3L, dune.addedAt)
    }

    @Test
    fun nothingIsKeptOfATitleTheUserNeverTouched() {
        val plan = KeptStates.plan(listOf(film(1, "Dune (2021).mkv", DUNE, lastPlayedAt = 10, addedAt = 5)), staying = emptyList())
        assertEquals(emptyList<KeptStateEntity>(), plan.kept)
        assertEquals("what an older removal kept goes", listOf("film:tmdb:$DUNE"), plan.emptied)
    }

    @Test
    fun aRowStayingWithTheSameTitleTakesItsStateInstead() {
        val leaving = film(1, "Dune (2021) 1080p.mkv", DUNE, played = true, lastPlayedAt = 10)
        val staying = listOf(film(3, "Arrival (2016).mkv", ARRIVAL), film(4, "4K/Dune (2021).mkv", DUNE), film(2, "Dune.mkv", DUNE))
        val plan = KeptStates.plan(listOf(leaving), staying)
        assertEquals("nothing kept", emptyList<KeptStateEntity>(), plan.kept)
        assertEquals(emptyList<String>(), plan.emptied)
        assertEquals("the oldest, the title's main row", listOf(2L), plan.handed.map { it.first.id })

        // An episode: of the same show, season and episodes.
        val pilot = episode(5, SEVERANCE, season = 1, number = 1, played = true)
        val others = listOf(episode(6, SEVERANCE, 1, 1, last = 2), episode(7, OTHER, 1, 1), episode(8, SEVERANCE, 1, 1))
        assertEquals(listOf(8L), KeptStates.plan(listOf(pilot), others).handed.map { it.first.id })
    }

    @Test
    fun aStateGivenBackFillsOnlyWhatTheRowLacks() {
        val dune = kept(
            film(1, "Dune (2021).mkv", DUNE, DUNE, played = true, lastPlayedAt = 10, favorite = true, poster = "/kept.jpg", addedAt = 5),
        )
        val born = UserState(2, false, 0, null, null, false, null, null, null, null, addedAt = 50)
        assertEquals(UserState(2, true, 0, null, 10, true, DUNE, "/kept.jpg", null, null, addedAt = 5), KeptStates.onto(born, dune))

        val watching = born.copy(positionMs = 300_000, lastPlayedAt = 40, userPosterPath = "/own.jpg")
        val given = KeptStates.onto(watching, dune)
        assertEquals("its own resume point", 300_000L to 40L, given.positionMs to given.lastPlayedAt)
        assertTrue("watched, as the title was", given.played)
        assertEquals("/own.jpg", given.userPosterPath)
    }

    @Test
    fun anEpisodeIsKeptByItsShowSeasonAndEpisodes() {
        val double = kept(episode(1, SEVERANCE, season = 1, number = 1, last = 2, played = true))
        assertEquals("ep:tmdb:$SEVERANCE:1:1-2", double.title)
        assertEquals("severance|2022:1:1-2", double.nameKey)
        assertEquals(SEVERANCE, double.identity)
        assertEquals("ep:name:$FOLDER:severance|2022:0:3-3", kept(episode(2, show = null, season = 0, number = 3)).title)

        // Given back to a file of the same episodes only.
        val rows = listOf(episode(3, SEVERANCE, 1, 1), episode(4, SEVERANCE, 1, 2), episode(5, SEVERANCE, 1, 1, last = 2))
        assertEquals(listOf(5L), KeptStates.onEpisodes(listOf(double), rows).map { it.first.id })
    }

    @Test
    fun anEpisodeOfAShowTmdbMatchedWaitsForItsShowsMatch() {
        val pilot = listOf(kept(episode(1, SEVERANCE, season = 1, number = 1, played = true)))
        assertNull(KeptStates.forEpisode(pilot, show = null, showPending = true))
        assertEquals("TMDB out of reach, or knowing no such show", pilot.single(), KeptStates.forEpisode(pilot, null, showPending = false))
        assertNull("another show", KeptStates.forEpisode(pilot, show = OTHER, showPending = false))
    }

    private companion object {
        const val NOW = 1_000_000L
        const val FOLDER = 1L
        const val SERIES = 7L
        const val DUNE = 438631
        const val ARRIVAL = 329865
        const val DISNEY = 532639
        const val DEL_TORO = 555604
        const val OTHER = 1
        const val SEVERANCE = 95396
    }
}
