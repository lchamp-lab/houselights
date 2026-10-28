package app.houselights.ui.design.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.formatCount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Layout: home's first scan says the folder it reads, the videos its listing found in the file's place, then
 * the Counter with its thousands grouped; « Aller à l'accueil » takes focus as it comes only while nothing has it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class FolderBlockTest {
    @get:Rule
    val compose = createComposeRule()

    private fun text(words: String, substring: Boolean = false) = compose.onNodeWithText(words, substring, useUnmergedTree = true)

    /** While the folder is listed: what it does, nothing before the first video, then how many, a word in the file's place. */
    @Test
    fun whileTheFolderIsListedItsVideosStandInTheFilesPlace() {
        var listed by mutableIntStateOf(0)
        compose.setTvContent { FolderBlock("Films", "DS920", processed = 0, total = 0, protocol = "NFS", listed = listed) }
        text("Reading the folder…").assertExists()
        text("Films").assertExists()
        text("NFS").assertExists()
        text("found…", substring = true).assertDoesNotExist()
        listed = 1
        text("1 video found…").assertExists()
        listed = THOUSANDS
        text("${formatCount(THOUSANDS.toLong())} videos found…").assertExists()
    }

    /** Once the files are counted: the count after the line, its thousands grouped, the file read under it. */
    @Test
    fun onceTheFilesAreCountedTheCountGroupsItsThousands() {
        compose.setTvContent {
            FolderBlock("Films", "DS920", processed = READ, total = FILES, current = "Bugonia (2025).mkv", listed = THOUSANDS)
        }
        text("Scanning the folder…").assertExists()
        text("${formatCount(READ.toLong())}$NBSP/$NBSP${formatCount(FILES.toLong())}").assertExists()
        text("Bugonia (2025).mkv").assertExists()
        text("found…", substring = true).assertDoesNotExist()
    }

    /** Nothing has focus as it comes: it takes it, so OK is enough; it is read out with what goes on meanwhile. */
    @Test
    fun theButtonTakesFocusAsItComesWhileNothingHasIt() {
        compose.setTvContent { GoHomeButton(onClick = {}, focusFree = { true }) }
        compose.onNodeWithContentDescription("Go to Home. The scan continues in the background").assertIsFocused()
        text("The scan continues in the background").assertDoesNotExist()
    }

    /** Focus the viewer placed elsewhere stays there as it comes. */
    @Test
    fun theButtonLeavesFocusWhereTheViewerPlacedIt() {
        compose.setTvContent { GoHomeButton(onClick = {}, focusFree = { false }) }
        compose.onNodeWithText("Go to Home").assertIsNotFocused()
    }

    private companion object {
        const val THOUSANDS = 1_240
        const val READ = 1_204
        const val FILES = 3_871
    }
}
