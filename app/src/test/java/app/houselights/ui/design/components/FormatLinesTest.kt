package app.houselights.ui.design.components

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

    @Test
    fun nothingAskedIsTheBestTrackWithoutItsLanguage() {
        compose.setTvContent { FormatLines(furious) }
        compose.onNodeWithText("Dolby Digital Plus Atmos").assertExists()
        val english = Locale.ENGLISH.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase() }
        compose.onNodeWithText(english).assertDoesNotExist()
    }
}
