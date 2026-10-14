package app.houselights.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlIconDisc
import app.houselights.ui.design.components.HlListItem
import app.houselights.ui.design.components.HlMeter
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlPanel
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.MeterLegend
import app.houselights.ui.design.components.MeterSegment
import app.houselights.ui.design.components.PanelLevel
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.SectionHeader
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.Wordmark

/**
 * Every token and component of the design system in its states, for review on a real screen.
 * Debug builds only (Settings, About); not translated, as it is a tool for whoever works on the
 * app.
 */
@Composable
fun CatalogScreen() {
    val c = Hl.colors
    val t = Hl.type
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        Backdrop(null, BackdropStyle.Quiet)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = HlSpace.safeH, vertical = HlSpace.safeV),
            verticalArrangement = Arrangement.spacedBy(HlSpace.xl),
        ) {
            item { ScreenTitle("Design system") }

            item {
                Group("Colour", Modifier.focusRequester(first), still = true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                        listOf(
                            "background" to c.background, "surface" to c.surface, "raised" to c.surfaceRaised,
                            "outline" to c.outline, "decorative" to c.contentDecorative, "tertiary" to c.contentTertiary,
                            "secondary" to c.contentSecondary, "content" to c.content, "tungsten" to c.tungsten,
                        ).forEach { (name, color) -> Swatch(name, color) }
                    }
                }
            }

            item {
                Group("Type: the voice of the poster, of reading, technical", still = true) {
                    listOf(
                        "display" to t.display, "headline" to t.headline, "title" to t.title, "lead" to t.lead,
                        "body" to t.body, "caption" to t.caption, "label" to t.label, "meta" to t.meta,
                        "figure" to t.figure, "spec" to t.spec, "badge" to t.badge, "code" to t.code,
                        "billing" to t.billing, "wordmark" to t.wordmark,
                    ).forEach { (name, style) -> Specimen(name, style) }
                }
            }

            item {
                Group("Controls") {
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.Top) {
                        HlButton("Lire", {}, icon = Icons.Filled.PlayArrow, detail = "1 h 53", emphasis = Emphasis.Primary)
                        HlButton("Secondary", {})
                        HlIconButton(HlIcons.Info, "Icon button", {})
                    }
                    var place by remember { mutableIntStateOf(0) }
                    var season by remember { mutableIntStateOf(2) }
                    HlSegmented(listOf("Accueil", "Films", "Séries"), selected = place, onSelect = { place = it })
                    HlSegmented((1..12).map { "Saison $it" }, selected = season, onSelect = { season = it }, selectOnFocus = true, scrollable = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                        listOf("Ultra HD", "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1", "12").forEach { Badge(it) }
                        Badge("Your choice", lit = true)
                    }
                    FactRow(listOf("2026", "1 h 53", "8,2"))
                }
            }

            item {
                Group("Panels and status") {
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                        HlPanel(Modifier.weight(1f)) { Text("Quiet panel", style = t.lead, color = c.content) }
                        HlPanel(Modifier.weight(1f), lit = true) { Text("Lit panel", style = t.lead, color = c.content) }
                        HlPanel(Modifier.weight(1f), level = PanelLevel.Raised) { Text("Raised panel", style = t.lead, color = c.content) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.CenterVertically) {
                        HlIconDisc(HlIcons.Folder)
                        HlIconDisc(HlIcons.Folder, lit = true)
                        HlStatus(StatusState.On, "Online")
                        HlStatus(StatusState.Pending, "Testing")
                        HlStatus(StatusState.Off, "Offline")
                    }
                    Column(Modifier.widthIn(max = HlLayout.readable), verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                        HlMeter(listOf(MeterSegment(0.3f, c.content), MeterSegment(0.1f, c.tungsten), MeterSegment(0.05f, c.contentSecondary)))
                        MeterLegend("Online images", "300 MB", c.content)
                        MeterLegend("Local images", "100 MB", c.tungsten)
                    }
                }
            }

            item {
                Group("Lists and settings") {
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                        Column(Modifier.width(HlLayout.sideColumn), verticalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                            HlNavItem(HlIcons.Folder, "Selected", "8 titles", selected = true, onFocused = {})
                            HlNavItem(HlIcons.Display, "Other", "4K 60 Hz", selected = false, onFocused = {})
                        }
                        Column(Modifier.widthIn(max = HlLayout.readable)) {
                            HlSettingGroup("Group") {
                                HlSettingRow("Leads somewhere", value = "Value", onClick = {})
                                HlSettingRow("Your choice", value = "Chosen", valueLit = true, description = "A line of description", onClick = {})
                                HlSettingRow("States a value", value = "1920 × 1080")
                            }
                        }
                    }
                    HlListItem("List item", "Its description", {}, icon = HlIcons.Folder)
                    HlEmptyState("What happened, and what to do about it.", title = "Empty or error state", action = "Try again")
                }
            }

            item { Wordmark(style = t.wordmarkHero) }
        }
    }
}

/**
 * A section of the catalogue. Sections with nothing to focus ([still]) take focus themselves, so
 * the remote can step through every one.
 */
@Composable
private fun Group(title: String, modifier: Modifier = Modifier, still: Boolean = false, content: @Composable () -> Unit) {
    Column(modifier.revealOnFocus().then(if (still) Modifier.focusable() else Modifier), verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        SectionHeader(title)
        content()
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Column {
        Box(Modifier.size(HlSize.disc).clip(HlShapes.panel).background(color))
        Spacer(Modifier.height(HlSpace.xxs))
        Text(name, style = Hl.type.caption, color = Hl.colors.contentSecondary)
    }
}

@Composable
private fun Specimen(name: String, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Hl.type.caption, color = Hl.colors.contentTertiary, modifier = Modifier.width(HlLayout.specLanguage))
        Text("Houselights 2,39:1 ${style.fontSize.value.toInt()} sp", style = style, color = Hl.colors.content, maxLines = 1, modifier = Modifier.padding(start = HlSpace.s))
    }
}
