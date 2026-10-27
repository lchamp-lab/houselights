package app.houselights.ui.design.components

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.houselights.core.AudioTrack
import app.houselights.core.BaseRange
import app.houselights.core.Codecs
import app.houselights.core.HdrFormat
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SubtitleTrack
import app.houselights.core.TechValue
import app.houselights.core.VideoInfo
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The technical sheet, read block by block: the picture as marks, each measured under it, then the sound, the
 * subtitles and the file; a block taller than the sheet read through before focus leaves it.
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
            container = "MKV",
            sizeBytes = 9_310_000_000,
        ),
    )

    /** The block read first as [name]. */
    private fun block(name: String) = SemanticsMatcher("the $name block") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("$name, ") == true
    }

    /** What reads exactly as [words]. */
    private fun read(words: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(words))

    private fun top(name: String) = compose.onNode(block(name)).getUnclippedBoundsInRoot().top

    @Test
    fun theBlocksReadInOrder() {
        compose.setTvContent { TechSpecs(film, onDismiss = {}) }
        // A mark reads as its format, then what measures it.
        compose.onAllNodes(read("Video, Full HD, 1920 × 804, 2.39:1, SDR, HEVC, 10-bit"))
            .assertCountEquals(1)
        val tops = listOf("Video", "Audio", "Subtitles", "File").map(::top)
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun theFileIsAFactOfTheHead() {
        compose.setTvContent { TechSpecs(film, onDismiss = {}) }
        compose.onAllNodes(SemanticsMatcher("MKV, 8.67 GB") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("MKV, ") } == true
        }).assertCountEquals(1)
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

    /** Dolby Vision and HDR10+ make one mark, its profile and the other range under it, the ratio under the definition. */
    @Test
    fun aFactLedByAFormatStartsAMark() {
        val video = VideoInfo(
            codec = Codecs.HEVC, width = 3840, height = 1608, bitDepth = 10,
            hdr = listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10_PLUS), dolbyVisionProfile = 8, dolbyVisionCompatibility = 1,
        )
        compose.setTvContent { TechSpecs(film.copy(media = MediaInfo(video = video)), onDismiss = {}) }
        val read = compose.onNode(block("Video")).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
        val order = listOf("Ultra HD", "3840", "2.39:1", "Dolby Vision", "profile 8.1", "HDR10+", "HEVC", "10-bit")
        assertEquals(read, order, order.sortedBy { read.indexOf(it) })
        assertTrue(read, order.all { it in read })
    }

    @Test
    fun aBlockTallerThanTheSheetIsReadThrough() {
        val many = (1..SUBTITLES).map { SubtitleTrack(language = "fre", format = Codecs.SRT, title = "Piste $it") }
        compose.setTvContent { TechSpecs(film.copy(media = film.media!!.copy(subtitles = many)), onDismiss = {}) }
        // Down from the picture to the subtitles, then through them.
        repeat(2) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNode(block("Subtitles")).assertIsFocused()
        var presses = 0
        while (compose.onAllNodes(block("Subtitles") and isFocused()).fetchSemanticsNodes().isNotEmpty() && presses < SUBTITLES) {
            val bottom = compose.onNode(block("Subtitles")).getUnclippedBoundsInRoot().bottom
            compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
            presses++
            if (compose.onAllNodes(block("File") and isFocused()).fetchSemanticsNodes().isNotEmpty()) {
                assertTrue("its foot was shown before focus left: $bottom", bottom.value <= SCREEN - HlSpace.safeV.value)
            }
        }
        assertTrue("several presses read it: $presses", presses > 1)
        compose.onNode(block("File")).assertIsFocused()
    }

    private companion object {
        const val SUBTITLES = 30
        const val SCREEN = 540f
    }
}
