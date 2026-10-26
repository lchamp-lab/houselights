package app.houselights.providers.local

import app.houselights.providers.local.Numbering.ABSOLUTE
import app.houselights.providers.local.Numbering.FOLDER
import app.houselights.providers.local.Numbering.SEQUEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What names say of episodes numbered otherwise than "S01E05": from their show's start, as anime are; anime sequels;
 * a number after a dash in a season's folder; books and volumes; seasons in words or by their year. Never a film's
 * name, a resolution, a year or a hash read as a number.
 */
class FilenameNumberingTest {
    private fun episode(name: String, folders: List<String> = emptyList()) = FilenameParser.parseEpisode(name, folders)

    @Test
    fun anAnimeEpisodeIsNumberedFromTheStartOfItsShow() {
        val onePiece = ParsedEpisode("One Piece", 1, 1071, numbering = ABSOLUTE)
        assertEquals(onePiece, episode("[SubsPlease] One Piece - 1071 (1080p) [ABCD1234].mkv"))
        assertEquals(onePiece, episode("One Piece - 1071v2.mkv"))
        assertEquals(onePiece, episode("One Piece E1071.mkv"))
        assertEquals(onePiece, episode("One Piece Episode 1071.mkv"))
        assertEquals(onePiece.copy(lastEpisode = 1072), episode("One Piece - 1071-1072.mkv"))
    }

    @Test
    fun anAnimeSequelIsItsSeason() {
        val mushoku = episode("[SubsPlease] Mushoku Tensei S2 - 01 (1080p) [ABCD].mkv")
        assertEquals(ParsedEpisode("Mushoku Tensei", 2, 1, numbering = SEQUEL), mushoku)
        assertEquals(ParsedEpisode("Spy x Family", 2, 1, numbering = SEQUEL), episode("Spy x Family Season 2 - 01.mkv"))
        assertEquals(ParsedEpisode("Dr. Stone", 3, 1, numbering = SEQUEL), episode("Dr. Stone S3 - 01.mkv"))
    }

    @Test
    fun aNumberAfterADashInASeasonsFolderIsThatSeasonsEpisode() {
        assertEquals(ParsedEpisode("Show", 2, 5, numbering = FOLDER), episode("Show - 05 - Title.mkv", listOf("Show", "Season 2")))
    }

    @Test
    fun resolutionsYearsAndHashesAreNoNumber() {
        val none = listOf(
            "Show - 1080p.mkv", "Show (1080p).mkv", "Show - 2019.mkv", "Blade Runner - 2049.mkv", "Show - 100 Days.mkv",
            "Show [ABCD1234].mkv",
        )
        none.forEach { assertNull(it, episode(it)) }
        assertEquals("its S01E05 first", ParsedEpisode("Show", 1, 5), episode("Show S01E05 - 24 Hours.mkv"))
    }

    @Test
    fun booksPartsAndVolumesAreSeasonsInRomanNumeralsOrNot() {
        val seasons = mapOf(
            "Livre I" to 1, "Livre IV" to 4, "Livre XII" to 12, "Book 2" to 2, "Partie 3" to 3, "Part IX" to 9, "Volume 1" to 1,
            "Vol. 2" to 2,
        )
        seasons.forEach { (folder, season) -> assertEquals(folder, season, FilenameParser.seasonFromFolder(folder)) }
        listOf("Book Club", "Partie de chasse", "Livre IIII").forEach { assertNull(it, FilenameParser.seasonFromFolder(it)) }
        assertEquals(ParsedEpisode("Kaamelott", 1, 1, numbering = FOLDER), episode("01 - Heat.avi", listOf("Kaamelott", "Livre I")))
    }

    @Test
    fun aBookInTheNameNumbersItsEpisode() {
        assertEquals(ParsedEpisode("Kaamelott", 1, 1), episode("Kaamelott - Livre I - 01 - Heat.avi"))
        // Films are named by their volumes and parts.
        val films = listOf(
            "Kill Bill - Volume 1 (2003).mkv", "Harry Potter and the Deathly Hallows - Part 2 (2011).mkv", "Dune - Part 2 - 2160p.mkv",
        )
        films.forEach { assertNull(it, episode(it)) }
        val finalSeason = episode("Shingeki no Kyojin - The Final Season Part 2 - 01.mkv")
        assertEquals("never a book's", ABSOLUTE to 1, finalSeason?.numbering to finalSeason?.season)
    }

    @Test
    fun aSeasonAndAnEpisodeInWords() {
        assertEquals(ParsedEpisode("Show", 1, 5), episode("Show Season 1 Episode 5.mkv"))
        assertEquals(ParsedEpisode("Show", 2, 3), episode("Show - Saison 2 - Épisode 3.mkv"))
    }

    @Test
    fun aSeasonCanBeAYear() {
        assertEquals(ParsedEpisode("Show", 2023, 1), episode("Show S2023E01.mkv"))
        assertEquals(2023, FilenameParser.seasonFromFolder("Season 2023"))
    }

    /** By its numbering as the file names it, whatever its place: an episode is kept and found again by it. */
    @Test
    fun anEpisodeIsKeyedAsItsFileNamesIt() {
        assertEquals("a1071-1071", ParsedEpisode("One Piece", 1, 1071, numbering = ABSOLUTE).key())
        assertEquals("a1071-1072", ParsedEpisode("One Piece", 1, 1071, 1072, ABSOLUTE).key())
        assertEquals("1:5-5", ParsedEpisode("Show", 1, 5).key())
        assertEquals("1:5-6", ParsedEpisode("Show", 1, 5, 6, FOLDER).key())
    }

    /** A films folder tells a title's own folder by "S01E05" alone: "Star Wars Episode 4" stays a film's name. */
    @Test
    fun aFilmNamedLikeAnEpisodeIsNoEpisodeToAFilmsFolder() {
        assertNull(FilenameParser.parseEpisode("Star Wars Episode 4.mkv", emptyList(), named = true))
        assertEquals("Star Wars Episode 4", FilenameParser.parseMovie("Star Wars Episode 4.mkv").title)
    }

    /** A shows folder's video its name places nowhere, and why: no episode's number, or numbers but no show's name. */
    @Test
    fun whyAFileNamesNoEpisode() {
        assertEquals(IgnoredReason.NO_NUMBER, FilenameParser.refusal("Shōgun (2024)/Saison 1/Shōgun - Anjin.mkv"))
        assertEquals(IgnoredReason.NO_NUMBER, FilenameParser.refusal("Kaamelott - Heat.mkv"))
        assertEquals(IgnoredReason.NO_SHOW, FilenameParser.refusal("S01E01.mkv"))
        assertEquals(IgnoredReason.NO_SHOW, FilenameParser.refusal("Saison 1/03 - Victor.mkv"))
        assertNull(FilenameParser.refusal("Severance/Season 1/Severance - S01E01.mkv"))
    }

    @Test
    fun anAnimeBatchFolderIsNamedUpToItsRange() {
        val path = FilenameParser.parseShowPath("One Piece (1001-1100)/[SubsPlease] One Piece - 1071 (1080p).mkv")
        assertEquals(Triple("One Piece", null, "One Piece (1001-1100)"), path?.let { Triple(it.title, it.year, it.dir) })
        assertEquals(ABSOLUTE, path?.episode?.numbering)
    }
}
