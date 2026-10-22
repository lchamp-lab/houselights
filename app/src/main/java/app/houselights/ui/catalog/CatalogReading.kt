package app.houselights.ui.catalog

import app.houselights.ui.design.HeroDefaults
import app.houselights.ui.design.TwoPaneDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.FormatLines
import app.houselights.ui.design.components.BillingBlock
import app.houselights.ui.design.components.EpisodeHeading
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.TechSpecs
import app.houselights.ui.design.components.TitleArt
import app.houselights.ui.design.components.TitleLine
import app.houselights.ui.util.NBSP
import app.houselights.ui.design.components.WithItsPicture
import app.houselights.ui.design.components.HeadOverview
import app.houselights.ui.design.components.SynopsisSheet
import app.houselights.ui.design.components.FilmsOfHeading
import app.houselights.core.Version
import app.houselights.core.Editions
import app.houselights.ui.design.components.VersionRow
import app.houselights.ui.design.components.VersionsSheet
import app.houselights.ui.util.versionName
import app.houselights.ui.design.components.SearchQuery
import app.houselights.ui.design.components.LetterGrid
import app.houselights.ui.design.components.ResultName
import app.houselights.ui.design.components.ResultsHeading
import androidx.compose.foundation.layout.fillMaxWidth

/** What a title says about itself (name, facts, credits, sheets), without artwork, at the widths screens give. */
internal fun LazyListScope.catalogReading() {
    item { ReadingTitleArtSection() }
    item { ReadingEpisodeHeadingSection() }
    item { ReadingMetaLineSection() }
    item { ReadingBadgeRowSection() }
    item { ReadingTitleLineSection() }
    item { ReadingBillingBlockSection() }
    item { ReadingFilmsOfSection() }
    // A hero is as tall as on the home screen; each takes the reading measure it has there.
    item { ReadingHeroInfoSection() }
    item { ReadingHeroEpisodeSection() }
    item { ReadingHeroLongTitleSection() }
    item { ReadingTechSheetSection() }
    item { ReadingSynopsisSection() }
    item { ReadingVersionsSection() }
    item { ReadingSearchSection() }
}

@Composable
private fun ReadingTitleArtSection() {
    Group("Title art", still = true) {
        ReadingRow {
            Specimen("no logo: the title set in condensed type", Modifier.weight(1f), gap = HlSpace.xxs) {
                TitleArt(title = "Le Samouraï", logo = null, maxWidth = HlLayout.titleArtWidth, maxHeight = HlLayout.titleArtHeight)
            }
            Specimen("logo that fails to load", Modifier.weight(1f), gap = HlSpace.xxs) {
                TitleArt(
                    title = "Playtime", logo = READING_BROKEN_LOGO,
                    maxWidth = HlLayout.titleArtWidth, maxHeight = HlLayout.titleArtHeight,
                )
            }
        }
        ReadingRow {
            Specimen("long title: two lines at most", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                TitleArt(
                    title = "Docteur Folamour ou${NBSP}: comment j’ai appris à ne plus m’en faire et à aimer la bombe",
                    logo = null,
                    maxWidth = HlLayout.titleArtWidth,
                    maxHeight = HlLayout.titleArtHeight,
                )
            }
            Specimen("detail page, measured: short at its own size, long on even lines", Modifier.weight(1f), gap = HlSpace.xxs) {
                TitleArt(
                    title = "Ran",
                    logo = null,
                    maxWidth = HlLayout.titleArtWidth,
                    maxHeight = HlLayout.titleArtHeight,
                    measured = true,
                )
                TitleArt(
                    title = "Les Sept Samouraïs",
                    logo = null,
                    maxWidth = HlLayout.titleArtWidth,
                    maxHeight = HlLayout.titleArtHeight,
                    measured = true,
                )
            }
            Specimen("browsing hero size", Modifier.weight(1f), gap = HlSpace.xxs) {
                TitleArt(
                    title = "Twin Peaks",
                    logo = null,
                    maxWidth = HeroDefaults.titleWidth,
                    maxHeight = HeroDefaults.titleHeight,
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
            Specimen("label and title", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                EpisodeHeading("S1 E3", "Pile ou face")
            }
            Specimen("no label", Modifier.weight(1f), gap = HlSpace.xxs) {
                EpisodeHeading(null, "Pile ou face")
            }
        }
        ReadingRow {
            Specimen("long title: one line", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                EpisodeHeading("S2 E7", "Où l’on apprend enfin ce qui s’est vraiment passé la nuit de la fête au lac")
            }
            Specimen("three-digit episode", Modifier.weight(1f), gap = HlSpace.xxs) {
                EpisodeHeading("S12 E108", "Le Retour")
            }
        }
    }
}

@Composable
private fun ReadingMetaLineSection() {
    Group("Meta line", still = true) {
        ReadingRow {
            Specimen("film, every fact", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) { MetaLine(READING_FILM) }
            Specimen("year only", Modifier.weight(1f), gap = HlSpace.xxs) { MetaLine(sample("Playtime", 1967).copy(runtimeMs = null)) }
        }
        ReadingRow {
            Specimen("series, continuing", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) { MetaLine(READING_SERIES_ON) }
            Specimen("genres hidden, as in the library", Modifier.weight(1f), gap = HlSpace.xxs) {
                MetaLine(READING_FILM, showGenres = false)
            }
        }
        ReadingRow {
            Specimen("series, ended", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) { MetaLine(READING_SERIES_ENDED) }
            Specimen("series, ended, in a side column", Modifier.width(TwoPaneDefaults.side), gap = HlSpace.xxs) {
                MetaLine(READING_SERIES_ENDED)
            }
        }
        ReadingRow {
            Specimen("collection: films owned of all", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                MetaLine(READING_COLLECTION)
            }
            Specimen("no facts: draws nothing", Modifier.weight(1f), gap = HlSpace.xxs) {
                MetaLine(MediaItem(id = "catalog-no-facts", kind = MediaKind.VIDEO, title = "Sans titre"))
            }
        }
        ReadingRow {
            Specimen("long genres: the last goes whole", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                MetaLine(READING_LONG_GENRES)
            }
            Specimen("in a side column: genres, then the score, go", Modifier.width(TwoPaneDefaults.side), gap = HlSpace.xxs) {
                MetaLine(READING_LONG_GENRES)
            }
        }
        ReadingRow {
            Specimen("narrower still: down to the year", Modifier.width(HlSize.poster), gap = HlSpace.xxs) {
                MetaLine(READING_FILM)
            }
        }
    }
}

@Composable
private fun ReadingTitleLineSection() {
    // As wide as the library gives it: the screen inside the safe area.
    Group("Title line", still = true) {
        Specimen("short name and its facts; formats are the page’s", gap = HlSpace.xxs) {
            TitleLine(READING_LINE_SHORT, Modifier.height(HlLayout.infoLine))
        }
        Specimen("long name: it takes what the facts leave", gap = HlSpace.xxs) {
            TitleLine(READING_LINE_SHORT.copy(title = READING_LINE_LONG), Modifier.height(HlLayout.infoLine))
        }
        Specimen("longer still: the name ends in an ellipsis, the facts stay", gap = HlSpace.xxs) {
            TitleLine(READING_LINE_SHORT.copy(title = "$READING_LINE_LONG, $READING_LINE_LONG"), Modifier.height(HlLayout.infoLine))
        }
        Specimen("no facts", gap = HlSpace.xxs) { TitleLine(sample("Sans titre", 0).copy(year = null, runtimeMs = null)) }
    }
}

@Composable
private fun ReadingBadgeRowSection() {
    Group("Formats: words on a page, no frames; a mark keeps its frame", still = true) {
        ReadingRow {
            Specimen(
                "from the file: codec and rate, the sound by its full name",
                Modifier.width(HlLayout.readableNarrow),
                gap = HlSpace.xxs,
            ) {
                FormatLines(READING_TECH_FULL)
            }
            Specimen("a lean file", Modifier.width(TwoPaneDefaults.side), gap = HlSpace.xxs) { FormatLines(READING_TECH_LEAN) }
        }
        ReadingRow {
            Specimen(
                "from badges: Dolby Vision says its HDR10; TrueHD, Atmos: one",
                Modifier.width(HlLayout.readableNarrow),
                gap = HlSpace.xxs,
            ) {
                FormatLines(sample("Le Samouraï", 1967).copy(badges = listOf("4K", "Dolby Vision", "HDR10", "Atmos", "TrueHD", "7.1")))
            }
            Specimen("Dolby Digital Plus for E-AC3; a remux is not a format", Modifier.width(TwoPaneDefaults.side), gap = HlSpace.xxs) {
                FormatLines(sample("Playtime", 1967).copy(badges = listOf("1080p", "E-AC3", "5.1", "Remux")))
            }
        }
        ReadingRow {
            Specimen("no formats: draws nothing", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                FormatLines(sample("Sans titre", 0))
            }
            Specimen("marks keep a frame: a rating, a key, an id", Modifier.weight(1f), gap = HlSpace.xxs) {
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                    Badge("12")
                    Badge("OK")
                    Badge("TMDB 5511")
                }
            }
        }
    }
}

@Composable
private fun ReadingFilmsOfSection() {
    Group("The billing of a row", still = true) {
        ReadingRow {
            Specimen("a row the library writes: a director's films", Modifier.width(HlLayout.readable), gap = HlSpace.xxs) {
                FilmsOfHeading("Denis Villeneuve")
            }
        }
    }
}

@Composable
private fun ReadingBillingBlockSection() {
    Group("Billing block", still = true) {
        ReadingRow {
            Specimen("a director and three leads", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                BillingBlock(listOf("Jean Vigo"), listOf("Dita Parlo", "Jean Dasté", "Michel Simon"))
            }
            Specimen("no names: draws nothing", Modifier.weight(1f), gap = HlSpace.xxs) { BillingBlock(emptyList(), emptyList()) }
        }
        ReadingRow {
            Specimen("long names: the last leads drop", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                BillingBlock(READING_LONG_DIRECTOR, READING_LONG_CAST)
            }
            Specimen("the same in a side column", Modifier.width(TwoPaneDefaults.side), gap = HlSpace.xxs) {
                BillingBlock(READING_LONG_DIRECTOR, READING_LONG_CAST)
            }
        }
        ReadingRow {
            Specimen("cast only, as for a series", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                BillingBlock(emptyList(), listOf("Kyle MacLachlan", "Sheryl Lee", "Lara Flynn Boyle", "Michael Ontkean"))
            }
        }
        ReadingRow {
            Specimen("two directors: the first only", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                BillingBlock(listOf("Jean-Marie Straub", "Danièle Huillet"), listOf("Gilberto Adamo", "Angela Nugara"))
            }
        }
    }
}

@Composable
private fun ReadingHeroInfoSection() {
    Group("Hero info", still = true) {
        ReadingRow {
            Specimen("film, no artwork, in progress", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                HeroInfo(READING_HERO_FILM, Modifier.height(HlLayout.hero))
            }
            Specimen("no rows yet: draws nothing", Modifier.weight(1f), gap = HlSpace.xxs) {
                HeroInfo(null, Modifier.height(HlLayout.hero))
            }
        }
        ReadingRow {
            Specimen(
                "a wall's line, said with its picture: here at once, no backdrop under it",
                Modifier.width(HlLayout.readableNarrow),
                gap = HlSpace.xxs,
            ) {
                WithItsPicture(READING_HERO_FILM) { TitleLine(it) }
            }
        }
    }
}

@Composable
private fun ReadingHeroEpisodeSection() {
    Group("Hero info: episode", still = true) {
        Specimen("episode of a series, no artwork", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
            HeroInfo(READING_HERO_EPISODE, Modifier.height(HlLayout.hero))
        }
    }
}

@Composable
private fun ReadingHeroLongTitleSection() {
    Group("Hero info: long title", still = true) {
        Specimen("two-line title in the hero’s height", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
            HeroInfo(READING_HERO_LONG, Modifier.height(HlLayout.hero))
        }
    }
}

@Composable
private fun ReadingTechSheetSection() {
    // Opened as a detail page opens it: the same panel, its first row focused.
    Group("Technical sheet") {
        var open by remember { mutableStateOf<MediaItem?>(null) }
        ReadingRow {
            Specimen("DV 8.1 and HDR10+, two French dubs, a note", gap = HlSpace.xxs) {
                HlButton("Dolby", { open = READING_TECH_DOLBY })
            }
            Specimen("longer than the screen: its rows scroll", gap = HlSpace.xxs) {
                HlButton("3 audio, 14 subtitles", { open = READING_TECH_FULL })
            }
        }
        Spacer(Modifier.height(HlSpace.l))
        ReadingRow {
            Specimen("a lean file", gap = HlSpace.xxs) { HlButton("1 audio, no subtitles", { open = READING_TECH_LEAN }) }
            Specimen("nothing named, as a server gives it", gap = HlSpace.xxs) { HlButton("Sparse", { open = READING_TECH_SPARSE }) }
            Specimen("the longest words, side by side", gap = HlSpace.xxs) { HlButton("Stress", { open = READING_TECH_STRESS }) }
        }
        open?.let { TechSpecs(it, onDismiss = { open = null }) }
    }
}

@Composable
private fun ReadingSearchSection() {
    Group("Search: its title, the field its keys always type in, its keys, its rows of results") {
        val hint = stringResource(R.string.search_hint)
        ReadingRow {
            Specimen("nothing typed: the cursor waits, the hint says what", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                SearchQuery("", hint, Modifier.fillMaxWidth())
            }
            Specimen("typed", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                SearchQuery("AMEL", hint, Modifier.fillMaxWidth())
            }
        }
        Specimen("longer than the field", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
            SearchQuery("LES PARAPLUIES DE CHERBOURG RESTAURÉ", hint, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(HlSpace.l))
        ReadingRow {
            Specimen("the keys, without the system's keyboard: tiles in the picture's dark, no edge", gap = HlSpace.xxs) {
                LetterGrid(onKey = {}, onSpace = {}, onDelete = {})
            }
            Specimen("a row's title and count, the result in light under it", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xs) {
                Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    ResultsHeading(stringResource(R.string.search_titles), 2)
                    ResultName(sample("Les Parapluies de Cherbourg", 1964))
                    ResultName(sample("Un titre bien trop long pour la ligne qui le nomme sous sa rangée", 2026))
                }
            }
        }
    }
}

@Composable
private fun ReadingSynopsisSection() {
    // Opened as a detail page opens it: from the overview its head cuts.
    Group("Synopsis: the overview a head cuts, whole beside the page") {
        var open by remember { mutableStateOf(false) }
        val film = READING_TECH_DOLBY.copy(overview = READING_LONG_OVERVIEW)
        ReadingRow {
            Specimen("cut: focus, OK opens it whole", Modifier.width(HlLayout.readable), gap = HlSpace.xxs) {
                HeadOverview(READING_LONG_OVERVIEW, onOpen = { open = true })
            }
            Specimen("whole: words alone", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                HeadOverview("Un instituteur part en vacances avec ses élèves.", onOpen = {})
            }
        }
        if (open) SynopsisSheet(film, onDismiss = { open = false })
    }
}

private const val READING_LONG_OVERVIEW =
    "Un instituteur part en vacances avec ses élèves. Sur la plage, une barque les attend, et la mer. " +
        "Les jours passent, les enfants apprennent à nager, à pêcher, à se taire quand l'orage vient. " +
        "Un soir, la barque ne revient pas : l'instituteur part la chercher, seul, et ne trouve que la nuit. " +
        "Au matin, tout le village est sur la plage, et chacun raconte ce qu'il a vu, qui n'est jamais la même chose."

@Composable
private fun ReadingVersionsSection() {
    // Opened as a detail page opens it: the copy Lire plays focused.
    Group("Versions: a film in several copies") {
        var open by remember { mutableStateOf(false) }
        val film = READING_TECH_DOLBY
        val versions = listOf(
            Version(film, Editions.EXTENDED, null, film.media, 78_400_000_000, 8_880_000, chosen = true, best = true, key = "1"),
            Version(
                film, Editions.THEATRICAL, null, READING_TECH_LEAN.media, 21_300_000_000, 7_920_000,
                chosen = false, best = false, key = "2",
            ),
            Version(film, null, null, READING_TECH_SPARSE.media, null, null, chosen = false, best = false, key = "3"),
        )
        ReadingRow {
            Specimen("a copy, chosen", Modifier.width(HlLayout.readableNarrow), gap = HlSpace.xxs) {
                VersionRow(versionName(versions[0].edition, null, 0), versions[0], onChoose = {})
            }
            Specimen("the panel", gap = HlSpace.xxs) { HlButton("Versions", { open = true }) }
        }
        if (open) VersionsSheet(film, versions, onChoose = {}, onDismiss = { open = false })
    }
}

/** States side by side, top-aligned. */
@Composable
private fun ReadingRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l), verticalAlignment = Alignment.Top, content = content)
}

/** A logo whose image never loads. */
private val READING_BROKEN_LOGO = ImageRef("catalog-broken-logo") { "file:///nonexistent/logo.png" }

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
            frameRate = 24000f / 1001f,
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

/** The panel as designed: every picture row, the default dub first, a region where two dubs share a language. */
private val READING_TECH_DOLBY = sample("Bugonia", 2025).copy(
    fileName = "Bugonia (2025) 2160p WEB-DL DV HDR10+ DDP Atmos.mkv",
    media = MediaInfo(
        container = "MKV",
        sizeBytes = 9_310_000_000,
        durationMs = 7_080_000,
        video = VideoInfo(
            codec = Codecs.HEVC,
            width = 3840,
            height = 1608,
            bitDepth = 10,
            hdr = listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10_PLUS, HdrFormat.HDR10),
            dolbyVisionProfile = 8,
            dolbyVisionCompatibility = 1,
            frameRate = 24000f / 1001f,
            bitrateKbps = 6_500,
        ),
        audio = listOf(
            AudioTrack(codec = Codecs.EAC3, channels = 6, language = "fr-FR", atmos = true, default = true, bitrateKbps = 768),
            AudioTrack(codec = Codecs.AC3, channels = 6, language = "fre", title = "VFQ", bitrateKbps = 640),
            AudioTrack(codec = Codecs.TRUEHD, channels = 8, language = "eng", atmos = true, bitrateKbps = 2_857),
            AudioTrack(codec = Codecs.AC3, channels = 6, language = "eng", title = "Compatibility Track", bitrateKbps = 448),
        ),
        subtitles = listOf(
            SubtitleTrack(language = "fre", format = Codecs.SRT, forced = true, default = true),
            SubtitleTrack(language = "fre", format = Codecs.SRT),
            SubtitleTrack(language = "fre", format = Codecs.PGS, hearingImpaired = true),
            SubtitleTrack(language = "eng", format = Codecs.PGS),
            SubtitleTrack(language = "eng", format = Codecs.PGS, hearingImpaired = true),
        ),
    ),
)

/** The widest words a row can hold: a language with its region beside a format by its full name. */
private val READING_TECH_STRESS = sample("Cidade de Deus", 2002).copy(
    fileName = "Cidade.de.Deus.2002.REMASTERED.1080p.BluRay.REMUX.AVC.DDP.Atmos.7.1-GROUPWITHAVERYLONGNAME.mkv",
    media = MediaInfo(
        container = "MKV",
        sizeBytes = 31_400_000_000,
        durationMs = 7_800_000,
        video = VideoInfo(codec = Codecs.AVC, width = 1920, height = 1040, bitDepth = 8, frameRate = 24000f / 1001f),
        audio = listOf(
            AudioTrack(codec = Codecs.EAC3, channels = 8, language = "pt-BR", atmos = true, default = true, bitrateKbps = 1_024),
            AudioTrack(codec = Codecs.DTS_HD_MA, channels = 8, language = "pt-PT", bitrateKbps = 4_054),
            AudioTrack(
                codec = Codecs.AC3, channels = 2, language = "por",
                title = "Commentaire audio du réalisateur Fernando Meirelles et de la co-réalisatrice Kátia Lund",
                bitrateKbps = 192,
            ),
        ),
        subtitles = listOf(SubtitleTrack(language = "pt-BR", format = Codecs.PGS, forced = true, hearingImpaired = true, default = true)),
    ),
)

private val READING_TECH_LEAN = sample("Playtime", 1967).copy(
    fileName = "Playtime.mp4",
    media = MediaInfo(
        container = "MP4",
        sizeBytes = 2_150_000_000,
        durationMs = 7_440_000,
        video = VideoInfo(codec = Codecs.AVC, width = 1920, height = 872, bitDepth = 8, frameRate = 25f),
        audio = listOf(AudioTrack(codec = Codecs.AAC, channels = 2, language = "fre", default = true)),
    ),
)

/** One track of each, nothing named: no picture section, size or file name, as a server gives it. */
private val READING_TECH_SPARSE = sample("Pierrot le fou", 1965).copy(
    media = MediaInfo(
        container = "ISO",
        audio = listOf(AudioTrack(codec = Codecs.DTS, channels = 6)),
        subtitles = listOf(SubtitleTrack(format = Codecs.VOBSUB)),
    ),
)
