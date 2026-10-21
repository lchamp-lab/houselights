package app.houselights.ui.design.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SubtitleTrack
import app.houselights.core.VideoInfo
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The technical sheet: a fact read whole, then the credits after the picture and the sound, three names a craft. */
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
        assertTrue("credits after the sound", top("Audio") < top("Credits"))
        assertTrue("subtitles after the credits", top("Credits") < top("Subtitles"))
    }

    @Test
    fun aTitleWithCreditsAndNoFileShowsItsCredits() {
        val show = film.copy(kind = MediaKind.SERIES, media = null)
        compose.setTvContent { TechSpecs(show, onDismiss = {}, crafts = listOf(Craft("Musique", listOf("Daniel Pemberton")))) }
        compose.onNodeWithContentDescription("Musique, Daniel Pemberton").assertExists()
        compose.onAllNodesWithText("Video").assertCountEquals(0)
    }
}
