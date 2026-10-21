package app.houselights.ui.detail

import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.design.components.SegmentDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }

    /** The watched tile says the season watched exactly when its tab carries the check. */
    @Test
    fun watchedIsTheCheck() {
        val seasons = listOf(
            season(10, 0), season(10, 4), season(10, 10), season(10, 3, played = true),
            season(null, null, played = true), season(null, null),
        )
        seasons.forEach { assertEquals(it.toString(), seasonDetail(it) == SegmentDetail.Done, seasonWatched(it)) }
    }
}
