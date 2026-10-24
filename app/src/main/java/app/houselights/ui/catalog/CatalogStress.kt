package app.houselights.ui.catalog

import app.houselights.ui.design.TwoPaneDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.Text
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.TEXT_SCALE_MAX
import app.houselights.ui.design.TEXT_SCALE_MIN
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.TitleLine
import app.houselights.ui.design.textRoom
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.screenSizePx
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/** The fullest content the screens can meet, unadjusted: what overflows here overflows on screen. */
internal fun LazyListScope.catalogStress() {
    item { StressInfoLineSection() }
    item { StressHeroSection() }
    item { StressScriptsSection() }
    item { StressScriptsOnPosterSection() }
    item { StressFactsSection() }
    item { StressDeviceSection() }
}

@Composable
private fun StressInfoLineSection() {
    // Every format a file can report; the line keeps the first four, as the library does.
    Group("Library info line", still = true) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
            Column {
                StressInfoLine(STRESS_INFO)
                StressCaption("60-character title, a rating, a score and seven formats: the formats go first, then the title")
            }
            Column {
                AtTextScale(TEXT_SCALE_MAX) { StressInfoLine(STRESS_INFO) }
                StressCaption("The same at the largest text size the app lays out at, 1.3: Android’s largest, or Grande on 1.15")
            }
            Column {
                AtTextScale(TEXT_SCALE_MIN) { StressInfoLine(STRESS_INFO) }
                StressCaption("The same at the smallest, 0.75: Compacte on Android’s smallest")
            }
        }
    }
}

@Composable
private fun StressHeroSection() {
    // A hero (« À regarder », a person's page) keeps one height whatever has focus, growing with the text scale (textRoom).
    Group("Hero", still = true) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
            Column {
                StressHero()
                StressCaption("Two-line title without logo, badges, progress and overview, in the hero's frame (on a fill)")
            }
            Column {
                AtTextScale(TEXT_SCALE_MAX) { StressHero() }
                StressCaption("The same at the largest text size the app lays out at, 1.3: the frame grows with it")
            }
            Column {
                AtTextScale(TEXT_SCALE_MIN) { StressHero() }
                StressCaption("The same at the smallest, 0.75: the frame shrinks with it")
            }
        }
    }
}

@Composable
private fun StressHero() {
    HeroInfo(STRESS_HERO, Modifier.background(Hl.colors.content.copy(alpha = HlAlpha.FILL)).height(textRoom(HlLayout.hero)))
}

@Composable
private fun StressScriptsSection() {
    // Widths that break every title in two: sideColumn for display, readingLabel for headline.
    Group("Scripts Archivo lacks", still = true) {
        StressScriptRow { script ->
            Text(script.title, style = Hl.type.display, color = Hl.colors.content)
            StressCaption("display · ${script.note}")
        }
        StressScriptRow { script ->
            Text(script.title, style = Hl.type.headline, color = Hl.colors.content, modifier = Modifier.width(HlLayout.readingLabel))
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
            Column(Modifier.width(TwoPaneDefaults.side)) {
                FactRow(STRESS_FACTS, Modifier.fillMaxWidth().background(Hl.colors.content.copy(alpha = HlAlpha.FILL)))
                StressCaption("In sideColumn, on a fill to show its edge: the last facts go whole")
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
        val screen = screenSizePx()
        val density = LocalDensity.current
        val declared = LocalContext.current.resources.displayMetrics.density
        val canvas = with(density) {
            "${screen.width.toDp().value.roundToInt()}$NBSP×$NBSP${screen.height.toDp().value.roundToInt()}${NBSP}dp"
        }
        Column {
            Text(
                "$canvas, écran ${screen.width}$NBSP×$NBSP${screen.height}${NBSP}px, densité ${stressDecimal(density.density)} " +
                    "(annoncée ${stressDecimal(declared)}), échelle du texte ${stressDecimal(density.fontScale)}",
                style = Hl.type.meta,
                color = Hl.colors.content,
            )
            StressCaption("The canvas the app lays out on, the real screen, and the density the device declares")
        }
    }
}

/** [content] on the canvas at [textScale], as « Taille du texte » and Android's setting together may set it. */
@Composable
private fun AtTextScale(textScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, textScale), content = content)
}

/** The line naming the focused title above the library's wall, at the height LibraryScreen gives it. */
@Composable
private fun StressInfoLine(item: MediaItem) {
    TitleLine(item, Modifier.height(HlLayout.infoLine))
}

/** One cell per script, each a side column wide, so the rows line up. */
@Composable
private fun StressScriptRow(cell: @Composable (StressScript) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs), verticalAlignment = Alignment.Top) {
        STRESS_SCRIPTS.forEach { script -> Column(Modifier.width(TwoPaneDefaults.side)) { cell(script) } }
    }
}

@Composable
private fun StressCaption(text: String) {
    Text(text, style = Hl.type.meta, color = Hl.colors.contentTertiary, modifier = Modifier.padding(top = HlSpace.xxs))
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

private val STRESS_HERO = sample("La Petite Marchande d’allumettes et le grand magasin", 2026, positionMs = 3_600_000).copy(
    runtimeMs = 9_720_000,
    officialRating = "16",
    communityRating = 8.2,
    genres = listOf("Comédie", "Drame", "Fantastique"),
    badges = listOf("4K", "Dolby Vision", "HDR10+", "TrueHD Atmos", "7.1"),
    overview = "La veille de Noël, une petite marchande d’allumettes se glisse dans le plus grand magasin de la ville${NBSP}: " +
        "chaque allumette qu’elle frotte allume un rayon, une vitrine, un souvenir, jusqu’à la fermeture des portes.",
)

private val STRESS_FACTS = listOf(
    "1967",
    formatDuration(6_300_000),
    "1,85:1",
    "Français",
    "Il reste 20${NBSP}min",
    "24,3${NBSP}Go",
)

