package app.houselights.ui.design.components

import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.NfoInfo
import app.houselights.core.NfoKind
import app.houselights.core.VideoInfo
import app.houselights.ui.detail.NfoContent
import app.houselights.ui.detail.NfoSheet
import app.houselights.ui.detail.NfoState
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.LoadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Secondary screens: a sheet reads at the boards' rhythm (the head and a technical sheet's blocks from
 * `board2/second.html`; an NFO's definitions and a synopsis from « Échelle de texte »), each line's baseline within
 * [CLOSE] of the board's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
// Real fonts, so baselines fall as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetRhythmTest {
    @get:Rule
    val compose = createComposeRule()

    private fun SemanticsNodeInteraction.baseline(): Float =
        getUnclippedBoundsInRoot().top.value + getAlignmentLinePosition(FirstBaseline).value

    private fun at(text: String) = compose.onNodeWithText(text).baseline()

    private fun assertAt(what: String, board: Float, at: Float) = assertEquals(what, board, at, CLOSE)

    @Test
    fun aTechnicalSheetsHeadAndBlocks() {
        val film = MediaItem(
            "f",
            MediaKind.MOVIE,
            "The Furious",
            year = 2026,
            media = MediaInfo(
                video = VideoInfo(codec = Codecs.HEVC, width = 3832, height = 1592, bitDepth = 10),
                audio = listOf(
                    AudioTrack(codec = Codecs.TRUEHD, channels = 6, language = "eng", default = true),
                    AudioTrack(codec = Codecs.AC3, channels = 6, language = "fre"),
                ),
                container = "MKV",
                sizeBytes = 23_400_000_000,
            ),
        )
        compose.setTvContent { TechSpecs(film, onDismiss = {}) }
        assertAt("the title", 58.7f, at("Fiche technique"))
        assertAt("its facts", 87.35f, at("The Furious"))
        val picture = compose.onNode(block("Image")).getUnclippedBoundsInRoot()
        assertAt("the picture's plate", 110f, picture.top.value)
        val sound = compose.onNode(block("Son")).getUnclippedBoundsInRoot()
        assertAt("the sound's plate, a group's gap under it", (picture.bottom.value + 16f), sound.top.value)
    }

    @Test
    fun anNfosDefinitionsKeepTheirPitch() {
        val info = NfoInfo(
            kind = NfoKind.MOVIE,
            title = "Colony",
            year = 2026,
            runtimeMin = 122,
            genres = listOf("Horreur", "Action", "Science-Fiction"),
            directors = listOf("Yeon Sang-ho"),
        )
        val film = MediaItem("c1", MediaKind.MOVIE, "Colony", year = 2026)
        compose.setTvContent {
            val read = LoadState.Ready(NfoContent("/films/Colony.nfo", info, emptyList()))
            NfoSheet(film, NfoState("Colony", read), onRetry = {}, onDismiss = {})
        }
        assertAt("the title", 58.7f, at("Fichier NFO"))
        assertAt("what it is", 126f, at("Écrite par votre gestionnaire de médias, elle prime sur TMDB."))
        val title = compose.onNodeWithText("Titre").baseline()
        assertAt("the first definition", 179f, title)
        assertAt("the next, a definition's pitch under it", title + PITCH, at("Année"))
        // The year in the head first, then in its definition.
        assertAt("a label and its value, one baseline", at("Année"), compose.onAllNodesWithText("2026")[1].baseline())
    }

    @Test
    fun aSynopsisSetsItsTaglineOverItsText() {
        val film = MediaItem(
            "t",
            MediaKind.MOVIE,
            "Top Gun : Maverick",
            year = 2022,
            tagline = "Ressentez le besoin… Le besoin de vitesse.",
            overview = "Après avoir été l’un des meilleurs pilotes de chasse de l’Aéronavale américaine.",
        )
        compose.setTvContent { SynopsisSheet(film, onDismiss = {}) }
        assertAt("its tagline", 137f, at("Ressentez le besoin… Le besoin de vitesse."))
        assertAt("its text", 179f, at("Après avoir été l’un des meilleurs pilotes de chasse de l’Aéronavale américaine."))
    }

    /** The block read first as [name]. */
    private fun block(name: String) = SemanticsMatcher("the $name block") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("$name, ") == true
    }

    private companion object {
        /** Within 3 dp of the board. */
        const val CLOSE = 3f

        /** An NFO's definitions on the board: their values in `body`, 20 apart. */
        const val PITCH = 37f
    }
}
