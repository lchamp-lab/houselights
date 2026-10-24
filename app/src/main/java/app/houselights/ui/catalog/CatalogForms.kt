package app.houselights.ui.catalog

import app.houselights.ui.design.TwoPaneDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.ChoicePlace
import app.houselights.ui.design.components.ConnectionSecurity
import app.houselights.ui.design.components.ConnectionState
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
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.MenuOption
import app.houselights.ui.design.components.MenuSection
import app.houselights.ui.design.components.PanelLevel
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState
import app.houselights.core.LibraryKind
import app.houselights.ui.util.libraryName

/** Forms, menus, choice sheets and long text; HlTwoPane and HlFormScaffold fill the screen (see setup). */
internal fun LazyListScope.catalogForms() {
    item { Group("Settings: entries and rows on the room, white with focus") { FormSettings() } }
    item { Group("Servers found: how each will be reached, its check's place kept") { FormFoundServers() } }
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
            "no well: the pane you are in lights its icon, raspberry on focus's white",
            Modifier.width(TwoPaneDefaults.side),
            gap = HlSpace.xs,
        ) {
            Column {
                HlNavItem(HlIcons.Folder, stringResource(R.string.section_source), selected = true, onFocused = {})
                HlNavItem(HlIcons.Numbers, stringResource(R.string.section_numbers), selected = false, onFocused = {})
                HlNavItem(HlIcons.Display, stringResource(R.string.section_display), selected = false, onFocused = {})
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            Specimen("a group: its rows on one plate, 60 tall, no rule; OK does what the words say", gap = HlSpace.xs) {
                HlSettingGroup(stringResource(R.string.section_display)) {
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
                    )
                    HlSettingRow(
                        stringResource(R.string.scan_now),
                        description = stringResource(R.string.scan_now_desc),
                        onClick = {},
                        enabled = false,
                    )
                    HlSettingRow(
                        stringResource(R.string.clear_image_cache),
                        description = stringResource(R.string.clear_cache_desc),
                        value = "151,5 Mo",
                        valueStyle = Hl.type.jaquette,
                        onClick = {},
                    )
                    HlSettingRow(stringResource(R.string.section_storage), value = "92,4 Mo")
                }
            }
            Text(
                "Above: a value, a value of your choice in ember, an icon grey until focus, a disabled row in its well " +
                    "(focus rings it), a figure in the technical voice, a row that only states its value.",
                style = Hl.type.caption,
                color = Hl.colors.contentTertiary,
            )
        }
    }
    Specimen(
        "in a sheet, on glass: its group on a plate a step up",
        Modifier.width(HlLayout.readable),
        gap = HlSpace.xs,
    ) {
        HlPanel(Modifier.fillMaxWidth(), level = PanelLevel.Raised, padding = HlInset.tight) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.scan_now),
                    description = stringResource(R.string.scan_now_desc),
                    onClick = {},
                )
                HlSettingRow(
                    stringResource(R.string.identify),
                    description = stringResource(R.string.identify_menu_desc),
                    icon = HlIcons.Search,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun FormTextFields() {
    Text(
        "A field is a glass plate 60 tall edged by a hairline, its name in white over it. Focus rings it in projector " +
            "white and shows, at its end, that OK types; OK opens the keyboard, and while typing the ring is the ember " +
            "light and the cursor raspberry, and only then. Every field has a name, read out even when hidden.",
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        var protocol by remember { mutableIntStateOf(0) }
        Specimen("FieldLabel over a segmented track", Modifier.weight(1f), gap = HlSpace.xs) {
            Column {
                FieldLabel(stringResource(R.string.field_protocol))
                HlSegmented(listOf("NFS", "SMB"), selected = protocol, onSelect = { protocol = it })
            }
        }
        var folder by remember { mutableStateOf("") }
        Specimen("optional: said after its name, in grey", Modifier.weight(1f), gap = HlSpace.xs) {
            HlTextField(
                stringResource(R.string.field_path), folder, { folder = it },
                placeholder = stringResource(R.string.placeholder_path_movies),
                optional = true,
            )
        }
    }
    FormFoldedFields()
}

/** A sign-in's servers found as their checks left them, the one picked checked after its state. */
@Composable
private fun FormFoundServers() {
    val rows = listOf(
        Triple("Salon", "jellyfin.mondomaine.fr", ConnectionSecurity.Encrypted),
        Triple("NAS du bureau", "192.168.1.31:8096", ConnectionSecurity.Plain),
        Triple("Chambre", "192.168.1.42:8096", ConnectionSecurity.Checking),
        Triple("Ancien NAS", "192.168.1.57:8096", ConnectionSecurity.Silent),
    )
    Specimen(
        "the lock closed and white, open and grey, the quiet dot, the ring; never ember; focus inks them",
        Modifier.width(HlLayout.readableNarrow),
        gap = HlSpace.xs,
    ) {
        HlSettingGroup(stringResource(R.string.servers_found)) {
            rows.forEach { (name, address, security) ->
                HlSettingRow(
                    name,
                    description = address,
                    onClick = {},
                    trailing = {
                        ConnectionState(security)
                        ChoicePlace(chosen = security == ConnectionSecurity.Plain)
                    },
                )
            }
        }
    }
}

/** An NFS folder's identity under its form's actions: folded, its value said; open, what it changes over two fields. */
@Composable
private fun FormFoldedFields() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("a setting folded under a form's actions, its value said; root in words", Modifier.weight(1f), gap = HlSpace.xs) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.nfs_identity_title),
                    description = stringResource(R.string.nfs_identity_desc),
                    value = stringResource(R.string.nfs_identity_root),
                    onClick = {},
                )
            }
        }
        var uid by remember { mutableStateOf("1026") }
        var gid by remember { mutableStateOf("100") }
        Specimen("open: what it changes over its two fields, its figures in the technical voice", Modifier.weight(1f), gap = HlSpace.xs) {
            Column {
                HlSettingGroup(null) {
                    HlSettingRow(
                        stringResource(R.string.nfs_identity_title),
                        description = stringResource(R.string.nfs_identity_desc),
                        value = stringResource(R.string.nfs_identity, uid, gid),
                        valueStyle = Hl.type.jaquette,
                        onClick = {},
                        valueBreaks = true,
                    )
                }
                Text(
                    stringResource(R.string.nfs_identity_hint),
                    style = Hl.type.meta,
                    color = Hl.colors.contentSecondary,
                    modifier = Modifier.padding(top = HlSpace.s, bottom = HlSpace.l),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                    HlTextField(
                        stringResource(R.string.field_uid), uid, { uid = it }, Modifier.weight(1f),
                        placeholder = stringResource(R.string.placeholder_uid), keyboardType = KeyboardType.Number,
                    )
                    HlTextField(
                        stringResource(R.string.field_gid), gid, { gid = it }, Modifier.weight(1f),
                        placeholder = stringResource(R.string.placeholder_gid), keyboardType = KeyboardType.Number,
                    )
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("long figures, or the largest text size: the user over the group, words whole", Modifier.weight(1f), gap = HlSpace.xs) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.nfs_identity_title),
                    description = stringResource(R.string.nfs_identity_desc),
                    value = stringResource(R.string.nfs_identity, LONG_ID, LONG_ID),
                    valueStyle = Hl.type.jaquette,
                    onClick = {},
                    valueBreaks = true,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** A user or group number as a directory service hands out, too long for one line beside the row's words. */
private const val LONG_ID = "2000001"

@Composable
private fun FormStatusPanels() {
    val films = stringResource(R.string.library_movies)
    val nfs = listOf("NFS", films)
    val address = stringResource(R.string.label_address)
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("off: untested, nothing typed yet", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
            StatusPanel(StatusState.Off, stringResource(R.string.connection_untested), address, "…", badges = nfs)
        }
        Specimen("pending: testing", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
            StatusPanel(
                StatusState.Pending, stringResource(R.string.connection_testing), address, "192.168.1.10",
                detail = "/volume1/media/films", badges = nfs,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("on: its white dot, with what was found", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
            StatusPanel(
                StatusState.On, stringResource(R.string.connection_ok), address, "192.168.1.10",
                detail = "/volume1/media/films", badges = nfs, result = pluralStringResource(R.plurals.entries_found, 128, 128),
            )
        }
        Specimen(
            "attention: failed, its mark and the result as an attention note",
            Modifier.width(TwoPaneDefaults.sideWide),
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
        Specimen("done: a fact, white; a long value stops at three lines", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
            StatusPanel(
                StatusState.Done, stringResource(R.string.identify_state_matched), stringResource(R.string.identify_file),
                "Le.Samourai.1967.REMASTERED.2160p.UHD.BluRay.HDR10.DTS-HD.MA.5.1.x265-HOUSELIGHTS.mkv",
                valueStyle = Hl.type.entry,
                content = {
                    Text(stringResource(R.string.identify_current), style = Hl.type.meta, color = Hl.colors.contentSecondary)
                    Spacer(Modifier.height(HlSpace.s))
                    Text("Le Samouraï", style = Hl.type.entry, color = Hl.colors.content)
                    FactRow(listOf("1967", "Jean-Pierre Melville"))
                },
            )
        }
        Specimen("pending: reading, no value yet", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
            StatusPanel(StatusState.Pending, "Lecture…", stringResource(R.string.identify_file), "…", valueStyle = Hl.type.entry)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("done: a server found, by its name, its protocol verified", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
            StatusPanel(
                StatusState.Done, stringResource(R.string.server_found), stringResource(R.string.field_server), "jellyfin.mondomaine.fr",
                name = "Salon",
                badges = listOf("HTTPS"),
                facts = listOf(stringResource(R.string.connection_is_encrypted)),
            )
        }
        Specimen("picked while checked: no protocol yet", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
            StatusPanel(
                StatusState.Done, stringResource(R.string.server_found), stringResource(R.string.field_server), "192.168.1.42:8096",
                name = "Chambre",
                facts = listOf(stringResource(R.string.checking)),
            )
        }
        Specimen("on: an NFS folder presenting another user, among its facts", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
            StatusPanel(
                StatusState.On, stringResource(R.string.connection_ok), address, "192.168.1.20",
                detail = "/volume1/media/films", badges = listOf("NFS"),
                facts = listOf(films, stringResource(R.string.nfs_identity, "1026", "100")),
                result = pluralStringResource(R.plurals.entries_found, 128, 128),
            )
        }
    }
}

@Composable
private fun FormPanelButtons() {
    // Servers a sign-in found, as its list says them.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("rest; focus frames it in projector white", Modifier.weight(1f), gap = HlSpace.xs) {
            HlPanelButton({}, Modifier.fillMaxWidth()) { FormChoice("Salon", "http://192.168.1.20:8096") }
        }
        Specimen("lit: the choice in use", Modifier.weight(1f), gap = HlSpace.xs) {
            HlPanelButton({}, Modifier.fillMaxWidth(), lit = true) { FormChoice("Bureau", "http://nas-bureau.local:8096") }
        }
        Specimen("disabled: a faint well, focus outlines it", Modifier.weight(1f), gap = HlSpace.xs) {
            HlPanelButton({}, Modifier.fillMaxWidth(), enabled = false) {
                FormChoice("Grenier", stringResource(R.string.error_offline_title), enabled = false)
            }
        }
    }
}

/** A choice on a panel button as a list item says it: its name in `lead`, what it is in secondary `meta`. */
@Composable
private fun FormChoice(name: String, line: String, enabled: Boolean = true) {
    Column(Modifier.padding(HlInset.card)) {
        Text(name, style = Hl.type.lead)
        Spacer(Modifier.height(HlSpace.xs))
        // Disabled, its words keep LocalContentColor, which follows the state.
        Text(line, style = Hl.type.meta, color = if (enabled) Hl.colors.contentSecondary else Color.Unspecified)
    }
}

@Composable
private fun FormDividers() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        Specimen("horizontal", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
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
                "the menu has gone; filters stay open so choices combine, and an active one lights the detail. A films " +
                "wall with sagas first shows its films and sagas, or the sagas alone, named before the filters.",
            style = Hl.type.caption,
            color = Hl.colors.contentTertiary,
            modifier = Modifier.weight(1f),
        )
        Specimen("filters, none active", gap = HlSpace.xs) { FormFilterMenu(FormFilters()) }
        Specimen("filters, two active", gap = HlSpace.xs) { FormFilterMenu(FormFilters(status = 1, genre = "Drame")) }
        Specimen("the sagas alone, one filter", gap = HlSpace.xs) { FormFilterMenu(FormFilters(sagas = true, status = 1)) }
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

/** The films wall's filters, as its filter menu combines them, and whether it shows the [sagas] alone. */
private data class FormFilters(
    val sagas: Boolean = false,
    val status: Int = 0,
    val genre: String? = null,
    val decade: Int? = null,
    val quality: String? = null,
) {
    val active: Int get() = listOf(status != 0, genre != null, decade != null, quality != null).count { it }
}

@Composable
private fun FormFilterMenu(initial: FormFilters) {
    var f by remember { mutableStateOf(initial) }
    val statuses = formStatuses()
    val decades = FORM_DECADES.associateWith { stringResource(R.string.decade, it) }
    val sagasName = libraryName(LibraryKind.COLLECTIONS)
    val sections = buildList {
        add(
            MenuSection(
                stringResource(R.string.filter_show),
                listOf(
                    MenuOption(stringResource(R.string.show_grouped), !f.sagas) { f = f.copy(sagas = false) },
                    MenuOption(sagasName, f.sagas) { f = f.copy(sagas = true) },
                ),
            )
        )
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
        val reset = MenuOption(stringResource(R.string.reset_filters), false) { f = FormFilters() }
        if (f.active > 0 || f.sagas) add(MenuSection(null, listOf(reset)))
    }
    // The button names the sagas shown alone, then the active filter, or how many are combined.
    val filters = when (f.active) {
        0 -> null
        1 -> statuses[f.status].takeIf { f.status != 0 } ?: f.genre ?: f.decade?.let(decades::getValue) ?: f.quality
        else -> pluralStringResource(R.plurals.filter_count, f.active, f.active)
    }
    val detail = listOfNotNull(sagasName.takeIf { f.sagas }, filters).joinToString(" · ").ifEmpty { null }
    HlMenuButton(
        text = stringResource(R.string.filter),
        icon = HlIcons.Filter,
        sections = sections,
        detail = detail,
        detailLit = detail != null,
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
            HlScrollArea(FORM_NFO_LINES, Modifier.height(HlLayout.hero))
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
