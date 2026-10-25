package app.houselights.ui.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals

/** The style the text reading [text] is laid out in. */
fun SemanticsNodeInteractionsProvider.styleOf(text: String): TextStyle {
    val layouts = mutableListOf<TextLayoutResult>()
    onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    return layouts.first().layoutInput.style
}

/** Fails unless [text] is set in [style]: its size and line height, its voice, weight and tracking. */
fun SemanticsNodeInteractionsProvider.assertSetIn(style: TextStyle, text: String) {
    fun TextStyle.type() = listOf(fontSize, lineHeight, fontFamily, fontWeight, letterSpacing)
    assertEquals("«$text»", style.type(), styleOf(text).type())
}

/** Every text on screen, in every window, whose own layout runs past its box or ends in an ellipsis. */
fun SemanticsNodeInteractionsProvider.cutTexts(): Set<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        .mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.firstOrNull()?.takeIf { it.hasVisualOverflow || (0 until it.lineCount).any(it::isLineEllipsized) }
        }
        .map { it.layoutInput.text.text }
        .toSet()
