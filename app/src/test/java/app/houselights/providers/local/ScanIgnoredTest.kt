package app.houselights.providers.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The videos of a shows folder whose names place them nowhere are listed, by folder, with why, not dropped in silence:
 * written only when the list changes, kept while the folder is out of reach, forgotten with it; a file renamed as an
 * episode leaves the list and joins its show.
 */
@RunWith(RobolectricTestRunner::class)
class ScanIgnoredTest {
    private val log = StatementLog()

    @get:Rule
    val scan = ScanBenchRule(tmdbKey = false, onQuery = log)

    private val bench get() = scan.bench
    private lateinit var shows: SourceEntity
    private val showsNas get() = bench.showsNas

    @Before
    fun open() = runTest {
        shows = bench.folder("Séries", SourceKind.SHOWS)
        showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        showsNas.put(NO_SHOW)
        showsNas.put(NO_NUMBER)
    }

    private suspend fun ignored() = bench.dao.observeIgnored().first().map { Triple(it.folder, it.filePath, it.reason) }

    @Test
    fun aVideoNamingNoEpisodeIsListedWithWhy() = runTest {
        bench.scan()

        // By path, as SQLite sorts them.
        val expected = listOf(
            Triple("Séries", NO_SHOW, IgnoredReason.NO_SHOW),
            Triple("Séries", NO_NUMBER, IgnoredReason.NO_NUMBER),
        )
        assertEquals(expected, ignored())
        assertEquals(listOf("Severance/Season 1/Severance - S01E01.mkv"), bench.paths(shows))
    }

    @Test
    fun aFileRenamedAsAnEpisodeLeavesTheListAndJoinsItsShow() = runTest {
        bench.scan()
        val renamed = "Shōgun (2024)/Saison 1/Shōgun - S01E01 - Anjin.mkv"
        showsNas.rename(NO_NUMBER, renamed)
        bench.scan()

        assertEquals(listOf(NO_SHOW), ignored().map { it.second })
        assertEquals("Shōgun", bench.showOf(shows, renamed).title)
    }

    /** Every few minutes on a NAS, a pass finding the same files writes nothing of them. */
    @Test
    fun aPassThatFindsTheSameWritesNothing() = runTest {
        bench.scan()
        log.recording = true
        bench.scan()
        log.recording = false

        val writes = log.recorded.filter { "ignored_files" in it && !it.trimStart().startsWith("SELECT", ignoreCase = true) }
        assertEquals(emptyList<String>(), writes)
        assertEquals(2, ignored().size)
    }

    @Test
    fun aFolderOutOfReachKeepsItsList() = runTest {
        bench.scan()
        showsNas.listFailure = IOException("NAS unreachable")
        bench.scan()

        assertEquals(2, ignored().size)
    }

    @Test
    fun aFilmsFolderIgnoresNothing() = runTest {
        bench.folder("Films")
        bench.nas.put("Holidays.mkv")
        bench.nas.put("S01E01.mkv")
        bench.scan()

        assertTrue(ignored().none { it.first == "Films" })
    }

    @Test
    fun aFolderRemovedTakesItsIgnoredFilesAlong() = runTest {
        bench.scan()
        bench.scanner.removeSource(shows)

        assertEquals(emptyList<Triple<String, String, IgnoredReason>>(), ignored())
    }

    private companion object {
        /** Numbers, but no show's name to put them under. */
        const val NO_SHOW = "S01E01.mkv"

        /** A show's season, but no episode's number. */
        const val NO_NUMBER = "Shōgun (2024)/Saison 1/Shōgun - Anjin.mkv"
    }
}
