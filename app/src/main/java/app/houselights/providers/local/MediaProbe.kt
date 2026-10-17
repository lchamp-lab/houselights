package app.houselights.providers.local

import android.util.Log
import app.houselights.core.MediaInfo
import app.houselights.core.lenientJson
import app.houselights.core.suspendRunCatching
import kotlinx.serialization.encodeToString

/** What a probe made of a file. */
sealed interface ProbeResult {
    val info: MediaInfo

    /**
     * The file read as far as the probe reads, or one it cannot read the inside of, or one the NAS
     * refuses: kept until the probe or the file changes.
     */
    data class Read(override val info: MediaInfo) : ProbeResult

    /**
     * A read failed on the way (a lock, a timeout, the file renamed since it was listed): [info] holds
     * what was read before it, and says no probe read it ([MediaInfo.probedWith] 0), so the next scan
     * reads the file again.
     */
    data class Failed(override val info: MediaInfo, val cause: Throwable) : ProbeResult
}

/** Reads what is inside a video file from its first megabytes. */
object MediaProbe {
    private const val TAG = "MediaProbe"
    /** Track lists usually sit in the first 100 KB; a later list is read in a second pass. */
    private const val FIRST_READ = 256 shl 10
    private const val MAX_READ = 16 shl 20

    /**
     * How far a file is read for the first frames of its tracks, when they are not in the first read: a
     * 4K video's first frame alone may fill it (300 KB to 1 MB), the audio's following. The first
     * megabyte, then further only when a track still has none.
     */
    private val FRAME_STEPS = listOf(1 shl 20, 4 shl 20)

    /** A file's tags (a few kilobytes with mkvmerge), read where its SeekHead says they lie. */
    private const val TAGS_READ = 64 shl 10
    private const val BITS_PER_KILOBIT = 1000L
    private val MATROSKA = setOf("mkv", "mk3d", "webm")

    /**
     * Read failures a file says no better at the next scan: the NAS refuses it. Not a file gone since it
     * was listed: a file gone for good is not listed again, and one renamed (Radarr, Sonarr) takes its
     * row along, of the same size, so kept as read it would never be read under its new name.
     */
    private val FINAL = setOf(ScanErrorKind.ACCESS_DENIED)

    /**
     * Reads of a file's start at most: the first, the tracks its SeekHead puts further in, the end of
     * a track list longer than that read, the first frames further in, at each of [FRAME_STEPS].
     */
    private const val MAX_READS = 5

    /**
     * The version of what a probe reads, kept with each reading: a file read by an older probe is
     * read again at the next scan, its headers only. 1: the frame rate. 2: formats read from the
     * tracks' first frames (Atmos, DTS layers, HDR10+), audio bitrates from the file's tags. 3: the
     * video's bitrate, what a Dolby Vision base layer plays as. 4: tracks muxed from AVI named by their
     * FOURCC and format tag (XviD and DivX are MPEG-4, not VC-1).
     */
    const val VERSION = 4

    /** A file not read yet, or read by an older probe. */
    fun isStale(mediaInfo: String?): Boolean = (decode(mediaInfo)?.probedWith ?: -1) < VERSION

    fun encode(info: MediaInfo): String = lenientJson.encodeToString(info)

    fun decode(text: String?): MediaInfo? = text?.let { runCatching { lenientJson.decodeFromString<MediaInfo>(it) }.getOrNull() }

    /**
     * A file whose inside cannot be read still gets its container and size, for good, and so does one
     * the NAS refuses. A read that fails for a while is not the file's fault: what was read before it
     * is [ProbeResult.Failed], read again at the next scan. A NAS that stops answering throws.
     */
    suspend fun probe(source: SourceEntity, file: RemoteFile, browser: RemoteBrowser): ProbeResult {
        val ext = FilenameParser.extension(file.relativePath)
        val basic = MediaInfo(container = ext.uppercase().ifBlank { null }, sizeBytes = file.size.takeIf { it > 0 }, probedWith = VERSION)
        if (ext !in MATROSKA) return ProbeResult.Read(basic)
        val reads = Reads(browser, source, file)
        val info = suspendRunCatching {
            val result = readTracks(reads) ?: return@suspendRunCatching basic
            val bitrates = readBitrates(reads, result.tagsAt)
            fun kbps(uid: Long?) = uid?.let(bitrates::get)?.let { ((it + BITS_PER_KILOBIT / 2) / BITS_PER_KILOBIT).toInt() }
            val audio = result.info.audio.mapIndexed { i, track -> track.copy(bitrateKbps = kbps(result.audioUids.getOrNull(i))) }
            val video = result.info.video?.let { it.copy(bitrateKbps = kbps(result.videoUid)) }
            result.info.copy(video = video, audio = audio, sizeBytes = basic.sizeBytes, probedWith = VERSION)
        }.getOrElse { e ->
            // Reads keep their own failures: what is left is the NAS out of reach, or bytes that
            // cannot be made sense of, which the file says no better at the next scan.
            if (ScanErrors.isUnreachable(e)) throw e
            Log.w(TAG, "Could not read the inside of ${file.relativePath}", e)
            basic
        }
        val failure = reads.failure ?: return ProbeResult.Read(info)
        Log.w(TAG, "Could not read ${file.relativePath}; it is read again at the next scan", failure)
        return ProbeResult.Failed(info.copy(probedWith = 0), failure)
    }

    /**
     * The reads of one file. A NAS that stops answering throws; any other failure gives nothing, and
     * the probe goes on with what it has. A passing one (a lock, a timeout) is kept as the [failure], so
     * the file is read again at the next scan; a refusal is the NAS's last word until the file changes:
     * read again, it would fail the same way at every pass.
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
     * A Matroska file's tracks, from its start: read further when its track list reaches past the
     * first read, then when its tracks' first frames do (a step of [FRAME_STEPS] at a time, until
     * every track has some). Each read goes on from where the bytes read so far end, none twice. Null
     * when it is not Matroska, or its tracks lie too far in. A read that fails keeps the tracks found
     * before it.
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

    /** How far into a file to read after what [result] made of its first [read] bytes; null when no further. */
    private fun further(result: MkvProbe.Result, read: Int): Int? = when (result) {
        // Some first frames lie past what was read: on to the next step.
        is MkvProbe.Result.Found -> FRAME_STEPS.firstOrNull { it > read }.takeIf { !result.framesRead }
        is MkvProbe.Result.NeedMore -> result.upTo.takeIf { it in 0..(MAX_READ - FIRST_READ) }?.let { (it + FIRST_READ).toInt() }
        MkvProbe.Result.NotMatroska -> null
    }

    /** The bitrate of each track by UID, from the tags at [tagsAt]; tags that cannot be read leave none. */
    private suspend fun readBitrates(reads: Reads, tagsAt: Long?): Map<Long, Long> {
        val at = tagsAt?.takeIf { it in 0 until reads.file.size } ?: return emptyMap()
        val bytes = reads.at(at, TAGS_READ) ?: return emptyMap()
        return runCatching { MkvProbe.bitrates(bytes) }.getOrElse { emptyMap() }
    }
}
