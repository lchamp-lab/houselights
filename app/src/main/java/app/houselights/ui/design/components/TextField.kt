package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

@Composable
fun HlTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    // On TV the keyboard only opens on OK: the field is first a plain focusable box.
    var editing by remember { mutableStateOf(false) }
    var boxFocused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val shape = HlShapes.field
    val c = Hl.colors
    // Focus outlines in projector white; typing lights the tungsten lamp.
    val borderColor = when {
        editing -> c.tungsten
        boxFocused -> c.focus
        else -> c.outline
    }

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

    fun stopEditing() {
        boxFocus.requestFocus()
        editing = false
    }

    Column(modifier) {
        // An empty label leaves the naming to a setting title placed above the field.
        if (label.isNotEmpty()) {
            Text(label, style = Hl.type.caption, color = c.contentSecondary)
            Spacer(Modifier.height(HlSpace.xs - 2.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .focusRequester(boxFocus)
                .onFocusChanged { boxFocused = it.isFocused }
                .onKeyEvent { event ->
                    val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (!editing && ok) {
                        if (event.type == KeyEventType.KeyUp) editing = true
                        true
                    } else false
                }
                .focusable()
                .clip(shape)
                .background(if (boxFocused || editing) c.surfaceRaised else c.surface)
                .border(BorderStroke(if (boxFocused || editing) HlSize.focusBorder else HlSize.hairline, borderColor), shape)
                .padding(horizontal = HlSpace.m, vertical = HlSpace.s),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(if (editing) c.tungsten else Color.Transparent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onAny = {
                    stopEditing()
                    if (imeAction == ImeAction.Next) focusManager.moveFocus(FocusDirection.Next)
                    onImeAction()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lineHeight)
                    .focusRequester(fieldFocus)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged {
                        val wasFocused = fieldFocused
                        fieldFocused = it.isFocused
                        if (wasFocused && !it.isFocused) editing = false
                    },
                decorationBox = { inner ->
                    Box {
                        // Only a real placeholder: an empty one would change the field's height.
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, style = Hl.type.body, color = c.contentTertiary)
                        }
                        inner()
                    }
                },
            )
        }
    }
}
