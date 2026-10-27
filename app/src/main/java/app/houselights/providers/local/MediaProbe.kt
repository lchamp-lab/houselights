package app.houselights.providers.local

import android.util.Log
import app.houselights.core.MediaInfo
import app.houselights.core.bpsToKbps
import app.houselights.core.lenientJson
import app.houselights.core.suspendRunCatching

sealed interface ProbeResult {
    val info: MediaInfo

    /** Fully probed, unparsable, or denied by the NAS: kept until the probe version or the file changes. */
    data class Read(override val info: MediaInfo) : ProbeResult

    /** A read failed midway (lock, timeout, rename): [info] has [MediaInfo.probedWith] 0, so the next scan retries. */
    data class Failed(override val info: MediaInfo, val cause: Throwable) : ProbeResult
}

/** Reads a video file's stream details from its first megabytes. */
object MediaProbe {
    private const val TAG = "MediaProbe"
    /** Track lists usually sit in the first 100 KB; a later list is read in a second pass. */
    private const val FIRST_READ = 256 shl 10
    private const val MAX_READ = 16 shl 20

    /** Read sizes for first frames: a 4K video's first frame alone can take 300 KB to 1 MB before the audio's. */
    private val FRAME_STEPS = listOf(1 shl 20, 4 shl 20)

    /** Read size for the Tags element (a few kilobytes with mkvmerge), located by the SeekHead. */
    private const val TAGS_READ = 64 shl 10
    private val MATROSKA = setOf("mkv", "mk3d", "webm")

    /**
     * Failures that would recur, so the result is kept. Not a file missing since listing: a Radarr or Sonarr rename
     * keeps its row and size, and would never be probed under its new name.
     */
    private val FINAL = setOf(ScanErrorKind.ACCESS_DENIED)

    /** Reads per file: first, Tracks via the SeekHead, rest of a long track list, and each of [FRAME_STEPS]. */
    private const val MAX_READS = 5

    /**
     * Probe version stored with each result; files probed by an older version get a headers-only re-probe at the
     * next scan. 1: frame rate. 2: formats from first frames (Atmos, DTS layers, HDR10+), audio bitrates from
     * tags. 3: video bitrate, Dolby Vision base layer. 4: AVI-muxed tracks by FOURCC and format tag (XviD and DivX
     * are MPEG-4, not VC-1). [MediaInfo.tracksInOrder] takes none: the migration to schema 16 marks what 4 read.
     */
    const val VERSION = 4

    fun isStale(mediaInfo: String?): Boolean = (decode(mediaInfo)?.probedWith ?: -1) < VERSION

    fun encode(info: MediaInfo): String = lenientJson.encodeToString(info)

    fun decode(text: String?): MediaInfo? = text?.let { runCatching { lenientJson.decodeFromString<MediaInfo>(it) }.getOrNull() }

    /** Unparsable or denied files still get container and size; transient failures give [ProbeResult.Failed]. */
    suspend fun probe(source: SourceEntity, file: RemoteFile, browser: RemoteBrowser): ProbeResult {
        val ext = FilenameParser.extension(file.relativePath)
        val basic = MediaInfo(container = ext.uppercase().ifBlank { null }, sizeBytes = file.size.takeIf { it > 0 }, probedWith = VERSION)
        if (ext !in MATROSKA) return ProbeResult.Read(basic)
        val reads = Reads(browser, source, file)
        val info = suspendRunCatching {
            val result = readTracks(reads) ?: return@suspendRunCatching basic
            val bitrates = readBitrates(reads, result.tagsAt)
            fun kbps(uid: Long?) = uid?.let(bitrates::get)?.let(::bpsToKbps)
            val audio = result.info.audio.mapIndexed { i, track -> track.copy(bitrateKbps = kbps(result.audioUids.getOrNull(i))) }
            val video = result.info.video?.let { it.copy(bitrateKbps = kbps(result.videoUid)) }
            result.info.copy(video = video, audio = audio, sizeBytes = basic.sizeBytes, probedWith = VERSION, tracksInOrder = true)
        }.getOrElse { e ->
            // Reads records read failures; what remains is an unreachable NAS or unparsable bytes.
            if (ScanErrors.isUnreachable(e)) throw e
            Log.w(TAG, "Could not read the inside of ${file.relativePath}", e)
            basic
        }
        val failure = reads.failure ?: return ProbeResult.Read(info)
        Log.w(TAG, "Could not read ${file.relativePath}; it is read again at the next scan", failure)
        return ProbeResult.Failed(info.copy(probedWith = 0), failure)
    }

    /**
     * Reads of one file: an unreachable NAS throws, other failures return null. A transient one is kept as [failure]
     * for a retry; access denied is final until the file changes.
     */
    private class Reads(private val browser: RemoteBrowser, private val source: SourceEntity, val file: RemoteFile) {
        var failure: Throwable? = null
            private set

        suspend fun at(offset: Long, length: Int): ByteArray? =
            suspendRunCatching { browser.readAt(source, file.relativePath, offset, length) }.getOrElse { e ->
                if (ScanErrors.isUnreachable(e)) throw e
                if (ScanErrors.classify(e).kind in FINAL) {
                    Log.w(TAG, "${file.relativePath} cannot be read; kept as it is until it changes", e)
                } else {
                    failure = failure ?: e
                }
                null
            }
    }

    /**
     * Reads Matroska tracks from the start, extending the buffer while the track list or first frames lie beyond it.
     * Null when not Matroska or too far; a failed read keeps the tracks found so far.
     */
    private suspend fun readTracks(reads: Reads): MkvProbe.Result.Found? {
        var bytes = reads.at(0, FIRST_READ) ?: return null
        var atEnd = bytes.size < FIRST_READ
        var found: MkvProbe.Result.Found? = null
        var read = 1
        while (true) {
            val result = MkvProbe.probe(bytes)
            if (result is MkvProbe.Result.Found) found = result
            val wanted = further(result, bytes.size)?.takeIf { it > bytes.size && !atEnd && read < MAX_READS } ?: return found
            val more = reads.at(bytes.size.toLong(), wanted - bytes.size) ?: return found
            read++
            atEnd = more.size < wanted - bytes.size
            bytes += more
        }
    }

    /** Total bytes to read next given [result] on the first [read] bytes; null to stop. */
    private fun further(result: MkvProbe.Result, read: Int): Int? = when (result) {
        is MkvProbe.Result.Found -> FRAME_STEPS.firstOrNull { it > read }.takeIf { !result.framesRead }
        is MkvProbe.Result.NeedMore -> result.upTo.takeIf { it in 0..(MAX_READ - FIRST_READ) }?.let { (it + FIRST_READ).toInt() }
        MkvProbe.Result.NotMatroska -> null
    }

    private suspend fun readBitrates(reads: Reads, tagsAt: Long?): Map<Long, Long> {
        val at = tagsAt?.takeIf { it in 0 until reads.file.size } ?: return emptyMap()
        val bytes = reads.at(at, TAGS_READ) ?: return emptyMap()
        return runCatching { MkvProbe.bitrates(bytes) }.getOrElse { emptyMap() }
    }
}
