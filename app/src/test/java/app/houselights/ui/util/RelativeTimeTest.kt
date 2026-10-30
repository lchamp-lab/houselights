package app.houselights.ui.util

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Inside a sentence the time is lower-case, whatever the system's casing; set alone, it takes a capital. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr")
class RelativeTimeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    @Test
    fun aSentenceGoesOnInLowerCase() {
        assertEquals("Analysé il y a 3 minutes", lastScanned(context, now - 3 * minute, now).normal())
        assertEquals("Analysé à l’instant", lastScanned(context, now - 1_000, now))
        assertEquals("Analysé hier", lastScanned(context, now - 25 * 60 * minute, now))
        assertEquals("Jamais analysé", lastScanned(context, 0, now))
    }

    @Test
    fun aValueAloneTakesACapital() {
        assertEquals("Il y a 3 minutes", relativeTime(context, now - 3 * minute, now, alone = true).normal())
        assertEquals("Il y a 2 heures", relativeTime(context, now - 2 * 60 * minute, now, alone = true).normal())
        assertEquals("À l’instant", relativeTime(context, now - 1_000, now, alone = true))
        assertEquals("Jamais", relativeTime(context, 0, now, alone = true))
    }

    /** Home's settings door says it short: "Il y a 2 h", "2 hr. ago". */
    @Test
    fun shortItsUnitIsAbbreviated() {
        assertEquals("Il y a 2 h", relativeTime(context, now - 2 * 60 * minute, now, alone = true, short = true).normal())
    }

    @Test
    @Config(qualifiers = "en")
    fun shortInEnglish() {
        assertEquals("2 hr. ago", relativeTime(context, now - 2 * 60 * minute, now, alone = true, short = true).normal())
    }

    /** A year counts 365 days, not DateUtils's 52 weeks: the 364th day is still months ago. */
    @Test
    fun aYearIsThreeHundredAndSixtyFiveDays() {
        assertEquals("Il y a 12 mois", relativeTime(context, now - 364 * day, now, alone = true).normal())
        assertEquals("Il y a 1 an", relativeTime(context, now - 365 * day, now, alone = true).normal())
    }

    @Test
    fun noCapitalAfterAColon() {
        // However the time is worded, a sentence never resumes with a capital.
        listOf(1_000L, 3 * minute, 5 * 60 * minute, 3 * 24 * 60 * minute, 60 * 24 * 60 * minute, 0L).forEach { ago ->
            val at = if (ago == 0L) 0L else now - ago
            val words = relativeTime(context, at, now)
            assertEquals(words, words.replaceFirstChar { it.lowercase() })
        }
    }

    /**
     * Shown a while on a screen left open, the time said moves on with the clock: « Analysé il y a 1 minute » is
     * « il y a 2 minutes » a minute on, by the next reading of the clock ([NOW_TICK_MS]).
     */
    @Test
    fun aTimeShownMovesOnWithTheClock() {
        var clock = now
        compose.setTvContent { Text(lastScanned(now - minute, rememberNow { clock })) }
        compose.onNodeWithText(lastScanned(context, now - minute, now)).assertExists()

        clock += minute
        compose.mainClock.advanceTimeBy(NOW_TICK_MS)
        compose.onNodeWithText(lastScanned(context, now - minute, now + minute)).assertExists()
    }

    /** ICU may set a no-break space between a number and its unit; the words are what is tested. */
    private fun String.normal() = replace(NB, ' ')

    private companion object {
        const val NB = Typography.nbsp
    }
}
