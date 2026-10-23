package app.houselights.player

import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.SubtitleChoice
import app.houselights.core.SubtitleKind
import app.houselights.core.SubtitleMode
import app.houselights.core.SubtitleTrack
import app.houselights.core.TrackChoice
import app.houselights.core.TrackPlan
import app.houselights.core.TrackPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** What to switch once the film plays, from what the Zidoo says it plays (Colony, playing Korean without subtitles). */
class TrackCheckTest {
    private val plays = PlayerTracks(
        audio = checkNotNull(ZidooStatus.parseTracks(PlayerAnswers.COLONY_AUDIO)).second,
        audioIndex = 1,
        subtitles = checkNotNull(ZidooStatus.parseTracks(PlayerAnswers.COLONY_SUBTITLES)).second,
        subtitleIndex = 0,
    )

    /** Colony's lists with a subtitle file beside it listed first: every subtitle moves by one. */
    private val shifted = plays.copy(
        subtitles = listOf(PlayerTrack(0, null), PlayerTrack(1, "fre")) + plays.subtitles.drop(1).map { it.copy(index = it.index + 1) },
    )

    private val forced = TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FORCED)

    private fun plan(preferences: TrackPreferences, choice: TrackChoice? = null, media: MediaInfo = COLONY) =
        TrackPlan(media, preferences, choice, language = "fr", region = "FR", titleKey = "local:3")

    @Test
    fun whatWasAskedPlaysAlready() {
        assertEquals(emptyList<TrackFix>(), TrackCheck.fixes(plan(TrackPreferences(), TrackChoice("ko", SubtitleChoice.Off)), plays))
    }

    /** French and its forced subtitles asked: the player plays other numbers, which only the user can have chosen. */
    @Test
    fun aKindTheUserChangedIsLeftAsItIs() {
        assertEquals(emptyList<TrackFix>(), TrackCheck.fixes(plan(forced), plays))
        assertEquals("subtitles turned off", emptyList<TrackFix>(), TrackCheck.fixes(plan(forced), plays.copy(audioIndex = 0)))
    }

    /** The audio left to the player, which played Korean as it remembered: the subtitles asked follow it. */
    @Test
    fun theSubtitlesFollowTheAudioThePlayerChose() {
        val fixes = TrackCheck.fixes(plan(TrackPreferences(subtitles = SubtitleMode.FOREIGN)), plays.copy(subtitleIndex = 1))
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 2)), fixes)
    }

    @Test
    fun aTrackNumberedOtherwiseIsFoundByLanguageAndKind() {
        // Asked 1, the forced French of the file: the player's 1 is the file beside it.
        val asked = shifted.copy(audioIndex = 0, subtitleIndex = 1)
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 2)), TrackCheck.fixes(plan(forced), asked))
        val sdh = plan(TrackPreferences(), TrackChoice("ko", SubtitleChoice.Track("fr", SubtitleKind.SDH)))
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 6)), TrackCheck.fixes(sdh, shifted.copy(subtitleIndex = 5)))
    }

    @Test
    fun aTrackThePlayerLacksIsLeftAsItIs() {
        val noForced = plays.copy(subtitles = plays.subtitles.filterNot { it.forced }, subtitleIndex = 1)
        assertEquals(emptyList<TrackFix>(), TrackCheck.fixes(plan(TrackPreferences("ko", SubtitleMode.FORCED)), noForced))
    }

    /** A list the player did not give does not keep the other kind from being checked; the audio is then taken as asked. */
    @Test
    fun aMissingListLeavesTheOtherChecked() {
        val noAudio = shifted.copy(audio = emptyList(), audioIndex = null, subtitleIndex = 1)
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 2)), TrackCheck.fixes(plan(forced), noAudio))
        val audioOnly = PlayerTracks(listOf(PlayerTrack(0, "eng"), PlayerTrack(1, "fre"), PlayerTrack(2, "kor")), 1, emptyList(), null)
        assertEquals(listOf(TrackFix(TrackKind.AUDIO, 2)), TrackCheck.fixes(plan(TrackPreferences("ko", SubtitleMode.OFF)), audioOnly))
    }

    /** The VFQ asked, third in the file: numbered otherwise, it is the player's second French track, as in the file. */
    @Test
    fun anAudioNumberedOtherwiseIsTheSameRankInItsLanguage() {
        val listed = listOf(PlayerTrack(0, "ger"), PlayerTrack(1, "fre"), PlayerTrack(2, "eng"), PlayerTrack(3, "fre"))
        val vfq = plan(TrackPreferences(), TrackChoice("fr-CA"), file(VFF, ENGLISH, VFQ))
        assertEquals(listOf(TrackFix(TrackKind.AUDIO, 3)), TrackCheck.fixes(vfq, PlayerTracks(listed, 2, emptyList(), null)))
    }

    /** The file replaced since the scan: a French commentary now comes before the French audio, so nothing is sure. */
    @Test
    fun anAudioOfUncertainRankIsLeftAsItIs() {
        val listed = listOf(PlayerTrack(0, "eng"), PlayerTrack(1, "fre"), PlayerTrack(2, "fre"))
        val french = plan(TrackPreferences(TrackPreferences.INTERFACE), media = file(VFF, ENGLISH))
        assertEquals(emptyList<TrackFix>(), TrackCheck.fixes(french, PlayerTracks(listed, 0, emptyList(), null)))
    }

    /** What the user chose in the player keeps the region of the file's track at that number, when of its language. */
    @Test
    fun aChoiceKeepsTheFilesRegion() {
        val p = plan(TrackPreferences(TrackPreferences.INTERFACE), media = file(VFF, VFQ, ENGLISH))
        val listed = PlayerTracks(listOf(PlayerTrack(0, "fre"), PlayerTrack(1, "fre"), PlayerTrack(2, "eng")), 0, emptyList(), null)
        assertEquals("fr-CA", TrackCheck.choiceOf(p, listed, 1, null, byFile = false).audio)
        val other = listed.copy(audio = listOf(PlayerTrack(0, "eng"), PlayerTrack(1, "fre"), PlayerTrack(2, "fre")))
        assertEquals("another language there: the player's", "en", TrackCheck.choiceOf(p, other, 0, null, byFile = false).audio)
    }

    /** A list the player did not give: read in the file's numbering only where it can be trusted. */
    @Test
    fun aMissingListIsReadInTheFilesNumberingOrNot() {
        val audioOnly = plays.copy(subtitles = emptyList(), subtitleIndex = null)
        val full = SubtitleChoice.Track("fr", SubtitleKind.FULL)
        assertEquals(TrackChoice("ko", full), TrackCheck.choiceOf(plan(forced), audioOnly, 1, 2, byFile = true))
        assertEquals(TrackChoice("ko", null), TrackCheck.choiceOf(plan(forced), audioOnly, 1, 2, byFile = false))
        assertEquals(TrackChoice("ko", full), TrackCheck.choiceOf(plan(forced), null, 1, 2, byFile = true))
    }

    private companion object {
        val VFF = AudioTrack(Codecs.AC3, 6, "fre", "VFF")
        val VFQ = AudioTrack(Codecs.AC3, 6, "fre", "VFQ")
        val ENGLISH = AudioTrack(Codecs.TRUEHD, 8, "eng")

        fun file(vararg audio: AudioTrack) = MediaInfo(audio = audio.toList(), tracksInOrder = true)

        val COLONY = MediaInfo(
            container = "MKV",
            audio = listOf(AudioTrack(Codecs.EAC3, 6, "fr", default = true), AudioTrack(Codecs.EAC3, 6, "ko")),
            subtitles = listOf(
                SubtitleTrack("fr", format = Codecs.SRT, forced = true, default = true),
                SubtitleTrack("fr", format = Codecs.SRT),
                SubtitleTrack("fr", format = Codecs.PGS, forced = true),
                SubtitleTrack("fr", format = Codecs.PGS),
                SubtitleTrack("fr", format = Codecs.PGS, hearingImpaired = true),
            ),
            tracksInOrder = true,
        )
    }
}
