package app.houselights.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.NfoActor
import app.houselights.core.NfoInfo
import app.houselights.core.NfoKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.TwoPaneDefaults
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.DefinitionPlate
import app.houselights.ui.design.components.DeviceCodePlate
import app.houselights.ui.design.components.FIELD_POSTERS
import app.houselights.ui.design.components.FactPlate
import app.houselights.ui.design.components.Gallery
import app.houselights.ui.design.components.GalleryTile
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlGroup
import app.houselights.ui.design.components.HlMeter
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlPanel
import app.houselights.ui.design.components.HlPlate
import app.houselights.ui.design.components.HlPlates
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.JaquetteLine
import app.houselights.ui.design.components.LocalBackdropRelay
import app.houselights.ui.design.components.MeterFact
import app.houselights.ui.design.components.MeterInk
import app.houselights.ui.design.components.MeterSegment
import app.houselights.ui.design.components.PaneHead
import app.houselights.ui.design.components.PaneLockup
import app.houselights.ui.design.components.PanelLevel
import app.houselights.ui.design.components.PlateDefinition
import app.houselights.ui.design.components.PlateFact
import app.houselights.ui.design.components.PosterField
import app.houselights.ui.design.components.PreviewShape
import app.houselights.ui.design.components.ReadingPlate
import app.houselights.ui.design.components.ScanProgress
import app.houselights.ui.design.components.Showcase
import app.houselights.ui.design.components.ShowcaseRow
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.detail.NfoContent
import app.houselights.ui.detail.NfoSheet
import app.houselights.ui.detail.NfoState
import app.houselights.ui.util.LoadState

/** The grammar of the secondary screens: the posters' room, its places and groups, showcases, a gallery, sheets. */
internal fun LazyListScope.catalogSecondary() {
    item { Group("Secondary screens: the library's posters as the room, its places, groups and facts") { SecondaryRoom() } }
    item { Group("Panes: a head on the screen title's baseline, the lockup, case backs, a meter, a code to type") { SecondaryPanes() } }
    item { Group("Showcase: a folder's posters, full height, melting into glass in their own light") { SecondaryShowcases() } }
    item { Group("Gallery: a title's images at their shape of use, framed with focus") { SecondaryGallery() } }
    item { Group("Sheets: their glass and plates in the film's light, what is read ringed, one wide to read") { SecondarySheets() } }
}

/** The settings' room: the field of the library's posters, the places as words, a group of rows, a plate of facts. */
@Composable
private fun SecondaryRoom() {
    val art = shippedImage(R.drawable.banner)
    val mark = shippedImage(R.drawable.ic_launcher_foreground)
    val posters = remember(art, mark) { List(FIELD_POSTERS) { if (it % 2 == 0) art else mark } }
    var place by remember { mutableIntStateOf(0) }
    PosterField(posters, Modifier.fillMaxWidth().aspectRatio(HlSize.PREVIEW_RATIO)) {
        Row(Modifier.padding(HlSpace.l)) {
            Column(Modifier.width(TwoPaneDefaults.side)) {
                listOf(
                    HlIcons.Folder to R.string.section_source,
                    HlIcons.History to R.string.section_trakt,
                    HlIcons.Display to R.string.section_display,
                    HlIcons.Info to R.string.section_about,
                ).forEachIndexed { i, (icon, label) ->
                    HlNavItem(icon, stringResource(label), selected = i == place, onFocus = { place = i })
                }
            }
            Spacer(Modifier.width(HlSpace.xxl))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                // A plate of facts, then a titled group a band under it.
                HlPlates {
                    FactPlate(note = "Pour la changer : Réglages du lecteur, puis Affichage.") {
                        PlateFact(
                            stringResource(R.string.current_output),
                            stringResource(R.string.output_by_player),
                            state = StatusState.Done,
                        )
                        PlateFact("Rendu de l’interface", "Full HD", technical = true, measure = "1920 × 1080")
                    }
                    HlSettingGroup("Interface") {
                        HlSettingRow(
                            stringResource(R.string.interface_language),
                            description = "Menus, réglages et messages",
                            value = "Français",
                            onClick = {},
                        )
                        HlSettingRow(
                            stringResource(R.string.clear_image_cache),
                            description = stringResource(R.string.clear_cache_desc),
                            value = "151,5 Mo",
                            valueStyle = Hl.type.spec,
                            onClick = {},
                        )
                        // The Counter on a row: an update downloading, its count at the end of its line.
                        HlSettingRow(
                            stringResource(R.string.update_downloading),
                            description = "2,1 Mo / 6,4 Mo",
                            progress = 0.33f,
                            onClick = {},
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                    HlStatus(StatusState.On, "Connecté")
                    HlStatus(StatusState.Pending, "En attente de votre accord")
                    HlStatus(StatusState.Off, "Non connecté")
                }
            }
        }
    }
}

/** A pane's heads (a count, a state, a sentence; About's lockup), its case backs, the meter that states, a code. */
@Composable
private fun SecondaryPanes() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxl)) {
        Column(Modifier.width(HlSize.showcase), verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
            PaneHead("Bibliothèque", aside = "12 titres dans 2 dossiers")
            PaneHead("Trakt", aside = "En attente de votre accord", state = StatusState.Pending)
            PaneHead("Stockage", sentence = stringResource(R.string.storage_desc))
            PaneHead("Trakt", aside = "Non connecté", state = StatusState.Off, read = stringResource(R.string.trakt_desc))
            PaneLockup()
            JaquetteLine(listOf("Version" to "0.1.0", "Licence" to "GPL-3.0, logiciel libre"))
        }
        // A titled group, then plates without a title, a plate's gap apart.
        HlPlates(Modifier.weight(1f)) {
            HlGroup("Générique") {
                DefinitionPlate {
                    PlateDefinition("Données des films", note = stringResource(R.string.tmdb_attribution)) {
                        Text("TMDB", style = Hl.type.spec, color = Hl.colors.content)
                    }
                    PlateDefinition("Police") { Text("Archivo, SIL Open Font License", style = Hl.type.entry, color = Hl.colors.content) }
                }
            }
            HlPlate(Modifier.fillMaxWidth()) {
                HlMeter(
                    listOf(MeterSegment(0.15f, MeterInk.Primary), MeterSegment(0.01f, MeterInk.Tertiary)),
                    height = HlSize.meterLarge,
                )
                Spacer(Modifier.height(HlSpace.l))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MeterFact("Cache des images", "151,5 Mo", MeterInk.Primary)
                    MeterFact("Base de la bibliothèque", "472 Ko", MeterInk.Tertiary)
                    MeterFact("Images locales", "Aucune", MeterInk.Secondary, technical = false, empty = true)
                }
            }
            DeviceCodePlate(
                "B4D7E29A",
                stringResource(R.string.trakt_code_desc),
                R.drawable.trakt_qr,
                "trakt.tv/activate",
                left = "Ce code expire dans 9 min",
            )
        }
    }
}

/** Two folders in their row, one whole and one waiting for posters, and one being read. */
@Composable
private fun SecondaryShowcases() {
    val art = shippedImage(R.drawable.banner)
    val mark = shippedImage(R.drawable.ic_launcher_foreground)
    ShowcaseRow {
        item {
            Showcase(
                "Films",
                "NFS",
                listOf(art, mark, art, mark),
                {},
                facts = listOf("11 titres", "8 en Ultra HD"),
                state = "Analysé à l’instant",
            )
        }
        item { Showcase("Séries", "SMB", listOf(mark), {}, facts = listOf("1 série", "10 épisodes")) }
        item {
            Showcase(
                "Spectacles",
                "NFS",
                listOf(art, mark),
                {},
                facts = listOf("22 titres"),
                state = "12 introuvables",
                stateLight = StatusState.Off,
            )
        }
    }
    Showcase(
        "Concerts",
        "SMB",
        emptyList(),
        {},
        status = { ScanProgress(3, 9, current = "Concert pour la paix.mkv") },
    )
}

/** Modifier's images, on a sheet's glass: the poster, the backdrop and the logo on its neutral step; the others step back. */
@Composable
private fun SecondaryGallery() {
    val art = shippedImage(R.drawable.banner)
    val logo = shippedImage(R.drawable.tmdb_logo)
    HlPanel(level = PanelLevel.Raised) {
        Gallery {
            GalleryTile(art, PreviewShape.Poster, stringResource(R.string.artwork_poster), {}, width = HlLayout.posterChoice)
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                GalleryTile(art, PreviewShape.Backdrop, stringResource(R.string.artwork_backdrop), {}, width = HlSize.galleryWide)
                GalleryTile(logo, PreviewShape.Logo, stringResource(R.string.artwork_logo), {}, width = HlSize.galleryWide)
            }
        }
    }
}

/** A plate in a film's light beside the sheets that hold them, over the picture they are tinted by. */
@Composable
private fun SecondarySheets() {
    val art = shippedImage(R.drawable.banner)
    var acting by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var nfo by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalBackdropRelay provides null) {
        Box(Modifier.fillMaxWidth().aspectRatio(HlSize.PREVIEW_RATIO)) {
            AmbientBackdrop(art, BackdropStyle.Full) {
                Row(Modifier.padding(HlSpace.l), horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                        HlButton("A sheet in the film’s light", { acting = true })
                        HlButton("A synopsis: a standard sheet to read", { reading = true })
                        HlButton("An NFO: its views on the title's line", { nfo = true })
                    }
                    // A sheet's glass, and on it a plate a step up in the film's glow.
                    HlPanel(Modifier.width(TwoPaneDefaults.sideWide), level = PanelLevel.Raised) {
                        HlPlate {
                            Text("glassPlate: glass a step up, 5 % of the film’s glow", style = Hl.type.meta, color = Hl.colors.content)
                        }
                    }
                }
                if (acting) SheetInItsLight(art, onDismiss = { acting = false })
                if (reading) SheetToRead(onDismiss = { reading = false })
                if (nfo) SheetOfAnNfo(onDismiss = { nfo = false })
            }
        }
    }
}

@Composable
private fun SheetInItsLight(art: ImageRef, onDismiss: () -> Unit) {
    HlSideSheet(stringResource(R.string.edit), onDismiss = onDismiss, facts = listOf("Le Samouraï", "1967")) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            GalleryTile(art, PreviewShape.Poster, stringResource(R.string.artwork_poster), {}, width = HlLayout.posterChoice)
            GalleryTile(art, PreviewShape.Backdrop, stringResource(R.string.artwork_backdrop), {}, width = HlSize.galleryWide)
        }
        HlSettingGroup(null) {
            HlSettingRow(
                stringResource(R.string.identify),
                description = stringResource(R.string.identify_menu_desc),
                icon = HlIcons.Search,
                onClick = {},
            )
        }
        // What is read takes focus as a ring.
        ReadingPlate(readAs = "Image, Ultra HD, 3840 × 1608") {
            Text("Image", style = Hl.type.group, color = Hl.colors.contentSecondary)
            Text("Ultra HD", style = Hl.type.spec, color = Hl.colors.content)
            Text("3840 × 1608", style = Hl.type.meta, color = Hl.colors.contentSecondary)
        }
        FactPlate {
            PlateFact("Image", "Ultra HD", technical = true, measure = "2,39:1")
            PlateFact("Son", "Dolby Atmos", technical = true)
        }
    }
}

/** An NFO as its sheet shows it: how it reads, its views, its details focused to be read. */
@Composable
private fun SheetOfAnNfo(onDismiss: () -> Unit) {
    val film = MediaItem("nfo", MediaKind.MOVIE, "Le Samouraï", year = 1967)
    val info = NfoInfo(
        kind = NfoKind.MOVIE,
        title = "Le Samouraï",
        year = 1967,
        runtimeMin = 105,
        genres = listOf("Policier", "Drame"),
        directors = listOf("Jean-Pierre Melville"),
        actors = listOf(NfoActor("Alain Delon", null, null, 0), NfoActor("François Périer", null, null, 1)),
        tmdbId = 5_511,
        imdbId = "tt0062229",
    )
    val lines = listOf("<movie>", "  <title>Le Samouraï</title>", "  <year>1967</year>", "</movie>")
    val read = LoadState.Ready(NfoContent("/films/Le Samouraï.nfo", info, lines))
    NfoSheet(film, NfoState(read), onRetry = {}, onDismiss = onDismiss)
}

@Composable
private fun SheetToRead(onDismiss: () -> Unit) {
    HlSideSheet("Synopsis", onDismiss = onDismiss, facts = listOf("Le Samouraï", "1967", "Jean-Pierre Melville")) {
        Text("Le silence d’un homme seul.", style = Hl.type.lead, color = Hl.colors.content)
        Text(
            "Jef Costello, tueur à gages solitaire, se fabrique un alibi avant d’abattre le patron d’une boîte de nuit. " +
                "Vu par les témoins, relâché faute de preuves, il est désormais traqué par la police et par ceux qui " +
                "l’ont engagé. Il ne lui reste qu’à suivre jusqu’au bout le code qu’il s’est donné.",
            style = Hl.type.body,
            color = Hl.colors.contentSecondary,
        )
    }
}
