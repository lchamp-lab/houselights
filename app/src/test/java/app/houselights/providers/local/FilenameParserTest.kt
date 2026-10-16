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
        assertEquals(ParsedMovie("Oppenheimer", null), movie("Oppenheimer.FRENCH.1080p.WEB.H264-GROUP.mkv"))
        assertEquals(ParsedMovie("Oppenheimer", null), movie("Oppenheimer (FRENCH) 1080p.mkv"))
    }

    /** Films named by their titles alone. */
    @Test
    fun theNasFilms() {
        val titles = listOf(
            "Bugonia", "Colony", "Disclosure Day", "I See You", "Spider-Man Brand New Day", "Spider-Man No Way Home",
            "The Furious", "Top Gun Maverick", "evil dead burn",
        )
        titles.forEach { assertEquals(ParsedMovie(it, null), movie("$it.mkv")) }
    }

    /** Words that are release tags at times, in titles: a title is cut at a tag no title holds, and only there. */
    @Test
    fun titleWordsThatAreTagsAtTimes() {
        assertEquals(ParsedMovie("The French Connection", 1971), movie("The French Connection (1971).mkv"))
        assertEquals(ParsedMovie("The French Connection", null), movie("The French Connection.mkv"))
        assertEquals(ParsedMovie("The French Dispatch", 2021), movie("The.French.Dispatch.2021.1080p.BluRay.x264-GROUP.mkv"))
        assertEquals(ParsedMovie("The French Dispatch", null), movie("The.French.Dispatch.1080p.BluRay.x264-GROUP.mkv"))
        assertEquals(ParsedMovie("Charlotte's Web", 2006), movie("Charlotte's Web (2006).mkv"))
        assertEquals(ParsedMovie("A Complete Unknown", 2024), movie("A.Complete.Unknown.2024.MULTi.2160p.WEB-DL.mkv"))
        assertEquals(ParsedMovie("The Proper Way", 2019), movie("The.Proper.Way.2019.PROPER.1080p.WEB.mkv"))
        assertEquals(ParsedMovie("Hybrid", 2007), movie("Hybrid (2007).mkv"))
        assertEquals(ParsedMovie("Dune Part Two", null), movie("Dune Part Two.mkv"))
    }

    /** A year to come is a title's number, not its year. */
    @Test
    fun aYearToComeIsPartOfTheTitle() {
        assertEquals(ParsedMovie("Blade Runner 2049", null), movie("Blade Runner 2049.mkv"))
        assertEquals(ParsedMovie("Blade Runner 2049", 2017), movie("Blade.Runner.2049.2017.2160p.UHD.BluRay.mkv"))
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

    /** Episodes named in a season's folder. */
    @Test
    fun theNasEpisodes() {
        val season = listOf("Mobland", "S01")
        (1..9).forEach { n ->
            assertEquals(ParsedEpisode("Mobland", 1, n), FilenameParser.parseEpisode("Mobland.S01E0$n.MULTi.1080p.WEB.H265-GRP.mkv", season))
        }
        assertEquals(ParsedEpisode("Mobland", 1, 10), FilenameParser.parseEpisode("Mobland.S01E10.FiNAL.MULTi.1080p.WEB.H265-GRP.mkv", season))
        assertEquals(ParsedEpisode("Mobland", 1, 1), FilenameParser.parseEpisode("Mobland.S01E01.MULTi.1080p.WEB.H265-GRP.mkv", emptyList()))
    }

    /** Shows numbered from their start (Plex's absolute order), and files with an episode number of four digits. */
    @Test
    fun episodesPast999() {
        assertEquals(
            ParsedEpisode("One Piece", 1, 1071),
            FilenameParser.parseEpisode("One Piece - S01E1071 - The Peak.mkv", listOf("One Piece", "Season 1")),
        )
        assertEquals(ParsedEpisode("One Piece", 21, 1071), FilenameParser.parseEpisode("1071 - Le sommet.mkv", listOf("One Piece", "Saison 21")))
        assertNull("a date", FilenameParser.parseEpisode("2019-05-12 - Live.mkv", listOf("Talk Show", "Season 1")))
    }

    /** One file holding several episodes, named as Sonarr's multi-episode styles name it. */
    @Test
    fun filesHoldingSeveralEpisodes() {
        fun episode(name: String) = FilenameParser.parseEpisode(name, emptyList())
        assertEquals(ParsedEpisode("The Office", 1, 1, 2), episode("The.Office.S01E01-E02.720p.mkv"))
        assertEquals(ParsedEpisode("The Office", 1, 1, 2), episode("The.Office.S01E01E02.720p.mkv"))
        assertEquals(ParsedEpisode("The Office", 1, 1, 2), episode("The Office - S01E01-02 - Pilot.mkv"))
        assertEquals(ParsedEpisode("The Office", 1, 1, 3), episode("The Office - S01E01-E02-E03 - Pilot.mkv"))
        assertEquals(ParsedEpisode("The Office", 1, 1, 2), episode("The.Office.S01E01.S01E02.720p.mkv"))
        assertEquals(ParsedEpisode("The Office", 1, 5), episode("The.Office.S01E05-720p.mkv"))
        assertEquals(ParsedEpisode("The Office", 1, 5), episode("The Office - S01E05 - Episode 6.mkv"))
        assertEquals(ParsedEpisode("The Office", 1, 5), episode("The Office - S01E05-2019.mkv"))
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
        assertEquals(0, FilenameParser.seasonFromFolder("Season 00"))
        assertEquals(0, FilenameParser.seasonFromFolder("Specials"))
        assertEquals(0, FilenameParser.seasonFromFolder("Spécial"))
        assertEquals(0, FilenameParser.seasonFromFolder("SPÉCIAUX"))
        assertNull("a show's name", FilenameParser.seasonFromFolder("Special"))
    }

    private fun show(path: String) = FilenameParser.parseShowPath(path)?.let { Triple(it.title, it.year, it.dir) }

    /** The show's folder is the one named as the file names the show, else the first one. */
    @Test
    fun showFolders() {
        assertEquals(Triple("Arcane", null, "Animation/Arcane"), show("Animation/Arcane/Saison 1/Arcane.S01E01.mkv"))
        assertEquals(
            Triple("Marvel's Agents of S.H.I.E.L.D", 2013, "Marvel/Marvel's Agents of S.H.I.E.L.D. (2013)"),
            show("Marvel/Marvel's Agents of S.H.I.E.L.D. (2013)/Season 1/Marvels.Agents.of.S.H.I.E.L.D.S01E01.mkv"),
        )
        // A season's pack keeps its show's folder, though it names the show too.
        assertEquals(
            Triple("Mobland", null, "Mobland"),
            show("Mobland/Mobland.MULTi.1080p.WEB.H265-GRP/Mobland.S01E01.MULTi.1080p.WEB.H265-GRP.mkv"),
        )
        assertEquals(Triple("Mobland", null, "Mobland"), show("Mobland/S01/Mobland.S01E01.MULTi.1080p.WEB.H265-GRP.mkv"))
        assertEquals(Triple("Les Revenants", null, "Les Revenants"), show("Les Revenants/Saison 1/03 - Victor.mkv"))
        assertEquals(Triple("Severance", null, ""), show("Saison 1/Severance.S01E01.mkv"))
        assertNull("nothing names the show", show("Saison 1/03 - Victor.mkv"))
    }

    /** The year of a show is its folder's, else its name's at the source's root; specials are season 0. */
    @Test
    fun showYearsAndSpecials() {
        assertEquals(Triple("Severance", 2022, "Severance (2022)"), show("Severance (2022)/Season 2/severance.s02e01.mkv"))
        assertEquals(Triple("Doctor Who", 2005, ""), show("Doctor.Who.2005.S01E01.mkv"))
        assertEquals(Triple("Doctor Who", 2005, "Doctor Who (2005)"), show("Doctor Who (2005)/Specials/Doctor.Who.2005.S00E01.mkv"))
        assertEquals(ParsedEpisode("Doctor Who", 0, 1), FilenameParser.parseShowPath("Doctor Who (2005)/Specials/01 - Christmas.mkv")?.episode)
        assertEquals("doctor who|2005", FilenameParser.showKey("Doctor Who", 2005))
        assertEquals("doctor who", FilenameParser.showKey("Doctor Who", null))
    }
}
