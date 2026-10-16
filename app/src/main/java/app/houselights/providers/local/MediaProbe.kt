package app.houselights.providers.local

import android.util.Log
import app.houselights.core.MediaInfo
import app.houselights.core.suspendRunCatching
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reads what is inside a video file from its first megabytes. */
object MediaProbe {
    private const val TAG = "MediaProbe"
    /** Track lists usually sit in the first 100 KB; a later list is read in a second pass. */
    private const val FIRST_READ = 256 shl 10
    private const val MAX_READ = 16 shl 20

    /** As far as a file is read for the first frames of its tracks, when they are not in the first read. */
    private const val FRAMES_READ = 4 shl 20

    /** A file's tags (a few kilobytes with mkvmerge), read where its SeekHead says they lie. */
    private const val TAGS_READ = 64 shl 10
    private const val BITS_PER_KILOBIT = 1000L
    private val MATROSKA = setOf("mkv", "mk3d", "webm")

    /** Reads of a file's start at most: the first, the tracks further in, the first frames further in. */
    private const val MAX_ATTEMPTS = 3

    val json = Json { ignoreUnknownKeys = true }

    /**
     * The version of what a probe reads, kept with each reading: a file read by an older probe is
     * read again at the next scan, its headers only. 1: the frame rate. 2: formats read from the
     * tracks' first frames (Atmos, DTS layers, HDR10+), audio bitrates from the file's tags. 3: the
     * video's bitrate, what a Dolby Vision base layer plays as.
     */
    const val VERSION = 3

    /** A file not read yet, or read by an older probe. */
    fun isStale(mediaInfo: String?): Boolean = (decode(mediaInfo)?.probedWith ?: -1) < VERSION

    fun encode(info: MediaInfo): String = json.encodeToString(info)

    fun decode(text: String?): MediaInfo? = text?.let { runCatching { json.decodeFromString<MediaInfo>(it) }.getOrNull() }

    /**
     * An unreadable file still gets its container and size. Throws only when the NAS can't be reached,
     * so that a passing failure is not kept as the file's description.
     */
    suspend fun probe(source: SourceEntity, file: RemoteFile, browser: RemoteBrowser): MediaInfo {
        val ext = FilenameParser.extension(file.relativePath)
        val basic = MediaInfo(container = ext.uppercase().ifBlank { null }, sizeBytes = file.size.takeIf { it > 0 }, probedWith = VERSION)
        if (ext !in MATROSKA) return basic
        return suspendRunCatching {
            val result = readTracks(browser, source, file) ?: return basic
            val bitrates = readBitrates(browser, source, file, result.tagsAt)
            fun kbps(uid: Long?) = uid?.let(bitrates::get)?.let { ((it + BITS_PER_KILOBIT / 2) / BITS_PER_KILOBIT).toInt() }
            val audio = result.info.audio.mapIndexed { i, track -> track.copy(bitrateKbps = kbps(result.audioUids.getOrNull(i))) }
            val video = result.info.video?.let { it.copy(bitrateKbps = kbps(result.videoUid)) }
            result.info.copy(video = video, audio = audio, sizeBytes = basic.sizeBytes, probedWith = VERSION)
        }.getOrElse { e ->
            // A NAS that stops answering throws: the file is probed again at the next scan.
            if (ScanErrors.isUnreachable(e)) throw e
            Log.w(TAG, "Could not read ${file.relativePath}", e)
            basic
        }
    }

    /**
     * A Matroska file's tracks, from its start: read further when its track list lies past the
     * first read, then when its tracks' first frames do (as far as [FRAMES_READ]). Null when it is
     * not Matroska, or its tracks lie too far in.
     */
    private suspend fun readTracks(browser: RemoteBrowser, source: SourceEntity, file: RemoteFile): MkvProbe.Result.Found? {
        var found: MkvProbe.Result.Found? = null
        var length: Int? = FIRST_READ
        var reads = 0
        while (length != null && reads < MAX_ATTEMPTS) {
            val bytes = browser.readPrefix(source, file.relativePath, length)
            reads++
            val read = length
            length = when (val result = MkvProbe.probe(bytes)) {
                is MkvProbe.Result.Found -> {
                    found = result
                    // Some first frames lie past this read: read further, once.
                    FRAMES_READ.takeIf { !result.framesRead && read < FRAMES_READ && bytes.size == read }
                }
                is MkvProbe.Result.NeedMore -> result.offset.takeIf { it in 0..(MAX_READ - FIRST_READ) }?.let { (it + FIRST_READ).toInt() }
                MkvProbe.Result.NotMatroska -> null
            }
        }
        return found
    }

    /** The bitrate of each track by UID, from the tags at [tagsAt]; tags that cannot be read leave none. */
    private suspend fun readBitrates(browser: RemoteBrowser, source: SourceEntity, file: RemoteFile, tagsAt: Long?): Map<Long, Long> {
        val at = tagsAt?.takeIf { it in 0 until file.size } ?: return emptyMap()
        return suspendRunCatching { MkvProbe.bitrates(browser.readAt(source, file.relativePath, at, TAGS_READ)) }
            .getOrElse { e -> if (ScanErrors.isUnreachable(e)) throw e else emptyMap() }
    }
}
