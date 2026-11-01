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

    /** "Star Wars Episode IV A New Hope.mkv": no year, TMDB's only answer "Star Wars", named so by its alternative titles. */
    @Test
    fun theOnlyAnswerToANameWithoutAYearIsAskedItsAlternativeTitles() {
        val wanted = Wanted.of("Star Wars Episode IV A New Hope", null)
        answers[Search(wanted.title, null)] = listOf(film(11, "La Guerre des étoiles", 1977, 23_000, original = "Star Wars"))
        alternatives[11] = listOf("Star Wars: Episode IV - A New Hope")
        assertEquals(11, run(wanted, english = true).id())
        assertEquals(listOf(Search(wanted.title, null)), sent)
        assertEquals(listOf(11), asked)
    }

    /** Without a year, a first answer among others as voted is not asked; one with twenty times the votes of each is. */
    @Test
    fun aFirstAnswerAmongOthersAsVotedIsNotAskedItsAlternativeTitles() {
        val wanted = Wanted.of("Mon film", null)
        answers[Search("Mon film", null)] = listOf(film(1, "Un autre", 2001, 2_000), film(2, "Un troisième", 2003, 1_000))
        alternatives[1] = listOf("Mon film")
        assertTrue(run(wanted).id() == null)
        assertEquals(emptyList<Int>(), asked)

        answers[Search("Mon film", null)] = listOf(film(1, "Un autre", 2001, 20_000), film(2, "Un troisième", 2003, 1_000))
        assertEquals(1, run(wanted).id())
        assertEquals(listOf(1), asked)
    }

    /** A name with a year: the search without it is not asked the alternative titles of a first answer of another year. */
    @Test
    fun aSearchWithoutTheNamesYearIsNotAskedAlternativeTitles() {
        val wanted = Wanted.of("Mon film", 2000)
        answers[Search("Mon film", null)] = listOf(film(1, "Un autre", 1990, 20_000))
        alternatives[1] = listOf("Mon film")
        assertEquals(null, run(wanted).id())
        assertTrue(Search("Mon film", null) in sent)
        assertEquals(emptyList<Int>(), asked)
    }

    /**
     * "Star Wars Episode VI Return of the Jedi.mkv": TMDB's "Return of the Jedi" alike, not enough to be taken without a
     * year; its alternative titles name it. A first answer named as the name is ("Dune") is never asked them.
     */
    @Test
    fun aFirstAnswerAlikeButNamedOtherwiseIsAskedItsAlternativeTitles() {
        val wanted = Wanted.of("Star Wars Episode VI Return of the Jedi", null)
        val jedi = film(1892, "Le Retour du Jedi", 1983, 16_000, original = "Return of the Jedi")
        answers[Search(wanted.title, null)] = listOf(jedi)
        assertTrue("alike, not taken", run(wanted).id() == null)
        assertEquals(listOf(1892), asked)

        asked.clear()
        alternatives[1892] = listOf("Star Wars: Episode VI - Return of the Jedi")
        assertEquals(1892, run(wanted).id())

        asked.clear()
        answers[Search("Dune", null)] = listOf(film(438631, "Dune", 2021, 12_000), film(841, "Dune", 1984, 3_000))
        assertTrue(run(Wanted.of("Dune", null)) is Verdict.Unsure)
        assertEquals(emptyList<Int>(), asked)
    }

    /**
     * "Le Grand Voyage", a film TMDB has by that very title beside another whose alternative titles, every country's,
     * hold it: the first is not asked them, so the name stays to identify, the film titled so offered, as before.
     */
    @Test
    fun aFirstAnswerIsNotAskedItsAlternativeTitlesWhileAnotherIsNamed() {
        val wanted = Wanted.of("Le Grand Voyage", null)
        answers[Search("Le Grand Voyage", null)] = listOf(film(1, "The Long Journey", 2004, 500), film(2, "Le Grand Voyage", 2004, 0))
        alternatives[1] = listOf("Le Grand Voyage")
        val verdict = run(wanted)
        assertEquals(listOf(2), (verdict as Verdict.Unsure).top.map { it.candidate.id })
        assertEquals(emptyList<Int>(), asked)
    }

    /**
     * The nine episodes of Star Wars named without a year, as TMDB answers each in French: one answer, its English title
     * the original. Episodes I to III by their titles; VII to IX by theirs, the episode being no other number; IV to VI by
     * their alternative titles, the only ones asked; Rogue One and Solo as before.
     */
    @Test
    fun theWholeSagaWithoutYearsIsMatched() {
        val saga = listOf(
            Named("Star Wars Episode I The Phantom Menace", 1893, "Star Wars, épisode I : La Menace fantôme", 1999),
            Named("Star Wars Episode II Attack of the Clones", 1894, "Star Wars, épisode II : L'Attaque des clones", 2002),
            Named("Star Wars Episode III Revenge of the Sith", 1895, "Star Wars, épisode III : La Revanche des Sith", 2005),
            Named("Star Wars Episode IV A New Hope", 11, "La Guerre des étoiles", 1977),
            Named("Star Wars Episode V The Empire Strikes Back", 1891, "L'Empire contre-attaque", 1980),
            Named("Star Wars Episode VI Return of the Jedi", 1892, "Le Retour du Jedi", 1983),
            Named("Star Wars Episode VII The Force Awakens", 140607, "Star Wars : Le Réveil de la Force", 2015),
            Named("Star Wars Episode VIII The Last Jedi", 181808, "Star Wars : Les Derniers Jedi", 2017),
            Named("Star Wars Episode IX The Rise of Skywalker", 181812, "Star Wars : L'Ascension de Skywalker", 2019),
            Named("Rogue One A Star Wars Story", 330459, "Rogue One : A Star Wars Story", 2016),
            Named("Solo A Star Wars Story", 348350, "Solo : A Star Wars Story", 2018),
        )
        for (named in saga) {
            val result = film(named.id, named.title, named.year, 10_000, original = SAGA_ORIGINALS[named.id])
            answers[Search(named.name, null)] = listOf(result)
            answers[Search(named.name, null, english = true)] = listOf(result)
            alternatives[named.id] = SAGA_ALTERNATIVES[named.id].orEmpty()
        }
        assertEquals(saga.map { it.id }, saga.map { run(Wanted.of(it.name, null), english = true).id() })
        assertEquals("one search each", saga.map { Search(it.name, null) }, sent)
        assertEquals("IV to VI only", listOf(11, 1891, 1892), asked)
    }

    /** A film named [name], TMDB's [id], its French [title] spaced before its colon as TMDB spaces it, by a no-break space. */
    private class Named(val name: String, val id: Int, title: String, val year: Int) {
        val title = title.replace(" :", "\u00a0:")
    }

    private companion object {
        /** The English titles of Star Wars' films, TMDB's originals. */
        val SAGA_ORIGINALS = mapOf(
            1893 to "Star Wars: Episode I - The Phantom Menace",
            1894 to "Star Wars: Episode II - Attack of the Clones",
            1895 to "Star Wars: Episode III - Revenge of the Sith",
            11 to "Star Wars",
            1891 to "The Empire Strikes Back",
            1892 to "Return of the Jedi",
            140607 to "Star Wars: The Force Awakens",
            181808 to "Star Wars: The Last Jedi",
            181812 to "Star Wars: The Rise of Skywalker",
        )

        /** TMDB's alternative titles of Star Wars' episodes; Episode VIII has none in English. */
        val SAGA_ALTERNATIVES = mapOf(
            11 to listOf("Star Wars: Episode IV - A New Hope", "Star Wars Episode IV – A New Hope"),
            1891 to listOf("Star Wars: Episode V - The Empire Strikes Back"),
            1892 to listOf("Star Wars: Episode VI - Return of the Jedi"),
            140607 to listOf("Star Wars: Episode VII - The Force Awakens"),
            181808 to listOf("Star Wars - Episode VIII - Les Derniers Jedi"),
            181812 to listOf("Star Wars: Episode IX - The Rise of Skywalker"),
        )
    }
}
