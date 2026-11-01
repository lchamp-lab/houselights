package app.houselights.ui.catalog

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkNote
import app.houselights.core.ArtworkOption
import app.houselights.core.ArtworkOrigin
import app.houselights.core.ImageRef
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HlStroke
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.TwoPaneDefaults
import app.houselights.ui.design.components.AbsentCard
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.ArtworkChoiceCard
import app.houselights.ui.design.components.ArtworkImage
import app.houselights.ui.design.components.ArtworkPrint
import app.houselights.ui.design.components.ArtworkSurface
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.HeroSkeleton
import app.houselights.ui.design.components.HlResultItem
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.LocalBackdropRelay
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PersonCard
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.PosterThumb
import app.houselights.ui.design.components.ProgressLine
import app.houselights.ui.design.components.SelectedMark
import app.houselights.ui.edit.ArtworkSheet
import app.houselights.ui.util.formatDuration

// Images come from app resources, and a failed one from a missing file: nothing needs the network.

/** Cards, artwork and the parts of the pickers, each in every state it has. */
internal fun LazyListScope.catalogCards() {
    item { Group("Media row: its title, its new titles; one frame travels from card to card, the others step back") { PosterRow() } }
    item { Group("Ambient backdrop: a screen on its picture, whose light the focused card glows with") { AmbientBackdrops() } }
    item { Group("Artwork surface and image") { Surfaces() } }
    item { Group("Landscape card: its name, then its facts beside it when both fit, else under it") { LandscapeRow() } }
    item { Group("A saga whole, counted: the films the library lacks keep their place, as empty frames") { SagaRow() } }
    item { Group("Person card: one line, two lines, long role, no photo") { CastRow() } }
    item { Group("Progress line: at a poster's and a still's foot, on dark and white; beside text", still = true) { ProgressLines() } }
    item { Group("Card skeleton: the shapes screens ask for", still = true) { Skeletons() } }
    item {
        Group("Contact sheet: a poster's, its print beside six a line, the line with focus level with it, its section's name held") {
            ContactSheet(ArtworkKind.POSTER)
        }
    }
    item {
        Group("Contact sheet: a backdrop's, full screen behind the band at the screen's foot, five a line") {
            ContactSheet(ArtworkKind.BACKDROP)
        }
    }
    item { Group("Contact sheet: a logo's, at its place over the title's backdrop, four a line") { ContactSheet(ArtworkKind.LOGO) } }
    item {
        Group("Contact sheet: a saga's TMDB does not know, its films' pictures alone after why, said as a fact") {
            SagaContactSheet()
        }
    }
    item {
        Group("Artwork choice and print: nothing under a choice but Automatique and a saga's film; and the selected mark") {
            ChoiceCards()
            SelectedMarks()
        }
    }
    item { Group("Search result") { Results() } }
    item { Group("Poster thumb", still = true) { Thumbs() } }
}

private const val BROKEN_URL = "file:///nonexistent/poster.jpg"

private val brokenImage = ImageRef("catalog-broken") { BROKEN_URL }

/** Artwork the app ships with, so a card shows a real image without the network. */
@Composable
internal fun shippedImage(@DrawableRes id: Int): ImageRef {
    val pkg = LocalContext.current.packageName
    return remember(pkg, id) { ImageRef("catalog-res-$id") { "android.resource://$pkg/$id" } }
}

/** What a screen puts in an image's fallback slot: here, its name, low on the card. */
@Composable
private fun BoxScope.FallbackSample(title: String) {
    Text(
        title,
        style = Hl.type.cardTitle,
        color = Hl.colors.content,
        modifier = Modifier.align(Alignment.BottomStart).padding(HlSpace.s),
    )
}

/** Posters in a row as home and the detail page lay them out: focus one and the others step back. */
@Composable
private fun PosterRow() {
    val art = shippedImage(R.drawable.banner)
    val now = System.currentTimeMillis()
    val cards = listOf(
        "rest, no artwork" to sample("Le Samouraï", 1967),
        "artwork" to sample("Houselights", 2026).copy(poster = art),
        "watched" to sample("Playtime", 1967, played = true).copy(poster = art),
        "in progress, 40%" to sample("La Nuit américaine", 1973, positionMs = 1_440_000).copy(poster = art),
        "new this week" to sample("Drive My Car", 2021, addedAt = now),
        "6 unwatched" to sample("Twin Peaks", 1990, kind = MediaKind.SERIES, unplayed = 6),
        "1234 unwatched, long title" to sample(
            "Les Aventures extraordinaires d’Adèle Blanc-Sec et le secret de la momie",
            2010,
            kind = MediaKind.SERIES,
            unplayed = 1234,
        ),
        "image failed: set in type" to sample("Pierrot le Fou", 1965).copy(poster = brokenImage),
    )
    MediaRow("Ajoutés récemment", news = 1) {
        items(cards) { (state, media) -> Specimen(state, width = HlSize.poster) { PosterCard(media, {}) } }
    }
}

/** Small screens on their pictures: a focused card without artwork glows with the picture's light. */
@Composable
private fun AmbientBackdrops() {
    val art = shippedImage(R.drawable.banner)
    // Isolated backdrops: no relay hands a picture to them or from them.
    CompositionLocalProvider(LocalBackdropRelay provides null) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
            listOf("a picture: its light" to art, "no picture: the black room, no light" to null).forEach { (state, image) ->
                Specimen(state, width = TwoPaneDefaults.sideWide) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                        AmbientBackdrop(image, BackdropStyle.Hero) {
                            PosterCard(sample("Le Samouraï", 1967), {}, Modifier.align(Alignment.CenterStart).padding(start = HlSpace.l))
                        }
                    }
                }
            }
            Specimen("quiet, a person's titles: the picture dimmed down to the foot", width = TwoPaneDefaults.sideWide) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { Backdrop(art, BackdropStyle.Quiet) }
            }
        }
    }
}

/** The focus treatment alone, and the image inside it: loaded, or failed with its fallback in its place. */
@Composable
private fun Surfaces() {
    val art = shippedImage(R.drawable.banner).url(0)
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        val wide = Modifier.width(HlSize.landscape).aspectRatio(16f / 9f)
        Specimen("surface alone", width = HlSize.landscape) {
            ArtworkSurface({}, wide, shape = HlShapes.wide) {}
        }
        Specimen("image", width = HlSize.landscape) {
            ArtworkSurface({}, wide, shape = HlShapes.wide) {
                ArtworkImage(art, "Houselights", Modifier.fillMaxSize())
            }
        }
        Specimen("image failed: its fallback", width = HlSize.landscape) {
            ArtworkSurface({}, wide, shape = HlShapes.wide) {
                ArtworkImage(BROKEN_URL, "Pierrot le Fou", Modifier.fillMaxSize()) { FallbackSample("Pierrot le Fou") }
            }
        }
        Specimen("a portrait, 3:4", width = HlSize.personColumn) {
            ArtworkSurface({}, Modifier.width(HlSize.portrait).aspectRatio(HlSize.PORTRAIT_RATIO)) {
                ArtworkImage(art, null, Modifier.fillMaxSize())
            }
        }
    }
}

/** Home's landscape cards, without a caption, then episodes as the detail page lists them. */
@Composable
private fun LandscapeRow() {
    val art = shippedImage(R.drawable.banner)
    val now = System.currentTimeMillis()
    val longName = "Où l’on comprend enfin pourquoi les hiboux ne sont pas ce qu’ils semblent être"
    val left = stringResource(R.string.time_left, formatDuration(1_560_000))
    MediaRow(header = null, spacing = HlSpace.l) {
        item {
            Specimen("home: no artwork, 40%", width = HlSize.landscape) {
                LandscapeCard(sample("La Nuit américaine", 1973, positionMs = 1_440_000), {})
            }
        }
        item {
            Specimen("home: artwork, new", width = HlSize.landscape) {
                LandscapeCard(sample("Houselights", 2026, addedAt = now).copy(thumb = art), {})
            }
        }
        item {
            Specimen("resume: name and time left on one line", width = HlSize.landscape) {
                LandscapeCard(
                    sample("Twin Peaks", 1990, kind = MediaKind.EPISODE, positionMs = 1_440_000).copy(thumb = art),
                    {},
                    title = "Twin Peaks",
                    facts = listOf("S1 E3", left),
                )
            }
        }
        item {
            Specimen("resume: a long name, its facts under it", width = HlSize.landscape) {
                LandscapeCard(
                    sample("Les Brigades du Tigre", 1974, kind = MediaKind.EPISODE, positionMs = 1_440_000),
                    {},
                    title = "Les Brigades du Tigre",
                    facts = listOf("S2 E11", left),
                )
            }
        }
        item {
            Specimen("episode: rest, no artwork", width = HlSize.episode) {
                LandscapeCard(
                    sample("Pile ou face", 2025, kind = MediaKind.EPISODE),
                    {},
                    width = HlSize.episode,
                    title = "Pile ou face",
                    number = "1",
                    facts = listOf("1 h", null),
                )
            }
        }
        item {
            Specimen("episode: long name, artwork, 40%", width = HlSize.episode) {
                LandscapeCard(
                    sample(longName, 2025, kind = MediaKind.EPISODE, positionMs = 1_440_000).copy(thumb = art),
                    {},
                    width = HlSize.episode,
                    title = longName,
                    number = "12",
                    facts = listOf("1 h", stringResource(R.string.time_left, formatDuration(2_160_000))),
                )
            }
        }
        item {
            Specimen("episode: options, the Menu key with focus", width = HlSize.episode) {
                LandscapeCard(
                    sample("Puzzle", 2025, kind = MediaKind.EPISODE).copy(thumb = art),
                    {},
                    width = HlSize.episode,
                    title = "Puzzle",
                    number = "2",
                    facts = listOf("52 min", null),
                    onLongClick = {},
                    onLongClickLabel = stringResource(R.string.more_options),
                )
            }
        }
        item {
            Specimen("episode: watched, image failed: set in type", width = HlSize.episode) {
                LandscapeCard(
                    sample("Le Retour", 2025, kind = MediaKind.EPISODE, played = true).copy(thumb = brokenImage),
                    {},
                    width = HlSize.episode,
                    title = "Le Retour",
                    number = "3",
                    facts = listOf("1 h", "Vu"),
                )
            }
        }
    }
}

/** The cast row of a detail page: names of one and two lines side by side, their roles in line. */
@Composable
private fun SagaRow() {
    val poster = shippedImage(R.drawable.banner)
    MediaRow("Alien", count = "1 film sur 4") {
        item { Specimen("held", width = HlSize.poster) { PosterCard(sample("Alien", 1979), {}) } }
        item {
            Specimen("lacking: its poster in grey, framed without a glow; OK opens its synopsis", width = HlSize.poster) {
                AbsentCard("Aliens", 1986, poster = poster, onClick = {})
            }
        }
        item {
            Specimen("not out yet: in grey, read as when it comes out", width = HlSize.poster) {
                AbsentCard("Alien: Earth", 2027, poster = poster, said = "Sortie le 15 décembre 2027", onClick = {})
            }
        }
        item { Specimen("lacking, no poster: its title and year, focus outlined", width = HlSize.poster) { AbsentCard("Aliens", 1986) } }
        item { Specimen("a long title", width = HlSize.poster) { AbsentCard("Alien, la résurrection", 1997) } }
        item { Specimen("not out yet: no year", width = HlSize.poster) { AbsentCard("Alien: Romulus", null) } }
    }
}

@Composable
private fun CastRow() {
    val photo = shippedImage(R.drawable.banner)
    val cast = listOf(
        "one line, no photo" to Person("Alain Delon", "Jef Costello", null),
        "two lines" to Person("Maria de Medeiros Esteves", "Fabienne", null),
        "long role" to Person("François Périer", "Le commissaire chargé de l’enquête", null),
        "photo" to Person("Nathalie Delon", "Jane Lagrange", photo),
        "photo failed: an empty frame" to Person("Cathy Rosier", "La pianiste", brokenImage),
        "no role" to Person("Jacques Leroy", null, null),
        "name cut" to Person("Maria de Medeiros Esteves Vitorino de Almeida", "Elle-même", null),
        "in two titles: opens their page" to Person("Jean-Pierre Melville", "Réalisation", null, id = "tmdb:1", titleCount = 7),
    )
    MediaRow(header = null, spacing = HlSpace.s) {
        items(cast) { (state, person) ->
            Specimen(state, width = HlSize.personColumn) { PersonCard(person, onOpen = if (person.id != null) ({}) else null) }
        }
    }
}

/** At a poster's foot, then a still's, on its plate (dark image, projector white), then beside text on its light track. */
@Composable
private fun ProgressLines() {
    val c = Hl.colors
    listOf("dark image" to c.background, "white image" to c.content).forEach { (ground, color) ->
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
            listOf(0f to "0%: not drawn", 0.4f to "40%", 1f to "100%").forEach { (progress, label) ->
                Specimen("poster, $ground, $label", width = HlSize.poster) {
                    Box(Modifier.width(HlSize.poster).height(HlSpace.l).clip(HlShapes.artwork).background(color)) {
                        OnArtwork { ProgressLine(progress, Modifier.align(Alignment.BottomCenter), HlStroke.bold) }
                    }
                }
            }
            Specimen("still, $ground, 40%", width = HlSize.landscape) {
                Box(Modifier.width(HlSize.landscape).height(HlSpace.l).clip(HlShapes.wide).background(color)) {
                    OnArtwork { ProgressLine(0.4f, Modifier.align(Alignment.BottomCenter), HlStroke.bold) }
                }
            }
        }
    }
    Specimen("beside text, 40%", width = HlSize.progressShort) { ProgressLine(0.4f, Modifier.width(HlSize.progressShort)) }
}

/** Every footprint a screen asks a placeholder for, and the landscape one no screen asks for. */
@Composable
private fun Skeletons() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("poster row (home)", width = HlSize.poster) { CardSkeleton(HlSize.poster, HlSize.POSTER_RATIO) }
        Specimen("poster choice", width = HlLayout.posterChoiceCell) { CardSkeleton(HlLayout.posterChoiceCell, HlSize.POSTER_RATIO) }
        Specimen("backdrop choice", width = HlLayout.backdropChoiceCell) {
            CardSkeleton(HlLayout.backdropChoiceCell, HlSize.PREVIEW_RATIO)
        }
        Specimen("logo choice", width = HlLayout.logoChoiceCell) { CardSkeleton(HlLayout.logoChoiceCell, HlSize.LOGO_BOX_RATIO) }
        Specimen("landscape row: unused", width = HlSize.landscape) { CardSkeleton(HlSize.landscape, 16f / 9f) }
        Specimen("title and facts (detail page)", width = HlSize.landscape) { HeroSkeleton() }
    }
}

/**
 * A title's contact sheet of [kind] as its screen lays it out, across the screen's whole width, the catalogue's margins
 * included: Automatique and the one chosen, then three languages, one picture failing; focus stays where it is as it
 * comes.
 */
@Composable
private fun ContactSheet(kind: ArtworkKind) {
    val picture = shippedImage(if (kind == ArtworkKind.LOGO) R.drawable.ic_launcher_foreground else R.drawable.banner)
    val backdrop = shippedImage(R.drawable.banner)
    val choices = remember(kind, picture) { sheetSample(kind, picture) }
    val title = remember(backdrop) { sample("Le Samouraï", 1967).copy(backdrop = backdrop) }
    // Isolated: no relay hands its picture to the catalogue's other backdrops.
    CompositionLocalProvider(LocalBackdropRelay provides null) {
        Box(Modifier.requiredWidth(HlLayout.CANVAS_WIDTH.dp).aspectRatio(HlSize.PREVIEW_RATIO)) {
            ArtworkSheet(kind, title, choices, error = null, onlineError = null, onChoose = {}, onRetry = {}, takesFocus = false)
        }
    }
}

/**
 * A saga's poster's contact sheet (#48), one an NFO's set names and TMDB does not know: Automatique, its first film's,
 * named after it; why TMDB's sections are missing, a fact; then its films', named by their title and year.
 */
@Composable
private fun SagaContactSheet() {
    val picture = shippedImage(R.drawable.banner)
    val choices = remember(picture) {
        val films = listOf("À bout de souffle" to 1960, "Le Mépris" to 1963, "Pierrot le fou" to 1965).map { (title, year) ->
            ArtworkOption("film-$year", picture, ArtworkOrigin.FILM, film = title, filmYear = year)
        }
        ArtworkChoices(films, films.first(), films.first(), onlineAvailable = false, note = ArtworkNote.SAGA_UNKNOWN)
    }
    val saga = remember(picture) { sample("Nouvelle Vague", 1960, kind = MediaKind.COLLECTION).copy(backdrop = picture) }
    CompositionLocalProvider(LocalBackdropRelay provides null) {
        Box(Modifier.requiredWidth(HlLayout.CANVAS_WIDTH.dp).aspectRatio(HlSize.PREVIEW_RATIO)) {
            ArtworkSheet(ArtworkKind.POSTER, saga, choices, null, onlineError = null, onChoose = {}, onRetry = {}, takesFocus = false)
        }
    }
}

/** A title's choices of [kind] in [picture]: Automatique, the one chosen, then by language as TMDB's are ranked. */
private fun sheetSample(kind: ArtworkKind, picture: ImageRef): ArtworkChoices {
    fun online(n: Int, language: String?) =
        ArtworkOption("$kind-$language-$n", if (n == FAILING) brokenImage else picture, ArtworkOrigin.ONLINE, language)
    val automatic = ArtworkOption("$kind-automatic", picture, ArtworkOrigin.ONLINE, "fr")
    // A logo's head has the title set in type too.
    val head = listOfNotNull(automatic, ArtworkOption("", null, ArtworkOrigin.NONE).takeIf { kind == ArtworkKind.LOGO })
    val languages = if (kind == ArtworkKind.BACKDROP) listOf(null, "fr", "en") else listOf("fr", "en", null)
    val options = head + languages.zip(SAMPLE_COUNTS).flatMap { (language, count) -> (0 until count).map { online(it, language) } }
    return ArtworkChoices(options, automatic, selected = online(0, languages.first()), onlineAvailable = true)
}

/** The pictures of each language of [sheetSample]; the fifth of each fails. */
private val SAMPLE_COUNTS = listOf(9, 14, 3)
private const val FAILING = 4

/**
 * The parts of a contact sheet: the print, with its words or none, then choices at rest, in use, failed, and the only
 * words under one, « Automatique » across the next column and a saga's film and its year; a backdrop, a logo fitted
 * in its box and a title set in type in a logo's.
 */
@Composable
private fun ChoiceCards() {
    val picture = shippedImage(R.drawable.banner)
    val logo = shippedImage(R.drawable.ic_launcher_foreground)
    val automatic = stringResource(R.string.artwork_automatic)
    val cell = HlLayout.posterChoiceCell
    val poster = HlSize.POSTER_RATIO
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Top) {
        Specimen("print: the one in use, Automatique", width = HlLayout.choicePrint) {
            ArtworkPrint(picture, true, HlLayout.choicePrint, poster, name = automatic, detail = null)
        }
        Specimen("print: a saga's film, its year after it; failed", width = HlLayout.choicePrint) {
            ArtworkPrint(brokenImage, false, HlLayout.choicePrint, poster, name = "Le Cercle rouge", detail = "1970")
        }
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Top) {
                Specimen("Automatique, its words under the next column; the one chosen") {
                    Row(horizontalArrangement = Arrangement.spacedBy(HlLayout.choiceGutter)) {
                        val across = HlLayout.choiceWords(cell, 2, last = false)
                        ArtworkChoiceCard(picture, false, automatic, {}, cell, poster, name = automatic, captionWidth = across)
                        ArtworkChoiceCard(picture, true, "Français", {}, cell, poster)
                    }
                }
                Specimen("rest", width = cell) { ArtworkChoiceCard(picture, false, "Anglais", {}, cell, poster) }
                Specimen("failed", width = cell) { ArtworkChoiceCard(brokenImage, false, "Anglais", {}, cell, poster) }
                Specimen("a saga's film") {
                    val words = HlLayout.choiceWords(cell, 2, last = false)
                    val film = "Le Cercle rouge"
                    ArtworkChoiceCard(picture, false, film, {}, cell, poster, name = film, detail = "1970", captionWidth = words)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Top) {
                val wide = HlLayout.backdropChoiceCell
                Specimen("backdrop", width = wide) { ArtworkChoiceCard(picture, false, "Sans texte", {}, wide, HlSize.PREVIEW_RATIO) }
                val box = HlLayout.logoChoiceCell
                Specimen("logo, fitted", width = box) {
                    ArtworkChoiceCard(logo, true, "Français", {}, box, HlSize.LOGO_BOX_RATIO, fit = true)
                }
                Specimen("title in type", width = box) {
                    val inType = stringResource(R.string.artwork_title_in_type)
                    ArtworkChoiceCard(null, false, inType, {}, box, HlSize.LOGO_BOX_RATIO, fit = true, placeholder = "Le Samouraï")
                }
            }
        }
    }
}

/** The check of the choice in use, alone on the room and on a white image. */
@Composable
private fun SelectedMarks() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Top) {
        Specimen("selected mark") { SelectedMark() }
        Specimen("on a white image") {
            Box(Modifier.size(CatalogSwatch).background(Hl.colors.content), contentAlignment = Alignment.Center) { SelectedMark() }
        }
    }
}

private const val SAMURAI_OVERVIEW =
    "Jef Costello, tueur à gages solitaire et méthodique, abat le patron d’une boîte de nuit. Vu par plusieurs " +
        "témoins, il s’est pourtant forgé un alibi parfait. Mais le commissaire chargé de l’enquête ne croit pas à " +
        "son innocence, et ses commanditaires préfèrent se débarrasser de lui."

private const val SEVEN_SAMURAI_OVERVIEW =
    "Dans le Japon médiéval, des paysans las d’être pillés chaque année par des bandits engagent sept samouraïs " +
        "sans maître pour défendre leur village. Contre un peu de riz, ces guerriers vont leur apprendre à se " +
        "battre, et découvrir ce que vaut la parole donnée."

/** Results of a search, about as wide as the identify form gives them. */
@Composable
private fun ColumnScope.Results() {
    val poster = shippedImage(R.drawable.banner)
    val current = stringResource(R.string.result_current)
    val matching = stringResource(R.string.identifying)
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("current, long overview", Modifier.weight(1f)) {
            HlResultItem(poster, "Le Samouraï", listOf("1967", null), SAMURAI_OVERVIEW, {}, mark = current)
        }
        Specimen("rest, long overview", Modifier.weight(1f)) {
            HlResultItem(poster, "Les Sept Samouraïs", listOf("1954", "七人の侍"), SEVEN_SAMURAI_OVERVIEW, {})
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("being matched", Modifier.weight(1f)) {
            HlResultItem(poster, "Le Samouraï", listOf("1967", null), SAMURAI_OVERVIEW, {}, busy = matching)
        }
        Specimen("being matched, the current one", Modifier.weight(1f)) {
            HlResultItem(poster, "Le Samouraï", listOf("1967", null), SAMURAI_OVERVIEW, {}, mark = current, busy = matching)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        // A title to identify, as its list shows it: what it may be on a line no fact gives way to, then where it lies.
        Specimen("a note on its own line, where it lies as its overview", Modifier.weight(1f)) {
            HlResultItem(
                null,
                "Wonder Woman",
                listOf("1984", stringResource(R.string.to_identify_unsure)),
                "Films/Wonder Woman 1984.mkv",
                {},
                note = stringResource(R.string.to_identify_best, "Wonder Woman 1984", 2020),
            )
        }
        // Menu opens its suggestions: the key at the end of its name, with focus only, its room kept at rest.
        Specimen("with options: the Menu key ends its name with focus", Modifier.weight(1f)) {
            HlResultItem(
                null,
                "Dune",
                listOf(null, stringResource(R.string.to_identify_unsure)),
                "Films/Dune.mkv",
                {},
                note = stringResource(R.string.to_identify_best, "Dune", 2021),
                onLongClick = {},
                onLongClickLabel = stringResource(R.string.more_options),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("a long note ends in an ellipsis", Modifier.weight(1f)) {
            HlResultItem(
                null,
                "Le Seigneur des anneaux",
                listOf(null, stringResource(R.string.to_identify_unsure)),
                "Films/Le Seigneur des anneaux.mkv",
                {},
                note = stringResource(R.string.to_identify_best_undated, "Le Seigneur des anneaux : La Communauté de l’anneau"),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("no poster, no overview", Modifier.weight(1f)) {
            HlResultItem(null, "Samouraï", listOf("2002", null), null, {})
        }
        Specimen("poster failed: empty frame, long title", Modifier.weight(1f)) {
            HlResultItem(
                brokenImage,
                "Le Dernier Samouraï : l’histoire vraie de la rébellion de Satsuma et de ses derniers guerriers",
                listOf("2003", "The Last Samurai"),
                "En 1876, un ancien officier de l’armée américaine est engagé pour former l’armée impériale japonaise.",
                {},
            )
        }
    }
}

/** Small posters of lists and of a sheet's head. */
@Composable
private fun Thumbs() {
    val art = shippedImage(R.drawable.banner)
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        listOf(
            Triple("thumb", art, HlSize.posterThumb),
            Triple("thumb, none: empty frame", null, HlSize.posterThumb),
            Triple("thumb, failed: empty frame", brokenImage, HlSize.posterThumb),
            Triple("large", art, HlSize.posterThumbLarge),
        ).forEach { (state, image, width) ->
            Specimen(state, width = HlSize.posterThumbLarge) { PosterThumb(image, width = width) }
        }
        listOf("as tall as it is given: a sheet's title block" to art, "as tall, none: empty frame" to null).forEach { (state, image) ->
            Specimen(state, width = HlSize.posterThumbLarge) {
                Box(Modifier.height(HlSize.posterThumb)) { PosterThumb(image, width = null) }
            }
        }
    }
}
