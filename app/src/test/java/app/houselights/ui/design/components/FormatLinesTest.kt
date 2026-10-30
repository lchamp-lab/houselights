package app.houselights.ui.design.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The Son line of a title's plate: the track Lire asks for, with its language, else the file's best track. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class FormatLinesTest {
    @get:Rule
    val compose = createComposeRule()

    /** The Furious: a French Dolby Digital dub, and the English Dolby Digital Plus Atmos, its best track. */
    private val french = AudioTrack(Codecs.AC3, 6, "fr-FR", default = true, bitrateKbps = 384)
    private val furious = MediaItem(
        id = "furious",
        kind = MediaKind.MOVIE,
        title = "The Furious",
        media = MediaInfo(audio = listOf(french, AudioTrack(Codecs.EAC3, 6, "en", atmos = true, bitrateKbps = 768))),
    )

    @Test
    fun theSoundIsWhatLirePlays() {
        compose.setTvContent { FormatLines(furious, plays = french) }
        compose.onNodeWithText("Dolby Digital").assertExists()
        compose.onNodeWithText(Locale.FRENCH.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase() }).assertExists()
        compose.onNodeWithText("Dolby Digital Plus Atmos").assertDoesNotExist()
    }

    /** Without the file's media info, the plate reads the title's badges as they are now: a file relinked, its own. */
    @Test
    fun badgesChangedShowTheirFormats() {
        var item by mutableStateOf(MediaItem(id = "heat", kind = MediaKind.MOVIE, title = "Heat", badges = listOf("1080p", "5.1")))
        compose.setTvContent { FormatLines(item) }
        compose.onNodeWithText("Full HD").assertExists()

        item = item.copy(badges = listOf("4K", "7.1"))
        compose.waitForIdle()
        compose.onNodeWithText("Ultra HD").assertExists()
        compose.onNodeWithText("7.1").assertExists()
        compose.onNodeWithText("Full HD").assertDoesNotExist()
    }

    @Test
    fun nothingAskedIsTheBestTrackWithoutItsLanguage() {
        compose.setTvContent { FormatLines(furious) }
        compose.onNodeWithText("Dolby Digital Plus Atmos").assertExists()
        val english = Locale.ENGLISH.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase() }
        compose.onNodeWithText(english).assertDoesNotExist()
    }
}
