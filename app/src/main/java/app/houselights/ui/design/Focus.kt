@file:OptIn(ExperimentalFoundationApi::class)

package app.houselights.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Focus keys naming what an element is, not where, so they survive reloads; nested as `group/key` ([within]). */
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

    /** A slash before a kind of key ends a group; an id may contain slashes of its own. */
    private val boundary = Regex("/(?=(item|action|tab|pane|row|section):)")

    private fun boundaries(key: String) = boundary.findAll(key).map { it.range.first }.toList()
}

/**
 * Requests focus; false when none was taken, as when no node carries this requester (not composed yet, or gone):
 * Compose answers false there since 1.8, where it threw.
 */
fun FocusRequester.tryRequestFocus(): Boolean = requestFocus()

/**
 * Where focus was on a screen, so returning to it refocuses the element left, even after a reload.
 * Elements carry [focusKey], their groups [focusGroupKey]; a screen keeps the memory with
 * [rememberFocusMemory] and restores focus with [RestoreFocus].
 */
@Stable
class FocusMemory(initial: String? = null) {
    /** What last had focus on the screen; null on a first visit. */
    var last: String? by mutableStateOf(initial)
        private set

    /**
     * True until the screen's first frame ([RestoreFocus]) when it arrives with [initial] to restore: a new window
     * focuses its first element on its own as it is first laid out, and that is not where the user was.
     */
    var arriving = initial != null
        private set

    /** The screen drew its first frame: focus from now on is the user's. */
    fun arrived() {
        arriving = false
    }

    /** Requesters the screen focuses itself ([requester]), kept for the screen's lifetime. */
    private val requesters = HashMap<String, FocusRequester>()

    /** Attached groups ([focusGroupKey]), which can tell whether a focus request took. */
    private val groups = HashMap<String, FocusGroupNode>()

    /** Handles of composed keyed elements: stable so modifiers stay equal, dropped when the element leaves. */
    private val handles = HashMap<String, KeyHandles>()

    /** Attached keyed elements, which know whether they are placed and whether they took focus. */
    private val nodes = HashMap<String, FocusKeyNode>()

    /** Every key the memory holds something for, so tests can check it forgets them. */
    internal val held: Set<String> get() = requesters.keys + handles.keys + nodes.keys + groups.keys

    /** The requester for [key], for the screen's own use (start, redirects); [focusGroupKey] carries it. */
    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { handles[key]?.requester ?: FocusRequester() }

    /** Records [key] as where focus is, without moving it. */
    fun record(key: String) {
        if (!arriving) last = key
    }

    /**
     * Focuses [key]; false when it is absent or took no focus. A keyed element must be placed (lists
     * compose unseen items ahead).
     */
    fun focus(key: String): Boolean {
        nodes[key]?.let { return it.placed && it.requestFocus() }
        groups[key]?.let { return it.requestFocus() }
        return requesters[key]?.tryRequestFocus() == true
    }

    /** Whether [key]'s element holds focus now; read as a change composes, before it removes that element. */
    fun hasFocus(key: String): Boolean = nodes[key]?.focused == true

    internal fun handles(key: String): KeyHandles = handles.getOrPut(key) { KeyHandles(requesters[key] ?: FocusRequester()) }

    internal fun attach(node: FocusKeyNode) {
        nodes[node.key] = node
        // A node reattached without recomposing keeps its handles: register them again.
        handles[node.key] = node.handles
    }

    internal fun detach(node: FocusKeyNode) {
        if (nodes[node.key] !== node) return
        nodes -= node.key
        if (handles[node.key] === node.handles) handles -= node.key
    }

    internal fun attach(group: FocusGroupNode) {
        groups[group.key] = group
    }

    internal fun detach(group: FocusGroupNode) {
        if (groups[group.key] === group) groups -= group.key
    }

    /**
     * Restores focus once the screen is laid out, in [restoreFocus]'s order, [inPlace] naming what took the place of an
     * element gone meanwhile; true when something took it.
     */
    suspend fun restore(
        fallback: FocusRequester?,
        reveal: suspend (key: String) -> Boolean = { false },
        inPlace: (key: String) -> String? = { null },
    ): Boolean {
        withFrameNanos { }
        val revealed: suspend (String) -> Boolean = { key -> reveal(key).also { if (it) withFrameNanos { } } }
        // Keep only the element left in view: one that refused focus must not pull the list away.
        var took: String? = null
        val focusing: (String) -> Boolean = { key -> focus(key).also { if (it) took = key } }
        arrived()
        val restored = restoreFocus(last, focusing, revealed, inPlace) { fallback?.tryRequestFocus() == true }
        took?.takeIf { it == last }?.let { key -> keepInView(key) }
        return restored
    }

    /** Brings the element back into view each frame for a while, as content above may still arrive. */
    private suspend fun keepInView(key: String) {
        withTimeoutOrNull(KEEP_IN_VIEW_MS) {
            while (last == key) {
                val node = nodes[key]?.takeIf { it.placed } ?: break
                try {
                    node.handles.bringer.bringIntoView()
                } catch (_: CancellationException) {
                    // A newer scroll superseded this one: retry next frame; cancelling this loop stops it.
                    currentCoroutineContext().ensureActive()
                }
                withFrameNanos { }
            }
        }
    }

    companion object {
        /** Only the key of what had focus survives navigation. */
        val Saver: Saver<FocusMemory, String> = Saver(save = { it.last }, restore = { FocusMemory(it) })
    }
}

/**
 * Focus order: [key]'s element, again after [reveal]; gone, the element [inPlace] says took its place, again after
 * [reveal]; [key]'s groups nearest first, then [fallback].
 */
internal suspend fun restoreFocus(
    key: String?,
    focus: (String) -> Boolean,
    reveal: suspend (String) -> Boolean,
    inPlace: (String) -> String? = { null },
    fallback: () -> Boolean,
): Boolean {
    if (key == null) return fallback()
    suspend fun shown(element: String) = focus(element) || (reveal(element) && focus(element))
    if (shown(key)) return true
    if (inPlace(key)?.let { shown(it) } == true) return true
    return FocusKey.groups(key).any(focus) || fallback()
}

/** A screen's focus memory, kept while the screen is in the back stack. */
@Composable
fun rememberFocusMemory(): FocusMemory = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory() }

/**
 * Names this element [key] in [memory], so focus on it is remembered and restored. It carries its own
 * bring-into-view requester: Foundation 1.7 has no public way to bring a node itself into view.
 */
fun Modifier.focusKey(key: String, memory: FocusMemory): Modifier {
    val handles = memory.handles(key)
    return bringIntoViewRequester(handles.bringer)
        .focusRequester(handles.requester)
        .then(FocusKeyElement(key, memory, handles))
}

/**
 * Names this group [key] in [memory] (a row, a pane, the tabs): focus falls back to it when a remembered
 * element is gone, and [FocusMemory.focus] can tell whether something in it took focus. It records nothing.
 */
fun Modifier.focusGroupKey(key: String, memory: FocusMemory): Modifier =
    focusRequester(memory.requester(key)).then(FocusGroupElement(key, memory))

private data class FocusGroupElement(val key: String, val memory: FocusMemory) : ModifierNodeElement<FocusGroupNode>() {
    override fun create() = FocusGroupNode(key, memory)
    override fun update(node: FocusGroupNode) = node.update(key, memory)
    override fun InspectorInfo.inspectableProperties() {
        name = "focusGroupKey"
        properties["key"] = key
    }
}

internal class FocusGroupNode(key: String, private var memory: FocusMemory) : Modifier.Node(), FocusRequesterModifierNode {
    var key = key
        private set

    fun update(key: String, memory: FocusMemory) {
        if (isAttached) this.memory.detach(this)
        this.key = key
        this.memory = memory
        if (isAttached) memory.attach(this)
    }

    override fun onAttach() = memory.attach(this)

    override fun onDetach() = memory.detach(this)
}

/** How long a restored element is held in view while its screen fills in. */
private const val KEEP_IN_VIEW_MS = 1_200L

internal class KeyHandles(val requester: FocusRequester) {
    val bringer = BringIntoViewRequester()
}

private data class FocusKeyElement(val key: String, val memory: FocusMemory, val handles: KeyHandles) :
    ModifierNodeElement<FocusKeyNode>() {
    override fun create() = FocusKeyNode(key, memory, handles)
    override fun update(node: FocusKeyNode) = node.update(key, memory, handles)
    override fun InspectorInfo.inspectableProperties() {
        name = "focusKey"
        properties["key"] = key
    }
}

/** Records focus on its element, knows whether it is placed, and reports whether a focus request took. */
internal class FocusKeyNode(key: String, private var memory: FocusMemory, handles: KeyHandles) :
    Modifier.Node(), FocusRequesterModifierNode, FocusEventModifierNode, LayoutAwareModifierNode {
    var key = key
        private set

    var handles = handles
        private set

    /** Laid out on screen, not just composed ahead by a list. */
    var placed = false
        private set

    private var focusState: FocusState? = null

    /** Holds focus, itself or within. */
    val focused: Boolean get() = focusState?.hasFocus == true

    fun update(key: String, memory: FocusMemory, handles: KeyHandles) {
        if (isAttached) this.memory.detach(this)
        this.key = key
        this.memory = memory
        this.handles = handles
        if (isAttached) memory.attach(this)
    }

    override fun onAttach() = memory.attach(this)

    override fun onDetach() {
        placed = false
        memory.detach(this)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        placed = coordinates.isAttached
    }

    // Like onFocusChanged: only a change of state is recorded.
    override fun onFocusEvent(focusState: FocusState) {
        if (this.focusState == focusState) return
        this.focusState = focusState
        if (focusState.hasFocus) memory.record(key)
    }
}

/**
 * Restores focus once per arrival, as soon as [ready]: where [memory] says, else on [fallback]. [reveal]
 * scrolls a list to an unshown element and says whether it could; [inPlace] names what took the place of an element
 * gone while the screen was away (a title marked on its page out of a filtered wall), focused before its group. Later
 * reloads never move focus.
 */
@Composable
fun RestoreFocus(
    memory: FocusMemory,
    fallback: FocusRequester?,
    ready: Boolean = true,
    inPlace: (key: String) -> String? = { null },
    reveal: suspend (key: String) -> Boolean = { false },
) {
    var done by remember { mutableStateOf(false) }
    val currentFallback by rememberUpdatedState(fallback)
    val currentReveal by rememberUpdatedState(reveal)
    val currentInPlace by rememberUpdatedState(inPlace)
    LaunchedEffect(memory) {
        withFrameNanos { }
        memory.arrived()
    }
    LaunchedEffect(ready) {
        if (ready && !done) {
            done = true
            memory.restore(currentFallback, currentReveal) { key -> currentInPlace(key) }
        }
    }
}

/** Focusable elements in order, by their keys ([focusKey]), in the group [group] names ([focusGroupKey]), if any. */
@Immutable
data class FocusPlaces(val group: String?, val keys: List<String>)

/**
 * Focus is never lost to an element leaving [places] (DESIGN.md, Focus), as a title marked out of a filtered wall, or
 * gone as a reload comes: once the one holding focus is gone, focus goes to the one now at its place in its group, else
 * its group's last; its group gone, the group after it, else the one before it; else [otherwise] says where. Read as the
 * change composes, while the element leaving still holds focus, so focus moved elsewhere meanwhile stays there.
 */
@Composable
fun FocusTakesPlace(places: List<FocusPlaces>, memory: FocusMemory, otherwise: suspend () -> Unit = {}) {
    val laid = remember { LaidPlaces() }
    val before = laid.places
    val gone = remember(places) { leaving(before, places, memory::hasFocus) }
    SideEffect { laid.places = places }
    val currentOtherwise by rememberUpdatedState(otherwise)
    LaunchedEffect(gone) {
        val left = gone ?: return@LaunchedEffect
        val taken = takesPlace(left, before, places)
        if (taken == null || !memory.focusLaidOut { taken }) currentOtherwise()
    }
}

/**
 * Focuses what [key] names once it is laid out, a frame at a time, [LAID_OUT_FRAMES] at most, as a list lays out what
 * it composes: an element come in place of another, or put back; false when it never took focus.
 */
suspend fun FocusMemory.focusLaidOut(key: () -> String?): Boolean {
    repeat(LAID_OUT_FRAMES) {
        withFrameNanos { }
        if (key()?.let(this::focus) == true) return true
    }
    return false
}

/** Frames a list takes to lay out what it composes, before an element of it can take focus. */
private const val LAID_OUT_FRAMES = 3

/** The places last laid out, read as the next ones compose. */
private class LaidPlaces {
    var places: List<FocusPlaces> = emptyList()
}

/**
 * Where focus left from: the [group] and the place in it ([index]) of the element leaving with it ([leaving]), named by
 * its [key], so that the next one leaving from the same place, which took focus there, is another leaving.
 */
internal data class LeftPlace(val group: Int, val index: Int, val key: String)

/** The element of [before] gone from [now] that holds focus ([focused]); null when none of them does. */
internal fun leaving(before: List<FocusPlaces>, now: List<FocusPlaces>, focused: (String) -> Boolean): LeftPlace? {
    if (before.isEmpty() || before == now) return null
    val kept = now.flatMapTo(HashSet()) { it.keys }
    before.forEachIndexed { g, place ->
        place.keys.forEachIndexed { i, key -> if (key !in kept && focused(key)) return LeftPlace(g, i, key) }
    }
    return null
}

/**
 * What takes the place [left] in [before] once [now] shows: the element now at its place in its group, else the group's
 * last; its group gone or emptied, the group after it, else the one before it (its key, else its first element).
 */
internal fun takesPlace(left: LeftPlace, before: List<FocusPlaces>, now: List<FocusPlaces>): String? {
    val group = before[left.group]
    val same = now.firstOrNull { it.group == group.group }?.keys.orEmpty()
    if (same.isNotEmpty()) return same.getOrNull(left.index) ?: same.last()
    val shown = now.filter { it.keys.isNotEmpty() }.associateBy { it.group }
    val around = before.drop(left.group + 1) + before.take(left.group).reversed()
    return around.firstNotNullOfOrNull { shown[it.group] }?.let { it.group ?: it.keys.first() }
}

/** The group child that last had focus, restored when the remote re-enters the group ([rememberedInGroup]). */
@Stable
class GroupFocus {
    internal var last: FocusRequester? = null
}

/** The surrounding [GroupFocus], if any. */
val LocalGroupFocus = staticCompositionLocalOf<GroupFocus?> { null }

/**
 * Re-enters the group on the child that last had focus, by redirecting to its requester. Compose 1.7's
 * focus restorer is avoided: it cancels the restored focus with the move, and releases a pinned item
 * twice when the list changes (a crash). Enter goes where it asks; with no child remembered, focus
 * enters as usual.
 */
fun Modifier.keepChildFocus(group: GroupFocus): Modifier = this
    .focusProperties {
        onEnter = { if (requestedFocusDirection != FocusDirection.Enter) group.last?.requestFocus() }
    }
    .focusGroup()

/** The requester an element reports with ([rememberedInGroup]); [group] forgets it when the element leaves. */
@Composable
fun rememberGroupMember(group: GroupFocus?): FocusRequester {
    val self = remember { FocusRequester() }
    if (group != null) DisposableEffect(group, self) { onDispose { if (group.last === self) group.last = null } }
    return self
}

/**
 * Reports to [group] when this element, focused by [self], takes focus. Plain node elements rather than
 * `composed`, so the surface still skips recomposition when its row is entered.
 */
fun Modifier.rememberedInGroup(group: GroupFocus?, self: FocusRequester): Modifier =
    if (group == null) this else focusRequester(self).then(GroupMemberElement(group, self))

private data class GroupMemberElement(val group: GroupFocus, val self: FocusRequester) : ModifierNodeElement<GroupMemberNode>() {
    override fun create() = GroupMemberNode(group, self)
    override fun update(node: GroupMemberNode) {
        node.group = group
        node.self = self
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "rememberedInGroup"
    }
}

private class GroupMemberNode(var group: GroupFocus, var self: FocusRequester) : Modifier.Node(), FocusEventModifierNode {
    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) group.last = self
    }
}

/**
 * Back in two steps: while [away] (focus deep in the screen), Back runs [toStart]; from the start, Back
 * leaves the screen. Sheets and menus are separate windows with their own Back.
 */
@Composable
fun BackToStart(away: Boolean, toStart: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(toStart)
    BackHandler(enabled = away) { scope.launch { current() } }
}
