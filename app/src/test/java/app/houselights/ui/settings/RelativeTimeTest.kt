package app.houselights.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Inside a sentence the time is lower-case, whatever the system's casing; set alone, it takes a capital. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr")
class RelativeTimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

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

    @Test
    fun noCapitalAfterAColon() {
        // However the time is worded, a sentence never resumes with a capital.
        listOf(1_000L, 3 * minute, 5 * 60 * minute, 3 * 24 * 60 * minute, 60 * 24 * 60 * minute, 0L).forEach { ago ->
            val at = if (ago == 0L) 0L else now - ago
            val words = relativeTime(context, at, now)
            assertEquals(words, words.replaceFirstChar { it.lowercase() })
        }
    }

    /** ICU may set a no-break space between a number and its unit; the words are what is tested. */
    private fun String.normal() = replace(NB, ' ')

    private companion object {
        const val NB = Typography.nbsp
    }
}
