package app.houselights.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.R
import app.houselights.providers.local.FirstTitleRow
import app.houselights.providers.local.Folder
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import app.houselights.providers.local.Volume
import app.houselights.ui.design.FirstScanDefaults
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.FirstTitle
import app.houselights.ui.design.components.FirstWall
import app.houselights.ui.design.components.FirstWallState
import app.houselights.ui.design.components.FolderBlock
import app.houselights.ui.design.components.GoHomeButton
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.focusKey
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What home's first scan shows beside its progress ([HomeViewModel.firstScan]): the [folder] read (the first while the
 * pass reaches none), the titles found so far as its wall takes them (the first found, then the latest) and how many
 * were [found].
 */
@Immutable
data class FirstScanShows(val folder: Folder? = null, val titles: List<FirstTitle> = emptyList(), val found: Int = 0)

/** A film or show of the library as the first scan's wall shows it, its poster as the library gives pictures. */
internal fun firstTitleOf(row: FirstTitleRow) =
    FirstTitle(row.id, row.title, row.year, row.poster?.takeIf { it.isNotEmpty() }?.let { LocalProvider.image(it, ImageType.POSTER) })

/**
 * « Aller à l'accueil » chosen on home's first scan, kept with the library's first pass ([ScanState.firstPass]) for as
 * long as the app runs, not with the screen, whose saved state Back clears as it quits: leaving the app and coming back
 * lands on home's grid while that pass goes on. Forgotten once the pass ends, so another first pass holds home again.
 */
class FirstScanLeft(scope: CoroutineScope, private val scan: StateFlow<ScanState>) {
    private val chosen = MutableStateFlow(false)

    /** Whether « Aller à l'accueil » was chosen during the first pass under way. */
    val left: StateFlow<Boolean> = chosen

    init {
        scope.launch { scan.map { it.firstPass }.distinctUntilChanged().collect { first -> if (!first) chosen.value = false } }
    }

    /** « Aller à l'accueil »: chosen for the first pass under way, none otherwise. */
    fun leave() {
        if (scan.value.firstPass) chosen.value = true
    }
}

/**
 * Home's first scan under its head (DESIGN.md, Layout), only it reading [progress] and what it [shows]. On the margin,
 * TMDB's trouble with the scan and « Modifier la clé » for a refused key stand over the folder's block, which stands on
 * the safe area's foot, so nothing in it moves as they come. At the safe area's end, on the same foot, the wall of the
 * titles found ([wall], kept with home so it is found again as it was), « Aller à l'accueil » over it from the first
 * title while the library's first pass runs ([firstPass]), taking focus as it comes only while nothing has it
 * ([focusFree]). The button and « Modifier la clé » are named in home's focus [memory], so focus comes back to them.
 */
@Composable
internal fun FirstScan(
    progress: StateFlow<ScanState>,
    shows: StateFlow<FirstScanShows>,
    wall: FirstWallState,
    memory: FocusMemory,
    firstPass: Boolean,
    onGoHome: () -> Unit,
    focusFree: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val found by shows.collectAsStateWithLifecycle()
    Row(modifier.padding(start = HlSpace.safeH, end = HlSpace.safeH, bottom = HlSpace.safeV)) {
        FolderColumn(progress, found.folder, memory, Modifier.width(FirstScanDefaults.column).fillMaxHeight().padding(top = HlSpace.l))
        Spacer(Modifier.weight(1f))
        Column(Modifier.width(FirstScanDefaults.wall).fillMaxHeight()) {
            Spacer(Modifier.weight(1f))
            if (firstPass && found.titles.isNotEmpty()) {
                GoHomeButton(onGoHome, Modifier.focusKey(GO_HOME, memory), focusFree = focusFree)
                Spacer(Modifier.height(FirstScanDefaults.buttonGap))
            }
            FirstWall(found.titles, found.found, state = wall)
        }
    }
}

/**
 * The first scan's column, standing on the safe area's foot: TMDB's trouble with the scan over the [folder]'s block, the
 * one recomposed as each file is read ([progress]).
 */
@Composable
private fun FolderColumn(progress: StateFlow<ScanState>, folder: Folder?, memory: FocusMemory, modifier: Modifier = Modifier) {
    val scan by progress.collectAsStateWithLifecycle()
    Column(modifier) {
        Spacer(Modifier.weight(1f))
        scan.tmdbIssue?.toUiError()?.let { issue ->
            ScanNote(issue, memory)
            Spacer(Modifier.height(HlSpace.xl))
        }
        if (folder != null) {
            FolderBlock(
                folder.name,
                placeOf(folder),
                scan.processed,
                scan.total,
                protocol = folder.protocol.name.takeIf { folder.protocol != Protocol.LOCAL },
                current = scan.current,
                listed = scan.listed,
            )
        }
    }
}

/**
 * TMDB's trouble with the scan, which goes on, the titles named after their files; for a refused key, « Modifier la
 * clé » under it, which never takes focus as it comes.
 */
@Composable
private fun ScanNote(issue: UiError, memory: FocusMemory, modifier: Modifier = Modifier) {
    Column(modifier) {
        AttentionNote(stringResource(noteOf(issue)), style = Hl.type.meta)
        scanNoteAction(issue)?.let { action ->
            val fix = LocalErrorFix.current
            Spacer(Modifier.height(HlSpace.m))
            HlButton(stringResource(action.label), onClick = { fix(action) }, modifier = Modifier.focusKey(EDIT_KEY, memory))
        }
    }
}

/** Where [folder] is, as its block says it: its NAS by the name it gave, else the player's disk as « Ajouter un dossier » names it. */
@Composable
private fun placeOf(folder: Folder): String = when {
    folder.protocol != Protocol.LOCAL -> folder.host
    folder.host == Volume.INTERNAL -> stringResource(R.string.disk_internal)
    else -> stringResource(R.string.disk_usb)
}

/** TMDB's trouble in the first scan's words: refused, a certificate refused, or out of reach whatever else failed. */
private fun noteOf(issue: UiError): Int = when (issue) {
    UiError.TMDB_KEY_REFUSED -> R.string.home_scan_tmdb_refused
    UiError.CERTIFICATE -> R.string.home_scan_tmdb_certificate
    else -> R.string.home_scan_tmdb_offline
}

/** The first scan's fix for [issue]: edit a refused TMDB key. */
internal fun scanNoteAction(issue: UiError?): ErrorAction? = issue?.action?.takeIf { it == ErrorAction.EDIT_TMDB_KEY }

/** The tile at the end of home's head without a grid, in the screen's focus memory: focus comes back to it from Réglages. */
internal val SETTINGS_TILE = FocusKey.action("settings-tile")

/** « Aller à l'accueil » and « Modifier la clé » on the first scan, in the screen's focus memory. */
private val GO_HOME = FocusKey.action("go-home")
private val EDIT_KEY = FocusKey.action("edit-key")
