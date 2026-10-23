package app.houselights.player

import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.PlaybackPlan
import app.houselights.core.SubtitleMode
import app.houselights.core.SubtitleTrack
import app.houselights.core.TrackPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
