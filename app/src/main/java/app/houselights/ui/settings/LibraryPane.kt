package app.houselights.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import app.houselights.providers.local.address
import app.houselights.settings.AppSettings
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlDivider
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.PosterThumb
import app.houselights.ui.design.components.HlPanelButton
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.ProgressLine
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.scanErrorText
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import app.houselights.core.CollectionThreshold

internal fun LazyListScope.libraryPane(s: AppSettings, onAddFolder: () -> Unit, onChangeSource: () -> Unit) {
    when (val source = s.source) {
        SourceConfig.Local -> {
            item { Section(stringResource(R.string.section_source)) { Sources() } }
            item { LibraryActions(onAddFolder, onChangeSource) }
        }
        is SourceConfig.Jellyfin -> {
            item {
                Section(stringResource(R.string.source_jellyfin)) {
                    HlSettingGroup(null) {
                        val server = source.serverName ?: stringResource(R.string.source_jellyfin)
                        HlSettingRow(stringResource(R.string.label_server), value = server)
                        HlSettingRow(stringResource(R.string.label_address), value = source.serverUrl)
                        val encrypted = JellyfinClient.isEncrypted(source.serverUrl)
                        HlSettingRow(
                            stringResource(R.string.label_connection),
                            value = stringResource(if (encrypted) R.string.connection_encrypted else R.string.connection_plain),
                        )
                        source.userName?.let { HlSettingRow(stringResource(R.string.label_user), value = it) }
                    }
                }
            }
            item { HlSettingGroup(null) { SourceRow(onChangeSource) } }
        }
        null -> {}
    }
}

/** One card per watched folder. */
@Composable
private fun Sources() {
    val vm = settingsViewModel()
    val scope = rememberCoroutineScope()
    val sources by vm.library.folders.collectAsStateWithLifecycle(emptyList())
    val scan by vm.library.scan.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<SourceEntity?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        sources.forEach { source ->
            SourceCard(
                source,
                scanning = scan.running && scan.sourceName == source.name,
                progress = if (scan.total > 0) scan.processed.toFloat() / scan.total else 0f,
                current = scan.current,
                error = scan.errors[source.name],
                onClick = { actionsFor = source },
            )
        }
    }
    actionsFor?.let { source ->
        SourceActions(
            source,
            onDismiss = { actionsFor = null },
            // Launched from here: the sheet has left the composition by the time it runs.
            onRemove = { scope.launch { vm.library.removeFolder(source) } },
        )
    }
}

/**
 * What can be done with a watched folder, beside the list: scan it now, or remove it, which asks
 * first, in the sheet itself, with focus on Cancel.
 */
@Composable
private fun SourceActions(source: SourceEntity, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val vm = settingsViewModel()
    val posters by remember(source.id) { vm.library.latestPosters(source, HEADER_POSTERS) }.collectAsStateWithLifecycle(emptyList())
    val kind = stringResource(if (source.kind == SourceKind.MOVIES) R.string.library_movies else R.string.library_shows)
    var confirming by remember { mutableStateOf(false) }
    var backFromConfirm by remember { mutableStateOf(false) }
    val scan = remember { FocusRequester() }
    val remove = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    HlSideSheet(
        title = sourceName(source),
        onDismiss = onDismiss,
        facts = listOf(kind.takeIf { !source.name.equals(it, ignoreCase = true) }, source.protocol.name),
        description = source.address(withScheme = false),
        artwork = if (posters.isEmpty()) null else ({ PosterFan(posters) }),
        initialFocus = scan,
    ) { sheet ->
        if (!confirming) {
            LaunchedEffect(backFromConfirm) { if (backFromConfirm) runCatching { remove.requestFocus() } }
            HlSettingRow(
                stringResource(R.string.scan_now),
                onClick = { sheet.dismiss { vm.library.scan() } },
                chevron = false,
                modifier = Modifier.focusRequester(scan),
            )
            Spacer(Modifier.height(HlSpace.s))
            HlDivider()
            Spacer(Modifier.height(HlSpace.s))
            HlSettingRow(
                stringResource(R.string.remove_folder),
                description = stringResource(R.string.remove_folder_desc),
                onClick = { confirming = true },
                chevron = false,
                modifier = Modifier.focusRequester(remove),
            )
        } else {
            LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
            Text(stringResource(R.string.remove_folder_confirm, sourceName(source)), style = Hl.type.lead, color = Hl.colors.content)
            Spacer(Modifier.height(HlSpace.xs))
            Text(stringResource(R.string.remove_folder_desc), style = Hl.type.body, color = Hl.colors.contentSecondary)
            Spacer(Modifier.height(HlSpace.l))
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                HlButton(
                    stringResource(R.string.cancel),
                    onClick = {
                        confirming = false
                        backFromConfirm = true
                    },
                    modifier = Modifier.focusRequester(cancel),
                )
                HlButton(stringResource(R.string.remove), onClick = { sheet.dismiss(onRemove) })
            }
        }
    }
}

/** The latest posters of a folder, side by side, at the head of its sheet. */
@Composable
private fun PosterFan(paths: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
        paths.take(HEADER_POSTERS).forEach { path ->
            PosterThumb(LocalProvider.image(path, ImageType.POSTER), width = HlSize.rowPreviewPoster)
        }
    }
}

@Composable
private fun sourceName(source: SourceEntity) = source.name.replaceFirstChar { it.uppercase() }

/**
 * A watched folder, headed by its latest posters as a cover from edge to edge; then its name and
 * kind, where it lives, and how its last scan went (or the scan going on). OK opens its actions.
 */
@Composable
private fun SourceCard(
    source: SourceEntity,
    scanning: Boolean,
    progress: Float,
    current: String?,
    error: ScanError?,
    onClick: () -> Unit,
) {
    val c = Hl.colors
    val library = settingsViewModel().library
    val count by remember(source.id) { library.titleCount(source) }.collectAsStateWithLifecycle(0)
    val ultraHd by remember(source.id) { library.ultraHdCount(source) }.collectAsStateWithLifecycle(0)
    val posters by remember(source.id) { library.latestPosters(source, STRIP) }.collectAsStateWithLifecycle(emptyList())
    val kind = stringResource(if (source.kind == SourceKind.MOVIES) R.string.library_movies else R.string.library_shows)

    HlPanelButton(onClick, Modifier.fillMaxWidth()) {
        Column {
            PosterCover(posters)
            Column(Modifier.padding(HlInset.card)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    Text(sourceName(source), style = Hl.type.lead, color = c.content)
                    if (!source.name.equals(kind, ignoreCase = true)) Badge(kind)
                    Badge(source.protocol.name)
                }
                Spacer(Modifier.height(HlSpace.xxs))
                Text(
                    source.address(withScheme = false),
                    style = Hl.type.meta,
                    color = c.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(HlSpace.m))
                when {
                    scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(progress.coerceAtLeast(PROGRESS_MIN), Modifier.width(HlSize.progressShort))
                        Spacer(Modifier.width(HlSpace.s))
                        Text(
                            current ?: "",
                            style = Hl.type.detail,
                            color = c.contentSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    error != null -> Text(scanErrorText(error, source.host), style = Hl.type.detail, color = c.tungsten)
                    else -> FactRow(
                        listOf(
                            pluralStringResource(R.plurals.title_count, count, count),
                            if (ultraHd > 0) pluralStringResource(R.plurals.uhd_count, ultraHd, ultraHd) else null,
                            stringResource(R.string.last_scan, relativeTime(source.lastScanAt)),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The latest posters of a folder, side by side from edge to edge at the head of its card, parted
 * by hairlines; the slots still free keep their frame, so every cover has the same rhythm.
 */
@Composable
private fun PosterCover(paths: List<String>) {
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { HlSize.poster.roundToPx() }
    Row(Modifier.fillMaxWidth().background(Hl.colors.background), horizontalArrangement = Arrangement.spacedBy(HlSize.hairline)) {
        repeat(STRIP) { i ->
            Box(Modifier.weight(1f).aspectRatio(2f / 3f).background(Hl.colors.surfaceRaised)) {
                paths.getOrNull(i)?.let { path ->
                    AsyncImage(
                        model = LocalProvider.image(path, ImageType.POSTER).url(widthPx),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryActions(onAddFolder: () -> Unit, onChangeSource: () -> Unit) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val scan by vm.library.scan.collectAsStateWithLifecycle()
    val sources by vm.library.folders.collectAsStateWithLifecycle(emptyList())
    var editKey by remember { mutableStateOf(false) }
    val lastScan = sources.maxOfOrNull { it.lastScanAt } ?: 0L
    val hasBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank()

    HlSettingGroup(null, Modifier.revealOnFocus()) {
        HlSettingRow(stringResource(R.string.add_folder), onClick = onAddFolder, chevron = false, trailing = { PlusIcon() })
        HlSettingRow(
            stringResource(R.string.scan_now),
            value = if (scan.running) stringResource(R.string.scan_running, scan.processed, scan.total) else relativeTime(lastScan),
            valueLit = scan.running,
            onClick = { vm.library.scan() },
            chevron = false,
        )
        HlSettingRow(
            stringResource(R.string.refresh_metadata),
            description = stringResource(R.string.refresh_metadata_desc),
            onClick = { vm.library.scan(refreshMetadata = true) },
            chevron = false,
        )
        HlSettingRow(
            stringResource(R.string.tmdb_key),
            value = stringResource(
                when {
                    s.tmdbApiKey != null -> R.string.tmdb_personal
                    hasBuiltIn -> R.string.tmdb_builtin_short
                    else -> R.string.tmdb_missing_short
                }
            ),
            valueLit = s.tmdbApiKey != null || !hasBuiltIn,
            onClick = { editKey = true },
        )
        CollectionsRow()
        SourceRow(onChangeSource)
    }
    if (editKey) TmdbKeyDialog(onDismiss = { editKey = false })
}

/** When a saga shows as a collection: from two films (default), always, or never. */
@Composable
private fun CollectionsRow() {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    var choose by remember { mutableStateOf(false) }
    @Composable
    fun option(label: Int, description: Int) = ChoiceOption(stringResource(label), stringResource(description))
    val options = listOf(
        CollectionThreshold.TWO_FILMS to option(R.string.collections_two, R.string.collections_two_desc),
        CollectionThreshold.ANY to option(R.string.collections_all, R.string.collections_all_desc),
        CollectionThreshold.HIDDEN to option(R.string.collections_off, R.string.collections_off_desc),
    )
    val current = options.indexOfFirst { it.first == s.collectionMinFilms }.coerceAtLeast(0)
    HlSettingRow(
        stringResource(R.string.collections_setting),
        value = options[current].second.label,
        description = stringResource(R.string.collections_setting_desc),
        onClick = { choose = true },
    )
    if (choose) {
        HlChoiceSheet(
            title = stringResource(R.string.collections_setting),
            options = options.map { it.second },
            selected = current,
            onSelect = { i -> vm.setCollectionMinFilms(options[i].first) },
            onDismiss = { choose = false },
            description = stringResource(R.string.collections_setting_desc),
        )
    }
}

@Composable
private fun SourceRow(onChangeSource: () -> Unit) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    HlSettingRow(
        stringResource(R.string.change_source_row),
        value = stringResource(if (s.source is SourceConfig.Jellyfin) R.string.source_jellyfin else R.string.source_local),
        description = stringResource(R.string.change_source_desc),
        onClick = { vm.changeSource(onChangeSource) },
    )
}

@Composable
private fun PlusIcon() {
    androidx.tv.material3.Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(HlSize.icon))
}

@Composable
private fun TmdbKeyDialog(onDismiss: () -> Unit) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(s.tmdbApiKey ?: "") }
    val field = remember { FocusRequester() }
    val hasBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank()
    HlSideSheet(
        stringResource(R.string.tmdb_key),
        onDismiss,
        description = stringResource(R.string.tmdb_desc),
        initialFocus = field,
    ) { sheet ->
        HlTextField("", key, { key = it }, password = true, modifier = Modifier.fillMaxWidth().focusRequester(field))
        Spacer(Modifier.height(HlSpace.s))
        Text(
            stringResource(if (hasBuiltIn) R.string.tmdb_key_builtin else R.string.tmdb_key_missing),
            style = Hl.type.detail,
            color = if (hasBuiltIn) Hl.colors.contentSecondary else Hl.colors.tungsten,
        )
        Spacer(Modifier.height(HlSpace.l))
        HlButton(
            stringResource(R.string.save),
            { vm.setTmdbApiKey(key) { sheet.dismiss() } },
            icon = Icons.Filled.Check,
            emphasis = Emphasis.Primary,
        )
    }
}
/** Posters in a source card's strip. */
private const val STRIP = 6

/** Posters at the head of a folder's sheet. */
private const val HEADER_POSTERS = 3

/** A scan that has just started still shows a sliver of progress. */
private const val PROGRESS_MIN = 0.02f
