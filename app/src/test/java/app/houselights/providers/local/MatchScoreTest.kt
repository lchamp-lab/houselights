package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a file's name is matched among TMDB's candidates ([MatchScore]), on real names; votes are TMDB's, rounded. A
 * candidate is taken only when clearly ahead, else the title is left to identify with its best candidates.
 */
class MatchScoreTest {
    private var rank = 0

    /** A candidate in TMDB's answer, the next place each time. */
    private fun film(id: Int, title: String, year: Int?, votes: Int, original: String? = null, place: Int = ++rank) =
        Candidate.of(
            TmdbSearchResult(id, title = title, releaseDate = year?.let { "$it-06-01" }, originalTitle = original, voteCount = votes),
            rank = place,
        )

    private fun verdict(wanted: Wanted, vararg pool: Candidate) = MatchScore.verdict(wanted, pool.toList())

    private fun matched(wanted: Wanted, vararg pool: Candidate): Int? = (verdict(wanted, *pool) as? Verdict.Match)?.best?.candidate?.id

    private fun unsure(wanted: Wanted, vararg pool: Candidate): List<Int>? =
        (verdict(wanted, *pool) as? Verdict.Unsure)?.top?.map { it.candidate.id }

    private fun sim(a: String, b: String) = MatchScore.similarity(a, b)

    @Test
    fun keysFoldAccentsArticlesAndPunctuation() {
        assertEquals(MatchScore.key("La Communauté de l'anneau"), MatchScore.key("La.Communaute.de.l.Anneau"))
        assertEquals("arnaque", MatchScore.key("L'Arnaque"))
        assertEquals("arnaque", MatchScore.key("L.Arnaque"))
        assertEquals("office", MatchScore.key("The Office"))
        assertEquals("not an article", "die hard", MatchScore.key("Die Hard"))
        assertEquals("oedipe roi", MatchScore.key("Œdipe roi"))
        val shield = sim("Marvels.Agents.of.S.H.I.E.L.D", "Marvel's Agents of S.H.I.E.L.D.")
        assertEquals("an apostrophe read both ways", 1.0, shield, 0.0)
        assertEquals("fast and furious", MatchScore.key("Fast & Furious"))
    }

    @Test
    fun anAcronymInCapitalsStandsForItsWords() {
        assertEquals(1.0, sim("Law and Order SVU", "Law & Order: Special Victims Unit"), 0.0)
        assertTrue("a name all in capitals has no acronym", sim("LAW AND ORDER SVU", "Law & Order: Special Victims Unit") < 0.8)
    }

    @Test
    fun romanNumeralsEqualDigits() {
        assertEquals(1.0, sim("Rocky II", "Rocky 2"), 0.0)
        assertEquals("not an acronym", 1.0, sim("Rocky III", "Rocky 3"), 0.0)
    }

    @Test
    fun numberWordsAndOrdinalsEqualDigits() {
        assertEquals("in words", 1.0, sim("Oceans 11", "Ocean's Eleven"), 0.0)
        assertEquals("an ordinal and its part's word", 1.0, sim("Le Parrain 2", "Le Parrain, 2e partie"), 0.0)
        assertEquals(1.0, sim("The Godfather Part II", "The Godfather 2"), 0.0)
    }

    /** A title's part after its colon or dash is the title, or its part before, both ways; never without the separator. */
    @Test
    fun aSubtitleAfterTheColonIsTheTitle() {
        assertEquals(0.9, sim("La Communauté de l'anneau", "Le Seigneur des anneaux : La Communauté de l'anneau"), 0.0)
        assertEquals(0.9, sim("Rogue One", "Rogue One : A Star Wars Story"), 0.0)
        val dashed = sim("Le Seigneur des anneaux - La Communauté de l'anneau", "La Communauté de l'anneau")
        assertEquals("the name's own dash", 0.9, dashed, 0.0)
        assertTrue(sim("Dune", "Dune Drifter") < 0.8)
    }

    @Test
    fun theOriginalTitleMatchesToo() {
        assertEquals(1, matched(Wanted.of("The Godfather", 1972), film(1, "Le Parrain", 1972, 20_000, original = "The Godfather")))
    }

    @Test
    fun aTitleOfOtherWordsIsNeverMatched() {
        assertEquals(Verdict.NotFound, verdict(Wanted.of("Inception", 2010), film(1, "The Cobol Job", 2010, 50)))
        assertEquals(null, matched(Wanted.of("Dune", 2020), film(1, "Dune Drifter", 2020, 100)))
    }

    /** "Wonder Woman 1984.mkv": the bare year is the title's, the 2017 film TMDB's first answer is not. */
    @Test
    fun wonderWoman1984IsTheFilmOf2020() {
        val wanted = Wanted.of("Wonder Woman", 1984, bareYear = true)
        val films = arrayOf(film(297762, "Wonder Woman", 2017, 20_000), film(464052, "Wonder Woman 1984", 2020, 9_000))
        assertEquals(464052, matched(wanted, *films))
    }

    /** "Dune (2020).mkv": TMDB's first answer of 2020 is another film, Dune of 2021 a year off. */
    @Test
    fun dune2020IsDuneOf2021() {
        val wanted = Wanted.of("Dune", 2020)
        val drifter = film(1, "Dune Drifter", 2020, 100)
        val dunes = arrayOf(drifter, film(438631, "Dune", 2021, 12_000, place = 1), film(841, "Dune", 1984, 3_000))
        assertEquals(438631, matched(wanted, *dunes))
    }

    @Test
    fun lawAndOrderSvuIsSpecialVictimsUnit() {
        val wanted = Wanted.of("Law and Order SVU", null)
        val shows = arrayOf(film(2734, "Law & Order: Special Victims Unit", 1999, 3_000), film(549, "Law & Order", 1990, 1_500))
        assertEquals(2734, matched(wanted, *shows))
    }

    @Test
    fun aConcertTmdbHasNotIsLeftToIdentify() {
        val wanted = Wanted.of("Metallica - Live in Paris", 2019, bareYear = true)
        assertEquals(null, matched(wanted, film(1, "Coldplay: Live in Paris", 2019, 50)))
        val foresti = Wanted.of("Florence Foresti - L'Intégrale", null)
        assertEquals("another show of hers", null, matched(foresti, film(2, "Florence Foresti - Mother Fucker", 2013, 40)))
    }

    /** "Dune.mkv": both films are Dune, and neither is clearly ahead. */
    @Test
    fun aRemakeWithoutAYearIsLeftToIdentify() {
        val dunes = arrayOf(
            film(438631, "Dune", 2021, 12_000),
            film(841, "Dune", 1984, 3_000),
            film(693134, "Dune : Deuxième partie", 2024, 6_000),
        )
        assertEquals(listOf(438631, 841, 693134), unsure(Wanted.of("Dune", null), *dunes))
    }

    /** A file a year off its part's year takes the next part for none: TMDB's first answer of that year is the next part. */
    @Test
    fun aPartOfAnotherYearIsNeverTakenForItsSequel() {
        val hallows = Wanted.of("Harry Potter and the Deathly Hallows Part 2", 2010)
        val partOne = film(12444, "Harry Potter and the Deathly Hallows: Part 1", 2010, 18_000)
        assertEquals(null, matched(hallows, partOne))
        val partTwo = film(12445, "Harry Potter and the Deathly Hallows: Part 2", 2011, 19_000, place = 1)
        assertEquals(12445, matched(hallows, partOne, partTwo))
        val saw = Wanted.of("Saw IV", 2008)
        val sawV = film(11917, "Saw V", 2008, 3_000)
        assertEquals(null, matched(saw, sawV))
        assertEquals(663, matched(saw, sawV, film(663, "Saw IV", 2007, 3_500, place = 1)))
        val mockingjay = Wanted.of("The Hunger Games Mockingjay Part 1", 2015)
        val finale = film(131634, "The Hunger Games: Mockingjay - Part 2", 2015, 13_000)
        assertEquals(null, matched(mockingjay, finale))
        assertEquals(131631, matched(mockingjay, finale, film(131631, "The Hunger Games: Mockingjay - Part 1", 2014, 17_000, place = 1)))
    }

    /** Names 0.3.0 matched through TMDB's first answer, still matched. */
    @Test
    fun aTitleBeforeItsColonIsMatched() {
        assertEquals(330459, matched(Wanted.of("Rogue One", 2016), film(330459, "Rogue One : A Star Wars Story", 2016, 15_000)))
        rank = 0
        val bills = arrayOf(film(24, "Kill Bill : Volume 1", 2003, 17_000), film(393, "Kill Bill : Volume 2", 2004, 14_000))
        assertEquals(24, matched(Wanted.of("Kill Bill", 2003), *bills))
        rank = 0
        val borat = film(496, "Borat : Leçons culturelles sur l'Amérique pour profit glorieuse nation Kazakhstan", 2006, 7_000)
        assertEquals(496, matched(Wanted.of("Borat", 2006), borat, film(740985, "Borat, nouvelle mission filmée", 2020, 2_000)))
        rank = 0
        val godfathers = arrayOf(
            film(240, "Le Parrain, 2e partie", 1974, 12_000, original = "The Godfather Part II"),
            film(238, "Le Parrain", 1972, 20_000),
            film(242, "Le Parrain, 3e partie", 1990, 6_000),
        )
        assertEquals(240, matched(Wanted.of("Le Parrain 2", 1974), *godfathers))
    }

    /** "Star Wars Episode IV - A New Hope (1977)": TMDB's title is "Star Wars", its alternative titles hold the name's. */
    @Test
    fun aTitleTmdbKnowsByAnotherIsMatchedByItsAlternativeTitles() {
        val wanted = Wanted.of("Star Wars Episode IV - A New Hope", 1977)
        val starWars = film(11, "La Guerre des étoiles", 1977, 20_000, original = "Star Wars")
        assertEquals(null, matched(wanted, starWars))
        assertEquals(11, matched(wanted, starWars.copy(titles = starWars.titles + "Star Wars: Episode IV - A New Hope")))
    }

    /** Folders rarely give a show's year: a show far more voted than its namesake is ahead, two as known are not. */
    @Test
    fun aShowWithoutAYearIsTheOneFarMoreKnown() {
        assertEquals(1668, matched(Wanted.of("Friends", null), film(1668, "Friends", 1994, 8_000), film(1, "Friends", 2023, 50)))
        assertEquals(1419, matched(Wanted.of("Castle", null), film(1419, "Castle", 2009, 1_500), film(2, "Castle", 1997, 20)))
        val offices = arrayOf(film(2316, "The Office", 2005, 3_000), film(2996, "The Office", 2001, 1_000))
        assertEquals("two as known", listOf(2316, 2996), unsure(Wanted.of("The Office", null), *offices))
    }

    /** "Death Race 2000 (1975)/Death Race 2000.mkv": the file's bare year is the title's, its folder's year the film's. */
    @Test
    fun aFoldersYearReadsABareYearAsTheTitles() {
        val wanted = Wanted.of("Death Race", 2000, bareYear = true, folder = Reading("Death Race 2000", 1975))
        val races = arrayOf(
            film(10483, "Death Race", 2008, 2_500),
            film(13282, "Death Race 2000", 1975, 400),
            film(36670, "Death Race 2", 2010, 700),
        )
        assertEquals(13282, matched(wanted, *races))
    }

    /** A title a name's id found but not trusted is offered first, the others after, three at most. */
    @Test
    fun aTitleANamesIdFoundIsOfferedFirst() {
        val wanted = Wanted.of("Dune", null)
        val dunes = listOf(
            film(438631, "Dune", 2021, 12_000),
            film(841, "Dune", 1984, 3_000),
            film(693134, "Dune : Deuxième partie", 2024, 6_000),
        )
        val verdict = MatchScore.verdict(wanted, dunes)
        val first = film(1, "Dune Drifter", 2022, 100, place = 0)
        assertEquals(listOf(1, 438631, 841), MatchScore.offered(wanted, verdict, first).map { it.candidate.id })
        assertEquals(listOf(1), MatchScore.offered(wanted, Verdict.NotFound, first).map { it.candidate.id })
        assertEquals(emptyList<Scored>(), MatchScore.offered(wanted, Verdict.Match(MatchScore.score(wanted, dunes[0])), first))
    }
}
