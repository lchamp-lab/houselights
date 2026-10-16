package app.houselights.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZidooStatusTest {
    @Test
    fun aFilmPlaying() {
        val body = """{"status":200,"video":{"status":1,"currentPosition":61000,"duration":7200000,"path":"/mnt/nfs/Heat.mkv"}}"""
        assertEquals(ZidooPlayState(playing = true, paused = false, positionMs = 61000, durationMs = 7200000), ZidooStatus.parse(body))
        val paused = """{"status":200,"video":{"status":0,"currentPosition":61000,"duration":7200000}}"""
        assertEquals(true, ZidooStatus.parse(paused)?.paused)
    }

    @Test
    fun nothingPlaying() {
        assertNull(ZidooStatus.parse("""{"status":804}"""))
        assertNull(ZidooStatus.parse("""{"status":806,"video":{"duration":7200000}}"""))
        assertNull(ZidooStatus.parse("""{"status":200,"video":{"status":1,"duration":0}}"""))
        assertNull(ZidooStatus.parse("not json"))
    }
}
