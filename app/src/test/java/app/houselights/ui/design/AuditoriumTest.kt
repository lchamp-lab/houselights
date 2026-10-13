package app.houselights.ui.design

import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditoriumTest {
    private fun film(width: Int?, height: Int?) =
        MediaItem(id = "1", kind = MediaKind.MOVIE, title = "Film", media = MediaInfo(video = VideoInfo("HEVC", width, height)))

    @Test
    fun scopeEncodesStopAtTwoThirtyNine() {
        assertEquals(2.39f, HlScreen.ratioOf(film(3832, 1600)))
        assertEquals(2.39f, HlScreen.ratioOf(film(1920, 804)))
        assertEquals(2.39f, HlScreen.ratioOf(film(3840, 1608)))
    }

    @Test
    fun flatFilmsAndTelevisionFillTheDisplay() {
        assertEquals(HlScreen.DISPLAY, HlScreen.ratioOf(film(3840, 2160)))
        assertEquals(HlScreen.DISPLAY, HlScreen.ratioOf(film(1440, 1080)))
        assertEquals(1.85f, HlScreen.ratioOf(film(1998, 1080)))
    }

    @Test
    fun unknownPictureFillsTheDisplay() {
        assertEquals(HlScreen.DISPLAY, HlScreen.ratioOf(null))
        assertEquals(HlScreen.DISPLAY, HlScreen.ratioOf(film(null, null)))
        assertEquals(HlScreen.DISPLAY, HlScreen.ratioOf(film(1920, 0)))
    }

    @Test
    fun theRoomGoesDarkBeforeTheScreen() {
        // When black starts, the room has gone and the scrims have lifted.
        val blackStarts = 0.58f
        assertEquals(0f, HouseLightCue.room(blackStarts))
        assertEquals(0f, HouseLightCue.scrims(blackStarts))
        assertEquals(0f, HouseLightCue.darkness(0.3f))
        assertEquals(1f, HouseLightCue.darkness(1f))
        assertEquals(1f, HouseLightCue.room(0f))
    }
}
