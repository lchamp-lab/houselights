package app.houselights.core

/** How many films of a saga the library needs before it shows them as a collection. */
object CollectionThreshold {
    /** The default: a saga shows once two of its films are here. */
    const val TWO_FILMS = 2

    /** Every saga, even with a single film. */
    const val ANY = 1

    /** No collections at all. */
    const val HIDDEN = 0

    const val DEFAULT = TWO_FILMS
}
