package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilenameParserTest {
    private fun movie(name: String) = FilenameParser.parseMovie(name)

    @Test
    fun plainTitles() {
        assertEquals(ParsedMovie("Top Gun Maverick", null), movie("Top Gun Maverick.mkv"))
        assertEquals(ParsedMovie("Spider-Man Brand New Day", null), movie("Spider-Man Brand New Day.mkv"))
        assertEquals(ParsedMovie("evil dead burn", null), movie("evil dead burn.mkv"))
    }

    @Test
    fun yearInParenthesesOrScene() {
        assertEquals(ParsedMovie("The Matrix", 1999), movie("The Matrix (1999).mkv"))
        assertEquals(ParsedMovie("Blade Runner 2049", 2017), movie("Blade Runner 2049 (2017).mkv"))
        assertEquals(
            ParsedMovie("Dune Part Two", 2024),
            movie("Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.Atmos-FGT.mkv"),
        )
        assertEquals(ParsedMovie("2001 A Space Odyssey", 1968), movie("2001.A.Space.Odyssey.1968.1080p.mkv"))
        assertEquals(ParsedMovie("Le Comte de Monte-Cristo", 2024), movie("Le Comte de Monte-Cristo [2024] MULTi VFF 2160p.mkv"))
    }

    @Test
    fun titleThatIsAYear() {
        assertEquals(ParsedMovie("1917", null), movie("1917.mkv"))
        assertEquals(ParsedMovie("1917", 2019), movie("1917 (2019).mkv"))
    }

    @Test
    fun junkWithoutYear() {
        assertEquals(ParsedMovie("Oppenheimer", null), movie("Oppenheimer.MULTi.2160p.WEB-DL.mkv"))
    }

    @Test
    fun episodes() {
        assertEquals(
            ParsedEpisode("Breaking Bad", 1, 2),
            FilenameParser.parseEpisode("Breaking.Bad.S01E02.720p.BluRay.mkv", emptyList()),
        )
        assertEquals(
            ParsedEpisode("The Office", 2, 5),
            FilenameParser.parseEpisode("The Office - 2x05 - Halloween.mkv", emptyList()),
        )
        assertEquals(
            ParsedEpisode("Severance", 2, 1),
            FilenameParser.parseEpisode("severance.s02e01.mkv", listOf("Severance (2022)", "Season 2")),
        )
        assertEquals(
            ParsedEpisode("Les Revenants", 1, 3),
            FilenameParser.parseEpisode("03 - Victor.mkv", listOf("Les Revenants", "Saison 1")),
        )
        assertNull(FilenameParser.parseEpisode("Making of.mkv", listOf("Dark", "Extras")))
    }

    @Test
    fun badges() {
        assertEquals(
            listOf("4K", "Dolby Vision", "Atmos", "TrueHD", "Remux"),
            FilenameParser.badges("Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.Atmos-FGT.mkv"),
        )
        assertEquals(emptyList<String>(), FilenameParser.badges("Top Gun Maverick.mkv"))
    }

    @Test
    fun seasonFolders() {
        assertEquals(2, FilenameParser.seasonFromFolder("Season 2"))
        assertEquals(1, FilenameParser.seasonFromFolder("Saison 01"))
        assertEquals(3, FilenameParser.seasonFromFolder("S03"))
        assertNull(FilenameParser.seasonFromFolder("Severance"))
    }
}
