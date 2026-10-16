package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun parsesKodiMovie() {
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
        assertEquals(Triple("truehd", "eng", 8), nfo.streams!!.audio.single())
    }

    @Test
    fun linkOnlyNfo() {
        val nfo = NfoParser.parse("https://www.themoviedb.org/movie/701387-bugonia")!!
        assertEquals(NfoKind.LINK, nfo.kind)
        assertEquals(701387, nfo.tmdbId)
        assertEquals("tt0133093", NfoParser.parse("https://www.imdb.com/title/tt0133093/")!!.imdbId)
    }

    @Test
    fun episode() {
        val nfo = NfoParser.parse("<episodedetails><title>Pilot</title><season>1</season><episode>2</episode><aired>2022-02-18</aired></episodedetails>")!!
        assertEquals(NfoKind.EPISODE, nfo.kind)
        assertEquals(1, nfo.season)
        assertEquals(2, nfo.episode)
    }

    @Test
    fun garbage() {
        assertNull(NfoParser.parse("not an nfo"))
    }

    private val amelie = "<movie><title>Le Fabuleux Destin d’Amélie Poulain</title><plot>Amélie, serveuse à Montmartre…</plot></movie>"

    private fun titleOf(bytes: ByteArray) = NfoParser.parse(NfoParser.decode(bytes))?.title

    /** Written by an older media manager, in the Latin-1 of Windows, and saying so or not. */
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
        assertEquals("Le Fabuleux Destin d’Amélie Poulain", titleOf("﻿$amelie".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Le Fabuleux Destin d’Amélie Poulain", titleOf(amelie.toByteArray(Charsets.UTF_16LE)))
        assertEquals("Le Fabuleux Destin d’Amélie Poulain", titleOf(amelie.toByteArray(Charsets.UTF_16BE)))
    }

    /** An NFO longer than what a scan reads ends in the middle of a character: still UTF-8. */
    @Test
    fun utf8CutWithinACharacterStaysUtf8() {
        val start = amelie.removeSuffix("</movie>") + "<tagline>Ét"
        val bytes = "${start}é".toByteArray()
        // The last character, "é", cut after its first byte.
        assertEquals(start, NfoParser.decode(bytes.copyOf(bytes.size - 1)))
    }

    /** Kodi and tinyMediaManager keep each site's rating on its own scale. */
    @Test
    fun ratingsAreReadOutOfTen() {
        fun rating(ratings: String) = NfoParser.parse("<movie><ratings>$ratings</ratings></movie>")?.rating
        assertEquals(7.8, rating("""<rating name="metacritic" max="100" default="true"><value>78</value></rating>""")!!, 0.001)
        assertEquals(8.2, rating("""<rating name="trakt" max="5"><value>4.1</value></rating>""")!!, 0.001)
        assertEquals(7.1, rating("""<rating name="imdb"><value>7.1</value></rating>""")!!, 0.001)
        val imdb = """<rating name="imdb" max="10"><value>7.6</value></rating>"""
        val critics = """<rating name="tomatometerallcritics" max="100" default="true"><value>89</value></rating>"""
        assertEquals(8.9, rating(imdb + critics)!!, 0.001)
    }
}
