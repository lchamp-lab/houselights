package app.posterwall.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
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

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** null when the API is unreachable or nothing is playing (status 804/806). */
    suspend fun playStatus(): ZidooPlayState? = withContext(Dispatchers.IO) {
        runCatching {
            val body = http.newCall(Request.Builder().url("$BASE/ZidooVideoPlay/getPlayStatus").build())
                .execute().use { it.body?.string() } ?: return@runCatching null
            val root = json.parseToJsonElement(body).jsonObject
            if (root["status"]?.jsonPrimitive?.intOrNull != 200) return@runCatching null
            val video = root["video"]?.jsonObject ?: return@runCatching null
            val duration = video["duration"]?.jsonPrimitive?.longOrNull ?: 0L
            if (duration <= 0) return@runCatching null
            // Not formally documented: 1 = playing, 0 = paused, -1 = error.
            val status = video["status"]?.jsonPrimitive?.intOrNull ?: -1
            ZidooPlayState(
                playing = status == 1,
                paused = status == 0,
                positionMs = video["currentPosition"]?.jsonPrimitive?.longOrNull ?: 0L,
                durationMs = duration,
            )
        }.getOrNull()
    }

    /** True when the control API answers, i.e. we run on a Zidoo. */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$BASE/ZidooControlCenter/getModel").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
