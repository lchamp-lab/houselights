package app.houselights.providers.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.houselights.providers.local.SchemaHistory.insertAt
import app.houselights.testing.testSecrets
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Version 25 keeps each title's NFO text in a table of its own ([NfoEntity]), a film's, a show's and an episode's, none
 * for a title without one or with an empty one; every other column of every row stays as it was, Room's schema checked
 * on open, and a title's id is never given again, though its row was the last one.
 */
@RunWith(RobolectricTestRunner::class)
class NfoMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val secrets = testSecrets()
    private val name = "migration-nfo-test.db"
    private var opened: LibraryDatabase? = null

    @After
    fun close() {
        opened?.close()
        context.deleteDatabase(name)
    }

    @Test
    fun version25KeepsTheNfoTextsApartAndEveryOtherColumnAsItWas() = runTest {
        context.deleteDatabase(name)
        SchemaHistory.create(context, name, 24) { fill() }
        // As version 24 left it, before Room opens it.
        val path = context.getDatabasePath(name).path
        val before = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { file ->
            rows(file.rawQuery(ALL_ITEMS, null)).mapValues { (_, row) -> row - "nfo" }
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addTypeConverter(SecretConverter(secrets))
            .addMigrations(*LibraryDatabase.migrations(secrets))
            .build()
            .also { opened = it }
        val dao = db.dao()

        assertEquals(TEXTS, dao.nfosOf(listOf(1L, 2L, 3L, 10L, 11L, 12L)))
        assertEquals(ItemNfo(1, "Dune.nfo", TEXTS[1]), dao.nfoOf(1))
        assertEquals(before, rows(db.openHelper.readableDatabase.query(ALL_ITEMS)))
        assertEquals("Firefly/S01E01.nfo" to true, dao.item(12)?.let { it.nfoPath to it.played })
        val next = dao.insert(ItemEntity(kind = ItemKind.MOVIE, sourceId = 1, title = "Neuf", sortTitle = "neuf", addedAt = 9))
        assertEquals("after the last id given", 51L, next)
    }

    /** Films, one with an NFO, one without, one with an empty one; a show and an episode with theirs, a season without. */
    private fun SupportSQLiteDatabase.fill() {
        fun row(values: String, more: Map<String, Any?> = emptyMap()) = insertAt(24, "items", columns(values) + more)
        insertAt(24, "sources", columns("id=1; name=Films; kind=MOVIES; protocol=NFS; host=nas"))
        insertAt(24, "sources", columns("id=2; name=Séries; kind=SHOWS; protocol=NFS; host=nas"))
        row(
            "id=1; kind=MOVIE; sourceId=1; title=Dune; sortTitle=dune; filePath=Dune.mkv; nfoPath=Dune.nfo; played=1; " +
                "positionMs=600000; favorite=1; lastPlayedAt=5; userTmdbId=438631; userPosterPath=file:/dune.jpg; " +
                "versionChoice=3; missingSince=7; matchState=MATCHED; sidecarKey=nfo:5:6:Dune.nfo; addedAt=4",
            mapOf("nfo" to TEXTS[1]),
        )
        row("id=2; kind=MOVIE; sourceId=1; title=Heat; sortTitle=heat; filePath=Heat.mkv")
        row("id=3; kind=MOVIE; sourceId=1; title=Vide; sortTitle=vide; filePath=Vide.mkv; nfoPath=Vide.nfo", mapOf("nfo" to ""))
        row(
            "id=10; kind=SERIES; sourceId=2; title=Firefly; sortTitle=firefly; tmdbId=1437; nfoPath=Firefly/tvshow.nfo; " +
                "userEpisodeOrder=$DVD; episodeOrder=$DVD",
            mapOf("nfo" to TEXTS[10]),
        )
        row("id=11; kind=SEASON; sourceId=2; parentId=10; seriesId=10; title=; sortTitle=001")
        row(
            "id=12; kind=EPISODE; sourceId=2; parentId=11; seriesId=10; title=Serenity; sortTitle=serenity; seasonNumber=1; " +
                "episodeNumber=1; filePath=Firefly/S01E01.mkv; nfoPath=Firefly/S01E01.nfo; played=1; tmdbSeason=1; tmdbEpisode=11",
            mapOf("nfo" to TEXTS[12]),
        )
        // The last title added, then removed.
        row("id=50; kind=MOVIE; sourceId=1; title=Parti; sortTitle=parti; filePath=Parti.mkv")
        execSQL("DELETE FROM items WHERE id = 50")
    }

    /** Columns as `name=value; …`, a value in digits an integer. */
    private fun columns(text: String): Map<String, Any?> = text.split("; ").associate { column ->
        val (name, value) = column.split('=', limit = 2)
        name to (value.toLongOrNull() ?: value)
    }

    /** The rows [cursor] reads ([ALL_ITEMS]), by id, each column by name as SQLite holds it. */
    private fun rows(cursor: Cursor): Map<Long, Map<String, Any?>> = cursor.use { c ->
        buildMap {
            while (c.moveToNext()) {
                val row = c.columnNames.withIndex().associate { (i, column) ->
                    column to when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        else -> c.getString(i)
                    }
                }
                put(row.getValue("id") as Long, row)
            }
        }
    }

    private companion object {
        const val ALL_ITEMS = "SELECT * FROM items ORDER BY id"
        const val DVD = "5b5a9ef3c3a3685a5c000c5c"
        val TEXTS = mapOf(
            1L to "<movie><title>Dune</title></movie>",
            10L to "<tvshow><title>Firefly</title><displayorder>dvd</displayorder></tvshow>",
            12L to "<episodedetails><title>Serenity</title></episodedetails>",
        )
    }
}
