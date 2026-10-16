package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a listing that could not see everything does to the rows of the files it did not list. */
class MissingFilesTest {
    private fun film(id: Long, path: String) = ItemEntity(
        id = id, kind = ItemKind.MOVIE, sourceId = 1, title = path, sortTitle = path, filePath = path, addedAt = 0,
    )

    private val dune = film(1, "Dune (2021)/Dune (2021).mkv")
    private val arrival = film(2, "Private/Arrival (2016)/Arrival (2016).mkv")
    private val heat = film(3, "Private films/Heat (1995).mkv")

    private fun settle(vararg unreadable: String) =
        MissingFiles.settle(SourceKind.MOVIES, listOf(dune, arrival, heat), Listing(emptyList(), emptyList(), unreadable.toList()), NOW)

    /** A file the NAS could not be asked about (gone between the listing and the question) is out of sight, not missing. */
    @Test
    fun aFileThatCouldNotBeAskedAboutKeepsItsRow() {
        assertEquals(listOf(arrival.id, heat.id), settle("Dune (2021)/Dune (2021).mkv").held)
    }

    @Test
    fun aFolderThatCouldNotBeReadKeepsTheRowsUnderItAlone() {
        assertEquals("a folder whose name only starts the same is not under it", listOf(dune.id, heat.id), settle("Private").held)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
