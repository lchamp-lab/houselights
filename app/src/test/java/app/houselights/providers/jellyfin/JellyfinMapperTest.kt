package app.houselights.providers.jellyfin

import app.houselights.core.HdrFormat.DOLBY_VISION
import app.houselights.core.HdrFormat.HDR10
import app.houselights.core.HdrFormat.HDR10_PLUS
import app.houselights.core.HdrFormat.HLG
import app.houselights.core.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the app reads what Jellyfin says of a video, its range type as HDR formats, and of a box set, its name. */
class JellyfinMapperTest {
    @Test
    fun eachVideoRangeSaysItsFormats() {
        val expected = mapOf(
            null to emptyList(),
            "SDR" to emptyList(),
            "HDR10" to listOf(HDR10),
            "HDR10Plus" to listOf(HDR10_PLUS),
            "HLG" to listOf(HLG),
            "DOVI" to listOf(DOLBY_VISION),
            "DOVIWithHDR10" to listOf(DOLBY_VISION, HDR10),
            "DOVIWithHLG" to listOf(DOLBY_VISION, HLG),
            "DOVIWithSDR" to listOf(DOLBY_VISION),
            // Jellyfin 10.11's: HDR10+ is not lost, and metadata that can't be used is not Dolby Vision.
            "DOVIWithHDR10Plus" to listOf(DOLBY_VISION, HDR10_PLUS),
            "DOVIWithEL" to listOf(DOLBY_VISION),
            "DOVIWithELHDR10Plus" to listOf(DOLBY_VISION, HDR10_PLUS),
            "DOVIInvalid" to emptyList(),
            // A value from a newer server.
            "DOVIWithSomethingNew" to listOf(DOLBY_VISION),
            "Unknown" to emptyList(),
        )
        expected.forEach { (range, formats) -> assertEquals("$range", formats, JellyfinMapper.hdrFormats(range)) }
    }

    /** A box set shows without TMDB's French « - Saga », as a set of the library's own; its « Collection » stays. */
    @Test
    fun aBoxSetShowsWithoutTheWordThatMarksItASaga() {
        assertEquals("John Wick", JellyfinMapper.shownTitle("John Wick - Saga", MediaKind.COLLECTION))
        assertEquals("The Criterion Collection", JellyfinMapper.shownTitle("The Criterion Collection", MediaKind.COLLECTION))
        assertEquals("a film keeps its title", "Top Gun - Saga", JellyfinMapper.shownTitle("Top Gun - Saga", MediaKind.MOVIE))
    }
}
