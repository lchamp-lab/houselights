package app.houselights.ui.detail

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.NfoDocument
import app.houselights.core.NfoInfo
import app.houselights.core.suspendRunCatching
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.DefinitionList
import app.houselights.ui.design.components.FieldLabel
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlScrollArea
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.appContainer
import kotlinx.coroutines.launch
import java.util.Locale

/** Shows the NFO of a title: its details laid out, or the XML as written. */
@Composable
fun NfoScreen(itemId: String) {
    val app = appContainer()
    var document by remember { mutableStateOf<NfoDocument?>(null) }
    var title by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    val tabsFocus = remember { FocusRequester() }
    LaunchedEffect(itemId) {
        suspendRunCatching {
            val item = app.provider.item(itemId)
            title = item.title
            document = app.provider.nfo(item)
        }
        runCatching { tabsFocus.requestFocus() }
    }
    val doc = document
    HlFormScaffold(
        title = stringResource(R.string.nfo_title),
        description = stringResource(R.string.nfo_desc),
        aside = {
            StatusPanel(
                icon = HlIcons.Document,
                label = title,
                value = doc?.path?.substringAfterLast('/') ?: "…",
                lit = doc?.info != null,
                result = doc?.path,
                resultIsError = false,
            )
        },
    ) {
        HlSegmented(
            listOf(stringResource(R.string.nfo_tab_info), stringResource(R.string.nfo_tab_xml)),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.focusRequester(tabsFocus),
            selectOnFocus = true,
        )
        if (doc != null) {
            when (tab) {
                0 -> NfoDetails(doc.info)
                else -> XmlView(doc.text)
            }
        }
    }
}

@Composable
private fun NfoDetails(info: NfoInfo?) = ScrollablePanel {
    when {
        info == null -> Text(stringResource(R.string.nfo_unreadable), style = Hl.type.body, color = Hl.colors.contentSecondary)
        info.kind == "link" -> {
            Text(stringResource(R.string.nfo_link_only), style = Hl.type.body, color = Hl.colors.contentSecondary)
            DefinitionList(ids(info))
        }
        else -> {
            val rows = buildList {
                info.title?.let { add(stringResource(R.string.label_title) to it) }
                info.originalTitle?.takeIf { it != info.title }?.let { add(stringResource(R.string.label_original_title) to it) }
                if (info.season != null && info.episode != null) add(stringResource(R.string.label_episode) to "S${info.season} E${info.episode}")
                (info.year?.toString() ?: info.premiered)?.let { add(stringResource(R.string.label_year) to it) }
                info.runtimeMin?.let { add(stringResource(R.string.label_runtime) to "$it min") }
                info.certification?.let { add(stringResource(R.string.label_certification) to it) }
                info.rating?.let { add(stringResource(R.string.label_rating) to String.format(Locale.getDefault(), "%.1f", it)) }
                info.genres.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.label_genres) to it.joinToString(", ")) }
                info.directors.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.label_directors) to it.joinToString(", ")) }
                info.studios.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.label_studios) to it.joinToString(", ")) }
                info.collection?.let { add(stringResource(R.string.label_collection) to it) }
                addAll(ids(info))
                info.tagline?.let { add(stringResource(R.string.label_tagline) to it) }
                info.actors.takeIf { it.isNotEmpty() }?.let { actors ->
                    add(stringResource(R.string.label_cast) to actors.take(8).joinToString("\n") { a -> listOfNotNull(a.name, a.role).joinToString(FACT_GAP) })
                }
            }
            DefinitionList(rows)
            info.plot?.let { plot ->
                Spacer(Modifier.height(HlSpace.s))
                FieldLabel(stringResource(R.string.label_plot))
                Text(plot, style = Hl.type.body, color = Hl.colors.content)
            }
        }
    }
}

@Composable
private fun ids(info: NfoInfo): List<Pair<String, String>> {
    val ids = listOfNotNull(info.tmdbId?.let { "TMDB $it" }, info.imdbId?.let { "IMDb $it" }, info.tvdbId?.let { "TVDB $it" })
    return if (ids.isEmpty()) emptyList() else listOf(stringResource(R.string.label_ids) to ids.joinToString(FACT_GAP))
}

/** The NFO as written, in a monospaced face since it is code. */
@Composable
private fun XmlView(text: String) {
    ScrollablePanel {
        Text(
            text.trim(),
            style = Hl.type.code,
            color = Hl.colors.contentSecondary,
        )
    }
}

/**
 * A panel the remote can focus and scroll with up and down. Up at the top lets focus
 * leave (to the tabs above).
 */
@Composable
private fun ScrollablePanel(content: @Composable () -> Unit) {
    HlScrollArea(Modifier.height(HlLayout.textPanel)) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) { content() }
    }
}
