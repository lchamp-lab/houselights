package app.houselights.settings

/** « Taille du texte »: a step on the system's text size, Android's own on either side of Standard. */
enum class TextSize(val step: Float) {
    /** Everything 15 % smaller, titles included. */
    COMPACT(COMPACT_STEP),
    STANDARD(1f),

    /** Android's next size, on its curve: the smaller sizes grow most, the titles keep theirs. */
    LARGE(LARGE_STEP),
}

/** Android's text sizes either side of its default. */
private const val COMPACT_STEP = 0.85f
private const val LARGE_STEP = 1.15f
