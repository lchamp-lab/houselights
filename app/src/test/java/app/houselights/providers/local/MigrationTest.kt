package app.houselights.providers.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Job
import app.houselights.core.MediaInfo
import app.houselights.core.NfoParser
import app.houselights.core.Secrets
import app.houselights.core.SubtitleChoice
import app.houselights.core.TrackChoice
import app.houselights.testing.secretsOf
import app.houselights.testing.testSecrets
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.ProviderException

/** Libraries left by earlier versions, opened by this one: what each migration keeps, and what it changes. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val secrets = testSecrets()
    private val opened = mutableListOf<Pair<String, LibraryDatabase>>()

    @After
    fun close() = opened.forEach { (name, db) ->
        db.close()
        context.deleteDatabase(name)
    }

    /** A library created at schema [from] and filled by [fill], opened with every migration; closed after the test. */
    private fun migrated(from: Int, keys: Secrets = secrets, fill: SupportSQLiteDatabase.() -> Unit): LibraryDatabase {
        val name = "migration-from-$from-test.db"
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, from, fill)
        return Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(keys))
            .addMigrations(*LibraryDatabase.migrations(keys))
            .build()
            .also { opened += name to it }
    }

    /** Folder 1, "Films", as schemas 8 and later hold it. */
    private fun SupportSQLiteDatabase.filmsFolder() = execSQL(
        "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt, firstScanAt) " +
            "VALUES (1, 'Films', 'MOVIES', 'NFS', 'nas', 'media', 'films', '', '', '', 1000, 1000)"
    )

    /** Schemas 1 to 5 predate Room's schema export and were rebuilt (app/schemas); Room checks them on open. */
    @Test
    fun everyVersionBeforeSchemasWereKeptReachesTheLatest() = runTest {
        for (version in 1..5) {
            val db = migrated(from = version) {
                val protocol = if (version >= 2) listOf("protocol" to "'NFS'") else emptyList()
                val columns = listOf(
                    "id" to "1", "name" to "'Films'", "kind" to "'MOVIES'", "host" to "'nas'", "share" to "'media'", "path" to "'films'",
                    "username" to "'zidoo'", "password" to "'s3cret'", "domain" to "''", "lastScanAt" to "1000",
                ) + protocol
                execSQL("INSERT INTO sources (${columns.joinToString { it.first }}) VALUES (${columns.joinToString { it.second }})")
                execSQL(
                    "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, filePath, fileSize, " +
                        "badges, addedAt, matchedAt, positionMs, played, favorite) " +
                        "VALUES (1, 'MOVIE', 1, 'Dune', 'dune', 0, 'Science-fiction', '', 'Dune.mkv', 42, '', 0, 500, 600000, 0, 1)"
                )
            }
            val source = db.dao().sources().single()
            assertEquals("from $version", if (version >= 2) Protocol.NFS else Protocol.SMB, source.protocol)
            assertEquals("from $version", "s3cret", source.password.value)
            assertEquals("from $version", 1000L, db.dao().firstScans().single().firstScanAt)
            val dune = checkNotNull(db.dao().item(1))
            assertEquals("from $version", listOf("Science-fiction"), dune.genres)
            assertEquals("from $version", "Dune.mkv" to 42L, dune.filePath to dune.fileSize)
            assertEquals("from $version", 600_000L to true, dune.positionMs to dune.favorite)
            // Version 6 matches films again, so they learn their collection.
            assertEquals("from $version", null, dune.matchedAt)
            assertNull("from $version: never found missing", dune.missingSince)
            assertEquals("from $version: never read", 0 to null, dune.probeVersion to dune.fileDurationMs)
        }
    }

    @Test
    fun version7SealsPasswords() = runTest {
        val db = migrated(from = 6) {
            execSQL("$FOLDER_UP_TO_7 VALUES (1, 'Films', 'MOVIES', 'SMB', 'nas', 'media', 'films', 'zidoo', 's3cret', '', 0)")
            execSQL("$FOLDER_UP_TO_7 VALUES (2, 'Séries', 'SHOWS', 'NFS', 'nas', 'volume1/media', 'series', '', '', '', 0)")
        }
        val raw = db.openHelper.readableDatabase.query("SELECT password FROM sources ORDER BY id").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        assertTrue(secrets.isSealed(raw[0]))
        assertEquals("", raw[1])
        assertEquals(listOf("s3cret", ""), db.dao().sources().sortedBy { it.id }.map { it.password.value })
    }

    /** A Keystore that cannot seal leaves the password plain: the library still opens and the folder signs in. */
    @Test
    fun version7KeepsAPasswordTheKeystoreCannotSeal() = runTest {
        val broken = secretsOf { throw ProviderException("Keystore operation failed") }
        val db = migrated(from = 6, keys = broken) {
            execSQL("$FOLDER_UP_TO_7 VALUES (1, 'Films', 'MOVIES', 'SMB', 'nas', 'media', 'films', 'zidoo', 's3cret', '', 0)")
        }
        assertEquals(listOf("s3cret"), db.dao().sources().map { it.password.value })
    }

    @Test
    fun version8KeepsWhatLibrariesHoldFromBeingNew() = runTest {
        val db = migrated(from = 7) {
            execSQL("$FOLDER_UP_TO_7 VALUES (1, 'Films', 'MOVIES', 'NFS', 'nas', 'media', 'films', '', '', '', 1000)")
            execSQL("$FOLDER_UP_TO_7 VALUES (2, 'Séries', 'SHOWS', 'NFS', 'nas', 'media', 'series', '', '', '', 0)")
        }
        assertEquals(listOf(1L to 1000L, 2L to 0L), db.dao().firstScans().sortedBy { it.id }.map { it.id to it.firstScanAt })
    }

    @Test
    fun version10KeepsEveryRowAsItWas() = runTest {
        val db = migrated(from = 9) {
            execSQL(
                "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt, firstScanAt) " +
                    "VALUES (1, 'Séries', 'SHOWS', 'NFS', 'nas', 'media', 'series', '', '', '', 1000, 1000)"
            )
            execSQL(
                "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, seasonNumber, episodeNumber, " +
                    "filePath, fileSize, badges, nfoPath, addedAt, positionMs, played, favorite) " +
                    "VALUES (1, 'EPISODE', 1, 'Pilot', 'pilot', 0, '', '', 1, 1, 'Show/S01E01.mkv', 42, '', 'Show/S01E01.nfo', 0, 0, 1, 0)"
            )
        }
        val pilot = checkNotNull(db.dao().item(1))
        assertEquals(null, pilot.lastEpisodeNumber)
        assertEquals(emptyList<String>(), pilot.parts)
        assertEquals(null, pilot.sidecarKey)
        assertEquals("Show/S01E01.nfo", pilot.nfoPath)
        assertTrue(pilot.played)
    }

    /** Version 11 copies each file's probe version and length into columns; unreadable JSON means a new probe. */
    @Test
    fun version11ReadsEachFilesProbeAndLengthIntoTheirColumns() = runTest {
        val read = """{"container":"MKV","durationMs":9300000,"video":{"codec":"HEVC","width":3840},"probedWith":3}"""
        val fromNfo = """{"container":"AVI","video":{"codec":"MPEG-4"}}"""
        val db = migrated(from = 10) {
            filmsFolder()
            for ((id, mediaInfo) in listOf(1 to read, 2 to fromNfo, 3 to "not json", 4 to null)) {
                execSQL(
                    "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, filePath, parts, fileSize, " +
                        "badges, mediaInfo, userTmdbId, userPosterPath, addedAt, positionMs, durationMs, played, favorite, lastPlayedAt) " +
                        "VALUES (?, 'MOVIE', 1, 'Film', 'film', 0, '', '', ?, '', 42, '4K', ?, 438631, 'file:/poster.jpg', 0, 600000, " +
                        "9000000, 0, 1, 5)",
                    arrayOf<Any?>(id, "Film $id.mkv", mediaInfo),
                )
            }
        }
        val films = (1L..4L).map { checkNotNull(db.dao().item(it)) }
        assertEquals(listOf(3, 0, 0, 0), films.map { it.probeVersion })
        assertEquals(listOf(9_300_000L, null, null, null), films.map { it.fileDurationMs })
        assertEquals(listOf(read, fromNfo, "not json", null), films.map { it.mediaInfo })
        for (film in films) {
            assertEquals(438631 to "file:/poster.jpg", film.userTmdbId to film.userPosterPath)
            assertEquals(600_000L to 9_000_000L, film.positionMs to film.durationMs)
            assertEquals(true to 5L, film.favorite to film.lastPlayedAt)
            assertEquals(listOf("4K"), film.badges)
        }
    }

    /** Version 10 folds version 9's accented sort keys and drops a glued l': "la land" keeps its "la". */
    @Test
    fun version10FoldsSortKeysAndTakesOffAGluedLAndNothingMore() = runTest {
        val keys = listOf("ça", "à couteaux tirés", "été 85", "œdipe roi", "la land", "l'arnaque", "l’exorciste", "zodiac")
        val db = migrated(from = 9) {
            filmsFolder()
            keys.forEachIndexed { i, key ->
                execSQL(
                    "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, filePath, fileSize, " +
                        "badges, addedAt, positionMs, played, favorite) " +
                        "VALUES (${i + 1}, 'MOVIE', 1, ?, ?, 0, '', '', ?, 42, '', 0, 0, 0, 0)",
                    arrayOf<Any>(key, key, "$key.mkv"),
                )
            }
            execSQL("INSERT INTO collections (id, matchKey, name, sortTitle) VALUES (1, 'name:etrange', 'Étrange saga', 'étrange saga')")
            execSQL("INSERT INTO collections (id, matchKey, name, sortTitle) VALUES (2, 'tmdb:1', 'L''Âge de glace', 'l''âge de glace')")
        }
        assertEquals(
            listOf("ca", "a couteaux tires", "ete 85", "oedipe roi", "la land", "arnaque", "exorciste", "zodiac"),
            keys.indices.map { checkNotNull(db.dao().item(it + 1L)).sortTitle },
        )
        assertEquals("ça", db.dao().item(1)?.title)
        assertEquals("etrange saga", db.dao().collection(1)?.sortTitle)
        assertEquals("age de glace", db.dao().collection(2)?.sortTitle)
    }

    /** Sagas named in Japanese shared the key "name:": version 10 rematches their films, and only theirs. */
    @Test
    fun version10MatchesAgainTheFilmsOfSagasWhoseNamesLeftNoWord() = runTest {
        val db = migrated(from = 9) {
            filmsFolder()
            execSQL("INSERT INTO collections (id, matchKey, name, sortTitle) VALUES (1, 'name:', '攻殻機動隊', '攻殻機動隊')")
            execSQL("INSERT INTO collections (id, matchKey, name, sortTitle) VALUES (2, 'tmdb:531241', 'Top Gun', 'top gun')")
            for ((id, collection) in listOf(1 to 1, 2 to 1, 3 to 2)) {
                execSQL(
                    "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, filePath, fileSize, " +
                        "badges, collectionId, addedAt, matchedAt, positionMs, played, favorite) " +
                        "VALUES ($id, 'MOVIE', 1, 'Film $id', 'film $id', 0, '', '', 'Film $id.mkv', 42, '', $collection, 0, 500, 0, 0, 0)"
                )
            }
        }
        assertEquals(listOf(null, null, 500L), (1L..3L).map { checkNotNull(db.dao().item(it)).matchedAt })
        assertEquals(listOf(null, null, 2L), (1L..3L).map { checkNotNull(db.dao().item(it)).collectionId })
        assertNull(db.dao().collection(1))
        assertEquals("tmdb:531241", db.dao().collection(2)?.matchKey)
    }

    /**
     * Version 12 adds credits, search words, editions and copies' titles, and refetches known sagas' films,
     * keeping what the user did with each title.
     */
    @Test
    fun version12KeepsTheCastAsCreditsAndWhatTheUserDidAsItWas() = runTest {
        val db = migrated(from = 11) {
            filmsFolder()
            val collections = "INSERT INTO collections (id, matchKey, tmdbId, name, sortTitle, partCount, fetchedAt)"
            execSQL("$collections VALUES (1, 'tmdb:10', 10, 'Blade Runner - Saga', 'blade runner', 2, 99)")
            execSQL("$collections VALUES (2, 'name:mes films', NULL, 'Mes films', 'mes films', NULL, 99)")
            val films = listOf(
                Triple(1, "Blade Runner (1982).mkv", "Ridley Scott"),
                Triple(2, "Blade Runner (1982) {edition-Final Cut}.mkv", "Ridley Scott"),
                Triple(3, "Amélie (2001) Version Longue.mkv", "Jean-Pierre Jeunet"),
            )
            for ((id, path, director) in films) {
                execSQL(
                    "INSERT INTO items (id, kind, sourceId, tmdbId, title, sortTitle, continuing, genres, directors, filePath, " +
                        "parts, fileSize, badges, probeVersion, userTmdbId, userPosterPath, collectionId, addedAt, matchedAt, " +
                        "positionMs, durationMs, played, favorite, lastPlayedAt) VALUES (?, 'MOVIE', 1, ?, ?, ?, 0, '', ?, ?, " +
                        "'', 42, '', 3, ?, 'file:/p.jpg', ?, 0, 500, 600000, 9000000, ?, 1, 5)",
                    arrayOf<Any?>(
                        id, if (id < 3) 78 else 194, if (id < 3) "Blade Runner" else "Le Fabuleux Destin d'Amélie Poulain",
                        if (id < 3) "blade runner" else "fabuleux destin d'amelie poulain", director, path, if (id == 2) 78 else null,
                        if (id < 3) 1 else null, if (id == 2) 1 else 0,
                    ),
                )
            }
            val cast = "INSERT INTO `cast` (itemId, ord, name, character, profilePath)"
            execSQL("$cast VALUES (1, 0, 'Harrison Ford', 'Rick Deckard', '/ford.jpg')")
            execSQL("$cast VALUES (3, 0, 'Audrey Tautou', 'Amélie Poulain', NULL)")
        }
        checkVersion12(db)
    }

    @Test
    fun version13AddsWhatWaitsForTraktAndKeepsTheLibraryAsItWas() = runTest {
        val db = migrated(from = 12) {
            filmsFolder()
            execSQL(
                "INSERT INTO items (id, kind, sourceId, tmdbId, title, sortTitle, continuing, genres, directors, filePath, parts, " +
                    "badges, probeVersion, addedAt, positionMs, played, favorite, lastPlayedAt) " +
                    "VALUES (1, 'MOVIE', 1, 949, 'Heat', 'heat', 0, '', '', 'Heat.mkv', '', '', 0, 0, 600000, 1, 0, 5)"
            )
        }
        db.dao().item(1)!!.let { assertEquals(Triple(true, 600_000L, 5L), Triple(it.played, it.positionMs, it.lastPlayedAt)) }
        val trakt = db.trakt()
        assertNull(trakt.state())
        trakt.queue(listOf(TraktPendingEntity("movie:949", TraktAction.WATCHED, at = 5)))
        assertEquals(listOf("movie:949"), trakt.pending().map { it.title })
        assertEquals(listOf(949), trakt.rows().map { it.tmdbId })
    }

    @Test
    fun version14KeepsWhatWaitsForTraktNeverTriedNorSent() = runTest {
        val db = migrated(from = 13) {
            execSQL("INSERT INTO trakt_pending (title, action, at, progress, seq) VALUES ('movie:949', 'PROGRESS', 5, 42.5, 1)")
        }
        val waiting = db.trakt().pending().single()
        assertEquals(TraktPendingEntity("movie:949", TraktAction.PROGRESS, at = 5, progress = 42.5f, seq = 1), waiting)
        assertEquals(0 to null, waiting.attempts to waiting.sentAt)
    }

    @Test
    fun version15KeepsTheFoldersWithNothingToReadAgain() = runTest {
        val db = migrated(from = 14) { filmsFolder() }
        val films = checkNotNull(db.dao().source(1))
        assertEquals("Films" to 1000L, films.name to films.lastScanAt)
        assertNull(films.refreshIn)
    }

    @Test
    fun version16KeepsEachTitlesChoiceOfTracks() = runTest {
        val db = migrated(from = 15) { filmsFolder() }
        assertNull(db.trackChoices().choice("local:1"))
        db.trackChoices().save(TrackChoiceEntity("local:1", "ko", SubtitleChoice.Off.stored, at = 5))
        assertEquals(TrackChoice("ko", SubtitleChoice.Off), db.trackChoices().choice("local:1")?.choice())
    }

    /** Version 17 lets an NFS folder present another user; every folder kept presents itself as root, as before. */
    @Test
    fun version17KeepsEveryFolderPresentingItselfAsRoot() = runTest {
        // From the previous version, and from 0.2.0's.
        for (from in listOf(16, 15)) {
            val films = checkNotNull(migrated(from) { filmsFolder() }.dao().source(1))
            assertEquals("from $from", "Films" to 1000L, films.name to films.lastScanAt)
            assertEquals("from $from", null to null, films.uid to films.gid)
        }
    }

    /**
     * Version 18 keeps what the user did to titles whose files left, by title: nothing yet, every title's own state as
     * it was, and the table ready for the first file to leave.
     */
    @Test
    fun version18KeepsTheStateOfTitlesWhoseFilesLeft() = runTest {
        // From the previous version, and from 0.2.0's.
        for (from in listOf(17, 15)) {
            val dao = migrated(from) {
                filmsFolder()
                execSQL(
                    "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, filePath, parts, " +
                        "badges, probeVersion, addedAt, positionMs, played, favorite) " +
                        "VALUES (1, 'MOVIE', 1, 'Dune', 'dune', 0, '', '', 'Dune (2021).mkv', '', '', 0, 5, 600000, 0, 1)"
                )
            }.dao()
            val dune = checkNotNull(dao.item(1))
            assertEquals("from $from", Triple(600_000L, true, 5L), Triple(dune.positionMs, dune.favorite, dune.addedAt))
            assertEquals("from $from", emptyList<KeptStateEntity>(), dao.keptNamed(ItemKind.MOVIE, "dune|2021"))

            val row = dao.keptRows(listOf(1L)).single()
            dao.keep(listOfNotNull(KeptStates.of(row)))
            val kept = dao.keptNamed(ItemKind.MOVIE, "dune|2021")
            assertEquals("from $from", listOf(true to 600_000L), kept.map { it.favorite to it.positionMs })
        }
    }

    /**
     * Version 19 says what TMDB made of each film and show: matched with a TMDB id, its own or its NFO's; not found, so
     * to identify, when TMDB answered without one; neither while TMDB never answered, nor for seasons and episodes.
     * Nothing matched is searched again.
     */
    @Test
    fun version19KeepsTitlesMatchedAndPutsThoseTmdbHadNotToIdentify() = runTest {
        // From the previous version, and from 0.2.0's.
        for (from in listOf(18, 15)) {
            val dao = migrated(from) {
                filmsFolder()
                // A film matched, one TMDB has not, one TMDB never answered for, a show its NFO named, an episode.
                listOf(
                    arrayOf<Any?>(1, "MOVIE", "Dune", "Dune (2021).mkv", 1, 438631, 7, null),
                    arrayOf<Any?>(2, "MOVIE", "Inconnu", "Inconnu.mkv", 0, null, 7, null),
                    arrayOf<Any?>(3, "MOVIE", "Pas encore", "Pas encore.mkv", 0, null, null, null),
                    arrayOf<Any?>(4, "SERIES", "Severance", null, 0, 95396, null, null),
                    arrayOf<Any?>(5, "EPISODE", "Pilote", "Severance/S01E01.mkv", 0, null, 7, 4),
                ).forEach { values ->
                    execSQL(
                        "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, filePath, parts, " +
                            "badges, probeVersion, addedAt, positionMs, played, favorite, tmdbId, matchedAt, seriesId) " +
                            "VALUES (?1, ?2, 1, ?3, ?3, 0, '', '', ?4, '', '', 0, ?1, 0, ?5, 0, ?6, ?7, ?8)",
                        values,
                    )
                }
            }.dao()
            val rows = (1L..5L).map { checkNotNull(dao.item(it)) }
            assertEquals(
                "from $from",
                listOf(MatchState.MATCHED, MatchState.NOT_FOUND, null, MatchState.MATCHED, null),
                rows.map { it.matchState },
            )
            assertEquals("from $from: matched as before", listOf(7L, 7L, null, null, 7L), rows.map { it.matchedAt })
            assertEquals("from $from", listOf(true, false, false, false, false), rows.map { it.played })
            assertEquals("from $from", listOf(2L), dao.toIdentify().map { it.id })
            val hints = rows.map { it.hintKey to it.userNoMatch }.toSet()
            assertEquals("from $from: no name's id yet, none left as it is", setOf(null to false), hints)
        }
    }

    /**
     * Version 20 asks rather than holds by the clock: no folder's titles are kept yet, so a drop asks; a row the versions
     * before held a day is listed again, its next pass judging it by the drop alone, everything else as it was.
     */
    @Test
    fun version20HoldsNothingByTheClockAndKeepsNoAnswerYet() = runTest {
        // From the previous version, and from 0.2.0's.
        for (from in listOf(19, 15)) {
            // Version 19's tables, made by Room, hold no default for the choice to leave a title as it is.
            val (noMatch, notLeft) = if (from >= 19) ", userNoMatch" to ", 0" else "" to ""
            val dao = migrated(from) {
                filmsFolder()
                listOf(arrayOf<Any?>(1, "Dune", "Dune (2021).mkv", null), arrayOf<Any?>(2, "Heat", "Heat (1995).mkv", 5_000)).forEach {
                    execSQL(
                        "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, filePath, parts, " +
                            "badges, probeVersion, addedAt, positionMs, played, favorite, missingSince$noMatch) " +
                            "VALUES (?1, 'MOVIE', 1, ?2, ?2, 0, '', '', ?3, '', '', 0, 5, 0, 0, 1, ?4$notLeft)",
                        it,
                    )
                }
            }.dao()
            val films = checkNotNull(dao.source(1))
            assertEquals("from $from", false, films.heldKept)
            assertEquals("from $from", "Films" to 1000L, films.name to films.lastScanAt)
            val rows = listOf(1L, 2L).map { checkNotNull(dao.item(it)) }
            assertEquals("from $from", listOf(null, null), rows.map { it.missingSince })
            assertEquals("from $from", listOf(true, true), rows.map { it.favorite })
            assertEquals("from $from", emptyList<Long>(), dao.heldIn(1))
        }
    }

    /**
     * Version 21 keeps every episode where it was and reads again those that may be numbered from their show's start
     * (E100 and more), and one episode of a show whose tvshow.nfo names an order, so it is read and follows it; its new
     * columns and tables start empty and take what a scan writes, what was kept of a title not left as it is, and a
     * title left as it is kept so. From 20, and from 0.2.0's version 15.
     */
    @Test
    fun version21ReadsAgainWhatMayBeNumberedAnotherWayAndKeepsTheRest() = runTest {
        for (from in listOf(20, 15)) {
            val dao = migrated(from) {
                showsFolder(from)
                val dvd = "<tvshow><title>Firefly</title><displayorder>dvd</displayorder></tvshow>"
                show(1, "Firefly", nfo = dvd, from = from)
                show(2, "One Piece", nfo = "<tvshow><title>One Piece</title></tvshow>", from = from)
                episode(11, show = 1, number = 1, from = from)
                episode(12, show = 1, number = 2, from = from)
                episode(21, show = 2, number = 1071, from = from)
                episode(22, show = 2, number = 99, from = from)
                // What was kept of a show whose files left, from version 18 on.
                if (from >= 18) {
                    execSQL(
                        "INSERT INTO kept_states (title, kind, nameKey, sourceId, played, positionMs, favorite, addedAt) " +
                            "VALUES ('show:name:1:kaamelott', 'SERIES', 'kaamelott', 1, 0, 0, 1, 5)"
                    )
                }
            }.dao()
            val matched = listOf(1L, 11L, 12L, 2L, 21L, 22L).map { checkNotNull(dao.item(it)).matchedAt != null }
            assertEquals("from $from: the show naming an order and its first episode", listOf(false, false, true), matched.take(3))
            assertEquals("from $from: E1071 read again, E99 not", listOf(true, false, true), matched.drop(3))
            val episode = checkNotNull(dao.item(21))
            assertEquals("from $from", Triple(1, 1071, true), Triple(episode.seasonNumber, episode.episodeNumber, episode.played))
            assertEquals("from $from", listOf(null, null, null), listOf(episode.absoluteNumber, episode.tmdbEpisode, episode.tmdbSeason))
            val show = checkNotNull(dao.item(1))
            assertEquals("from $from", listOf(null, null), listOf(show.episodeOrder, show.userEpisodeOrder))
            assertEquals("from $from", emptyList<EpisodeOrderEntity>(), dao.episodeOrders(FIREFLY))
            dao.saveEpisodeOrders(listOf(EpisodeOrderEntity(FIREFLY, EpisodeOrderEntity.AIRED, 0, "", 14, places = "[]")))
            assertEquals("from $from", listOf(EpisodeOrderEntity.AIRED), dao.episodeOrders(FIREFLY).map { it.orderId })
            dao.replaceIgnored(1, listOf(IgnoredFileEntity(1, "S01E01.mkv", IgnoredReason.NO_SHOW)))
            assertEquals("from $from", listOf("S01E01.mkv"), dao.ignoredOf(1).map { it.filePath })
            val before = dao.keptNamed(ItemKind.SERIES, "kaamelott").map { it.favorite to it.userNoMatch }
            assertEquals("from $from: kept as it was, not left as it is", if (from >= 18) listOf(true to false) else emptyList(), before)
            dao.setUserNoMatch(2, true)
            dao.keep(listOfNotNull(KeptStates.of(dao.keptRows(listOf(2L)).single())))
            assertEquals("from $from: left as it is", true, dao.keptState(KeptKeys.show(ONE_PIECE))?.userNoMatch)
        }
    }

    /** Folder 1, "Séries", as schema [from] holds it, from 8; version 20's tables, made by Room, hold no default for heldKept. */
    private fun SupportSQLiteDatabase.showsFolder(from: Int) {
        val (kept, notKept) = if (from >= 20) ", heldKept" to ", 0" else "" to ""
        execSQL(
            "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt, firstScanAt$kept) " +
                "VALUES (1, 'Séries', 'SHOWS', 'NFS', 'nas', 'media', 'series', '', '', '', 1000, 1000$notKept)"
        )
    }

    /** Show [id], matched, its season [id] * 100 under it; [from] the schema it is written in. */
    private fun SupportSQLiteDatabase.show(id: Long, title: String, nfo: String, from: Int) {
        val (noMatch, notLeft) = if (from >= 19) ", userNoMatch" to ", 0" else "" to ""
        execSQL(
            "INSERT INTO items (id, kind, sourceId, matchKey, tmdbId, title, sortTitle, continuing, genres, directors, parts, " +
                "badges, probeVersion, nfo, addedAt, matchedAt, positionMs, played, favorite$noMatch) " +
                "VALUES (?1, 'SERIES', 1, ?2, ?3, ?2, ?2, 0, '', '', '', '', 0, ?4, 5, 6, 0, 0, 0$notLeft)",
            arrayOf<Any?>(id, title, if (id == 1L) FIREFLY else ONE_PIECE, nfo),
        )
        execSQL(
            "INSERT INTO items (id, kind, sourceId, parentId, seriesId, title, sortTitle, continuing, genres, directors, parts, " +
                "badges, probeVersion, seasonNumber, addedAt, matchedAt, positionMs, played, favorite$noMatch) " +
                "VALUES (?1, 'SEASON', 1, ?2, ?2, '', '001', 0, '', '', '', '', 0, 1, 5, 6, 0, 0, 0$notLeft)",
            arrayOf<Any?>(id * 100, id),
        )
    }

    /** Episode [id] of show [show], S01E[number], watched and matched. */
    private fun SupportSQLiteDatabase.episode(id: Long, show: Long, number: Int, from: Int) {
        val (noMatch, notLeft) = if (from >= 19) ", userNoMatch" to ", 0" else "" to ""
        execSQL(
            "INSERT INTO items (id, kind, sourceId, parentId, seriesId, title, sortTitle, continuing, genres, directors, filePath, " +
                "parts, badges, probeVersion, seasonNumber, episodeNumber, addedAt, matchedAt, positionMs, played, favorite$noMatch) " +
                "VALUES (?1, 'EPISODE', 1, ?2, ?3, 'Episode', 'e', 0, '', '', ?4, '', '', 0, 1, ?5, 5, 6, 0, 1, 0$notLeft)",
            arrayOf<Any?>(id, show * 100, show, "Show $show/S01E$number.mkv", number),
        )
    }

    /**
     * Version 16 marks the tracks the probe read in the file's order, so no file is read again for it. Never an NFO's:
     * an MKV the probe found no picture in takes its <streamdetails>, picture and tracks in the NFO's order.
     */
    @Test
    fun version16MarksTheTracksTheProbeReadInTheFilesOrder() = runTest {
        val probed = (MkvProbe.probe(Matroska.sample()) as MkvProbe.Result.Found).info.copy(sizeBytes = 42, probedWith = 4)
        val streams = "<fileinfo><streamdetails><video><codec>hevc</codec><width>3840</width><height>2160</height></video>" +
            "<audio><codec>ac3</codec><language>fre</language></audio><audio><codec>truehd</codec><language>eng</language></audio>" +
            "</streamdetails></fileinfo>"
        val nfo = checkNotNull(NfoParser.parse("<movie><title>Heat</title>$streams</movie>")?.streams)
        val fromNfo = NfoStreamDetails.toMediaInfo(nfo, MediaInfo(container = "MKV", sizeBytes = 42, probedWith = 4))
        assertEquals("an MKV with a picture", "MKV" to "HEVC", fromNfo.container to fromNfo.video?.codec)
        val stored = listOf(
            probed, fromNfo, MediaInfo(container = "MP4", sizeBytes = 42, probedWith = 4),
            // A read that failed midway: read again at the next scan.
            probed.copy(probedWith = 0),
        ).map(MediaProbe::encode)
        val db = migrated(from = 15) {
            filmsFolder()
            stored.forEachIndexed { i, mediaInfo ->
                execSQL(
                    "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, filePath, parts, " +
                        "badges, mediaInfo, probeVersion, addedAt, positionMs, played, favorite) " +
                        "VALUES (?, 'MOVIE', 1, 'Film', 'film', 0, '', '', ?, '', '', ?, ?, 0, 0, 0, 0)",
                    arrayOf<Any?>(i + 1, "Film ${i + 1}.mkv", mediaInfo, MediaProbe.decode(mediaInfo)?.probedWith),
                )
            }
        }
        val films = (1L..stored.size).map { checkNotNull(db.dao().item(it)) }
        assertEquals(probed.copy(tracksInOrder = true), MediaProbe.decode(films[0].mediaInfo))
        assertEquals("the others as they were", stored.drop(1), films.drop(1).map { it.mediaInfo })
        assertEquals(listOf(4, 4, 4, 0), films.map { it.probeVersion })
    }

    /** What [version12KeepsTheCastAsCreditsAndWhatTheUserDidAsItWas] finds once migrated. */
    private suspend fun checkVersion12(db: LibraryDatabase) {
        val dao = db.dao()
        val films = (1L..3L).map { checkNotNull(dao.item(it)) }
        for (film in films) {
            assertEquals(600_000L to 9_000_000L, film.positionMs to film.durationMs)
            assertEquals(true to 5L, film.favorite to film.lastPlayedAt)
            assertEquals("file:/p.jpg", film.userPosterPath)
            assertEquals("their crew comes at later passes", null, film.creditsAt)
        }
        // Watched in one copy: the title is watched. The copy keeps its own state.
        assertEquals(listOf(true, true, false), films.map { it.played })
        assertEquals(listOf(null, 78, null), films.map { it.userTmdbId })
        assertEquals(listOf(null, "Final Cut", "Extended"), films.map { it.edition })
        assertEquals(listOf(null, 1L, null), films.map { it.versionOf })

        val ford = dao.creditsOf(1).single { it.job == Job.CAST }
        assertEquals("name:harrison ford", ford.personKey)
        assertEquals("Rick Deckard" to "/ford.jpg", ford.character to ford.profilePath)
        assertEquals("name:ridley scott", dao.creditsOf(1).single { it.job == Job.DIRECTOR }.personKey)
        assertEquals("a copy counts once", 1, dao.creditsOf(2).single { it.job == Job.DIRECTOR }.titles)
        assertEquals(listOf("Audrey Tautou", "Jean-Pierre Jeunet"), dao.creditsOf(3).map { it.name }.sorted())

        val hasCast = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name = 'cast'").use { it.count }
        assertEquals(0, hasCast)
        assertEquals(listOf(null, 99L), listOf(1L, 2L).map { dao.collection(it)?.fetchedAt })
        assertEquals(listOf(dao.collection(1)), dao.sagasDue(10))

        val catalog = LocalProvider(context, dao, NoEditor).catalog
        assertEquals(listOf("Le Fabuleux Destin d’Amélie Poulain"), catalog.search("amel").titles.map { it.title })
        assertEquals(listOf("Jean-Pierre Jeunet"), catalog.search("jeunet").people.map { it.name })
        assertEquals(listOf("name:ridley scott"), catalog.search("ridley").people.map { it.id })
    }

    private companion object {
        const val FIREFLY = 1437
        const val ONE_PIECE = 37854

        /** A folder's row in schemas 6 and 7, before firstScanAt. */
        const val FOLDER_UP_TO_7 =
            "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt)"
    }
}
