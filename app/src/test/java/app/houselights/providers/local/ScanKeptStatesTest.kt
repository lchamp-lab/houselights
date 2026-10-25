package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A title whose files left, back: what the user did comes back with it (watched state, resume point, favourite, a
 * match by hand, chosen artwork, tracks, its place in "recently added"), once, and never to another title of that name.
 */
@RunWith(RobolectricTestRunner::class)
class ScanKeptStatesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private val nas get() = bench.nas
    private val shows get() = bench.showsNas
    private val dao get() = bench.dao
    private val tmdb get() = bench.tmdb
    private val uhdNas = FakeRemoteBrowser()

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false)
    }

    @After
    fun close() = bench.close()

    private fun cached(path: String) = File(path.removePrefix("file://"))

    /** Films with Heat beside, so the folder never lists empty, which would keep every title ([EmptyFolderException]). */
    private suspend fun filmsFolder(): SourceEntity = bench.folder("Films").also { nas.put("Heat (1995)/Heat (1995).mkv") }

    /** Shows with Andor beside, for the same reason. */
    private suspend fun showsFolder(): SourceEntity =
        bench.folder("Séries", SourceKind.SHOWS).also { shows.put("Andor/Season 1/Andor - S01E01.mkv") }

    /** Severance's episode files in [folder], in order. */
    private suspend fun severance(folder: SourceEntity) =
        dao.filesOf(folder.id).filter { it.filePath.orEmpty().startsWith("Severance") }.sortedBy { it.episodeNumber }

    private fun nfo(tmdbId: Int) = "<movie><title>Dune</title><uniqueid type=\"tmdb\">$tmdbId</uniqueid></movie>"

    private suspend fun assertNothingForTrakt() =
        assertEquals("nothing sent to Trakt", emptyList<TraktPendingEntity>(), bench.db.trakt().pending())

    @Test
    fun aFilmDeletedThenAddedBackGetsBackWhatTheUserDid() = runTest {
        val films = filmsFolder()
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.putImage("Dune (2021)/poster.jpg")
        bench.scan()
        val dune = bench.item(films, "Dune (2021)/Dune (2021).mkv")
        val poster = checkNotNull(dune.posterPath)
        dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = PLAYED_AT)
        dao.setFavorite(dune.id, true)
        dao.setUserPoster(dune.id, poster)
        bench.db.trackChoices().save(TrackChoiceEntity(tracksKey(dune), "eng", null, at = 5))

        // Deleted from the NAS, its poster with it: gone at the next pass.
        nas.remove("Dune (2021)/Dune (2021).mkv")
        nas.remove("Dune (2021)/poster.jpg")
        bench.scan()
        assertNull(dao.item(dune.id))
        assertTrue("the poster the user chose stays", cached(poster).isFile)

        nas.put("Dune (2021)/Dune (2021).mkv", size = 9_000_000_000)
        bench.scan()
        val back = bench.item(films, "Dune (2021)/Dune (2021).mkv")
        assertEquals(Triple(600_000L, 9_000_000L, PLAYED_AT), Triple(back.positionMs, back.durationMs, back.lastPlayedAt))
        assertTrue(back.favorite)
        assertEquals(poster, back.userPosterPath)
        assertEquals("its place in recently added", dune.addedAt, back.addedAt)
        assertEquals("eng", bench.db.trackChoices().choice(tracksKey(back))?.audio)
        assertEquals("given back once", emptyList<String>(), bench.keptTitles())
        assertNothingForTrakt()
    }

    @Test
    fun aRemakeGetsNothingFromTheFilmBefore() = runTest {
        val films = filmsFolder()
        nas.put("Dune (1984)/Dune (1984).mkv")
        bench.scan()
        val lynch = bench.item(films, "Dune (1984)/Dune (1984).mkv")
        dao.setPlayed(lynch.id, played = true, now = bench.now)
        dao.setFavorite(lynch.id, true)
        nas.remove("Dune (1984)/Dune (1984).mkv")
        bench.scan()

        nas.put("Dune (2021)/Dune (2021).mkv")
        bench.scan()
        val villeneuve = bench.item(films, "Dune (2021)/Dune (2021).mkv")
        assertFalse(villeneuve.played)
        assertFalse(villeneuve.favorite)
        assertEquals("kept for the film before", listOf("film:name:${films.id}:Dune (1984):dune|1984"), bench.keptTitles())
    }

    /** Named otherwise, the new file is the same film by its NFO's TMDB id, even without TMDB. */
    @Test
    fun aFilmNamedOtherwiseGetsItsStateBackFromItsNfo() = runTest {
        val films = filmsFolder()
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.putText("Dune (2021)/Dune (2021).nfo", nfo(DUNE))
        bench.scan()
        val dune = bench.item(films, "Dune (2021)/Dune (2021).mkv")
        assertEquals(DUNE, dune.tmdbId)
        dao.setPlayed(dune.id, played = true, now = bench.now)
        nas.remove("Dune (2021)/Dune (2021).mkv")
        nas.remove("Dune (2021)/Dune (2021).nfo")
        bench.scan()

        nas.put("Dune Part One/Dune.Part.One.2160p.mkv")
        nas.putText("Dune Part One/Dune.Part.One.2160p.nfo", nfo(DUNE))
        bench.scan()
        val back = bench.item(films, "Dune Part One/Dune.Part.One.2160p.mkv")
        assertEquals(DUNE, back.tmdbId)
        assertTrue(back.played)
        assertEquals(emptyList<String>(), bench.keptTitles())
    }

    /** While TMDB is out of reach, a film TMDB matched waits for its match: TMDB may yet name another film. */
    @Test
    fun aFilmTmdbCouldNotMatchGetsItsStateOnceItDoes() = runTest {
        bench.withTmdb = true
        tmdb.movie(DUNE, "Dune", 2021)
        val films = filmsFolder()
        nas.put("Dune (2021) Bluray-1080p.mkv")
        bench.scan()
        val dune = bench.item(films, "Dune (2021) Bluray-1080p.mkv")
        assertEquals(DUNE, dune.tmdbId)
        dao.setPlayed(dune.id, played = true, now = bench.now)
        nas.remove("Dune (2021) Bluray-1080p.mkv")
        bench.scan()

        bench.tmdbOutOfReach()
        nas.put("Dune (2021) Remux-2160p.mkv")
        bench.scan()
        val born = bench.item(films, "Dune (2021) Remux-2160p.mkv")
        assertNull(born.tmdbId)
        assertFalse("not yet", born.played)

        bench.tmdbBack()
        bench.scan()
        val matched = bench.item(films, "Dune (2021) Remux-2160p.mkv")
        assertEquals(DUNE, matched.tmdbId)
        assertTrue(matched.played)
        assertEquals(emptyList<String>(), bench.keptTitles())
        assertNothingForTrakt()
    }

    /** The old file was matched through its NFO; the new one has none, and TMDB finds no such film: same folder, same film. */
    @Test
    fun aFilmMatchedByItsNfoGetsItsStateBackWhenTmdbFindsNoneForTheNewFile() = runTest {
        bench.withTmdb = true
        tmdb.movie(DUNE, "Dune : Première partie", 2021)
        val films = filmsFolder()
        nas.put("Dune (2021)/Dune (2021) Bluray-1080p.mkv")
        nas.putText("Dune (2021)/Dune (2021) Bluray-1080p.nfo", nfo(DUNE))
        bench.scan()
        val dune = bench.item(films, "Dune (2021)/Dune (2021) Bluray-1080p.mkv")
        assertEquals(DUNE, dune.tmdbId)
        dao.setPlayed(dune.id, played = true, now = bench.now)
        nas.remove("Dune (2021)/Dune (2021) Bluray-1080p.mkv")
        nas.remove("Dune (2021)/Dune (2021) Bluray-1080p.nfo")
        bench.scan()

        nas.put("Dune (2021)/Dune (2021) Remux-2160p.mkv")
        bench.scan()
        val back = bench.item(films, "Dune (2021)/Dune (2021) Remux-2160p.mkv")
        assertNull("TMDB knows no film Dune of 2021", back.tmdbId)
        assertTrue(back.played)
        assertEquals(emptyList<String>(), bench.keptTitles())
    }

    /** Radarr upgrades a film the user matched by hand in its folder: the new file is that film too. */
    @Test
    fun aFilmIdentifiedByHandIsMatchedAsChosenOnceReplaced() = runTest {
        val films = pinocchioIdentifiedByHand()
        bench.nas.put("Pinocchio (2022)/Pinocchio (2022) Bluray-2160p.mkv")
        bench.scan()

        val back = bench.item(films, "Pinocchio (2022)/Pinocchio (2022) Bluray-2160p.mkv")
        assertEquals(DEL_TORO to DEL_TORO, back.userTmdbId to back.tmdbId)
        assertTrue(back.played)
        assertEquals(emptyList<String>(), bench.keptTitles())
    }

    /** Elsewhere, a file of that name may be the other film, the very case a match by hand settles. */
    @Test
    fun aFilmIdentifiedByHandIsNotForcedOnAnotherFilmOfThatName() = runTest {
        val films = pinocchioIdentifiedByHand()
        bench.nas.put("Disney/Pinocchio (2022).mkv")
        bench.scan()

        val other = bench.item(films, "Disney/Pinocchio (2022).mkv")
        assertEquals(null to DISNEY, other.userTmdbId to other.tmdbId)
        assertFalse(other.played)
        assertEquals("kept for the film matched by hand", listOf(KeptKeys.film(DEL_TORO)), bench.keptTitles())
    }

    /** Pinocchio (2022) matched with Disney's, identified by hand as del Toro's, watched, then deleted. */
    private suspend fun pinocchioIdentifiedByHand(): SourceEntity {
        bench.withTmdb = true
        tmdb.movie(DISNEY, "Pinocchio", 2022)
        tmdb.movie(DEL_TORO, "Guillermo del Toro's Pinocchio", 2022)
        val films = filmsFolder()
        val path = "Pinocchio (2022)/Pinocchio (2022) WEBDL-1080p.mkv"
        nas.put(path)
        bench.scan()
        val pinocchio = bench.item(films, path)
        assertEquals(DISNEY, pinocchio.tmdbId)
        bench.scanner.identify(pinocchio.id, DEL_TORO)
        dao.setPlayed(pinocchio.id, played = true, now = bench.now)
        nas.remove(path)
        bench.scan()
        return films
    }

    /** One copy of two deleted: the other, the title still, takes its resume point; nothing is kept. */
    @Test
    fun aCopyLeavingGivesItsResumePointToTheCopyThatStays() = runTest {
        val (hd, uhd) = duneTwice()
        val first = bench.item(hd, HD_DUNE)
        dao.savePlayback(first.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = PLAYED_AT)

        nas.remove(HD_DUNE)
        nas.remove(HD_DUNE.replace(".mkv", ".nfo"))
        bench.scan()
        val copy = bench.item(uhd, UHD_DUNE)
        assertNull("the title now", copy.versionOf)
        assertEquals(600_000L to PLAYED_AT, copy.positionMs to copy.lastPlayedAt)
        assertTrue(copy.favorite)
        assertEquals("nothing kept: the title is still there", emptyList<String>(), bench.keptTitles())
    }

    /** Unwatched since the other copy left, the title is unwatched when Radarr replaces the last copy: nothing older returns. */
    @Test
    fun anUnmarkIsNeverUndoneByAStateKeptBefore() = runTest {
        val (_, uhd) = duneTwice()
        dao.setPlayed(bench.item(uhd, UHD_DUNE).id, played = true, now = bench.now)
        nas.remove(HD_DUNE)
        nas.remove(HD_DUNE.replace(".mkv", ".nfo"))
        bench.scan()
        val copy = bench.item(uhd, UHD_DUNE)
        assertTrue(copy.played && copy.favorite)
        dao.setPlayed(copy.id, played = false, now = bench.now)
        dao.setFavorite(copy.id, false)

        // Radarr upgrades it in two steps.
        uhdNas.remove(UHD_DUNE)
        uhdNas.remove(UHD_DUNE.replace(".mkv", ".nfo"))
        bench.scan()
        assertEquals(emptyList<String>(), bench.keptTitles())
        uhdNas.put("Dune (2021)/Dune (2021) Remux-2160p.mkv")
        uhdNas.putText("Dune (2021)/Dune (2021) Remux-2160p.nfo", nfo(DUNE))
        bench.scan()

        val remux = bench.item(uhd, "Dune (2021)/Dune (2021) Remux-2160p.mkv")
        assertFalse(remux.played)
        assertFalse(remux.favorite)
        assertNothingForTrakt()
    }

    /** Dune in two folders, named by its NFO, one copy of the other, favourite: the two folders. */
    private suspend fun duneTwice(): Pair<SourceEntity, SourceEntity> {
        val hd = filmsFolder()
        val uhd = bench.folder("Films 4K", on = uhdNas)
        uhdNas.put("Arrival (2016)/Arrival (2016) 2160p.mkv")
        nas.put(HD_DUNE)
        nas.putText(HD_DUNE.replace(".mkv", ".nfo"), nfo(DUNE))
        uhdNas.put(UHD_DUNE)
        uhdNas.putText(UHD_DUNE.replace(".mkv", ".nfo"), nfo(DUNE))
        bench.scan()
        val first = bench.item(hd, HD_DUNE)
        assertEquals(first.id, bench.item(uhd, UHD_DUNE).versionOf)
        dao.setFavorite(first.id, true)
        return hd to uhd
    }

    @Test
    fun anEpisodeImportedInTwoStepsStaysWatched() = runTest {
        val series = showsFolder()
        shows.put("Severance/Season 1/Severance - S01E01 - Good News About Hell WEBDL-1080p.mkv")
        shows.put("Severance/Season 1/Severance - S01E02 - Half Loop WEBDL-1080p.mkv")
        bench.scan()
        val pilot = severance(series).first()
        dao.savePlayback(pilot.id, positionMs = 1_200_000, durationMs = 3_300_000, played = false, lastPlayedAt = PLAYED_AT)
        val show = bench.series("Severance")

        shows.remove("Severance/Season 1/Severance - S01E01 - Good News About Hell WEBDL-1080p.mkv")
        bench.scan()
        assertNull(dao.item(pilot.id))
        shows.put("Severance/Season 1/Severance - S01E01 - Good News About Hell Bluray-2160p.mkv")
        bench.scan()

        val back = severance(series).first()
        assertEquals(1_200_000L to PLAYED_AT, back.positionMs to back.lastPlayedAt)
        assertEquals(pilot.addedAt, back.addedAt)
        assertEquals("a return, not a new episode", show.addedAt, bench.series("Severance").addedAt)
        assertNothingForTrakt()
    }

    @Test
    fun aShowWhoseEpisodesAllLeftGetsBackItsFavouriteImagesAndTracks() = runTest {
        showsFolder()
        val episodes = listOf("Severance/Season 1/Severance - S01E01.mkv", "Severance/Season 1/Severance - S01E02.mkv")
        episodes.forEach { shows.put(it) }
        bench.scan()
        val show = bench.series("Severance")
        dao.setFavorite(show.id, true)
        dao.setUserPoster(show.id, "/chosen.jpg")
        dao.setUserBackdrop(show.id, "/wide.jpg")
        bench.db.trackChoices().save(TrackChoiceEntity("local:${show.id}", "kor", null, at = 5))

        episodes.forEach(shows::remove)
        bench.scan()
        assertNull(dao.item(show.id))
        assertNull("no show keys it any more", bench.db.trackChoices().choice("local:${show.id}"))

        episodes.forEach { shows.put(it) }
        bench.scan()
        val back = bench.series("Severance")
        assertTrue(back.favorite)
        assertEquals("/chosen.jpg" to "/wide.jpg", back.userPosterPath to back.userBackdropPath)
        assertEquals("kor", bench.db.trackChoices().choice("local:${back.id}")?.audio)
        assertEquals(emptyList<String>(), bench.keptTitles())
    }

    /** Back in a folder named otherwise while TMDB is out of reach: the episodes wait for their show's match. */
    @Test
    fun episodesBornBeforeTheirShowIsMatchedGetTheirStateOnceItIs() = runTest {
        bench.withTmdb = true
        tmdb.show(SEVERANCE, "Severance", 2022)
        tmdb.season(SEVERANCE, 1, episodes = 2)
        val series = showsFolder()
        val before = listOf("Severance (2022)/Season 1/Severance - S01E01.mkv", "Severance (2022)/Season 1/Severance - S01E02.mkv")
        before.forEach { shows.put(it) }
        bench.scan()
        assertEquals(SEVERANCE, bench.series("Severance", 2022).tmdbId)
        val pilot = severance(series).first()
        dao.setPlayed(pilot.id, played = true, now = bench.now)
        before.forEach(shows::remove)
        bench.scan()

        bench.tmdbOutOfReach()
        val after = before.map { it.replace("Severance (2022)/", "Severance/") }
        after.forEach { shows.put(it) }
        bench.scan()
        assertNull(bench.series("Severance").tmdbId)
        assertFalse("not yet", severance(series).first().played)

        bench.tmdbBack()
        bench.scan()
        assertEquals(SEVERANCE, bench.series("Severance").tmdbId)
        assertEquals(listOf(true, false), severance(series).map { it.played })
        assertEquals(emptyList<String>(), bench.keptTitles())
        assertNothingForTrakt()
    }

    private companion object {
        const val DUNE = 438631
        const val DISNEY = 532639
        const val DEL_TORO = 555604
        const val SEVERANCE = 95396
        const val PLAYED_AT = 42L
        const val HD_DUNE = "Dune (2021)/Dune (2021).mkv"
        const val UHD_DUNE = "Dune (2021)/Dune (2021) 2160p.mkv"
    }
}
