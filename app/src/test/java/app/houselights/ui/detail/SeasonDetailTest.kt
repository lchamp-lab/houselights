package app.houselights.ui.detail

import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.design.components.SegmentDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the season track says after a season's name: how far it is watched. */
class SeasonDetailTest {
    private fun season(episodes: Int?, unplayed: Int?, played: Boolean = false) = MediaItem(
        id = "s",
        kind = MediaKind.SEASON,
        title = "Saison 1",
        childCount = episodes,
        user = UserState(played = played, unplayedCount = unplayed),
    )

    @Test
    fun doneBegunOrNotYet() {
        assertEquals(SegmentDetail.Done, seasonDetail(season(10, 0)))
        assertEquals(SegmentDetail.Count("6 / 10"), seasonDetail(season(10, 4)))
        assertNull(seasonDetail(season(10, 10)))
        // Marked watched by hand, or a server that says so without counting.
        assertEquals(SegmentDetail.Done, seasonDetail(season(10, 3, played = true)))
        assertEquals(SegmentDetail.Done, seasonDetail(season(null, null, played = true)))
        assertNull(seasonDetail(season(null, null)))
        // A season without episodes, which a server may call watched: nothing to say.
        assertNull(seasonDetail(season(0, 0, played = true)))
    }

    /** What the watched tile reads a season as: watched once all of at least one episode are. */
    @Test
    fun aSeasonIsWatchedOnceAllItsEpisodesAre() {
        assertTrue(seasonWatched(season(10, 0)))
        assertFalse(seasonWatched(season(10, 4)))
        assertFalse(seasonWatched(season(10, 10)))
        assertTrue(seasonWatched(season(10, 3, played = true)))
        assertFalse(seasonWatched(season(10, null)))
        // Without episodes, never, whatever the source says.
        assertFalse(seasonWatched(season(0, 0, played = true)))
        assertFalse(seasonWatched(season(0, null)))
        // Not counted: as the source says.
        assertTrue(seasonWatched(season(null, null, played = true)))
        assertFalse(seasonWatched(season(null, null)))
    }
}
