package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** A title is headed with its own name, an episode with its show's when known. */
class HeadTitleTest {
    @Test
    fun anEpisodeIsHeadedWithItsShow() {
        val episode = MediaItem("e", MediaKind.EPISODE, "Hello, Ms. Cobel", seriesTitle = "Severance")
        assertEquals("Severance", episode.headTitle)
        assertEquals("Hello, Ms. Cobel", episode.copy(seriesTitle = null).headTitle)
    }

    @Test
    fun anythingElseKeepsItsOwnName() {
        assertEquals("Alien", MediaItem("m", MediaKind.MOVIE, "Alien").headTitle)
        assertEquals("Severance", MediaItem("t", MediaKind.SERIES, "Severance").headTitle)
        assertEquals("Alien, la saga", MediaItem("c", MediaKind.COLLECTION, "Alien, la saga").headTitle)
        // A season knows its show, yet is headed with its own name.
        assertEquals("Saison 2", MediaItem("s", MediaKind.SEASON, "Saison 2", seriesTitle = "Severance").headTitle)
    }
}
