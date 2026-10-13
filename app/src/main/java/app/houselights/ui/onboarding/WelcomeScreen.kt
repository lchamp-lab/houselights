package app.houselights.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.components.ImmersiveBackdrop
import app.houselights.ui.theme.Palette

/** First launch: pick where the library comes from. */
@Composable
fun WelcomeScreen(onLocal: () -> Unit, onJellyfin: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        ImmersiveBackdrop(null)
        Column(Modifier.align(Alignment.CenterStart).padding(start = 96.dp)) {
            Text("HOUSELIGHTS", style = MaterialTheme.typography.titleSmall, color = Palette.Accent)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.displayMedium, color = Palette.Text)
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SourceCard(
                    stringResource(R.string.source_local),
                    stringResource(R.string.source_local_desc),
                    onLocal,
                    Modifier.focusRequester(focus),
                )
                SourceCard(stringResource(R.string.source_jellyfin), stringResource(R.string.source_jellyfin_desc), onJellyfin)
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.sources_coming_soon), style = MaterialTheme.typography.bodyMedium, color = Palette.TextFaint)
        }
    }
}

@Composable
private fun SourceCard(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.width(340.dp).height(190.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Palette.SurfaceHigh), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, Palette.Accent), shape = shape),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            focusedContainerColor = Palette.SurfaceHigh,
            contentColor = Palette.Text,
            focusedContentColor = Palette.Text,
        ),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        }
    }
}
