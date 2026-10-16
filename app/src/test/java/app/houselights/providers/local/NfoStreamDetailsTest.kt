package app.houselights.providers.local

import app.houselights.core.NfoParser
import org.junit.Assert.assertEquals
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
}
