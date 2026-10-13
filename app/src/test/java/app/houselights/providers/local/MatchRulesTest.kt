package app.houselights.providers.local

import app.houselights.core.NfoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchRulesTest {
    private val nfo = NfoInfo(kind = "movie", title = "Colony")

    @Test
    fun withoutManualMatchTheNfoAlwaysApplies() {
        assertFalse(LibraryScanner.describesOther(nfo.copy(tmdbId = 1), userTmdbId = null, imdbId = null))
    }

    @Test
    fun nfoNamingAnotherTmdbTitleIsSetAside() {
        assertTrue(LibraryScanner.describesOther(nfo.copy(tmdbId = 1), userTmdbId = 2, imdbId = null))
        assertFalse(LibraryScanner.describesOther(nfo.copy(tmdbId = 2), userTmdbId = 2, imdbId = null))
    }

    @Test
    fun imdbIdsAreComparedWhenTheNfoHasNoTmdbId() {
        val linked = nfo.copy(imdbId = "tt0111161")
        assertFalse(LibraryScanner.describesOther(linked, userTmdbId = 278, imdbId = "TT0111161"))
        assertTrue(LibraryScanner.describesOther(linked, userTmdbId = 680, imdbId = "tt0110912"))
        assertFalse("unknown: the NFO is kept", LibraryScanner.describesOther(linked, userTmdbId = 680, imdbId = null))
    }

    @Test
    fun nfoWithoutIdsDescribesTheFile() {
        assertFalse(LibraryScanner.describesOther(nfo, userTmdbId = 2, imdbId = "tt0110912"))
    }

    @Test
    fun movieNameComesFromTheFileOrItsFolder() {
        assertEquals(ParsedMovie("Colony", 2026), LibraryScanner.parseMovie("Colony (2026)/movie.mkv"))
        assertEquals(ParsedMovie("Top Gun Maverick", null), LibraryScanner.parseMovie("Top Gun Maverick.mkv"))
        assertEquals(ParsedMovie("Bugonia", 2025), LibraryScanner.parseMovie("Films/Bugonia.2025.2160p.mkv"))
    }
}
