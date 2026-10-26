package app.houselights.providers.trakt

import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.SyncRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A show placed in another order sends Trakt the episodes its files are: Trakt's marks made before the change, under the
 * order before, may name another episode than the file now shows, so none of them marks a file watched.
 */
class SyncPlanOrderTest {
    private fun episode(id: Long, number: Int, reorderedAt: Long?) = SyncRow(
        id, ItemKind.EPISODE, FIREFLY, seasonNumber = 1, episodeNumber = number, lastEpisodeNumber = null, versionOf = null,
        parts = emptyList(), played = false, positionMs = 0, lastPlayedAt = null, durationMs = null, fileDurationMs = null,
        runtimeMin = 44, reorderedAt = reorderedAt,
    )

    @Test
    fun noMarkOlderThanItsShowsChangeOfOrderIsTaken() {
        val watched = mapOf<TraktTitle, Long>(TraktTitle.Episode(FIREFLY, 1, 11) to 5L, TraktTitle.Episode(FIREFLY, 1, 1) to 20L)
        val rows = listOf(episode(1, 11, reorderedAt = 10), episode(2, 1, reorderedAt = 10))
        val plan = SyncPlan.of(rows, watched, emptyList(), union = false)
        assertEquals(mapOf(20L to listOf(2L)), plan.watched)

        val never = SyncPlan.of(listOf(episode(1, 11, reorderedAt = null)), watched, emptyList(), union = false)
        assertEquals("a show never reordered takes them all", mapOf(5L to listOf(1L)), never.watched)
    }

    private companion object {
        const val FIREFLY = 1437
    }
}
