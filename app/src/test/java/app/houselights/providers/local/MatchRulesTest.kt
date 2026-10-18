package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchRulesTest {
    private val nfo = NfoInfo(kind = NfoKind.MOVIE, title = "Colony")

    @Test
    fun withoutManualMatchTheNfoAlwaysApplies() {
        assertFalse(MetadataMerge.describesOther(nfo.copy(tmdbId = 1), userTmdbId = null, imdbId = null))
    }

    @Test
    fun nfoNamingAnotherTmdbTitleIsSetAside() {
        assertTrue(MetadataMerge.describesOther(nfo.copy(tmdbId = 1), userTmdbId = 2, imdbId = null))
        assertFalse(MetadataMerge.describesOther(nfo.copy(tmdbId = 2), userTmdbId = 2, imdbId = null))
    }

    @Test
    fun imdbIdsAreComparedWhenTheNfoHasNoTmdbId() {
        val linked = nfo.copy(imdbId = "tt0111161")
        assertFalse(MetadataMerge.describesOther(linked, userTmdbId = 278, imdbId = "TT0111161"))
        assertTrue(MetadataMerge.describesOther(linked, userTmdbId = 680, imdbId = "tt0110912"))
        assertFalse("unknown: the NFO is kept", MetadataMerge.describesOther(linked, userTmdbId = 680, imdbId = null))
        assertFalse("TMDB knows of none", MetadataMerge.describesOther(linked, userTmdbId = 680, imdbId = ""))
    }

    /** A show's NFO by Sonarr: TVDB's id, and IMDb's when TVDB has one. */
    @Test
    fun tvdbIdsAreComparedWhenTheNfoHasNeitherTmdbNorImdbId() {
        val sonarr = NfoInfo(kind = NfoKind.SHOW, title = "The Office (UK)", tvdbId = "78107")
        assertTrue(MetadataMerge.describesOther(sonarr, userTmdbId = 2316, imdbId = "tt0386676", tvdbId = 73244))
        assertFalse(MetadataMerge.describesOther(sonarr, userTmdbId = 2996, imdbId = "tt0290978", tvdbId = 78107))
        assertFalse("unknown: the NFO is kept", MetadataMerge.describesOther(sonarr, userTmdbId = 2316, imdbId = null, tvdbId = null))
        // The IMDb id takes precedence.
        val both = sonarr.copy(imdbId = "tt0290978")
        assertTrue(MetadataMerge.describesOther(both, userTmdbId = 2316, imdbId = "tt0386676", tvdbId = 78107))
        assertTrue(MetadataMerge.describesOther(both, userTmdbId = 2316, imdbId = null, tvdbId = 73244))
    }

    @Test
    fun nfoWithoutIdsDescribesTheFile() {
        assertFalse(MetadataMerge.describesOther(nfo, userTmdbId = 2, imdbId = "tt0110912"))
    }

    @Test
    fun movieNameComesFromTheFileOrItsFolder() {
        assertEquals(ParsedMovie("Colony", 2026), LibraryScanner.parseMovie("Colony (2026)/movie.mkv"))
        assertEquals(ParsedMovie("Top Gun Maverick", null), LibraryScanner.parseMovie("Top Gun Maverick.mkv"))
        assertEquals(ParsedMovie("Bugonia", 2025), LibraryScanner.parseMovie("Films/Bugonia.2025.2160p.mkv"))
    }
}
