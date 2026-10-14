package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is new: added in the last week, to a library that was already there, and not seen. */
class AddedTest {
    private val now = 10 * NEW_FOR_MS
    private fun item(addedAt: Long?, played: Boolean = false) =
        MediaItem(id = "1", kind = MediaKind.MOVIE, title = "Alien", addedAt = addedAt, user = UserState(played = played))

    @Test
    fun newForAWeekUntilWatched() {
        assertTrue(item(now - NEW_FOR_MS + 1).isNew(now))
        assertFalse(item(now - NEW_FOR_MS).isNew(now))
        assertFalse(item(now - 1, played = true).isNew(now))
        assertFalse(item(null).isNew(now))
        // A date ahead of the clock (a server set differently) is not taken as new.
        assertFalse(item(now + 1).isNew(now))
    }

    @Test
    fun jellyfinDatesAreRead() {
        assertEquals(1_714_566_896_000L, epochMillisOf("2024-05-01T12:34:56.0000000Z"))
        assertNull(epochMillisOf("2024-05-01"))
        assertNull(epochMillisOf(null))
    }
}
