package app.houselights.ui.design.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * `board2/second.html`, [SecondBoard]; an NFO's definitions and a synopsis from « Échelle de texte », [TextBoard]),
 * each line's baseline within [CLOSE] of the board's.
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
        assertAt("the title", SecondBoard.TITLE, at("Fiche technique"))
        assertAt("its facts", SecondBoard.FACTS, at("The Furious"))
        val picture = compose.onNode(block("Image")).getUnclippedBoundsInRoot()
        assertAt("the picture's plate", SecondBoard.FIRST_PLATE, picture.top.value)
        val sound = compose.onNode(block("Son")).getUnclippedBoundsInRoot()
        assertAt("the sound's plate, a group's gap under it", picture.bottom.value + SecondBoard.PLATE_GAP, sound.top.value)
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
            NfoSheet(film, NfoState(read), onRetry = {}, onDismiss = {})
        }
        assertAt("the title", SecondBoard.TITLE, at("Fichier NFO"))
        assertAt("what it is", TextBoard.NFO_ABOUT, at("Écrite par votre gestionnaire de médias, elle prime sur TMDB."))
        val title = compose.onNodeWithText("Titre").baseline()
        assertAt("the first definition", TextBoard.FIRST_DEFINITION, title)
        assertAt("the next, a definition's pitch under it", title + TextBoard.DEFINITION_PITCH, at("Année"))
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
        assertAt("its tagline", TextBoard.TAGLINE, at("Ressentez le besoin… Le besoin de vitesse."))
        assertAt("its text", TextBoard.SYNOPSIS, at("Après avoir été l’un des meilleurs pilotes de chasse de l’Aéronavale américaine."))
    }

    @Test
    fun aTitledGroupStandsABandFromThePlateBeforeItAndNoneAtTheTop() {
        var titled by mutableStateOf(false)
        var first by mutableStateOf(false)
        compose.setTvContent {
            HlSideSheet("Feuille", onDismiss = {}) { _ ->
                if (!first) HlSettingGroup(null) { HlSettingRow("Une") }
                HlSettingGroup(if (titled) "Groupe" else null) { HlSettingRow("Deux") }
            }
        }
        val plain = at("Deux")
        titled = true
        compose.waitForIdle()
        assertEquals("a band, not a plate's gap, then the title's box", BAND - PLATES + TITLE_BOX, at("Deux") - plain, HALF)
        first = true
        compose.waitForIdle()
        val alone = at("Deux")
        titled = false
        compose.waitForIdle()
        assertEquals("first, only the title's box", TITLE_BOX, alone - at("Deux"), HALF)
    }

    /** The block read first as [name]. */
    private fun block(name: String) = SemanticsMatcher("the $name block") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("$name, ") == true
    }

    /** `board2/second.html`, in dp from the sheet's top, its title on the screen title's baseline: its head, a technical sheet's plates. */
    private object SecondBoard {
        const val TITLE = 56f
        const val FACTS = 80f
        const val FIRST_PLATE = 103f
        const val PLATE_GAP = 16f
    }

    /**
     * « Échelle de texte », in dp from the sheet's top, its sentence `m` under its head and a synopsis's text `s`
     * under its tagline: what an NFO is and its definitions, a synopsis.
     */
    private object TextBoard {
        const val NFO_ABOUT = 114f
        const val FIRST_DEFINITION = 170f

        /** From one definition's baseline to the next's. */
        const val DEFINITION_PITCH = 37f
        const val TAGLINE = 131f
        const val SYNOPSIS = 159f
    }

    private companion object {
        /** Within 3 dp of the board. */
        const val CLOSE = 3f
        const val HALF = 0.5f

        /** A band, a plate's gap, a group title's box (its baseline `s` under its top and over its plate). */
        const val BAND = 24f
        const val PLATES = 16f
        const val TITLE_BOX = 24f
    }
}
