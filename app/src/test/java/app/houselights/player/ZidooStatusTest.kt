package app.houselights.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZidooStatusTest {
    @Test
    fun aFilmPlaying() {
        val body = """{"status":200,"video":{"status":1,"currentPosition":61000,"duration":7200000,"path":"/mnt/nfs/Heat.mkv"}}"""
        val heat = ZidooPlayState(playing = true, paused = false, positionMs = 61000, durationMs = 7200000, path = "/mnt/nfs/Heat.mkv")
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

    /** Colony's lists as the Zidoo answered them on 2026-10-22, playing Korean without subtitles. */
    @Test
    fun theTracksOfTheFilmPlaying() {
        val (audioIndex, audio) = checkNotNull(ZidooStatus.parseTracks(PlayerAnswers.COLONY_AUDIO))
        assertEquals(1, audioIndex)
        assertEquals(listOf(PlayerTrack(0, "fre"), PlayerTrack(1, "kor")), audio)
        val (subtitleIndex, subtitles) = checkNotNull(ZidooStatus.parseTracks(PlayerAnswers.COLONY_SUBTITLES))
        assertEquals(0, subtitleIndex)
        assertEquals(
            listOf(
                PlayerTrack(0, null),
                PlayerTrack(1, "fre", forced = true),
                PlayerTrack(2, "fre"),
                PlayerTrack(3, "fre", forced = true),
                PlayerTrack(4, "fre"),
                PlayerTrack(5, "fre", hearingImpaired = true),
            ),
            subtitles,
        )
    }

    @Test
    fun noTracksWhenNothingPlays() {
        assertNull(ZidooStatus.parseTracks("""{"status":806,"msg":"The resource does not exist"}"""))
        assertNull(ZidooStatus.parseTracks("not json"))
        assertTrue(ZidooStatus.accepted("""{"status":200}"""))
        assertFalse(ZidooStatus.accepted("""{"status":805,"msg":"The parameter is error or no parameter is submitted"}"""))
    }
}
