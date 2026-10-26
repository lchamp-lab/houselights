package app.houselights.ui.settings

import app.houselights.providers.local.Folder
import app.houselights.providers.local.HeldRow
import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the sheet of titles held lists: ten at most then how many more, a film by its year, an episode by its show. */
class HeldLinesTest {
    private fun film(n: Int, folder: Long = 1) = HeldRow(
        id = n.toLong(), sourceId = folder, kind = ItemKind.MOVIE, title = "Film $n", year = 2000 + n, filePath = "Film $n.mkv",
        missingSince = 1, showTitle = null, seasonNumber = null, episodeNumber = null,
    )

    @Test
    fun tenTitlesThenHowManyMore() {
        val lines = heldLines((1..12).map { film(it) })
        assertEquals(11, lines.size)
        assertEquals((1..10).map { "Film $it" }, lines.take(10).map { it.title })
        assertEquals(HeldLine(more = 2), lines.last())
        assertEquals(10, heldLines((1..10).map { film(it) }).size)
    }

    @Test
    fun anEpisodeIsNamedByItsShowAndNumber() {
        val pilot = HeldRow(
            id = 1, sourceId = 1, kind = ItemKind.EPISODE, title = "Pilot", year = null, filePath = "MobLand/S01E01.mkv",
            missingSince = 1, showTitle = "MobLand", seasonNumber = 1, episodeNumber = 1,
        )
        assertEquals(HeldLine(title = "MobLand", season = 1, episode = 1, path = "MobLand/S01E01.mkv"), heldLines(listOf(pilot)).single())
    }

    /** Each folder its own titles, in the folders' order; a folder holding none says nothing. */
    @Test
    fun eachFolderItsOwnTitles() {
        fun folder(id: Long, name: String) = Folder(id, name, SourceKind.MOVIES, Protocol.NFS, "nas", "nas/$name", lastScanAt = 1)
        val folders = listOf(folder(1, "films"), folder(2, "concerts"), folder(3, "kids"))
        val held = heldByFolder(listOf(film(1, folder = 3), film(2, folder = 1), film(3, folder = 3)), folders)
        assertEquals(listOf(1L, 3L), held.map { it.folder.id })
        assertEquals(listOf(listOf(2L), listOf(1L, 3L)), held.map { it.ids })
        assertEquals("Films", held.first().name)
    }
}
