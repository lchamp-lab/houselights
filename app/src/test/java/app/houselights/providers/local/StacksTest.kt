package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Films in several files, as Kodi stacks them: the suffixes a file's name ends with, the films made of
 * parts side by side, the NFO and artwork found under the film's name, and what a folder's rows become
 * once its parts are renamed, added or gone.
 */
class StacksTest {
    private fun part(name: String) = StackPart.of(name)

    private fun stacks(vararg paths: String, kind: SourceKind = SourceKind.MOVIES) =
        Stacks.of(kind, paths.map { RemoteFile(it, 1_000) })

    private fun Stacks.titlePaths() = titles.map { it.relativePath }

    @Test
    fun kodisSuffixesEndTheName() {
        assertEquals(StackPart("Le Parrain (1972)", 1), part("Le Parrain (1972) CD1.avi"))
        assertEquals(StackPart("Le Parrain (1972)", 2), part("Le Parrain (1972) cd2.avi"))
        assertEquals(StackPart("Film (2020)", 1, titleWord = true), part("Film (2020) - Part 1.mkv"))
        assertEquals(StackPart("Film.2020", 2, titleWord = true), part("Film.2020.part.2.mkv"))
        assertEquals(StackPart("Film", 3), part("Film_dvd_3.iso"))
        assertEquals(StackPart("film", 4, titleWord = true), part("film-pt4.mkv"))
        assertEquals(StackPart("Film", 2), part("Film disc b.mkv"))
        assertEquals(StackPart("Film", 4), part("Film DISK-d.mkv"))
        assertEquals(StackPart("Film", 1), part("Film CDa.avi"))
    }

    @Test
    fun namesThatOnlyLookLikePartsAreNot() {
        // The suffix ends the name: a tag, a year or a word after it is a title's.
        assertNull(part("Film (2020) CD1 [1080p].mkv"))
        assertNull(part("Harry Potter and the Deathly Hallows Part 1 (2010).mkv"))
        assertNull(part("Dune Part Two (2024).mkv"))
        // One part from 1 to 9, or a letter from a to d; volumes are films of their own.
        assertNull(part("Film CD10.avi"))
        assertNull(part("Film part e.mkv"))
        assertNull(part("Kill Bill Vol. 1 (2003).mkv"))
        // The suffix starts a word of its own, after a name.
        assertNull(part("Counterpart 2.mkv"))
        assertNull(part("CD1.avi"))
        // Titles alone.
        listOf("Bugonia.mkv", "Colony.mkv", "Disclosure Day.mkv", "I See You.mkv", "Spider-Man Brand New Day.mkv",
            "Spider-Man No Way Home.mkv", "The Furious.mkv", "Top Gun Maverick.mkv", "evil dead burn.mkv").forEach {
            assertNull(it, part(it))
        }
    }

    @Test
    fun partsSideBySideMakeOneFilmUnderTheFirst() {
        val godfather = stacks("Le Parrain (1972)/Le Parrain (1972) CD2.avi", "Le Parrain (1972)/Le Parrain (1972) CD1.avi")
        assertEquals(listOf("Le Parrain (1972)/Le Parrain (1972) CD1.avi"), godfather.titlePaths())
        val stack = checkNotNull(godfather.stackAt("Le Parrain (1972)/Le Parrain (1972) CD1.avi"))
        assertEquals("Le Parrain (1972)", stack.name)
        assertEquals(listOf("Le Parrain (1972)/Le Parrain (1972) CD2.avi"), godfather.partsOf(stack.first.relativePath))
        assertEquals("Le Parrain (1972)/Le Parrain (1972).avi", stack.namedPath)
        assertEquals("Le Parrain (1972)/Le Parrain (1972) CD1.avi", godfather.firstOf("Le Parrain (1972)/Le Parrain (1972) CD2.avi"))

        val dashed = stacks("Film (2020) - Part 1.mkv", "Film (2020) - Part 2.mkv", "Film (2020) - Part 3.mkv")
        assertEquals(listOf("Film (2020) - Part 1.mkv"), dashed.titlePaths())
        assertEquals(listOf("Film (2020) - Part 2.mkv", "Film (2020) - Part 3.mkv"), dashed.partsOf("Film (2020) - Part 1.mkv"))
        // Parts of one film may be written in two ways.
        assertEquals(listOf("Heat/Heat CD1.avi"), stacks("Heat/Heat CD1.avi", "Heat/heat part 2.avi").titlePaths())
    }

    /**
     * "Part 1" and "Part 2" end two films' names as well as one film's files': among other films and with no
     * year, they are two films; with their year, or alone in a folder of their own, one. CDs and discs always are.
     */
    @Test
    fun filesNamedPartAreOneFilmByTheirYearOrTheirOwnFolder() {
        val hallows = "Harry Potter and the Deathly Hallows"
        assertEquals(2, stacks("$hallows Part 1.mkv", "$hallows Part 2.mkv").titles.size)
        assertEquals(3, stacks("Films/Dune Part 1.mkv", "Films/Dune Part 2.mkv", "Films/Top Gun Maverick.mkv").titles.size)
        assertEquals(3, stacks("Mockingjay Pt1.mkv", "Mockingjay Pt2.mkv", "Colony.mkv").titles.size)

        val dated = stacks("Film (2020) - Part 1.mkv", "Film (2020) - Part 2.mkv", "Colony.mkv")
        assertEquals(listOf("Film (2020) - Part 1.mkv", "Colony.mkv"), dated.titlePaths())
        assertEquals(listOf("Film/Film Part 1.mkv"), stacks("Film/Film Part 1.mkv", "Film/Film Part 2.mkv").titlePaths())
        assertEquals(listOf("Heat CD1.avi", "Colony.mkv"), stacks("Heat CD1.avi", "Heat CD2.avi", "Colony.mkv").titlePaths())
        assertEquals(listOf("Heat disc 1.avi"), stacks("Heat disc 1.avi", "Heat disc 2.avi").titlePaths())
    }

    @Test
    fun aPartAloneIsAFilmOfItsOwn() {
        assertEquals(listOf("Dune Part 2.mkv"), stacks("Dune Part 2.mkv").titlePaths())
        assertNull(stacks("Dune Part 2.mkv").stackAt("Dune Part 2.mkv"))
        // No first part beside them: two films, as named.
        assertEquals(listOf("Film CD2.avi", "Film CD3.avi"), stacks("Film CD2.avi", "Film CD3.avi").titlePaths())
        // A gap ends the film: the fourth part is a title of its own.
        assertEquals(listOf("Film CD1.avi", "Film CD4.avi"), stacks("Film CD1.avi", "Film CD2.avi", "Film CD4.avi").titlePaths())
        // The first part twice: which is the film's cannot be told.
        assertEquals(3, stacks("Film CD1.avi", "Film part 1.avi", "Film CD2.avi").titles.size)
    }

    @Test
    fun aStackIsFormedInOneFolderOfFilmsOnly() {
        assertEquals(2, stacks("A/Film CD1.avi", "B/Film CD2.avi").titles.size)
        assertEquals(2, stacks("Film CD1.avi", "Film CD2.mkv").titles.size)
        assertEquals(2, stacks("Kill Bill Vol. 1 (2003).mkv", "Kill Bill Vol. 2 (2004).mkv").titles.size)
        assertEquals(2, stacks("Show/Show.S01E01.Part.1.mkv", "Show/Show.S01E01.Part.2.mkv", kind = SourceKind.SHOWS).titles.size)
    }

    @Test
    fun aFilmInSeveralFilesIsNamedWithoutThePart() {
        assertEquals("Le Parrain/Le Parrain (1972).avi", Stacks.filmPath("Le Parrain/Le Parrain (1972) CD1.avi", stacked = true))
        assertEquals("Heat.avi", Stacks.filmPath("Heat - Part 1.avi", stacked = true))
        // A film of one file keeps its name, whatever it ends with.
        assertEquals("Dune Part 2.mkv", Stacks.filmPath("Dune Part 2.mkv", stacked = false))
    }

    @Test
    fun theNfoAndArtworkOfAFilmInPartsGoByItsName() {
        val dir = "Le Parrain (1972)"
        val videos = stacks("$dir/Le Parrain (1972) CD1.avi", "$dir/Le Parrain (1972) CD2.avi")
        val files = listOf("$dir/Le Parrain (1972).nfo", "$dir/Le Parrain (1972)-poster.jpg", "$dir/fanart.jpg").map { RemoteFile(it, 10) }
        val found = Sidecars(files, videos.titles, videos).forVideo(videos.titles.single())
        assertEquals("$dir/Le Parrain (1972).nfo", found.nfo?.relativePath)
        assertEquals("$dir/Le Parrain (1972)-poster.jpg", found.poster?.relativePath)
        // Its folder is its own: two files, one film.
        assertEquals("$dir/fanart.jpg", found.fanart?.relativePath)

        // A part's own name serves as well.
        val own = Sidecars(listOf(RemoteFile("$dir/Le Parrain (1972) CD2.nfo", 10)), videos.titles, videos)
        assertEquals("$dir/Le Parrain (1972) CD2.nfo", own.forVideo(videos.titles.single()).nfo?.relativePath)
    }

    private fun row(id: Long, path: String, parts: List<String> = emptyList()) = ItemEntity(
        id = id, kind = ItemKind.MOVIE, sourceId = 1, title = path, sortTitle = path, filePath = path, parts = parts, addedAt = 0,
    )

    private fun settle(rows: List<ItemEntity>, vararg paths: String) =
        MissingFiles.settle(SourceKind.MOVIES, rows, stacks(*paths), NOW)

    @Test
    fun aPartRenamedIsAChangeOfItsFilm() {
        val godfather = row(1, "Le Parrain CD1.avi", parts = listOf("Le Parrain CD2.avi"))
        val renamed = settle(listOf(godfather), "Le Parrain CD1.avi", "Le Parrain - Disc 2.avi")
        assertEquals(listOf(1L to listOf("Le Parrain - Disc 2.avi")), renamed.parts)
        assertTrue(renamed.held.isEmpty() && renamed.removed.isEmpty() && renamed.relinked.isEmpty())

        // A part gone: a film of one file, the same row.
        val gone = settle(listOf(godfather), "Le Parrain CD1.avi")
        assertEquals(listOf(1L to emptyList<String>()), gone.parts)
        assertTrue(gone.held.isEmpty() && gone.removed.isEmpty())
    }

    @Test
    fun aFirstPartRenamedTakesItsRowAlong() {
        val godfather = row(1, "Le Parrain CD1.avi", parts = listOf("Le Parrain CD2.avi"))
        val outcome = settle(listOf(godfather), "Le Parrain disc 1.avi", "Le Parrain CD2.avi")
        assertEquals(listOf(1L to RemoteFile("Le Parrain disc 1.avi", 1_000)), outcome.relinked)
        assertTrue(outcome.parts.isEmpty())
        assertTrue(outcome.held.isEmpty())
    }

    @Test
    fun aFileThatBecameAPartGivesWayToItsFilm() {
        // Scanned before parts were told apart: one row a file.
        val first = row(1, "Heat CD1.avi")
        val second = row(2, "Heat CD2.avi")
        val outcome = settle(listOf(first, second), "Heat CD1.avi", "Heat CD2.avi")
        assertEquals(listOf(1L to listOf("Heat CD2.avi")), outcome.parts)
        assertEquals("not held: its file is there, in its film", listOf(2L), outcome.removed)
        assertTrue(outcome.held.isEmpty())

        // The film has no row yet: the part's stands for it.
        val alone = settle(listOf(second), "Heat CD1.avi", "Heat CD2.avi")
        assertEquals(listOf(2L to RemoteFile("Heat CD1.avi", 1_000)), alone.relinked)
        assertEquals(listOf(2L to listOf("Heat CD2.avi")), alone.parts)
        assertTrue(alone.removed.isEmpty())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
