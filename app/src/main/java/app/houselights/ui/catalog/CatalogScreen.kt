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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlDivider
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlIconDisc
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.HlListItem
import app.houselights.ui.design.components.HlMeter
import app.houselights.ui.design.components.HlModal
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlPanel
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.MeterLegend
import app.houselights.ui.design.components.MeterSegment
import app.houselights.ui.design.components.PanelLevel
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.SheetConfirm
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.Wordmark
import kotlinx.coroutines.delay
import app.houselights.ui.util.NBSP

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
                            "secondary" to c.contentSecondary, "content" to c.content, "on focus, detail" to c.onFocusSecondary,
                            "tungsten" to c.tungsten,
                        ).forEach { (name, color) -> Swatch(name, color) }
                    }
                }
            }

            item {
                Group("Type: the voices of the poster, of reading, of controls, technical", still = true) {
                    listOf(
                        "display" to t.display, "headline" to t.headline, "figure" to t.figure, "subject" to t.subject,
                        "billing" to t.billing, "wordmark" to t.wordmark,
                        "title" to t.title, "lead" to t.lead, "body" to t.body, "entry" to t.entry, "meta" to t.meta,
                        "caption" to t.caption, "detail" to t.detail, "code" to t.code,
                        "label" to t.label,
                        "spec" to t.spec, "badge" to t.badge,
                    ).forEach { (name, style) -> Specimen(name, style) }
                }
            }

            item {
                Group("Controls") {
                    Row(
                        Modifier.padding(bottom = HlLayout.iconLabelRoom),
                        horizontalArrangement = Arrangement.spacedBy(HlSpace.s),
                        verticalAlignment = Alignment.Top,
                    ) {
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
                        HlStatus(StatusState.Attention, "Can’t play", detail = "The Zidoo player isn’t installed.")
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
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
                        HlEmptyState(
                            "What happened, and what to do about it.",
                            Modifier.weight(1f),
                            title = "Empty or error state",
                            action = "Try again",
                            secondaryAction = "Look closer",
                        )
                        HlEmptyState(
                            "Nothing fixes it here: Back is the way out.",
                            Modifier.weight(1f),
                            title = "No action",
                            backHint = true,
                        )
                    }
                }
            }

            item {
                Group("Sheets") {
                    var side by remember { mutableStateOf(false) }
                    var reading by remember { mutableStateOf(false) }
                    var asking by remember { mutableStateOf(false) }
                    var keyed by remember { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                        HlButton("Side sheet", { side = true })
                        HlButton("Reading sheet", { reading = true })
                        HlButton("An action that asks first", { asking = true })
                        HlButton("A status, a field", { keyed = true })
                    }
                    if (asking) SheetQuestion(onDismiss = { asking = false })
                    if (keyed) SheetStatusField(onDismiss = { keyed = false })
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
                    if (reading) {
                        HlModal("Fiche technique", onDismiss = { reading = false }, facts = listOf("Le Samouraï", "1967")) {
                            Text("A sheet to read; Back closes it.", style = t.body, color = c.contentSecondary)
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
                    Row(
                        Modifier.padding(bottom = HlLayout.iconLabelRoom),
                        horizontalArrangement = Arrangement.spacedBy(HlSpace.s),
                        verticalAlignment = Alignment.Top,
                    ) {
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
                        HlIconButton(Icons.Filled.Check, "Marquer comme non vu", {}, lit = true)
                        HlIconButton(HlIcons.Info, "Fiche technique", {})
                        HlIconButton(HlIcons.Document, "Fichier NFO", {})
                        HlIconButton(Icons.Filled.Favorite, "Retirer des favoris", {}, lit = true)
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
            HlButton("Tester", {}, icon = HlIcons.Restart, busy = true, busyLabel = "Vérification…")
        }
    }
    Row(
        Modifier.padding(bottom = HlLayout.iconLabelRoom),
        horizontalArrangement = Arrangement.spacedBy(HlSpace.m),
        verticalAlignment = Alignment.Top,
    ) {
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
        ControlCase("round, disabled") { HlIconButton(HlIcons.Edit, "Modifier", {}, enabled = false) }
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

/** How long the catalogue's busy buttons work before they give their answer. */
private const val CATALOG_BUSY_MS = 3_000L

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
