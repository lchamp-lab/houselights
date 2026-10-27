package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading an NFO as Kodi, tinyMediaManager and others write it: fields, kinds, encodings and ratings. */
class NfoParserTest {
    private val movie = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
        <movie>
            <title>Bugonia</title>
            <originaltitle>Bugonia</originaltitle>
            <ratings>
                <rating name="imdb" max="10"><value>7.1</value></rating>
                <rating name="themoviedb" max="10" default="true"><value>7.3</value></rating>
            </ratings>
            <year>2025</year>
            <plot>Deux jeunes hommes obsédés par les théories du complot…</plot>
            <tagline>Est-elle humaine ou extraterrestre ?</tagline>
            <runtime>119</runtime>
            <thumb aspect="poster">https://image.tmdb.org/t/p/original/poster.jpg</thumb>
            <thumb aspect="clearlogo">https://assets.fanart.tv/logo.png</thumb>
            <fanart><thumb>https://image.tmdb.org/t/p/original/fanart.jpg</thumb></fanart>
            <mpaa>FR:12</mpaa>
            <uniqueid type="imdb">tt12300742</uniqueid>
            <uniqueid type="tmdb" default="true">701387</uniqueid>
            <genre>Science-Fiction</genre>
            <genre>Thriller / Comédie</genre>
            <director>Yorgos Lanthimos</director>
            <set><name>Collection test</name></set>
            <actor><name>Emma Stone</name><role>Michelle</role><order>0</order><thumb>https://image.tmdb.org/t/p/h632/emma.jpg</thumb></actor>
            <actor><name>Jesse Plemons</name><role>Teddy</role><order>1</order></actor>
            <fileinfo><streamdetails>
                <video><codec>hevc</codec><width>3840</width><height>2160</height><hdrtype>dolbyvision</hdrtype></video>
                <audio><codec>truehd</codec><language>eng</language><channels>8</channels></audio>
                <subtitle><language>fre</language></subtitle>
            </streamdetails></fileinfo>
        </movie>
    """.trimIndent()

    @Test
    fun aKodiMoviesFieldsAreRead() {
        val nfo = NfoParser.parse(movie)!!
        assertEquals(NfoKind.MOVIE, nfo.kind)
        assertEquals("Bugonia", nfo.title)
        assertEquals(2025, nfo.year)
        assertEquals(7.3, nfo.rating!!, 0.001)
        assertEquals(701387, nfo.tmdbId)
        assertEquals("tt12300742", nfo.imdbId)
        assertEquals("12", nfo.certification)
        assertEquals(listOf("Science-Fiction", "Thriller", "Comédie"), nfo.genres)
        assertEquals(listOf("Yorgos Lanthimos"), nfo.directors)
        assertEquals("Collection test", nfo.collection)
        assertEquals(2, nfo.actors.size)
        assertEquals("Michelle", nfo.actors[0].role)
        assertEquals("https://assets.fanart.tv/logo.png", nfo.logoUrl)
        assertEquals("https://image.tmdb.org/t/p/original/fanart.jpg", nfo.fanartUrl)
        assertEquals(3840, nfo.streams!!.width)
        assertEquals(NfoAudio("truehd", "eng", 8), nfo.streams!!.audio.single())
    }

    @Test
    fun anNfoThatIsOnlyALinkGivesItsId() {
        val nfo = NfoParser.parse("https://www.themoviedb.org/movie/701387-bugonia")!!
        assertEquals(NfoKind.LINK, nfo.kind)
        assertEquals(701387, nfo.tmdbId)
        assertEquals("tt0133093", NfoParser.parse("https://www.imdb.com/title/tt0133093/")!!.imdbId)
    }

    @Test
    fun anEpisodeGivesItsSeasonAndNumber() {
        val nfo = NfoParser.parse(
            "<episodedetails><title>Pilot</title><season>1</season><episode>2</episode><aired>2022-02-18</aired></episodedetails>",
        )!!
        assertEquals(NfoKind.EPISODE, nfo.kind)
        assertEquals(1, nfo.season)
        assertEquals(2, nfo.episode)
    }

    @Test
    fun textThatIsNoNfoIsNothing() {
        assertNull(NfoParser.parse("not an nfo"))
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
                """.trimIndent(),
            ),
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

    /** Kodi writes the collection's TMDB id on its set, tinyMediaManager beside it. */
    @Test
    fun aSetGivesTheCollectionAndItsId() {
        val kodi = NfoParser.parse("<movie><title>Top Gun</title><set tmdbcolid=\"531241\"><name>Top Gun Collection</name></set></movie>")!!
        assertEquals("Top Gun Collection", kodi.collection)
        assertEquals(531241, kodi.collectionTmdbId)
        val tmm = NfoParser.parse("<movie><title>Top Gun</title><set>Top Gun</set><tmdbCollectionId>531241</tmdbCollectionId></movie>")!!
        assertEquals("Top Gun", tmm.collection)
        assertEquals(531241, tmm.collectionTmdbId)
    }

    private val amelie = "<movie><title>Le Fabuleux Destin d’Amélie Poulain</title><plot>Amélie, serveuse à Montmartre…</plot></movie>"

    private fun titleOf(bytes: ByteArray) = NfoParser.parse(NfoParser.decode(bytes))?.title

    @Test
    fun latin1NfoIsReadInItsEncoding() {
        val windows = charset("windows-1252")
        val declared = """<?xml version="1.0" encoding="ISO-8859-1"?>$amelie""".toByteArray(windows)
        assertEquals("Le Fabuleux Destin d’Amélie Poulain", titleOf(declared))
        assertEquals("Amélie, serveuse à Montmartre…", NfoParser.parse(NfoParser.decode(declared))?.plot)
        assertEquals("not UTF-8: Windows-1252", "Le Fabuleux Destin d’Amélie Poulain", titleOf(amelie.toByteArray(windows)))
        val lying = """<?xml version="1.0" encoding="UTF-8"?><movie><title>Amélie</title></movie>""".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Amélie", titleOf(lying))
    }

    @Test
    fun utf8AndUtf16AreReadAsTheySay() {
        assertEquals("Le Fabuleux Destin d’Amélie Poulain", titleOf(amelie.toByteArray()))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + amelie.toByteArray()
        assertEquals("<movie>", NfoParser.decode(bom).take(7))
        assertEquals("Le Fabuleux Destin d’Amélie Poulain", titleOf("$amelie".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Le Fabuleux Destin d’Amélie Poulain", titleOf(amelie.toByteArray(Charsets.UTF_16LE)))
        assertEquals("Le Fabuleux Destin d’Amélie Poulain", titleOf(amelie.toByteArray(Charsets.UTF_16BE)))
    }

    /** .NET writes "utf-16" in XML it saves as UTF-8 or Windows Latin-1: valid UTF-8 bytes are read as UTF-8. */
    @Test
    fun aDeclarationTheBytesBelieIsNotFollowed() {
        val title = "<movie><title>Amélie</title></movie>"
        val net = """<?xml version="1.0" encoding="utf-16"?>$title"""
        assertEquals("Amélie", titleOf(net.toByteArray()))
        assertEquals("Amélie", titleOf(net.toByteArray(charset("windows-1252"))))
        assertEquals("Amélie", titleOf("""<?xml version="1.0" encoding="ISO-8859-1"?>$title""".toByteArray()))
        // Not UTF-8, and in the encoding it declares.
        val brat = """<?xml version="1.0" encoding="windows-1251"?><movie><title>Брат</title></movie>"""
        assertEquals("Брат", titleOf(brat.toByteArray(charset("windows-1251"))))
    }

    /** An NFO longer than the scan's read limit can be cut mid-character: it still decodes as UTF-8. */
    @Test
    fun utf8CutWithinACharacterStaysUtf8() {
        val start = amelie.removeSuffix("</movie>") + "<tagline>Ét"
        val bytes = "${start}é".toByteArray()
        assertEquals(start, NfoParser.decode(bytes.copyOf(bytes.size - 1)))
    }

    private fun rating(ratings: String) = NfoParser.parse("<movie><ratings>$ratings</ratings></movie>")?.rating

    /** Kodi and tinyMediaManager keep each site's rating on its own scale. */
    @Test
    fun ratingsAreReadOutOfTen() {
        assertEquals(7.8, rating("""<rating name="metacritic" max="100" default="true"><value>78</value></rating>""")!!, 0.001)
        assertEquals(8.2, rating("""<rating name="trakt" max="5"><value>4.1</value></rating>""")!!, 0.001)
        assertEquals(7.1, rating("""<rating name="imdb"><value>7.1</value></rating>""")!!, 0.001)
        val imdb = """<rating name="imdb" max="10"><value>7.6</value></rating>"""
        val critics = """<rating name="tomatometerallcritics" max="100" default="true"><value>89</value></rating>"""
        assertEquals(8.9, rating(imdb + critics)!!, 0.001)
    }

    /** Jellyfin's `<displayorder>`, a Kodi link to a TMDB episode group, which alone still names the show. */
    @Test
    fun aShowsNfoNamesItsOrderAsJellyfinOrKodiWritesIt() {
        assertEquals("dvd", NfoParser.parse("<tvshow><title>Firefly</title><displayorder>dvd</displayorder></tvshow>")?.displayOrder)
        assertEquals("storyarc", NfoParser.parse("<tvshow><displayorder> storyArc </displayorder></tvshow>")?.displayOrder)
        val link = NfoParser.parse("https://www.themoviedb.org/tv/1437-firefly/episode_group/5b11ba820e0a265847002c6e")
        val read = link?.let { Triple(it.kind, it.tmdbId, it.episodeGroup) }
        assertEquals(Triple(NfoKind.LINK, 1437, "5b11ba820e0a265847002c6e"), read)
        val guide = NfoParser.parse("""<tvshow><episodeguide>{"tmdb":"1437"}</episodeguide></tvshow>""")
        assertEquals("ids only: no order", null to null, guide?.displayOrder to guide?.episodeGroup)
    }

    @Test
    fun withoutADefaultImdbOrTmdbRatingIsTaken() {
        val metacritic = """<rating name="metacritic" max="100"><value>78</value></rating>"""
        val tomatoes = """<rating name="tomatometerallcritics" max="100"><value>91</value></rating>"""
        assertEquals(7.6, rating(metacritic + """<rating name="imdb" max="10"><value>7.6</value></rating>""")!!, 0.001)
        assertEquals(7.3, rating(tomatoes + """<rating name="themoviedb" max="10"><value>7.3</value></rating>""")!!, 0.001)
        val both = """<rating name="IMDb"><value>7.1</value></rating><rating name="themoviedb"><value>7.3</value></rating>"""
        assertEquals("the first of them", 7.1, rating(both)!!, 0.001)
        assertEquals("none of them: the first", 7.8, rating(metacritic + tomatoes)!!, 0.001)
    }
}
