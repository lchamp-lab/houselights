package app.houselights.player

import app.houselights.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
)

object ZidooStatus {
    private const val BASE = "http://127.0.0.1:9529"

    private val http = Http.client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** null when the API is unreachable or nothing is playing (status 804/806). */
    suspend fun playStatus(): ZidooPlayState? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$BASE/ZidooVideoPlay/getPlayStatus").build())
                .execute().use { it.body?.string() }?.let(::parse)
        }.getOrNull()
    }

    /** What an answer of getPlayStatus says; null when nothing plays (status 804/806) or it makes no sense. */
    internal fun parse(body: String): ZidooPlayState? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val video = root["video"]?.jsonObject?.takeIf { root["status"]?.jsonPrimitive?.intOrNull == HTTP_OK }
        val duration = video?.get("duration")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@runCatching null
        // Not formally documented: 1 = playing, 0 = paused, -1 = error.
        val status = video["status"]?.jsonPrimitive?.intOrNull ?: -1
        ZidooPlayState(
            playing = status == 1,
            paused = status == 0,
            positionMs = video["currentPosition"]?.jsonPrimitive?.longOrNull ?: 0L,
            durationMs = duration,
        )
    }.getOrNull()

    private const val HTTP_OK = 200

    /** True when the control API answers, i.e. we run on a Zidoo. */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$BASE/ZidooControlCenter/getModel").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
