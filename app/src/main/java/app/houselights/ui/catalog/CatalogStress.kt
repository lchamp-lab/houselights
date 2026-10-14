package app.houselights.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.BadgeRow
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.formatDuration
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The fullest content the screens can meet, rebuilt from the components they use, and the device
 * they are measured on. Shown as it is today: what overflows or vanishes here does so on screen.
 */
internal fun LazyListScope.catalogStress() {
    item { StressTopBarSection() }
    item { StressInfoLineSection() }
    item { StressScriptsSection() }
    item { StressScriptsOnPosterSection() }
    item { StressFactsSection() }
    item { StressDeviceSection() }
}

@Composable
private fun StressTopBarSection() {
    Group("Home top bar on the brightest image") {
        Column {
            StressTopBar(STRESS_LIBRARIES)
            StressCaption("Nine libraries with long names")
        }
        Column {
            StressTopBar(listOf("Films", "Séries", "Documentaires"))
            StressCaption("Three libraries, for comparison")
        }
    }
}

@Composable
private fun StressInfoLineSection() {
    // Every format a file can report; the line keeps the first four, as the library does.
    Group("Library info line", still = true) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
            Column {
                StressInfoLine(STRESS_INFO)
                StressCaption("60-character title, a rating, a score and seven formats")
            }
            Column {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, STRESS_LARGEST_TEXT)) {
                    StressInfoLine(STRESS_INFO)
                }
                StressCaption("The same at Android’s largest text size, 1.3")
            }
        }
    }
}

@Composable
private fun StressScriptsSection() {
    // Widths that break every title over two lines: sideColumn for display, definitionLabel
    // (a few dp under the Vietnamese line) for headline.
    Group("Scripts Archivo lacks", still = true) {
        StressScriptRow { script ->
            Text(script.title, style = Hl.type.display, color = Hl.colors.content)
            StressCaption("display · ${script.note}")
        }
        StressScriptRow { script ->
            Text(script.title, style = Hl.type.headline, color = Hl.colors.content, modifier = Modifier.width(HlLayout.definitionLabel))
            StressCaption("headline · ${script.note}")
        }
    }
}

@Composable
private fun StressScriptsOnPosterSection() {
    // cardTitle is only ever set in a card's fallback: here at the poster's own width.
    Group("Scripts Archivo lacks, on a poster") {
        StressScriptRow { script ->
            PosterCard(sample(script.title, script.year), {})
            StressCaption("cardTitle · ${script.note}")
        }
    }
}

@Composable
private fun StressFactsSection() {
    Group("Six facts in a side column", still = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(HlLayout.sideColumn)) {
                FactRow(STRESS_FACTS, Modifier.fillMaxWidth().background(Hl.colors.surface))
                StressCaption("In sideColumn, on surface to show its edge")
            }
            Column {
                FactRow(STRESS_FACTS)
                StressCaption("With the room they need")
            }
        }
    }
}

@Composable
private fun StressDeviceSection() {
    Group("Device", still = true) {
        val config = LocalConfiguration.current
        val density = LocalDensity.current
        Column {
            Text(
                "${config.screenWidthDp}$NBSP×$NBSP${config.screenHeightDp}${NBSP}dp, " +
                    "densité ${stressDecimal(density.density)}, échelle du texte ${stressDecimal(density.fontScale)}",
                style = Hl.type.meta,
                color = Hl.colors.content,
            )
            StressCaption("Screen size, density and text scale, as Compose sees them")
        }
    }
}

/**
 * The home screen's top bar, composed as HomeScreen does, on projector white: the lightest
 * backdrop a film can bring behind it. The catalogue already sits inside the side margins, so the
 * bar keeps the width it has on screen; choosing a library only moves the pill.
 */
@Composable
private fun StressTopBar(libraries: List<String>) {
    Row(
        Modifier.fillMaxWidth().background(Hl.colors.content).padding(top = HlSpace.safeV, bottom = HlSpace.m),
        verticalAlignment = Alignment.Top,
    ) {
        var current by remember { mutableIntStateOf(0) }
        HlSegmented(
            options = listOf(stringResource(R.string.nav_home)) + libraries,
            selected = current,
            onSelect = { current = it },
        )
        Spacer(Modifier.weight(1f))
        HlIconButton(Icons.Filled.Settings, stringResource(R.string.nav_settings), onClick = {})
    }
}

/** The line naming the focused title above the library's wall, composed as LibraryScreen does. */
@Composable
private fun StressInfoLine(item: MediaItem) {
    Row(Modifier.height(HlLayout.infoLine), verticalAlignment = Alignment.CenterVertically) {
        Text(item.title, style = Hl.type.entry, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(HlSpace.m))
        MetaLine(item, showGenres = false)
        if (item.badges.isNotEmpty()) {
            Spacer(Modifier.width(HlSpace.l))
            BadgeRow(item.badges.take(4))
        }
    }
}

/** One cell per script, each a side column wide, so the rows line up. */
@Composable
private fun StressScriptRow(cell: @Composable (StressScript) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs), verticalAlignment = Alignment.Top) {
        STRESS_SCRIPTS.forEach { script -> Column(Modifier.width(HlLayout.sideColumn)) { cell(script) } }
    }
}

@Composable
private fun StressCaption(text: String) {
    Text(text, style = Hl.type.caption, color = Hl.colors.contentTertiary, modifier = Modifier.padding(top = HlSpace.xxs))
}

/** "2,0", "2,625", "1,15": French decimals, at least one and up to three. */
private fun stressDecimal(value: Float): String =
    DecimalFormat("0.0##", DecimalFormatSymbols(Locale.FRANCE)).format(value.toDouble())

/** A title in a script, and where its glyphs come from. */
private class StressScript(val title: String, val year: Int, val note: String)

private val STRESS_SCRIPTS = listOf(
    StressScript("Ổn Định Ở Đâu", 2024, "Vietnamese, in Archivo"),
    StressScript("Жизнь и судьба", 2012, "Cyrillic, system fallback"),
    StressScript("Ζορμπάς ο Έλλην", 1964, "Greek, system fallback"),
)

private val STRESS_LIBRARIES = listOf(
    "Films 4K HDR et Dolby Vision",
    "Séries d’animation japonaise",
    "Documentaires animaliers",
    "Concerts et spectacles vivants",
    "Classiques du cinéma français",
    "Courts métrages d’étudiants",
    "Films de famille et vacances",
    "Émissions de télévision",
    "Collections et sagas complètes",
)

private val STRESS_INFO = MediaItem(
    id = "catalog-info-line",
    kind = MediaKind.MOVIE,
    title = "La Petite Marchande d’allumettes et le grand magasin de Noël",
    year = 2026,
    runtimeMs = 9_720_000,
    officialRating = "16",
    communityRating = 8.2,
    badges = listOf("4K", "Dolby Vision", "HDR10+", "HDR10", "HLG", "TrueHD Atmos", "7.1"),
)

private val STRESS_FACTS = listOf(
    "1967",
    formatDuration(6_300_000),
    "1,85:1",
    "Français",
    "Il reste 20${NBSP}min",
    "24,3${NBSP}Go",
)

/** Android's largest text size setting. */
private const val STRESS_LARGEST_TEXT = 1.3f
