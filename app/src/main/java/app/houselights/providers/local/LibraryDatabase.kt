package app.houselights.providers.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.houselights.core.Secrets

@Database(entities = [SourceEntity::class, ItemEntity::class, CastEntity::class, CollectionEntity::class], version = 9)
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

        /** Seals the passwords stored in plain text so far, and indexes what lists sort and filter on. */
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

        internal fun migration6To7(secrets: Secrets) = object : Migration(startVersion = 6, endVersion = 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query("SELECT id, password FROM sources").use { rows ->
                    while (rows.moveToNext()) {
                        val password = rows.getString(1)
                        if (password.isEmpty() || secrets.isSealed(password)) continue
                        db.execSQL("UPDATE sources SET password = ? WHERE id = ?", arrayOf<Any>(secrets.seal(password), rows.getLong(0)))
                    }
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_sortTitle` ON `items` (`kind`, `sortTitle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_addedAt` ON `items` (`kind`, `addedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_lastPlayedAt` ON `items` (`kind`, `lastPlayedAt`)")
            }
        }

        internal fun migrations(secrets: Secrets) = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, migration6To7(secrets), MIGRATION_7_8,
            MIGRATION_8_9,
        )

        fun create(context: Context, secrets: Secrets): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, NAME)
                .addTypeConverter(SecretConverter(secrets))
                .addMigrations(*migrations(secrets))
                .build()
    }
}
