package app.houselights.player

import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.PlaybackPlan
import app.houselights.core.SubtitleMode
import app.houselights.core.SubtitleTrack
import app.houselights.core.TrackChoice
import app.houselights.core.TrackPick
import app.houselights.core.TrackPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TrackChooserTest {
    /** The app in French on a device in English: the player, asked no audio, looks for English, then French in full fits. */
    @Test
    fun thePlayersOwnAudioIsGuessedInTheDevicesLanguage() = runTest {
        val chooser = TrackChooser(
            preferences = { TrackPreferences(subtitles = SubtitleMode.FOREIGN) },
            locale = { Locale.FRANCE },
            load = { null },
            save = { _, _ -> },
            forget = {},
            deviceLanguage = { "en" },
        )
        val plan = checkNotNull(chooser.plan(LAUNCH))
        assertEquals("en" to 2, plan.expectedAudio?.language to plan.pick.subtitle)
    }

    /**
     * A resume asks Settings: its position may come from elsewhere (a pause Trakt keeps, another Jellyfin client), which
     * the player has no memory of. Last played while the app was stopped, the file is left to the player, which
     * remembers what was chosen in it then; a title's own choice is still asked, alone.
     */
    @Test
    fun aResumeLeavesTheTracksToThePlayersMemoryOnlyOfAFileItAloneFollowed() = runTest {
        var stored: TrackChoice? = null
        val unfollowed = mutableSetOf<String>()
        val chooser = TrackChooser(
            preferences = { TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.ALWAYS) },
            locale = { Locale.FRANCE },
            load = { stored },
            save = { _, _ -> },
            forget = {},
            playersOwn = { it in unfollowed },
        )
        val resumed = LAUNCH.copy(startMs = 1_800_000)
        assertEquals("from the start: Settings", TrackPick(0, 2), chooser.plan(LAUNCH)?.pick)
        assertEquals("resumed: Settings", TrackPick(0, 2), chooser.plan(resumed)?.pick)
        unfollowed += "4"
        assertNull(chooser.plan(resumed))
        assertEquals("from the start: Settings", TrackPick(0, 2), chooser.plan(LAUNCH)?.pick)
        stored = TrackChoice(audio = "en")
        assertEquals(TrackPick(1, null), chooser.plan(resumed)?.pick)
    }

    /** No forced French in the file: none are asked, and the check looks for them beside the video. */
    @Test
    fun subtitlesToLookForBesideTheVideoAreAPlan() = runTest {
        val chooser = TrackChooser({ TrackPreferences(subtitles = SubtitleMode.FOREIGN) }, { Locale.FRANCE }, { null }, { _, _ -> }, {})
        val french = checkNotNull(LAUNCH.media).let { it.copy(subtitles = it.subtitles.filterNot(SubtitleTrack::forced)) }
        val plan = checkNotNull(chooser.plan(LAUNCH.copy(media = french)))
        assertEquals(TrackPick(null, 0) to true, plan.pick to plan.subtitleBeside)
    }

    private companion object {
        val LAUNCH = PlaybackPlan.Launch(
            uri = "nfs://nas/films/Anatomie.mkv", container = "mkv", title = "Anatomie", backdropUrl = null, logoUrl = null,
            startMs = 0, reporter = null, resumeKey = "4", titleKey = "local:4",
            media = MediaInfo(
                container = "MKV",
                probedWith = 5,
                audio = listOf(AudioTrack(Codecs.AC3, 6, "fr"), AudioTrack(Codecs.AC3, 6, "en")),
                subtitles = listOf(SubtitleTrack("fr", format = Codecs.SRT, forced = true), SubtitleTrack("fr", format = Codecs.SRT)),
                tracksInOrder = true,
            ),
        )
    }
}
