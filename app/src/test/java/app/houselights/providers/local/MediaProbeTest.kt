package app.houselights.providers.local

import app.houselights.core.MediaInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file read by an older probe is read again; one read by this probe is not. How far a file is read, and from where. */
class MediaProbeTest {
    @Test
    fun anOlderReadingIsReadAgain() {
        assertTrue(MediaProbe.isStale(null))
        // Read before the probe kept its version: no frame rate in it.
        assertTrue(MediaProbe.isStale("""{"container":"MKV","video":{"codec":"HEVC","width":3840}}"""))
        assertTrue(MediaProbe.isStale("not json"))
    }

    @Test
    fun aCurrentReadingStays() {
        assertFalse(MediaProbe.isStale(MediaProbe.encode(MediaInfo(container = "MKV", probedWith = MediaProbe.VERSION))))
        // An MP4 the probe could not open is not tried again at every scan.
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

    /** Where each read of [file] started, and what the probe made of it. */
    private suspend fun probe(file: ByteArray): Pair<List<Long>, MediaInfo> {
        val nas = FakeRemoteBrowser().apply { put("Heat (1995).mkv", size = file.size.toLong(), bytes = file) }
        val starts = mutableListOf<Long>()
        nas.onRead = { _, offset, _ -> starts += offset }
        val result = MediaProbe.probe(folder, RemoteFile("Heat (1995).mkv", file.size.toLong()), nas)
        return starts to result.info
    }

    /** A track list that alone outgrows the first read, as a track's 300 KB CodecPrivate makes it: read on to its end. */
    @Test
    fun aTrackListLongerThanTheFirstReadIsReadToItsEnd() = runTest {
        val video = el(
            0xAE,
            number(0xD7, 1), number(0x83, 1), text(0x86, "V_MPEG4/ISO/AVC"), el(0x63A2, ByteArray(300 shl 10)),
            el(0xE0, number(0xB0, 1920), number(0xBA, 1080)),
        )
        val file = el(0x1A45DFA3, text(0x4282, "matroska")) + el(0x18538067, el(0x1654AE6B, video))
        assertEquals("their end, not their start", MkvProbe.Result.NeedMore(file.size.toLong()), MkvProbe.probe(file.copyOf(FIRST_READ)))
        val (starts, info) = probe(file)
        assertEquals("AVC", info.video?.codec)
        assertEquals(1920, info.video?.width)
        assertEquals("each read goes on from the last", listOf(0L, FIRST_READ.toLong()), starts)
    }

    /** Tracks in the first read, their first frames further in: the next read starts where the first stopped. */
    @Test
    fun framesFurtherInAreReadOnFromTheFirstRead() = runTest {
        val (starts, info) = probe(MkvProbeTest().sampleFile() + ByteArray(FIRST_READ))
        assertEquals("HEVC", info.video?.codec)
        assertEquals(listOf(0L, FIRST_READ.toLong()), starts)
    }

    private companion object {
        const val FIRST_READ = 256 shl 10
    }
}
