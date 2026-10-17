package app.houselights.core

import java.io.IOException

/** What was asked for is not in the library (anymore): removed by a scan, or an id from elsewhere. */
class NotFoundException(what: String) : NoSuchElementException("$what not found")

/** Searching TMDB or listing its images needs a key, and there is none. */
class NoTmdbKeyException : IllegalStateException("No TMDB key")

/**
 * TMDB could not be reached (no network, DNS, timeout), now or a moment ago, or stayed busy when
 * asked again (429, a gateway failure).
 */
open class TmdbUnreachableException(cause: Throwable?, message: String = "TMDB is unreachable") : IOException(message, cause)

/**
 * TMDB refused the API key (401, 403): to be changed in the settings, not a server to sign in to again.
 * An [IOException], as anything an OkHttp interceptor throws must be.
 */
class TmdbKeyRefusedException : IOException("TMDB refused the API key")

/**
 * TMDB has no entry at the id asked (404): removed, or merged into another, since a search found it.
 * TMDB's to say, not a title gone from the library ([NotFoundException]). An [IOException], as
 * TmdbClient's OkHttp interceptor throws it.
 */
class TmdbNotFoundException : IOException("TMDB has no such entry")
