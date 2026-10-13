package app.houselights.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class NfoActor(val name: String, val role: String?, val thumb: String?, val order: Int?)

data class NfoStreams(
    val videoCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val hdrType: String? = null,
    val audio: List<Triple<String?, String?, Int?>> = emptyList(), // codec, language, channels
    val subtitleLanguages: List<String> = emptyList(),
)

/** What a Kodi-style NFO says about a film, a show or an episode. Blank fields are null. */
data class NfoInfo(
    val kind: String,
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

    fun parse(text: String): NfoInfo? {
        val xmlStart = text.indexOf('<')
        val xml = if (xmlStart >= 0) text.substring(xmlStart) else ""
        val parsed = runCatching { parseXml(xml) }.getOrNull()
        // Links next to (or instead of) the XML still identify the title.
        val tmdb = TMDB_URL.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val imdb = IMDB_ID.find(text)?.value
        return when {
            parsed != null -> parsed.copy(tmdbId = parsed.tmdbId ?: tmdb, imdbId = parsed.imdbId ?: imdb)
            tmdb != null || imdb != null -> NfoInfo(kind = "link", tmdbId = tmdb, imdbId = imdb)
            else -> null
        }
    }

    private fun parseXml(xml: String): NfoInfo? {
        if (xml.isBlank()) return null
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(xml))
        while (p.eventType != XmlPullParser.START_TAG) {
            if (p.next() == XmlPullParser.END_DOCUMENT) return null
        }
        val root = p.name
        if (root !in setOf("movie", "tvshow", "episodedetails", "musicvideo")) return null

        var info = NfoInfo(kind = root)
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
                "set" -> info = info.copy(collection = setName(p))
                "season" -> info = info.copy(season = p.text()?.toIntOrNull())
                "episode" -> info = info.copy(episode = p.text()?.toIntOrNull())
                "status" -> info = info.copy(status = p.text())
                "thumb" -> {
                    val aspect = p.getAttributeValue(null, "aspect")
                    val url = p.text()?.takeIf { it.startsWith("http") }
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

    /** "France:12", "FR:12" or "Rated PG-13" → "12" / "PG-13". */
    private fun certification(raw: String?): String? =
        raw?.substringAfterLast(':')?.removePrefix("Rated ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun ratings(p: XmlPullParser): Pair<Double?, Double?> {
        var default: Double? = null
        var first: Double? = null
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "rating") {
                val isDefault = p.getAttributeValue(null, "default") == "true"
                val ratingDepth = p.depth
                var value: Double? = null
                while (!(p.next() == XmlPullParser.END_TAG && p.depth == ratingDepth)) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "value") value = p.text()?.replace(',', '.')?.toDoubleOrNull()
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
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "name" -> name = p.text()
                "role" -> role = p.text()
                "thumb" -> thumb = p.text()?.takeIf { it.startsWith("http") }
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
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
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
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "thumb" && url == null) url = p.text()?.takeIf { it.startsWith("http") }
        }
        return url
    }

    private fun streams(p: XmlPullParser): NfoStreams {
        var s = NfoStreams()
        val audio = mutableListOf<Triple<String?, String?, Int?>>()
        val subs = mutableListOf<String>()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
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
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG) p.text()?.let { out[p.name] = it }
        }
        return out
    }

    /** Text of the current element, consuming its end tag; nested elements are skipped. */
    private fun XmlPullParser.text(): String? {
        val depth = depth
        val sb = StringBuilder()
        while (!(next() == XmlPullParser.END_TAG && this.depth == depth)) {
            if (eventType == XmlPullParser.TEXT) sb.append(text)
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun skip(p: XmlPullParser) {
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) Unit
    }
}
