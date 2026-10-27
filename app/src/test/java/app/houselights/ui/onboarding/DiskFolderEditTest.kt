package app.houselights.ui.onboarding

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import app.houselights.R
import app.houselights.providers.local.ItemEntity
import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.Standing
import app.houselights.providers.local.Volume
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.DISKS
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.T7
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.press
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * « Modifier le dossier » on a folder of the player's own disks: the flow opened at step 5 on its disk where the folder
 * is, Back up to the disk's root then out, another folder chosen saved as the same folder (its id, its kind, its titles
 * and what was done with them), the library's other folders no choice, and a disk unplugged said on its row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class DiskFolderEditTest : FlowHarness() {
    /** Films on the USB disk « Samsung T7 », the folder edited. */
    private val onT7 = SourceEntity(
        id = EDITED, name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.LOCAL, host = T7, share = "", path = "Films",
        username = "", password = Secret(""),
    )

    /**
     * The edit opens on the folder's disk where it is, « Choisir ce dossier » focused, as « Modifier le dossier » over
     * the sentence saying what it keeps, the panel naming the disk and the folder's kind; no NAS is asked. Back goes up
     * to the disk's root, focus on the folder left, then leaves.
     */
    @Test
    fun theEditOpensWhereTheFolderIsAndBackLeavesFromTheDisksRoot() {
        network.mounted = DISKS
        val flow = flow(onT7, editing = EDITED)
        assertEquals(FlowStep.BROWSE, flow.step)
        assertEquals(listOf("Films"), flow.path)
        focusIsOn(text(R.string.choose_folder))
        compose.onNodeWithText(text(R.string.edit_folder)).assertExists()
        compose.onNodeWithText(text(R.string.flow_edit_desc, "Films")).assertExists()
        compose.onNodeWithText(text(R.string.on_this_player)).assertExists()
        compose.onNodeWithText("Samsung T7").assertExists()
        val kind = compose.onAllNodesWithText(text(R.string.library_movies), substring = true, useUnmergedTree = true)
        assertTrue("its kind in the panel", kind.fetchSemanticsNodes().isNotEmpty())
        assertTrue("${network.asked}", network.asked.none { it.startsWith("protocols") || it.startsWith("shares") })

        back()
        until { flow.path.isEmpty() }
        compose.waitForIdle()
        focusIsOn("Films")
        assertFalse(left)
        back()
        assertTrue(left)
        assertNull(done)
    }

    /**
     * Another folder chosen is the folder edited, saved at once with no step 6, its kind kept: the same id, its titles
     * and what was done with them as they were, the flow done on it as an add is.
     */
    @Test
    fun anotherFolderChosenIsTheSameFolderWithItsTitles() {
        network.mounted = DISKS
        val flow = flow(onT7, editing = EDITED)
        val alien = runBlocking {
            db.dao().insert(
                ItemEntity(
                    kind = ItemKind.MOVIE, sourceId = EDITED, title = "Alien", sortTitle = "alien", filePath = "Alien (1979)/Alien.mkv",
                    addedAt = 0, positionMs = 1_800_000, favorite = true,
                ),
            )
        }
        back()
        until { flow.path.isEmpty() }
        compose.waitForIdle()
        down()
        focusIsOn("Films 4K")
        ok()
        until { flow.path == listOf("Films 4K") }
        compose.waitForIdle()
        focusIsOn(text(R.string.choose_folder))
        ok()
        until { done != null }
        assertEquals(FlowDone(switched = false, folderId = EDITED), done)
        assertEquals(FlowStep.BROWSE, flow.step)
        val saved = runBlocking { db.dao().sources() }.single()
        assertEquals(
            listOf<Any>(EDITED, "Films 4K", SourceKind.MOVIES, Protocol.LOCAL, T7, "", "Films 4K"),
            listOf(saved.id, saved.name, saved.kind, saved.protocol, saved.host, saved.share, saved.path),
        )
        val kept = runBlocking { db.dao().item(alien) }
        assertEquals(EDITED, kept?.sourceId)
        assertEquals(1_800_000L, kept?.positionMs)
        assertEquals(true, kept?.favorite)
    }

    /**
     * The library's other folders on the disk are no choice, as in an add, the folder edited's own place is: the disk's
     * root holds « Séries », and « Séries » is in the library. One added meanwhile inside the folder chosen refuses the
     * save, said in the panel, focus kept, the folder edited unchanged.
     */
    @Test
    fun aFolderOfTheLibraryIsNoChoiceAndOneAddedMeanwhileRefusesTheSave() {
        network.mounted = DISKS
        val series = onT7.copy(id = SERIES, name = "Séries", kind = SourceKind.SHOWS, path = "Séries")
        val flow = flow(onT7, series, editing = EDITED)
        back()
        until { flow.path.isEmpty() }
        compose.waitForIdle()
        compose.onNode(hasText(text(R.string.folder_holds_added, "Séries"))).assertIsNotEnabled()
        compose.onNode(hasText("Séries") and hasText(text(R.string.folder_overlaps_state))).assertIsNotEnabled()
        focusIsOn("Films")
        down()
        focusIsOn("Films 4K")
        ok()
        until { flow.path == listOf("Films 4K") }
        compose.waitForIdle()

        runBlocking { db.dao().insertSource(onT7.copy(id = 0, name = "Dune (2021)", path = "Films 4K/Dune (2021)")) }
        ok()
        until { flow.message != null }
        compose.waitForIdle()
        val overlaps = flow.message as FlowMessage.Overlaps
        assertEquals("Dune (2021)", overlaps.existing)
        assertTrue("${overlaps.standing}", overlaps.standing is Standing.Holds)
        compose.onNodeWithText(text(R.string.folder_holds, "Dune (2021)"), useUnmergedTree = true).assertExists()
        focusIsOn(text(R.string.choose_folder))
        assertNull(done)
        assertEquals("Films", runBlocking { db.dao().source(EDITED) }?.path)
    }

    /**
     * Its disk unplugged, the edit shows that disk's row alone, « Ne répond pas », the panel saying to plug it back in;
     * plugged in, OK opens it where the folder is. Unplugged again while it is browsed, the folder's row says so and the
     * panel names the disk; Back, the level above out of reach, leaves.
     */
    @Test
    fun aDiskUnpluggedIsSaidOnItsRowAndOpensOncePluggedBackIn() {
        network.mounted = DISKS.filter { it.internal }
        val flow = flow(onT7, editing = EDITED)
        assertEquals(FlowStep.WHERE, flow.step)
        focusIsOn(text(R.string.server_silent))
        compose.onNode(hasText(text(R.string.disk_usb)) and hasClickAction()).assertExists()
        compose.onNodeWithText(text(R.string.disk_absent, text(R.string.disk_usb))).assertExists()
        compose.onNodeWithText(text(R.string.nas_used)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.type_address)).assertDoesNotExist()

        network.mounted = DISKS
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        assertEquals(listOf("Films"), flow.path)
        focusIsOn(text(R.string.choose_folder))
        compose.onNodeWithText("Samsung T7").assertExists()

        network.mounted = DISKS.filter { it.internal }
        down()
        focusIsOn("Alien (1979)")
        ok()
        until { flow.message != null }
        compose.waitForIdle()
        assertEquals(FlowMessage.DiskAbsent(FlowNas(T7, "Samsung T7")), flow.message)
        compose.onNodeWithText(text(R.string.disk_absent, "Samsung T7")).assertExists()
        focusIsOn(text(R.string.server_silent))

        back()
        until { left }
        assertNull(done)
    }

    /**
     * A folder no longer where it was opens at the nearest folder above it still there, here the player's storage's root,
     * focused as a level is; chosen there, the folder is named as the disk's row, « Disque interne ».
     */
    @Test
    fun aFolderMovedOpensAboveItAndADisksRootIsNamedAsItsRow() {
        network.mounted = DISKS
        val flow = flow(onT7.copy(host = Volume.INTERNAL, path = "Vidéos/Films"), editing = EDITED)
        assertEquals(FlowStep.BROWSE, flow.step)
        assertEquals(emptyList<String>(), flow.path)
        assertTrue("${network.asked}", "level ${Volume.INTERNAL}/Vidéos" in network.asked)
        focusIsOn("Movies")
        compose.press(Key.DirectionUp, times = 2)
        focusIsOn(text(R.string.choose_folder))
        ok()
        until { done != null }
        val saved = runBlocking { db.dao().source(EDITED) }
        assertEquals(text(R.string.disk_internal) to "", saved?.name to saved?.path)
    }

    /** The edit survives Android stopping the app: on its disk, at the level left, never its first step. */
    @Test
    fun theEditIsFoundAgainAfterAStoppedProcess() {
        network.mounted = DISKS
        val saved = SavedStateHandle()
        val flow = flow(onT7, saved = saved, editing = EDITED)
        back()
        until { flow.path.isEmpty() }

        val again = FolderFlow(network, library, scope, useFolders = { false }, saved = saved.afterProcessDeath(), editing = EDITED)
        until { again.ready }
        assertEquals(FlowStep.BROWSE, again.step)
        assertEquals(emptyList<String>(), again.path)
        assertEquals(T7, again.nas?.host)
        assertEquals(EDITED to "Films", again.edited?.id to again.edited?.path)
        assertEquals(FlowKeys.CHOOSE, again.aim?.key)
    }

    private companion object {
        const val EDITED = 5L
        const val SERIES = 9L
    }
}
