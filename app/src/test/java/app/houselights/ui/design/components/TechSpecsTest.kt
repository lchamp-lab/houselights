package app.houselights.ui.design.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.houselights.core.AudioTrack
import app.houselights.core.BaseRange
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SubtitleTrack
import app.houselights.core.TechValue
import app.houselights.core.VideoInfo
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The technical sheet: a fact read whole, its words in the voice of their kind, set from the row's end and whole;
 * the crew after the picture and the sound, three names a craft, and no crew without a name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class TechSpecsTest {
    @get:Rule
    val compose = createComposeRule()

    private val film = MediaItem(
        id = "colony",
        kind = MediaKind.MOVIE,
        title = "Colony",
        year = 2026,
        media = MediaInfo(
            video = VideoInfo(codec = Codecs.HEVC, width = 1920, height = 804, bitDepth = 10),
            audio = listOf(AudioTrack(codec = Codecs.EAC3, channels = 6, language = "kor", default = true)),
            subtitles = listOf(SubtitleTrack(language = "fre", format = Codecs.SRT, default = true)),
        ),
    )

    private val crafts = listOf(
        Craft("Image", listOf("Boris Kaufman", "Louis Berger", "Jean-Paul Alphen", "Albert Viguier")),
        Craft("Musique", emptyList()),
    )

    @Test
    fun theCreditsFollowThePictureAndTheSound() {
        compose.setTvContent { TechSpecs(film, onDismiss = {}, crafts = crafts) }
        // A fact reads as its label, its word as on a case's back, then what qualifies it.
        compose.onNodeWithContentDescription("Resolution, Full HD, 1920\u00A0×\u00A0804").assertExists()
        compose.onNodeWithContentDescription("Image, Boris Kaufman, Louis Berger, Jean-Paul Alphen").assertExists()
        compose.onAllNodesWithContentDescription("Musique", substring = true).assertCountEquals(0)
        fun top(name: String) = compose.onNodeWithText(name).getUnclippedBoundsInRoot().top
        assertTrue("the crew after the sound", top("Audio") < top("Crew"))
        assertTrue("subtitles after the crew", top("Crew") < top("Subtitles"))
    }

    @Test
    fun noCraftNamedNoCrew() {
        compose.setTvContent { TechSpecs(film, onDismiss = {}, crafts = listOf(Craft("Musique", emptyList()))) }
        compose.onAllNodesWithText("Crew").assertCountEquals(0)
        compose.onNodeWithText("Video").assertExists()
    }

    /** Formats as on a case's back, measures and what qualifies them alike, a video's bitrate as an audio track's. */
    @Test
    fun aValueSpeaksInTheVoiceOfItsKind() {
        val formats = listOf(
            TechValue.Word("Ultra HD"), TechValue.Word("Dolby Vision"), TechValue.Word("HDR10"), TechValue.StandardRange,
            TechValue.DolbyVisionProfile("8.1"), TechValue.BaseLayer(BaseRange.HDR10),
        )
        val measures = listOf(
            TechValue.Dimensions(1920, 804), TechValue.Ratio(2.39f), TechValue.FrameRate(23.976f), TechValue.BitDepth(10),
            TechValue.Channels("7.1"), TechValue.Megabits(2_000), TechValue.Kilobits(768), TechValue.Bytes(9_310_000_000),
            TechValue.Duration(7_080_000), TechValue.Forced, TechValue.HearingImpaired,
        )
        formats.forEach { assertEquals("$it", Voice.Format, it.voice) }
        measures.forEach { assertEquals("$it", Voice.Measure, it.voice) }
    }

    @Test
    // Real fonts, so words measure as on the device.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wordsAreSetFromTheEndAndWhole() {
        compose.setTvContent {
            EndWords(Modifier.width(WORDS).testTag("words")) {
                Text("Ultra HD", Modifier.testTag("format"))
                Text("3840 × 1608", Modifier.testTag("size"))
                Text("Dolby TrueHD Atmos", Modifier.testTag("next"))
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val words = bounds("words")
        assertEquals("the line ends at the row's end", words.right.value, bounds("size").right.value, 0.5f)
        assertTrue("a word that does not fit starts the next line", bounds("next").top >= bounds("format").bottom)
        assertEquals("whole, from the end", words.right.value, bounds("next").right.value, 0.5f)
        assertEquals("on one baseline", bounds("format").top.value, bounds("size").top.value, 0.5f)
    }

    @Test
    fun aTitleWithCreditsAndNoFileShowsItsCredits() {
        val show = film.copy(kind = MediaKind.SERIES, media = null)
        compose.setTvContent { TechSpecs(show, onDismiss = {}, crafts = listOf(Craft("Musique", listOf("Daniel Pemberton")))) }
        compose.onNodeWithContentDescription("Musique, Daniel Pemberton").assertExists()
        compose.onAllNodesWithText("Video").assertCountEquals(0)
    }

    private companion object {
        /** Room for two words a fact's space apart, not three. */
        val WORDS = 280.dp
    }
}
