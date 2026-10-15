package app.houselights.ui.design.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.emberLight
import app.houselights.ui.util.toPx
import app.houselights.core.ImageRef
import coil.compose.AsyncImage
import androidx.compose.ui.res.stringResource

/** Row and section header, sentence case. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Hl.type.lead, color = Hl.colors.content, modifier = modifier)
}

/** Screen title (library name, settings). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Hl.type.headline, color = Hl.colors.content, modifier = modifier)
}

/**
 * "houselights", set in type and nothing else: lowercase, Archivo expanded and heavy, as the banner
 * sets it on the screen of La Salle, in the ember light across its letters. No halo or shadow: the
 * light in the room comes from the films. [style] is its size: `wordmark` by default (About),
 * `wordmarkHero` over the welcome wall.
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier, style: TextStyle = Hl.type.wordmark) {
    Text(
        stringResource(R.string.wordmark),
        // The brush in the style wins over any colour the text would take.
        style = style.copy(brush = Hl.colors.emberLight()),
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** The title's clear logo, or the title set in condensed type when there is none. */
@Composable
fun TitleArt(
    title: String,
    logo: ImageRef?,
    maxWidth: Dp,
    maxHeight: Dp,
    style: TextStyle = Hl.type.display,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    val url = logo?.url(maxWidth.toPx())
    var failed by remember(url) { mutableStateOf(false) }
    if (url != null && !failed) {
        AsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { failed = true },
            modifier = modifier.heightIn(max = maxHeight).widthIn(max = maxWidth),
        )
    } else {
        Text(
            title,
            style = style,
            color = Hl.colors.content,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.widthIn(max = maxWidth + HlSpace.xxl * 3),
        )
    }
}
