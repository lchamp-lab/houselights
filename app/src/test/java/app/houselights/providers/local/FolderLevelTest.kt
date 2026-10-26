package app.houselights.providers.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.util.Locale

/** One level of a folder as the add-folder flow browses it, whatever the protocol: its folders' order, what is left out. */
class FolderLevelTest {
    /** An entry of [name], a folder when [isFolder]; [fails] when telling which costs a request that fails. */
    private class Entry(
        override val name: String,
        private val isFolder: Boolean = false,
        private val fails: Throwable? = null,
    ) : RemoteEntry {
        var asked = false

        override fun folder(): RemoteFolder? {
            asked = true
            fails?.let { throw it }
            return if (isFolder) RemoteFolder { emptyList() } else null
        }

        override fun size() = 0L
    }

    private fun folderOf(vararg entries: Entry) = RemoteFolder { entries.toList() }

    private val neverFatal: (Throwable) -> Boolean = { false }

    /** As a reader looks for them in French: case aside, an accent after the letter bare, never after "Z". */
    @Test
    fun namesSortAsAReaderLooksForThem() {
        val names = listOf("Zodiac", "été", "Ete", "eau", "films", "Films", "Arrival", "Ébauches")
        assertEquals(
            listOf("Arrival", "eau", "Ébauches", "Ete", "été", "Films", "films", "Zodiac"),
            names.sortedWith(byName(Locale.FRENCH)),
        )
    }

    @Test
    fun aLevelGivesItsFoldersAndCountsItsOwnVideos() {
        val level = level(
            folderOf(
                Entry("Séries", isFolder = true),
                Entry("Films", isFolder = true),
                Entry("Dune (2021).mkv"),
                Entry("Dune (2021)-sample.mkv"),
                Entry("movie.nfo"),
                Entry("Heat (1995).iso"),
            ),
            Locale.FRENCH,
            neverFatal,
        )
        assertEquals(FolderLevel(listOf("Films", "Séries"), videos = 2), level)
    }

    /** Synology's, QNAP's and Windows's own folders, and hidden ones, are left out without asking what they are. */
    @Test
    fun hiddenAndSystemEntriesAreLeftOutUnasked() {
        val hidden = listOf(".snapshot", "@eaDir", "#recycle", "#snapshot", "\$RECYCLE.BIN", "System Volume Information", "lost+found")
            .map { Entry(it, isFolder = true) }
        val level = level(folderOf(*hidden.toTypedArray(), Entry("Films", isFolder = true)), isFatal = neverFatal)
        assertEquals(FolderLevel(listOf("Films"), videos = 0), level)
        assertTrue(hidden.none { it.asked })
    }

    /** An entry gone since listed, or a link leading nowhere, is left out; the NAS gone silent fails the level. */
    @Test
    fun anEntryThatCannotBeReadIsLeftOutUnlessTheNasIsGone() {
        val gone = Entry("Arrival (2016)", fails = FileNotFoundException("lookup Arrival (2016)"))
        val films = Entry("Films", isFolder = true)
        val isFatal: (Throwable) -> Boolean = { it is SocketTimeoutException }
        assertEquals(FolderLevel(listOf("Films"), videos = 0), level(folderOf(gone, films), isFatal = isFatal))

        val silent = Entry("Séries", fails = SocketTimeoutException("timed out"))
        val failure = runCatching { level(folderOf(films, silent), isFatal = isFatal) }.exceptionOrNull()
        assertTrue("$failure", failure is SocketTimeoutException)
    }

    @Test
    fun aLevelWithNeitherFolderNorVideoIsEmpty() {
        assertTrue(level(folderOf(Entry("movie.nfo"), Entry("@eaDir", isFolder = true)), isFatal = neverFatal).isEmpty)
        assertFalse(level(folderOf(Entry("Dune (2021).mkv")), isFatal = neverFatal).isEmpty)
    }

    /** The scans' fake NAS lists levels from the files it holds, so the flow's tests browse what a scan would read. */
    @Test
    fun theFakeNasListsTheLevelsItsFilesMake() = runBlocking {
        val nas = FakeRemoteBrowser()
        nas.put("Films/Dune (2021)/Dune (2021).mkv")
        nas.put("Films/Heat (1995).mkv")
        nas.put("Films/@eaDir/thumb.jpg")
        nas.put("Séries/Severance/S01E01.mkv")
        val root = nfsFolder(path = "")
        assertEquals(FolderLevel(listOf("Films", "Séries"), videos = 0), nas.folders(root))
        assertEquals(FolderLevel(listOf("Dune (2021)"), videos = 1), nas.folders(root, "/Films/"))
        val missing = runCatching { nas.folders(root, "Films2") }.exceptionOrNull()
        assertTrue("$missing", missing is FileNotFoundException)
    }
}
