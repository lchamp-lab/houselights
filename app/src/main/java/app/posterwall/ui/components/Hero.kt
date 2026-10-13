package app.posterwall.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import app.posterwall.core.MediaItem
import app.posterwall.core.MediaKind
import app.posterwall.ui.theme.Palette

/** Clear logo of the title, or the title in large type when there is none. */
@Composable
fun TitleLogo(title: String, logoUrl: String?, maxHeight: Dp = 120.dp, maxWidth: Dp = 440.dp) {
    var failed by remember(logoUrl) { mutableStateOf(false) }
    if (logoUrl != null && !failed) {
        AsyncImage(
            model = logoUrl,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { failed = true },
            modifier = Modifier.heightIn(max = maxHeight).widthIn(max = maxWidth),
        )
    } else {
        Text(
            title,
            style = MaterialTheme.typography.displayMedium,
            color = Palette.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 640.dp),
        )
    }
}

@Composable
fun MetaRow(parts: List<String>, genres: List<String> = emptyList()) {
    val text = (parts + listOfNotNull(genres.take(3).joinToString(", ").ifBlank { null })).joinToString("   ·   ")
    if (text.isNotBlank()) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, maxLines = 1)
    }
}

/** Information block at the top of the home and library screens. */
@Composable
fun HeroInfo(item: MediaItem?, overviewLines: Int = 3, modifier: Modifier = Modifier) {
    if (item == null) return
    val logoUrl = item.logo?.url(400.dp.toPx())
    Column(modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.Bottom) {
        val title = if (item.kind == MediaKind.EPISODE) item.seriesTitle ?: item.title else item.title
        TitleLogo(title, logoUrl, maxHeight = 104.dp, maxWidth = 400.dp)
        Spacer(Modifier.height(14.dp))
        if (item.kind == MediaKind.EPISODE) {
            Text(
                listOfNotNull(item.episodeLabel, item.title).joinToString("  —  "),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        MetaRow(metaLine(item), item.genres)
        if (!item.overview.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                item.overview,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextDim,
                maxLines = overviewLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun BadgeRow(badges: List<String>) {
    if (badges.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.forEach { TechBadge(it) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.titleSmall,
        color = Palette.TextDim,
        modifier = modifier,
    )
}
