package app.houselights.providers.local

/**
 * What an id of the local library names, read once where the provider receives it. Films, shows,
 * seasons and episodes are rows of the library ("12"); a part of a film in several files is its row and
 * the part's number ("12p2"); collections have a table of their own and a prefix ("c12"), as both share
 * the id space of navigation.
 */
sealed interface LocalId {
    /** [part]: of a film in several files, the one to play (1 for the first); null for the title itself. */
    data class Item(val key: Long, val part: Int? = null) : LocalId

    data class Collection(val key: Long) : LocalId

    companion object {
        private const val COLLECTION = "c"
        private const val PART = 'p'

        /** null for an id this library never gave out. */
        fun parse(id: String): LocalId? = when {
            id.startsWith(COLLECTION) -> id.removePrefix(COLLECTION).toLongOrNull()?.let(::Collection)
            PART in id -> {
                val key = id.substringBefore(PART).toLongOrNull()
                val part = id.substringAfter(PART).toIntOrNull()?.takeIf { it > 0 }
                if (key == null || part == null) null else Item(key, part)
            }
            else -> id.toLongOrNull()?.let { Item(it) }
        }

        fun ofCollection(key: Long): String = "$COLLECTION$key"

        fun ofPart(key: Long, part: Int): String = "$key$PART$part"
    }
}
