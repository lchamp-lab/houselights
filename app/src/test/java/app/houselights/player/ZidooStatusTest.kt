package app.houselights.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ZidooStatusTest {
    @Test
    fun aFilmPlaying() {
        val body = """{"status":200,"video":{"status":1,"currentPosition":61000,"duration":7200000,"path":"/mnt/nfs/Heat.mkv"}}"""
        val heat = ZidooPlayState(playing = true, paused = false, positionMs = 61000, durationMs = 7200000)
        assertEquals(PlayerAnswer.Playing(heat), ZidooStatus.parse(body))
        val paused = """{"status":200,"video":{"status":0,"currentPosition":61000,"duration":7200000}}"""
        assertEquals(true, (ZidooStatus.parse(paused) as PlayerAnswer.Playing).state.paused)
    }

    @Test
    fun nothingPlaying() {
        assertEquals(PlayerAnswer.NothingPlays, ZidooStatus.parse("""{"status":804}"""))
        assertEquals(PlayerAnswer.NothingPlays, ZidooStatus.parse("""{"status":806,"video":{"duration":7200000}}"""))
    }

    /** An answer that makes no sense says nothing of the film: it may well still play. */
    @Test
    fun anAnswerThatMakesNoSense() {
        assertEquals(PlayerAnswer.NoAnswer, ZidooStatus.parse("""{"status":200,"video":{"status":1,"duration":0}}"""))
        assertEquals(PlayerAnswer.NoAnswer, ZidooStatus.parse("""{"status":500}"""))
        assertEquals(PlayerAnswer.NoAnswer, ZidooStatus.parse("not json"))
    }
}
