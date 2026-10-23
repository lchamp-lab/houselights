package app.houselights.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** A server's list read entry by entry: what cannot be read is left out and logged by where, never by what it says. */
@RunWith(RobolectricTestRunner::class)
class JsonFormatsTest {
    @Serializable
    private data class Entry(val id: Int, val name: String? = null)

    @Before
    fun clear() = ShadowLog.clear()

    private fun told() = ShadowLog.getLogsForTag("Json").map { it.msg }

    private fun decoded(json: String) =
        serverJson.decodeEach(Entry.serializer(), serverJson.parseToJsonElement(json).jsonArray, "Test list")

    @Test
    fun anEntryThatCannotBeReadIsLeftOutTheOthersKept() {
        assertEquals(listOf(Entry(1), Entry(4, "d")), decoded("""[{"id":1},{"id":"s3cret"},{"name":"c"},{"id":4,"name":"d"}]"""))
        assertEquals(
            listOf(
                "Test list: entry 1 left out, unreadable answer at $.id",
                "Test list: entry 2 left out, unreadable answer at $, missing id",
            ),
            told(),
        )
    }

    @Test
    fun manyEntriesLeftOutAreCountedPastTheFirst() {
        assertEquals(listOf(Entry(9)), decoded("""[{},{},{},{},{},{"id":9}]"""))
        assertEquals(4, told().size)
        assertEquals("Test list: 5 of 6 entries left out", told().last())
    }

    @Test
    fun anAnswerIsLoggedByThePathAtFaultNeverByTheInputItQuotes() {
        val e = SerializationException(
            "Unexpected JSON token at offset 9: Failed to parse type 'float' for input 'at path: x' at path: $.progress\n" +
                "JSON input: {\"access_token\":\"s3cret\",\"at path: s3cret\":1}",
        )
        assertEquals("unreadable answer at $.progress", unreadable(e))
        val quoted = SerializationException("Expected an object\nJSON input: {\"error\":\"at path: s3cret\"}")
        assertEquals("unreadable answer", unreadable(quoted))
    }
}
