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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.FieldLabel
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlDivider
import app.houselights.ui.design.components.HlMenuButton
import app.houselights.ui.design.components.HlPanelButton
import app.houselights.ui.design.components.HlScrollArea
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.MenuOption
import app.houselights.ui.design.components.MenuSection
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState

/**
 * Forms and what surrounds them: fields, the status beside a form, panel buttons, menus, choice
 * sheets and long text. HlTwoPane and HlFormScaffold fill the screen: see them on the setup screens.
 */
internal fun LazyListScope.catalogForms() {
    item { Group("Text fields") { FormTextFields() } }
    item { Group("Status panel", still = true) { FormStatusPanels() } }
    item { Group("Panel button") { FormPanelButtons() } }
    item { Group("Divider", still = true) { FormDividers() } }
    item { Group("Menus") { FormMenus() } }
    item { Group("Choice sheet") { FormChoiceSheet() } }
    item { Group("Scroll area") { FormScrollAreas() } }
}

/** One state of a component, captioned underneath. */
@Composable
private fun FormCase(caption: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        content()
        Spacer(Modifier.height(HlSpace.xs))
        Text(caption, style = Hl.type.caption, color = Hl.colors.contentTertiary)
    }
}

@Composable
private fun FormTextFields() {
    Text(
        "Focus outlines a field in projector white and shows, at its end, that OK types; OK opens the keyboard, " +
            "and the edge turns ember while typing, and only then. Every field has a name, read out even when hidden.",
        style = Hl.type.caption,
        color = Hl.colors.contentTertiary,
    )
    // Bottom-aligned, so a field without its label shows next to the others.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Bottom) {
        var host by remember { mutableStateOf("") }
        FormCase("empty, label and placeholder", Modifier.weight(1f)) {
            HlTextField("Adresse du NAS", host, { host = it }, placeholder = "Ex. : 192.168.1.10", keyboardType = KeyboardType.Uri)
        }
        var share by remember { mutableStateOf("media") }
        FormCase("filled", Modifier.weight(1f)) {
            HlTextField("Partage", share, { share = it }, placeholder = "Ex. : media")
        }
        var secret by remember { mutableStateOf("cinema1967") }
        FormCase("password", Modifier.weight(1f)) {
            HlTextField("Mot de passe", secret, { secret = it }, password = true, imeAction = ImeAction.Done)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        var key by remember { mutableStateOf("") }
        FormCase("label hidden, read out; placeholder, supporting line", Modifier.weight(1f)) {
            HlTextField(
                "Clé personnelle", key, { key = it },
                placeholder = "Clé personnelle",
                showLabel = false,
                supporting = "Laissez vide pour utiliser la clé intégrée à l’application.",
                password = true,
                imeAction = ImeAction.Done,
            )
        }
        var refused by remember { mutableStateOf("0123456789abcdef0123456789abcdef") }
        FormCase("error: in ember under the field, its edge unchanged", Modifier.weight(1f)) {
            HlTextField(
                "Clé personnelle", refused, { refused = it },
                showLabel = false,
                error = "TMDB refuse cette clé",
                password = true,
                imeAction = ImeAction.Done,
            )
        }
        var export by remember { mutableStateOf("/volume1/video/Films/Cinéma français/Nouvelle Vague/Restaurations 4K") }
        FormCase("value longer than the field", Modifier.weight(1f)) {
            HlTextField("Export NFS", export, { export = it }, placeholder = "Ex. : /volume1/media")
        }
    }
    var protocol by remember { mutableIntStateOf(0) }
    FormCase("FieldLabel over a segmented track") {
        FieldLabel("Protocole")
        HlSegmented(listOf("NFS", "SMB"), selected = protocol, onSelect = { protocol = it })
    }
}

@Composable
private fun FormStatusPanels() {
    val nfs = listOf("NFS", "Films")
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        FormCase("off: untested, nothing typed yet", Modifier.width(HlLayout.sideColumnWide)) {
            StatusPanel(StatusState.Off, "Connexion non testée", "Adresse", "…", badges = nfs)
        }
        FormCase("pending: testing", Modifier.width(HlLayout.sideColumnWide)) {
            StatusPanel(
                StatusState.Pending, "Connexion en cours…", "Adresse", "192.168.1.10",
                detail = "/volume1/media/films", badges = nfs,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        FormCase("on: lit, with what was found", Modifier.width(HlLayout.sideColumnWide)) {
            StatusPanel(
                StatusState.On, "Connexion réussie", "Adresse", "192.168.1.10",
                detail = "/volume1/media/films", badges = nfs, result = "128 éléments trouvés",
            )
        }
        FormCase("attention: failed, its mark and the result as an attention note", Modifier.width(HlLayout.sideColumnWide)) {
            StatusPanel(
                StatusState.Attention, "Connexion impossible", "Adresse", "nas-salon.local",
                detail = "/films", badges = listOf("SMB", "Films"),
                result = "nas-salon.local ne répond pas. Vérifiez que le NAS est allumé et connecté au réseau.",
                resultIsError = true,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        FormCase("done: a fact, white; a long value stops at three lines", Modifier.width(HlLayout.sideColumnWide)) {
            StatusPanel(
                StatusState.Done, "Identifié", "Fichier",
                "Le.Samourai.1967.REMASTERED.2160p.UHD.BluRay.HDR10.DTS-HD.MA.5.1.x265-HOUSELIGHTS.mkv",
                valueStyle = Hl.type.entry,
                content = {
                    Text("Associé à", style = Hl.type.caption, color = Hl.colors.contentSecondary)
                    Spacer(Modifier.height(HlSpace.s))
                    Text("Le Samouraï", style = Hl.type.entry, color = Hl.colors.content)
                    FactRow(listOf("1967", "Jean-Pierre Melville"))
                },
            )
        }
        FormCase("pending: reading, no value yet", Modifier.width(HlLayout.sideColumnWide)) {
            StatusPanel(StatusState.Pending, "Lecture…", "Fichier", "…", valueStyle = Hl.type.entry)
        }
    }
}

@Composable
private fun FormPanelButtons() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        FormCase("rest; focus raises it in projector white", Modifier.weight(1f)) {
            HlPanelButton({}, Modifier.fillMaxWidth()) {
                FormFolder("Films", "192.168.1.10/volume1/media/films", listOf("1 284 titres", "312 en Ultra HD"))
            }
        }
        FormCase("lit: the choice in use", Modifier.weight(1f)) {
            HlPanelButton({}, Modifier.fillMaxWidth(), lit = true) {
                FormFolder("Séries", "nas-salon.local/media/series", listOf("86 titres", "Analysé il y a 2 h"))
            }
        }
        FormCase("disabled: a faint well, focus outlines it", Modifier.weight(1f)) {
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
        Text(name, style = Hl.type.lead, color = Hl.colors.content)
        Spacer(Modifier.height(HlSpace.xxs))
        Text(address, style = Hl.type.meta, color = Hl.colors.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(HlSpace.m))
        FactRow(facts)
    }
}

@Composable
private fun FormDividers() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalAlignment = Alignment.Top) {
        FormCase("horizontal", Modifier.width(HlLayout.sideColumnWide)) {
            Text("Analyser maintenant", style = Hl.type.entry, color = Hl.colors.content)
            Spacer(Modifier.height(HlSpace.s))
            HlDivider()
            Spacer(Modifier.height(HlSpace.s))
            Text("Retirer ce dossier", style = Hl.type.entry, color = Hl.colors.content)
        }
        FormCase("vertical") {
            Row(Modifier.height(HlSize.control), verticalAlignment = Alignment.CenterVertically) {
                Text("Réglages", style = Hl.type.entry, color = Hl.colors.content)
                Spacer(Modifier.width(HlSpace.xl))
                HlDivider(vertical = true)
                Spacer(Modifier.width(HlSpace.xl))
                Text("Bibliothèque", style = Hl.type.entry, color = Hl.colors.contentSecondary)
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
        FormCase("filters, none active") { FormFilterMenu(FormFilters()) }
        FormCase("filters, two active") { FormFilterMenu(FormFilters(status = 1, genre = "Drame")) }
        FormCase("sort") { FormSortMenu() }
    }
}

@Composable
private fun FormSortMenu() {
    var sort by remember { mutableIntStateOf(1) }
    HlMenuButton(
        text = "Trier",
        icon = HlIcons.Sort,
        detail = FORM_SORTS[sort],
        sections = listOf(MenuSection(null, FORM_SORT_ORDERS.mapIndexed { i, label -> MenuOption(label, sort == i) { sort = i } })),
    )
}

/** The library's filters, as its filter menu combines them. */
private data class FormFilters(val status: Int = 0, val genre: String? = null, val decade: Int? = null, val quality: String? = null) {
    val active: Int get() = listOf(status != 0, genre != null, decade != null, quality != null).count { it }
}

@Composable
private fun FormFilterMenu(initial: FormFilters) {
    var f by remember { mutableStateOf(initial) }
    val sections = buildList {
        add(MenuSection("Statut", FORM_STATUSES.mapIndexed { i, label -> MenuOption(label, f.status == i) { f = f.copy(status = i) } }))
        add(
            MenuSection(
                "Genre",
                listOf(MenuOption("Tous les genres", f.genre == null) { f = f.copy(genre = null) }) +
                    FORM_GENRES.map { g -> MenuOption(g, f.genre == g) { f = f.copy(genre = g) } },
            )
        )
        add(
            MenuSection(
                "Décennie",
                listOf(MenuOption("Toutes les décennies", f.decade == null) { f = f.copy(decade = null) }) +
                    FORM_DECADES.map { d -> MenuOption("Années $d", f.decade == d) { f = f.copy(decade = d) } },
            )
        )
        add(
            MenuSection(
                "Qualité",
                listOf(MenuOption("Toutes les qualités", f.quality == null) { f = f.copy(quality = null) }) +
                    FORM_QUALITIES.map { q -> MenuOption(q, f.quality == q) { f = f.copy(quality = q) } },
            )
        )
        if (f.active > 0) add(MenuSection(null, listOf(MenuOption("Réinitialiser les filtres", false) { f = FormFilters() })))
    }
    // The button names the active filter, or how many are combined.
    val detail = when (f.active) {
        0 -> null
        1 -> FORM_STATUSES[f.status].takeIf { f.status != 0 } ?: f.genre ?: f.decade?.let { "Années $it" } ?: f.quality
        else -> "${f.active} filtres"
    }
    HlMenuButton(
        text = "Filtrer",
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
    FormCase("opens the sheet: focus starts on the current choice, choosing closes it", Modifier.widthIn(max = HlLayout.readable)) {
        HlSettingRow(
            "Collections",
            value = FORM_COLLECTIONS[current].label,
            description = FORM_COLLECTIONS_ABOUT,
            onClick = { choosing = true },
        )
    }
    if (choosing) {
        HlChoiceSheet(
            "Collections",
            FORM_COLLECTIONS,
            current,
            onSelect = { current = it },
            onDismiss = { choosing = false },
            description = FORM_COLLECTIONS_ABOUT,
        )
    }
}

@Composable
private fun FormScrollAreas() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        FormCase("framed, longer than its height: it fades where there is more", Modifier.weight(1f)) {
            HlScrollArea(Modifier.height(HlLayout.hero)) { FormReading(FORM_LONG_TEXT) }
        }
        FormCase("lazy, the lines of an NFO: laid out as they show, read the same", Modifier.weight(1f)) {
            HlScrollArea(Hl.type.code.lineHeight, Modifier.height(HlLayout.hero)) {
                items(FORM_NFO_LINES) { Text(it, style = Hl.type.code, color = Hl.colors.contentSecondary) }
            }
        }
        FormCase("framed, fits: takes no focus", Modifier.weight(1f)) {
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

private val FORM_SORTS = listOf("Titre", "Date d’ajout", "Année", "Note")

private val FORM_SORT_ORDERS =
    listOf("Titre, de A à Z", "Date d’ajout, récents d’abord", "Année, récents d’abord", "Note, meilleures d’abord")

private val FORM_STATUSES = listOf("Tous", "Non vus", "En cours", "Vus", "Favoris")

/** TMDB's genres, and Jellyfin's longest ones. */
private val FORM_GENRES = listOf(
    "Action", "Action & Aventure", "Animation", "Aventure", "Comédie", "Crime", "Documentaire", "Drame",
    "Familial", "Fantastique", "Guerre", "Guerre & Politique", "Histoire", "Horreur", "Musique", "Mystère",
    "Romance", "Science-Fiction", "Science-Fiction & Fantastique", "Téléfilm", "Thriller", "Western",
)

private val FORM_DECADES = listOf(1950, 1960, 1970, 1980, 1990, 2000, 2010, 2020)

private val FORM_QUALITIES = listOf("Ultra HD", "HDR", "Dolby Vision")

private const val FORM_COLLECTIONS_ABOUT = "Les films d’une même saga, réunis"

private val FORM_COLLECTIONS = listOf(
    ChoiceOption("À partir de 2 films", "Une saga apparaît dès que vous en avez au moins deux films"),
    ChoiceOption("Toutes", "Même quand vous n’avez qu’un film de la saga"),
    ChoiceOption("Masquées", "Ni bibliothèque Collections, ni rangée de saga"),
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
