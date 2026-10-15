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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.groundFill
import app.houselights.ui.design.groundStep

/**
 * A text field for the remote: a focusable box first, the keyboard on OK. Focused and waiting, it
 * says so at its end ([KeyHint]: OK, type); its edge turns ember while typing, and only then.
 * Every field has a [label], read out even when [showLabel] is false because what surrounds the
 * field already names it (the title of its sheet). Under it, one line: [error] in ember after
 * the attention light, else [supporting] (what to type, what leaving it empty does). [placeholder]
 * shows an example while the field is empty ("Ex. : 192.168.1.10").
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
) {
    require(label.isNotBlank()) { "A field needs a name, if only for accessibility services" }
    Column(modifier) {
        if (showLabel) {
            // Read out with the field itself, not twice.
            Text(label, style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.height(HlSpace.xs))
        }
        FieldBox(
            value,
            onValueChange,
            Modifier.semantics {
                contentDescription = label
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

/**
 * The box of a [HlTextField]: on TV the keyboard only opens on OK, so the field is first a plain
 * focusable box, and only OK hands focus to the text inside.
 */
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
    val shape = HlShapes.field
    val c = Hl.colors

    // Always one line of body text tall. Empty, a field would otherwise take the platform
    // font's line height and stand taller than its neighbours.
    val textStyle = Hl.type.body.copy(color = c.content)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lineHeight = remember(textStyle, density) {
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
            .focusRequester(boxFocus)
            .onFocusChanged { boxFocused = it.isFocused }
            .openOnOk(waiting = !editing) { editing = true }
            .focusable()
            .clip(shape)
            .background(c.content.copy(alpha = groundStep()))
            .border(fieldEdge(editing, boxFocused), shape)
            .padding(horizontal = HlSpace.m, vertical = HlSpace.s),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(if (editing) c.ember else Color.Transparent),
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
                .height(lineHeight)
                .focusRequester(fieldFocus)
                .focusProperties { canFocus = editing }
                .leaveVertically(editing) { direction ->
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
            // Over the end of the text, on the field's own surface; never taller than the field.
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                KeyHint(Modifier.wrapContentHeight(unbounded = true).background(groundFill(groundStep())).padding(start = HlSpace.s))
            }
        }
    }
}

/**
 * Back closes the keyboard without telling the app, and the field would keep the remote: while
 * [editing], up or down, meaningless on one line, end typing and [leave] that way.
 */
private fun Modifier.leaveVertically(editing: Boolean, leave: (FocusDirection) -> Unit): Modifier = onPreviewKeyEvent { event ->
    val direction = when (event.key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> null
    }
    if (editing && direction != null && event.type == KeyEventType.KeyDown) {
        leave(direction)
        true
    } else {
        false
    }
}

/** OK on a field that waits starts typing (on the key's release, so the keyboard does not take it). */
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
        focused -> BorderStroke(HlSize.focusBorder, c.focus)
        else -> BorderStroke(HlSize.hairline, c.outline)
    }
}

/** An example of what to type, while the field is empty. Only a real one: an empty text would change the field's height. */
@Composable
private fun Placeholder(text: String, shown: Boolean) {
    if (shown && text.isNotEmpty()) {
        Text(text, style = Hl.type.body, color = Hl.colors.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * What OK does on a field that has focus and waits: the key, drawn like a badge, then the word.
 * The remote has no other way to tell that OK opens the keyboard.
 */
@Composable
internal fun KeyHint(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Badge(stringResource(R.string.key_ok))
        Spacer(Modifier.width(HlSpace.xs))
        Text(stringResource(R.string.key_hint_type), style = Hl.type.caption, color = Hl.colors.contentTertiary, maxLines = 1)
    }
}

/**
 * The line under a field: what is wrong, in ember after the attention light; else what to type,
 * quiet. The field's edge does not change for an error: ember there means typing.
 */
@Composable
private fun FieldMessage(error: String?, supporting: String?) {
    val text = error ?: supporting ?: return
    val c = Hl.colors
    val style = Hl.type.caption
    Spacer(Modifier.height(HlSpace.xs))
    Row {
        if (error != null) {
            // As tall as the first line, so the light sits beside it.
            val line = with(LocalDensity.current) { style.lineHeight.toDp() }
            Box(Modifier.height(line), contentAlignment = Alignment.Center) { StatusLight(StatusState.Attention) }
            Spacer(Modifier.width(HlSpace.xs))
        }
        Text(text, style = style, color = if (error != null) c.ember else c.contentSecondary)
    }
}
