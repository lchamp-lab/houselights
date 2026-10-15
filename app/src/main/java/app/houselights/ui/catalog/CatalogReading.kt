package app.houselights.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.HdrFormat
import app.houselights.core.ImageRef
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SubtitleTrack
import app.houselights.core.VideoInfo
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.BadgeRow
import app.houselights.ui.design.components.BillingBlock
import app.houselights.ui.design.components.DefinitionList
import app.houselights.ui.design.components.EpisodeHeading
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.design.components.PlaceHeading
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlModal
import app.houselights.ui.design.components.InfoBlock
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.TechSpecs
import app.houselights.ui.design.components.TitleArt
import app.houselights.ui.design.components.TitleLine
import app.houselights.ui.util.NBSP

/**
 * What a title says about itself, in its states: its name, its facts and formats, its credits, the
 * blocks of a sheet, and the technical sheet as a detail page opens it. Nothing here has artwork.
 * Wide parts are shown at the width a screen gives them (readableNarrow), not squeezed.
 */
internal fun LazyListScope.catalogReading() {
    item { ReadingTitleArtSection() }
    item { ReadingEpisodeHeadingSection() }
    item { ReadingMetaLineSection() }
    item { ReadingBadgeRowSection() }
    item { ReadingTitleLineSection() }
    item { ReadingBillingBlockSection() }
    // A hero is as tall as on the home screen; each takes the reading measure it has there.
    item { ReadingHeroInfoSection() }
    item { ReadingHeroEpisodeSection() }
    item { ReadingHeroLongTitleSection() }
    item { ReadingInfoBlockSection() }
    item { ReadingDefinitionListSection() }
    item { ReadingTechSheetSection() }
}

@Composable
private fun ReadingTitleArtSection() {
    Group("Title art", still = true) {
        ReadingRow {
            ReadingCase("no logo: the title set in condensed type", Modifier.weight(1f)) {
                TitleArt(title = "Le Samouraï", logo = null, maxWidth = HlLayout.titleArtWidth, maxHeight = HlLayout.titleArtHeight)
            }
            ReadingCase("logo that fails to load", Modifier.weight(1f)) {
                TitleArt(
                    title = "Playtime", logo = READING_BROKEN_LOGO,
                    maxWidth = HlLayout.titleArtWidth, maxHeight = HlLayout.titleArtHeight,
                )
            }
        }
        ReadingRow {
            ReadingCase("long title: two lines at most", Modifier.width(HlLayout.readableNarrow)) {
                TitleArt(
                    title = "Docteur Folamour ou${NBSP}: comment j’ai appris à ne plus m’en faire et à aimer la bombe",
                    logo = null,
                    maxWidth = HlLayout.titleArtWidth,
                    maxHeight = HlLayout.titleArtHeight,
                )
            }
            ReadingCase("browsing hero size", Modifier.weight(1f)) {
                TitleArt(
                    title = "Twin Peaks",
                    logo = null,
                    maxWidth = HlLayout.heroTitleWidth,
                    maxHeight = HlLayout.heroTitleHeight,
                    style = Hl.type.headline,
                )
            }
        }
    }
}

@Composable
private fun ReadingEpisodeHeadingSection() {
    Group("Episode heading", still = true) {
        ReadingRow {
            ReadingCase("label and title", Modifier.width(HlLayout.readableNarrow)) {
                EpisodeHeading("S1 E3", "Pile ou face")
            }
            ReadingCase("no label", Modifier.weight(1f)) {
                EpisodeHeading(null, "Pile ou face")
            }
        }
        ReadingRow {
            ReadingCase("long title: one line", Modifier.width(HlLayout.readableNarrow)) {
                EpisodeHeading("S2 E7", "Où l’on apprend enfin ce qui s’est vraiment passé la nuit de la fête au lac")
            }
            ReadingCase("three-digit episode", Modifier.weight(1f)) {
                EpisodeHeading("S12 E108", "Le Retour")
            }
        }
    }
}

@Composable
private fun ReadingMetaLineSection() {
    Group("Meta line", still = true) {
        ReadingRow {
            ReadingCase("film, every fact", Modifier.width(HlLayout.readableNarrow)) { MetaLine(READING_FILM) }
            ReadingCase("year only", Modifier.weight(1f)) { MetaLine(sample("Playtime", 1967).copy(runtimeMs = null)) }
        }
        ReadingRow {
            ReadingCase("series, continuing", Modifier.width(HlLayout.readableNarrow)) { MetaLine(READING_SERIES_ON) }
            ReadingCase("genres hidden, as in the library", Modifier.weight(1f)) { MetaLine(READING_FILM, showGenres = false) }
        }
        ReadingRow {
            ReadingCase("series, ended", Modifier.width(HlLayout.readableNarrow)) { MetaLine(READING_SERIES_ENDED) }
            ReadingCase("series, ended, in a side column", Modifier.width(HlLayout.sideColumn)) { MetaLine(READING_SERIES_ENDED) }
        }
        ReadingRow {
            ReadingCase("collection: films owned of all", Modifier.width(HlLayout.readableNarrow)) { MetaLine(READING_COLLECTION) }
            ReadingCase("no facts: draws nothing", Modifier.weight(1f)) {
                MetaLine(MediaItem(id = "catalog-no-facts", kind = MediaKind.VIDEO, title = "Sans titre"))
            }
        }
        ReadingRow {
            ReadingCase("long genres: the last goes whole", Modifier.width(HlLayout.readableNarrow)) { MetaLine(READING_LONG_GENRES) }
            ReadingCase("in a side column: genres, then the score, go", Modifier.width(HlLayout.sideColumn)) {
                MetaLine(READING_LONG_GENRES)
            }
        }
        ReadingRow {
            ReadingCase("narrower still: down to the year", Modifier.width(HlLayout.definitionLabelShort)) { MetaLine(READING_FILM) }
        }
    }
}

@Composable
private fun ReadingTitleLineSection() {
    // As wide as the library gives it: the screen inside the safe area.
    Group("Title line", still = true) {
        ReadingCase("short name, its facts and four formats") { TitleLine(READING_LINE_SHORT, Modifier.height(HlLayout.infoLine)) }
        ReadingCase("long name: the formats go first, from the last") {
            TitleLine(READING_LINE_SHORT.copy(title = READING_LINE_LONG), Modifier.height(HlLayout.infoLine))
        }
        ReadingCase("longer still: the name ends in an ellipsis, the facts stay") {
            TitleLine(READING_LINE_SHORT.copy(title = "$READING_LINE_LONG, $READING_LINE_LONG"), Modifier.height(HlLayout.infoLine))
        }
        ReadingCase("no facts, no formats") { TitleLine(sample("Sans titre", 0).copy(year = null, runtimeMs = null)) }
    }
}

@Composable
private fun ReadingBadgeRowSection() {
    Group("Badge row", still = true) {
        ReadingRow {
            ReadingCase("a film’s formats", Modifier.width(HlLayout.readableNarrow)) {
                BadgeRow(listOf("4K", "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1"))
            }
            ReadingCase("every format, in a side column", Modifier.width(HlLayout.sideColumn)) {
                BadgeRow(listOf("4K", "Dolby Vision", "HDR10+", "HDR10", "TrueHD Atmos", "DTS:X", "7.1", "16"))
            }
        }
        ReadingRow {
            ReadingCase("definitions by name", Modifier.width(HlLayout.readableNarrow)) {
                BadgeRow(listOf("4K", "1080p", "720p", "SD", "DTS-HD MA", "5.1"))
            }
            ReadingCase("no badges: draws nothing", Modifier.weight(1f)) { BadgeRow(emptyList()) }
        }
        ReadingRow {
            ReadingCase("on one line, in a side column: the last ones go", Modifier.width(HlLayout.sideColumn)) {
                BadgeRow(listOf("4K", "Dolby Vision", "HDR10+", "HDR10", "TrueHD Atmos", "DTS:X", "7.1", "16"), oneLine = true)
            }
        }
    }
}

@Composable
private fun ReadingBillingBlockSection() {
    Group("Billing block", still = true) {
        ReadingRow {
            ReadingCase("a director and three leads", Modifier.width(HlLayout.readableNarrow)) {
                BillingBlock(listOf("Jean Vigo"), listOf("Dita Parlo", "Jean Dasté", "Michel Simon"))
            }
            ReadingCase("no names: draws nothing", Modifier.weight(1f)) { BillingBlock(emptyList(), emptyList()) }
        }
        ReadingRow {
            ReadingCase("long names: the last leads drop", Modifier.width(HlLayout.readableNarrow)) {
                BillingBlock(READING_LONG_DIRECTOR, READING_LONG_CAST)
            }
            ReadingCase("the same in a side column", Modifier.width(HlLayout.sideColumn)) {
                BillingBlock(READING_LONG_DIRECTOR, READING_LONG_CAST)
            }
        }
        ReadingRow {
            ReadingCase("cast only, as for a series", Modifier.width(HlLayout.readableNarrow)) {
                BillingBlock(emptyList(), listOf("Kyle MacLachlan", "Sheryl Lee", "Lara Flynn Boyle", "Michael Ontkean"))
            }
        }
        ReadingRow {
            ReadingCase("two directors: the first only", Modifier.width(HlLayout.readableNarrow)) {
                BillingBlock(listOf("Jean-Marie Straub", "Danièle Huillet"), listOf("Gilberto Adamo", "Angela Nugara"))
            }
        }
    }
}

@Composable
private fun ReadingHeroInfoSection() {
    Group("Hero info", still = true) {
        ReadingRow {
            ReadingCase("film, no artwork, in progress", Modifier.width(HlLayout.readableNarrow)) {
                HeroInfo(READING_HERO_FILM, Modifier.height(HlLayout.hero))
            }
            ReadingCase("nothing focused: draws nothing", Modifier.weight(1f)) {
                HeroInfo(null, Modifier.height(HlLayout.hero))
            }
        }
        ReadingRow {
            ReadingCase("a library's tab focused: the place", Modifier.width(HlLayout.readableNarrow)) {
                PlaceHeading("Films", listOf("9 titres", "7 en Ultra HD"), Modifier.height(HlLayout.hero))
            }
            ReadingCase("home's tab, nothing started: its name alone", Modifier.weight(1f)) {
                PlaceHeading("Accueil", listOf(null), Modifier.height(HlLayout.hero))
            }
        }
    }
}

@Composable
private fun ReadingHeroEpisodeSection() {
    Group("Hero info: episode", still = true) {
        ReadingCase("episode of a series, no artwork", Modifier.width(HlLayout.readableNarrow)) {
            HeroInfo(READING_HERO_EPISODE, Modifier.height(HlLayout.hero))
        }
    }
}

@Composable
private fun ReadingHeroLongTitleSection() {
    Group("Hero info: long title", still = true) {
        ReadingCase("two-line title in the hero’s height", Modifier.width(HlLayout.readableNarrow)) {
            HeroInfo(READING_HERO_LONG, Modifier.height(HlLayout.hero))
        }
    }
}

@Composable
private fun ReadingInfoBlockSection() {
    Group("Info block", still = true) {
        ReadingRow {
            ReadingCase("a paragraph", Modifier.weight(1f)) {
                InfoBlock("Résumé") {
                    Column(Modifier.widthIn(max = HlLayout.readable)) {
                        Text(
                            "Il n’y a pas de plus profonde solitude que celle du samouraï.",
                            style = Hl.type.title, color = Hl.colors.content,
                        )
                        Spacer(Modifier.height(HlSpace.xs))
                        Text(READING_PLOT, style = Hl.type.body, color = Hl.colors.contentSecondary)
                    }
                }
            }
            ReadingCase("a definition list", Modifier.weight(1f)) {
                InfoBlock("Détails") {
                    DefinitionList(
                        listOf(
                            "Genres" to "Policier, Drame, Thriller",
                            "Studios" to "Filmel, CICC, Fida Cinematografica",
                            "Pays" to "France, Italie",
                        ),
                        labelWidth = HlLayout.definitionLabelShort,
                    )
                }
            }
        }
        ReadingRow {
            ReadingCase("badges", Modifier.weight(1f)) {
                InfoBlock("Identifiants") {
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                        Badge("TMDB 5511")
                        Badge("IMDb tt0062229")
                    }
                }
            }
            ReadingCase("no content", Modifier.weight(1f)) { InfoBlock("Distribution") {} }
        }
    }
}

@Composable
private fun ReadingDefinitionListSection() {
    Group("Definition list", still = true) {
        ReadingRow {
            ReadingCase("default label column", Modifier.weight(1f)) {
                DefinitionList(
                    listOf(
                        "Conteneur" to "Matroska",
                        "Taille" to "61,8${NBSP}Go",
                        "Débit moyen" to "78,5${NBSP}Mb/s",
                        "Durée" to "1${NBSP}h${NBSP}45",
                    ),
                )
            }
            ReadingCase("short label column, a value that wraps", Modifier.weight(1f)) {
                DefinitionList(
                    listOf(
                        "Studios" to "Filmel, Compagnie industrielle et commerciale cinématographique, Fida Cinematografica",
                        "Genres" to "Policier, Drame, Thriller",
                    ),
                    labelWidth = HlLayout.definitionLabelShort,
                )
            }
        }
        ReadingRow {
            ReadingCase("labels longer than their column: the value on their first line", Modifier.weight(1f)) {
                DefinitionList(
                    listOf(
                        "Fréquence d’images" to "23,976${NBSP}im/s",
                        "Profondeur de couleur" to "10${NBSP}bits",
                        "Format d’image" to "2,39:1",
                    ),
                    labelWidth = HlLayout.definitionLabelShort,
                )
            }
            ReadingCase("no rows: draws nothing", Modifier.weight(1f)) { DefinitionList(emptyList()) }
        }
    }
}

@Composable
private fun ReadingTechSheetSection() {
    // Opened as a detail page opens it: the same sheet, title and facts, no initial focus.
    Group("Technical sheet") {
        var open by remember { mutableStateOf<MediaItem?>(null) }
        ReadingRow {
            ReadingCase("longer than the screen: its content scrolls") { HlButton("3 audio, 14 subtitles", { open = READING_TECH_FULL }) }
            ReadingCase("a lean file") { HlButton("1 audio, no subtitles", { open = READING_TECH_LEAN }) }
            ReadingCase("no video, unknown languages, no size") { HlButton("Sparse", { open = READING_TECH_SPARSE }) }
        }
        open?.let { shown ->
            val media = shown.media
            if (media != null) {
                HlModal(
                    stringResource(R.string.tech_details),
                    onDismiss = { open = null },
                    facts = listOf(shown.title, shown.year?.toString()),
                ) {
                    TechSpecs(media, fileName = shown.fileName)
                }
            }
        }
    }
}

/** One state of a component, captioned underneath. */
@Composable
private fun ReadingCase(caption: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
        content()
        Text(caption, style = Hl.type.caption, color = Hl.colors.contentTertiary)
    }
}

/** States side by side, top-aligned. */
@Composable
private fun ReadingRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Top, content = content)
}

/** A logo whose image never loads. */
private val READING_BROKEN_LOGO = ImageRef("catalog-broken-logo") { "file:///nonexistent/logo.png" }

private const val READING_PLOT =
    "Jef Costello, tueur à gages méthodique, se ménage un alibi parfait avant d’abattre le patron d’une boîte de nuit. " +
        "Mais une pianiste l’a vu sortir du bureau, et la police comme ses commanditaires se lancent à sa poursuite."

private val READING_FILM = sample("Le Samouraï", 1967).copy(
    runtimeMs = 6_300_000,
    officialRating = "12",
    communityRating = 7.9,
    genres = listOf("Policier", "Drame", "Thriller"),
)

private val READING_SERIES_ON = sample("Slow Horses", 2022, kind = MediaKind.SERIES).copy(
    continuing = true,
    childCount = 4,
    officialRating = "16",
    communityRating = 8.1,
    genres = listOf("Drame", "Thriller"),
)

private val READING_SERIES_ENDED = sample("Twin Peaks", 1990, kind = MediaKind.SERIES).copy(
    endYear = 1991,
    childCount = 2,
    officialRating = "12",
    communityRating = 8.6,
    genres = listOf("Drame", "Mystère", "Policier"),
)

private val READING_COLLECTION = sample("Trois Couleurs", 1993, kind = MediaKind.COLLECTION).copy(
    endYear = 1994,
    childCount = 2,
    totalCount = 3,
    genres = listOf("Drame"),
)

private val READING_LINE_SHORT = READING_FILM.copy(badges = listOf("4K", "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1"))

private val READING_LINE_LONG = "Le Seigneur des anneaux$NBSP: Le Retour du roi, version longue"

private val READING_LONG_GENRES = sample("Le Voyage de Chihiro", 2001).copy(
    runtimeMs = 7_500_000,
    officialRating = "U",
    communityRating = 8.5,
    genres = listOf("Film d’animation", "Fantastique", "Aventure familiale", "Comédie dramatique"),
)

private val READING_LONG_DIRECTOR = listOf("Apichatpong Weerasethakul")
private val READING_LONG_CAST = listOf("Thanapat Saisaymar", "Jenjira Pongpas Widner", "Sakda Kaewbuadee", "Natthakarn Aphaiwonk")

private val READING_HERO_FILM = sample("La Nuit américaine", 1973, positionMs = 2_400_000).copy(
    runtimeMs = 6_960_000,
    officialRating = "12",
    communityRating = 7.8,
    genres = listOf("Comédie", "Drame", "Romance"),
    badges = listOf("1080p", "DTS-HD MA", "2.0"),
    overview = "Aux studios de la Victorine, à Nice, Ferrand tourne «${NBSP}Je vous présente Paméla${NBSP}». Caprices de la vedette, " +
        "amours de l’équipe, accidents de tournage${NBSP}: le film avance malgré tout, et le cinéma passe avant la vie.",
)

private val READING_HERO_EPISODE = sample("Zen, ou l’art d’attraper un tueur", 1990, kind = MediaKind.EPISODE).copy(
    seriesTitle = "Twin Peaks",
    seasonNumber = 1,
    episodeNumber = 3,
    runtimeMs = 2_820_000,
    officialRating = "12",
    communityRating = 8.6,
    genres = listOf("Drame", "Mystère"),
    badges = listOf("1080p", "DTS-HD MA", "5.1"),
    overview = "Cooper fait part au shérif Truman d’un rêve étrange${NBSP}: un homme au bras manquant, une pièce aux rideaux rouges, " +
        "et une femme qui lui murmure à l’oreille le nom du meurtrier.",
)

private val READING_HERO_LONG = sample("Le Charme discret de la bourgeoisie", 1972).copy(
    runtimeMs = 6_120_000,
    officialRating = "12",
    communityRating = 7.6,
    genres = listOf("Comédie", "Satire", "Surréalisme"),
    badges = listOf("4K", "Dolby Vision", "HDR10", "DTS-HD MA", "1.0"),
    overview = "Six bourgeois tentent de dîner ensemble. Un malentendu, une veillée funèbre, une manœuvre militaire ou l’arrivée " +
        "de la police les en empêchent chaque fois, entre rêve et réalité, sans que rien ne trouble leurs bonnes manières.",
)

/** Three audio tracks and fourteen subtitles: longer than the screen, so the sheet scrolls. */
private val READING_TECH_FULL = sample("Le Samouraï", 1967).copy(
    fileName = "Le Samouraï (1967) 2160p UHD Blu-ray Remux HEVC Dolby Vision TrueHD Atmos 7.1.mkv",
    media = MediaInfo(
        container = "MKV",
        sizeBytes = 61_840_000_000,
        durationMs = 6_300_000,
        video = VideoInfo(
            codec = Codecs.HEVC,
            width = 3840,
            height = 1608,
            bitDepth = 10,
            hdr = listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10),
            dolbyVisionProfile = 7,
        ),
        audio = listOf(
            AudioTrack(codec = Codecs.TRUEHD, channels = 8, language = "fre", title = "Version restaurée", atmos = true, default = true),
            AudioTrack(codec = Codecs.DTS_HD_MA, channels = 6, language = "eng"),
            AudioTrack(
                codec = Codecs.AC3, channels = 2, language = "fre",
                title = "Commentaire audio de Ginette Vincendeau, historienne du cinéma",
            ),
        ),
        subtitles = listOf(
            SubtitleTrack(language = "fre", format = Codecs.PGS, forced = true, default = true),
            SubtitleTrack(language = "fre", format = Codecs.PGS),
            SubtitleTrack(language = "fre", format = Codecs.SRT, hearingImpaired = true),
            SubtitleTrack(language = "eng", format = Codecs.PGS),
            SubtitleTrack(language = "eng", format = Codecs.PGS, hearingImpaired = true),
            SubtitleTrack(language = "ger", format = Codecs.PGS),
            SubtitleTrack(language = "spa", format = Codecs.PGS),
            SubtitleTrack(language = "ita", format = Codecs.PGS),
            SubtitleTrack(language = "por", format = Codecs.PGS),
            SubtitleTrack(language = "dut", format = Codecs.PGS),
            SubtitleTrack(language = "swe", format = Codecs.PGS),
            SubtitleTrack(language = "jpn", format = Codecs.PGS),
            SubtitleTrack(language = "pol", format = Codecs.ASS),
            SubtitleTrack(format = Codecs.VOBSUB),
        ),
    ),
)

private val READING_TECH_LEAN = sample("Playtime", 1967).copy(
    fileName = "Playtime.mp4",
    media = MediaInfo(
        container = "MP4",
        sizeBytes = 2_150_000_000,
        durationMs = 7_440_000,
        video = VideoInfo(codec = Codecs.AVC, width = 1920, height = 872, bitDepth = 8),
        audio = listOf(AudioTrack(codec = Codecs.AAC, channels = 2, language = "fre", default = true)),
    ),
)

/** A container, one track of each and nothing named: no picture block, no size, no file name. */
private val READING_TECH_SPARSE = sample("Pierrot le fou", 1965).copy(
    media = MediaInfo(
        container = "ISO",
        audio = listOf(AudioTrack(codec = Codecs.DTS, channels = 6)),
        subtitles = listOf(SubtitleTrack(format = Codecs.VOBSUB)),
    ),
)
