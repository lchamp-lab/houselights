package app.houselights.ui.design.components

import android.view.InputDevice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlColors
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HlType
import app.houselights.ui.design.emberLight
import java.text.BreakIterator

/**
 * A text field for the remote, [HlSize.field] tall: a glass plate edged by a hairline, the keyboard on OK ([KeyHint]);
 * focus rings it in white, and while typing the ring is the ember light and the cursor raspberry, which the remote's
 * Left and Right move, leaving the field only from the text's start or end. While it holds text and it or its cross has
 * focus, a cross at its end empties it (#78, [ClearTile]): Right at rest reaches it, as Right from the text's end while
 * typing does; OK on it empties the field and brings focus back to it, at rest, the keyboard hidden. Focused at rest,
 * "OK saisir" shows only where it leaves the text whole before the cross: the text comes first. Its [label] in white over
 * it, followed by "(optional)" in grey when [optional], is always read out, even when [showLabel] is false, and names
 * its cross ("Effacer Année"). Below, [error] or else [supporting]; [placeholder] is an example ("Ex. : 192.168.1.10").
 */
@Composable
fun HlTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    showLabel: Boolean = true,
    supporting: String? = null,
    error: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    optional: Boolean = false,
) {
    require(label.isNotBlank()) { "A field needs a name, if only for accessibility services" }
    val named = if (optional) stringResource(R.string.field_optional, label) else label
    Column(modifier) {
        if (showLabel) {
            // Read out with the field itself, not twice.
            FieldName(label, named, Modifier.clearAndSetSemantics {})
            Spacer(Modifier.height(HlSpace.s))
        }
        FieldBox(
            label,
            named,
            error,
            value,
            onValueChange,
            placeholder,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            password = password,
            onImeAction = onImeAction,
        )
        FieldMessage(error, supporting)
    }
}

/**
 * On TV the keyboard opens only on OK, so the field is a focusable box, read out as [named] with its [error], that hands
 * focus to the text on OK. Its cross ([FieldCross]), named for [label], lies inside the box's frame, where Compose's
 * search for the next focus would not look: the box names it as its Right.
 */
@Composable
private fun FieldBox(
    label: String,
    named: String,
    error: String?,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    password: Boolean,
    onImeAction: () -> Unit,
) {
    val state = remember { FieldState(value) }
    val crossed = state.crossed(value)
    // The field's width once laid out, which the text, « OK saisir » and the cross share.
    var width by remember { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val latest by rememberUpdatedState(value)
    // The last text told to the owner: the keyboard may send several edits between two compositions.
    var told by remember(value) { mutableStateOf(value) }
    val shown = if (state.editing) state.typing(value) else TextFieldValue(value)
    val shape = HlShapes.field
    val c = Hl.colors

    // One line tall: empty, it would take the platform font's taller line height.
    val textStyle = Hl.type.body.copy(color = c.content)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val oneLine = remember(textStyle, density) {
        with(density) { measurer.measure("Ag", textStyle.copy(lineHeight = TextUnit.Unspecified)).size.height.toDp() }
    }
    val hint = rememberHint(value, password, textStyle, atRest = state.atRest, crossed = crossed, width = width)

    LaunchedEffect(state.editing) {
        if (state.editing) {
            state.textFocus.requestFocus()
            keyboard?.show()
        }
    }

    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        Box(
            Modifier
                .semantics {
                    contentDescription = named
                    if (error != null) error(error)
                }
                .fillMaxWidth()
                .heightIn(min = HlSize.field)
                .focusRequester(state.boxFocus)
                .focusProperties { if (crossed) right = state.crossFocus }
                .onFocusChanged { state.boxFocused = it.isFocused }
                .openOnOk(waiting = !state.editing) { state.startTyping(latest) }
                .focusable()
                .clip(shape)
                .background(c.glass)
                .border(fieldEdge(state.editing, state.boxFocused), shape)
                // The text, its example and « OK saisir » end before the cross.
                .padding(start = HlSpace.m, top = HlSpace.s, end = endRoom(crossed), bottom = HlSpace.s),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = shown,
                onValueChange = { next -> told = state.type(next, told, onValueChange) },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(if (state.editing) c.emberTo else Color.Transparent),
                visualTransformation = if (password) PasswordVisualTransformation(PASSWORD_DOT) else VisualTransformation.None,
                keyboardOptions = keyboardOptions,
                keyboardActions = imeActions(state, keyboardOptions.imeAction, crossed, focusManager, onImeAction),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(oneLine)
                    .focusRequester(state.textFocus)
                    .focusProperties { canFocus = state.editing }
                    // Read as each arrow comes, so presses quicker than frames each count.
                    .arrowsWhileTyping(state.editing, field = { state.typing(latest) }, moveTo = { state.typed = it }) { direction ->
                        state.stopTyping()
                        focusManager.moveFocus(direction)
                    }
                    .onFocusChanged { state.textFocused(it.isFocused) },
                decorationBox = { inner ->
                    Box {
                        Placeholder(placeholder, shown = value.isEmpty(), hint = hint)
                        inner()
                    }
                },
            )
            EndHint(shown = hint != null)
        }
        FieldCross(crossed, label, state, focusManager) {
            // Emptied as erasing it all would: the owner hears it and nothing else runs.
            told = ""
            onValueChange("")
            state.cleared()
        }
    }
}

/**
 * Where a field stands: typing or not ([editing]), which of its box, its text and its cross has focus, and the text with
 * the cursor as typed ([typed]). Every start of typing, and text the field did not type while typing, puts the cursor
 * after the text, where erase reaches all of it; then Left and Right move it, and OK types where they left it. Not a
 * selection, which TV keyboards' erase skips. At rest it is at the start, which a one-line field scrolls to, focused or
 * not.
 */
@Stable
private class FieldState(value: String) {
    var editing by mutableStateOf(false)
    var boxFocused by mutableStateOf(false)
    var crossFocused by mutableStateOf(false)
    var typed by mutableStateOf(atEnd(value))
    val boxFocus = FocusRequester()
    val textFocus = FocusRequester()
    val crossFocus = FocusRequester()
    private var textHadFocus = false

    /** Focused at rest, neither typing nor on its cross: where « OK saisir » may show. */
    val atRest: Boolean get() = boxFocused && !editing

    /** The cross shows while the field holds [value] and it, its text or the cross has focus: a form at rest stays bare. */
    fun crossed(value: String): Boolean = value.isNotEmpty() && (boxFocused || editing || crossFocused)

    /** While typing, [text] with the cursor the keyboard or the arrows left, or after it when it came from elsewhere. */
    fun typing(text: String): TextFieldValue = if (typed.text == text) typed else atEnd(text)

    /** OK on the field: typing starts after its [text] as it is now. */
    fun startTyping(text: String) {
        typed = atEnd(text)
        editing = true
    }

    /** Typing ends, focus back on the box, at rest. */
    fun stopTyping() {
        boxFocus.requestFocus()
        editing = false
    }

    /** The text has focus, or not ([focused]): losing it ends typing. */
    fun textFocused(focused: Boolean) {
        if (textHadFocus && !focused) editing = false
        textHadFocus = focused
    }

    /** [next] typed: [tell] hears its text unless it is the one [told] last; the text told now. */
    fun type(next: TextFieldValue, told: String, tell: (String) -> Unit): String {
        typed = next
        if (next.text != told) tell(next.text)
        return next.text
    }

    /** Emptied by its cross: focus back on the field, at rest, the keyboard hidden, so that OK opens it. */
    fun cleared() {
        typed = atEnd("")
        stopTyping()
    }
}

/** The room at a field's end: the cross's while it shows ([crossed]), else the field's own margin. */
private fun endRoom(crossed: Boolean): Dp = if (crossed) CROSS_ROOM else HlSpace.m

/**
 * The room « OK saisir » takes (px) while it shows: focused [atRest], and only where it leaves [value], as [password]
 * draws it in [style], whole before the end ([endRoom]) of the field [width] px wide; null where it gives way, the text
 * coming first, the white ring saying the focus and OK opening the keyboard as ever.
 */
@Composable
private fun rememberHint(value: String, password: Boolean, style: TextStyle, atRest: Boolean, crossed: Boolean, width: Int): Int? {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val hint = rememberHintWidth(measurer)
    val text = remember(value, password, style, density) {
        if (value.isEmpty()) 0 else measurer.measure(if (password) masked(value) else value, style).size.width
    }
    val room = with(density) { width - HlSpace.m.roundToPx() - endRoom(crossed).roundToPx() }
    return hint.takeIf { atRest && (width == 0 || text + hint <= room) }
}

/**
 * The keyboard's action key: typing ends on the field, Next going on to the next field, from the cross while it shows
 * ([crossed]), which follows the field in focus order; then [onImeAction].
 */
private fun imeActions(
    state: FieldState,
    action: ImeAction,
    crossed: Boolean,
    focusManager: FocusManager,
    onImeAction: () -> Unit,
): KeyboardActions = KeyboardActions(onAny = {
    val next = action == ImeAction.Next
    state.stopTyping()
    if (next && crossed) state.crossFocus.requestFocus()
    if (next) focusManager.moveFocus(FocusDirection.Next)
    onImeAction()
})

/**
 * The field's cross while [shown] ([ClearTile]), named for its [label], at its end: Left comes back to the field, Up and
 * Down leave as from it, Right goes on; OK empties it ([onClear]).
 */
@Composable
private fun BoxScope.FieldCross(shown: Boolean, label: String, state: FieldState, focusManager: FocusManager, onClear: () -> Unit) {
    if (!shown) return
    ClearTile(
        stringResource(R.string.field_clear, label),
        onClick = onClear,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = HlSpace.s)
            .focusRequester(state.crossFocus)
            .focusProperties { left = state.boxFocus }
            .leavesAsItsField(state.boxFocus, state.crossFocus, focusManager::moveFocus)
            .onFocusChanged { state.crossFocused = it.isFocused },
    )
}

/**
 * Up and Down from a field's cross leave as from the field itself, the box [field], whose frame it lies in; with nothing
 * that way, focus stays on the [cross].
 */
private fun Modifier.leavesAsItsField(field: FocusRequester, cross: FocusRequester, moveFocus: (FocusDirection) -> Boolean): Modifier =
    onPreviewKeyEvent { event ->
        val direction = arrow(event.key)?.takeIf { it == FocusDirection.Up || it == FocusDirection.Down }
        if (direction == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        field.requestFocus()
        if (!moveFocus(direction)) cross.requestFocus()
        true
    }

/**
 * The cross that empties a field, a header's tile ([HlSize.tileCompact], its glyph [HlSize.icon]) inside it, read as a
 * button named [label] (« Effacer Année »): at rest its glyph grey on the field's glass, with focus the tile white, as
 * every tile.
 */
@Composable
private fun ClearTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.fill(rest = Color.Transparent, restInk = Hl.colors.contentSecondary),
        shape = HlShapes.control,
        modifier = modifier.size(HlSize.tileCompact),
    ) {
        Icon(
            HlIcons.Clear,
            contentDescription = label,
            tint = LocalContentColor.current,
            modifier = Modifier.size(HlSize.icon).align(Alignment.Center),
        )
    }
}

/** Room the cross takes at a field's end: a gap, its tile, a gap to the field's edge. */
private val CROSS_ROOM = HlSpace.s + HlSize.tileCompact + HlSpace.s

/** What a password field draws for each character, measured for the room the text takes. */
private const val PASSWORD_DOT = '\u2022'

/** [value] as a password field draws it. */
private fun masked(value: String) = PASSWORD_DOT.toString().repeat(value.length)

/**
 * The room « OK saisir » takes at a field's end, as [EndHint] lays it out: a gap, the key as a badge, a gap, the word
 * (px).
 */
@Composable
private fun rememberHintWidth(measurer: TextMeasurer): Int {
    val t = Hl.type
    val density = LocalDensity.current
    val ok = stringResource(R.string.key_ok)
    val word = stringResource(R.string.key_hint_type)
    return remember(ok, word, t, density) {
        with(density) {
            val badge = measurer.measure(ok, t.badge).size.width + HlControl.badgePaddingH.roundToPx() * 2
            HlSpace.s.roundToPx() + badge + HlSpace.xs.roundToPx() + measurer.measure(word, t.meta).size.width
        }
    }
}

/** [text] with the cursor after it. */
private fun atEnd(text: String) = TextFieldValue(text, TextRange(text.length))

/**
 * The arrows while [editing], which reach the app once Back hid the keyboard without telling it: the remote's Left and
 * Right move the cursor of [field] a character ([moveTo]), held they repeat, and a press from the text's start or end
 * leaves that way, a held key stopping there; up or down ends typing and leaves at once. Set on the text itself, before
 * Compose's own handling, which turns a remote's arrows into focus moves wherever the cursor is. A keyboard's Left and
 * Right, the one shown or one plugged in, are left to that handling, which moves the cursor, selects with Shift and
 * never leaves.
 */
private fun Modifier.arrowsWhileTyping(
    editing: Boolean,
    field: () -> TextFieldValue,
    moveTo: (TextFieldValue) -> Unit,
    leave: (FocusDirection) -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val direction = arrow(event.key)
    if (!editing || direction == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val across = direction == FocusDirection.Left || direction == FocusDirection.Right
    if (across && !event.fromRemote()) return@onPreviewKeyEvent false
    val moved = if (across) stepped(field(), forward = direction == FocusDirection.Right) else null
    when {
        moved != null -> moveTo(moved)
        !across || event.nativeKeyEvent.repeatCount == 0 -> leave(direction)
    }
    true
}

/** Where [key] points when it is an arrow. */
private fun arrow(key: Key): FocusDirection? = when (key) {
    Key.DirectionUp -> FocusDirection.Up
    Key.DirectionDown -> FocusDirection.Down
    Key.DirectionLeft -> FocusDirection.Left
    Key.DirectionRight -> FocusDirection.Right
    else -> null
}

/**
 * Whether this key comes from a remote's D-pad, told as Compose's own text field tells the keys it turns into focus
 * moves: from a device of its own (the keyboard shown sends its keys from a virtual one) with a D-pad, its source not a
 * keyboard's alone (a keyboard plugged in).
 */
private fun KeyEvent.fromRemote(): Boolean {
    val device = nativeKeyEvent.device ?: return false
    return device.supportsSource(InputDevice.SOURCE_DPAD) && !device.isVirtual &&
        nativeKeyEvent.source != InputDevice.SOURCE_KEYBOARD
}

/**
 * [field] with its cursor a character on, towards the end when [forward] (a selection collapsing to that side); null
 * when the cursor is already at the start, or at the end when [forward].
 */
private fun stepped(field: TextFieldValue, forward: Boolean): TextFieldValue? {
    val text = field.text
    val selection = field.selection
    val from = if (forward) selection.max else selection.min
    val to = when {
        !selection.collapsed -> from
        forward && from < text.length -> characters(text).following(from)
        !forward && from > 0 -> characters(text).preceding(from)
        else -> null
    }
    return to?.let { TextFieldValue(text, TextRange(it)) }
}

/** [text]'s characters as a reader counts them: an accent typed apart or an emoji is one. */
private fun characters(text: String): BreakIterator = BreakIterator.getCharacterInstance().apply { setText(text) }

/** OK starts typing on key release, so the keyboard does not receive it. */
private fun Modifier.openOnOk(waiting: Boolean, open: () -> Unit): Modifier = onKeyEvent { event ->
    val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
    if (waiting && ok) {
        if (event.type == KeyEventType.KeyUp) open()
        true
    } else {
        false
    }
}

/** A field's edge: a hairline at rest, projector white with focus, ember while typing. */
@Composable
private fun fieldEdge(editing: Boolean, focused: Boolean): BorderStroke {
    val c = Hl.colors
    return when {
        editing -> BorderStroke(HlSize.focusBorder, c.emberLight())
        focused -> FocusStyle.ring()
        else -> BorderStroke(HlSize.hairline, c.outline)
    }
}

/**
 * Shown while the field is empty, ending [hint] px before the field's end while « OK saisir » shows there; skipped when
 * blank, since empty text would change the field's height.
 */
@Composable
private fun Placeholder(text: String, shown: Boolean, hint: Int?) {
    if (shown && text.isNotEmpty()) {
        val room = with(LocalDensity.current) { (hint ?: 0).toDp() }
        Text(
            text,
            style = Hl.type.body,
            color = Hl.colors.contentTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = room),
        )
    }
}

/**
 * « OK saisir » at the text's end, while [shown], on the field's surface, never taller than it, as wide as
 * [rememberHintWidth] says.
 */
@Composable
private fun BoxScope.EndHint(shown: Boolean) {
    if (!shown) return
    Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
        KeyHint(
            Modifier
                .wrapContentHeight(unbounded = true)
                .background(Hl.colors.glass)
                .padding(start = HlSpace.s),
        )
    }
}

/** Shows that OK opens the keyboard (the key as a badge, then the word); nothing else tells the remote user. */
@Composable
internal fun KeyHint(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Badge(stringResource(R.string.key_ok))
        Spacer(Modifier.width(HlSpace.xs))
        Text(stringResource(R.string.key_hint_type), style = Hl.type.meta, color = Hl.colors.contentTertiary, maxLines = 1)
    }
}

/** [error] as an [AttentionNote], else [supporting]; the edge stays unchanged, since ember there means typing. */
@Composable
private fun ColumnScope.FieldMessage(error: String?, supporting: String?) {
    if (error == null && supporting == null) return
    Spacer(Modifier.height(HlSpace.xs))
    if (error != null) {
        AttentionNote(error)
    } else if (supporting != null) {
        Text(supporting, style = Hl.type.meta, color = Hl.colors.contentSecondary)
    }
}

/** A field's name in white, then what [named] adds to it ("(facultatif)") in grey: a name, then a remark. */
@Composable
internal fun FieldName(label: String, named: String, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val t = Hl.type
    val text = remember(label, named, c, t) { fieldNameText(label, named, c, t) }
    Text(text, style = t.group, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** [named] as [FieldName] sets it, in `group`: [label] in white, what it adds in grey `meta`. */
private fun fieldNameText(label: String, named: String, c: HlColors, t: HlType): AnnotatedString {
    val at = named.indexOf(label)
    return buildAnnotatedString {
        append(named)
        if (at >= 0 && named.length > label.length) {
            val remark = t.meta.toSpanStyle().copy(color = c.contentSecondary)
            if (at > 0) addStyle(remark, 0, at)
            if (at + label.length < named.length) addStyle(remark, at + label.length, named.length)
        }
    }
}
