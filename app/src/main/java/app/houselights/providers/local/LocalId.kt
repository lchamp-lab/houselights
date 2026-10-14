package app.houselights.providers.local

/**
 * What an id of the local library names, read once where the provider receives it. Films, shows,
 * seasons and episodes are rows of the library ("12"); collections have a table of their own and a
 * prefix ("c12"), as both share the id space of navigation.
 */
sealed interface LocalId {
    data class Item(val key: Long) : LocalId

    data class Collection(val key: Long) : LocalId

    companion object {
        private const val COLLECTION = "c"

        /** null for an id this library never gave out. */
        fun parse(id: String): LocalId? =
            if (id.startsWith(COLLECTION)) id.removePrefix(COLLECTION).toLongOrNull()?.let(::Collection)
            else id.toLongOrNull()?.let(::Item)

        fun ofCollection(key: Long): String = "$COLLECTION$key"
    }
}
