package app.houselights.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlListItem
import app.houselights.ui.design.components.PosterWall
import app.houselights.ui.design.components.WallLights
import app.houselights.ui.design.components.Wordmark
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.toPx

/** First launch: a lobby of this week's films behind the wordmark, then where yours are. */
@Composable
fun WelcomeScreen(onLocal: () -> Unit, onJellyfin: () -> Unit) {
    val app = appContainer()
    val focus = remember { FocusRequester() }
    var posters by remember { mutableStateOf<List<String>>(emptyList()) }
    val posterPx = 148.dp.toPx()
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        posters = app.trendingPosters(posterPx)
    }
    val black = Hl.colors.background
    val tungsten = Hl.colors.tungsten

    Box(Modifier.fillMaxSize().background(black)) {
        PosterWall(posters)
        // Darken the wall where text sits, keep it visible in the middle, and light the wordmark.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to black.copy(alpha = 0.92f),
                    0.42f to black.copy(alpha = 0.55f),
                    0.6f to black.copy(alpha = 0.7f),
                    1f to black.copy(alpha = 0.94f),
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to black, 0.2f to Color.Transparent, 0.8f to Color.Transparent, 1f to black)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    0f to tungsten.copy(alpha = 0.16f),
                    1f to Color.Transparent,
                    center = androidx.compose.ui.geometry.Offset(520f, 420f),
                    radius = 900f,
                )
            )
        )
        WallLights()

        Row(
            Modifier.fillMaxSize().padding(horizontal = HlSpace.safeH + HlSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Wordmark(style = Hl.type.display.copy(fontSize = 76.sp, lineHeight = 76.sp))
                Spacer(Modifier.height(HlSpace.m))
                Text(
                    stringResource(R.string.welcome_tagline),
                    style = Hl.type.title,
                    color = Hl.colors.contentSecondary,
                    modifier = Modifier.widthIn(max = 380.dp),
                )
            }
            Spacer(Modifier.width(HlSpace.xl))
            Column(Modifier.width(470.dp), verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                Text(stringResource(R.string.welcome_title), style = Hl.type.section, color = Hl.colors.content.copy(alpha = 0.86f))
                Spacer(Modifier.height(HlSpace.xxs))
                HlListItem(
                    stringResource(R.string.source_local),
                    stringResource(R.string.source_local_desc),
                    onLocal,
                    Modifier.focusRequester(focus),
                    icon = HlIcons.Folder,
                )
                HlListItem(
                    stringResource(R.string.source_jellyfin),
                    stringResource(R.string.source_jellyfin_desc),
                    onJellyfin,
                    icon = HlIcons.Server,
                )
                Spacer(Modifier.height(HlSpace.xxs))
                Text(stringResource(R.string.sources_coming_soon), style = Hl.type.caption, color = Hl.colors.contentTertiary)
            }
        }
    }
}
