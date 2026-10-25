package app.houselights.providers.local

import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.NfoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file the probe cannot read is described by its NFO's <streamdetails>, under the names the app shows. */
class NfoStreamDetailsTest {
    private fun media(video: String, vararg audio: String) = NfoStreamDetails.toMediaInfo(
        checkNotNull(
            NfoParser.parse(
                """
                <movie><title>Amélie</title><fileinfo><streamdetails>
                    <video><codec>$video</codec><width>720</width><height>304</height></video>
                    ${audio.joinToString("") { "<audio><codec>$it</codec><language>fre</language><channels>6</channels></audio>" }}
                </streamdetails></fileinfo></movie>
                """.trimIndent(),
            )?.streams,
        ),
        base = null,
    )

    /** An NFO lists tracks in an order of its own: they are not asked of the player by number. */
    @Test
    fun anNfosTracksAreNotInTheFilesOrder() {
        assertFalse(media("hevc", "eac3").tracksInOrder)
        val nfo = "<movie><title>A</title><fileinfo><streamdetails><video><codec>hevc</codec></video></streamdetails></fileinfo></movie>"
        val read = MediaInfo(container = "MKV", audio = listOf(AudioTrack(Codecs.AC3, 6, "fre")), tracksInOrder = true)
        assertTrue("the file's own tracks", NfoStreamDetails.toMediaInfo(checkNotNull(NfoParser.parse(nfo)?.streams), read).tracksInOrder)
    }

    @Test
    fun anOldRipIsMpeg4WithDts() {
        val rip = media("xvid", "dca", "ac3")
        assertEquals("MPEG-4", rip.video?.codec)
        assertEquals(listOf("DTS", "AC3"), rip.audio.map { it.codec })
    }

    @Test
    fun aBluRayRemuxSaysItsDtsLayer() {
        val remux = media("vc1", "dtshd_hra", "dtshd_ma")
        assertEquals("VC-1", remux.video?.codec)
        assertEquals(listOf("DTS-HD HRA", "DTS-HD MA"), remux.audio.map { it.codec })
    }

    /** Kodi 21 names the object layer in the codec: Atmos over Dolby Digital Plus or TrueHD, DTS:X over DTS-HD MA. */
    @Test
    fun kodi21SaysAtmosAndDtsXInTheCodec() {
        val web = media("hevc", "eac3_ddp_atmos", "truehd_atmos", "dtshd_ma_x", "dtshd_ma_x_imax")
        assertEquals(listOf("E-AC3", "TrueHD", "DTS-HD MA", "DTS-HD MA"), web.audio.map { it.codec })
        assertEquals(listOf(true, true, false, false), web.audio.map { it.atmos })
        assertEquals(listOf(false, false, true, true), web.audio.map { it.dtsX })
    }
}
