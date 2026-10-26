package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What TMDB made of a title ([MatchState]): matched once it has a TMDB id, not found, so to identify, once TMDB
 * answered without; a title TMDB did not answer for is only waiting, as before.
 */
@RunWith(RobolectricTestRunner::class)
class ToIdentifyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb
    private val dao get() = bench.dao

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("artwork"))
    }

    @After
    fun close() = bench.close()

    @Test
    fun aTitleTmdbHasNotIsToIdentifyNotUnanswered() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        nas.put("Dune (2021).mkv")
        nas.put("Vacances à Biarritz (2019).mkv")
        bench.scan()

        assertEquals(MatchState.MATCHED, bench.item(films, "Dune (2021).mkv").matchState)
        val holidays = bench.item(films, "Vacances à Biarritz (2019).mkv")
        assertEquals(MatchState.NOT_FOUND, holidays.matchState)
        assertEquals(listOf(holidays.id), dao.toIdentify().map { it.id })
    }

    @Test
    fun aTitleTmdbDoesNotAnswerIsNotToIdentify() = runTest {
        val films = bench.folder("Films")
        nas.put("Vacances à Biarritz (2019).mkv")
        bench.tmdbOutOfReach()
        bench.scan()

        val holidays = bench.item(films, "Vacances à Biarritz (2019).mkv")
        assertNull("still to look up", holidays.matchState)
        assertNull(holidays.matchedAt)
        assertEquals(emptyList<ItemEntity>(), dao.toIdentify())
    }

    /** Named by its NFO while TMDB is out of reach, a film is matched: it has a TMDB id, whatever TMDB says later. */
    @Test
    fun aTitleWithATmdbIdIsMatchedWhetherTmdbAnsweredOrNot() = runTest {
        val films = bench.folder("Films")
        nas.put("Dune (2021).mkv")
        nas.putText("Dune (2021).nfo", "<movie><title>Dune</title><uniqueid type=\"tmdb\">$DUNE</uniqueid></movie>")
        bench.tmdbOutOfReach()
        bench.scan()

        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(DUNE to MatchState.MATCHED, dune.tmdbId to dune.matchState)
        assertEquals(emptyList<ItemEntity>(), dao.toIdentify())
    }

    /** A show TMDB has not is to identify; its seasons and episodes never are. */
    @Test
    fun aShowTmdbHasNotIsToIdentifyNotItsEpisodes() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        bench.showsNas.put("Kaamelott/Livre 1/Kaamelott - S01E01.mkv")
        bench.scan()

        val show = bench.showOf(shows, "Kaamelott/Livre 1/Kaamelott - S01E01.mkv")
        assertEquals(MatchState.NOT_FOUND, show.matchState)
        assertNull(bench.item(shows, "Kaamelott/Livre 1/Kaamelott - S01E01.mkv").matchState)
        assertEquals(listOf(show.id), dao.toIdentify().map { it.id })
    }

    private companion object {
        const val DUNE = 438631
    }
}
