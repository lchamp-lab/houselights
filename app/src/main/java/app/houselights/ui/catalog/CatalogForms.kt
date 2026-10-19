package app.houselights.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.FieldLabel
import app.houselights.ui.design.components.HlChoiceSetting
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlDivider
import app.houselights.ui.design.components.HlMenuButton
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlPanel
import app.houselights.ui.design.components.HlPanelButton
import app.houselights.ui.design.components.HlScrollArea
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.MenuOption
import app.houselights.ui.design.components.MenuSection
import app.houselights.ui.design.components.PanelLevel
import app.houselights.ui.design.components.PreviewShape
import app.houselights.ui.design.components.RowPreview
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState

/** Forms, menus, choice sheets and long text; HlTwoPane and HlFormScaffold fill the screen (see setup). */
internal fun LazyListScope.catalogForms() {
    item { Group("Settings: entries and rows on the room, white with focus") { FormSettings() } }
    item { Group("Text fields") { FormTextFields() } }
    item { Group("Status panel", still = true) { FormStatusPanels() } }
    item { Group("Panel button") { FormPanelButtons() } }
    item { Group("Divider", still = true) { FormDividers() } }
    item { Group("Menus") { FormMenus() } }
    item { Group("Choice sheet") { FormChoiceSheet() } }
    item { Group("Choice setting: the row and its sheet in one") { FormChoiceSettings() } }
    item { Group("Scroll area") { FormScrollAreas() } }
}

@Composable
private fun FormSettings() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxl), verticalAlignment = Alignment.Top) {
        Specimen(
            "no well, no chevron: the pane you are in lights its icon, raspberry on focus's white",
            Modifier.width(HlLayout.sideColumn),
            gap = HlSpace.xs,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                HlNavItem(HlIcons.Folder, stringResource(R.string.section_source), "10 titres", selected = true, onFocused = {})
                HlNavItem(HlIcons.Numbers, stringResource(R.string.section_numbers), null, selected = false, onFocused = {})
                HlNavItem(
                    HlIcons.Display,
                    stringResource(R.string.section_display),
                    stringResource(R.string.display_by_player),
                    selected = false,
                    onFocused = {},
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            Specimen("rows: words on the room, 60 tall; a chevron shows with focus where a row leads", gap = HlSpace.xs) {
                Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                    HlSettingRow(stringResource(R.string.interface_language), value = "Français", onClick = {})
                    HlSettingRow(
                        stringResource(R.string.tmdb_key),
                        value = stringResource(R.string.tmdb_personal),
                        valueLit = true,
                        onClick = {},
                    )
                    HlSettingRow(
                        stringResource(R.string.source_local),
                        description = stringResource(R.string.scan_now_desc),
                        icon = HlIcons.Folder,
                        onClick = {},
                        chevron = false,
                    )
                    HlSettingRow(
                        stringResource(R.string.scan_now),
                        description = stringResource(R.string.scan_now_desc),
                        onClick = {},
                        chevron = false,
                        enabled = false,
                    )
                    HlSettingRow(stringResource(R.string.section_storage), value = "92,4 Mo")
                }
            }
            Text(
                "Above: a value, a value of your choice in ember, an icon grey until focus, a disabled row in its well " +
                    "(focus rings it), a row that only states its value.",
                style = Hl.type.caption,
                color = Hl.colors.contentTertiary,
            )
        }
    }
    Specimen("in a sheet, on glass: rows 52 tall, a preview in each slot", Modifier.width(HlLayout.readable), gap = HlSpace.xs) {
        HlPanel(Modifier.fillMaxWidth(), level = PanelLevel.Raised, padding = HlInset.tight) {
            HlSettingRow(
                stringResource(R.string.artwork_poster),
                description = stringResource(R.string.artwork_poster_desc),
                onClick = {},
                leading = { RowPreview(null, PreviewShape.Poster) },
            )
            HlSettingRow(
                stringResource(R.string.artwork_backdrop),
                description = stringResource(R.string.artwork_backdrop_desc),
                onClick = {},
                leading = { RowPreview(null, PreviewShape.Backdrop) },
            )
        }
    }
}

@Composable
private fun FormTextFields() {
    Text(
        "A field is a glass plate edged by a hairline. Focus rings it in projector white and shows, at its end, that " +
            "OK types; OK opens the keyboard, and while typing the ring is the ember light and the cursor raspberry, " +
            "and only then. Every field has a name, read out even when hidden.",
        style = Hl.type.caption,
        color = Hl.colors.contentTertiary,
    )
    // Bottom-aligned, so a field without its label shows next to the others.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Bottom) {
        var host by remember { mutableStateOf("") }
        Specimen("empty, label and placeholder", Modifier.weight(1f), gap = HlSpace.xs) {
            HlTextField(
                stringResource(R.string.field_host), host, { host = it },
                placeholder = stringResource(R.string.placeholder_host),
                keyboardType = KeyboardType.Uri,
            )
        }
        var share by remember { mutableStateOf("media") }
        Specimen("filled", Modifier.weight(1f), gap = HlSpace.xs) {
            HlTextField(
                stringResource(R.string.field_share), share, { share = it },
                placeholder = stringResource(R.string.placeholder_share),
            )
        }
        var secret by remember { mutableStateOf("cinema1967") }
        Specimen("password", Modifier.weight(1f), gap = HlSpace.xs) {
            HlTextField(stringResource(R.string.field_password), secret, { secret = it }, password = true, imeAction = ImeAction.Done)
        }
    }
    val personal = stringResource(R.string.tmdb_key_personal)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        var key by remember { mutableStateOf("") }
        Specimen("label hidden, read out; placeholder, supporting line", Modifier.weight(1f), gap = HlSpace.xs) {
            HlTextField(
                personal, key, { key = it },
                placeholder = personal,
                showLabel = false,
                supporting = stringResource(R.string.tmdb_key_builtin),
                password = true,
                imeAction = ImeAction.Done,
            )
        }
        var refused by remember { mutableStateOf("0123456789abcdef0123456789abcdef") }
        Specimen("error: in ember under the field, its edge unchanged", Modifier.weight(1f), gap = HlSpace.xs) {
            HlTextField(
                personal, refused, { refused = it },
                showLabel = false,
                error = stringResource(R.string.error_tmdb_key_refused_title),
                password = true,
                imeAction = ImeAction.Done,
            )
        }
        var export by remember { mutableStateOf("/volume1/video/Films/Cinéma français/Nouvelle Vague/Restaurations 4K") }
        Specimen("value longer than the field", Modifier.weight(1f), gap = HlSpace.xs) {
            HlTextField(
                stringResource(R.string.field_export), export, { export = it },
                placeholder = stringResource(R.string.placeholder_export),
            )
        }
    }
    var protocol by remember { mutableIntStateOf(0) }
    Specimen("FieldLabel over a segmented track", gap = HlSpace.xs) {
        Column {
            FieldLabel(stringResource(R.string.field_protocol))
            HlSegmented(listOf("NFS", "SMB"), selected = protocol, onSelect = { protocol = it })
        }
    }
}

@Composable
private fun FormStatusPanels() {
    val films = stringResource(R.string.library_movies)
    val nfs = listOf("NFS", films)
    val address = stringResource(R.string.label_address)
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("off: untested, nothing typed yet", Modifier.width(HlLayout.sideColumnWide), gap = HlSpace.xs) {
            StatusPanel(StatusState.Off, stringResource(R.string.connection_untested), address, "…", badges = nfs)
        }
        Specimen("pending: testing", Modifier.width(HlLayout.sideColumnWide), gap = HlSpace.xs) {
            StatusPanel(
                StatusState.Pending, stringResource(R.string.connection_testing), address, "192.168.1.10",
                detail = "/volume1/media/films", badges = nfs,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("on: lit, with what was found", Modifier.width(HlLayout.sideColumnWide), gap = HlSpace.xs) {
            StatusPanel(
                StatusState.On, stringResource(R.string.connection_ok), address, "192.168.1.10",
                detail = "/volume1/media/films", badges = nfs, result = pluralStringResource(R.plurals.entries_found, 128, 128),
            )
        }
        Specimen(
            "attention: failed, its mark and the result as an attention note",
            Modifier.width(HlLayout.sideColumnWide),
            gap = HlSpace.xs,
        ) {
            StatusPanel(
                StatusState.Attention, stringResource(R.string.connection_failed), address, "nas-salon.local",
                detail = "/films", badges = listOf("SMB", films),
                result = stringResource(R.string.error_nas_unreachable, "nas-salon.local"),
                resultIsError = true,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("done: a fact, white; a long value stops at three lines", Modifier.width(HlLayout.sideColumnWide), gap = HlSpace.xs) {
            StatusPanel(
                StatusState.Done, stringResource(R.string.identify_state_matched), stringResource(R.string.identify_file),
                "Le.Samourai.1967.REMASTERED.2160p.UHD.BluRay.HDR10.DTS-HD.MA.5.1.x265-HOUSELIGHTS.mkv",
                valueStyle = Hl.type.entry,
                content = {
                    Text(stringResource(R.string.identify_current), style = Hl.type.caption, color = Hl.colors.contentSecondary)
                    Spacer(Modifier.height(HlSpace.s))
                    Text("Le Samouraï", style = Hl.type.entry, color = Hl.colors.content)
                    FactRow(listOf("1967", "Jean-Pierre Melville"))
                },
            )
        }
        Specimen("pending: reading, no value yet", Modifier.width(HlLayout.sideColumnWide), gap = HlSpace.xs) {
            StatusPanel(StatusState.Pending, "Lecture…", stringResource(R.string.identify_file), "…", valueStyle = Hl.type.entry)
        }
    }
}

@Composable
private fun FormPanelButtons() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("rest; focus raises it in projector white", Modifier.weight(1f), gap = HlSpace.xs) {
            HlPanelButton({}, Modifier.fillMaxWidth()) {
                FormFolder(
                    "Films",
                    "192.168.1.10/volume1/media/films",
                    listOf("1 284 titres", pluralStringResource(R.plurals.uhd_count, 312, 312)),
                )
            }
        }
        Specimen("lit: the choice in use", Modifier.weight(1f), gap = HlSpace.xs) {
            HlPanelButton({}, Modifier.fillMaxWidth(), lit = true) {
                FormFolder(
                    "Séries",
                    "nas-salon.local/media/series",
                    listOf(pluralStringResource(R.plurals.title_count, 86, 86), "Analysé il y a 2 h"),
                )
            }
        }
        Specimen("disabled: a faint well, focus outlines it", Modifier.weight(1f), gap = HlSpace.xs) {
            // Its words in LocalContentColor, which follows the state.
            HlPanelButton({}, Modifier.fillMaxWidth(), enabled = false) {
                Column(Modifier.padding(HlInset.card)) {
                    Text("Concerts", style = Hl.type.lead)
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text("Aucun dossier", style = Hl.type.meta)
                }
            }
        }
    }
}

/** What a folder card says, without its cover. */
@Composable
private fun FormFolder(name: String, address: String, facts: List<String>) {
    Column(Modifier.padding(HlInset.card)) {
        Text(name, style = Hl.type.rowTitle, color = Hl.colors.content)
        Spacer(Modifier.height(HlSpace.xxs))
        Text(address, style = Hl.type.caption, color = Hl.colors.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(HlSpace.m))
        FactRow(facts)
    }
}

@Composable
private fun FormDividers() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("horizontal", Modifier.width(HlLayout.sideColumnWide), gap = HlSpace.xs) {
            Column {
                Text(stringResource(R.string.scan_now), style = Hl.type.entry, color = Hl.colors.content)
                Spacer(Modifier.height(HlSpace.s))
                HlDivider()
                Spacer(Modifier.height(HlSpace.s))
                Text(stringResource(R.string.remove_folder), style = Hl.type.entry, color = Hl.colors.content)
            }
        }
        Specimen("vertical", gap = HlSpace.xs) {
            Row(Modifier.height(HlSize.control), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nav_settings), style = Hl.type.entry, color = Hl.colors.content)
                Spacer(Modifier.width(HlSpace.xl))
                HlDivider(vertical = true)
                Spacer(Modifier.width(HlSpace.xl))
                Text(stringResource(R.string.section_source), style = Hl.type.entry, color = Hl.colors.contentSecondary)
            }
        }
    }
}

@Composable
private fun FormMenus() {
    // On the right, as in the library bar: the popup hangs from the button's right edge.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.Top) {
        Text(
            "A popup hanging from the button, inside the safe area: below it when the room down to the safe area " +
                "holds it, else above (scroll this group to the foot of the screen); a longer menu scrolls inside. " +
                "It grows from the button's corner and leaves the same way. Sort closes on a choice and applies once " +
                "the menu has gone; filters stay open so choices combine, and an active one lights the detail.",
            style = Hl.type.caption,
            color = Hl.colors.contentTertiary,
            modifier = Modifier.weight(1f),
        )
        Specimen("filters, none active", gap = HlSpace.xs) { FormFilterMenu(FormFilters()) }
        Specimen("filters, two active", gap = HlSpace.xs) { FormFilterMenu(FormFilters(status = 1, genre = "Drame")) }
        Specimen("sort", gap = HlSpace.xs) { FormSortMenu() }
    }
}

@Composable
private fun FormSortMenu() {
    var sort by remember { mutableIntStateOf(1) }
    HlMenuButton(
        text = stringResource(R.string.sort),
        icon = HlIcons.Sort,
        detail = formSorts()[sort],
        sections = listOf(MenuSection(null, formSortOrders().mapIndexed { i, label -> MenuOption(label, sort == i) { sort = i } })),
    )
}

/** The library's filters, as its filter menu combines them. */
private data class FormFilters(val status: Int = 0, val genre: String? = null, val decade: Int? = null, val quality: String? = null) {
    val active: Int get() = listOf(status != 0, genre != null, decade != null, quality != null).count { it }
}

@Composable
private fun FormFilterMenu(initial: FormFilters) {
    var f by remember { mutableStateOf(initial) }
    val statuses = formStatuses()
    val decades = FORM_DECADES.associateWith { stringResource(R.string.decade, it) }
    val sections = buildList {
        add(
            MenuSection(
                stringResource(R.string.filter_status),
                statuses.mapIndexed { i, label -> MenuOption(label, f.status == i) { f = f.copy(status = i) } },
            )
        )
        add(
            MenuSection(
                stringResource(R.string.filter_genre),
                listOf(MenuOption(stringResource(R.string.all_genres), f.genre == null) { f = f.copy(genre = null) }) +
                    FORM_GENRES.map { g -> MenuOption(g, f.genre == g) { f = f.copy(genre = g) } },
            )
        )
        add(
            MenuSection(
                stringResource(R.string.filter_decade),
                listOf(MenuOption(stringResource(R.string.all_decades), f.decade == null) { f = f.copy(decade = null) }) +
                    decades.map { (d, label) -> MenuOption(label, f.decade == d) { f = f.copy(decade = d) } },
            )
        )
        add(
            MenuSection(
                stringResource(R.string.filter_quality),
                listOf(MenuOption(stringResource(R.string.all_qualities), f.quality == null) { f = f.copy(quality = null) }) +
                    FORM_QUALITIES.map { q -> MenuOption(q, f.quality == q) { f = f.copy(quality = q) } },
            )
        )
        if (f.active > 0) add(MenuSection(null, listOf(MenuOption("Réinitialiser les filtres", false) { f = FormFilters() })))
    }
    // The button names the active filter, or how many are combined.
    val detail = when (f.active) {
        0 -> null
        1 -> statuses[f.status].takeIf { f.status != 0 } ?: f.genre ?: f.decade?.let(decades::getValue) ?: f.quality
        else -> pluralStringResource(R.plurals.filter_count, f.active, f.active)
    }
    HlMenuButton(
        text = stringResource(R.string.filter),
        icon = HlIcons.Filter,
        sections = sections,
        detail = detail,
        detailLit = f.active > 0,
        closeOnSelect = false,
    )
}

@Composable
private fun FormChoiceSheet() {
    var current by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf(false) }
    val collections = formCollections()
    Specimen(
        "opens the sheet: focus starts on the current choice, choosing closes it",
        Modifier.widthIn(max = HlLayout.readable),
        gap = HlSpace.xs,
    ) {
        HlSettingRow(
            stringResource(R.string.collections_setting),
            value = collections[current].label,
            description = stringResource(R.string.collections_setting_desc),
            onClick = { choosing = true },
        )
    }
    if (choosing) {
        HlChoiceSheet(
            stringResource(R.string.collections_setting),
            collections,
            current,
            onSelect = { current = it },
            onDismiss = { choosing = false },
            description = stringResource(R.string.collections_setting_desc),
        )
    }
}

@Composable
private fun FormChoiceSettings() {
    var current by remember { mutableIntStateOf(0) }
    val title = stringResource(R.string.collections_setting)
    val collections = formCollections()
    val about = stringResource(R.string.collections_setting_desc)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("its value, the current choice; OK opens the sheet", Modifier.weight(1f), gap = HlSpace.xs) {
            HlChoiceSetting(title, collections, current, onSelect = { current = it }, description = about)
        }
        Specimen("a change that did not take: said in its value", Modifier.weight(1f), gap = HlSpace.xs) {
            HlChoiceSetting(
                title,
                collections,
                current,
                onSelect = { current = it },
                description = about,
                attention = stringResource(R.string.action_not_saved),
            )
        }
    }
}

@Composable
private fun FormScrollAreas() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("framed, longer than its height: it fades where there is more", Modifier.weight(1f), gap = HlSpace.xs) {
            HlScrollArea(Modifier.height(HlLayout.hero)) { FormReading(FORM_LONG_TEXT) }
        }
        Specimen("lazy, the lines of an NFO: laid out as they show, read the same", Modifier.weight(1f), gap = HlSpace.xs) {
            HlScrollArea(Hl.type.code.lineHeight, Modifier.height(HlLayout.hero)) {
                items(FORM_NFO_LINES) { Text(it, style = Hl.type.code, color = Hl.colors.contentSecondary) }
            }
        }
        Specimen("framed, fits: takes no focus", Modifier.weight(1f), gap = HlSpace.xs) {
            HlScrollArea(Modifier.height(HlLayout.hero)) { FormReading(listOf(FORM_SHORT_TEXT)) }
        }
    }
}

@Composable
private fun FormReading(paragraphs: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        paragraphs.forEach { Text(it, style = Hl.type.body, color = Hl.colors.contentSecondary) }
    }
}

/** The library's sorts, as its sort button names the one in use. */
@Composable
private fun formSorts() =
    listOf(R.string.sort_title, R.string.sort_added, R.string.sort_year, R.string.sort_rating).map { stringResource(it) }

/** The library's sorts, as its sort menu offers them. */
@Composable
private fun formSortOrders() =
    listOf(R.string.sort_title_order, R.string.sort_added_order, R.string.sort_year_order, R.string.sort_rating_order)
        .map { stringResource(it) }

/** The library's statuses, as its filter menu offers them. */
@Composable
private fun formStatuses() = listOf(
    R.string.show_all, R.string.filter_unwatched, R.string.status_in_progress, R.string.status_watched, R.string.section_favorites,
).map { stringResource(it) }

/** TMDB's genres, and Jellyfin's longest ones. */
private val FORM_GENRES = listOf(
    "Action", "Action & Aventure", "Animation", "Aventure", "Comédie", "Crime", "Documentaire", "Drame",
    "Familial", "Fantastique", "Guerre", "Guerre & Politique", "Histoire", "Horreur", "Musique", "Mystère",
    "Romance", "Science-Fiction", "Science-Fiction & Fantastique", "Téléfilm", "Thriller", "Western",
)

private val FORM_DECADES = listOf(1950, 1960, 1970, 1980, 1990, 2000, 2010, 2020)

private val FORM_QUALITIES = listOf("Ultra HD", "HDR", "Dolby Vision")

/** The choices of the collections setting, in the words of the setting itself. */
@Composable
private fun formCollections() = listOf(
    ChoiceOption(stringResource(R.string.collections_two), stringResource(R.string.collections_two_desc)),
    ChoiceOption(stringResource(R.string.collections_all), stringResource(R.string.collections_all_desc)),
    ChoiceOption(stringResource(R.string.collections_off), stringResource(R.string.collections_off_desc)),
)

private val FORM_LONG_TEXT = listOf(
    "Paris, un matin d’avril. Jef Costello, tueur à gages, prépare son alibi avec la minutie d’un horloger : " +
        "une chambre nue, un bouvreuil en cage, un imperméable et un chapeau qu’il ajuste devant le miroir. " +
        "Le contrat est simple ; son exécution le sera moins.",
    "Dans un club de la rive gauche, il abat le propriétaire et croise le regard de la pianiste. Arrêté avec " +
        "des dizaines de suspects, il tient grâce à son alibi. Le commissaire, lui, n’en croit pas un mot : " +
        "il fait suivre chacun de ses pas, jusque dans les couloirs du métro.",
    "Melville filme la ville comme un décor de western : des rues désertes, des faïences blanches, des " +
        "voitures volées dans le silence. Presque aucun dialogue pendant les dix premières minutes ; seuls " +
        "comptent les gestes, répétés jusqu’au rituel.",
    "Ce que l’on appelle depuis « le style Melville » tient tout entier dans ce film. Combien de " +
        "cinéastes l’ont imité ? Beaucoup, et jusqu’à Hong Kong !",
)

private const val FORM_SHORT_TEXT = "Un tueur à gages, un alibi, une pianiste : Paris en 1967, presque sans un mot."

/** An NFO as written, a line per item. */
private val FORM_NFO_LINES = listOf(
    """<?xml version="1.0" encoding="UTF-8"?>""",
    "<movie>",
    "  <title>Le Samouraï</title>",
    "  <year>1967</year>",
    "  <runtime>105</runtime>",
    """  <uniqueid type="tmdb">5511</uniqueid>""",
    """  <uniqueid type="imdb">tt0062229</uniqueid>""",
    "  <genre>Crime</genre>",
    "  <genre>Thriller</genre>",
    "  <director>Jean-Pierre Melville</director>",
    "  <actor>",
    "    <name>Alain Delon</name>",
    "    <role>Jef Costello</role>",
    "  </actor>",
    "  <actor>",
    "    <name>François Périer</name>",
    "    <role>Le commissaire</role>",
    "  </actor>",
    "</movie>",
)
