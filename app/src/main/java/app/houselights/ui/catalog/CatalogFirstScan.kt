package app.houselights.ui.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.ui.design.FirstScanDefaults
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.FirstTitle
import app.houselights.ui.design.components.FirstWall
import app.houselights.ui.design.components.FolderBlock
import app.houselights.ui.design.components.GoHomeButton
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.util.ErrorAction
import kotlinx.coroutines.delay

// Home's first scan on the app's own pictures: the banner as a poster, every third title without one.

/** Home's first scan: the folder's block and its wall, listed, counted, filling, in one-sheets, then live. */
internal fun LazyListScope.catalogFirstScan() {
    item {
        Group("First scan: the folder listed, nothing found yet; then the videos its listing found") {
            Specimen("listed: its name to its measure, where it is, the sliver; the wall's empty frames") {
                Scene(listed = 0, titles = emptyList())
            }
            Specimen("with its count, in the file's place: thousands grouped, a word that changes once a second") {
                Scene(listed = LISTED, titles = emptyList())
            }
        }
    }
    item {
        Group("First scan: the files counted, the wall filling first found first, « Aller à l'accueil » over it") {
            val titles = samples().take(FILLING)
            Specimen("three titles: the button came with the first, focus on it as nothing had it") {
                Scene(processed = FILLING, titles = titles, button = true)
            }
            Specimen("TMDB not answering: one-sheets named after their files, a poster of the folder as a poster") {
                val named = samples().take(FULL).map { title -> title.copy(poster = title.poster.takeIf { title.id == 1L }) }
                Scene(processed = FULL, titles = named)
            }
            Specimen("TMDB refusing the key: its note and « Modifier la clé » over the block, which stays on the foot") {
                val named = samples().take(FILLING).map { title -> title.copy(poster = null) }
                Scene(processed = FILLING, titles = named, button = true, refused = true)
            }
        }
    }
    item {
        Group("First scan's wall, live: each title arriving over TINT as its picture is decoded; full, renewed in place") {
            Specimen("an arrival: a title found every two seconds, then again from empty") { Filling() }
            Specimen("a renewal: full, the latest title found comes into the oldest frame, one a step") { Renewing() }
        }
    }
}

/**
 * The first scan as home lays it out: the folder's block on the margin, TMDB's note over it when the key is [refused],
 * the wall at the safe area's end, both on its foot.
 */
@Composable
private fun Scene(
    titles: List<FirstTitle>,
    modifier: Modifier = Modifier,
    processed: Int = 0,
    listed: Int = 0,
    button: Boolean = false,
    refused: Boolean = false,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column {
            if (refused) {
                AttentionNote(stringResource(R.string.home_scan_tmdb_refused), Modifier.width(FirstScanDefaults.column))
                Spacer(Modifier.height(HlSpace.m))
                HlButton(stringResource(ErrorAction.EDIT_TMDB_KEY.label), onClick = {})
                Spacer(Modifier.height(HlSpace.xl))
            }
            FolderBlock(
                "Films",
                "DS920",
                processed = processed,
                total = if (processed > 0) TOTAL else 0,
                protocol = "NFS",
                current = "Bugonia (2025).mkv".takeIf { processed > 0 },
                listed = listed,
            )
        }
        Spacer(Modifier.weight(1f))
        Column {
            if (button) {
                GoHomeButton(onClick = {})
                Spacer(Modifier.height(FirstScanDefaults.buttonGap))
            }
            FirstWall(titles, found = titles.size)
        }
    }
}

/** The wall filling as a scan finds a title every [LIVE_MS], then the same from empty. */
@Composable
private fun Filling(modifier: Modifier = Modifier) {
    val all = samples()
    var round by remember { mutableIntStateOf(0) }
    var found by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        while (found < FirstScanDefaults.FRAMES) {
            delay(LIVE_MS)
            found++
        }
        delay(LIVE_MS * 3)
        // A new wall from none found, so it fills again rather than coming with them all.
        found = 0
        round++
    }
    key(round) { FirstWall(all.take(found), found, modifier) }
}

/** A full wall as a scan goes on finding a title every [LIVE_MS]: the first found, then the latest, as home reads them. */
@Composable
private fun Renewing(modifier: Modifier = Modifier) {
    val all = samples()
    var found by remember { mutableIntStateOf(FirstScanDefaults.FRAMES) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(LIVE_MS)
            found++
        }
    }
    val latest = all[(found - 1) % all.size].copy(id = found.toLong())
    FirstWall(all.take(FirstScanDefaults.FRAMES) + latest, found, modifier)
}

/** The sample titles, ids from 1, every third without a poster. */
@Composable
private fun samples(): List<FirstTitle> {
    val art: ImageRef = shippedImage(R.drawable.banner)
    return remember(art) {
        SAMPLES.mapIndexed { i, (title, year) -> FirstTitle(i + 1L, title, year, art.takeIf { i % 3 != 2 }) }
    }
}

private val SAMPLES = listOf(
    "Dune" to 2021, "Bugonia" to 2025, "Arrival" to 2016, "Heat" to 1995, "Ran" to 1985, "Sicario" to 2015,
    "Le Samouraï" to 1967, "Paterson" to 2016, "Aftersun" to 2022, "Drive" to 2011, "Tár" to 2022, "Alien" to 1979,
)

private const val LISTED = 1_240
private const val TOTAL = 412
private const val FILLING = 3
private const val FULL = 8

/** How often the live specimens find a title: more often than the wall takes one, so its pace shows. */
private const val LIVE_MS = 1_500L
