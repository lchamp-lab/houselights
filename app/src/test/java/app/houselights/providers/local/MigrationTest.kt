package app.houselights.providers.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Job
import app.houselights.core.Secrets
import app.houselights.testing.testSecrets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.ProviderException

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val secrets = testSecrets()

    /** Schemas 1 to 5 predate Room's schema export and were rebuilt (app/schemas); Room checks them on open. */
    @Test
    fun everyVersionBeforeSchemasWereKeptReachesTheLatest() = runTest {
        for (version in 1..5) {
            val name = "migration-from-$version-test.db"
            context.deleteDatabase(name)
            SchemaHistory.create(context, name, version) {
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
            val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addTypeConverter(SecretConverter(secrets))
                .addMigrations(*LibraryDatabase.migrations(secrets))
                .build()
            try {
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
                assertEquals("from $version: never read", 0 to null, dune.probeVersion to dune.fileDurationMs)
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun version7SealsPasswords() = runTest {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, version = 6) {
            val columns = "(id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt)"
            execSQL("INSERT INTO sources $columns VALUES (1, 'Films', 'MOVIES', 'SMB', 'nas', 'media', 'films', 'zidoo', 's3cret', '', 0)")
            execSQL("INSERT INTO sources $columns VALUES (2, 'Séries', 'SHOWS', 'NFS', 'nas', 'volume1/media', 'series', '', '', '', 0)")
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
        try {
            val raw = db.openHelper.readableDatabase.query("SELECT password FROM sources ORDER BY id").use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }
            }
            assertTrue(secrets.isSealed(raw[0]))
            assertEquals("", raw[1])
            assertEquals(listOf("s3cret", ""), db.dao().sources().sortedBy { it.id }.map { it.password.value })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /** A Keystore that cannot seal leaves the password plain: the library still opens and the folder signs in. */
    @Test
    fun version7KeepsAPasswordTheKeystoreCannotSeal() = runTest {
        val name = "migration-7-broken-test.db"
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, version = 6) {
            val columns = "(id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt)"
            execSQL("INSERT INTO sources $columns VALUES (1, 'Films', 'MOVIES', 'SMB', 'nas', 'media', 'films', 'zidoo', 's3cret', '', 0)")
        }
        val broken = Secrets { throw ProviderException("Keystore operation failed") }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(broken))
            .addMigrations(*LibraryDatabase.migrations(broken))
            .build()
        try {
            assertEquals(listOf("s3cret"), db.dao().sources().map { it.password.value })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun version8KeepsWhatLibrariesHoldFromBeingNew() = runTest {
        val name = "migration-8-test.db"
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, version = 7) {
            val columns = "(id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt)"
            execSQL("INSERT INTO sources $columns VALUES (1, 'Films', 'MOVIES', 'NFS', 'nas', 'media', 'films', '', '', '', 1000)")
            execSQL("INSERT INTO sources $columns VALUES (2, 'Séries', 'SHOWS', 'NFS', 'nas', 'media', 'series', '', '', '', 0)")
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
        try {
            assertEquals(listOf(1L to 1000L, 2L to 0L), db.dao().firstScans().sortedBy { it.id }.map { it.id to it.firstScanAt })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun version9KeepsEveryRowListed() = runTest {
        val name = "migration-9-test.db"
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, version = 8) {
            execSQL(
                "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt, firstScanAt) " +
                    "VALUES (1, 'Films', 'MOVIES', 'NFS', 'nas', 'media', 'films', '', '', '', 1000, 1000)"
            )
            execSQL(
                "INSERT INTO items (id, kind, sourceId, title, sortTitle, continuing, genres, directors, filePath, fileSize, " +
                    "badges, addedAt, positionMs, played, favorite) " +
                    "VALUES (1, 'MOVIE', 1, 'Dune', 'dune', 0, '', '', 'Dune.mkv', 42, '', 0, 600000, 0, 1)"
            )
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
        try {
            val dune = checkNotNull(db.dao().item(1))
            assertEquals(null, dune.missingSince)
            assertEquals("Dune.mkv" to 42L, dune.filePath to dune.fileSize)
            assertEquals(600_000L, dune.positionMs)
            assertTrue(dune.favorite)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun version10KeepsEveryRowAsItWas() = runTest {
        val name = "migration-10-test.db"
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, version = 9) {
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
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
        try {
            val pilot = checkNotNull(db.dao().item(1))
            assertEquals(null, pilot.lastEpisodeNumber)
            assertEquals(emptyList<String>(), pilot.parts)
            assertEquals(null, pilot.sidecarKey)
            assertEquals("Show/S01E01.nfo", pilot.nfoPath)
            assertTrue(pilot.played)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /** Version 11 copies each file's probe version and length into columns; unreadable JSON means a new probe. */
    @Test
    fun version11ReadsEachFilesProbeAndLengthIntoTheirColumns() = runTest {
        val name = "migration-11-test.db"
        context.deleteDatabase(name)
        val read = """{"container":"MKV","durationMs":9300000,"video":{"codec":"HEVC","width":3840},"probedWith":3}"""
        val fromNfo = """{"container":"AVI","video":{"codec":"MPEG-4"}}"""
        SchemaHistory.create(context, name, version = 10) {
            execSQL(
                "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt, firstScanAt) " +
                    "VALUES (1, 'Films', 'MOVIES', 'NFS', 'nas', 'media', 'films', '', '', '', 1000, 1000)"
            )
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
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
        try {
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
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /** Version 10 folds version 9's accented sort keys and drops a glued l': "la land" keeps its "la". */
    @Test
    fun version10FoldsSortKeysAndTakesOffAGluedLAndNothingMore() = runTest {
        val name = "migration-10-sort-test.db"
        context.deleteDatabase(name)
        val keys = listOf("ça", "à couteaux tirés", "été 85", "œdipe roi", "la land", "l'arnaque", "l’exorciste", "zodiac")
        SchemaHistory.create(context, name, version = 9) {
            execSQL(
                "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt, firstScanAt) " +
                    "VALUES (1, 'Films', 'MOVIES', 'NFS', 'nas', 'media', 'films', '', '', '', 1000, 1000)"
            )
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
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
        try {
            assertEquals(
                listOf("ca", "a couteaux tires", "ete 85", "oedipe roi", "la land", "arnaque", "exorciste", "zodiac"),
                keys.indices.map { checkNotNull(db.dao().item(it + 1L)).sortTitle },
            )
            assertEquals("ça", db.dao().item(1)?.title)
            assertEquals("etrange saga", db.dao().collection(1)?.sortTitle)
            assertEquals("age de glace", db.dao().collection(2)?.sortTitle)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /** Sagas named in Japanese shared the key "name:": version 10 rematches their films, and only theirs. */
    @Test
    fun version10MatchesAgainTheFilmsOfSagasWhoseNamesLeftNoWord() = runTest {
        val name = "migration-10-sagas-test.db"
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, version = 9) {
            execSQL(
                "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt, firstScanAt) " +
                    "VALUES (1, 'Films', 'MOVIES', 'NFS', 'nas', 'media', 'films', '', '', '', 1000, 1000)"
            )
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
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
        try {
            assertEquals(listOf(null, null, 500L), (1L..3L).map { checkNotNull(db.dao().item(it)).matchedAt })
            assertEquals(listOf(null, null, 2L), (1L..3L).map { checkNotNull(db.dao().item(it)).collectionId })
            assertNull(db.dao().collection(1))
            assertEquals("tmdb:531241", db.dao().collection(2)?.matchKey)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * Version 12 adds credits, search words, editions and copies' titles, and refetches known sagas' films,
     * keeping what the user did with each title.
     */
    @Test
    fun version12KeepsTheCastAsCreditsAndWhatTheUserDidAsItWas() = runTest {
        val name = "migration-12-test.db"
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, version = 11) {
            execSQL(
                "INSERT INTO sources (id, name, kind, protocol, host, share, path, username, password, domain, lastScanAt, firstScanAt) " +
                    "VALUES (1, 'Films', 'MOVIES', 'NFS', 'nas', 'media', 'films', '', '', '', 1000, 1000)"
            )
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
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
        try {
            checkVersion12(db)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /** What [version12KeepsTheCastAsCreditsAndWhatTheUserDidAsItWas] finds once migrated. */
    private suspend fun checkVersion12(db: LibraryDatabase) {
        run {
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
    }
}
