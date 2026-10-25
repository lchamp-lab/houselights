package app.houselights.ui.detail

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.tv.material3.Text
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
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
import app.houselights.player.TrackChooser
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.design.components.tracksHint
import app.houselights.ui.util.PlaybackOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Locale

/** A film with a choice of tracks: its tile, its sheet, and what choosing keeps. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class DetailTracksTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val provider = FakeProvider().apply { items["colony"] = film("colony", "Colony") }

    private var plan: TrackPlan? = TrackPlan(COLONY, TrackPreferences(), null, "fr", "FR", "local:colony")
    /** What was stored for the title, and what each choice made of it. */
    private val stored = TrackChoice(subtitles = SubtitleChoice.Off)
    private val kept = mutableListOf<Pair<String, TrackChoice>>()
    private var keeping: Result<Unit> = Result.success(Unit)

    @After
    fun stop() = work.cancel()

    private fun text(id: Int) = context.getString(id)

    private fun page(
        keepTracks: suspend (String, (TrackChoice) -> TrackChoice) -> Unit = { key, change ->
            keeping.getOrThrow()
            kept += key to change(stored)
        },
    ): DetailViewModel {
        val vm = DetailViewModel("colony", { provider }, FakeEvents(), work, tracksOf = { plan }, keepTracks = keepTracks)
        compose.setTvContent {
            DetailPage(
                vm,
                PlaybackOutcome(MutableSharedFlow()),
                screen = "page",
                onPlay = { _, _, _ -> },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = {},
                onOpenPerson = {},
            )
        }
        compose.waitForIdle()
        return vm
    }

    /** The sheet open, focus on its first row: "As in Settings" for the audio. */
    private fun sheetOpen() {
        compose.onNodeWithContentDescription(text(R.string.tracks)).requestFocus()
        compose.press(Key.DirectionCenter)
    }

    @Test
    fun choosingATrackKeepsItForTheTitle() {
        val vm = page()
        sheetOpen()
        compose.onNodeWithText(text(R.string.tracks_for_film)).assertExists()
        // Down to the second audio track, Korean.
        repeat(2) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        // The subtitles changed in the player since the page was read are kept.
        assertEquals(listOf("local:colony" to TrackChoice(audio = "ko", subtitles = SubtitleChoice.Off)), kept)
        assertEquals(1, vm.state.value.tracks?.audio)
    }

    @Test
    fun aChoiceNotKeptIsUndoneAndSaidWhy() {
        plan = plan?.copy(choice = TrackChoice(subtitles = SubtitleChoice.Off))
        keeping = Result.failure(IOException("Disk busy"))
        val vm = page()
        vm.chooseTracks(TrackChoice(audio = "ko", subtitles = SubtitleChoice.Off))
        compose.waitForIdle()
        assertEquals(TrackChoice(subtitles = SubtitleChoice.Off), vm.state.value.tracks?.choice)
        assertEquals(true, vm.state.value.actionError != null)
    }

    /** Two choices in quick succession while the store is slow: the second reads what the first wrote. */
    @Test
    fun twoQuickChoicesAreBothKept() {
        var saved: TrackChoice? = null
        val slow = CompletableDeferred<Unit>()
        val chooser = TrackChooser(
            preferences = { TrackPreferences() },
            locale = { Locale.FRANCE },
            load = { saved },
            save = { _, choice ->
                slow.await()
                saved = choice
            },
            forget = { saved = null },
        )
        val vm = page(chooser::choose)
        vm.chooseTracks(TrackChoice(audio = "ko"))
        vm.chooseTracks(TrackChoice(audio = "ko", subtitles = SubtitleChoice.Off))
        slow.complete(Unit)
        compose.waitForIdle()
        assertEquals(TrackChoice("ko", SubtitleChoice.Off), saved)
    }

    /** A choice that could not be kept is undone only while the page shows it, never over one made since. */
    @Test
    fun aChoiceNotKeptLeavesALaterOneShown() {
        val first = CompletableDeferred<Unit>()
        var calls = 0
        val vm = page { key, change ->
            if (++calls == 1) {
                first.await()
                throw IOException("Disk busy")
            }
            kept += key to change(stored)
        }
        val full = TrackChoice("ko", SubtitleChoice.Track("fr", SubtitleKind.FULL))
        vm.chooseTracks(TrackChoice(audio = "ko"))
        vm.chooseTracks(full)
        first.complete(Unit)
        compose.waitForIdle()
        assertEquals(full, vm.state.value.tracks?.choice)
        assertEquals(true, vm.state.value.actionError != null)
    }

    @Test
    fun aSingleTrackHasNoTile() {
        plan = plan?.copy(media = COLONY.copy(audio = COLONY.audio.take(1), subtitles = emptyList()))
        page()
        compose.onNodeWithContentDescription(text(R.string.tracks)).assertDoesNotExist()
    }

    @Test
    fun tracksInAnUnknownOrderHaveNoTile() {
        plan = null
        page()
        compose.onNodeWithContentDescription(text(R.string.tracks)).assertDoesNotExist()
    }

    /** Settings ask for French and its forced subtitles: the tile's second line says so before Lire. */
    @Test
    fun theTileSaysWhatLirePlays() {
        val asked = checkNotNull(plan).copy(preferences = TrackPreferences(TrackPreferences.INTERFACE, SubtitleMode.FOREIGN))
        compose.setTvContent { Text(tracksHint(asked)) }
        val french = Locale.FRENCH.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        compose.onNodeWithText(context.getString(R.string.tracks_hint_pair, french, text(R.string.tracks_hint_forced))).assertExists()
    }

    @Test
    fun nothingAskedIsThePlayersChoice() {
        compose.setTvContent { Text(tracksHint(checkNotNull(plan))) }
        compose.onNodeWithText(text(R.string.tracks_player)).assertExists()
    }

    private companion object {
        val COLONY = MediaInfo(
            container = "MKV",
            audio = listOf(AudioTrack(Codecs.EAC3, 6, "fre", default = true), AudioTrack(Codecs.EAC3, 6, "kor")),
            subtitles = listOf(
                SubtitleTrack("fre", format = Codecs.SRT, forced = true, default = true),
                SubtitleTrack("fre", format = Codecs.SRT),
            ),
            tracksInOrder = true,
        )
    }
}
