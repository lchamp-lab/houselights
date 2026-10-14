package app.houselights.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

class AuditoriumTest {
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
