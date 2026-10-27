package app.houselights.ui.catalog

import app.houselights.ui.design.PaneDefaults
import app.houselights.ui.design.TwoPaneDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.ui.edit.EditMenu
import app.houselights.core.MediaKind
import app.houselights.journal.JournalAccess
import app.houselights.providers.trakt.SkippedEntry
import app.houselights.providers.trakt.SkippedKind
import app.houselights.ui.settings.JournalSheet
import app.houselights.ui.settings.SkippedSheet
import kotlinx.coroutines.awaitCancellation
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
import app.houselights.ui.design.components.HlActionRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlIconButton
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
import app.houselights.ui.design.components.LocalBackdropRelay
import app.houselights.ui.design.components.MeterInk
import app.houselights.ui.design.components.MeterFact
import app.houselights.ui.design.components.MeterSegment
import app.houselights.ui.design.components.PanelLevel
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.QrCode
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.SheetConfirm
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.emberLit
import app.houselights.ui.design.plate
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.formatDuration
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Every token and component in its states, for review on a real screen; debug builds only, untranslated. */
@Composable
fun CatalogScreen() {
    val c = Hl.colors
    val t = Hl.type
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = HlSpace.safeH, vertical = HlSpace.safeV),
            verticalArrangement = Arrangement.spacedBy(HlSpace.xl),
        ) {
            item { ScreenTitle("Design system") }

            item {
                Group("Colour", Modifier.focusRequester(first), still = true) { Colours() }
            }

            item { Group("Black levels: the room's steps, then the darks a picture lends", still = true) { BlackLevels() } }
            catalogContrast()

            item {
                Group("Type: the voices of the poster, of reading, technical; then the signatures", still = true) {
                    listOf(
                        "display" to t.display, "headline" to t.headline, "subject" to t.subject,
                        "place" to t.place, "placeCurrent, ember lit" to t.placeCurrent.emberLit(c), "rowTitle" to t.rowTitle,
                        "cardName" to t.cardName, "cardTitle" to t.cardTitle,
                        "lead" to t.lead, "body" to t.body, "entry" to t.entry, "meta" to t.meta, "group" to t.group, "code" to t.code,
                        "spec" to t.spec, "jaquette" to t.jaquette, "badge" to t.badge,
                        "titleSignature" to t.titleSignature, "billing" to t.billing, "billingJoin" to t.billingJoin,
                        "codeSignature" to t.codeSignature, "figureSignature" to t.figureSignature,
                    ).forEach { (name, style) -> StyleSpecimen(name, style) }
                }
            }

            catalogFocus()
            // The three gestures of the small surfaces, side by side, before the components that use them.
            catalogGestures()
            catalogIcons()

            catalogControls()

            item {
                Group("Panels and status") {
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                        HlPanel(Modifier.weight(1f)) { Text("Quiet panel", style = t.entry, color = c.content) }
                        HlPanel(Modifier.weight(1f), lit = true) { Text("Lit panel", style = t.entry, color = c.content) }
                        HlPanel(Modifier.weight(1f), level = PanelLevel.Raised) { Text("Raised panel", style = t.entry, color = c.content) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.CenterVertically) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxl)) {
                            MeterFact("Image cache", "300 MB", MeterInk.Primary)
                            MeterFact("Library database", "20 MB", MeterInk.Tertiary)
                            MeterFact("Local images", "None", MeterInk.Secondary, technical = false, empty = true)
                        }
                    }
                }
            }

            item {
                Group("Lists and settings") {
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                        Column(Modifier.width(TwoPaneDefaults.side)) {
                            HlNavItem(HlIcons.Folder, "Selected", selected = true, onFocused = {})
                            HlNavItem(HlIcons.Display, "Other", selected = false, onFocused = {})
                        }
                        Column(Modifier.widthIn(max = HlLayout.readable)) {
                            HlSettingGroup("Group") {
                                HlSettingRow("Leads somewhere", value = "Value", onClick = {})
                                HlSettingRow(
                                    "Your choice",
                                    value = "Chosen",
                                    valueLit = true,
                                    description = "A line of description",
                                    onClick = {},
                                )
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
                    var code by remember { mutableStateOf(false) }
                    var skipped by remember { mutableStateOf(false) }
                    var journal by remember { mutableStateOf<JournalAccess?>(null) }
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                        HlButton("Modifier: a gallery of its images", { side = true })
                        HlButton("An action that asks first", { asking = true })
                        HlButton("A status, a field", { keyed = true })
                        HlButton("More rows than it holds", { long = true })
                    }
                    // Sheets with nothing to focus, on a line of their own: five buttons pass the safe area.
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                        HlButton("A code to scan", { code = true })
                        HlButton("What a sync left out", { skipped = true })
                        HlButton("An address made here", { journal = CATALOG_JOURNAL })
                        HlButton("No home network", { journal = JournalAccess.Offline })
                    }
                    journal?.let { access ->
                        // The log's sheet as it serves, its address encoded at run time, for as long as it stays open.
                        JournalSheet(
                            journal = { opened ->
                                opened(access)
                                awaitCancellation()
                            },
                            onDismiss = { journal = null },
                        )
                    }
                    if (asking) SheetQuestion(onDismiss = { asking = false })
                    if (keyed) SheetStatusField(onDismiss = { keyed = false })
                    if (long) SheetLong(onDismiss = { long = false })
                    if (code) SheetCode(onDismiss = { code = false })
                    if (skipped) SkippedSheet(CATALOG_SKIPPED, onDismiss = { skipped = false })
                    if (side) {
                        // Its poster and backdrop from the app's own art, a logo that never loads.
                        val art = shippedImage(R.drawable.banner)
                        val edited = sample("Le Samouraï", 1967).copy(poster = art, backdrop = art, logo = BROKEN_LOGO)
                        EditMenu(edited, onDismiss = { side = false }, onArtwork = { side = false }, onIdentify = { side = false })
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
                            facts = listOf("1 h", stringResource(R.string.time_left, formatDuration(1_200_000))),
                        )
                    }
                    // Raised on glass, as over the welcome wall.
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                        HlListItem(
                            stringResource(R.string.source_local), "Vos vidéos sur un NAS ou un partage réseau (NFS ou SMB).", {},
                            Modifier.weight(1f), icon = HlIcons.Folder, badges = listOf("NFS", "SMB", "TMDB"), descriptionLines = 2,
                            level = PanelLevel.Raised,
                        )
                        HlListItem(
                            stringResource(R.string.source_jellyfin), "Votre serveur Jellyfin.", {},
                            Modifier.weight(1f), icon = HlIcons.Server, badges = listOf("HTTPS", "HTTP"), descriptionLines = 2,
                            level = PanelLevel.Raised,
                        )
                    }
                }
            }

            catalogSecondary()
            catalogForms()
            catalogCards()
            catalogDoors()
            catalogReading()

            item {
                // The longest French labels and fullest rows the screens can meet: nothing may be cut.
                Group("Stress") {
                    // Room for the labels, as the detail page keeps it.
                    HlActionRow {
                        // An episode in progress, with every capability: the fullest row of a detail page.
                        HlButton(
                            stringResource(R.string.resume),
                            {},
                            icon = HlIcons.Play,
                            detail = stringResource(R.string.time_left, "1${NBSP}h${NBSP}26"),
                            emphasis = Emphasis.Primary,
                        )
                        HlIconButton(HlIcons.Restart, stringResource(R.string.from_start), {})
                        HlButton(stringResource(R.string.view_series), {})
                        // Toggles name what OK does; lit, the icon says the episode is watched, a favourite.
                        HlIconButton(HlIcons.Check, stringResource(R.string.mark_unwatched), {}, lit = true)
                        HlIconButton(HlIcons.Info, stringResource(R.string.tech_details), {})
                        HlIconButton(HlIcons.Document, stringResource(R.string.nfo_title), {})
                        HlIconButton(HlIcons.HeartFilled, stringResource(R.string.remove_favorite), {}, lit = true)
                        HlIconButton(HlIcons.Edit, stringResource(R.string.edit), {})
                    }
                    var library by remember { mutableIntStateOf(0) }
                    HlSegmented(
                        listOf(
                            stringResource(R.string.library_movies),
                            "Films 4K HDR",
                            "Séries d’animation",
                            "Documentaires",
                            "Concerts et spectacles",
                        ),
                        selected = library,
                        onSelect = { library = it },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                        listOf("Ultra HD", "Dolby Vision", "HDR10+", "HDR10", "TrueHD Atmos", "DTS:X", "7.1", "16").forEach { Badge(it) }
                    }
                    Column(Modifier.widthIn(max = HlLayout.readable)) {
                        HlSettingRow(
                            stringResource(R.string.refresh_metadata),
                            description = stringResource(R.string.refresh_metadata_desc),
                            onClick = {},
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
        }
    }
}

/** The palette, the roles that name it, ember and a picture's dark. */
@Composable
private fun Colours() {
    val c = Hl.colors
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        // The palette: the colours themselves, named by their lightness.
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            listOf(
                "N0" to HlPalette.N0, "N8" to HlPalette.N8, "N21" to HlPalette.N21, "N28" to HlPalette.N28,
                "N36" to HlPalette.N36, "N57" to HlPalette.N57, "N65" to HlPalette.N65, "N96" to HlPalette.N96,
                "Ember" to HlPalette.Ember, "EmberStart" to HlPalette.EmberStart, "EmberEnd" to HlPalette.EmberEnd,
                "Deep" to HlPalette.Deep,
            ).forEach { (name, color) -> Swatch(name, color) }
        }
        // The roles that name them, as components use them.
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            listOf(
                "background" to c.background, "fill" to c.content.copy(alpha = HlAlpha.FILL).compositeOver(c.background),
                "glass" to c.glass, "outline" to c.outline, "decorative" to c.contentDecorative,
                "tertiary" to c.contentTertiary, "secondary" to c.contentSecondary, "content" to c.content,
                "focus" to c.focus, "on focus" to c.onFocus, "on focus, detail" to c.onFocusSecondary,
            ).forEach { (name, color) -> Swatch(name, color) }
        }
        // Ember and what it lies on; the picture's own dark and its plate, on the room.
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            listOf(
                "ember" to c.ember, "on ember" to c.onEmber, "ember on light" to c.emberOnLight,
                "ambient deep" to c.ambientDeep, "plate" to c.plate().compositeOver(c.background),
            ).forEach { (name, color) -> Swatch(name, color) }
            Swatch("ember light", c.emberLight())
        }
    }
}

/** The black levels to check on the TV, then a picture's veils and a wall's field, which must show no rings there. */
@Composable
private fun BlackLevels() {
    val c = Hl.colors
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        // Every step should stand apart from the room.
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Bottom) {
            Swatch("room", c.background)
            HlAlpha.BLACK_LEVELS.forEach { level ->
                val step = c.content.copy(alpha = level).compositeOver(c.background)
                Swatch("white ${(level * PERCENT).roundToInt()} %", step)
            }
            Text(
                "Black levels: on the TV each step stands apart from the room, or its blacks are crushed.",
                style = Hl.type.meta,
                color = c.contentSecondary,
                modifier = Modifier.padding(start = HlSpace.m).widthIn(max = HlLayout.readableNarrow),
            )
        }
        // The darks a picture lends: its veils in its own dark, and a wall's diffuse field.
        val art = shippedImage(R.drawable.banner)
        CompositionLocalProvider(LocalBackdropRelay provides null) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                listOf(
                    "tinted veils: the picture's own dark at the left and the top, black at the foot" to BackdropStyle.Hero,
                    "a wall's diffuse field: dithered, no rings, never brighter than its text allows" to BackdropStyle.Diffuse,
                ).forEach { (state, style) ->
                    Specimen(state, width = TwoPaneDefaults.sideWide) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { Backdrop(art, style) }
                    }
                }
            }
        }
    }
}

/** A folder's sheet: removing it asks first, in the sheet, with focus on Cancel. */
@Composable
private fun SheetQuestion(onDismiss: () -> Unit) {
    HlSideSheet(
        "Films",
        onDismiss = onDismiss,
        badges = listOf("NFS"),
        description = "192.168.1.10/volume1/media/films",
    ) { sheet ->
        SheetConfirm(
            question = stringResource(R.string.remove_folder_confirm, "Films"),
            consequence = stringResource(R.string.remove_folder_desc),
            confirm = stringResource(R.string.remove),
            onConfirm = { sheet.dismiss() },
        ) { ask, origin ->
            HlSettingRow(
                stringResource(R.string.scan_now),
                description = stringResource(R.string.scan_now_desc),
                onClick = { sheet.dismiss() },
            )
            HlSettingRow(
                stringResource(R.string.remove_folder),
                origin,
                description = stringResource(R.string.remove_folder_row_desc),
                onClick = ask,
            )
        }
    }
}

/** A sheet with more rows than it holds: they fade into it at an edge with more beyond. */
@Composable
private fun SheetLong(onDismiss: () -> Unit) {
    HlSideSheet(
        "Sagas",
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
    val personal = stringResource(R.string.tmdb_key_personal)
    HlSideSheet(
        stringResource(R.string.tmdb_key),
        onDismiss = onDismiss,
        description = stringResource(R.string.tmdb_desc),
        status = { HlStatus(StatusState.On, stringResource(R.string.tmdb_key_builtin_state)) },
    ) {
        HlTextField(
            personal, key, { typed ->
                key = typed
                refused = false
            },
            placeholder = stringResource(R.string.placeholder_tmdb_key),
            showLabel = false,
            supporting = stringResource(R.string.tmdb_key_builtin),
            error = if (refused) stringResource(R.string.error_tmdb_key_refused_title) else null,
            password = true,
        )
        Spacer(Modifier.height(HlSpace.l))
        HlButton(
            stringResource(R.string.save),
            { checking = true },
            busy = checking,
            busyLabel = stringResource(R.string.checking),
        )
    }
}

/** A sheet handing an address to a phone (About, Soutenir Houselights): code, address, Back closes it. */
@Composable
private fun SheetCode(onDismiss: () -> Unit) {
    HlSideSheet(stringResource(R.string.support), onDismiss = onDismiss, description = stringResource(R.string.support_scan)) {
        QrCode(R.drawable.support_qr, "ko-fi.com/lchamp_lab")
    }
}

/** More rows than a side sheet holds. */
private const val CATALOG_LONG_ROWS = 14

/** The log's page at its longest address, its journal almost empty: every line the sheet can say. */
private val CATALOG_JOURNAL = JournalAccess.Open("http://192.168.100.200:65535/journal/k7f2m4q9x3w8r5t6y2zb", few = true)

/** Trakt's entries a sync could not read (Réglages > Trakt): named, named by their show, naming nothing. */
private val CATALOG_SKIPPED = listOf(
    SkippedEntry(SkippedKind.EPISODE_HISTORY, "Breaking Bad", 2008),
    SkippedEntry(SkippedKind.PAUSED, "The Matrix", 1999),
    SkippedEntry(SkippedKind.PAUSED),
)


@Composable
private fun Swatch(name: String, color: Color) = Swatch(name, SolidColor(color))

@Composable
private fun Swatch(name: String, brush: Brush) {
    Column {
        Box(Modifier.size(CatalogSwatch).clip(HlShapes.panel).background(brush))
        Spacer(Modifier.height(HlSpace.xxs))
        Text(name, style = Hl.type.meta, color = Hl.colors.contentSecondary)
    }
}

@Composable
private fun StyleSpecimen(name: String, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(PaneDefaults.specName)) {
            Text(name, style = Hl.type.meta, color = Hl.colors.contentSecondary)
            Text(specimenMeasure(style), style = Hl.type.meta, color = Hl.colors.contentTertiary)
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

/** A logo whose image never loads: its row previews the icon. */
private val BROKEN_LOGO = ImageRef("catalog-edit-broken-logo") { "file:///nonexistent/edit-logo.png" }
