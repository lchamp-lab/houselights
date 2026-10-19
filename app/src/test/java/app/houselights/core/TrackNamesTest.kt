package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A track's region and its title, as a technical sheet shows them: only what the row does not say. */
class TrackNamesTest {
    @Test
    fun titlesThatRepeatTheRowSayNothing() {
        // Titles from real files: language, dub, format, channels and bitrate, nothing more.
        listOf(
            "VFF  DD 5.1  384 kbps" to "fr",
            "VO  DDP 5.1 Atmos  768 kbps" to "en",
            "ENG : Dolby TrueHD Atmos 7.1 @4054 kb/s" to "en",
            "VFF : DTS-HD Master Audio 5.1 @2201 kb/s" to "fr-FR",
            "VO Anglaise [TrueHD Atmos 7.1]" to "en",
            "VFF [EAC3 7.1]" to "fr",
            "Dolby Atmos 7.1 Mix" to "en",
            "English Dolby Atmos 7.1" to "en",
            "VO : English" to "en",
            "German DTS-HD MA 5.1" to "ger",
            "Georgian" to "geo",
            "Welsh 5.1" to "wel",
        ).forEach { (title, language) -> assertNull(title, TrackNames.note(title, language)) }
        assertNull(TrackNames.note(null))
    }

    @Test
    fun titlesThatSayMoreStay() {
        listOf("Commentaire audio de Ginette Vincendeau, historienne du cinéma", "Version restaurée", "Compatibility Track")
            .forEach { assertEquals(it, TrackNames.note(it, "fr")) }
    }

    @Test
    fun aRegionComesFromTheTagOrTheDub() {
        assertEquals("CA", TrackNames.region("fr-CA", null))
        assertEquals("419", TrackNames.region("es-419", null))
        assertEquals("CA", TrackNames.region("fr", "VFQ 5.1"))
        assertEquals("FR", TrackNames.region("fre", "VFF  DD 5.1  384 kbps"))
        assertNull(TrackNames.region("en", "VO : English"))
    }
}
