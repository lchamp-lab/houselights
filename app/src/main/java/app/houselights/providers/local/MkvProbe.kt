package app.houselights.providers.local

import app.houselights.core.AudioTrack
import app.houselights.core.HdrFormat
import app.houselights.core.MediaInfo
import app.houselights.core.SubtitleTrack
import app.houselights.core.VideoInfo

/**
 * Reads the track list of a Matroska (MKV, WebM) file from its first bytes: codecs, frame
 * size, HDR and Dolby Vision signalling, audio channels, languages and subtitles.
 * Only the headers are parsed; nothing is decoded.
 */
object MkvProbe {
    sealed interface Result {
        data class Found(val info: MediaInfo) : Result
        /** Tracks start beyond the bytes read: read up to [offset] + some, and try again. */
        data class NeedMore(val offset: Long) : Result
        data object NotMatroska : Result
    }

    // Element IDs (with their length marker bits, as written in the file).
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val INFO = 0x1549A966L
    private const val TIMESTAMP_SCALE = 0x2AD7B1L
    private const val DURATION = 0x4489L
    private const val TRACKS = 0x1654AE6BL
    private const val CLUSTER = 0x1F43B675L
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_TYPE = 0x83L
    private const val FLAG_DEFAULT = 0x88L
    private const val FLAG_FORCED = 0x55AAL
    private const val FLAG_HEARING_IMPAIRED = 0x55ABL
    private const val NAME = 0x536EL
    private const val LANGUAGE = 0x22B59CL
    private const val LANGUAGE_BCP47 = 0x22B59DL
    private const val CODEC_ID = 0x86L
    private const val CODEC_PRIVATE = 0x63A2L
    private const val VIDEO = 0xE0L
    private const val PIXEL_WIDTH = 0xB0L
    private const val PIXEL_HEIGHT = 0xBAL
    private const val COLOUR = 0x55B0L
    private const val BITS_PER_CHANNEL = 0x55B2L
    private const val TRANSFER = 0x55BAL
    private const val AUDIO = 0xE1L
    private const val CHANNELS = 0x9FL
    private const val BLOCK_ADDITION_MAPPING = 0x41E4L
    private const val BLOCK_ADD_ID_TYPE = 0x41E7L
    private const val BLOCK_ADD_ID_EXTRA = 0x41EDL

    private val DOLBY_VISION_CONFIGS = setOf(0x64766343L /* dvcC */, 0x64767643L /* dvvC */, 0x64767743L /* dvwC */)

    fun probe(bytes: ByteArray): Result {
        val r = Reader(bytes)
        if (r.remaining < 4 || r.peekId() != EBML) return Result.NotMatroska
        r.skipElement()
        if (r.remaining < 4 || r.readId() != SEGMENT) return Result.NotMatroska
        r.readSize() // Usually "unknown": read children until the data runs out.
        val segmentStart = r.pos

        var tracksOffset: Long? = null
        var timestampScale = 1_000_000L
        var durationTicks: Double? = null
        var tracks: Tracks? = null

        while (r.remaining > 0) {
            val start = r.pos
            val id = runCatching { r.readId() }.getOrNull() ?: break
            val size = runCatching { r.readSize() }.getOrNull() ?: break
            if (id == CLUSTER) break
            val end = r.pos + (size ?: break)
            if (end > bytes.size) {
                // Element cut by the end of what was read: worth reading more only for Tracks.
                if (id == TRACKS) return Result.NeedMore(start.toLong())
                break
            }
            when (id) {
                SEEK_HEAD -> tracksOffset = seekTracks(Reader(bytes, r.pos, end.toInt()))?.let { segmentStart + it }
                INFO -> Reader(bytes, r.pos, end.toInt()).children { cid, c ->
                    when (cid) {
                        TIMESTAMP_SCALE -> timestampScale = c.uint()
                        DURATION -> durationTicks = c.float()
                    }
                }
                TRACKS -> tracks = parseTracks(Reader(bytes, r.pos, end.toInt()))
            }
            r.pos = end.toInt()
        }

        val t = tracks
        if (t == null) {
            return tracksOffset?.takeIf { it >= bytes.size }?.let { Result.NeedMore(it) } ?: Result.NotMatroska
        }
        return Result.Found(
            MediaInfo(
                container = "MKV",
                durationMs = durationTicks?.let { (it * timestampScale / 1_000_000).toLong() },
                video = t.video,
                audio = t.audio,
                subtitles = t.subtitles,
            )
        )
    }

    private class Tracks(val video: VideoInfo?, val audio: List<AudioTrack>, val subtitles: List<SubtitleTrack>)

    /** Position of Tracks relative to the segment data, from the SeekHead. */
    private fun seekTracks(r: Reader): Long? {
        var found: Long? = null
        r.children { id, seek ->
            if (id != SEEK) return@children
            var target = 0L
            var position: Long? = null
            seek.children { cid, c ->
                when (cid) {
                    SEEK_ID -> target = c.uint()
                    SEEK_POSITION -> position = c.uint()
                }
            }
            if (target == TRACKS) found = position
        }
        return found
    }

    private fun parseTracks(r: Reader): Tracks {
        var video: VideoInfo? = null
        val audio = mutableListOf<AudioTrack>()
        val subtitles = mutableListOf<SubtitleTrack>()
        r.children { id, entry ->
            if (id != TRACK_ENTRY) return@children
            val e = Entry()
            entry.children { cid, c ->
                when (cid) {
                    TRACK_TYPE -> e.type = c.uint().toInt()
                    FLAG_DEFAULT -> e.default = c.uint() == 1L
                    FLAG_FORCED -> e.forced = c.uint() == 1L
                    FLAG_HEARING_IMPAIRED -> e.hearingImpaired = c.uint() == 1L
                    NAME -> e.name = c.string()
                    LANGUAGE -> if (e.language == null) e.language = c.string()
                    LANGUAGE_BCP47 -> e.language = c.string()
                    CODEC_ID -> e.codecId = c.string()
                    CODEC_PRIVATE -> e.codecPrivate = c.bytes()
                    VIDEO -> c.children { vid, v ->
                        when (vid) {
                            PIXEL_WIDTH -> e.width = v.uint().toInt()
                            PIXEL_HEIGHT -> e.height = v.uint().toInt()
                            COLOUR -> v.children { colid, col ->
                                when (colid) {
                                    BITS_PER_CHANNEL -> e.bitsPerChannel = col.uint().toInt()
                                    TRANSFER -> e.transfer = col.uint().toInt()
                                }
                            }
                        }
                    }
                    AUDIO -> c.children { aid, a -> if (aid == CHANNELS) e.channels = a.uint().toInt() }
                    BLOCK_ADDITION_MAPPING -> {
                        var type = 0L
                        var extra: ByteArray? = null
                        c.children { mid, m ->
                            when (mid) {
                                BLOCK_ADD_ID_TYPE -> type = m.uint()
                                BLOCK_ADD_ID_EXTRA -> extra = m.bytes()
                            }
                        }
                        if (type in DOLBY_VISION_CONFIGS) {
                            e.dolbyVision = true
                            // DOVIDecoderConfigurationRecord: profile in the 7 high bits of byte 2,
                            // base-layer compatibility (1 HDR10, 4 HLG, 6 PQ) in the high nibble of byte 4.
                            extra?.takeIf { it.size > 2 }?.let { e.dvProfile = (it[2].toInt() and 0xFF) shr 1 }
                            extra?.takeIf { it.size > 4 }?.let { e.dvCompatibility = (it[4].toInt() and 0xF0) shr 4 }
                        }
                    }
                }
            }
            when (e.type) {
                1 -> if (video == null) video = e.toVideo()
                2 -> audio += e.toAudio()
                17 -> subtitles += e.toSubtitle()
            }
        }
        return Tracks(video, audio, subtitles)
    }

    private class Entry {
        var type = 0
        var default = true // Matroska default for FlagDefault.
        var forced = false
        var hearingImpaired = false
        var name: String? = null
        var language: String? = null
        var codecId = ""
        var codecPrivate: ByteArray? = null
        var width: Int? = null
        var height: Int? = null
        var bitsPerChannel: Int? = null
        var transfer: Int? = null
        var channels: Int? = null
        var dolbyVision = false
        var dvProfile: Int? = null
        var dvCompatibility: Int? = null

        private val nameLower get() = name?.lowercase() ?: ""

        fun toVideo(): VideoInfo {
            val hdr = buildList {
                if (dolbyVision) add(HdrFormat.DOLBY_VISION)
                if (nameLower.contains("hdr10+")) add(HdrFormat.HDR10_PLUS)
                when (transfer) {
                    16 -> add(HdrFormat.HDR10) // SMPTE ST 2084 (PQ)
                    18 -> add(HdrFormat.HLG)
                }
                // Many files only signal the HDR10 or HLG base layer through Dolby Vision.
                when {
                    dvProfile == 7 || dvCompatibility == 1 || dvCompatibility == 6 -> add(HdrFormat.HDR10)
                    dvCompatibility == 4 -> add(HdrFormat.HLG)
                }
            }.distinct()
            return VideoInfo(
                codec = videoCodec(codecId),
                width = width,
                height = height,
                bitDepth = bitsPerChannel?.takeIf { it > 0 } ?: bitDepthFromPrivate(),
                hdr = hdr,
                dolbyVisionProfile = dvProfile,
            )
        }

        /** HEVC: hvcC byte 17 holds bitDepthLumaMinus8. AVC: High 10 profile is 110. */
        private fun bitDepthFromPrivate(): Int? {
            val p = codecPrivate ?: return null
            return when {
                codecId == "V_MPEGH/ISO/HEVC" && p.size > 17 -> (p[17].toInt() and 0x07) + 8
                codecId == "V_MPEG4/ISO/AVC" && p.size > 1 -> if ((p[1].toInt() and 0xFF) == 110) 10 else 8
                else -> null
            }
        }

        fun toAudio(): AudioTrack {
            val n = nameLower
            val codec = when {
                codecId == "A_DTS" && (n.contains("hd ma") || n.contains("hd-ma") || n.contains("dts-hd") || Regex("""\bma\b""").containsMatchIn(n)) -> "DTS-HD MA"
                codecId == "A_DTS" && (n.contains("hra") || n.contains("hd hr")) -> "DTS-HD HRA"
                else -> audioCodec(codecId)
            }
            return AudioTrack(
                codec = codec,
                channels = channels,
                language = language?.takeIf { it != "und" },
                title = name,
                atmos = n.contains("atmos"),
                dtsX = codecId == "A_DTS" && Regex("""dts[:\- ]?x\b""").containsMatchIn(n),
                default = default,
            )
        }

        fun toSubtitle(): SubtitleTrack {
            val n = nameLower
            return SubtitleTrack(
                language = language?.takeIf { it != "und" },
                title = name,
                format = subtitleFormat(codecId),
                forced = forced || n.contains("forced") || n.contains("forcé"),
                hearingImpaired = hearingImpaired || Regex("""\bsdh\b""").containsMatchIn(n),
                default = default,
            )
        }
    }

    private fun videoCodec(id: String) = when {
        id == "V_MPEGH/ISO/HEVC" -> "HEVC"
        id == "V_MPEG4/ISO/AVC" -> "AVC"
        id == "V_AV1" -> "AV1"
        id == "V_VP9" -> "VP9"
        id.startsWith("V_MPEG2") -> "MPEG-2"
        id == "V_MS/VFW/FOURCC" -> "VC-1"
        else -> id.removePrefix("V_")
    }

    private fun audioCodec(id: String) = when {
        id == "A_TRUEHD" -> "TrueHD"
        id == "A_DTS" -> "DTS"
        id == "A_EAC3" -> "E-AC3"
        id == "A_AC3" -> "AC3"
        id.startsWith("A_AAC") -> "AAC"
        id == "A_FLAC" -> "FLAC"
        id == "A_OPUS" -> "Opus"
        id.startsWith("A_PCM") -> "PCM"
        id == "A_MPEG/L3" -> "MP3"
        id == "A_VORBIS" -> "Vorbis"
        else -> id.removePrefix("A_")
    }

    private fun subtitleFormat(id: String) = when (id) {
        "S_TEXT/UTF8" -> "SRT"
        "S_HDMV/PGS" -> "PGS"
        "S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA" -> "ASS"
        "S_VOBSUB" -> "VobSub"
        "S_TEXT/WEBVTT" -> "WebVTT"
        else -> id.removePrefix("S_")
    }

    /** Cursor over a byte range, reading EBML variable-length integers and values. */
    private class Reader(val bytes: ByteArray, var pos: Int = 0, val end: Int = bytes.size) {
        val remaining get() = end - pos

        fun peekId(): Long {
            val saved = pos
            return readId().also { pos = saved }
        }

        /** Element ID, marker bits kept. */
        fun readId(): Long {
            val first = byte(pos)
            val length = Integer.numberOfLeadingZeros(first) - 23
            require(length in 1..4) { "Bad element ID" }
            var value = 0L
            repeat(length) { value = (value shl 8) or byte(pos++).toLong() }
            return value
        }

        /** Element data size; null when the size is "unknown" (all value bits set). */
        fun readSize(): Long? {
            val first = byte(pos)
            val length = Integer.numberOfLeadingZeros(first) - 23
            require(length in 1..8) { "Bad element size" }
            var value = (first and (0xFF shr length)).toLong()
            pos++
            repeat(length - 1) { value = (value shl 8) or byte(pos++).toLong() }
            val unknown = (1L shl (7 * length)) - 1
            return if (value == unknown) null else value
        }

        fun skipElement() {
            readId()
            val size = readSize() ?: error("Unknown size")
            pos += size.toInt()
        }

        /** Visits direct children of the current range. */
        fun children(visit: (Long, Reader) -> Unit) {
            while (remaining > 1) {
                val id = runCatching { readId() }.getOrNull() ?: return
                val size = runCatching { readSize() }.getOrNull() ?: return
                val childEnd = (pos + size).coerceAtMost(end.toLong()).toInt()
                visit(id, Reader(bytes, pos, childEnd))
                pos = childEnd
            }
        }

        fun uint(): Long {
            var v = 0L
            for (i in pos until end) v = (v shl 8) or byte(i).toLong()
            return v
        }

        fun float(): Double = when (remaining) {
            4 -> java.lang.Float.intBitsToFloat(uint().toInt()).toDouble()
            8 -> java.lang.Double.longBitsToDouble(uint())
            else -> 0.0
        }

        fun string(): String = String(bytes, pos, remaining, Charsets.UTF_8).trimEnd('\u0000')

        fun bytes(): ByteArray = bytes.copyOfRange(pos, end)

        private fun byte(i: Int): Int {
            if (i >= end) throw IndexOutOfBoundsException()
            return bytes[i].toInt() and 0xFF
        }
    }
}
