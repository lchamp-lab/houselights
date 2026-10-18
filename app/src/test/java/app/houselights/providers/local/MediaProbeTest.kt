package app.houselights.providers.local

import app.houselights.core.MediaInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which files are probed again (those of an older probe version), and how far a file is read, from where. */
class MediaProbeTest {
    @Test
    fun anOlderReadingIsReadAgain() {
        assertTrue(MediaProbe.isStale(null))
        // Probed before the probe recorded its version: no frame rate.
        assertTrue(MediaProbe.isStale("""{"container":"MKV","video":{"codec":"HEVC","width":3840}}"""))
        assertTrue(MediaProbe.isStale("not json"))
    }

    @Test
    fun aCurrentReadingStays() {
        assertFalse(MediaProbe.isStale(MediaProbe.encode(MediaInfo(container = "MKV", probedWith = MediaProbe.VERSION))))
        // An MP4 the probe could not open is not retried at every scan.
        assertFalse(MediaProbe.isStale(MediaProbe.encode(MediaInfo(container = "MP4", probedWith = MediaProbe.VERSION))))
    }

    private val folder = SourceEntity(
        name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "192.168.1.20", share = "volume1/media",
        path = "films", username = "", password = Secret(""),
    )

    /** An EBML element: its ID, its size in four bytes, then its body. */
    private fun el(id: Int, vararg body: ByteArray): ByteArray {
        val data = body.fold(ByteArray(0)) { all, b -> all + b }
        val n = data.size
        val idBytes = (24 downTo 0 step 8).map { (id shr it).toByte() }.dropWhile { it == 0.toByte() }.toByteArray()
        return idBytes + byteArrayOf(0x10, (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()) + data
    }

    private fun text(id: Int, s: String) = el(id, s.toByteArray())

    private fun number(id: Int, v: Int) = el(id, byteArrayOf((v shr 8).toByte(), v.toByte()))

    /** Each read of [file] (its offset and length), and what the probe made of it. */
    private suspend fun probe(file: ByteArray): Pair<List<Pair<Long, Int>>, MediaInfo> {
        val nas = FakeRemoteBrowser().apply { put("Heat (1995).mkv", size = file.size.toLong(), bytes = file) }
        val reads = mutableListOf<Pair<Long, Int>>()
        nas.onRead = { _, offset, length -> reads += offset to length }
        val result = MediaProbe.probe(folder, RemoteFile("Heat (1995).mkv", file.size.toLong()), nas)
        return reads to result.info
    }

    /** A remux's start: HEVC and AC3 tracks, a first video frame of [videoFrame] bytes, then the audio's. */
    private fun remux(videoFrame: Int): ByteArray {
        val video = el(
            0xAE,
            number(0xD7, 1), number(0x83, 1), text(0x86, "V_MPEGH/ISO/HEVC"), el(0xE0, number(0xB0, 3840), number(0xBA, 2160)),
        )
        val audio = el(0xAE, number(0xD7, 2), number(0x83, 2), text(0x86, "A_AC3"), el(0xE1, number(0x9F, 6)))
        // A SimpleBlock: its track's number, a timestamp, the flags of a keyframe without lacing, then the frame.
        fun block(track: Int, frame: Int) = el(0xA3, byteArrayOf((0x80 or track).toByte(), 0, 0, 0x80.toByte()) + ByteArray(frame))
        val cluster = el(0x1F43B675, number(0xE7, 0), block(1, videoFrame), block(2, 1_536))
        val rest = el(0xEC, ByteArray(FRAMES_AT_MOST))
        return el(0x1A45DFA3, text(0x4282, "matroska")) + el(0x18538067, el(0x1654AE6B, video, audio), cluster, rest)
    }

    /** What reading [file] whole makes of its tracks. */
    private fun whole(file: ByteArray) = (MkvProbe.probe(file) as MkvProbe.Result.Found).info

    /** A track list larger than the first read, as with a 300 KB CodecPrivate: it is read to its end. */
    @Test
    fun aTrackListLongerThanTheFirstReadIsReadToItsEnd() = runTest {
        val video = el(
            0xAE,
            number(0xD7, 1), number(0x83, 1), text(0x86, "V_MPEG4/ISO/AVC"), el(0x63A2, ByteArray(300 shl 10)),
            el(0xE0, number(0xB0, 1920), number(0xBA, 1080)),
        )
        val file = el(0x1A45DFA3, text(0x4282, "matroska")) + el(0x18538067, el(0x1654AE6B, video))
        assertEquals("their end, not their start", MkvProbe.Result.NeedMore(file.size.toLong()), MkvProbe.probe(file.copyOf(FIRST_READ)))
        val (reads, info) = probe(file)
        assertEquals("AVC", info.video?.codec)
        assertEquals(1920, info.video?.width)
        assertEquals("each read goes on from the last", listOf(0L, FIRST_READ.toLong()), reads.map { it.first })
    }

    /** Tracks in the first read, their first frames further in: the next read starts where the first stopped. */
    @Test
    fun framesFurtherInAreReadOnFromTheFirstRead() = runTest {
        val (reads, info) = probe(MkvProbeTest().sampleFile() + ByteArray(FIRST_READ))
        assertEquals("HEVC", info.video?.codec)
        assertEquals(listOf(0L, FIRST_READ.toLong()), reads.map { it.first })
    }

    /** A 4K remux's first video frame fills the first read, the audio's comes within the megabyte: no further. */
    @Test
    fun firstFramesWithinTheFirstMegabyteAreReadNoFurther() = runTest {
        val file = remux(videoFrame = 500_000)
        val (reads, info) = probe(file)
        assertEquals(listOf(0L to FIRST_READ, FIRST_READ.toLong() to MEGABYTE - FIRST_READ), reads)
        assertEquals(whole(file).video, info.video)
        assertEquals(whole(file).audio, info.audio)
    }

    /** A first video frame larger than the megabyte: reading goes on to the next step, for the audio's. */
    @Test
    fun firstFramesPastTheFirstMegabyteAreReadOnToTheNextStep() = runTest {
        val file = remux(videoFrame = 1_500_000)
        val (reads, info) = probe(file)
        assertEquals(
            listOf(0L to FIRST_READ, FIRST_READ.toLong() to MEGABYTE - FIRST_READ, MEGABYTE.toLong() to FRAMES_AT_MOST - MEGABYTE),
            reads,
        )
        assertEquals(whole(file).video, info.video)
        assertEquals(whole(file).audio, info.audio)
    }

    private companion object {
        const val FIRST_READ = 256 shl 10
        const val MEGABYTE = 1 shl 20

        /** How far a file is read for its tracks' first frames. */
        const val FRAMES_AT_MOST = 4 shl 20
    }
}
