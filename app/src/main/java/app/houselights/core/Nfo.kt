package app.houselights.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset

data class NfoActor(val name: String, val role: String?, val thumb: String?, val order: Int?)

data class NfoStreams(
    val videoCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val hdrType: String? = null,
    val audio: List<Triple<String?, String?, Int?>> = emptyList(), // codec, language, channels
    val subtitleLanguages: List<String> = emptyList(),
)

/** What an NFO describes: its root element, or only links to a title (a TMDB or IMDb address). */
enum class NfoKind(val root: String?) {
    MOVIE("movie"),
    SHOW("tvshow"),
    EPISODE("episodedetails"),
    MUSIC_VIDEO("musicvideo"),
    LINK(null),
    ;

    companion object {
        fun ofRoot(name: String): NfoKind? = entries.firstOrNull { it.root == name }
    }
}

/** What a Kodi-style NFO says about a film, a show or an episode. Blank fields are null. */
data class NfoInfo(
    val kind: NfoKind,
    val title: String? = null,
    val originalTitle: String? = null,
    val sortTitle: String? = null,
    val year: Int? = null,
    val premiered: String? = null,
    val plot: String? = null,
    val tagline: String? = null,
    val runtimeMin: Int? = null,
    val certification: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val actors: List<NfoActor> = emptyList(),
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tvdbId: String? = null,
    val collection: String? = null,
    /** TMDB id of the collection, when the NFO gives it (tinyMediaManager, Kodi). */
    val collectionTmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val status: String? = null,
    val posterUrl: String? = null,
    val fanartUrl: String? = null,
    val logoUrl: String? = null,
    val streams: NfoStreams? = null,
)

/**
 * Parses Kodi NFO files (as written by Kodi, tinyMediaManager, Jellyfin, Emby, Radarr…).
 * An NFO may also hold only a TMDB or IMDb link, which is enough to identify the title.
 */
object NfoParser {
    private val TMDB_URL = Regex("""themoviedb\.org/(?:movie|tv)/(\d+)""")
    private val IMDB_ID = Regex("""\btt\d{6,9}\b""")

    private val ENCODING = Regex("""^\s*<\?xml[^>]*?\bencoding\s*=\s*["']([A-Za-z][\w.:-]*)["']""")
    private val BOMS = listOf(
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to Charsets.UTF_8,
        byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to Charsets.UTF_16BE,
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to Charsets.UTF_16LE,
    )
    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
    private const val LT: Byte = 0x3C // "<"
    private const val NUL: Byte = 0

    /** How far into a file its XML declaration is looked for. */
    private const val DECLARATION_BYTES = 256

    /** A rating's scale when it says none. */
    private const val RATING_SCALE = 10.0

    fun parse(text: String): NfoInfo? {
        val xmlStart = text.indexOf('<')
        val xml = if (xmlStart >= 0) text.substring(xmlStart) else ""
        val parsed = runCatching { parseXml(xml) }.getOrNull()
        // Links next to (or instead of) the XML still identify the title.
        val tmdb = TMDB_URL.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val imdb = IMDB_ID.find(text)?.value
        return when {
            parsed != null -> parsed.copy(tmdbId = parsed.tmdbId ?: tmdb, imdbId = parsed.imdbId ?: imdb)
            tmdb != null || imdb != null -> NfoInfo(kind = NfoKind.LINK, tmdbId = tmdb, imdbId = imdb)
            else -> null
        }
    }

    /**
     * An NFO's text, from its bytes in the encoding they say: their byte order mark, else their XML
     * declaration, else UTF-8; or Windows-1252, the Latin-1 of older media managers, when they are
     * not UTF-8. A character cut by the end of what was read is left out.
     */
    fun decode(bytes: ByteArray): String {
        bom(bytes)?.let { (charset, length) -> return String(bytes, length, bytes.size - length, charset) }
        // UTF-16 without a mark, told by its first character, "<" (XML 1.0, appendix F).
        if (bytes.size >= 2 && bytes[0] == LT && bytes[1] == NUL) return String(bytes, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == NUL && bytes[1] == LT) return String(bytes, Charsets.UTF_16BE)
        return when (val declared = declaredCharset(bytes)) {
            null, Charsets.UTF_8, Charsets.US_ASCII -> utf8(bytes) ?: String(bytes, WINDOWS_1252)
            // What Windows writes as "ISO-8859-1" is Windows-1252, its quotes and dashes included.
            Charsets.ISO_8859_1 -> String(bytes, WINDOWS_1252)
            else -> String(bytes, declared)
        }
    }

    /** The charset a byte order mark says, and the mark's length. */
    private fun bom(bytes: ByteArray): Pair<Charset, Int>? = BOMS.firstOrNull { (mark, _) ->
        bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it] }
    }?.let { (mark, charset) -> charset to mark.size }

    /** The charset an XML declaration names, when the device has it. */
    private fun declaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, DECLARATION_BYTES), Charsets.ISO_8859_1)
        val name = ENCODING.find(head)?.groupValues?.get(1) ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    /** The bytes as UTF-8; null when they are not. */
    private fun utf8(bytes: ByteArray): String? {
        val out = CharBuffer.allocate(bytes.size)
        // Not the end of the input: a character cut by the end of what was read is left out, not malformed.
        val result = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes), out, false)
        return if (result.isError) null else String(out.array(), 0, out.position())
    }

    private fun parseXml(xml: String): NfoInfo? {
        // Entities are never needed in an NFO, and could expand or reach outside the file.
        if (xml.isBlank() || xml.contains("<!ENTITY", ignoreCase = true)) return null
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(xml))
        while (p.eventType != XmlPullParser.START_TAG) {
            if (p.next() == XmlPullParser.END_DOCUMENT) return null
        }
        val kind = NfoKind.ofRoot(p.name) ?: return null

        var info = NfoInfo(kind = kind)
        val genres = mutableListOf<String>()
        val directors = mutableListOf<String>()
        val studios = mutableListOf<String>()
        val actors = mutableListOf<NfoActor>()
        var defaultRating: Double? = null
        var firstRating: Double? = null

        while (true) {
            val event = p.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && p.depth == 1) break
            if (event != XmlPullParser.START_TAG || p.depth != 2) continue
            when (p.name) {
                "title" -> info = info.copy(title = p.text())
                "originaltitle" -> info = info.copy(originalTitle = p.text())
                "sorttitle" -> info = info.copy(sortTitle = p.text())
                "year" -> info = info.copy(year = p.text()?.toIntOrNull())
                "premiered", "aired" -> info = info.copy(premiered = p.text())
                "plot" -> info = info.copy(plot = p.text())
                "outline" -> p.text()?.let { if (info.plot == null) info = info.copy(plot = it) }
                "tagline" -> info = info.copy(tagline = p.text())
                "runtime" -> info = info.copy(runtimeMin = p.text()?.filter(Char::isDigit)?.toIntOrNull())
                "mpaa", "certification" -> info = info.copy(certification = certification(p.text()))
                "rating" -> p.text()?.replace(',', '.')?.toDoubleOrNull()?.let { firstRating = firstRating ?: it }
                "ratings" -> ratings(p).let { (default, first) -> defaultRating = default; firstRating = firstRating ?: first }
                "genre" -> p.text()?.split('/')?.map { it.trim() }?.filter { it.isNotEmpty() }?.let(genres::addAll)
                "director" -> p.text()?.let(directors::add)
                "studio" -> p.text()?.let(studios::add)
                "actor" -> actor(p)?.let(actors::add)
                "uniqueid" -> {
                    val type = p.getAttributeValue(null, "type")?.lowercase()
                    val value = p.text()
                    when (type) {
                        "tmdb" -> info = info.copy(tmdbId = value?.toIntOrNull())
                        "imdb" -> info = info.copy(imdbId = value)
                        "tvdb" -> info = info.copy(tvdbId = value)
                    }
                }
                "tmdbid" -> info = info.copy(tmdbId = p.text()?.toIntOrNull())
                "imdbid" -> info = info.copy(imdbId = p.text())
                "id" -> p.text()?.let { id -> if (id.startsWith("tt")) info = info.copy(imdbId = info.imdbId ?: id) }
                "set" -> {
                    val id = p.getAttributeValue(null, "tmdbcolid")?.toIntOrNull()
                    info = info.copy(collection = setName(p), collectionTmdbId = id ?: info.collectionTmdbId)
                }
                "tmdbcolid", "tmdbCollectionId" -> info = info.copy(collectionTmdbId = p.text()?.toIntOrNull() ?: info.collectionTmdbId)
                "season" -> info = info.copy(season = p.text()?.toIntOrNull())
                "episode" -> info = info.copy(episode = p.text()?.toIntOrNull())
                "status" -> info = info.copy(status = p.text())
                "thumb" -> {
                    val aspect = p.getAttributeValue(null, "aspect")
                    val url = p.text()?.let(::imageUrl)
                    when (aspect) {
                        "poster", null -> if (info.posterUrl == null) info = info.copy(posterUrl = url)
                        "clearlogo", "logo" -> if (info.logoUrl == null) info = info.copy(logoUrl = url)
                        "landscape", "fanart" -> if (info.fanartUrl == null) info = info.copy(fanartUrl = url)
                    }
                }
                "fanart" -> fanart(p)?.let { if (info.fanartUrl == null) info = info.copy(fanartUrl = it) }
                "fileinfo" -> info = info.copy(streams = streams(p))
                else -> skip(p)
            }
        }
        return info.copy(
            rating = defaultRating ?: firstRating,
            genres = genres.distinct(),
            directors = directors.distinct(),
            studios = studios.distinct(),
            actors = actors,
        )
    }

    /**
     * An artwork address from an NFO, fetched over HTTPS only: "http://" is upgraded, so an NFO can't
     * make the app call plain-HTTP servers on the home network. Anything else is ignored.
     */
    fun imageUrl(raw: String): String? = when {
        raw.startsWith("https://") -> raw
        raw.startsWith("http://") -> "https://" + raw.removePrefix("http://")
        else -> null
    }

    /** Moves to the next event inside the element opened at [depth]; false at its end, or the document's. */
    private fun XmlPullParser.nextWithin(depth: Int): Boolean {
        val event = next()
        return event != XmlPullParser.END_DOCUMENT && !(event == XmlPullParser.END_TAG && this.depth == depth)
    }

    /** "France:12", "FR:12" or "Rated PG-13" → "12" / "PG-13". */
    private fun certification(raw: String?): String? =
        raw?.substringAfterLast(':')?.removePrefix("Rated ")?.trim()?.takeIf { it.isNotEmpty() }

    /** The default rating and the first, both out of 10: a Metacritic 78 out of 100 is a 7.8. */
    private fun ratings(p: XmlPullParser): Pair<Double?, Double?> {
        var default: Double? = null
        var first: Double? = null
        val depth = p.depth
        while (p.nextWithin(depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "rating") {
                val isDefault = p.getAttributeValue(null, "default") == "true"
                val max = p.getAttributeValue(null, "max")?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 } ?: RATING_SCALE
                val ratingDepth = p.depth
                var value: Double? = null
                while (p.nextWithin(ratingDepth)) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "value") {
                        value = p.text()?.replace(',', '.')?.toDoubleOrNull()?.let { it * RATING_SCALE / max }
                    }
                }
                if (isDefault) default = value
                if (first == null) first = value
            }
        }
        return default to first
    }

    private fun actor(p: XmlPullParser): NfoActor? {
        var name: String? = null
        var role: String? = null
        var thumb: String? = null
        var order: Int? = null
        val depth = p.depth
        while (p.nextWithin(depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "name" -> name = p.text()
                "role" -> role = p.text()
                "thumb" -> thumb = p.text()?.let(::imageUrl)
                "order" -> order = p.text()?.toIntOrNull()
                else -> skip(p)
            }
        }
        return name?.let { NfoActor(it, role, thumb, order) }
    }

    private fun setName(p: XmlPullParser): String? {
        // Either <set>Name</set> or <set><name>Name</name>…</set>.
        val depth = p.depth
        var name: String? = null
        val text = StringBuilder()
        while (p.nextWithin(depth)) {
            when (p.eventType) {
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.START_TAG -> if (p.name == "name") name = p.text() else skip(p)
            }
        }
        return name ?: text.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun fanart(p: XmlPullParser): String? {
        val depth = p.depth
        var url: String? = null
        while (p.nextWithin(depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "thumb" && url == null) url = p.text()?.let(::imageUrl)
        }
        return url
    }

    private fun streams(p: XmlPullParser): NfoStreams {
        var s = NfoStreams()
        val audio = mutableListOf<Triple<String?, String?, Int?>>()
        val subs = mutableListOf<String>()
        val depth = p.depth
        while (p.nextWithin(depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "video" -> fields(p).let { f ->
                    s = s.copy(
                        videoCodec = f["codec"],
                        width = f["width"]?.toIntOrNull(),
                        height = f["height"]?.toIntOrNull(),
                        hdrType = f["hdrtype"],
                    )
                }
                "audio" -> fields(p).let { f -> audio += Triple(f["codec"], f["language"], f["channels"]?.toIntOrNull()) }
                "subtitle" -> fields(p)["language"]?.let(subs::add)
            }
        }
        return s.copy(audio = audio, subtitleLanguages = subs)
    }

    private fun fields(p: XmlPullParser): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val depth = p.depth
        while (p.nextWithin(depth)) {
            if (p.eventType == XmlPullParser.START_TAG) p.text()?.let { out[p.name] = it }
        }
        return out
    }

    /** Text of the current element, consuming its end tag; nested elements are skipped. */
    private fun XmlPullParser.text(): String? {
        val depth = depth
        val sb = StringBuilder()
        while (nextWithin(depth)) {
            if (eventType == XmlPullParser.TEXT) sb.append(text)
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun skip(p: XmlPullParser) {
        val depth = p.depth
        while (p.nextWithin(depth)) Unit
    }
}
