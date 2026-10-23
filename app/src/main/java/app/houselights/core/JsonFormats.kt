package app.houselights.core

import android.util.Log
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement

/**
 * The app's JSON format, ignoring unknown keys (a newer server's fields). Settings and media info are written
 * with it too, so an option added here changes what they store; Jellyfin and Trakt use [serverJson], TMDB its own.
 */
val lenientJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Jellyfin's and Trakt's JSON: unknown keys ignored, a null read as the field's default, nulls left out of requests
 * and defaults sent.
 */
val serverJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * [elements] decoded one at a time: one that cannot be is left out and logged as [what], with its index and the path
 * of the field at fault, so that one entry this version cannot read does not lose the others.
 */
fun <T> Json.decodeEach(serializer: KSerializer<T>, elements: List<JsonElement>, what: String): List<T> {
    var skipped = 0
    val decoded = elements.mapIndexedNotNull { index, element ->
        try {
            decodeFromJsonElement(serializer, element)
        } catch (e: SerializationException) {
            if (++skipped <= LOGGED_SKIPS) Log.w(TAG, "$what: entry $index left out, ${unreadable(whyNot(serializer, element) ?: e)}")
            null
        }
    }
    if (skipped > LOGGED_SKIPS) Log.w(TAG, "$what: $skipped of ${elements.size} entries left out")
    return decoded
}

/** Why [element] cannot be decoded, as the text decoder says it: unlike the tree's, it names a nested field's whole path. */
private fun <T> Json.whyNot(serializer: KSerializer<T>, element: JsonElement): SerializationException? = try {
    decodeFromString(serializer, element.toString())
    null
} catch (e: SerializationException) {
    e
}

/**
 * An answer that could not be read, for the log: a stable label, the path of the field at fault and the fields missing;
 * never the input its message quotes, which may hold a token.
 */
@OptIn(ExperimentalSerializationApi::class)
fun unreadable(e: SerializationException): String = buildString {
    append("unreadable answer")
    e.message?.substringBefore(JSON_INPUT)?.let { PATH.find(it) }?.let { append(" at ").append(it.groupValues[1]) }
    (e as? MissingFieldException)?.missingFields?.let { append(", missing ").append(it.joinToString()) }
}

/** A list read entry by entry ([decodeEach]): an incomplete entry is left out rather than failing the whole answer. */
class EntryByEntry<T>(private val entry: KSerializer<T>) : KSerializer<List<T>> {
    private val list = ListSerializer(entry)

    override val descriptor: SerialDescriptor = list.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = list.serialize(encoder, value)

    @OptIn(ExperimentalSerializationApi::class) // serialName: the entries' name in the log.
    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return list.deserialize(decoder)
        val name = entry.descriptor.serialName.substringAfterLast('.')
        val array = json.decodeJsonElement() as? JsonArray ?: throw SerializationException("Expected a list of $name")
        return json.json.decodeEach(entry, array, name)
    }
}

private const val TAG = "Json"

/** Entries logged one by one; past them, only their count. */
private const val LOGGED_SKIPS = 3

/** The path ends the message, before the input it quotes: a value quoted earlier in it may read "at path" too. */
private val PATH = Regex("""at path: (\S+)\s*$""")
private const val JSON_INPUT = "JSON input"
