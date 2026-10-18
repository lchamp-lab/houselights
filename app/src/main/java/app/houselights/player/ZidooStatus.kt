package app.houselights.player

import app.houselights.core.Http
import app.houselights.core.HttpStatus
import app.houselights.core.lenientJson
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

    /** True when the control API answers, i.e. the app runs on a Zidoo. */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        suspendRunCatching {
            http.newCall(Request.Builder().url("$BASE/ZidooControlCenter/getModel").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
