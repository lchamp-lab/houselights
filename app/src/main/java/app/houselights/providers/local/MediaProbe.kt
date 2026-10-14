package app.houselights.providers.local

import android.util.Log
import app.houselights.core.MediaInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reads what is inside a video file from its first megabytes. */
object MediaProbe {
    private const val TAG = "MediaProbe"
    private const val FIRST_READ = 1 shl 20
    private const val MAX_READ = 16 shl 20
    private val MATROSKA = setOf("mkv", "mk3d", "webm")

    val json = Json { ignoreUnknownKeys = true }

    fun encode(info: MediaInfo): String = json.encodeToString(info)

    fun decode(text: String?): MediaInfo? = text?.let { runCatching { json.decodeFromString<MediaInfo>(it) }.getOrNull() }

    /** Never throws: an unreadable file still gets its container and size. */
    suspend fun probe(source: SourceEntity, file: RemoteFile): MediaInfo {
        val ext = FilenameParser.extension(file.relativePath)
        val basic = MediaInfo(container = ext.uppercase().ifBlank { null }, sizeBytes = file.size.takeIf { it > 0 })
        if (ext !in MATROSKA) return basic
        return runCatching {
            val browser = RemoteBrowser.of(source)
            var bytes = browser.readPrefix(source, file.relativePath, FIRST_READ)
            repeat(2) {
                when (val result = MkvProbe.probe(bytes)) {
                    is MkvProbe.Result.Found -> return result.info.copy(sizeBytes = basic.sizeBytes)
                    is MkvProbe.Result.NeedMore -> {
                        if (result.offset !in 0..(MAX_READ - FIRST_READ)) return basic
                        bytes = browser.readPrefix(source, file.relativePath, (result.offset + FIRST_READ).toInt())
                    }
                    MkvProbe.Result.NotMatroska -> return basic
                }
            }
            basic
        }.getOrElse { e ->
            Log.w(TAG, "Could not read ${file.relativePath}", e)
            basic
        }
    }
}
