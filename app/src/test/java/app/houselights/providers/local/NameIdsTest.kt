package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ids names give, as media servers read them and Radarr and Sonarr write them, and when they are asked again. */
class NameIdsTest {
    private fun ids(name: String) = NameIds.of(name)

    /** The films a folder of these [paths] gives [path] the ids of, as a scan reads them. */
    private fun film(path: String, vararg paths: String) = NameIds.ofFilm(path, ScanTitles.holdingOneFilm(paths.toList() + path))

    @Test
    fun everyServersFormOfIdIsRead() {
        val dune = NameIds(tmdb = 438631)
        assertEquals("Plex, Infuse", dune, ids("Dune (2021) {tmdb-438631}"))
        assertEquals("Jellyfin", dune, ids("Dune (2021) [tmdbid-438631]"))
        assertEquals("Jellyfin, short", dune, ids("Dune (2021) [tmdb-438631]"))
        assertEquals("Emby", dune, ids("Dune (2021) [tmdbid=438631]"))
        assertEquals("in parentheses", dune, ids("Dune (2021) (tmdb-438631)"))
        assertEquals("in capitals", dune, ids("Dune (2021) {TMDB-438631}"))
        assertEquals(NameIds(imdb = "tt1160419"), ids("Dune (2021) {imdb-tt1160419}"))
        assertEquals(NameIds(imdb = "tt1160419"), ids("Dune (2021) [imdbid-TT1160419]"))
        assertEquals(NameIds(tvdb = 78804), ids("Doctor Who (2005) {tvdb-78804}"))
        assertEquals(NameIds(tvdb = 78804), ids("Doctor Who (2005) [tvdbid-78804]"))
        assertEquals(
            "Radarr's TRaSH naming",
            NameIds(tmdb = 438631, imdb = "tt1160419"),
            ids("Dune (2021) {tmdb-438631} {imdb-tt1160419} [Bluray-2160p][DV HDR10][TrueHD Atmos 7.1][x265]-FGT"),
        )
        assertEquals(NameIds(), ids("Dune (2021) [Bluray-2160p]"))
    }

    @Test
    fun anImdbIdMustStartWithTt() {
        assertEquals(NameIds(), ids("Dune (2021) {imdb-1160419}"))
        assertEquals("a TMDB id is a number", NameIds(), ids("Dune (2021) {tmdb-tt1160419}"))
        assertEquals(NameIds(), ids("Dune (2021) {tmdb-0}"))
    }

    @Test
    fun twoDifferentIdsOfOneKindCountAsNone() {
        assertEquals(NameIds(imdb = "tt1160419"), ids("Dune {tmdb-438631} {tmdb-841} {imdb-tt1160419}"))
        assertEquals("the same twice is one", NameIds(tmdb = 438631), ids("Dune {tmdb-438631} [tmdbid-438631]"))
    }

    @Test
    fun aFolderOfSeveralFilmsLendsNoneItsId() {
        assertEquals(NameIds(), film("Films {tmdb-438631}/Dune (2021).mkv", "Films {tmdb-438631}/Heat (1995).mkv"))
        assertEquals("nor a file named otherwise", NameIds(), film("Dune {tmdb-438631}/movie.mkv", "Dune {tmdb-438631}/trailer.mkv"))
        assertEquals("its own count", NameIds(tmdb = 949), film("Films {tmdb-1}/Heat (1995) {tmdb-949}.mkv", "Films {tmdb-1}/Dune.mkv"))
    }

    @Test
    fun copiesAndPartsOfOneFilmShareTheirFoldersId() {
        val folder = "Dune (2021) {tmdb-438631}"
        assertEquals(NameIds(tmdb = 438631), film("$folder/Dune (2021) 2160p.mkv", "$folder/Dune.2021.1080p.BluRay.mkv"))
        assertEquals("alone", NameIds(tmdb = 438631), film("$folder/movie.mkv"))
        // A multi-file film is named without its part ([Stack.namedPath]).
        assertEquals(NameIds(tmdb = 949), film("Heat (1995) {tmdb-949}/Heat (1995).avi"))
        assertEquals("a disc, by its folder", NameIds(tmdb = 438631), film("$folder/BDMV/STREAM/00001.m2ts"))
        assertEquals(
            "the file's own first",
            NameIds(tmdb = 841, imdb = "tt1160419"),
            NameIds.ofFilm("Dune {tmdb-438631} {imdb-tt1160419}/Dune (1984) {tmdb-841}.mkv", setOf("Dune {tmdb-438631} {imdb-tt1160419}")),
        )
    }

    /** Two films of one name in one folder, told apart by their years: the folder's id is either's, so neither's. */
    @Test
    fun aFolderOfARemakeAndItsOriginalLendsNoneItsId() {
        assertEquals(NameIds(), film("Dune {tmdb-438631}/Dune (2021).mkv", "Dune {tmdb-438631}/Dune (1984).mkv"))
        assertEquals(emptySet<String>(), ScanTitles.holdingOneFilm(listOf("Dune/Dune (2021).mkv", "Dune/Dune (1984).mkv")))
    }

    @Test
    fun aShowsIdsAreItsFolders() {
        val episode = "Doctor Who (2023) [tvdbid-436992]/Season 01/Doctor Who (2023) - S01E01 - Space Babies.mkv"
        assertEquals(NameIds(tvdb = 436992), NameIds.ofShow(episode))
        assertEquals(NameIds(tvdb = 436992), NameIds.ofShow("Séries/$episode"))
        assertEquals("at the root, its file's", NameIds(tmdb = 95396), NameIds.ofShow("Severance {tmdb-95396} - S01E01.mkv"))
        assertEquals(NameIds(), NameIds.ofShow("Severance/Season 1/Severance - S01E01.mkv"))
    }

    @Test
    fun aTmdbIdComesFirstThenTvdbForAShowThenImdb() {
        val all = NameIds(tmdb = 1399, imdb = "tt0944947", tvdb = 121361)
        assertEquals("tmdb:1399", all.key(show = true))
        assertEquals("tvdb:121361", all.copy(tmdb = null).key(show = true))
        assertEquals("a film by its IMDb id: TMDB finds none by TVDB's", "imdb:tt0944947", all.copy(tmdb = null).key(show = false))
        assertEquals(null, NameIds(tvdb = 121361).key(show = false))
    }

    @Test
    fun anIdIsAskedAgainOnlyWhenRenamedOrADayLater() {
        val names = NameIds(tmdb = 438631)
        val day = NameIds.RETRY_MS
        fun due(row: NameIds.Hinted, now: Long = 1_000, renamed: Boolean = false) =
            names.dueFor(row, show = false, now = now, renamed = renamed)
        assertTrue("never looked up", due(NameIds.Hinted(tmdbId = null, userTmdbId = null, hintKey = null, matchedAt = null)))
        val wrong = NameIds.Hinted(tmdbId = 841, userTmdbId = null, hintKey = null, matchedAt = 1_000)
        assertFalse("just looked up", due(wrong))
        assertTrue("renamed", due(wrong, renamed = true))
        assertTrue("a day later", due(wrong, now = 1_000 + day))
        val tried = NameIds.Hinted(841, null, hintKey = "tmdb:438631", matchedAt = 0)
        assertFalse("tried by it, whatever it found", due(tried, now = day * 9))
        assertFalse("tried by it, then renamed", due(tried, now = day * 9, renamed = true))
        val nothing = NameIds.Hinted(null, null, hintKey = "tmdb:438631", matchedAt = 1_000)
        assertFalse("tried by it, no title found", due(nothing, now = day))
        assertTrue("then a day later", due(nothing, now = 1_000 + day))
        assertFalse("already its own", due(NameIds.Hinted(438631, null, hintKey = null, matchedAt = 0), now = day * 9))
        assertFalse("the user's choice", due(NameIds.Hinted(841, userTmdbId = 841, hintKey = null, matchedAt = 0), now = day * 9))
        assertFalse("no id", NameIds().dueFor(wrong, show = false, now = day * 9, renamed = true))
    }

    /** A title found by a name's id ([NameIds.trusts]), of [titles] and [year], whose [alternatives] are counted when asked. */
    private class Hit(val titles: List<String>, val year: Int?, val alternatives: List<String> = emptyList(), id: Int = 1) {
        var asked = 0
        val found = NameIds.Found(TitleIds(id, imdb = null, tvdb = null), titles, year) { alternatives.also { asked++ } }
    }

    private fun trusts(name: String, year: Int?, found: Hit, nfo: NfoInfo? = null) = runBlocking {
        NameIds.of(name).trusts(found.found, IdKind.TMDB, nfo, listOf(FilenameParser.parseMovie(name).title), year)
    }

    /** A remake's id, a sequel's or another film's of another year is another film's, whatever its title. */
    @Test
    fun anIdOfATitleYearsApartIsNotTrusted() {
        assertFalse("the remake", trusts("Dune (2021) {tmdb-841}", 2021, Hit(listOf("Dune"), 1984)))
        assertFalse("the sequel", trusts("Saw (2004) {tmdb-951491}", 2004, Hit(listOf("Saw X"), 2023)))
        assertFalse("another film", trusts("Up (2009) {tmdb-458723}", 2009, Hit(listOf("Us"), 2019)))
        assertTrue("a year off, as a file's often is", trusts("Dune (2020) {tmdb-438631}", 2020, Hit(listOf("Dune"), 2021)))
        assertTrue("no year to tell", trusts("Dune {tmdb-438631}", null, Hit(listOf("Dune"), 2021)))
    }

    /** A title of other words is trusted only when one of its alternative titles is the name's, never by its year alone. */
    @Test
    fun anIdOfATitleOfOtherWordsIsTrustedOnlyByItsAlternativeTitles() {
        val neighbour = Hit(listOf("Avatar"), 2009, alternatives = listOf("Avatar: An IMAX 3D Experience"))
        assertFalse("a same-year neighbour's id", trusts("Up (2009) {tmdb-19995}", 2009, neighbour))
        assertEquals("its alternative titles asked once", 1, neighbour.asked)
        val chihiro = Hit(listOf("Le Voyage de Chihiro", "千と千尋の神隠し"), 2001, alternatives = listOf("Spirited Away"))
        assertTrue("its English title", trusts("Spirited Away (2001) {tmdb-129}", 2001, chihiro))
        val dune = Hit(listOf("Dune : Première partie"), 2021)
        assertTrue(trusts("Dune (2021) {tmdb-438631}", 2021, dune))
        assertEquals("nothing asked of a title alike", 0, dune.asked)
    }

    /** Another id naming the same title, the NFO's or the name's, settles it: two ids do not err alike. */
    @Test
    fun anIdAnotherIdAgreesWithIsTrustedYearsApart() {
        val bladeRunner = Hit(listOf("Blade Runner"), 1982, id = 78)
        assertTrue("the NFO's TMDB id", trusts("Blade Runner (2007) {tmdb-78}", 2007, bladeRunner, NfoInfo(NfoKind.MOVIE, tmdbId = 78)))
        val another = NfoInfo(NfoKind.MOVIE, tmdbId = 1)
        assertFalse("the NFO's naming another", trusts("Blade Runner (2007) {tmdb-78}", 2007, bladeRunner, another))
        val ids = TitleIds(78, imdb = "tt0083658", tvdb = null)
        val names = NameIds(tmdb = 78, imdb = "tt0083658")
        assertTrue("the name's IMDb id", ids.agree(names, IdKind.TMDB, nfo = null))
        assertFalse("the id that found it is no second", ids.agree(NameIds(tmdb = 78), IdKind.TMDB, nfo = null))
        val sonarr = NfoInfo(NfoKind.SHOW, tvdbId = "371980")
        assertTrue("the NFO's TheTVDB id", TitleIds(1, null, tvdb = 371980).agree(NameIds(), IdKind.IMDB, sonarr))
    }
}
