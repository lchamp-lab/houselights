package app.houselights.readme

/**
 * The README's library: recent films and shows, by their TMDB id, as a well-equipped home cinema holds them. Only the
 * ids, the files' names and tracks and what was watched are written here; titles, texts, posters, backdrops, logos and
 * cast come from TMDB in English when the captures are made ([ReadmeLibrary]), and nothing of TMDB's is committed but
 * the README's images. Change a title here, then regenerate (CONTRIBUTING, README's images).
 */
internal object ReadmeTitles {
    /** The NAS the folders are on, as home's state door and the settings name it. */
    const val NAS = "192.168.1.20"

    /** The films, by title: each in a folder named as Radarr names it, its TMDB id in the folder's name. */
    val FILMS = listOf(
        Film(438631, "Dune", 2021, 155, Master.UHD_DV_ATMOS, added = 130.0, watched = 95),
        Film(693134, "Dune: Part Two", 2024, 166, Master.UHD_DV_ATMOS, added = 88.0),
        Film(872585, "Oppenheimer", 2023, 180, Master.UHD_HDR10_DTS, added = 128.0, watched = 104),
        Film(792307, "Poor Things", 2023, 141, Master.UHD_DV_ATMOS, added = 126.0, watched = 83),
        Film(466420, "Killers of the Flower Moon", 2023, 206, Master.UHD_WEB, added = 124.0, watched = 77),
        Film(786892, "Furiosa: A Mad Max Saga", 2024, 148, Master.UHD_DV_ATMOS, added = 96.0, watched = 61),
        Film(929590, "Civil War", 2024, 109, Master.UHD_DV_ATMOS, added = 92.0, watched = 46),
        Film(1184918, "The Wild Robot", 2024, 102, Master.UHD_DV_HDR10_PLUS, added = 81.0, watched = 41),
        Film(974576, "Conclave", 2024, 120, Master.FHD_DTS, added = 79.0, watched = 36),
        Film(558449, "Gladiator II", 2024, 148, Master.UHD_DV_HDR10_PLUS, added = 75.0, watched = 33),
        Film(575265, "Mission: Impossible - The Final Reckoning", 2025, 170, Master.UHD_DV_HDR10_PLUS, added = 40.0, watched = 22),
        Film(1061474, "Superman", 2025, 129, Master.UHD_DV_HDR10_PLUS, added = 31.0, watched = 16),
        Film(911430, "F1", 2025, 156, Master.UHD_DV_HDR10_PLUS, added = 26.0, watched = 11),
        Film(1233413, "Sinners", 2025, 137, Master.UHD_DV_ATMOS, added = 21.0, watched = 8),
        Film(1054867, "One Battle After Another", 2025, 162, Master.UHD_DV_HDR10_PLUS, added = 13.0),
        Film(1062722, "Frankenstein", 2025, 150, Master.UHD_WEB, added = 9.0),
        Film(83533, "Avatar: Fire and Ash", 2025, 197, Master.UHD_DV_HDR10_PLUS, added = 5.5),
        Film(687163, "Project Hail Mary", 2026, 156, Master.UHD_DV_HDR10_PLUS, added = 3.2),
        Film(1368337, "The Odyssey", 2026, 165, Master.UHD_DV_ATMOS, added = 2.1),
        Film(969681, "Spider-Man: Brand New Day", 2026, 140, Master.UHD_WEB_HDR10_PLUS, added = 0.9),
    )

    /** The shows: a folder each, a season folder each, every episode of the seasons held. */
    val SHOWS = listOf(
        Show(95396, "Severance", 2022, seasons = listOf(9, 10), minutes = 52, added = 120.0, seasonAdded = listOf(120.0, 44.0)),
        Show(126308, "Shōgun", 2024, seasons = listOf(10), minutes = 58, added = 110.0),
        Show(100088, "The Last of Us", 2023, seasons = listOf(9, 7), minutes = 55, added = 118.0, seasonAdded = listOf(118.0, 50.0)),
        Show(83867, "Andor", 2022, seasons = listOf(12, 12), minutes = 47, added = 116.0, seasonAdded = listOf(116.0, 62.0)),
        Show(95480, "Slow Horses", 2022, seasons = listOf(6, 6, 6, 6), minutes = 46, added = 114.0),
        Show(225171, "Pluribus", 2025, seasons = listOf(9), minutes = 50, added = 4.4),
    )

    /** In progress: the film home's case resumes, where it stopped and when. */
    val RESUME = Resume(693134, positionMin = 92, hoursAgo = 20)

    /** The shows watched so far: through which season and episode, the last that many days ago. */
    val WATCHED = listOf(
        Watched(95396, season = 2, episode = 4, daysAgo = 1.0),
        Watched(95480, season = 3, episode = 6, daysAgo = 12.0),
        Watched(100088, season = 1, episode = 9, daysAgo = 47.0),
        Watched(83867, season = 2, episode = 12, daysAgo = 58.0),
        Watched(126308, season = 1, episode = 10, daysAgo = 90.0),
    )

    /** The show whose episodes the README shows, on its next episode. */
    const val SHOW_PAGE = 95396

    /** The saga the README shows, by one of its films. */
    const val SAGA_OF = 693134

    /** The film whose page the README shows. */
    const val FILM_PAGE = 83533

    /**
     * Files the scan cannot name alone, added for « To identify » and Identify's shots only: titles TMDB knows several
     * films by, given without a year, and a home video TMDB does not know.
     */
    val TO_IDENTIFY = listOf("Dracula.mkv", "Hamlet.mkv", "Wuthering Heights.mkv", "Holidays in Brittany 2025.mkv")

    /** The update the update sheet offers: the version installed, the one found, what it brings. */
    const val INSTALLED = "0.4.0"
    const val OFFERED = "0.4.1"
    const val OFFERED_NOTES = "- Forced subtitles are chosen again when a film has its own.\n" +
        "- A scan stopped by standby resumes where it was.\n" +
        "- A saga lists its films in their release order."
}

/** How a file was mastered, as its tracks say it ([ReadmeFiles]). */
internal enum class Master {
    /** Ultra HD Blu-ray remux: Dolby Vision over HDR10, Dolby TrueHD Atmos 7.1. */
    UHD_DV_ATMOS,

    /** The same, with HDR10+ beside Dolby Vision. */
    UHD_DV_HDR10_PLUS,

    /** Ultra HD Blu-ray remux in HDR10, DTS-HD Master Audio 5.1. */
    UHD_HDR10_DTS,

    /** A streaming service's Ultra HD: Dolby Vision over HDR10, Dolby Digital Plus Atmos 5.1. */
    UHD_WEB,

    /** The same, with HDR10+ beside Dolby Vision. */
    UHD_WEB_HDR10_PLUS,

    /** Blu-ray remux: Full HD, DTS-HD Master Audio 5.1. */
    FHD_DTS,
}

/**
 * A film: its TMDB [id], its English [title] as TMDB gives it (the folder is named after it, and the scan checks it),
 * [year], length in [minutes], how it was [master]ed, how many days ago it was [added], and how many days ago it was
 * [watched] (null: not yet).
 */
internal class Film(
    val id: Int,
    val title: String,
    val year: Int,
    val minutes: Int,
    val master: Master,
    val added: Double,
    val watched: Int? = null,
) {
    /** Its folder, as Radarr names it: the title without the characters a file name cannot hold, the year and the id. */
    val folder: String get() = "${fileTitle(title)} ($year) {tmdb-$id}"

    val path: String get() = "$folder/${fileTitle(title)} ($year).mkv"
}

/**
 * A show: its TMDB [id], English [title], first [year], how many episodes each season held has, an episode's length
 * in [minutes], how many days ago its first season was [added], and each season's, when they came apart.
 */
internal class Show(
    val id: Int,
    val title: String,
    val year: Int,
    val seasons: List<Int>,
    val minutes: Int,
    val added: Double,
    val seasonAdded: List<Double> = seasons.map { added },
) {
    val folder: String get() = "${fileTitle(title)} ($year) {tmdb-$id}"

    /** Episode [episode] of season [season], as Sonarr names it. */
    fun path(season: Int, episode: Int): String =
        "$folder/Season ${twoDigits(season)}/${fileTitle(title)} - S${twoDigits(season)}E${twoDigits(episode)}.mkv"
}

/** The film [id] stopped at [positionMin] minutes, [hoursAgo] hours ago. */
internal class Resume(val id: Int, val positionMin: Int, val hoursAgo: Int)

/** The show [id] watched through [season] and [episode], the last [daysAgo] days ago, one a day before it. */
internal class Watched(val id: Int, val season: Int, val episode: Int, val daysAgo: Double)

/** [title] as a file's name holds it: no colon nor slash, single spaces. */
internal fun fileTitle(title: String): String = title.replace(Regex("""[:/\\?*"<>|]"""), " ").replace(Regex("""\s+"""), " ").trim()

private fun twoDigits(n: Int): String = n.toString().padStart(2, '0')
