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
import app.houselights.core.MediaInfo
import app.houselights.core.SecretStore
import app.houselights.core.Secrets
import app.houselights.core.SecretsUnavailableException
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Database(
    entities = [
        SourceEntity::class, ItemEntity::class, CreditEntity::class, CollectionEntity::class, SagaPartEntity::class,
        SearchWordEntity::class, TraktPendingEntity::class, TraktStateEntity::class, TrackChoiceEntity::class,
        KeptStateEntity::class, SuggestionEntity::class, EpisodeOrderEntity::class, IgnoredFileEntity::class,
    ],
    version = 21,
)
@TypeConverters(SecretConverter::class, PipeList::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    abstract fun trakt(): TraktDao

    abstract fun trackChoices(): TrackChoiceDao

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
                        "`overview` TEXT, `posterPath` TEXT, `backdropPath` TEXT, `partCount` INTEGER, `fetchedAt` INTEGER)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_matchKey` ON `collections` (`matchKey`)")
                db.execSQL("ALTER TABLE items ADD COLUMN collectionId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_collectionId` ON `items` (`collectionId`)")
                // Existing films are matched again at the next scan to get their collection.
                db.execSQL("UPDATE items SET matchedAt = NULL WHERE kind = 'MOVIE'")
            }
        }

        /** v7 → v8: adds sources.firstScanAt (see [SourceEntity.firstScanAt]). */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN firstScanAt INTEGER NOT NULL DEFAULT 0")
                // Everything already in the library counts as found by the first scan, so none of it is new.
                db.execSQL("UPDATE sources SET firstScanAt = lastScanAt")
            }
        }

        /** v8 → v9: adds items.missingSince (see [MissingFiles]). */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN missingSince INTEGER")
            }
        }

        /**
         * v9 → v10: adds multi-episode files, multi-part films and sidecar keys; folds accents and ligatures out of
         * sort keys; splits the collection that merged all sagas whose names had no significant word.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN lastEpisodeNumber INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN parts TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN sidecarKey TEXT")
                foldSortKeys(db, "items")
                foldSortKeys(db, "collections")
                // Sagas with no significant word ("Collection", Japanese script) shared the key "name:": their
                // films are rematched at the next scan (see CollectionRules.key).
                val merged = "SELECT id FROM collections WHERE matchKey = 'name:'"
                db.execSQL("UPDATE items SET matchedAt = NULL WHERE kind = 'MOVIE' AND collectionId IN ($merged)")
                db.execSQL("UPDATE items SET collectionId = NULL WHERE collectionId IN ($merged)")
                db.execSQL("DELETE FROM collections WHERE matchKey = 'name:'")
            }
        }

        /**
         * v10 → v11: copies probe version and duration out of the mediaInfo JSON into columns, decoding in Kotlin as
         * Android 9's SQLite may lack JSON functions; undecodable rows keep version 0 and are probed again.
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
         * v11 → v12: replaces the cast table with credits, adds saga parts, film copies and search words (computed in
         * Kotlin, as Android 9's SQLite cannot fold accents). TMDB crew and saga parts follow via [Backfill].
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createTables(db)
                val credits = formerCredits(db)
                db.compileStatement(
                    "INSERT OR REPLACE INTO credits (itemId, job, ord, personKey, name, foldedName, character, profilePath) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
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
                // The main row, which cards show, takes the watched and favourite state of any copy.
                for (state in listOf("played", "favorite")) {
                    db.execSQL(
                        "UPDATE items SET $state = 1 WHERE kind = 'MOVIE' AND versionOf IS NULL AND $state = 0 " +
                            "AND id IN (SELECT versionOf FROM items WHERE versionOf IS NOT NULL AND $state = 1)",
                    )
                }
                db.execSQL("UPDATE collections SET fetchedAt = NULL WHERE tmdbId IS NOT NULL")
            }
        }

        /** v12 → v13: adds what waits for Trakt and where the sync stands ([TraktDao]). */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trakt_pending` (`title` TEXT NOT NULL, `action` TEXT NOT NULL, `at` INTEGER NOT NULL, " +
                        "`progress` REAL, `seq` INTEGER NOT NULL, PRIMARY KEY(`title`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trakt_state` (`id` INTEGER NOT NULL, `syncedAt` INTEGER, `watched` INTEGER NOT NULL, " +
                        "`inProgress` INTEGER NOT NULL, `error` TEXT, `activities` TEXT, PRIMARY KEY(`id`))",
                )
            }
        }

        /** v13 → v14: what waits for Trakt counts its failed writes and notes when it was sent. */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trakt_pending ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trakt_pending ADD COLUMN sentAt INTEGER")
            }
        }

        /** v14 → v15: a folder can be due to be read again in a new language. */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN refreshIn TEXT")
            }
        }

        /** v15 → v16: each title's choice of tracks; the tracks the probe read are marked in the file's order. */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `track_choices` (`title` TEXT NOT NULL, `audio` TEXT, `subtitles` TEXT, " +
                        "`at` INTEGER NOT NULL, PRIMARY KEY(`title`))",
                )
                markTracksInOrder(db)
            }
        }

        /** v16 → v17: an NFS folder can present another user than root ([SourceEntity.uid]). */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN uid INTEGER")
                db.execSQL("ALTER TABLE sources ADD COLUMN gid INTEGER")
            }
        }

        /** v17 → v18: what the user did to titles whose files left, given back when they return ([KeptStateEntity]). */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `kept_states` (`title` TEXT NOT NULL, `kind` TEXT NOT NULL, `nameKey` TEXT NOT NULL, " +
                        "`tmdbId` INTEGER, `showTmdbId` INTEGER, `sourceId` INTEGER NOT NULL, `folder` TEXT, `seasonNumber` INTEGER, " +
                        "`episodeNumber` INTEGER, `lastEpisodeNumber` INTEGER, `played` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER, `lastPlayedAt` INTEGER, `favorite` INTEGER NOT NULL, `userTmdbId` INTEGER, " +
                        "`userPosterPath` TEXT, `userBackdropPath` TEXT, `userLogoPath` TEXT, `addedAt` INTEGER NOT NULL, " +
                        "`audio` TEXT, `subtitles` TEXT, `tracksAt` INTEGER, PRIMARY KEY(`title`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_kept_states_nameKey` ON `kept_states` (`nameKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_kept_states_sourceId` ON `kept_states` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_kept_states_showTmdbId` ON `kept_states` (`showTmdbId`)")
            }
        }

        /**
         * v18 → v19: what TMDB made of each film and show ([MatchState]), the id its name gave ([ItemEntity.hintKey]),
         * the user's choice to leave one as it is, and the candidates of a title TMDB hesitated on ([SuggestionEntity]).
         * A title with a TMDB id is matched; one TMDB answered without is not found, so to identify; one TMDB never
         * answered for stays to look up, as before.
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN matchState TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN hintKey TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN userNoMatch INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_matchState` ON `items` (`matchState`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `suggestions` (`itemId` INTEGER NOT NULL, `rank` INTEGER NOT NULL, " +
                        "`tmdbId` INTEGER NOT NULL, `title` TEXT NOT NULL, `originalTitle` TEXT, `year` INTEGER, `overview` TEXT, " +
                        "`posterPath` TEXT, `score` REAL NOT NULL, PRIMARY KEY(`itemId`, `rank`))",
                )
                db.execSQL("UPDATE items SET matchState = 'MATCHED' WHERE kind IN ('MOVIE', 'SERIES') AND tmdbId IS NOT NULL")
                db.execSQL(
                    "UPDATE items SET matchState = 'NOT_FOUND' WHERE kind IN ('MOVIE', 'SERIES') AND tmdbId IS NULL " +
                        "AND matchedAt IS NOT NULL",
                )
            }
        }

        /**
         * v19 → v20: whether the user kept the titles a sudden drop holds in a folder ([SourceEntity.heldKept]). No clock
         * holds a row any longer: rows held a day by the versions before are listed again, so the next pass judges them
         * by the drop alone ([MissingFiles.isDrop]), gone if few, held and asked about if many.
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN heldKept INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE items SET missingSince = NULL WHERE missingSince IS NOT NULL")
            }
        }

        /**
         * v20 → v21: episodes numbered from their show's start ([ItemEntity.absoluteNumber]) and the TMDB episode each
         * shows ([ItemEntity.tmdbSeason]); the order a show's episodes follow, placed and chosen ([ItemEntity.episodeOrder],
         * [ItemEntity.userEpisodeOrder]), kept with a show that leaves ([KeptStateEntity.userEpisodeOrder]), and when it
         * changed ([ItemEntity.reorderedAt]); an episode kept by its file's numbering ([KeptStateEntity.span]); a title
         * left as it is kept so when its files leave ([KeptStateEntity.userNoMatch]), none yet; TMDB's orders of each show
         * ([EpisodeOrderEntity]) and the videos a shows folder places nowhere ([IgnoredFileEntity]).
         * Episodes numbered 100 or more are read again, as they may be numbered from their show's start, and so is one
         * episode of each show whose tvshow.nfo names an order, which it then follows; nothing else changes.
         */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = listOf(
                    "absoluteNumber INTEGER",
                    "tmdbSeason INTEGER",
                    "tmdbEpisode INTEGER",
                    "tmdbLastEpisode INTEGER",
                    "episodeOrder TEXT",
                    "userEpisodeOrder TEXT",
                    "reorderedAt INTEGER",
                )
                for (column in columns) db.execSQL("ALTER TABLE items ADD COLUMN $column")
                db.execSQL("ALTER TABLE kept_states ADD COLUMN span TEXT")
                db.execSQL("ALTER TABLE kept_states ADD COLUMN userEpisodeOrder TEXT")
                db.execSQL("ALTER TABLE kept_states ADD COLUMN userNoMatch INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `episode_orders` (`showTmdbId` INTEGER NOT NULL, `orderId` TEXT NOT NULL, " +
                        "`type` INTEGER NOT NULL, `name` TEXT NOT NULL, `episodeCount` INTEGER NOT NULL, `rank` INTEGER NOT NULL, " +
                        "`places` TEXT, `fetchedAt` INTEGER, `groupsAt` INTEGER, PRIMARY KEY(`showTmdbId`, `orderId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ignored_files` (`sourceId` INTEGER NOT NULL, `filePath` TEXT NOT NULL, " +
                        "`reason` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `filePath`))",
                )
                db.execSQL("UPDATE items SET matchedAt = NULL WHERE kind = 'EPISODE' AND episodeNumber >= 100")
                val ordered = "SELECT id FROM items WHERE kind = 'SERIES' AND nfo IS NOT NULL " +
                    "AND (instr(lower(nfo), 'displayorder') > 0 OR instr(lower(nfo), 'episode_group') > 0)"
                db.execSQL("UPDATE items SET matchedAt = NULL WHERE id IN ($ordered)")
                db.execSQL(
                    "UPDATE items SET matchedAt = NULL WHERE id IN (SELECT MIN(id) FROM items WHERE kind = 'EPISODE' " +
                        "AND seriesId IN ($ordered) GROUP BY seriesId)",
                )
            }
        }

        /**
         * Sets [MediaInfo.tracksInOrder] where the probe read the tracks, so that no file is read again for it; decoded
         * in Kotlin as in [MIGRATION_10_11], by batches so a large library's JSON is never held whole.
         */
        private fun markTracksInOrder(db: SupportSQLiteDatabase) {
            var after = 0L
            do {
                val rows = db.query(
                    "SELECT id, mediaInfo FROM items WHERE id > ? AND probeVersion > 0 AND mediaInfo IS NOT NULL " +
                        "ORDER BY id LIMIT $MARK_BATCH",
                    arrayOf<Any>(after),
                ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
                db.compileStatement("UPDATE items SET mediaInfo = ? WHERE id = ?").use { update ->
                    for ((id, text) in rows) {
                        val info = MediaProbe.decode(text)?.takeIf(::probedTracks) ?: continue
                        update.bindString(1, MediaProbe.encode(info.copy(tracksInOrder = true)))
                        update.bindLong(2, id)
                        update.executeUpdateDelete()
                        update.clearBindings()
                    }
                }
                after = rows.lastOrNull()?.first ?: after
            } while (rows.size == MARK_BATCH)
        }

        /**
         * An MKV whose video carries what only the probe reads. An NFO's video, filled in when the probe found none
         * ([withNfoStreams]), has a codec, size and HDR alone, and its tracks are in the NFO's order.
         */
        private fun probedTracks(info: MediaInfo): Boolean {
            val video = info.video ?: return false
            val probed = video.bitDepth != null || video.frameRate != null || video.dolbyVisionProfile != null || video.bitrateKbps != null
            return info.container == "MKV" && probed
        }

        /** Tables, columns and indexes added in v12, as in Room's exported schema. */
        private fun createTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `credits` (`itemId` INTEGER NOT NULL, `job` TEXT NOT NULL, `ord` INTEGER NOT NULL, " +
                    "`personKey` TEXT NOT NULL, `name` TEXT NOT NULL, `foldedName` TEXT NOT NULL, `character` TEXT, " +
                    "`profilePath` TEXT, PRIMARY KEY(`itemId`, `job`, `ord`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_credits_personKey_itemId` ON `credits` (`personKey`, `itemId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_credits_job_personKey` ON `credits` (`job`, `personKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_credits_foldedName` ON `credits` (`foldedName`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `saga_parts` (`collectionId` INTEGER NOT NULL, `tmdbId` INTEGER NOT NULL, " +
                    "`ord` INTEGER NOT NULL, `title` TEXT NOT NULL, `releaseDate` TEXT, `overview` TEXT, `posterPath` TEXT, " +
                    "`backdropPath` TEXT, PRIMARY KEY(`collectionId`, `tmdbId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_saga_parts_tmdbId` ON `saga_parts` (`tmdbId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `search_words` (`kind` TEXT NOT NULL, `word` TEXT NOT NULL, `ref` TEXT NOT NULL, " +
                    "PRIMARY KEY(`kind`, `word`, `ref`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_words_kind_ref` ON `search_words` (`kind`, `ref`)")
            for (column in listOf("edition TEXT", "versionOf INTEGER", "versionChoice INTEGER", "votes INTEGER", "creditsAt INTEGER")) {
                db.execSQL("ALTER TABLE items ADD COLUMN $column")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_tmdbId` ON `items` (`kind`, `tmdbId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_versionOf` ON `items` (`versionOf`)")
        }

        /** The v11 cast table and film directors as name-keyed credits ([PersonKeys]). */
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

        /** Editions from file names ([FilenameParser.edition]); the NFO's come with the backfill. */
        private fun writeEditions(db: SupportSQLiteDatabase) {
            val editions = db.query("SELECT id, filePath FROM items WHERE kind = 'MOVIE' AND filePath IS NOT NULL").use { rows ->
                buildList {
                    while (rows.moveToNext()) FilenameParser.edition(rows.getString(1))?.let { add(rows.getLong(0) to it) }
                }
            }
            for ((id, edition) in editions) db.execSQL("UPDATE items SET edition = ? WHERE id = ?", arrayOf<Any>(edition, id))
        }

        private fun titleWords(db: SupportSQLiteDatabase): List<SearchWordEntity> =
            db.query("SELECT id, title, originalTitle FROM items WHERE kind IN ('MOVIE', 'SERIES')").use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val original = if (rows.isNull(2)) null else rows.getString(2)
                        addAll(SearchWords.rows(SearchKind.TITLE, rows.getLong(0).toString(), rows.getString(1), original))
                    }
                }
            }

        private fun sagaWords(db: SupportSQLiteDatabase): List<SearchWordEntity> =
            db.query("SELECT id, name FROM collections").use { rows ->
                buildList {
                    while (rows.moveToNext()) addAll(SearchWords.rows(SearchKind.SAGA, rows.getLong(0).toString(), rows.getString(1)))
                }
            }

        /**
         * Folds sort keys of [table] ([MetadataMerge.fold]) and strips a glued l' ("l'arnaque"). Articles are not
         * stripped again, as "la land" (from "La La Land") would become "land".
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
         * v6 → v7: seals plain-text passwords and adds the indexes lists sort and filter on. If the Keystore cannot
         * seal, the password stays plain (it still works) and is sealed at a later launch ([sealPlainPasswords]).
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
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
            MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
            MIGRATION_20_21,
        )

        fun create(context: Context, secrets: Secrets): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, NAME)
                .addTypeConverter(SecretConverter(secrets))
                .addMigrations(*migrations(secrets))
                .build()

        /**
         * Records the key versions the passwords were sealed with ([Secrets.recordKeys]), then seals those left plain
         * by v6 → v7 when the Keystore failed, as scans never rewrite them. Runs at each launch; failures are logged
         * and retried at the next one.
         */
        suspend fun sealPlainPasswords(dao: SourceDao, secrets: Secrets) {
            suspendRunCatching {
                withContext(Dispatchers.IO) {
                    val passwords = dao.storedPasswords()
                    secrets.recordKeys(SecretStore.FOLDERS, passwords.map { it.password })
                    val plain = passwords.filterNot { secrets.isSealed(it.password) }
                    for (stored in plain) dao.sealPassword(stored.id, stored.password, secrets.seal(stored.password))
                    plain.size
                }
            }
                .onSuccess { if (it > 0) Log.i(TAG, "$it passwords left in plain text are sealed") }
                .onFailure { Log.w(TAG, "Passwords left in plain text wait for the next launch", it) }
        }

        private const val TAG = "Library"

        /** Rows decoded at a time by [markTracksInOrder]. */
        private const val MARK_BATCH = 500

        /** l' or l’ glued to the first word of a key; v9 stripped it only before a space. */
        private val GLUED_L = Regex("""^l['’](?=\S)""")
    }
}
