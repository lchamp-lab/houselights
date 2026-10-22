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

    private fun plan(preferences: TrackPreferences, choice: TrackChoice? = null) =
        TrackPlan(COLONY, preferences, choice, language = "fr", region = "FR", titleKey = "local:3")

    @Test
    fun whatWasAskedPlaysAlready() {
        assertEquals(emptyList<TrackFix>(), TrackCheck.fixes(plan(TrackPreferences(), TrackChoice("ko", SubtitleChoice.Off)), plays))
    }

    @Test
    fun otherTracksAreSwitched() {
        val french = TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FORCED)
        assertEquals(listOf(TrackFix(TrackKind.AUDIO, 0), TrackFix(TrackKind.SUBTITLE, 1)), TrackCheck.fixes(plan(french), plays))
    }

    /** The audio left to the player, which played Korean as it remembered: the subtitles follow. */
    @Test
    fun theSubtitlesFollowTheAudioThePlayerChose() {
        val fixes = TrackCheck.fixes(plan(TrackPreferences(subtitles = SubtitleMode.FOREIGN)), plays)
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 2)), fixes)
    }

    @Test
    fun aTrackNumberedOtherwiseIsFoundByLanguageAndKind() {
        // A file beside the video, listed first: every number moves by one.
        val shifted = plays.copy(
            subtitles = listOf(PlayerTrack(0, null), PlayerTrack(1, "fre")) +
                plays.subtitles.drop(1).map { it.copy(index = it.index + 1) },
        )
        val forced = TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FORCED)
        assertEquals(TrackFix(TrackKind.SUBTITLE, 2), TrackCheck.fixes(plan(forced), shifted).last())
        val sdh = TrackChoice("ko", SubtitleChoice.Track("fr", SubtitleKind.SDH))
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 6)), TrackCheck.fixes(plan(TrackPreferences(), sdh), shifted))
    }

    @Test
    fun aTrackThePlayerLacksIsLeftAsItIs() {
        val noForced = plays.copy(subtitles = plays.subtitles.filterNot { it.forced })
        val forced = TrackPreferences("ko", SubtitleMode.FORCED)
        assertEquals(emptyList<TrackFix>(), TrackCheck.fixes(plan(forced), noForced))
    }

    private companion object {
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
