package app.houselights.providers.local

/**
 * Parsed form of a local library id, decoded where the provider receives it. Films, shows, seasons and episodes
 * are item rows ("12"); a part of a multi-file film adds the part number ("12p2"); collections live in their own
 * table and take a prefix ("c12"), since all share the navigation id space.
 */
sealed interface LocalId {
    /** [part] is the file to play of a multi-file film (1 for the first); null for the title itself. */
    data class Item(val key: Long, val part: Int? = null) : LocalId

    data class Collection(val key: Long) : LocalId

    companion object {
        private const val COLLECTION = "c"
        private const val PART = 'p'

        /** Null for an id this library cannot have issued. */
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
