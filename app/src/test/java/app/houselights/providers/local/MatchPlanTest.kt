package app.houselights.providers.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The searches a title is matched by ([MatchPlan]): which are sent, in which order, and when they stop. */
class MatchPlanTest {
    /** TMDB's answers by search, its alternative titles by id; what was sent and asked, in order. */
    private val answers = mutableMapOf<Search, List<TmdbSearchResult>>()
    private val alternatives = mutableMapOf<Int, List<String>>()
    private val sent = mutableListOf<Search>()
    private val asked = mutableListOf<Int>()

    private fun film(id: Int, title: String, year: Int?, votes: Int = 1_000, original: String? = null) =
        TmdbSearchResult(id, title = title, releaseDate = year?.let { "$it-06-01" }, originalTitle = original, voteCount = votes)

    private fun run(wanted: Wanted, english: Boolean = false): Verdict = runBlocking {
        MatchPlan.run(wanted, english, { search -> answers[search].orEmpty().also { sent += search } }) { id ->
            asked += id
            alternatives[id].orEmpty()
        }
    }

    private fun Verdict.id() = (this as? Verdict.Match)?.best?.candidate?.id

    @Test
    fun aNameMatchedAtOnceAsksOnce() {
        answers[Search("Avatar", 2009)] = listOf(film(19995, "Avatar", 2009, 30_000))
        assertEquals(19995, run(Wanted.of("Avatar", 2009)).id())
        assertEquals(listOf(Search("Avatar", 2009)), sent)
    }

    /** "Wonder Woman 1984.mkv", TMDB putting the film of 1984's name third: the bare year as part of the title puts it first. */
    @Test
    fun aBareYearIsTriedAsPartOfTheTitle() {
        val ww84 = film(464052, "Wonder Woman 1984", 2020, 9_000)
        answers[Search("Wonder Woman", null)] =
            listOf(film(297762, "Wonder Woman", 2017, 20_000), film(1, "Wonder Woman: Bloodlines", 2019, 300), ww84)
        answers[Search("Wonder Woman 1984", null)] = listOf(ww84)
        assertEquals(464052, run(Wanted.of("Wonder Woman", 1984, bareYear = true)).id())
        assertEquals(listOf(Search("Wonder Woman", 1984), Search("Wonder Woman", null), Search("Wonder Woman 1984", null)), sent)
    }

    @Test
    fun theYearMovesByOneOnlyWhenNothingIsNear() {
        answers[Search("Mon film", 2019)] = listOf(film(1, "Mon film", 2019))
        assertEquals(1, run(Wanted.of("Mon film", 2020)).id())
        assertEquals(listOf(Search("Mon film", 2020), Search("Mon film", null), Search("Mon film", 2019)), sent)

        sent.clear()
        answers[Search("Le film", null)] = listOf(film(2, "Le film", 2021), film(3, "Le film", 2019))
        assertTrue(run(Wanted.of("Le film", 2020)) is Verdict.Unsure)
        assertEquals("two alike, a year off: never the year moved", listOf(Search("Le film", 2020), Search("Le film", null)), sent)
    }

    @Test
    fun theEnglishTitlesAreAskedOnlyInAnotherLanguage() {
        answers[Search("The Lord of the Rings", 2001, english = true)] =
            listOf(film(120, "The Lord of the Rings: The Fellowship of the Ring", 2001, 25_000))
        assertEquals(120, run(Wanted.of("The Lord of the Rings", 2001), english = true).id())
        assertEquals(Search("The Lord of the Rings", 2001, english = true), sent.last())

        sent.clear()
        assertEquals(null, run(Wanted.of("The Lord of the Rings", 2001)).id())
        assertTrue(sent.none { it.english })
    }

    /** A concert TMDB has not: the searches its name allows, the text after its dash before the year moved, five. */
    @Test
    fun noMoreThanFiveSearchesForATitle() {
        val wanted = Wanted.of("Metallica - Live in Paris", 2019, bareYear = true, original = "Metallica: Live in Paris")
        run(wanted, english = true)
        val expected = listOf(
            Search("Metallica - Live in Paris", 2019),
            Search("Metallica - Live in Paris", null),
            Search("Metallica - Live in Paris 2019", null),
            Search("Metallica: Live in Paris", 2019),
            Search("Metallica - Live in Paris", 2019, english = true),
        )
        assertEquals(expected, sent)
        sent.clear()
        run(Wanted.of("Metallica - Live in Paris", 2019, bareYear = true), english = true)
        assertEquals("then the text after the dash", Search("Live in Paris", 2019), sent.last())
        assertEquals(MatchPlan.MAX_SEARCHES, sent.size)
    }

    /** "Star Wars Episode IV - A New Hope (1977)": TMDB's first answer of the year, named otherwise, by its alternative titles. */
    @Test
    fun theFirstAnswerOfTheYearIsAskedItsAlternativeTitlesOnce() {
        val wanted = Wanted.of("Star Wars Episode IV - A New Hope", 1977)
        answers[Search(wanted.title, 1977)] = listOf(film(11, "La Guerre des étoiles", 1977, 20_000, original = "Star Wars"))
        alternatives[11] = listOf("Star Wars: Episode IV - A New Hope")
        assertEquals(11, run(wanted).id())
        assertEquals(listOf(Search(wanted.title, 1977)), sent)
        assertEquals(listOf(11), asked)

        sent.clear()
        asked.clear()
        alternatives.clear()
        // The text before the dash finds that film again, first of its year: its alternative titles are not asked again.
        answers[Search("Star Wars Episode IV", 1977)] = answers.getValue(Search(wanted.title, 1977))
        assertEquals(null, run(wanted).id())
        assertTrue(Search("Star Wars Episode IV", 1977) in sent)
        assertEquals("once", listOf(11), asked)
    }
}
