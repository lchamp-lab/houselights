package app.houselights.ui.catalog

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.ImageRef
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.ArtworkChoiceCard
import app.houselights.ui.design.components.ArtworkImage
import app.houselights.ui.design.components.ArtworkSurface
import app.houselights.ui.design.components.Lift
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.HeroSkeleton
import app.houselights.ui.design.components.HlResultItem
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PersonCard
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.PosterThumb
import app.houselights.ui.design.components.PreviewShape
import app.houselights.ui.design.components.ProgressLine
import app.houselights.ui.design.components.RowPreview
import app.houselights.ui.design.components.SelectedMark
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.formatDimensions
import app.houselights.ui.util.formatDuration
import androidx.compose.runtime.CompositionLocalProvider
import app.houselights.ui.design.components.LocalBackdropRelay
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.AbsentCard

// Images come from app resources, and a failed one from a missing file: nothing needs the network.

/** Cards, artwork and the parts of the pickers, each in every state it has. */
internal fun LazyListScope.catalogCards() {
    item { Group("Media row: poster cards, the others step back") { PosterRow() } }
    item { Group("Ambient backdrop: a screen on its picture, whose light the focused card glows with") { AmbientBackdrops() } }
    item { Group("Artwork surface and image") { Surfaces() } }
    item { Group("Landscape card") { LandscapeRow() } }
    item { Group("A saga whole: the films the library lacks keep their place, as empty frames") { SagaRow() } }
    item { Group("Person card: one line, two lines, long role, no photo") { CastRow() } }
    item { Group("Progress line: on artwork, dark and white; beside text", still = true) { ProgressLines() } }
    item { Group("Card skeleton: the shapes screens ask for", still = true) { Skeletons() } }
    item { Group("Artwork choice: poster") { ChoiceRow(ArtworkKind.POSTER) } }
    item { Group("Artwork choice: backdrop") { ChoiceRow(ArtworkKind.BACKDROP) } }
    item {
        Group("Artwork choice: logo, and the selected mark") {
            ChoiceRow(ArtworkKind.LOGO)
            SelectedMarks()
        }
    }
    item { Group("Search result") { Results() } }
    item { Group("Poster thumb and row preview", still = true) { Thumbs() } }
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
    MediaRow("Ajoutés récemment") {
        items(cards) { (state, media) -> Specimen(state, width = HlSize.poster) { PosterCard(media, {}) } }
    }
}

/** Small screens on their pictures: a focused card without artwork glows with the picture's light. */
@Composable
private fun AmbientBackdrops() {
    val art = shippedImage(R.drawable.banner)
    // Isolated backdrops: no relay hands a picture to them or from them.
    CompositionLocalProvider(LocalBackdropRelay provides null) { Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        listOf("a picture: its light" to art, "no picture: the black room, no light" to null).forEach { (state, image) ->
            Specimen(state, width = HlLayout.sideColumnWide) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    AmbientBackdrop(image, BackdropStyle.Hero) {
                        PosterCard(sample("Le Samouraï", 1967), {}, Modifier.align(Alignment.CenterStart).padding(start = HlSpace.l))
                    }
                }
            }
        }
        Specimen("a wall's, quiet: its picture dimmed down to the foot", width = HlLayout.sideColumnWide) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { Backdrop(art, BackdropStyle.Quiet) }
        }
    } }
}

/** The focus treatment alone, and the image inside it: loaded, or failed with its fallback in its place. */
@Composable
private fun Surfaces() {
    val art = shippedImage(R.drawable.banner).url(0)
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        val wide = Modifier.width(HlSize.landscape).aspectRatio(16f / 9f)
        Specimen("surface alone", width = HlSize.landscape) {
            ArtworkSurface({}, wide, shape = HlShapes.wide, lift = Lift.Wide) {}
        }
        Specimen("image", width = HlSize.landscape) {
            ArtworkSurface({}, wide, shape = HlShapes.wide, lift = Lift.Wide) {
                ArtworkImage(art, "Houselights", Modifier.fillMaxSize())
            }
        }
        Specimen("image failed: its fallback", width = HlSize.landscape) {
            ArtworkSurface({}, wide, shape = HlShapes.wide, lift = Lift.Wide) {
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
    MediaRow(header = null) {
        item { Specimen("held", width = HlSize.poster) { PosterCard(sample("Alien", 1979), {}) } }
        item { Specimen("lacking: its title and year, focus outlined", width = HlSize.poster) { AbsentCard("Aliens", 1986) } }
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
        "photo failed: initials" to Person("Cathy Rosier", "La pianiste", brokenImage),
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

/** On a poster's plate (dark image, projector white), then beside text on its light track. */
@Composable
private fun ProgressLines() {
    val c = Hl.colors
    listOf("dark image" to c.background, "white image" to c.content).forEach { (ground, color) ->
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
            listOf(0f to "0%: not drawn", 0.4f to "40%", 1f to "100%").forEach { (progress, label) ->
                Specimen("$ground, $label", width = HlSize.poster) {
                    Box(Modifier.width(HlSize.poster).height(HlSpace.l).background(color)) {
                        OnArtwork { ProgressLine(progress, Modifier.align(Alignment.BottomCenter)) }
                    }
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
        Specimen("poster row (home)", width = HlSize.poster) { CardSkeleton(HlSize.poster, 2f / 3f) }
        Specimen("poster choice", width = HlLayout.posterChoice) { CardSkeleton(HlLayout.posterChoice, 2f / 3f) }
        Specimen("backdrop or logo choice", width = HlLayout.landscapeChoice) {
            CardSkeleton(HlLayout.landscapeChoice, 16f / 9f)
        }
        Specimen("landscape row: unused", width = HlSize.landscape) { CardSkeleton(HlSize.landscape, 16f / 9f) }
        Specimen("title and facts (detail page)", width = HlSize.landscape) { HeroSkeleton() }
    }
}

/** An option of a picker, in one state. */
private class ArtworkChoiceSample(
    val state: String,
    val image: ImageRef?,
    val chosen: Boolean,
    val title: String,
    val caption: String?,
    val placeholder: String? = null,
)

/** The options of one artwork picker as its screen lays them out, after its loading placeholder. */
@Composable
private fun ChoiceRow(kind: ArtworkKind) {
    val picture = shippedImage(if (kind == ArtworkKind.LOGO) R.drawable.ic_launcher_foreground else R.drawable.banner)
    val automatic = stringResource(R.string.artwork_automatic)
    val localFile = stringResource(R.string.artwork_local_file)
    val inType = stringResource(R.string.artwork_title_in_type)
    val options = when (kind) {
        ArtworkKind.POSTER -> listOf(
            ArtworkChoiceSample("chosen", picture, true, automatic, "Français$FACT_GAP${formatDimensions(2000, 3000)}"),
            ArtworkChoiceSample("rest", picture, false, "Anglais", formatDimensions(2000, 3000)),
            ArtworkChoiceSample("image failed: set in type", brokenImage, false, localFile, null),
        )
        ArtworkKind.BACKDROP -> listOf(
            ArtworkChoiceSample("chosen", picture, true, stringResource(R.string.artwork_no_text), formatDimensions(3840, 2160)),
            ArtworkChoiceSample("rest", picture, false, stringResource(R.string.artwork_from_nfo), null),
            ArtworkChoiceSample("image failed: set in type", brokenImage, false, localFile, null),
        )
        ArtworkKind.LOGO -> listOf(
            ArtworkChoiceSample(
                "chosen",
                picture,
                true,
                automatic,
                stringResource(R.string.artwork_any_language) + FACT_GAP + formatDimensions(800, 310),
            ),
            ArtworkChoiceSample("rest", picture, false, "Français", formatDimensions(800, 310)),
            ArtworkChoiceSample("title in type", null, false, inType, null, placeholder = "Le Samouraï"),
            ArtworkChoiceSample(
                "title in type, chosen, long",
                null,
                true,
                inType,
                null,
                placeholder = "Les Aventures extraordinaires d’Adèle Blanc-Sec",
            ),
            ArtworkChoiceSample("image failed: set in type", brokenImage, false, "Anglais", null),
        )
    }
    val width = if (kind == ArtworkKind.POSTER) HlLayout.posterChoice else HlLayout.landscapeChoice
    val ratio = if (kind == ArtworkKind.POSTER) 2f / 3f else 16f / 9f
    MediaRow(header = null) {
        item { Specimen("loading", width = width) { CardSkeleton(width, ratio) } }
        items(options) { option ->
            Specimen(option.state, width = width) {
                ArtworkChoiceCard(
                    image = option.image,
                    selected = option.chosen,
                    title = option.title,
                    caption = option.caption,
                    onClick = {},
                    width = width,
                    aspectRatio = ratio,
                    fit = kind == ArtworkKind.LOGO,
                    placeholder = option.placeholder,
                )
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
private fun Results() {
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

/** Small posters of lists, then the previews at the head of the edit menu's rows. */
@Composable
private fun Thumbs() {
    val art = shippedImage(R.drawable.banner)
    val logo = shippedImage(R.drawable.ic_launcher_foreground)
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        listOf(
            Triple("thumb", art, HlSize.posterThumb),
            Triple("thumb, none: empty frame", null, HlSize.posterThumb),
            Triple("thumb, failed: empty frame", brokenImage, HlSize.posterThumb),
            Triple("large", art, HlSize.posterThumbLarge),
            Triple("row size", art, HlSize.rowPreviewPoster),
            Triple("row size, none: empty frame", null, HlSize.rowPreviewPoster),
        ).forEach { (state, image, width) ->
            Specimen(state, width = HlSize.posterThumbLarge) { PosterThumb(image, width = width) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        listOf(
            Triple("poster", art, PreviewShape.Poster),
            Triple("backdrop", art, PreviewShape.Backdrop),
            Triple("logo", logo, PreviewShape.Logo),
            Triple("poster, failed: empty frame", brokenImage, PreviewShape.Poster),
            Triple("logo, failed: empty frame", brokenImage, PreviewShape.Logo),
        ).forEach { (state, image, shape) ->
            Specimen(state, width = HlSize.rowPreviewWide) { RowPreview(image, shape) }
        }
        Specimen("logo, failed, icon", width = HlSize.rowPreviewWide) {
            RowPreview(brokenImage, PreviewShape.Logo, icon = HlIcons.Logo)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("poster, none: empty frame", width = HlSize.rowPreviewWide) { RowPreview(null, PreviewShape.Poster) }
        Specimen("backdrop, none: empty frame", width = HlSize.rowPreviewWide) { RowPreview(null, PreviewShape.Backdrop) }
        Specimen("logo, none: empty frame", width = HlSize.rowPreviewWide) { RowPreview(null, PreviewShape.Logo) }
        Specimen("logo, none, icon", width = HlSize.rowPreviewWide) { RowPreview(null, PreviewShape.Logo, icon = HlIcons.Logo) }
        Specimen("icon (identify)", width = HlSize.rowPreviewWide) { RowPreview(null, PreviewShape.Backdrop, icon = HlIcons.Search) }
    }
}
