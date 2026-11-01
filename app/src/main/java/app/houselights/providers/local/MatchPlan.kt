package app.houselights.providers.local

import kotlin.math.abs
import kotlin.math.max

/** A search [MatchPlan] sends: a title, with a year or without, in the client's language or in English. */
internal data class Search(val title: String, val year: Int?, val english: Boolean = false)

/** What a title's searches found ([TmdbClient.matchMovie]): the title, else the candidates offered, the best first. */
internal class Matched<out T>(val found: T?, val offered: List<Scored> = emptyList())

/**
 * The searches a title is matched by, fewest first: each answer joins the candidates, scored again ([MatchScore]),
 * until one is clearly ahead, at most [MAX_SEARCHES]. In order: the title and its year; without the year; a bare year
 * as part of the title; the folder's title and year; the NFO's original title; in English, the client speaking
 * another language; for a name holding " - ", the text after it, then before it ("Metallica - Live in Paris"); the
 * year one before and one after, only when no candidate alike is near the year; "&" and "and" swapped. When TMDB's
 * first answer is not named as the name is ([worthAsking]), its alternative titles are asked, once.
 */
internal object MatchPlan {
    /** Searches a title takes at most; a clean name takes one. */
    const val MAX_SEARCHES = 5

    private const val DASH = " - "
    private val AND = Regex("""(?i)\band\b""")

    /**
     * What TMDB's answers to [wanted]'s searches ([search], its results best first) make of it; [english] when the
     * client speaks another language. [alternatives] gives a title's alternative titles ([TmdbClient.alternativeTitles]).
     */
    suspend fun run(
        wanted: Wanted,
        english: Boolean,
        search: suspend (Search) -> List<TmdbSearchResult>,
        alternatives: suspend (Int) -> List<String>,
    ): Verdict {
        val pool = LinkedHashMap<Int, Candidate>()
        val sent = mutableListOf<Search>()
        var asked = false
        var verdict: Verdict = Verdict.NotFound
        while (sent.size < MAX_SEARCHES) {
            val next = next(wanted, english, sent, pool.values) ?: break
            sent += next
            val results = search(next)
            results.forEachIndexed { i, result ->
                val found = Candidate.of(result, rank = i + 1)
                pool[result.id] = pool[result.id]?.with(found) ?: found
            }
            verdict = MatchScore.verdict(wanted, pool.values)
            if (verdict is Verdict.Match) return verdict
            // TMDB's first answer, named otherwise: its alternative titles may be the name's.
            val top = results.firstOrNull()?.let { pool[it.id] }?.takeIf { !asked && worthAsking(wanted, next, results, it) }
            if (top != null) {
                asked = true
                pool[top.id] = top.copy(titles = (top.titles + alternatives(top.id)).distinct())
                verdict = MatchScore.verdict(wanted, pool.values)
                if (verdict is Verdict.Match) return verdict
            }
        }
        return verdict
    }

    /**
     * Whether [top], TMDB's first answer to [search] ([results]), is asked its alternative titles: when none of its titles
     * names the name ([MatchScore.named]), and it is of the year asked; or, the name giving no year at all, when it is
     * the only answer or has 20 times the votes of each other ("Star Wars Episode IV A New Hope", TMDB's "Star Wars").
     */
    private fun worthAsking(wanted: Wanted, search: Search, results: List<TmdbSearchResult>, top: Candidate): Boolean = when {
        MatchScore.named(wanted, top) -> false
        search.year != null -> top.year == search.year
        else -> wanted.readings.all { it.year == null } &&
            results.drop(1).all { top.votes >= MatchScore.DOMINANCE * max(1, it.voteCount) }
    }

    /** The first of [planned]'s searches not [sent] yet; the year moved by one only while nothing alike is near it. */
    private fun next(wanted: Wanted, english: Boolean, sent: List<Search>, pool: Collection<Candidate>): Search? =
        planned(wanted, english, pool).firstOrNull { it !in sent && it.title.isNotBlank() }

    private fun planned(wanted: Wanted, english: Boolean, pool: Collection<Candidate>): Sequence<Search> = sequence {
        val title = wanted.title
        val year = wanted.year
        if (year != null) yield(Search(title, year))
        yield(Search(title, null))
        wanted.readings.drop(1).forEach { yield(Search(it.title, it.year)) }
        wanted.alsoTitles.forEach { yield(Search(it, year)) }
        if (english) yield(Search(title, year, english = true))
        if (DASH in title) {
            yield(Search(title.substringAfter(DASH).trim(), year))
            yield(Search(title.substringBefore(DASH).trim(), year))
        }
        if (year != null && pool.none { near(wanted, it, year) }) {
            yield(Search(title, year - 1))
            yield(Search(title, year + 1))
        }
        swapped(title)?.let { yield(Search(it, year)) }
    }

    /** Whether [c], of a year at most one from [year], is alike enough to be matched. */
    private fun near(wanted: Wanted, c: Candidate, year: Int): Boolean = c.year != null && abs(c.year - year) <= 1 && alike(wanted, c)

    private fun alike(wanted: Wanted, c: Candidate): Boolean = MatchScore.alikeEnough(wanted, c)

    /** [title] with "&" for "and", or "and" for "&"; null with neither. */
    private fun swapped(title: String): String? = when {
        "&" in title -> title.replace("&", "and")
        AND.containsMatchIn(title) -> AND.replace(title, "&")
        else -> null
    }
}
