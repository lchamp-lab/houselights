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
        /** A folder's row in schemas 6 and 7, before firstScanAt. */
        const val FOLDER_UP_TO_7 =
            "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt)"
    }
}
