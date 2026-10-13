package app.posterwall.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import app.posterwall.core.MediaItem
import app.posterwall.ui.theme.Palette

private val CardShape = RoundedCornerShape(6.dp)

@Composable
private fun FocusSurface(
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape = CardShape,
    focusedScale: Float = 1.08f,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Text), shape = shape)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            focusedContainerColor = Palette.Surface,
            pressedContainerColor = Palette.Surface,
        ),
        content = content,
    )
}

/** 2:3 poster. */
@Composable
fun PosterCard(
    item: MediaItem,
    imageUrl: String?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 124.dp,
) {
    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    FocusSurface(onClick, onFocused, sized.aspectRatio(2f / 3f)) {
        FallbackTitle(item.title, imageUrl)
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        StatusBadge(item, Modifier.align(Alignment.TopEnd).padding(6.dp))
        ProgressBar(item.progress, Modifier.align(Alignment.BottomCenter))
    }
}

/** 16:9 card with title and subtitle below. */
@Composable
fun LandscapeCard(
    item: MediaItem,
    imageUrl: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 236.dp,
) {
    Column(modifier.width(width)) {
        FocusSurface(onClick, onFocused, Modifier.fillMaxWidth().aspectRatio(16f / 9f), focusedScale = 1.06f) {
            FallbackTitle(title, imageUrl)
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            StatusBadge(item, Modifier.align(Alignment.TopEnd).padding(6.dp))
            ProgressBar(item.progress, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Round cast portrait. */
@Composable
fun PersonCard(
    name: String,
    role: String?,
    imageUrl: String?,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusSurface(
            onClick = {},
            onFocused = onFocused,
            modifier = Modifier.size(88.dp),
            shape = CircleShape,
        ) {
            Text(
                name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                style = MaterialTheme.typography.headlineSmall,
                color = Palette.TextFaint,
                modifier = Modifier.align(Alignment.Center),
            )
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = Palette.Text, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (role != null) {
            Text(role, style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.FallbackTitle(title: String?, imageUrl: String?) {
    if (imageUrl == null) {
        Text(
            title ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(10.dp),
        )
    }
}

@Composable
private fun StatusBadge(item: MediaItem, modifier: Modifier) {
    val unplayed = item.user.unplayedCount ?: 0
    when {
        item.user.played -> Box(
            modifier.size(22.dp).clip(CircleShape).background(Palette.Watched.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
        }
        unplayed > 0 -> Box(
            modifier.clip(RoundedCornerShape(4.dp)).background(Palette.Accent).padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("$unplayed", style = MaterialTheme.typography.labelSmall, color = Palette.OnAccent)
        }
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f) return
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(Palette.Accent))
    }
}

/** Pill button (top bar, sorting, seasons). */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.Text.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = if (selected) Palette.Text else Palette.TextDim,
            focusedContainerColor = Palette.Text,
            focusedContentColor = Palette.Background,
            pressedContainerColor = Palette.Text,
            pressedContentColor = Palette.Background,
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        )
    }
}

/** Small technical badge (4K, DOLBY VISION, ATMOS…). */
@Composable
fun TechBadge(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Palette.Text.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.Text)
    }
}

