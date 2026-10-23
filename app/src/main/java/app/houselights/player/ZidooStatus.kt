package app.houselights.player

import app.houselights.core.Http
import app.houselights.core.HttpStatus
import app.houselights.core.lenientJson
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Native player state, read from the Zidoo local control API (port 9529). */
data class ZidooPlayState(
    val playing: Boolean,
    val paused: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** The file playing, as the player names it (a URI or a path on its own mounts); null when not reported. */
    val path: String? = null,
)

/** The local API's answer to "what is playing?". */
sealed interface PlayerAnswer {
    /** A file plays, or is paused. */
    data class Playing(val state: ZidooPlayState) : PlayerAnswer

    /** The player explicitly reports that nothing plays (status 804 or 806). */
    data object NothingPlays : PlayerAnswer

    /** No answer, or a nonsensical one: a briefly unreachable API says nothing about the film. */
    data object NoAnswer : PlayerAnswer
}

/** A track as the player lists it while it plays; [index] is the number it answers to ([ZidooStatus.select]). */
data class PlayerTrack(val index: Int, val language: String?, val forced: Boolean = false, val hearingImpaired: Boolean = false)

/**
 * The tracks the player lists and the ones it plays; its subtitles start with "none", numbered 0. A list it did not
 * give has no index ([audioIndex], [subtitleIndex]) and no tracks.
 */
data class PlayerTracks(val audio: List<PlayerTrack>, val audioIndex: Int?, val subtitles: List<PlayerTrack>, val subtitleIndex: Int?) {
    val complete: Boolean get() = audioIndex != null && subtitleIndex != null

    /** These lists, each replaced by [newer]'s where it gave it. */
    fun with(newer: PlayerTracks?) = PlayerTracks(
        audio = if (newer?.audioIndex != null) newer.audio else audio,
        audioIndex = newer?.audioIndex ?: audioIndex,
        subtitles = if (newer?.subtitleIndex != null) newer.subtitles else subtitles,
        subtitleIndex = newer?.subtitleIndex ?: subtitleIndex,
    )
}

enum class TrackKind { AUDIO, SUBTITLE }

object ZidooStatus {
    private const val BASE = "http://127.0.0.1:9529"

    private val http = Http.client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /** What the player plays now; [PlayerAnswer.NoAnswer] when its API is out of reach. */
    suspend fun playStatus(): PlayerAnswer = withContext(Dispatchers.IO) {
        suspendRunCatching {
            http.newCall(Request.Builder().url("$BASE/ZidooVideoPlay/getPlayStatus").build())
                .execute().use { it.body?.string() }?.let(::parse)
        }.getOrNull() ?: PlayerAnswer.NoAnswer
    }

    /** Parses a getPlayStatus answer: a file playing, nothing playing (status 804/806), or no usable answer. */
    internal fun parse(body: String): PlayerAnswer = runCatching {
        val root = lenientJson.parseToJsonElement(body).jsonObject
        val code = root["status"]?.jsonPrimitive?.intOrNull
        if (code in NOTHING_PLAYS) return@runCatching PlayerAnswer.NothingPlays
        val video = root["video"]?.jsonObject?.takeIf { code == HttpStatus.OK }
        val duration = video?.get("duration")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@runCatching PlayerAnswer.NoAnswer
        // Not formally documented: 1 = playing, 0 = paused, -1 = error.
        val status = video["status"]?.jsonPrimitive?.intOrNull ?: -1
        PlayerAnswer.Playing(
            ZidooPlayState(
                playing = status == 1,
                paused = status == 0,
                positionMs = video["currentPosition"]?.jsonPrimitive?.longOrNull ?: 0L,
                durationMs = duration,
                // Also undocumented: a path that is not a string is ignored, and the status still stands.
                path = (video["path"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null },
            )
        )
    }.getOrDefault(PlayerAnswer.NoAnswer)

    /** Statuses meaning nothing plays: no video open (804), the player not running (806). */
    private val NOTHING_PLAYS = setOf(804, 806)

    /** The tracks of the file playing, each list asked on its own; null when it gives neither (nothing plays, API out of reach). */
    suspend fun tracks(): PlayerTracks? = withContext(Dispatchers.IO) {
        lists(suspendRunCatching { get("getAudioList") }.getOrNull(), suspendRunCatching { get("getSubtitleList") }.getOrNull())
    }

    /** The lists in getAudioList's and getSubtitleList's answers, each missing when unusable; null when both are. */
    internal fun lists(audio: String?, subtitles: String?): PlayerTracks? {
        val sound = audio?.let(::parseTracks)
        val text = subtitles?.let(::parseTracks)
        if (sound == null && text == null) return null
        return PlayerTracks(sound?.second.orEmpty(), sound?.first, text?.second.orEmpty(), text?.first)
    }

    /** Asks the player to play its track numbered [index]; true once it has accepted. */
    suspend fun select(kind: TrackKind, index: Int): Boolean = withContext(Dispatchers.IO) {
        val call = if (kind == TrackKind.AUDIO) "setAudio" else "setSubtitle"
        suspendRunCatching { get("$call?index=$index")?.let(::accepted) }.getOrNull() ?: false
    }

    private fun get(call: String): String? =
        http.newCall(Request.Builder().url("$BASE/ZidooVideoPlay/$call").build()).execute().use { it.body?.string() }

    internal fun accepted(body: String): Boolean =
        runCatching { lenientJson.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.intOrNull == HttpStatus.OK }
            .getOrDefault(false)

    /**
     * Parses a getAudioList or getSubtitleList answer: the index playing and the tracks, null if unusable. Both list
     * under "subtitles"; whether a subtitle is forced or for the hearing impaired shows only in its title. Until the
     * player has read the file, it answers each list empty, numbered 0: not given yet (its subtitles always list "none").
     */
    internal fun parseTracks(body: String): Pair<Int, List<PlayerTrack>>? = runCatching {
        val root = lenientJson.parseToJsonElement(body).jsonObject
        if (root["status"]?.jsonPrimitive?.intOrNull != HttpStatus.OK) return@runCatching null
        val current = root["currentIndex"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
        val tracks = root["subtitles"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.let(::track) }
        tracks.takeIf { it.isNotEmpty() }?.let { current to it }
    }.getOrNull()

    private fun track(entry: JsonObject): PlayerTrack? {
        val index = entry["index"]?.jsonPrimitive?.intOrNull ?: return null
        val title = (entry["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        // The API's own spelling.
        val language = (entry["laungaue"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
        return PlayerTrack(index, language, forced = FORCED.containsMatchIn(title), hearingImpaired = SDH.containsMatchIn(title))
    }

    private val FORCED = Regex("""\bforced\b""", RegexOption.IGNORE_CASE)
    private val SDH = Regex("""\bSDH\b""")

    /** True when the control API answers, i.e. the app runs on a Zidoo. */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        suspendRunCatching {
            http.newCall(Request.Builder().url("$BASE/ZidooControlCenter/getModel").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** The player's firmware ("6.4.68"), as its control API gives it; null when it does not answer or say. */
    suspend fun firmware(): String? = withContext(Dispatchers.IO) {
        suspendRunCatching {
            http.newCall(Request.Builder().url("$BASE/ZidooControlCenter/getModel").build())
                .execute().use { it.body?.string() }
        }.getOrNull()?.let(::firmware)
    }

    /** The firmware a getModel answer names, without its "v". */
    internal fun firmware(body: String): String? = runCatching {
        (lenientJson.parseToJsonElement(body).jsonObject["firmware"] as? JsonPrimitive)?.contentOrNull
            ?.trim()?.removePrefix("v")?.ifBlank { null }
    }.getOrNull()
}
