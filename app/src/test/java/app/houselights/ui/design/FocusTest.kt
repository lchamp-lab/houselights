package app.houselights.ui.design

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bookkeeping of a screen's focus memory: its keys, and the order it tries them in. */
class FocusTest {

    @Test
    fun aKeyInsideGroupsKnowsThemNearestFirst() {
        val key = FocusKey.within(FocusKey.pane("Library"), FocusKey.row("source"))
        assertEquals("pane:Library/row:source", key)
        assertEquals(listOf("pane:Library"), FocusKey.groups(key))
        val deep = FocusKey.within(FocusKey.within("a", FocusKey.section("LATEST-1")), FocusKey.item("42"))
        assertEquals(listOf("a/section:LATEST-1", "a"), FocusKey.groups(deep))
        assertEquals(emptyList<String>(), FocusKey.groups(FocusKey.action("edit")))
    }

    @Test
    fun anIdKeepsItsOwnSlashesAndColons() {
        val id = "/library/metadata/12:3"
        val key = FocusKey.within(FocusKey.section("CONTINUE-null"), FocusKey.item(id))
        assertEquals(listOf("section:CONTINUE-null"), FocusKey.groups(key))
        assertEquals(id, FocusKey.itemId(key))
        assertEquals(id, FocusKey.itemId(FocusKey.item(id)))
        assertNull(FocusKey.itemId(FocusKey.action("play")))
        assertNull(FocusKey.itemId(FocusKey.within("track", FocusKey.tab("movies"))))
    }

    /** Runs the restore order against elements present on screen ([shown]), revealing [hidden] on demand. */
    private fun restore(key: String?, shown: Set<String>, hidden: Set<String> = emptySet(), fallback: Boolean = true): List<String> {
        val tried = mutableListOf<String>()
        val onScreen = shown.toMutableSet()
        runBlocking {
            restoreFocus(
                key,
                focus = {
                    tried += it
                    it in onScreen
                },
                reveal = {
                    tried += "reveal $it"
                    (it in hidden).also { found -> if (found) onScreen += it }
                },
                fallback = {
                    tried += "fallback"
                    fallback
                },
            )
        }
        return tried
    }

    @Test
    fun focusGoesBackToTheElementLeft() {
        assertEquals(listOf("item:7"), restore("item:7", shown = setOf("item:7")))
    }

    @Test
    fun anElementOutOfSightIsRevealedFirst() {
        assertEquals(listOf("item:7", "reveal item:7", "item:7"), restore("item:7", shown = emptySet(), hidden = setOf("item:7")))
    }

    @Test
    fun anElementGoneLeavesFocusToItsGroupThenToTheFallback() {
        val key = "pane:Library/row:reconnect"
        assertEquals(listOf(key, "reveal $key", "pane:Library"), restore(key, shown = setOf("pane:Library")))
        assertEquals(listOf(key, "reveal $key", "pane:Library", "fallback"), restore(key, shown = emptySet()))
    }

    @Test
    fun aFirstVisitStartsOnTheFallback() {
        assertEquals(listOf("fallback"), restore(null, shown = setOf("item:1")))
    }

    @Test
    fun theMemoryKeepsTheLastKeyAcrossNavigation() {
        val memory = FocusMemory()
        assertNull(memory.last)
        memory.record(FocusKey.action("edit"))
        memory.record(FocusKey.action("nfo"))
        assertEquals("action:nfo", memory.last)
        val saved = with(FocusMemory.Saver) { SaverScope { true }.save(memory) }
        assertEquals("action:nfo", FocusMemory.Saver.restore(saved!!)?.last)
    }

    /** An equal modifier at each composition lets a row's cards skip recomposition when the row is entered. */
    @Test
    fun aGroupMemberIsTheSameModifierFromOneCompositionToTheNext() {
        val group = GroupFocus()
        val self = FocusRequester()
        assertEquals(Modifier.rememberedInGroup(group, self), Modifier.rememberedInGroup(group, self))
        assertNotEquals(Modifier.rememberedInGroup(group, FocusRequester()), Modifier.rememberedInGroup(group, self))
        assertNotEquals(Modifier.rememberedInGroup(GroupFocus(), self), Modifier.rememberedInGroup(group, self))
        assertEquals(Modifier, Modifier.rememberedInGroup(null, self))
    }

    @Test
    fun aKeyHasOneRequesterAndNothingUnderItTakesNoFocus() {
        val memory = FocusMemory()
        assertTrue(memory.requester("row:similar") === memory.requester("row:similar"))
        assertFalse(memory.focus(FocusKey.item("1")))
        // A requester with nothing under it (not composed yet, or gone): said without throwing.
        assertFalse(memory.focus("row:similar"))
    }
}
