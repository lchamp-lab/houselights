package app.houselights.providers.local

import app.houselights.core.MediaInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file read by an older probe is read again; one read by this probe is not. */
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
}
