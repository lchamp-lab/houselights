package app.houselights.providers.local

import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.HdrFormat
import app.houselights.core.MediaInfo
import app.houselights.core.SubtitleTrack
import app.houselights.core.VideoInfo

/**
 * Reads the track list of a Matroska (MKV, WebM) file from its first bytes: codecs, frame
 * size and rate, HDR and Dolby Vision signalling, audio channels, languages and subtitles. Then
 * what the headers do not say, from the first frames of each track ([Bitstreams]): Atmos, the
 * layers of DTS, HDR10+; a track's name stands in only when none of its frames was read. Nothing
 * is decoded.
 */
object MkvProbe {
    sealed interface Result {
        /**
         * The file's tracks. [tagsAt]: where its tags lie (with mkvmerge, at its end), for
         * [bitrates]; [videoUid], [audioUids]: the tracks' UIDs, which the tags name them by;
         * [framesRead]: whether every track whose frames tell more had some in the bytes read.
         */
        data class Found(
            val info: MediaInfo,
            val tagsAt: Long? = null,
            val videoUid: Long? = null,
            val audioUids: List<Long?> = emptyList(),
            val framesRead: Boolean = true,
        ) : Result
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
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L
    private const val TAGS = 0x1254C367L
    private const val TAG = 0x7373L
    private const val TARGETS = 0x63C0L
    private const val TAG_TRACK_UID = 0x63C5L
    private const val SIMPLE_TAG = 0x67C8L
    private const val TAG_NAME = 0x45A3L
    private const val TAG_STRING = 0x4487L
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_UID = 0x73C5L
    private const val TRACK_TYPE = 0x83L
    private const val FLAG_DEFAULT = 0x88L
    private const val FLAG_FORCED = 0x55AAL
    private const val FLAG_HEARING_IMPAIRED = 0x55ABL
    private const val NAME = 0x536EL
    private const val LANGUAGE = 0x22B59CL
    private const val LANGUAGE_BCP47 = 0x22B59DL
    private const val CODEC_ID = 0x86L
    private const val CODEC_PRIVATE = 0x63A2L
    private const val DEFAULT_DURATION = 0x23E383L
    private const val NANOS_PER_SECOND = 1e9f
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

        var seek = emptyMap<Long, Long>()
        var timestampScale = 1_000_000L
        var durationTicks: Double? = null
        var entries: List<Entry>? = null
        val frames = mutableMapOf<Int, MutableList<IntRange>>()

        while (r.remaining > 0) {
            val start = r.pos
            val id = runCatching { r.readId() }.getOrNull() ?: break
            val read = runCatching { r.readSize() }
            if (read.isFailure) break
            val size = read.getOrNull()
            if (id == CLUSTER) {
                // The first frames of each track, as far as what was read goes.
                val tracks = entries ?: break
                val end = size?.let { (r.pos + it).coerceAtMost(bytes.size.toLong()).toInt() } ?: bytes.size
                collectFrames(Reader(bytes, r.pos, end), tracks, frames)
                if (size == null || r.pos + size > bytes.size || tracks.all { it.hasFrames(frames, enough = true) }) break
                r.pos = (r.pos + size).toInt()
                continue
            }
            val end = r.pos + (size ?: break)
            if (end > bytes.size) {
                // Element cut by the end of what was read: worth reading more only for Tracks.
                if (id == TRACKS) return Result.NeedMore(start.toLong())
                break
            }
            when (id) {
                SEEK_HEAD -> seek = seekPositions(Reader(bytes, r.pos, end.toInt())).mapValues { segmentStart + it.value }
                INFO -> Reader(bytes, r.pos, end.toInt()).children { cid, c ->
                    when (cid) {
                        TIMESTAMP_SCALE -> timestampScale = c.uint()
                        DURATION -> durationTicks = c.float()
                    }
                }
                TRACKS -> entries = parseTracks(Reader(bytes, r.pos, end.toInt()))
            }
            r.pos = end.toInt()
        }

        val tracks = entries ?: return seek[TRACKS]?.takeIf { it >= bytes.size }?.let { Result.NeedMore(it) } ?: Result.NotMatroska
        return found(bytes, tracks, frames, seek[TAGS], durationTicks?.let { (it * timestampScale / 1_000_000).toLong() })
    }

    /** The file's tracks, each as its frames say it when some were read. */
    private fun found(
        bytes: ByteArray,
        tracks: List<Entry>,
        frames: Map<Int, List<IntRange>>,
        tagsAt: Long?,
        durationMs: Long?,
    ): Result.Found {
        val video = tracks.firstOrNull { it.type == TYPE_VIDEO }
        val audio = tracks.filter { it.type == TYPE_AUDIO }
        return Result.Found(
            MediaInfo(
                container = "MKV",
                durationMs = durationMs,
                video = video?.toVideo(bytes, frames[video.number].orEmpty()),
                audio = audio.map { it.toAudio(bytes, frames[it.number].orEmpty()) },
                subtitles = tracks.filter { it.type == TYPE_SUBTITLE }.map { it.toSubtitle() },
            ),
            tagsAt = tagsAt,
            videoUid = video?.uid,
            audioUids = audio.map { it.uid },
            framesRead = (listOfNotNull(video) + audio).all { it.hasFrames(frames, enough = false) },
        )
    }

    /**
     * The bitrate mkvmerge measured for each track (its statistics tags: BPS, in bits a second),
     * by track UID, from a Tags element at the start of [bytes]. Empty when there is none.
     */
    fun bitrates(bytes: ByteArray): Map<Long, Long> {
        val r = Reader(bytes)
        if (r.remaining < 4 || runCatching { r.readId() }.getOrNull() != TAGS) return emptyMap()
        val size = runCatching { r.readSize() }.getOrNull() ?: return emptyMap()
        val tags = Reader(bytes, r.pos, (r.pos + size).coerceAtMost(bytes.size.toLong()).toInt())
        val found = mutableMapOf<Long, Long>()
        tags.children { id, tag ->
            if (id != TAG) return@children
            var uid: Long? = null
            var bps: Long? = null
            tag.children { tid, t ->
                when (tid) {
                    TARGETS -> t.children { gid, g -> if (gid == TAG_TRACK_UID) uid = g.uint() }
                    SIMPLE_TAG -> {
                        var name: String? = null
                        var value: String? = null
                        t.children { sid, v ->
                            when (sid) {
                                TAG_NAME -> name = v.string()
                                TAG_STRING -> value = v.string()
                            }
                        }
                        if (name == "BPS") bps = value?.trim()?.toLongOrNull()
                    }
                }
            }
            val track = uid
            val rate = bps
            if (track != null && rate != null) found[track] = rate
        }
        return found
    }

    /** Positions relative to the segment data, by element ID, from the SeekHead. */
    private fun seekPositions(r: Reader): Map<Long, Long> {
        val found = mutableMapOf<Long, Long>()
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
            position?.let { found[target] = it }
        }
        return found
    }

    /** The first frames of the tracks that tell more by them, from a cluster's blocks. */
    private fun collectFrames(cluster: Reader, tracks: List<Entry>, frames: MutableMap<Int, MutableList<IntRange>>) {
        val wanted = tracks.filter { it.framesWanted > 0 }.associateBy { it.number }
        fun block(b: Reader) {
            val number = runCatching { b.readSize() }.getOrNull()?.toInt() ?: return
            val track = wanted[number] ?: return
            val list = frames.getOrPut(number) { mutableListOf() }
            if (list.size >= track.framesWanted || b.remaining < BLOCK_HEADER) return
            val lacing = (b.bytes[b.pos + 2].toInt() shr 1) and LACING_BITS
            list += Bitstreams.laced(b.bytes, b.pos + BLOCK_HEADER, b.end, lacing).take(track.framesWanted - list.size)
        }
        cluster.children { id, child ->
            when (id) {
                SIMPLE_BLOCK -> block(child)
                BLOCK_GROUP -> child.children { gid, g -> if (gid == BLOCK) block(g) }
            }
        }
    }

    private fun parseTracks(r: Reader): List<Entry> {
        val entries = mutableListOf<Entry>()
        r.children { id, entry ->
            if (id != TRACK_ENTRY) return@children
            val e = Entry()
            entry.children { cid, c ->
                when (cid) {
                    TRACK_TYPE -> e.type = c.uint().toInt()
                    TRACK_NUMBER -> e.number = c.uint().toInt()
                    TRACK_UID -> e.uid = c.uint()
                    FLAG_DEFAULT -> e.default = c.uint() == 1L
                    FLAG_FORCED -> e.forced = c.uint() == 1L
                    FLAG_HEARING_IMPAIRED -> e.hearingImpaired = c.uint() == 1L
                    NAME -> e.name = c.string()
                    LANGUAGE -> if (e.language == null) e.language = c.string()
                    LANGUAGE_BCP47 -> e.language = c.string()
                    CODEC_ID -> e.codecId = c.string()
                    CODEC_PRIVATE -> e.codecPrivate = c.bytes()
                    DEFAULT_DURATION -> e.frameNanos = c.uint()
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
            entries += e
        }
        return entries
    }

    private const val TYPE_VIDEO = 1
    private const val TYPE_AUDIO = 2
    private const val TYPE_SUBTITLE = 17

    /** A Block's track number is followed by its timestamp (2 bytes) and its flags (1). */
    private const val BLOCK_HEADER = 3
    private const val LACING_BITS = 3

    /** How many frames a track's format is read from: TrueHD's major sync may come a few frames in. */
    private const val FRAMES_TRUEHD = 16
    private const val FRAMES_OTHER = 4

    private class Entry {
        var type = 0
        var number = 0
        var uid: Long? = null
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
        /** How long one frame lasts, in nanoseconds: 41708333 for 23.976 frames a second. */
        var frameNanos: Long? = null

        private val nameLower get() = name?.lowercase() ?: ""

        /** How many of its first frames tell this track's format; none for a format its header says whole. */
        val framesWanted: Int
            get() = when {
                type == TYPE_VIDEO && codecId == HEVC_ID -> FRAMES_OTHER
                type != TYPE_AUDIO -> 0
                codecId == TRUEHD_ID -> FRAMES_TRUEHD
                codecId == EAC3_ID || codecId == AC3_ID || codecId == DTS_ID -> FRAMES_OTHER
                else -> 0
            }

        /** Whether this track has frames to be read from: any ([enough] false), or all it wants. */
        fun hasFrames(frames: Map<Int, List<IntRange>>, enough: Boolean): Boolean {
            if (framesWanted == 0) return true
            val count = frames[number]?.size ?: 0
            return if (enough) count >= framesWanted else count > 0
        }

        fun toVideo(bytes: ByteArray, frames: List<IntRange>): VideoInfo {
            // What the stream signals: HDR10+ dynamic metadata, an HDR10 mastering display. Frames
            // that cannot be read leave the track as its header says it (readFrames).
            val stream = if (codecId == HEVC_ID && frames.isNotEmpty()) {
                readFrames { Bitstreams.hevcRanges(bytes, frames, nalLengthSize()) }
            } else {
                null
            }
            val hdr = buildList {
                if (dolbyVision) add(HdrFormat.DOLBY_VISION)
                if (stream?.contains(HdrFormat.HDR10_PLUS) ?: nameLower.contains("hdr10+")) add(HdrFormat.HDR10_PLUS)
                addAll(baseRanges())
                if (stream?.contains(HdrFormat.HDR10) == true && transfer != HLG_TRANSFER) add(HdrFormat.HDR10)
            }.distinct()
            return VideoInfo(
                codec = videoCodec(codecId),
                width = width,
                height = height,
                bitDepth = bitsPerChannel?.takeIf { it > 0 } ?: bitDepthFromPrivate(),
                hdr = hdr,
                dolbyVisionProfile = dvProfile,
                frameRate = frameNanos?.takeIf { it > 0 }?.let { NANOS_PER_SECOND / it },
                dolbyVisionCompatibility = dvCompatibility.takeIf { dolbyVision },
            )
        }

        /** The range the headers say: the transfer of the colour, or the base layer under Dolby Vision. */
        private fun baseRanges(): List<HdrFormat> = buildList {
            when (transfer) {
                16 -> add(HdrFormat.HDR10) // SMPTE ST 2084 (PQ)
                18 -> add(HdrFormat.HLG)
            }
            // Many files only signal the HDR10 or HLG base layer through Dolby Vision.
            when {
                dvProfile == 7 || dvCompatibility == 1 || dvCompatibility == 6 -> add(HdrFormat.HDR10)
                dvCompatibility == 4 -> add(HdrFormat.HLG)
            }
        }

        /** HEVC: hvcC byte 21 holds lengthSizeMinusOne, the size of the length before each NAL unit. */
        private fun nalLengthSize(): Int = codecPrivate?.takeIf { it.size > HVCC_LENGTH_SIZE_AT }
            ?.let { (it[HVCC_LENGTH_SIZE_AT].toInt() and NAL_LENGTH_SIZE_BITS) + 1 } ?: DEFAULT_NAL_LENGTH_SIZE

        /** HEVC: hvcC byte 17 holds bitDepthLumaMinus8. AVC: High 10 profile is 110. */
        private fun bitDepthFromPrivate(): Int? {
            val p = codecPrivate ?: return null
            return when {
                codecId == "V_MPEGH/ISO/HEVC" && p.size > 17 -> (p[17].toInt() and 0x07) + 8
                codecId == "V_MPEG4/ISO/AVC" && p.size > 1 -> if ((p[1].toInt() and 0xFF) == 110) 10 else 8
                else -> null
            }
        }

        /**
         * The track as its frames say it: Atmos in TrueHD or Dolby Digital Plus, the layers of DTS.
         * Its name stands in only when none of its frames was read.
         */
        fun toAudio(bytes: ByteArray, frames: List<IntRange>): AudioTrack {
            val n = nameLower
            val dts = if (codecId == DTS_ID) {
                readFrames { frames.firstNotNullOfOrNull { Bitstreams.dts(bytes, it.first, it.last + 1) } }
            } else {
                null
            }
            return AudioTrack(
                codec = dts?.let(::dtsCodec) ?: codecByName(n),
                channels = channels,
                language = language?.takeIf { it != "und" },
                title = name,
                atmos = streamAtmos(bytes, frames) ?: n.contains("atmos"),
                dtsX = if (dts != null) dts == Bitstreams.Dts.X else codecId == DTS_ID && DTS_X.containsMatchIn(n),
                default = default,
            )
        }

        /** Atmos as the frames say it, in TrueHD or Dolby Digital Plus; null when none was read. */
        private fun streamAtmos(bytes: ByteArray, frames: List<IntRange>): Boolean? = readFrames {
            when (codecId) {
                TRUEHD_ID -> frames.mapNotNull { Bitstreams.trueHdAtmos(bytes, it.first, it.last + 1) }
                EAC3_ID, AC3_ID -> frames.mapNotNull { Bitstreams.dolbyAtmos(bytes, it.first, it.last + 1) }
                else -> emptyList()
            }.takeIf { it.isNotEmpty() }?.any { it }
        }

        /**
         * What [read] makes of a track's frames, or null, as if none had been read, when they hold
         * what no reading expected: one bad frame costs what the frames add to the headers, never
         * the whole file's tracks.
         */
        private inline fun <T> readFrames(read: () -> T?): T? = runCatching(read).getOrNull()

        /** The codec when no frame told it: the layers of DTS-HD, by the track's name. */
        private fun codecByName(n: String): String = when {
            codecId == DTS_ID && (n.contains("hd ma") || n.contains("hd-ma") || n.contains("dts-hd") || MA.containsMatchIn(n)) ->
                Codecs.DTS_HD_MA
            codecId == DTS_ID && (n.contains("hra") || n.contains("hd hr")) -> Codecs.DTS_HD_HRA
            else -> audioCodec(codecId)
        }

        fun toSubtitle(): SubtitleTrack {
            val n = nameLower
            return SubtitleTrack(
                language = language?.takeIf { it != "und" },
                title = name,
                format = subtitleFormat(codecId),
                forced = forced || n.contains("forced") || n.contains("forcé"),
                hearingImpaired = hearingImpaired || SDH.containsMatchIn(n),
                default = default,
            )
        }
    }

    private const val HEVC_ID = "V_MPEGH/ISO/HEVC"
    private const val TRUEHD_ID = "A_TRUEHD"
    private const val EAC3_ID = "A_EAC3"
    private const val AC3_ID = "A_AC3"
    private const val DTS_ID = "A_DTS"
    private const val HLG_TRANSFER = 18
    private const val HVCC_LENGTH_SIZE_AT = 21
    private const val NAL_LENGTH_SIZE_BITS = 0x03
    private const val DEFAULT_NAL_LENGTH_SIZE = 4

    private val MA = Regex("""\bma\b""")
    private val DTS_X = Regex("""dts[:\- ]?x\b""")
    private val SDH = Regex("""\bsdh\b""")

    private fun dtsCodec(dts: Bitstreams.Dts) = when (dts) {
        Bitstreams.Dts.X, Bitstreams.Dts.MASTER_AUDIO -> Codecs.DTS_HD_MA
        Bitstreams.Dts.HIGH_RESOLUTION -> Codecs.DTS_HD_HRA
        Bitstreams.Dts.EXPRESS -> Codecs.DTS_EXPRESS
        Bitstreams.Dts.CORE -> Codecs.DTS
    }

    private fun videoCodec(id: String) = when {
        id == "V_MPEGH/ISO/HEVC" -> Codecs.HEVC
        id == "V_MPEG4/ISO/AVC" -> Codecs.AVC
        id == "V_AV1" -> Codecs.AV1
        id == "V_VP9" -> Codecs.VP9
        id.startsWith("V_MPEG2") -> Codecs.MPEG2
        id == "V_MS/VFW/FOURCC" -> Codecs.VC1
        else -> id.removePrefix("V_")
    }

    private fun audioCodec(id: String) = when {
        id == "A_TRUEHD" -> Codecs.TRUEHD
        id == "A_DTS" -> Codecs.DTS
        id == "A_EAC3" -> Codecs.EAC3
        id == "A_AC3" -> Codecs.AC3
        id.startsWith("A_AAC") -> Codecs.AAC
        id == "A_FLAC" -> Codecs.FLAC
        id == "A_OPUS" -> Codecs.OPUS
        id.startsWith("A_PCM") -> Codecs.PCM
        id == "A_MPEG/L3" -> Codecs.MP3
        id == "A_VORBIS" -> "Vorbis"
        else -> id.removePrefix("A_")
    }

    private fun subtitleFormat(id: String) = when (id) {
        "S_TEXT/UTF8" -> Codecs.SRT
        "S_HDMV/PGS" -> Codecs.PGS
        "S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA" -> Codecs.ASS
        "S_VOBSUB" -> Codecs.VOBSUB
        "S_TEXT/WEBVTT" -> Codecs.WEBVTT
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
            require(size <= remaining) { "Element past the end" }
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
            if (i < 0 || i >= end) throw IndexOutOfBoundsException()
            return bytes[i].toInt() and 0xFF
        }
    }
}
