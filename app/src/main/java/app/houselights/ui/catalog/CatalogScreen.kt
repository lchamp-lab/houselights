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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.Text
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlPalette
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlActionRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlDivider
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
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MeterInk
import app.houselights.ui.design.components.MeterLegend
import app.houselights.ui.design.components.MeterSegment
import app.houselights.ui.design.components.PanelLevel
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.SheetConfirm
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.Wordmark
import app.houselights.ui.design.emberLight
import app.houselights.ui.util.NBSP
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
                    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                        // The palette: the colours themselves, named by their lightness.
                        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                            listOf(
                                "N0" to HlPalette.N0, "N8" to HlPalette.N8, "N21" to HlPalette.N21, "N28" to HlPalette.N28,
                                "N36" to HlPalette.N36, "N57" to HlPalette.N57, "N65" to HlPalette.N65, "N96" to HlPalette.N96,
                                "Ember" to HlPalette.Ember, "EmberStart" to HlPalette.EmberStart, "EmberEnd" to HlPalette.EmberEnd,
                            ).forEach { (name, color) -> Swatch(name, color) }
                        }
                        // The roles that name them, as components use them.
                        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                            listOf(
                                "background" to c.background, "fill" to c.content.copy(alpha = HlAlpha.FILL).compositeOver(c.background),
                                "raised" to c.surfaceRaised, "outline" to c.outline, "decorative" to c.contentDecorative,
                                "tertiary" to c.contentTertiary, "secondary" to c.contentSecondary, "content" to c.content,
                                "focus" to c.focus, "on focus" to c.onFocus, "on focus, detail" to c.onFocusSecondary, "ember" to c.ember,
                            ).forEach { (name, color) -> Swatch(name, color) }
                            Swatch("ember light", c.emberLight())
                        }
                        // Look at it on the TV: every step should stand apart from the room.
                        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Bottom) {
                            Swatch("room", c.background)
                            HlAlpha.BLACK_LEVELS.forEach { level ->
                                val step = c.content.copy(alpha = level).compositeOver(c.background)
                                Swatch("white ${(level * PERCENT).roundToInt()} %", step)
                            }
                            Text(
                                "Black levels: on the TV each step stands apart from the room, or its blacks are crushed.",
                                style = Hl.type.caption,
                                color = c.contentSecondary,
                                modifier = Modifier.padding(start = HlSpace.m).widthIn(max = HlLayout.readableNarrow),
                            )
                        }
                    }
                }
            }

            item {
                Group("Type: the voices of the poster, of reading, technical; then the signatures", still = true) {
                    listOf(
                        "display" to t.display, "headline" to t.headline, "subject" to t.subject, "cardTitle" to t.cardTitle,
                        "title" to t.title, "lead" to t.lead, "body" to t.body, "entry" to t.entry, "meta" to t.meta,
                        "caption" to t.caption, "group" to t.group, "code" to t.code,
                        "spec" to t.spec, "badge" to t.badge,
                        "billing" to t.billing, "wordmark" to t.wordmark,
                    ).forEach { (name, style) -> Specimen(name, style) }
                }
            }

            // The three gestures of the small surfaces, side by side, before the components that use them.
            catalogGestures()
            catalogIcons()

            item {
                Group("Controls") {
                    HlActionRow {
                        HlButton("Lire", {}, icon = Icons.Filled.PlayArrow, detail = "1 h 53", emphasis = Emphasis.Primary)
                        HlButton("Secondary", {})
                        HlIconButton(HlIcons.Info, "Icon button", {})
                    }
                    ControlStates()
                    var place by remember { mutableIntStateOf(0) }
                    var season by remember { mutableIntStateOf(2) }
                    HlSegmented(listOf("Accueil", "Films", "Séries"), selected = place, onSelect = { place = it })
                    HlSegmented((1..12).map { "Saison $it" }, selected = season, onSelect = { season = it }, selectOnFocus = true, scrollable = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                        listOf("Ultra HD", "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1", "12").forEach { Badge(it) }
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
                        HlStatus(StatusState.Done, "Found")
                        HlStatus(StatusState.Pending, "Testing")
                        HlStatus(StatusState.Off, "Offline")
                        HlStatus(StatusState.Attention, "Can’t play", detail = "The Zidoo player isn’t installed.")
                    }
                    Column(Modifier.widthIn(max = HlLayout.readable), verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                        HlMeter(
                            listOf(
                                MeterSegment(0.3f, MeterInk.Primary),
                                MeterSegment(0.1f, MeterInk.Secondary),
                                MeterSegment(0.05f, MeterInk.Tertiary),
                            ),
                        )
                        MeterLegend("Online images", "300 MB", MeterInk.Primary)
                        MeterLegend("Local images", "100 MB", MeterInk.Secondary)
                        MeterLegend("Database", "20 MB", MeterInk.Tertiary)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
                        HlEmptyState(
                            "Empty or error state",
                            Modifier.weight(1f),
                            message = "What happened, and what to do about it.",
                            action = "Try again",
                            secondaryAction = "Look closer",
                        )
                        HlEmptyState(
                            "Trying again",
                            Modifier.weight(1f),
                            message = "The intertitle stays while its action is at work.",
                            action = "Try again",
                            busy = "Trying again…",
                        )
                        HlEmptyState(
                            "No action",
                            Modifier.weight(1f),
                            message = "Nothing fixes it here: Back is the way out.",
                            backHint = true,
                        )
                    }
                }
            }

            item {
                Group("Sheets") {
                    var side by remember { mutableStateOf(false) }
                    var asking by remember { mutableStateOf(false) }
                    var keyed by remember { mutableStateOf(false) }
                    var long by remember { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                        HlButton("Side sheet", { side = true })
                        HlButton("An action that asks first", { asking = true })
                        HlButton("A status, a field", { keyed = true })
                        HlButton("More rows than it holds", { long = true })
                    }
                    if (asking) SheetQuestion(onDismiss = { asking = false })
                    if (keyed) SheetStatusField(onDismiss = { keyed = false })
                    if (long) SheetLong(onDismiss = { long = false })
                    if (side) {
                        HlSideSheet(
                            "Modifier",
                            onDismiss = { side = false },
                            subject = "Le Samouraï",
                            facts = listOf("1967"),
                            description = "A panel to act on what the screen shows, which stays in view.",
                        ) { sheet ->
                            HlSettingRow("Affiche", description = "Dans les rangées et la bibliothèque", onClick = sheet::dismiss)
                            HlSettingRow("Fond d’écran", description = "Derrière cette fiche et sur l’accueil", onClick = sheet::dismiss)
                        }
                    }
                }
            }

            item {
                // Without artwork every card shows its typographic fallback; the marks sit on top.
                Group("Cards") {
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
                        PosterCard(sample("Le Samouraï", 1967), {})
                        PosterCard(sample("Playtime", 1967, played = true), {})
                        PosterCard(sample("Twin Peaks", 1990, kind = MediaKind.SERIES, unplayed = 6), {})
                        PosterCard(sample("La Nuit américaine", 1973, positionMs = 2_400_000), {})
                        PosterCard(sample("Drive My Car", 2021, addedAt = System.currentTimeMillis()), {})
                        LandscapeCard(
                            sample("Pile ou face", 2025, positionMs = 2_400_000),
                            {},
                            width = HlSize.episode,
                            title = "Pile ou face",
                            number = "1",
                            facts = listOf("1 h", "Il reste 20 min"),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                        HlListItem(
                            "Mes fichiers", "Vos vidéos sur un NAS ou un partage réseau (NFS ou SMB).", {},
                            Modifier.weight(1f), icon = HlIcons.Folder, badges = listOf("NFS", "SMB", "TMDB"), descriptionLines = 2,
                        )
                        HlListItem(
                            "Serveur Jellyfin", "Votre serveur Jellyfin.", {},
                            Modifier.weight(1f), icon = HlIcons.Server, badges = listOf("HTTPS", "HTTP"), descriptionLines = 2,
                        )
                    }
                }
            }

            catalogForms()
            catalogCards()
            catalogReading()

            item {
                // The longest French labels and the fullest rows the screens can meet: nothing here
                // may be cut or pushed out.
                Group("Stress") {
                    // Room for the labels, as the detail page keeps it.
                    HlActionRow {
                        // An episode in progress, with every capability: the fullest row of a detail page.
                        HlButton(
                            "Reprendre",
                            {},
                            icon = Icons.Filled.PlayArrow,
                            detail = "Il reste 1${NBSP}h${NBSP}26",
                            emphasis = Emphasis.Primary,
                        )
                        HlIconButton(HlIcons.Restart, "Depuis le début", {})
                        HlButton("Voir la série", {})
                        // Toggles name what OK does; lit, the icon says the episode is watched, a favourite.
                        HlIconButton(HlIcons.Check, "Marquer comme non vu", {}, lit = true)
                        HlIconButton(HlIcons.Info, "Fiche technique", {})
                        HlIconButton(HlIcons.Document, "Fichier NFO", {})
                        HlIconButton(HlIcons.HeartFilled, "Retirer des favoris", {}, lit = true)
                        HlIconButton(HlIcons.Edit, "Modifier", {})
                    }
                    var library by remember { mutableIntStateOf(0) }
                    HlSegmented(
                        listOf("Accueil", "Films 4K HDR", "Séries d’animation", "Documentaires", "Concerts et spectacles"),
                        selected = library,
                        onSelect = { library = it },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                        listOf("Ultra HD", "Dolby Vision", "HDR10+", "HDR10", "TrueHD Atmos", "DTS:X", "7.1", "16").forEach { Badge(it) }
                    }
                    Column(Modifier.widthIn(max = HlLayout.readable)) {
                        HlSettingRow(
                            "Actualiser toutes les informations",
                            description = "Récupère à nouveau affiches et informations sur TMDB",
                            onClick = {},
                            chevron = false,
                        )
                        HlSettingRow(
                            "Fréquence d’affichage automatique",
                            value = "Selon le film, 23,976 Hz",
                            description = "Adapte la fréquence de l’écran à celle du film",
                            onClick = {},
                        )
                    }
                }
            }

            catalogStress()
            catalogBrand()

            item {
                // Type alone, flat: large over the welcome wall, the size of a subject in About.
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
                    Wordmark(Modifier.alignByBaseline(), style = t.wordmarkHero)
                    Wordmark(Modifier.alignByBaseline())
                }
            }
        }
    }
}

/** Buttons, round buttons and setting rows in their states, each named underneath. */
@Composable
private fun ControlStates() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        ControlCase("detail lit: a state it names") { HlButton("Filtrer", {}, icon = HlIcons.Filter, detail = "Non vus", detailLit = true) }
        ControlCase("disabled: focus outlines it") { HlButton("Ajouter", {}, icon = HlIcons.Folder, enabled = false) }
        ControlCase("busy: the screen's words") {
            HlButton("Tester", {}, icon = HlIcons.Test, busy = true, busyLabel = "Vérification…")
        }
    }
    HlActionRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        var busy by remember { mutableStateOf(false) }
        LaunchedEffect(busy) {
            if (busy) {
                delay(CATALOG_BUSY_MS)
                busy = false
            }
        }
        ControlCase("OK: busy for 3 s, its width kept") {
            HlButton("Se connecter", { busy = true }, emphasis = Emphasis.Primary, busy = busy, busyLabel = "Connexion…")
        }
        // A round button's label takes no room of its own: the case keeps the room a row of them keeps.
        ControlCase("round, disabled") {
            HlActionRow { HlIconButton(HlIcons.Edit, "Modifier", {}, enabled = false) }
        }
    }
    ControlCase("a label longer than a reading column stops with an ellipsis") {
        HlButton("Ajouter « Le Seigneur des anneaux : la Communauté de l’anneau, version longue » à vos favoris", {})
    }
    Column(Modifier.widthIn(max = HlLayout.readable)) {
        ControlCase("setting row, disabled: tertiary words, focus outlines it; never in a raised sheet") {
            HlSettingRow("Analyser maintenant", description = "Aucun dossier à analyser", onClick = {}, chevron = false, enabled = false)
        }
    }
}

/** One state of a control, named underneath. */
@Composable
private fun ControlCase(caption: String, content: @Composable () -> Unit) {
    Column {
        content()
        Spacer(Modifier.height(HlSpace.xs))
        Text(caption, style = Hl.type.caption, color = Hl.colors.contentTertiary)
    }
}

/** A folder's sheet: removing it asks first, in the sheet, with focus on Cancel. */
@Composable
private fun SheetQuestion(onDismiss: () -> Unit) {
    HlSideSheet(
        "Films",
        onDismiss = onDismiss,
        facts = listOf("NFS"),
        description = "192.168.1.10/volume1/media/films",
    ) { sheet ->
        SheetConfirm(
            question = "Retirer « Films » ?",
            consequence = "Ses titres quittent la bibliothèque ; rien n’est supprimé sur le NAS.",
            confirm = "Retirer",
            onConfirm = { sheet.dismiss() },
        ) { ask, origin ->
            HlSettingRow(
                "Analyser maintenant",
                description = "Cherche les nouveaux fichiers",
                onClick = { sheet.dismiss() },
                chevron = false,
            )
            Spacer(Modifier.height(HlSpace.s))
            HlDivider()
            Spacer(Modifier.height(HlSpace.s))
            HlSettingRow("Retirer ce dossier", origin, description = "Ses titres quittent la bibliothèque", onClick = ask, chevron = false)
        }
    }
}

/** A sheet with more rows than it holds: they fade into it at an edge with more beyond. */
@Composable
private fun SheetLong(onDismiss: () -> Unit) {
    HlSideSheet(
        "Collections",
        onDismiss = onDismiss,
        description = "Rows past the sheet fade at its edge; focus stays clear of it.",
    ) { sheet ->
        for (n in 1..CATALOG_LONG_ROWS) {
            HlSettingRow("Collection $n", description = "Regroupe les films d’une saga", onClick = sheet::dismiss)
        }
    }
}

/** Where a setting stands, at the head of its sheet; then a field and the action that checks it. */
@Composable
private fun SheetStatusField(onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("0123456789abcdef0123456789abcdef") }
    var checking by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    LaunchedEffect(checking) {
        if (checking) {
            delay(CATALOG_BUSY_MS)
            checking = false
            refused = true
        }
    }
    HlSideSheet(
        "Clé API TMDB",
        onDismiss = onDismiss,
        description = "Les affiches et les informations proviennent de TMDB.",
        status = { HlStatus(StatusState.On, "Clé intégrée") },
    ) {
        HlTextField(
            "Clé personnelle", key, {
                key = it
                refused = false
            },
            placeholder = "Clé personnelle",
            showLabel = false,
            supporting = "Laissez vide pour utiliser la clé intégrée à l’application.",
            error = if (refused) "TMDB refuse cette clé" else null,
            password = true,
        )
        Spacer(Modifier.height(HlSpace.l))
        HlButton("Enregistrer", { checking = true }, emphasis = Emphasis.Primary, busy = checking, busyLabel = "Vérification…")
    }
}

/** More rows than a side sheet holds. */
private const val CATALOG_LONG_ROWS = 14

/** How long the catalogue's busy buttons work before they give their answer. */
private const val CATALOG_BUSY_MS = 3_000L

@Composable
private fun Swatch(name: String, color: Color) = Swatch(name, SolidColor(color))

@Composable
private fun Swatch(name: String, brush: Brush) {
    Column {
        Box(Modifier.size(HlSize.disc).clip(HlShapes.panel).background(brush))
        Spacer(Modifier.height(HlSpace.xxs))
        Text(name, style = Hl.type.caption, color = Hl.colors.contentSecondary)
    }
}

@Composable
private fun Specimen(name: String, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(HlLayout.specLanguage)) {
            Text(name, style = Hl.type.caption, color = Hl.colors.contentSecondary)
            Text(specimenMeasure(style), style = Hl.type.caption, color = Hl.colors.contentTertiary)
        }
        Text(
            "Houselights 2,39:1 · Réglages",
            style = style,
            color = Hl.colors.content,
            maxLines = 1,
            modifier = Modifier.padding(start = HlSpace.s),
        )
    }
}

/** A style's measure as the catalogue names it: size / line height, then its tracking when it has one. */
private fun specimenMeasure(style: TextStyle): String {
    val measure = "${style.fontSize.value.roundToInt()}/${style.lineHeight.value.roundToInt()}"
    val tracking = style.letterSpacing.value
    if (tracking == 0f) return measure
    return "$measure ${"%+.3f".format(Locale.ROOT, tracking).trimEnd('0').replace('-', '\u2212')} em"
}

private const val PERCENT = 100
