@file:OptIn(ExperimentalFoundationApi::class)

package app.houselights.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stable keys for what can hold focus. A key says what an element is, never where it is, so it
 * still finds its element after a list reloaded or moved. A key inside a group is `group/key`
 * ([within]): `pane:Library/row:source`, `section:LATEST-movies/item:42`.
 */
object FocusKey {
    fun item(id: String) = "item:$id"
    fun action(name: String) = "action:$name"
    fun tab(id: String) = "tab:$id"
    fun pane(name: String) = "pane:$name"
    fun row(name: String) = "row:$name"
    fun section(key: String) = "section:$key"
    fun within(group: String, key: String) = "$group/$key"

    /** The groups holding [key], nearest first: `a/b/c` is held by `a/b`, then by `a`. */
    fun groups(key: String): List<String> = boundaries(key).map { key.substring(0, it) }.reversed()

    /** The id of the item [key] names, wherever it sits; null when it names something else. */
    fun itemId(key: String): String? {
        val last = boundaries(key).lastOrNull()?.let { key.substring(it + 1) } ?: key
        return last.takeIf { it.startsWith(ITEM) }?.removePrefix(ITEM)
    }

    private const val ITEM = "item:"

    /** Where a group ends: a slash before a kind of key. An id may hold slashes of its own. */
    private val boundary = Regex("/(?=(item|action|tab|pane|row|section):)")

    private fun boundaries(key: String) = boundary.findAll(key).map { it.range.first }.toList()
}

/**
 * Where focus was on a screen, so that coming back to it (from a detail page, a screen opened from
 * one of its rows, the player) puts focus on the element left, even after the list around it
 * reloaded. Elements that matter carry a [FocusKey] through [focusKey]; the memory keeps the last
 * one focused. A screen keeps its memory with [rememberFocusMemory] and brings focus back with
 * [RestoreFocus].
 */
@Stable
class FocusMemory(initial: String? = null) {
    /** What last had focus on the screen; null on a first visit. */
    var last: String? by mutableStateOf(initial)
        private set

    private val requesters = HashMap<String, FocusRequester>()

    /**
     * Keyed elements laid out on the screen. A list composes the next items ahead without showing
     * them; focus put there would be invisible, so the memory only focuses what is placed.
     */
    private val placed = HashSet<String>()

    internal fun placed(key: String, isPlaced: Boolean) {
        if (isPlaced) placed += key else placed -= key
    }

    /** What focuses [key]: for a group holding keyed elements, or an element focused by the screen itself. */
    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** Takes [key] as where focus is, without moving it (what a screen will want back from elsewhere). */
    fun record(key: String) {
        last = key
    }

    /**
     * Focuses [key] now; false when nothing with that key is on screen. A group (a row, a pane, the
     * tabs) carries no key of its own and is focused whenever it is there.
     */
    fun focus(key: String): Boolean {
        val requester = requesters[key] ?: return false
        if (key in keyed && key !in placed) return false
        return runCatching { requester.requestFocus() }.isSuccess
    }

    /** Keys given to elements through [focusKey], as opposed to groups focused by the screen. */
    private val keyed = HashSet<String>()

    private val bringers = HashMap<String, BringIntoViewRequester>()

    internal fun bringer(key: String): BringIntoViewRequester = bringers.getOrPut(key) { BringIntoViewRequester() }

    internal fun keyed(key: String): FocusRequester {
        keyed += key
        return requester(key)
    }

    /**
     * Brings focus back once the screen is laid out: on what had it last; else on it once [reveal]
     * has scrolled a list to it (a list only composes what it shows); else on the nearest group
     * holding it (a row, a pane); else on [fallback]. True when something took focus.
     */
    suspend fun restore(fallback: FocusRequester?, reveal: suspend (key: String) -> Boolean = { false }): Boolean {
        withFrameNanos { }
        val revealed: suspend (String) -> Boolean = { key -> reveal(key).also { if (it) withFrameNanos { } } }
        val restored = restoreFocus(last, ::focus, revealed) { fallback != null && runCatching { fallback.requestFocus() }.isSuccess }
        last?.let { key -> bringers[key]?.let { keepInView(key, it) } }
        return restored
    }

    /**
     * What a screen shows may still arrive above the element just focused (a pane's folders, a
     * row's titles) and push it out of view: for a moment, the element is brought back into view
     * after each frame, until the viewer moves on.
     */
    private suspend fun keepInView(key: String, bringer: BringIntoViewRequester) {
        withTimeoutOrNull(KEEP_IN_VIEW_MS) {
            while (last == key && key in placed) {
                runCatching { bringer.bringIntoView() }
                withFrameNanos { }
            }
        }
    }

    companion object {
        /** The key of what had focus is all a memory keeps across navigation. */
        val Saver: Saver<FocusMemory, String> = Saver(save = { it.last }, restore = { FocusMemory(it) })
    }
}

/**
 * The order [FocusMemory.restore] tries, stopping at the first that takes focus: the element [key]
 * names, the same once [reveal] brought it into its list, each group holding it from the nearest,
 * then [fallback].
 */
internal suspend fun restoreFocus(
    key: String?,
    focus: (String) -> Boolean,
    reveal: suspend (String) -> Boolean,
    fallback: () -> Boolean,
): Boolean {
    if (key != null) {
        if (focus(key)) return true
        if (reveal(key) && focus(key)) return true
        if (FocusKey.groups(key).any(focus)) return true
    }
    return fallback()
}

/** The focus memory of a screen: it stays with the screen while the screen is in the back stack. */
@Composable
fun rememberFocusMemory(): FocusMemory = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory() }

/** Names this element [key] in [memory]: focus on it is remembered, and coming back finds it. */
fun Modifier.focusKey(key: String, memory: FocusMemory): Modifier =
    bringIntoViewRequester(memory.bringer(key))
        .focusRequester(memory.keyed(key))
        .onFocusChanged { if (it.hasFocus) memory.record(key) }
        .then(PlacedElement(key, memory))

/** How long an element brought back keeps being held in view while its screen fills in. */
private const val KEEP_IN_VIEW_MS = 1_200L

/** Tells [memory] whether the element named [key] is laid out on the screen. */
private data class PlacedElement(val key: String, val memory: FocusMemory) : ModifierNodeElement<PlacedNode>() {
    override fun create() = PlacedNode(key, memory)
    override fun update(node: PlacedNode) {
        if (node.key != key) node.memory.placed(node.key, false)
        node.key = key
        node.memory = memory
    }
}

private class PlacedNode(var key: String, var memory: FocusMemory) : Modifier.Node(), LayoutAwareModifierNode {
    override fun onPlaced(coordinates: LayoutCoordinates) = memory.placed(key, coordinates.isAttached)
    override fun onDetach() = memory.placed(key, false)
}

/**
 * Brings focus back each time the screen arrives, on a first visit or coming back, as soon as it
 * is [ready] (its content is there): where [memory] says, else on [fallback], where a first visit
 * starts. [reveal] scrolls a list to an element it does not show, and says whether it could.
 * Later changes (a list that reloads) never move focus away from the viewer.
 */
@Composable
fun RestoreFocus(
    memory: FocusMemory,
    fallback: FocusRequester?,
    ready: Boolean = true,
    reveal: suspend (key: String) -> Boolean = { false },
) {
    var done by remember { mutableStateOf(false) }
    val currentFallback by rememberUpdatedState(fallback)
    val currentReveal by rememberUpdatedState(reveal)
    LaunchedEffect(ready) {
        if (ready && !done) {
            done = true
            memory.restore(currentFallback, currentReveal)
        }
    }
}

/**
 * The child of a group that last had focus, which comes back when the remote returns to the group
 * (a row of cards, the rows of a pane). Children say they had it through [rememberedInGroup].
 */
@Stable
class GroupFocus {
    internal var last: FocusRequester? = null
}

/** The [GroupFocus] of the group around, if any: its children report to it. */
val LocalGroupFocus = staticCompositionLocalOf<GroupFocus?> { null }

/**
 * A group that gives focus back to the child it last had when the remote brings focus back into
 * it. Focus is redirected to that child's requester: restoring it by hand and cancelling the move,
 * as `focusRestorer` does in Compose 1.7, cancels the restored focus with the move, and
 * `focusRestorer` itself releases a pinned item twice when the list changes under it (a crash). A
 * request by name (Enter) goes where it asks; with no child remembered, focus enters as it would.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.keepChildFocus(group: GroupFocus): Modifier = this
    .focusProperties {
        enter = { direction -> group.last.takeIf { direction != FocusDirection.Enter } ?: FocusRequester.Default }
    }
    .focusGroup()

/** Reports to the [GroupFocus] around that this element had focus, while it is there. */
fun Modifier.rememberedInGroup(): Modifier = composed {
    val group = LocalGroupFocus.current
    if (group == null) {
        this
    } else {
        val self = remember { FocusRequester() }
        DisposableEffect(group, self) { onDispose { if (group.last === self) group.last = null } }
        focusRequester(self).onFocusChanged { if (it.isFocused) group.last = self }
    }
}

/**
 * Back in two steps. While [away] (focus deep in the screen: a lower row, past the first line of a
 * wall, in the right pane), Back runs [toStart], which brings focus back to where the screen
 * starts; from there, Back leaves the screen. Sheets and menus are windows of their own and keep
 * their own Back.
 */
@Composable
fun BackToStart(away: Boolean, toStart: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(toStart)
    BackHandler(enabled = away) { scope.launch { current() } }
}
