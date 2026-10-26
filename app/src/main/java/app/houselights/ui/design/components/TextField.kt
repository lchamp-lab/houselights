package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.emberLight
import java.text.BreakIterator

/**
 * A text field for the remote, [HlSize.field] tall: a glass plate edged by a hairline, the keyboard on OK ([KeyHint]);
 * focus rings it in white, and while typing the ring is the ember light and the cursor raspberry, which Left and Right
 * move, leaving the field only from the text's start or end. Its [label] in
 * white over it, followed by "(optional)" in grey when [optional], is always read out, even when [showLabel] is
 * false. Below, [error] or else [supporting]; [placeholder] is an example ("Ex. : 192.168.1.10").
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
            value,
            onValueChange,
            Modifier.semantics {
                contentDescription = named
                if (error != null) error(error)
            },
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

/** On TV the keyboard opens only on OK, so the field is a focusable box that hands focus to the text on OK. */
@Composable
private fun FieldBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    password: Boolean,
    onImeAction: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var boxFocused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Every start of typing, and text the field did not type while typing, puts the cursor after the text, where erase
    // reaches all of it; then Left and Right move it, and OK types where they left it. Not a selection, which TV
    // keyboards' erase skips. At rest it is at the start, which a one-line field scrolls to, focused or not.
    val latest by rememberUpdatedState(value)
    var typed by remember { mutableStateOf(atEnd(value)) }
    // The last text told to the owner: the keyboard may send several edits between two compositions.
    var told by remember(value) { mutableStateOf(value) }
    // While typing, [text] with the cursor the keyboard or the arrows left, or after it when it came from elsewhere.
    fun typing(text: String) = if (typed.text == text) typed else atEnd(text)
    val shown = if (editing) typing(value) else TextFieldValue(value)
    val shape = HlShapes.field
    val c = Hl.colors

    // One line tall: empty, it would take the platform font's taller line height.
    val textStyle = Hl.type.body.copy(color = c.content)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val oneLine = remember(textStyle, density) {
        with(density) { measurer.measure("Ag", textStyle.copy(lineHeight = TextUnit.Unspecified)).size.height.toDp() }
    }

    LaunchedEffect(editing) {
        if (editing) {
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = HlSize.field)
            .focusRequester(boxFocus)
            .onFocusChanged { boxFocused = it.isFocused }
            .openOnOk(waiting = !editing) {
                typed = atEnd(latest)
                editing = true
            }
            .focusable()
            .clip(shape)
            .background(c.glass)
            .border(fieldEdge(editing, boxFocused), shape)
            .padding(horizontal = HlSpace.m, vertical = HlSpace.s),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = shown,
            onValueChange = { next ->
                typed = next
                if (next.text != told) {
                    told = next.text
                    onValueChange(next.text)
                }
            },
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(if (editing) c.emberTo else Color.Transparent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(onAny = {
                boxFocus.requestFocus()
                editing = false
                if (keyboardOptions.imeAction == ImeAction.Next) focusManager.moveFocus(FocusDirection.Next)
                onImeAction()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .height(oneLine)
                .focusRequester(fieldFocus)
                .focusProperties { canFocus = editing }
                // Read as each arrow comes, so presses quicker than frames each count.
                .arrowsWhileTyping(editing, field = { typing(latest) }, moveTo = { typed = it }) { direction ->
                    boxFocus.requestFocus()
                    editing = false
                    focusManager.moveFocus(direction)
                }
                .onFocusChanged {
                    val wasFocused = fieldFocused
                    fieldFocused = it.isFocused
                    if (wasFocused && !it.isFocused) editing = false
                },
            decorationBox = { inner ->
                Box {
                    Placeholder(placeholder, shown = value.isEmpty())
                    inner()
                }
            },
        )
        if (boxFocused && !editing) {
            // Over the text's end, on the field's surface, never taller than the field.
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                KeyHint(Modifier.wrapContentHeight(unbounded = true).background(c.glass).padding(start = HlSpace.s))
            }
        }
    }
}

/** [text] with the cursor after it. */
private fun atEnd(text: String) = TextFieldValue(text, TextRange(text.length))

/**
 * The arrows while [editing], which reach the app once Back hid the keyboard without telling it: Left and Right move
 * the cursor of [field] a character ([moveTo]), held they repeat, and a press from the text's start or end leaves that
 * way, a held key stopping there; up or down ends typing and leaves at once. Set on the text itself, before Compose's
 * own handling, which turns a remote's arrows into focus moves wherever the cursor is.
 */
private fun Modifier.arrowsWhileTyping(
    editing: Boolean,
    field: () -> TextFieldValue,
    moveTo: (TextFieldValue) -> Unit,
    leave: (FocusDirection) -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val direction = when (event.key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        else -> null
    }
    if (!editing || direction == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val across = direction == FocusDirection.Left || direction == FocusDirection.Right
    val moved = if (across) stepped(field(), forward = direction == FocusDirection.Right) else null
    when {
        moved != null -> moveTo(moved)
        !across || event.nativeKeyEvent.repeatCount == 0 -> leave(direction)
    }
    true
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

/** Shown while the field is empty; skipped when blank, since empty text would change the field's height. */
@Composable
private fun Placeholder(text: String, shown: Boolean) {
    if (shown && text.isNotEmpty()) {
        Text(text, style = Hl.type.body, color = Hl.colors.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun FieldMessage(error: String?, supporting: String?) {
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
    val text = remember(label, named, c, t) {
        val at = named.indexOf(label)
        buildAnnotatedString {
            append(named)
            if (at >= 0 && named.length > label.length) {
                val remark = t.meta.toSpanStyle().copy(color = c.contentSecondary)
                if (at > 0) addStyle(remark, 0, at)
                if (at + label.length < named.length) addStyle(remark, at + label.length, named.length)
            }
        }
    }
    Text(text, style = t.group, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
