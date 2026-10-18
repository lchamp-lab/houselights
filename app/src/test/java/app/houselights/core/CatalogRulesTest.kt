package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Catalog reading rules: edition names, and an NFO's people, votes and edition. */
class CatalogRulesTest {
    @Test
    fun editionsAreKnownWhateverTheirSpelling() {
        assertEquals(Editions.DIRECTORS_CUT, Editions.of("Director's Cut"))
        assertEquals(Editions.DIRECTORS_CUT, Editions.of("DIRECTORS_CUT"))
        assertEquals(Editions.DIRECTORS_CUT, Editions.of("Montage du réalisateur"))
        assertEquals(Editions.EXTENDED, Editions.of("Version longue"))
        assertEquals(Editions.EXTENDED, Editions.of("Extended Edition"))
        assertEquals(Editions.THEATRICAL, Editions.of("Theatrical"))
        assertEquals(Editions.FINAL_CUT, Editions.of("The Final Cut"))
        assertEquals(Editions.REMASTERED, Editions.of("Remastered Edition"))
        assertEquals("one no token knows stays as written", "Fan Cut", Editions.of(" Fan Cut "))
        assertNull(Editions.of(""))
    }

    @Test
    fun anEditionIsFoundAmongWordsAsWholeWordsOnly() {
        assertEquals(Editions.SPECIAL, Editions.find("Special Edition 1080p BluRay"))
        assertEquals(Editions.IMAX, Editions.find("IMAX.2160p"))
        assertEquals(Editions.UNRATED, Editions.find(" - Unrated"))
        assertNull(Editions.find("Imaxx 1080p"))
        assertNull(Editions.find("1080p x265"))
    }

    @Test
    fun anNfosWritersVotesEditionAndActorsIdsAreRead() {
        val nfo = checkNotNull(
            NfoParser.parse(
                """
                <movie>
                    <title>Heat</title>
                    <ratings><rating name="imdb" max="10" default="true"><value>8.3</value><votes>720,431</votes></rating></ratings>
                    <credits>Michael Mann</credits>
                    <credits>Michael Mann</credits>
                    <edition>Director's Definitive Edition</edition>
                    <actor><name>Al Pacino</name><role>Vincent Hanna</role><tmdbid>1158</tmdbid></actor>
                    <actor><name>Robert De Niro</name></actor>
                </movie>
                """.trimIndent()
            )
        )
        assertEquals(8.3, checkNotNull(nfo.rating), 0.001)
        assertEquals(720_431, nfo.votes)
        assertEquals(listOf("Michael Mann"), nfo.writers)
        assertEquals("Director's Definitive Edition", nfo.edition)
        assertEquals(listOf(1158, null), nfo.actors.map { it.tmdbId })
        val older = NfoParser.parse("<movie><rating>7.1</rating><votes>1 200</votes></movie>")
        assertEquals("Kodi's older votes, beside its rating", 1200, older?.votes)
        assertNull(NfoParser.parse("<movie><votes>12</votes></movie>")?.votes)
    }
}
