package app.houselights.player

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
import app.houselights.core.SubtitleChoice
import app.houselights.core.SubtitleKind
import app.houselights.core.SubtitleMode
import app.houselights.core.SubtitleTrack
import app.houselights.core.TrackChoice
import app.houselights.core.TrackPlan
import app.houselights.core.TrackPreferences
import app.houselights.player.FakeApps.Companion.GALLERY
import app.houselights.player.FakeApps.Companion.MOVIE_ACTIVITY
import app.houselights.player.FakeApps.Companion.Z1000_PRO_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_KEY
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The tracks asked of the Zidoo player, checked once it plays, and what the user changes in it kept for the title. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrackFollowTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** What the player plays when asked ([ZidooStatus.tracks]); null: its API does not answer. */
    private var plays: PlayerTracks? = null
    private var listed = 0
    private val switched = mutableListOf<TrackFix>()
    private val kept = mutableListOf<Pair<String, TrackChoice>>()

    /** The intent the player was launched with. */
    private var launched: Intent? = null

    @Before
    fun zidoo() {
        val schemes = listOf("http", "file", "content")
        FakeApps(context).install(GALLERY, MOVIE_ACTIVITY, system = true, certificate = ZIDOO_KEY, schemes = schemes)
    }

    /** The player launched on [plan] with [tracks] asked, then playing what [file] says at each status: length and path. */
    private fun TestScope.started(
        tracks: TrackPlan?,
        plan: PlaybackPlan.Launch = PLAN,
        file: () -> Pair<Long, String?> = { FILM to null },
    ) = PlaybackManager(
        context, this,
        status = {
            val (length, path) = file()
            PlayerAnswer.Playing(ZidooPlayState(playing = true, paused = false, positionMs = currentTime, durationMs = length, path = path))
        },
        clock = { currentTime },
        players = ZidooPlayer(context) { Z1000_PRO_BUILD },
        tracks = {
            listed++
            plays
        },
        select = { kind, index ->
            switched += TrackFix(kind, index)
            true
        },
        remember = { key, choice -> kept += key to choice },
    ) { null }.apply {
        launched = intentFor(plan, tracks)
        onPlayerLaunched()
    }

    private fun plan(preferences: TrackPreferences, player: String? = null) =
        TrackPlan(COLONY, preferences, null, "fr", "FR", "local:3", playerAudio = player)

    /** Closes the player, which says it played [audio] and [subtitle]. */
    private fun TestScope.closed(manager: PlaybackManager, audio: Int, subtitle: Int) {
        manager.onPlayerResult(Intent().putExtra("position", 60_000).putExtra("audio_idx", audio).putExtra("subtitle_idx", subtitle))
        advanceUntilIdle()
    }

    /**
     * The audio left to the player, which remembered Korean; the forced French subtitles were asked for the French
     * it was expected to play: once it plays, they are switched to full ones.
     */
    @Test
    fun theTracksAreCheckedOnceAfterThePlayersOwnChoice() = runTest {
        plays = colony(audio = 1, subtitle = 1)
        val manager = started(plan(TrackPreferences(subtitles = SubtitleMode.FOREIGN)))
        advanceTimeBy(4_000 + 2_000L + 1)
        assertEquals("not before its third status", 0, listed)
        advanceTimeBy(2_000)
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 2)), switched)
        advanceTimeBy(2_000 * 10L)
        assertEquals("once, read again once switched", 2, listed)
        closed(manager, audio = 1, subtitle = 2)
        assertEquals("what was asked, played to the end", emptyList<Pair<String, TrackChoice>>(), kept)
    }

    /**
     * The player set to French, the subtitles in "if the audio is in another language": its forced ones are asked.
     * The user turns them off before the check: they stay off, and are kept so for the title.
     */
    @Test
    fun subtitlesTheUserTurnedOffBeforeTheCheckStayOff() = runTest {
        plays = colony(audio = 0, subtitle = 0)
        val asked = plan(TrackPreferences(subtitles = SubtitleMode.FOREIGN), player = "fr-FR")
        assertEquals(1, asked.pick.subtitle)
        val manager = started(asked)
        advanceTimeBy(4_000 + 2_000 * 5L)
        assertEquals(emptyList<TrackFix>(), switched)
        closed(manager, audio = 0, subtitle = 0)
        assertEquals(listOf("local:3" to TrackChoice(subtitles = SubtitleChoice.Off)), kept)
    }

    /**
     * Colony without its forced French, the film in French: none asked, never the player's first meanwhile, then the
     * forced French beside the video are switched to; the user leaves them for the French in full, kept for the title.
     */
    @Test
    fun subtitlesBesideTheVideoAreSwitchedToOnceListed() = runTest {
        val listed = checkNotNull(ZidooStatus.parseTracks(PlayerAnswers.COLONY_BESIDE_SUBTITLES)).second
        plays = colony(audio = 0, subtitle = 0).copy(subtitles = listed)
        val beside = COLONY.copy(subtitles = COLONY.subtitles.filterNot { it.forced })
        val foreign = TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FOREIGN)
        val manager = started(TrackPlan(beside, foreign, null, "fr", "FR", "local:3"))
        assertEquals(0 to 0, launched?.getIntExtra("audio_idx", -1) to launched?.getIntExtra("subtitle_idx", -1))
        advanceTimeBy(4_000 + 2_000 * 5L)
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 4)), switched)
        closed(manager, audio = 0, subtitle = 2)
        assertEquals(listOf("local:3" to TrackChoice(subtitles = SubtitleChoice.Track("fr", SubtitleKind.FULL))), kept)
    }

    /** Lists the player does not give yet are asked again at the next statuses. */
    @Test
    fun theListsAreAskedAgainUntilThePlayerGivesThem() = runTest {
        val manager = started(plan(TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FORCED)))
        advanceTimeBy(4_000 + 2_000 * 3L + 1)
        assertEquals(2 to emptyList<TrackFix>(), listed to switched)
        plays = shifted(audio = 0, subtitle = 1)
        advanceTimeBy(2_000)
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 2)), switched)
        advanceTimeBy(2_000 * 10L)
        assertEquals("read, then again once switched; no more", 4, listed)
        closed(manager, audio = 0, subtitle = 2)
    }

    /** Before it has read the file, the player answers each list empty: they are asked again, then checked. */
    @Test
    fun emptyListsAreAskedAgain() = runTest {
        plays = ZidooStatus.lists(PlayerAnswers.NOT_READ_YET, PlayerAnswers.NOT_READ_YET)
        val manager = started(plan(TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FORCED)))
        advanceTimeBy(4_000 + 2_000 * 2L + 1)
        assertEquals(1 to emptyList<TrackFix>(), listed to switched)
        plays = shifted(audio = 0, subtitle = 1)
        advanceTimeBy(2_000 * 3L)
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 2)), switched)
        closed(manager, audio = 0, subtitle = 3)
        assertEquals(listOf("local:3" to TrackChoice(subtitles = SubtitleChoice.Track("fr", SubtitleKind.FULL))), kept)
    }

    /**
     * The audio left to the player, which remembered Korean, its list missing at first: the subtitles wait for it,
     * then follow the Korean in full, not the French guessed. An English file beside the video moves them by one.
     */
    @Test
    fun theSubtitlesWaitForTheAudioList() = runTest {
        val both = shifted(audio = 1, subtitle = 1, beside = "eng")
        plays = both.copy(audio = emptyList(), audioIndex = null)
        val manager = started(plan(TrackPreferences(subtitles = SubtitleMode.FOREIGN)))
        advanceTimeBy(4_000 + 2_000 * 2L + 1)
        assertEquals(1 to emptyList<TrackFix>(), listed to switched)
        plays = both
        advanceTimeBy(2_000 * 3L)
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 3)), switched)
        closed(manager, audio = 1, subtitle = 3)
    }

    /** One list at a time: the subtitles are checked once a read gives both, against the audio playing with them. */
    @Test
    fun theSubtitlesAreCheckedWithTheAudioReadAlongside() = runTest {
        val both = colony(audio = 1, subtitle = 1)
        plays = both.copy(subtitles = emptyList(), subtitleIndex = null)
        val manager = started(plan(TrackPreferences(subtitles = SubtitleMode.FOREIGN)))
        advanceTimeBy(4_000 + 2_000 * 2L + 1)
        plays = both.copy(audio = emptyList(), audioIndex = null)
        advanceTimeBy(2_000)
        assertEquals(2 to emptyList<TrackFix>(), listed to switched)
        plays = both
        advanceTimeBy(2_000 * 3L)
        assertEquals(listOf(TrackFix(TrackKind.SUBTITLE, 2)), switched)
        closed(manager, audio = 1, subtitle = 2)
    }

    @Test
    fun whatTheUserChangesIsKeptForTheTitle() = runTest {
        plays = colony(audio = 0, subtitle = 1)
        val manager = started(plan(TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FORCED)))
        advanceTimeBy(4_000 + 2_000 * 5L)
        closed(manager, audio = 1, subtitle = 0)
        assertEquals(listOf("local:3" to TrackChoice("ko", SubtitleChoice.Off)), kept)
    }

    /** Only the subtitles were asked: the audio stays the player's to choose, and is not kept. */
    @Test
    fun onlyWhatWasAskedIsKept() = runTest {
        plays = colony(audio = 0, subtitle = 1)
        val manager = started(plan(TrackPreferences(subtitles = SubtitleMode.FORCED)))
        advanceTimeBy(4_000 + 2_000 * 5L)
        closed(manager, audio = 1, subtitle = 1)
        assertEquals(emptyList<Pair<String, TrackChoice>>(), kept)
    }

    /**
     * Its API out of reach five times: nothing is switched, and what the user chose cannot be read, the player
     * perhaps numbering a subtitle file beside the video the scan did not see.
     */
    @Test
    fun withoutItsListsNothingIsKept() = runTest {
        val manager = started(plan(TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.OFF)))
        advanceTimeBy(4_000 + 2_000 * 8L)
        assertEquals(5 to emptyList<TrackFix>(), listed to switched)
        closed(manager, audio = 0, subtitle = 2)
        assertEquals(emptyList<Pair<String, TrackChoice>>(), kept)
    }

    /** Closed before the check: the file's numbering stands for the player's. */
    @Test
    fun beforeTheCheckTheFilesNumbersStand() = runTest {
        plays = colony(audio = 0, subtitle = 0)
        val manager = started(plan(TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.OFF)))
        advanceTimeBy(4_000 + 2_000L)
        closed(manager, audio = 0, subtitle = 2)
        assertEquals(0, listed)
        assertEquals(listOf("local:3" to TrackChoice(subtitles = SubtitleChoice.Track("fr", SubtitleKind.FULL))), kept)
    }

    /** The player's own numbers: a file beside the video listed first moves every subtitle by one. */
    @Test
    fun whatIsKeptIsReadInThePlayersNumbers() = runTest {
        plays = shifted(audio = 0, subtitle = 1)
        val manager = started(plan(TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FORCED)))
        advanceTimeBy(4_000 + 2_000 * 5L)
        assertEquals("forced French, the player's 2", listOf(TrackFix(TrackKind.SUBTITLE, 2)), switched)
        closed(manager, audio = 0, subtitle = 3)
        assertEquals(listOf("local:3" to TrackChoice(subtitles = SubtitleChoice.Track("fr", SubtitleKind.FULL))), kept)
    }

    /** At the end, the player went on to the folder's next file and the user chose its tracks: not this title's. */
    @Test
    fun anotherFilesTracksAreNotKept() = runTest {
        plays = colony(audio = 0, subtitle = 1)
        val manager = started(plan(TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FORCED)))
        advanceTimeBy(4_000 + 2_000 * 5L)
        val next = "/mnt/nfs/192.168.1.20#volume1_media/films/Bugonia.mkv"
        manager.onPlayerResult(Intent().putExtra("url", next).putExtra("subtitle_idx", 0))
        advanceUntilIdle()
        assertEquals(emptyList<Pair<String, TrackChoice>>(), kept)
    }

    /** The player went on to the film's second part by itself: it plays its own tracks there, neither checked nor kept. */
    @Test
    fun aNextPartIsThePlayers() = runTest {
        plays = colony(audio = 1, subtitle = 0)
        val second = object : PlaybackReporter {
            override suspend fun started(positionMs: Long) = Unit
            override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) = Unit
            override suspend fun stopped(positionMs: Long, durationMs: Long?) = Unit
        }
        val first = object : PlaybackReporter by second {
            override suspend fun next() = PlaybackReporter.Next("films/Colony CD2.mkv", second, "3:2")
        }
        var heard = 0
        val manager = started(plan(TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FORCED)), plan = PLAN.copy(reporter = first)) {
            if (++heard < 2) FILM to "/mnt/nfs/films/Colony CD1.mkv" else PART to "/mnt/nfs/films/Colony CD2.mkv"
        }
        advanceTimeBy(4_000 + 2_000 * 5L)
        assertEquals(0 to emptyList<TrackFix>(), listed to switched)
        closed(manager, audio = 1, subtitle = 1)
        assertEquals(emptyList<Pair<String, TrackChoice>>(), kept)
    }

    @Test
    fun nothingAskedNothingChecked() = runTest {
        plays = colony(audio = 1, subtitle = 0)
        val manager = started(null)
        advanceTimeBy(4_000 + 2_000 * 5L)
        closed(manager, audio = 0, subtitle = 1)
        assertEquals(0 to emptyList<Pair<String, TrackChoice>>(), listed to kept)
    }

    /** Colony's lists with a subtitle file beside it, in [beside], listed first. */
    private fun shifted(audio: Int, subtitle: Int, beside: String = "fre") = colony(audio, subtitle).let { c ->
        c.copy(subtitles = listOf(PlayerTrack(0, null), PlayerTrack(1, beside)) + c.subtitles.drop(1).map { it.copy(index = it.index + 1) })
    }

    private fun colony(audio: Int, subtitle: Int) = PlayerTracks(
        audio = checkNotNull(ZidooStatus.parseTracks(PlayerAnswers.COLONY_AUDIO)).second,
        audioIndex = audio,
        subtitles = checkNotNull(ZidooStatus.parseTracks(PlayerAnswers.COLONY_SUBTITLES)).second,
        subtitleIndex = subtitle,
    )

    private companion object {
        const val FILM = 7_355_000L
        const val PART = 2_400_000L

        val PLAN = PlaybackPlan.Launch(
            uri = "nfs://nas/films/Colony.mkv", container = "mkv", title = "Colony", backdropUrl = null, logoUrl = null, startMs = 0,
            reporter = null, resumeKey = "3",
        )

        /** As the scan read it from the file. */
        val COLONY = MediaInfo(
            container = "MKV",
            probedWith = 5,
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
