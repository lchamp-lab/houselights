package app.houselights.settings

/** « Taille du texte »: a step on the system's text size, Android's own on either side of Standard. */
enum class TextSize(val step: Float) {
    /** Everything 15 % smaller, titles included. */
    COMPACT(0.85f),
    STANDARD(1f),

    /** Android's next size, on its curve: the smaller sizes grow most, the titles keep theirs. */
    LARGE(1.15f),
}
