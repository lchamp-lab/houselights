package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A title's words are read once ([MatchScore]), however often it is compared: by every candidate, every verdict. */
class MatchFormsTest {
    @Test
    fun aTitlesWordsAreReadOnceForEveryComparison() {
        val title = "Rogue One : A Star Wars Story"
        val key = MatchScore.key(title)
        assertEquals("rogue 1 a star wars story", key)
        assertSame("the words read before", key, MatchScore.key(title))
        assertEquals("its part before the colon", 0.9, MatchScore.similarity("Rogue One", title), 1e-9)
        assertSame("still, once compared", key, MatchScore.key(title))
    }
}
