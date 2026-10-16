package app.houselights.providers.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.houselights.core.Secrets
import app.houselights.core.SecretsUnavailableException
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Database(entities = [SourceEntity::class, ItemEntity::class, CastEntity::class, CollectionEntity::class], version = 11)
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
                for ((id, version, length) in read) {
                    db.execSQL("UPDATE items SET probeVersion = ?, fileDurationMs = ? WHERE id = ?", arrayOf<Any?>(version, length, id))
                }
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
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
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
