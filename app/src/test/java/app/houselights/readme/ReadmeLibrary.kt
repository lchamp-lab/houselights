package app.houselights.readme

import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.MediaItem
import app.houselights.core.PlaybackOptions
import app.houselights.core.SourceConfig
import app.houselights.core.TraktSession
import app.houselights.player.TrackChooser
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalEditor
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.TmdbClient
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SettingsStore
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.File
import java.util.Locale

/**
 * The README's library, made as a user's is: two folders on a NAS holding [ReadmeTitles]' files, scanned by the app's
 * own scanner against TMDB itself, in English ([network], with [key]), then what was watched. The settings are as
 * installed, the folders the source, a TMDB key of the user's (a placeholder: the real one never leaves [key]).
 * [extra] adds files before the scan. Built in the test's sandbox, on [root]; [close] once done.
 */
internal class ReadmeLibrary(
    root: File,
    network: ReadmeNetwork,
    key: String,
    extra: ReadmeLibrary.() -> Unit = {},
) : Closeable {
    val context: Context = ApplicationProvider.getApplicationContext()

    /** Now, on the clock the app's screens read. */
    val now: Long = System.currentTimeMillis()

    val db: LibraryDatabase = testDatabase()
    private val dao = db.dao()

    private val nas = linkedMapOf<String, FakeRemoteBrowser>()
    private val folderIds = mutableMapOf<Long, String>()

    /** The scanner's clock: the first pass runs months ago, as the library's first scan. */
    private var clock: Long = now
    private val passes = SupervisorJob()
    private val artwork = ArtworkCache(root.resolve("artwork"))
    private val tmdb = TmdbClient(key, transport = network.tmdb, locale = Locale.US)

    val scanner = LibraryScanner(
        dao = dao,
        tmdb = { tmdb },
        scope = CoroutineScope(passes),
        artwork = artwork,
        browsers = { source -> nas.getValue(folderIds.getValue(source.id)) },
        closeIdle = {},
        clock = { clock },
        interactiveTmdb = { tmdb },
    )
    val library = LocalLibrary(context, dao, scanner, artwork) {}
    private val editor = LocalEditor(dao, scanner) { tmdb }

    /** Work that outlives a screen, as the app's scope: an identification, a change of settings. */
    val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val files = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings: SettingsStore = runBlocking { testSettings(root.resolve("settings").apply { mkdirs() }, files) }
    val provider = LocalProvider(context, dao, editor) { settings.state.value.collectionMinFilms }

    /** The audio and subtitles Play asks for, as on a Zidoo: the file's tracks, Settings' choice. */
    val tracks = TrackChooser(
        preferences = { settings.state.value.tracks },
        locale = { Locale.US },
        load = { null },
        save = { _, _ -> },
        forget = {},
        deviceLanguage = { "en" },
    )

    init {
        runBlocking {
            settings.setSource(SourceConfig.Local)
            settings.setTmdbApiKey(SETTINGS_KEY)
            // A Trakt account connected, as home's state door says: placeholders, never sent anywhere.
            settings.setTrakt(TraktSession(PLACEHOLDER, PLACEHOLDER, expiresAt = now / MS_PER_S + TRAKT_LEFT_S, username = "houselights"))
            settings.state.first { it.source == SourceConfig.Local && it.tmdbApiKey != null && it.trakt != null }
            stock()
        }
        extra()
        runBlocking {
            // The library's first scan, months ago, then the last one, minutes ago.
            scan(now - FIRST_SCAN_DAYS * DAY)
            scan(now - LAST_SCAN_MIN * MINUTE)
        }
        checkNamed()
        stamp()
        watched()
        runBlocking { choosePosters() }
    }

    /** What Play would ask of the player for [item]: the file's tracks, as the app's container reads them on a Zidoo. */
    suspend fun tracksOf(item: MediaItem) = tracks.table(provider.playedTracks(item, PlaybackOptions(emptyList(), forceHttp = false)))

    /** A video at [path] in the films' folder, its tracks [bytes] and its [size]. */
    fun putFilm(path: String, bytes: ByteArray, size: Long) = nas.getValue(FILMS).put(path, size = size, bytes = bytes)

    /** The file at [path] in the films' folder joined the library [days] ago. */
    fun addedAgo(path: String, days: Double) = sql("UPDATE items SET addedAt = ? WHERE filePath LIKE ?", daysAgo(days), "%$path")

    /** The id the screens know the film or show of TMDB id [tmdbId] by. */
    fun idOf(tmdbId: Int): String =
        one("SELECT id FROM items WHERE kind IN ('MOVIE', 'SERIES') AND tmdbId = ? ORDER BY id LIMIT 1", tmdbId) { getLong(0) }.toString()

    /** The id of the saga the film of TMDB id [tmdbId] belongs to. */
    fun sagaOf(tmdbId: Int): String =
        "c" + one("SELECT collectionId FROM items WHERE kind = 'MOVIE' AND tmdbId = ? AND collectionId IS NOT NULL", tmdbId) { getLong(0) }

    /** Every film's and show's id, for the pages the animation may open. */
    fun titleIds(): List<String> = query("SELECT id FROM items WHERE kind IN ('MOVIE', 'SERIES') ORDER BY id") { getLong(0).toString() }

    /**
     * The films' and shows' titles TMDB gave, with their rating (home's doors cut their figures in the best rated
     * posters) and what TMDB has of their pictures, a poster chosen said so: the run's report.
     */
    fun report(): String = query(
        "SELECT kind, tmdbId, title, year, posterPath IS NOT NULL, backdropPath IS NOT NULL, logoPath IS NOT NULL, certification, " +
            "rating, userPosterPath IS NOT NULL FROM items WHERE kind IN ('MOVIE', 'SERIES') ORDER BY kind, sortTitle",
    ) {
        val poster = if (getInt(PICKED) == 1) "chosen poster" else "poster"
        val pictures = listOf(poster to getInt(4), "backdrop" to getInt(5), "logo" to getInt(6))
            .joinToString(" ") { (name, has) -> if (has == 1) name else "no $name" }
        val rating = if (isNull(RATING)) "no rating" else "rated %.1f".format(Locale.ROOT, getDouble(RATING))
        "${getString(0)} ${getInt(1)}: ${getString(2)} (${getInt(3)}), ${getString(7) ?: "unrated"}, $rating, $pictures"
    }.joinToString("\n")

    override fun close() {
        passes.cancel()
        work.cancel()
        files.cancel()
        db.close()
    }

    /** The folders and their files: each film in its folder, each show by season. */
    private suspend fun stock() {
        folder(FILMS, SourceKind.MOVIES)
        folder(SHOWS, SourceKind.SHOWS)
        for (title in ReadmeTitles.FILMS) {
            putFilm(title.path, ReadmeFiles.film(title.master, title.minutes), ReadmeFiles.size(title.master, title.minutes))
        }
        for (show in ReadmeTitles.SHOWS) {
            show.seasons.forEachIndexed { s, episodes ->
                (1..episodes).forEach { e ->
                    val size = ReadmeFiles.size(Master.UHD_WEB, show.minutes)
                    nas.getValue(SHOWS).put(show.path(s + 1, e), size = size, bytes = ReadmeFiles.episode(show.minutes))
                }
            }
        }
    }

    private suspend fun folder(name: String, kind: SourceKind) {
        val id = dao.insertSource(nfsFolder(name, kind, host = ReadmeTitles.NAS, share = "volume1/media"))
        folderIds[id] = name
        nas[name] = FakeRemoteBrowser()
    }

    /** One whole pass at [at], as « Scan now » asks it, awaited. */
    private suspend fun scan(at: Long) {
        clock = at
        scanner.scanAll()
        passes.children.toList().forEach { it.join() }
    }

    /** Each title is named by its id: one TMDB gave none for fails the shots, rather than showing it unnamed. */
    private fun checkNamed() {
        val named = query("SELECT kind, tmdbId FROM items WHERE kind IN ('MOVIE', 'SERIES') AND tmdbId IS NOT NULL") {
            getString(0) to getInt(1)
        }.toSet()
        val missing = ReadmeTitles.FILMS.filter { ("MOVIE" to it.id) !in named }.map { "${it.title} (${it.id})" } +
            ReadmeTitles.SHOWS.filter { ("SERIES" to it.id) !in named }.map { "${it.title} (${it.id})" }
        check(missing.isEmpty()) { "TMDB did not name ${missing.joinToString()}; the library holds:\n${report()}" }
    }

    /** When each title joined the library, as the fixture says: what the latest rows and the new dots read. */
    private fun stamp() {
        for (film in ReadmeTitles.FILMS) {
            sql("UPDATE items SET addedAt = ? WHERE kind = 'MOVIE' AND tmdbId = ?", daysAgo(film.added), film.id)
        }
        for (show in ReadmeTitles.SHOWS) {
            show.seasonAdded.forEachIndexed { s, days ->
                sql(
                    "UPDATE items SET addedAt = ? WHERE kind IN ('SEASON', 'EPISODE') AND seasonNumber = ? AND seriesId = $SERIES",
                    daysAgo(days), s + 1, show.id,
                )
            }
            sql("UPDATE items SET addedAt = ? WHERE kind = 'SERIES' AND tmdbId = ?", daysAgo(show.seasonAdded.min()), show.id)
        }
    }

    /**
     * The posters chosen in TMDB's own place ([Film.poster], [Show.poster]), kept as Edit artwork keeps a viewer's choice;
     * one TMDB no longer lists fails the shots, with those it lists.
     */
    private suspend fun choosePosters() {
        val chosen = ReadmeTitles.FILMS.mapNotNull { film -> film.poster?.let { Triple(film.id, false, it) } } +
            ReadmeTitles.SHOWS.mapNotNull { show -> show.poster?.let { Triple(show.id, true, it) } }
        for ((tmdbId, show, path) in chosen) {
            val listed = tmdb.images(tmdbId, show).posters.map { it.filePath }
            check(path in listed) { "TMDB lists no poster $path for ${if (show) "show" else "film"} $tmdbId, but ${listed.take(LISTED)}" }
            val kind = if (show) "SERIES" else "MOVIE"
            val id = one("SELECT id FROM items WHERE kind = ? AND tmdbId = ?", kind, tmdbId) { getLong(0) }
            dao.setUserPoster(id, path)
        }
    }

    /** What was watched: films seen, the one in progress, shows through an episode, one a day before the last. */
    private fun watched() {
        for (film in ReadmeTitles.FILMS) {
            val days = film.watched ?: continue
            sql(
                "UPDATE items SET played = 1, positionMs = 0, lastPlayedAt = ? WHERE kind = 'MOVIE' AND tmdbId = ?",
                daysAgo(days.toDouble()), film.id,
            )
        }
        val resume = ReadmeTitles.RESUME
        val minutes = ReadmeTitles.FILMS.first { it.id == resume.id }.minutes
        sql(
            "UPDATE items SET played = 0, positionMs = ?, durationMs = ?, lastPlayedAt = ? WHERE kind = 'MOVIE' AND tmdbId = ?",
            resume.positionMin * MINUTE, minutes * MINUTE, now - resume.hoursAgo * HOUR, resume.id,
        )
        for (show in ReadmeTitles.WATCHED) {
            val seen = query(
                "SELECT id FROM items WHERE kind = 'EPISODE' AND seriesId = $SERIES " +
                    "AND (seasonNumber < ? OR (seasonNumber = ? AND episodeNumber <= ?)) ORDER BY seasonNumber, episodeNumber",
                show.id, show.season, show.season, show.episode,
            ) { getLong(0) }
            seen.forEachIndexed { i, id ->
                sql("UPDATE items SET played = 1, lastPlayedAt = ? WHERE id = ?", daysAgo(show.daysAgo + (seen.size - 1 - i)), id)
            }
        }
    }

    private fun daysAgo(days: Double): Long = now - (days * DAY).toLong()

    private fun sql(sql: String, vararg args: Any?) = db.openHelper.writableDatabase.execSQL(sql, args)

    private fun <T> query(sql: String, vararg args: Any?, read: Cursor.() -> T): List<T> =
        db.openHelper.readableDatabase.query(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.read()) } }

    private fun <T> one(sql: String, vararg args: Any?, read: Cursor.() -> T): T =
        checkNotNull(query(sql, *args, read = read).firstOrNull()) { "Nothing for $sql (${args.joinToString()})" }

    companion object {
        const val FILMS = "Movies"
        const val SHOWS = "Shows"

        /** The key the settings hold, as typed in them: the real one is handed to TMDB's client alone. */
        const val SETTINGS_KEY = "0123456789abcdef0123456789abcdef"

        /** The Trakt account's tokens, which nothing reads: no sync runs. */
        private const val PLACEHOLDER = "placeholder"
        private const val MS_PER_S = 1000L
        private const val TRAKT_LEFT_S = 60L * 24 * 60 * 60

        /** The report's columns: the rating, and whether a poster was chosen. */
        private const val RATING = 8
        private const val PICKED = 9

        /** The posters a refused choice says TMDB lists. */
        private const val LISTED = 12

        private const val FIRST_SCAN_DAYS = 140
        private const val LAST_SCAN_MIN = 40
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        /** The show of the TMDB id bound next. */
        private const val SERIES = "(SELECT id FROM items WHERE kind = 'SERIES' AND tmdbId = ?)"
    }
}
