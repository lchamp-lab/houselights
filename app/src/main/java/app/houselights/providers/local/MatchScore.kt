package app.houselights.providers.local

import app.houselights.core.SortKeys
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How alike a title is to the one a file names, pure: TMDB's own ranking is only one of the clues. */
internal object MatchScore {
    /** A title found by a name's id with less in common than this with the name is another title ([named]). */
    private const val ALIKE_BY_NAME = 0.5

    /** Years a name's id may be off by, as a file's year often is (a festival's, a release's). */
    private const val YEARS_OFF = 1

    /** Below an exact title, a likeness never reaches 1. */
    private const val ALIKE = 0.99

    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val ARTICLES = (SortKeys.ARTICLE_WORDS.split('|') + "l").toSet()

    /** What a title found by a name's id is to the title of that name ([named]). */
    enum class Named {
        /** One of its titles is alike. */
        ALIKE,

        /** Both years known and more than one apart: a remake's id, a sequel's, a typo's. */
        APART,

        /** No title alike, yet no year apart: its alternative titles tell ([alike]), never its year alone. */
        UNLIKE,
    }

    /**
     * What a title an id of a name gave ([NameIds.trusts]), named [titles] (its title and original title) and of
     * [year], is to the one named [wanted] (its readings) in [wantedYear]. A TV id in a film's name, a remake's or a
     * same-year neighbour's id names another title.
     */
    fun named(wanted: List<String>, wantedYear: Int?, titles: List<String>, year: Int?): Named = when {
        wantedYear != null && year != null && abs(wantedYear - year) > YEARS_OFF -> Named.APART
        alike(wanted, titles) -> Named.ALIKE
        else -> Named.UNLIKE
    }

    /** Whether one of [titles] is alike one of [wanted] ([similarity] 0.5 at least). */
    fun alike(wanted: List<String>, titles: List<String>): Boolean =
        wanted.any { name -> titles.any { similarity(name, it) >= ALIKE_BY_NAME } }

    /** [title]'s words, folded ([SortKeys.fold]), without a leading article. */
    fun words(title: String): List<String> {
        val words = SortKeys.fold(title).split(NOT_WORD).filter { it.isNotEmpty() }
        return if (words.size > 1 && words.first() in ARTICLES) words.drop(1) else words
    }

    /** [title]'s words joined: titles of one key are alike. */
    fun key(title: String): String = words(title).joinToString(" ")

    /**
     * How alike two titles are, from 0 to 1: 1 for one key; else their words' share (Dice) or their keys' edit
     * distance, whichever is closer.
     */
    fun similarity(a: String, b: String): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        return min(ALIKE, max(dice(x, y), edits(x.joinToString(" "), y.joinToString(" "))))
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
}
