package app.houselights.providers.local

import app.houselights.core.SortKeys
import app.houselights.core.yearOf
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** A way a file names its title: a title, and a year or none ([Wanted]). */
internal data class Reading(val title: String, val year: Int?)

/**
 * What a title is searched by ([MatchPlan]): its [readings], the first its NFO's or name's title and year; then a bare
 * year read as part of the title ("Wonder Woman 1984"); then its folder's title and year when the file's year is bare
 * ("Death Race 2000 (1975)/Death Race 2000.mkv"). [alsoTitles] are other titles it goes by (its NFO's original title).
 */
internal data class Wanted(val readings: List<Reading>, val alsoTitles: List<String> = emptyList()) {
    val title: String get() = readings.first().title
    val year: Int? get() = readings.first().year

    /** This, going by [title] too (a name's title, its NFO's being another), unless it does already. */
    fun alsoNamed(title: String): Wanted =
        if (readings.any { it.title == title } || title in alsoTitles) this else copy(alsoTitles = alsoTitles + title)

    companion object {
        fun of(title: String, year: Int?, bareYear: Boolean = false, original: String? = null, folder: Reading? = null) = Wanted(
            listOfNotNull(Reading(title, year), Reading("$title $year", null).takeIf { bareYear && year != null }, folder).distinct(),
            listOfNotNull(original?.takeIf { it != title }),
        )
    }
}

/**
 * A title TMDB gave for [Wanted]'s searches, its answers merged by id: every title it goes by (in the client's
 * language, its original, in English, its alternative titles once asked for), its year and votes, and its best place
 * in TMDB's answers ([rank], 1 the first; 0 when TMDB's search did not give it). [result] is shown as a suggestion.
 */
internal data class Candidate(
    val id: Int,
    val titles: List<String>,
    val year: Int?,
    val votes: Int,
    val rank: Int,
    val result: TmdbSearchResult,
) {
    /** This candidate as both answers give it, [other] of the same id. */
    fun with(other: Candidate) = copy(
        titles = (titles + other.titles).distinct(),
        year = year ?: other.year,
        votes = max(votes, other.votes),
        rank = listOf(rank, other.rank).filter { it > 0 }.minOrNull() ?: 0,
    )

    companion object {
        /** A film a name's id found but did not trust ([NameIds.trusts]), offered first as TMDB's search did not give it. */
        fun of(movie: TmdbMovie) = of(
            TmdbSearchResult(
                movie.id, title = movie.title, releaseDate = movie.releaseDate, posterPath = movie.posterPath,
                originalTitle = movie.originalTitle, overview = movie.overview, voteCount = movie.voteCount ?: 0,
            ),
            rank = 0,
        )

        /** A show a name's id found but did not trust, as [of] a film. */
        fun of(tv: TmdbTv) = of(
            TmdbSearchResult(
                tv.id, name = tv.name, firstAirDate = tv.firstAirDate, posterPath = tv.posterPath, originalName = tv.originalName,
                overview = tv.overview, voteCount = tv.voteCount ?: 0,
            ),
            rank = 0,
        )

        fun of(result: TmdbSearchResult, rank: Int) = Candidate(
            id = result.id,
            titles = listOfNotNull(result.title ?: result.name, result.originalTitle ?: result.originalName).distinct(),
            year = yearOf(result.releaseDate ?: result.firstAirDate),
            votes = result.voteCount,
            rank = rank,
            result = result,
        )
    }
}

/** A [candidate] scored against what is wanted ([MatchScore.score]): its titles' likeness [sim], and its [score]. */
internal data class Scored(val candidate: Candidate, val sim: Double, val score: Double)

/** What TMDB's candidates make of a title ([MatchScore.verdict]). */
internal sealed interface Verdict {
    /** One candidate clearly ahead: the title. */
    data class Match(val best: Scored) : Verdict

    /** Candidates alike, none clearly ahead: the title is to identify, these first, the best first. */
    data class Unsure(val top: List<Scored>) : Verdict

    /** No candidate alike enough to be offered. */
    data object NotFound : Verdict
}

/**
 * Chooses among TMDB's candidates for a title, pure: TMDB's own ranking is only one of the clues. Titles are compared
 * by their words, folded ([words]), and their likeness ([similarity]) weighs most in a candidate's [score], with its
 * year, votes and place in TMDB's answers. A candidate is taken only when clearly ahead ([verdict]): a wrong first
 * result is never taken, a title TMDB hesitates on is left to identify with its best candidates.
 */
internal object MatchScore {
    /**
     * A title found by a name's id less alike than this to the name is another title ([alike]): above a part's of
     * other numbers ([OTHER_NUMBERS]), so a sequel's id is never trusted by its title ("Wonder Woman 1984 {tmdb-…}"
     * giving the film of 2017's).
     */
    private const val ALIKE_BY_NAME = 0.6

    /** Years a name's id may be off by, as a file's year often is (a festival's, a release's). */
    private const val YEARS_OFF = 1

    /** Below an exact title, a likeness never reaches 1. */
    private const val ALMOST = 0.99

    /** The title before or after a candidate's colon or dash is the name's: "Rogue One : A Star Wars Story". */
    private const val SUBTITLE = 0.9

    /** Titles whose numbers differ ("Saw IV", "Saw V") are at most this alike, below [RIVAL]. */
    private const val OTHER_NUMBERS = 0.55

    /** A candidate is taken only above this likeness, whatever its votes or place: TMDB's fuzzy hits never are. */
    private const val FLOOR = 0.8

    /** …and this score. */
    private const val SURE = 0.7

    /** …and this far ahead of the best rival… */
    private const val MARGIN = 0.1

    /** …a rival being a candidate at least this alike; weaker ones do not compete. */
    private const val RIVAL = 0.6

    /** Candidates offered when none is ahead are at least this alike, the [OFFERED] best. */
    private const val OFFERED_FROM = 0.5
    const val OFFERED = 3

    /** Without a year to tell them apart, a title of the same likeness with this many times the votes is ahead. */
    private const val DOMINANCE = 20

    private const val SIM_WEIGHT = 0.5
    private const val YEAR_WEIGHT = 0.3
    private const val VOTES_WEIGHT = 0.12
    private const val RANK_WEIGHT = 0.08

    /** Votes past 10,000 count no more ([votes]). */
    private const val VOTE_DIGITS = 4.0

    /** A year's fit ([score]): none wanted, the candidate's unknown; by years apart, the same, one, two, more none. */
    private const val NO_YEAR_WANTED = 0.5
    private const val UNDATED = 0.3
    private val YEARS_APART = listOf(1.0, 0.75, 0.3)

    /** A place's fit in TMDB's answers ([score]): its first three, the others none. */
    private val PLACES = listOf(1.0, 0.6, 0.3)

    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val APOSTROPHES = Regex("""['’ʼ`´]""")
    private val SEPARATORS = listOf(":", " - ", " – ", " — ")
    private val ARTICLES = (SortKeys.ARTICLE_WORDS.split('|') + "l").toSet()

    /** A digit's ordinal, folded: "1st", "2nd", "1re", "1er", "2e", "2eme". */
    private val ORDINAL = Regex("""^(\d{1,2})(?:st|nd|rd|th|e|er|re|eme)$""")
    private val ROMAN = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii", "xiii", "xiv", "xv",
        "xvi", "xvii", "xviii", "xix", "xx").withIndex().associate { (i, roman) -> roman to "${i + 1}" }

    /** Number words up to twenty, English and French ("un" and "une" being articles), and ordinals in words. */
    private val NUMBER_WORDS = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
    ).withIndex().associate { (i, word) -> word to "${i + 1}" } + listOf(
        "", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize", "quatorze",
        "quinze", "seize",
    ).withIndex().filter { it.value.isNotEmpty() }.associate { (i, word) -> word to "${i + 1}" } + mapOf(
        "vingt" to "20", "first" to "1", "second" to "2", "third" to "3", "premier" to "1", "premiere" to "1",
        "seconde" to "2", "deuxieme" to "2", "troisieme" to "3",
    )

    /** Letters an acronym has ("SVU", "NCIS"): fewer are initials or a word, more a word in capitals. */
    private val ACRONYM_LETTERS = 3..5

    /** Words naming a part beside its number ("Part 2", "2e partie", "Volume 1"), dropped there as Radarr drops them. */
    private val PART_WORDS = setOf("part", "partie", "chapter", "chapitre", "volume", "vol", "episode")

    /** A title's words ([forms]), and which were written as acronyms ("SVU" in "Law and Order SVU"). */
    private class Words(val words: List<String>, val acronyms: Set<Int>) {
        val key = words.joinToString(" ")
        val numbers = words.filter(::isNumber).sorted()
    }

    /** [title]'s words joined: titles of one key are alike. */
    fun key(title: String): String = forms(title).first().key

    /**
     * [title]'s words in both its forms, an apostrophe read as a space and as nothing ("Marvel's" is "marvel s" and
     * "marvels"): "&" read "and"; folded ([SortKeys.fold]); single letters joined ("S.H.I.E.L.D." is "shield"); one
     * leading article dropped (not "die", "el", "il" nor "de": "Die Hard" is never "Hard"); numbers read as digits,
     * roman ("Rocky II"), in words to twenty ("Ocean's Eleven") or ordinals ("2e partie"); a part's word beside its
     * number dropped ("Part 2" is "2"). Three to five capitals in a title in mixed case are an acronym.
     */
    private fun forms(title: String): List<Words> {
        val text = title.replace("&", " and ")
        val cased = text.any(Char::isLowerCase) && text.any(Char::isUpperCase)
        val forms = listOf(APOSTROPHES.replace(text, " "), APOSTROPHES.replace(text, "")).distinct()
        return forms.map { words(it, cased) }.distinctBy { it.key }
    }

    private fun words(text: String, cased: Boolean): Words {
        val raw = joinLetters(text.split(NOT_WORD).filter { it.isNotEmpty() })
        val read = raw.map { word -> number(SortKeys.fold(word)) }
        val acronyms = raw.indices.filterTo(HashSet()) { i -> cased && acronym(raw[i]) && !isNumber(read[i]) }
        val kept = read.indices.filterNot { i -> read[i] in PART_WORDS && (numberAt(read, i - 1) || numberAt(read, i + 1)) }
            .let { if (it.size > 1 && read[it.first()] in ARTICLES) it.drop(1) else it }
        return Words(kept.map { read[it] }, kept.indices.filterTo(HashSet()) { kept[it] in acronyms })
    }

    private fun acronym(word: String) = word.length in ACRONYM_LETTERS && word.all(Char::isUpperCase)

    private fun isNumber(word: String) = word.isNotEmpty() && word.all(Char::isDigit)

    private fun numberAt(words: List<String>, at: Int) = words.getOrNull(at)?.let(::isNumber) == true

    /** Runs of two single letters or more as one word: "S H I E L D" is "SHIELD", "A I" is "AI". */
    private fun joinLetters(words: List<String>): List<String> = buildList {
        val run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) add(run.toString())
            run.clear()
        }
        for (word in words) {
            if (word.length == 1 && word[0].isLetter()) {
                run.append(word)
            } else {
                flush()
                add(word)
            }
        }
        flush()
    }

    /** [word] as digits when it is a number: roman to twenty, in words, an ordinal. */
    private fun number(word: String): String =
        ROMAN[word] ?: NUMBER_WORDS[word] ?: ORDINAL.matchEntire(word)?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { null } ?: word

    /**
     * The titles [wanted] goes by in its readings of a year at most one from [year], a title an id of its name found
     * ([NameIds.trusts]), with its other titles; null when every reading's year is known and further: a remake's id, a
     * sequel's, a typo's. A reading or a title without a year is near: "Death Race 2000 {tmdb-13282}" names the film of
     * 1975 by its bare year read as part of its title ([Wanted.of]).
     */
    fun near(wanted: Wanted, year: Int?): List<String>? {
        val near = wanted.readings.filter { it.year == null || year == null || abs(it.year - year) <= YEARS_OFF }
        return if (near.isEmpty()) null else (near.map { it.title } + wanted.alsoTitles).distinct()
    }

    /** Whether one of [titles] is alike one of [wanted] ([similarity] 0.6 at least). */
    fun alike(wanted: List<String>, titles: List<String>): Boolean =
        wanted.any { name -> titles.any { similarity(name, it) >= ALIKE_BY_NAME } }

    /**
     * How alike a [wanted] title is to a [candidate]'s, from 0 to 1, the best of their forms ([forms]): 1 for the same
     * words, in any order, an acronym standing for the words it spells ("SVU", "Special Victims Unit"); 0.9 when one is
     * the other's part before or after its first colon or dash, both ways ("Rogue One", "Rogue One : A Star Wars
     * Story"); else their words' share (Dice) or their keys' edit distance, whichever is closer. Titles whose numbers
     * differ are at most 0.55 alike, so a part is never taken for the next one ("Saw IV", "Saw V").
     */
    fun similarity(wanted: String, candidate: String): Double {
        val whole = forms(wanted).maxOf { a -> forms(candidate).maxOf { b -> alike(a, b) } }
        val parted = parts(candidate).any { sameWords(wanted, it) } || parts(wanted).any { sameWords(it, candidate) }
        return if (parted) max(whole, SUBTITLE) else whole
    }

    private fun alike(a: Words, b: Words): Double {
        if (a.words.isEmpty() || b.words.isEmpty()) return 0.0
        val same = a.words.sorted() == b.words.sorted() || spelled(a, b) || spelled(b, a)
        val sim = if (same) 1.0 else min(ALMOST, max(dice(a.words, b.words), edits(a.key, b.key)))
        return if (a.numbers == b.numbers) sim else min(sim, OTHER_NUMBERS)
    }

    private fun sameWords(a: String, b: String): Boolean =
        forms(a).any { x -> forms(b).any { y -> x.words.isNotEmpty() && x.words.sorted() == y.words.sorted() } }

    /** [title]'s parts before and after its first colon or dash, both non-blank; none without one. */
    private fun parts(title: String): List<String> {
        val at = SEPARATORS.mapNotNull { sep -> title.indexOf(sep).takeIf { it > 0 }?.let { it to sep } }.minByOrNull { it.first }
            ?: return emptyList()
        val parts = listOf(title.substring(0, at.first), title.substring(at.first + at.second.length)).map { it.trim() }
        return if (parts.any { it.isBlank() }) emptyList() else parts
    }

    /** Whether [a]'s words, each acronym replaced by the run of [b]'s words whose initials spell it, are [b]'s. */
    private fun spelled(a: Words, b: Words): Boolean {
        if (a.acronyms.isEmpty()) return false
        val words = a.words.flatMapIndexed { i, word ->
            if (i !in a.acronyms) return@flatMapIndexed listOf(word)
            val start = (0..b.words.size - word.length).firstOrNull { at ->
                (word.indices).all { b.words[at + it].firstOrNull() == word[it] }
            }
            start?.let { b.words.subList(it, it + word.length) } ?: listOf(word)
        }
        return words.sorted() == b.words.sorted()
    }

    /** Twice the words two lists share (each as often as both hold it) over their words. */
    private fun dice(x: List<String>, y: List<String>): Double {
        val left = y.groupingBy { it }.eachCount().toMutableMap()
        var shared = 0
        for (word in x) {
            val count = left[word] ?: 0
            if (count > 0) {
                left[word] = count - 1
                shared++
            }
        }
        return 2.0 * shared / (x.size + y.size)
    }

    /** 1 less the edit distance between [a] and [b] over the longer one's length. */
    private fun edits(a: String, b: String): Double {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val change = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + change)
            }
            previous = current
        }
        return 1.0 - previous[b.length].toDouble() / max(a.length, b.length)
    }

    /**
     * [c] against [wanted], its best reading: 0.50·sim² + 0.30·year + 0.12·votes + 0.08·place, sim the likeness of its
     * titles to the reading's or [Wanted.alsoTitles]; year 1 for the same, 0.75 one apart, 0.3 two, else 0, 0.5 with no
     * year wanted, 0.3 for a candidate undated; votes on a log scale ([votes]); place 1, 0.6, 0.3 for TMDB's first three.
     */
    fun score(wanted: Wanted, c: Candidate): Scored = wanted.readings.map { reading ->
        val sim = (listOf(reading.title) + wanted.alsoTitles).maxOf { name -> c.titles.maxOfOrNull { similarity(name, it) } ?: 0.0 }
        val score = SIM_WEIGHT * sim * sim + YEAR_WEIGHT * yearFit(reading.year, c.year) + VOTES_WEIGHT * votes(c.votes) +
            RANK_WEIGHT * rankFit(c.rank)
        Scored(c, sim, score)
    }.maxBy { it.score }

    private fun yearFit(wanted: Int?, year: Int?): Double = when {
        wanted == null -> NO_YEAR_WANTED
        year == null -> UNDATED
        else -> YEARS_APART.getOrElse(abs(wanted - year)) { 0.0 }
    }

    /** 0 for no vote, 0.5 for 100, 1 from 10,000. */
    private fun votes(count: Int): Double = min(1.0, log10(1.0 + count) / VOTE_DIGITS)

    private fun rankFit(rank: Int): Double = PLACES.getOrElse(rank - 1) { 0.0 }

    /**
     * The title among [pool]: the best candidate when clearly ahead, alike (0.8 at least), scoring 0.7 at least and
     * 0.1 ahead of the best rival (a candidate 0.6 alike at least), or with no year wanted, as alike with 20 times its
     * votes ("Friends", not a namesake of 50 votes); else the [OFFERED] best at least 0.5 alike, none being ahead.
     */
    fun verdict(wanted: Wanted, pool: Collection<Candidate>): Verdict {
        val scored = pool.map { score(wanted, it) }.sortedByDescending { it.score }
        val best = scored.firstOrNull() ?: return Verdict.NotFound
        val rival = scored.drop(1).firstOrNull { it.sim >= RIVAL }
        val ahead = rival == null || best.score - rival.score >= MARGIN || dominates(wanted, best, rival)
        if (best.sim >= FLOOR && best.score >= SURE && ahead) return Verdict.Match(best)
        val top = scored.filter { it.sim >= OFFERED_FROM }.take(OFFERED)
        return if (top.isEmpty()) Verdict.NotFound else Verdict.Unsure(top)
    }

    private fun dominates(wanted: Wanted, best: Scored, rival: Scored): Boolean =
        wanted.readings.all { it.year == null } && best.sim >= rival.sim &&
            best.candidate.votes >= DOMINANCE * max(1, rival.candidate.votes)

    /**
     * The candidates offered for a title [verdict] did not match, best first: [first] (a title a name's id found, not
     * trusted) ahead of the others, at most [OFFERED]; none for a title matched.
     */
    fun offered(wanted: Wanted, verdict: Verdict, first: Candidate?): List<Scored> {
        val top = when (verdict) {
            is Verdict.Match -> return emptyList()
            is Verdict.Unsure -> verdict.top
            Verdict.NotFound -> emptyList()
        }
        return (listOfNotNull(first?.let { score(wanted, it) }) + top).distinctBy { it.candidate.id }.take(OFFERED)
    }

    /** Whether one of [c]'s titles is alike enough to one of [wanted]'s to be matched ([verdict]'s floor). */
    fun alikeEnough(wanted: Wanted, c: Candidate): Boolean =
        (wanted.readings.map { it.title } + wanted.alsoTitles).any { name -> c.titles.any { similarity(name, it) >= FLOOR } }
}
