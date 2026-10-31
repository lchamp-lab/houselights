package app.houselights.providers.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Creates a database as an older app version did, from Room's exported schema (app/schemas), for migrations. */
object SchemaHistory {
    private val dir = File("schemas/${LibraryDatabase::class.java.name}")

    /** A column of an exported schema: its SQLite affinity, whether it may be null, and whether it has a default. */
    class Column(val affinity: String, val notNull: Boolean, val hasDefault: Boolean) {
        /** A row must give it. */
        val required get() = notNull && !hasDefault
    }

    fun create(context: Context, name: String, version: Int, fill: SupportSQLiteDatabase.() -> Unit) {
        val schema = schema(version)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (entity in entities(schema)) {
                        val table = entity.string("tableName")
                        db.execSQL(entity.string("createSql").replace("\${TABLE_NAME}", table))
                        entity["indices"]?.jsonArray?.forEach {
                            db.execSQL(it.jsonObject.string("createSql").replace("\${TABLE_NAME}", table))
                        }
                    }
                    schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                    db.fill()
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()
    }

    /** The columns of [table] in schema [version], by name; none for a table the version did not have. */
    fun columns(version: Int, table: String): Map<String, Column> =
        entities(schema(version)).firstOrNull { it.string("tableName") == table }?.get("fields")?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .associate { field ->
                val notNull = field["notNull"]?.jsonPrimitive?.boolean == true
                field.string("columnName") to Column(field.string("affinity"), notNull, hasDefault = "defaultValue" in field)
            }

    /**
     * Writes into [table] of schema [version] the [values] it has a column for, its other required columns given the
     * empty value of their affinity (0 or ''), as a row of that version holds what its own code wrote.
     */
    fun SupportSQLiteDatabase.insertAt(version: Int, table: String, values: Map<String, Any?>) {
        val columns = columns(version, table)
        val row = values.filterKeys { it in columns } +
            columns.filter { (name, column) -> column.required && name !in values }.mapValues { (_, column) -> empty(column) }
        execSQL(
            "INSERT INTO `$table` (${row.keys.joinToString { "`$it`" }}) VALUES (${row.keys.joinToString { "?" }})",
            row.values.toTypedArray(),
        )
    }

    private fun empty(column: Column): Any = if (column.affinity == "TEXT") "" else 0L

    private val schemas = mutableMapOf<Int, JsonObject>()

    private fun schema(version: Int): JsonObject = schemas.getOrPut(version) {
        Json.parseToJsonElement(dir.resolve("$version.json").readText()).jsonObject["database"]!!.jsonObject
    }

    private fun entities(schema: JsonObject) = schema["entities"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content
}
