package app.houselights.core

/** What was asked for is not in the library (anymore): removed by a scan, or an id from elsewhere. */
class NotFoundException(what: String) : NoSuchElementException("$what not found")

/** Searching TMDB or listing its images needs a key, and there is none. */
class NoTmdbKeyException : IllegalStateException("No TMDB key")
