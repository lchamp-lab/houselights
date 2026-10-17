package app.houselights.providers.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.houselights.core.Job
import app.houselights.core.Secrets
import app.houselights.core.SecretsUnavailableException
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Database(
    entities = [
        SourceEntity::class, ItemEntity::class, CreditEntity::class, CollectionEntity::class, SagaPartEntity::class,
        SearchWordEntity::class,
    ],
    version = 12,
)
@TypeConverters(SecretConverter::class, PipeList::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        const val NAME = "library.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN protocol TEXT NOT NULL DEFAULT 'SMB'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN mediaInfo TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN nfoPath TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN nfo TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN userTmdbId INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN userPosterPath TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN userBackdropPath TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN userLogoPath TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`matchKey` TEXT NOT NULL, `tmdbId` INTEGER, `name` TEXT NOT NULL, `sortTitle` TEXT NOT NULL, " +
                        "`overview` TEXT, `posterPath` TEXT, `backdropPath` TEXT, `partCount` INTEGER, `fetchedAt` INTEGER)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_matchKey` ON `collections` (`matchKey`)")
                db.execSQL("ALTER TABLE items ADD COLUMN collectionId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_collectionId` ON `items` (`collectionId`)")
                // Films already in the library learn their collection at the next scan.
                db.execSQL("UPDATE items SET matchedAt = NULL WHERE kind = 'MOVIE'")
            }
        }

        /** What a library already holds was found by its first scans (see [SourceEntity.firstScanAt]). */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN firstScanAt INTEGER NOT NULL DEFAULT 0")
                // What a library already holds was found by its first scans: none of it is new.
                db.execSQL("UPDATE sources SET firstScanAt = lastScanAt")
            }
        }

        /** Rows of missing files wait before they go (see [MissingFiles]). */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN missingSince INTEGER")
            }
        }

        /**
         * Files holding several episodes, films in several parts, and what was read beside each file.
         * Sort keys lose their accents and ligatures; films of a saga whose name gave no key leave it, for their own.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN lastEpisodeNumber INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN parts TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN sidecarKey TEXT")
                foldSortKeys(db, "items")
                foldSortKeys(db, "collections")
                // Sagas whose names left no word ("Collection", a name in Japanese) were all one: their films
                // leave it, and find their own at the next scan (see CollectionRules.key), from their NFO's set
                // (their sidecars are read again, sidecarKey being new) or from TMDB, which is asked again.
                val merged = "SELECT id FROM collections WHERE matchKey = 'name:'"
                db.execSQL("UPDATE items SET matchedAt = NULL WHERE kind = 'MOVIE' AND collectionId IN ($merged)")
                db.execSQL("UPDATE items SET collectionId = NULL WHERE collectionId IN ($merged)")
                db.execSQL("DELETE FROM collections WHERE matchKey = 'name:'")
            }
        }

        /**
         * The probe each file was read with and the length it gives, in columns of their own (see
         * [ItemEntity.probeVersion]), from what each row holds: its JSON, decoded here once, in Kotlin,
         * as Android 9's SQLite may lack its JSON functions. A row whose JSON cannot be read keeps 0
         * and no length: the next scan reads its file again, as it did.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN probeVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN fileDurationMs INTEGER")
                val read = db.query("SELECT id, mediaInfo FROM items WHERE mediaInfo IS NOT NULL").use { rows ->
                    buildList {
                        while (rows.moveToNext()) {
                            val info = MediaProbe.decode(rows.getString(1)) ?: continue
                            if (info.probedWith != 0 || info.durationMs != null) {
                                add(Triple(rows.getLong(0), info.probedWith, info.durationMs))
                            }
                        }
                    }
                }
                // One statement for every row: a library of thousands of files opens once, without compiling each.
                db.compileStatement("UPDATE items SET probeVersion = ?, fileDurationMs = ? WHERE id = ?").use { update ->
                    for ((id, version, length) in read) {
                        update.bindLong(1, version.toLong())
                        if (length != null) update.bindLong(2, length) else update.bindNull(2)
                        update.bindLong(3, id)
                        update.executeUpdateDelete()
                        update.clearBindings()
                    }
                }
            }
        }

        /**
         * Credits by job and by person, in place of the cast alone; sagas whole; the copies of a film; words for
         * searches. What a library holds comes along at once, folded here in Kotlin as Android 9's SQLite folds no
         * accent: each title's cast and a film's directors, keyed by their names until TMDB gives their ids; each
         * title's, person's and saga's words; a film's edition from its file's name; the copies of each film. The
         * crew TMDB credits titles with, and the films of sagas TMDB knows (asked again: fetchedAt is cleared), come
         * at later passes, a few a pass ([Backfill]). Nothing the user did is lost: a film's title takes what was done
         * with any of its copies, watched or a favourite.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createTables(db)
                val credits = formerCredits(db)
                db.compileStatement(
                    "INSERT OR REPLACE INTO credits (itemId, job, ord, personKey, name, foldedName, character, profilePath) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { insert ->
                    for (c in credits) {
                        val values =
                            listOf(c.itemId, c.job.name, c.ord.toLong(), c.personKey, c.name, c.foldedName, c.character, c.profilePath)
                        values.forEachIndexed { i, value ->
                            when (value) {
                                null -> insert.bindNull(i + 1)
                                is Long -> insert.bindLong(i + 1, value)
                                else -> insert.bindString(i + 1, value.toString())
                            }
                        }
                        insert.executeInsert()
                        insert.clearBindings()
                    }
                }
                db.execSQL("DROP TABLE IF EXISTS `cast`")
                writeEditions(db)
                val words = titleWords(db) + credits.distinctBy { it.personKey }.flatMap {
                    SearchWords.rows(SearchKind.PERSON, it.personKey, it.name)
                } + sagaWords(db)
                db.compileStatement("INSERT OR IGNORE INTO search_words (kind, word, ref) VALUES (?, ?, ?)").use { insert ->
                    for (w in words) {
                        listOf(w.kind.name, w.word, w.ref).forEachIndexed { i, value -> insert.bindString(i + 1, value) }
                        insert.executeInsert()
                        insert.clearBindings()
                    }
                }
                db.execSQL(GROUP_VERSIONS)
                // A film watched or chosen as a favourite in one of its copies is so as one title: its card shows the
                // title row's state, and from now on what is done with it is done with every copy.
                for (state in listOf("played", "favorite")) {
                    db.execSQL(
                        "UPDATE items SET $state = 1 WHERE kind = 'MOVIE' AND versionOf IS NULL AND $state = 0 " +
                            "AND id IN (SELECT versionOf FROM items WHERE versionOf IS NOT NULL AND $state = 1)"
                    )
                }
                db.execSQL("UPDATE collections SET fetchedAt = NULL WHERE tmdbId IS NOT NULL")
            }
        }

        /** The tables and columns of version 12, as Room exported them. */
        private fun createTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `credits` (`itemId` INTEGER NOT NULL, `job` TEXT NOT NULL, `ord` INTEGER NOT NULL, " +
                    "`personKey` TEXT NOT NULL, `name` TEXT NOT NULL, `foldedName` TEXT NOT NULL, `character` TEXT, " +
                    "`profilePath` TEXT, PRIMARY KEY(`itemId`, `job`, `ord`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_credits_personKey_itemId` ON `credits` (`personKey`, `itemId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_credits_job_personKey` ON `credits` (`job`, `personKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_credits_foldedName` ON `credits` (`foldedName`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `saga_parts` (`collectionId` INTEGER NOT NULL, `tmdbId` INTEGER NOT NULL, " +
                    "`ord` INTEGER NOT NULL, `title` TEXT NOT NULL, `releaseDate` TEXT, `overview` TEXT, `posterPath` TEXT, " +
                    "`backdropPath` TEXT, PRIMARY KEY(`collectionId`, `tmdbId`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_saga_parts_tmdbId` ON `saga_parts` (`tmdbId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `search_words` (`kind` TEXT NOT NULL, `word` TEXT NOT NULL, `ref` TEXT NOT NULL, " +
                    "PRIMARY KEY(`kind`, `word`, `ref`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_words_kind_ref` ON `search_words` (`kind`, `ref`)")
            for (column in listOf("edition TEXT", "versionOf INTEGER", "versionChoice INTEGER", "votes INTEGER", "creditsAt INTEGER")) {
                db.execSQL("ALTER TABLE items ADD COLUMN $column")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_tmdbId` ON `items` (`kind`, `tmdbId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_versionOf` ON `items` (`versionOf`)")
        }

        /** The cast version 11 kept, and a film's directors, as credits keyed by name ([PersonKeys]). */
        private fun formerCredits(db: SupportSQLiteDatabase): List<CreditEntity> {
            fun credit(itemId: Long, job: Job, ord: Int, name: String, character: String?, profile: String?): CreditEntity {
                val folded = PersonKeys.fold(name)
                return CreditEntity(itemId, job, ord, PersonKeys.name(folded), name, folded, character, profile)
            }
            val cast = db.query("SELECT itemId, ord, name, character, profilePath FROM `cast`").use { rows ->
                fun text(column: String) = rows.getColumnIndexOrThrow(column).let { if (rows.isNull(it)) null else rows.getString(it) }
                buildList {
                    while (rows.moveToNext()) {
                        add(credit(rows.getLong(0), Job.CAST, rows.getInt(1), rows.getString(2), text("character"), text("profilePath")))
                    }
                }
            }
            val directors = db.query("SELECT id, directors FROM items WHERE kind = 'MOVIE' AND directors != ''").use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        PipeList.fromText(rows.getString(1)).distinct().forEachIndexed { i, name ->
                            add(credit(rows.getLong(0), Job.DIRECTOR, i, name, null, null))
                        }
                    }
                }
            }
            return cast + directors
        }

        /** Each film's edition, as its file's name gives it ([FilenameParser.edition]); its NFO's comes with the backfill. */
        private fun writeEditions(db: SupportSQLiteDatabase) {
            val editions = db.query("SELECT id, filePath FROM items WHERE kind = 'MOVIE' AND filePath IS NOT NULL").use { rows ->
                buildList {
                    while (rows.moveToNext()) FilenameParser.edition(rows.getString(1))?.let { add(rows.getLong(0) to it) }
                }
            }
            for ((id, edition) in editions) db.execSQL("UPDATE items SET edition = ? WHERE id = ?", arrayOf<Any>(edition, id))
        }

        /** The words of each film's and show's names. */
        private fun titleWords(db: SupportSQLiteDatabase): List<SearchWordEntity> =
            db.query("SELECT id, title, originalTitle FROM items WHERE kind IN ('MOVIE', 'SERIES')").use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val original = if (rows.isNull(2)) null else rows.getString(2)
                        addAll(SearchWords.rows(SearchKind.TITLE, rows.getLong(0).toString(), rows.getString(1), original))
                    }
                }
            }

        /** The words of each saga's name. */
        private fun sagaWords(db: SupportSQLiteDatabase): List<SearchWordEntity> =
            db.query("SELECT id, name FROM collections").use { rows ->
                buildList {
                    while (rows.moveToNext()) addAll(SearchWords.rows(SearchKind.SAGA, rows.getLong(0).toString(), rows.getString(1)))
                }
            }

        /**
         * Folds the accents and ligatures of the sort keys of [table] ([MetadataMerge.fold]), and takes off
         * an l' glued to its word ("l'arnaque"), which version 9 left. Nothing more: taking the articles off
         * again would not leave a key as it was ("la land", from "La La Land", would become "land"). A title
         * with no file beside it is merged again only by a refresh: its key is made right here.
         */
        private fun foldSortKeys(db: SupportSQLiteDatabase, table: String) {
            val folded = db.query("SELECT id, sortTitle FROM $table").use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val key = rows.getString(1)
                        val fold = MetadataMerge.fold(key).replaceFirst(GLUED_L, "")
                        if (fold != key) add(rows.getLong(0) to fold)
                    }
                }
            }
            for ((id, key) in folded) db.execSQL("UPDATE $table SET sortTitle = ? WHERE id = ?", arrayOf<Any>(key, id))
        }

        /**
         * Seals the passwords stored in plain text so far, and indexes what lists sort and filter on.
         * A Keystore that cannot seal leaves a password as it is: read as plain text, it still signs
         * in, and it is sealed at a later launch ([sealPlainPasswords]); the library still opens.
         */
        internal fun migration6To7(secrets: Secrets) = object : Migration(startVersion = 6, endVersion = 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query("SELECT id, password FROM sources").use { rows ->
                    while (rows.moveToNext()) {
                        val password = rows.getString(1)
                        if (password.isEmpty() || secrets.isSealed(password)) continue
                        val sealed = try {
                            secrets.seal(password)
                        } catch (e: SecretsUnavailableException) {
                            Log.w(TAG, "The password of folder ${rows.getLong(0)} left unsealed", e)
                            null
                        }
                        if (sealed != null) {
                            db.execSQL("UPDATE sources SET password = ? WHERE id = ?", arrayOf<Any>(sealed, rows.getLong(0)))
                        }
                    }
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_sortTitle` ON `items` (`kind`, `sortTitle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_addedAt` ON `items` (`kind`, `addedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_lastPlayedAt` ON `items` (`kind`, `lastPlayedAt`)")
            }
        }

        internal fun migrations(secrets: Secrets) = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, migration6To7(secrets), MIGRATION_7_8,
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
        )

        fun create(context: Context, secrets: Secrets): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, NAME)
                .addTypeConverter(SecretConverter(secrets))
                .addMigrations(*migrations(secrets))
                .build()

        /**
         * Seals the passwords still in plain text, which migration 6 to 7 left when the Keystore could
         * not seal: a scan never writes a folder's password, so without this they would stay so until
         * the folder is saved again. Run at each launch, off the main thread; a Keystore that still
         * cannot seal, or a write that fails, leaves them for the next launch, logged.
         */
        suspend fun sealPlainPasswords(dao: LibraryDao, secrets: Secrets) {
            suspendRunCatching {
                withContext(Dispatchers.IO) {
                    val plain = dao.storedPasswords().filterNot { secrets.isSealed(it.password) }
                    for (stored in plain) dao.sealPassword(stored.id, stored.password, secrets.seal(stored.password))
                    plain.size
                }
            }
                .onSuccess { if (it > 0) Log.i(TAG, "$it passwords left in plain text are sealed") }
                .onFailure { Log.w(TAG, "Passwords left in plain text wait for the next launch", it) }
        }

        private const val TAG = "Library"

        /** l' or l’ glued to a word, at the start of a key: version 9 took it off only before a space. */
        private val GLUED_L = Regex("""^l['’](?=\S)""")
    }
}
