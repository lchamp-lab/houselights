package app.houselights.readme

import app.houselights.providers.local.Matroska
import java.nio.ByteBuffer

/**
 * The README's video files: the start of a Matroska file as mkvmerge writes it, a SeekHead, the segment's info, its
 * tracks as [Master] has them and the Tags holding each track's bitrate, all the app's probe reads of a file.
 */
internal object ReadmeFiles {
    /** A film of [minutes], mastered as [master] says. */
    fun film(master: Master, minutes: Int): ByteArray = file(master, minutes)

    /** An episode of [minutes], from a streaming service in Ultra HD. */
    fun episode(minutes: Int): ByteArray = file(Master.UHD_WEB, minutes)

    /** How large a file of [minutes] is, at the bitrate its [master] has: what the folders' sizes add up to. */
    fun size(master: Master, minutes: Int): Long = (bitrates(master).let { it.first + it.second } / BITS) * minutes * SECONDS

    private fun file(master: Master, minutes: Int): ByteArray {
        val info = Matroska.element(
            INFO,
            Matroska.uint(TIMESTAMP_SCALE, 1_000_000),
            Matroska.element(DURATION, ByteBuffer.allocate(Double.SIZE_BYTES).putDouble(minutes * 60_000.0).array()),
        )
        val tracks = Matroska.element(TRACKS, *(listOf(video(master)) + audio(master) + subtitles(master)).toTypedArray())
        val (videoRate, audioRate) = bitrates(master)
        val tags = Matroska.element(TAGS, bitrate(VIDEO_UID, videoRate), bitrate(AUDIO_UID, audioRate))
        // The Tags' place in the segment, after the SeekHead (of a fixed size), the info and the tracks.
        val tagsAt = seekHead(0).size + info.size + tracks.size
        return Matroska.file(seekHead(tagsAt), info, tracks, tags)
    }

    /** The picture: Ultra HD in HEVC, 10-bit PQ, its Dolby Vision configuration; or Full HD in AVC. */
    private fun video(master: Master): ByteArray {
        val uhd = master != Master.FHD_DTS
        val dolbyVision = when (master) {
            Master.UHD_DV_ATMOS, Master.UHD_DV_HDR10_PLUS -> dolbyVision(profile = 7, compatibility = 6)
            Master.UHD_WEB, Master.UHD_WEB_HDR10_PLUS -> dolbyVision(profile = 8, compatibility = 1)
            Master.UHD_HDR10_DTS, Master.FHD_DTS -> null
        }
        // HDR10+ is read from the first frames, else from the track's name: these files hold no frame.
        val name = when (master) {
            Master.UHD_DV_HDR10_PLUS, Master.UHD_WEB_HDR10_PLUS -> "Dolby Vision, HDR10+"
            Master.UHD_DV_ATMOS, Master.UHD_WEB -> "Dolby Vision"
            Master.UHD_HDR10_DTS -> "HDR10"
            Master.FHD_DTS -> "SDR"
        }
        val picture = if (uhd) {
            Matroska.element(
                VIDEO,
                Matroska.uint(PIXEL_WIDTH, 3840),
                Matroska.uint(PIXEL_HEIGHT, 2160),
                Matroska.element(COLOUR, Matroska.uint(TRANSFER, PQ), Matroska.uint(BITS_PER_CHANNEL, 10)),
            )
        } else {
            Matroska.element(VIDEO, Matroska.uint(PIXEL_WIDTH, 1920), Matroska.uint(PIXEL_HEIGHT, 1080))
        }
        val parts = listOfNotNull(
            Matroska.uint(TRACK_NUMBER, 1),
            Matroska.uint(TRACK_UID, VIDEO_UID),
            Matroska.uint(TRACK_TYPE, 1),
            Matroska.str(CODEC_ID, if (uhd) "V_MPEGH/ISO/HEVC" else "V_MPEG4/ISO/AVC"),
            Matroska.str(NAME, name),
            // 41,708,333 ns a frame: 24000/1001 frames a second.
            Matroska.element(DEFAULT_DURATION, byteArrayOf(0x02, 0x7C, 0x6B, 0x2D)),
            picture,
            dolbyVision,
        )
        return Matroska.element(TRACK_ENTRY, *parts.toTypedArray())
    }

    /**
     * The Dolby Vision configuration record (dvcC up to profile 7, dvvC after): [profile] in byte 2, level 6 with its
     * RPU, enhancement and base layers in byte 3, the base layer's [compatibility] (1 HDR10, 6 a Blu-ray's) in byte 4.
     */
    private fun dolbyVision(profile: Int, compatibility: Int): ByteArray {
        val layers = if (profile == 7) 0x37 else 0x35
        val record = byteArrayOf(1, 0, (profile shl 1).toByte(), layers.toByte(), (compatibility shl 4).toByte())
        val type = if (profile <= 7) "dvcC" else "dvvC"
        return Matroska.element(
            BLOCK_ADDITION_MAPPING,
            Matroska.element(BLOCK_ADD_ID_TYPE, type.toByteArray()),
            Matroska.element(BLOCK_ADD_ID_EXTRA, record),
        )
    }

    /** The original sound first, in English, then a disc's compatibility track; the name says Atmos, as no frame does. */
    private fun audio(master: Master): List<ByteArray> = when (master) {
        Master.UHD_DV_ATMOS, Master.UHD_DV_HDR10_PLUS -> listOf(
            sound(AUDIO_UID, 2, "A_TRUEHD", "Dolby TrueHD Atmos 7.1", channels = 8),
            sound(AUDIO_UID + 1, 3, "A_AC3", "Dolby Digital 5.1", channels = 6, default = false),
        )
        Master.UHD_WEB, Master.UHD_WEB_HDR10_PLUS -> listOf(
            sound(AUDIO_UID, 2, "A_EAC3", "Dolby Digital Plus Atmos 5.1", channels = 6),
        )
        Master.UHD_HDR10_DTS, Master.FHD_DTS -> listOf(
            sound(AUDIO_UID, 2, "A_DTS", "DTS-HD MA 5.1", channels = 6),
            sound(AUDIO_UID + 1, 3, "A_AC3", "Dolby Digital 5.1", channels = 6, default = false),
        )
    }

    private fun sound(uid: Long, number: Int, codec: String, name: String, channels: Int, default: Boolean = true): ByteArray =
        Matroska.element(
            TRACK_ENTRY,
            Matroska.uint(TRACK_NUMBER, number.toLong()),
            Matroska.uint(TRACK_UID, uid),
            Matroska.uint(TRACK_TYPE, 2),
            Matroska.uint(FLAG_DEFAULT, if (default) 1 else 0),
            Matroska.str(CODEC_ID, codec),
            Matroska.str(LANGUAGE, "eng"),
            Matroska.str(NAME, name),
            Matroska.element(AUDIO, Matroska.uint(CHANNELS, channels.toLong())),
        )

    /** English, English for the hard of hearing, French and Spanish: a disc's as pictures, a service's as text. */
    private fun subtitles(master: Master): List<ByteArray> {
        val codec = if (master == Master.UHD_WEB || master == Master.UHD_WEB_HDR10_PLUS) "S_TEXT/UTF8" else "S_HDMV/PGS"
        val tracks = listOf("eng" to "English", "eng" to "English SDH", "fre" to "Français", "spa" to "Español")
        return tracks.mapIndexed { i, (language, name) ->
            Matroska.element(
                TRACK_ENTRY,
                Matroska.uint(TRACK_NUMBER, 4L + i),
                Matroska.uint(TRACK_UID, SUBTITLE_UID + i),
                Matroska.uint(TRACK_TYPE, 17),
                Matroska.uint(FLAG_DEFAULT, 0),
                Matroska.str(CODEC_ID, codec),
                Matroska.str(LANGUAGE, language),
                Matroska.str(NAME, name),
            )
        }
    }

    /** The video's and the first sound's bitrates, in bits a second, as mkvmerge's BPS tags give them. */
    private fun bitrates(master: Master): Pair<Long, Long> = when (master) {
        Master.UHD_DV_ATMOS, Master.UHD_DV_HDR10_PLUS -> 61_874_000L to 5_318_000L
        Master.UHD_HDR10_DTS -> 58_212_000L to 3_917_000L
        Master.UHD_WEB, Master.UHD_WEB_HDR10_PLUS -> 24_406_000L to 768_000L
        Master.FHD_DTS -> 31_550_000L to 3_786_000L
    }

    private fun bitrate(uid: Long, bps: Long): ByteArray = Matroska.element(
        TAG,
        Matroska.element(TARGETS, Matroska.uint(TAG_TRACK_UID, uid)),
        Matroska.element(SIMPLE_TAG, Matroska.str(TAG_NAME, "BPS"), Matroska.str(TAG_STRING, bps.toString())),
    )

    /** Where the Tags are in the segment, in four bytes, so the SeekHead's size never depends on it. */
    private fun seekHead(tagsAt: Int): ByteArray = Matroska.element(
        SEEK_HEAD,
        Matroska.element(
            SEEK,
            Matroska.element(SEEK_ID, byteArrayOf(0x12, 0x54, 0xC3.toByte(), 0x67)),
            Matroska.element(SEEK_POSITION, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(tagsAt).array()),
        ),
    )

    private const val BITS = 8
    private const val SECONDS = 60

    private const val VIDEO_UID = 0x5EED0001L
    private const val AUDIO_UID = 0x5EED0010L
    private const val SUBTITLE_UID = 0x5EED0100L

    /** Smpte ST 2084, PQ. */
    private const val PQ = 16L

    // Element IDs, marker bits kept, as MkvProbe reads them.
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val INFO = 0x1549A966L
    private const val TIMESTAMP_SCALE = 0x2AD7B1L
    private const val DURATION = 0x4489L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_UID = 0x73C5L
    private const val TRACK_TYPE = 0x83L
    private const val FLAG_DEFAULT = 0x88L
    private const val NAME = 0x536EL
    private const val LANGUAGE = 0x22B59CL
    private const val CODEC_ID = 0x86L
    private const val DEFAULT_DURATION = 0x23E383L
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
    private const val TAGS = 0x1254C367L
    private const val TAG = 0x7373L
    private const val TARGETS = 0x63C0L
    private const val TAG_TRACK_UID = 0x63C5L
    private const val SIMPLE_TAG = 0x67C8L
    private const val TAG_NAME = 0x45A3L
    private const val TAG_STRING = 0x4487L
}
