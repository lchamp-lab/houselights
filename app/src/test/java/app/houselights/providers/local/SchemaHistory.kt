package app.houselights.providers.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Creates a database as an older app version did, from Room's exported schema (app/schemas), for migrations. */
object SchemaHistory {
    private val dir = File("schemas/${LibraryDatabase::class.java.name}")

    fun create(context: Context, name: String, version: Int, fill: SupportSQLiteDatabase.() -> Unit) {
        val schema = Json.parseToJsonElement(dir.resolve("$version.json").readText()).jsonObject["database"]!!.jsonObject
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
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

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content
}
