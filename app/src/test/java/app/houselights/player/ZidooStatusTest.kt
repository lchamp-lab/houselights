package app.houselights.player

import app.houselights.core.TrackPlan
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

    /** A file beside the video, which the player names by its file: its language and kind are read there. */
    @Test
    fun aFileBesideTheVideoIsReadByItsName() {
        val (_, subtitles) = checkNotNull(ZidooStatus.parseTracks(PlayerAnswers.COLONY_BESIDE_SUBTITLES))
        assertEquals(PlayerTrack(4, "fr", forced = true), subtitles.last())
    }

    /** "Forcé" is read in capitals and as a Mac writes it, its accent apart. */
    @Test
    fun forcedIsReadHoweverItIsWritten() {
        listOf("Film.fr.FORCÉ.srt", "Film.fr.force\u0301.srt", "Film.fr.Forced.srt").forEach { name ->
            val body = """{"status":200,"currentIndex":0,"subtitles":[{"title":"$name","laungaue":"","index":1}]}"""
            assertEquals(name, PlayerTrack(1, "fr", forced = true), checkNotNull(ZidooStatus.parseTracks(body)).second.single())
        }
    }

    /** One list the player did not give leaves the other, which can still be checked. */
    @Test
    fun eachListIsReadOnItsOwn() {
        val subtitles = checkNotNull(ZidooStatus.lists(null, PlayerAnswers.COLONY_SUBTITLES))
        assertEquals(null to emptyList<PlayerTrack>(), subtitles.audioIndex to subtitles.audio)
        assertEquals(0 to 6, subtitles.subtitleIndex to subtitles.subtitles.size)
        val audio = checkNotNull(ZidooStatus.lists(PlayerAnswers.COLONY_AUDIO, """{"status":806}"""))
        assertEquals(1 to null, audio.audioIndex to audio.subtitleIndex)
        assertNull(ZidooStatus.lists(null, "not json"))
        assertEquals("a list read later completes them", true, subtitles.with(audio).complete)
    }

    /** Lists the player answers before it has read the file are not given yet, though numbered 0. */
    @Test
    fun emptyListsAreNotGivenYet() {
        assertNull(ZidooStatus.parseTracks(PlayerAnswers.NOT_READ_YET))
        assertNull(ZidooStatus.lists(PlayerAnswers.NOT_READ_YET, PlayerAnswers.NOT_READ_YET))
        val subtitles = checkNotNull(ZidooStatus.lists(PlayerAnswers.NOT_READ_YET, PlayerAnswers.COLONY_SUBTITLES))
        assertEquals(null to false, subtitles.audioIndex to subtitles.complete)
    }

    /** The player's audio setting as Settings.System numbers it (28: fr-FR on the Z1000 Pro). */
    @Test
    fun thePlayersAudioSetting() {
        assertEquals("fr-FR", ZidooSettings.audio(28, -1))
        assertEquals("af-ZA", ZidooSettings.audio(3, -1))
        assertEquals("jpn", ZidooSettings.audio(91, -1))
        assertEquals(TrackPlan.PLAYER_DEFAULT, ZidooSettings.audio(1, -1))
        assertEquals(TrackPlan.PLAYER_FIRST, ZidooSettings.audio(2, -1))
        assertEquals("the older setting, numbered one less", "fr-FR", ZidooSettings.audio(-1, 27))
        listOf(0 to -1, -1 to -1, -1 to 0, 92 to -1).forEach { (index, old) -> assertNull(ZidooSettings.audio(index, old)) }
    }

    /** The firmware the journal's head names, from getModel's answer; nothing when it does not say. */
    @Test
    fun theFirmware() {
        assertEquals("6.4.68", ZidooStatus.firmware("""{"status":200,"model":"Z1000 Pro","firmware":"v6.4.68","language":"fr"}"""))
        assertEquals("6.4.42", ZidooStatus.firmware("""{"status":200,"firmware":" 6.4.42 "}"""))
        listOf("""{"status":200,"model":"Z9X"}""", """{"firmware":""}""", """{"firmware":{"v":1}}""", "not json").forEach {
            assertNull(it, ZidooStatus.firmware(it))
        }
    }

    @Test
    fun noTracksWhenNothingPlays() {
        assertNull(ZidooStatus.parseTracks("""{"status":806,"msg":"The resource does not exist"}"""))
        assertNull(ZidooStatus.parseTracks("not json"))
        assertTrue(ZidooStatus.accepted("""{"status":200}"""))
        assertFalse(ZidooStatus.accepted("""{"status":805,"msg":"The parameter is error or no parameter is submitted"}"""))
    }
}
