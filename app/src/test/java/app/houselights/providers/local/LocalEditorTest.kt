package app.houselights.providers.local

import app.houselights.core.ArtworkKind
import app.houselights.core.FailureKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.TmdbKeyRefusedException
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
import org.robolectric.shadows.ShadowLog

/** The artwork a title can be given: when TMDB cannot list it, the reason shows in the choices and the log. */
@RunWith(RobolectricTestRunner::class)
class LocalEditorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("artwork"))
    }

    @After
    fun close() = bench.close()

    private suspend fun film(path: String): MediaItem {
        val films = bench.folder("Films")
        bench.nas.put(path)
        bench.scan()
        val row = bench.item(films, path)
        return MediaItem(row.id.toString(), MediaKind.MOVIE, row.title)
    }

    private fun editor(tmdb: TmdbClient? = bench.tmdb.client()) = LocalEditor(bench.dao, bench.scanner) { tmdb }

    /** With the key refused, the screen can say the key needs changing, not the connection. */
    @Test
    fun imagesTmdbDoesNotListSayWhy() = runTest {
        bench.tmdb.movie(949, "Heat", 1995)
        val heat = film("Heat (1995)/Heat.mkv")
        bench.tmdb.status = 401
        ShadowLog.clear()

        val choices = editor().artwork(heat, ArtworkKind.POSTER)
        assertFalse(choices.onlineAvailable)
        assertEquals(FailureKind.TMDB_KEY_REFUSED, choices.onlineError)
        val told = ShadowLog.getLogsForTag("LocalEditor").single()
        assertTrue(told.msg, heat.id in told.msg && "949" in told.msg)
        assertTrue(told.throwable is TmdbKeyRefusedException)
    }

    @Test
    fun withoutAKeyTheChoicesSaySo() = runTest {
        bench.tmdb.movie(949, "Heat", 1995)
        val heat = film("Heat (1995)/Heat.mkv")
        val choices = editor(tmdb = null).artwork(heat, ArtworkKind.BACKDROP)
        assertFalse(choices.onlineAvailable)
        assertEquals(FailureKind.NO_TMDB_KEY, choices.onlineError)
    }

    /** Nothing failed: there is nothing to list before the title is identified. */
    @Test
    fun aTitleNotIdentifiedHasNoImagesToList() = runTest {
        val film = film("Unknown (2001)/Unknown.mkv")
        val choices = editor().artwork(film, ArtworkKind.POSTER)
        assertFalse(choices.onlineAvailable)
        assertNull(choices.onlineError)
    }
}
