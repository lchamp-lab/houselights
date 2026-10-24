package app.houselights.core

import java.io.IOException

/** The requested item is not in the library: removed by a scan, or an id from elsewhere. */
class NotFoundException(what: String) : NoSuchElementException("$what not found")

/** Searching TMDB or listing its images needs a key, and there is none. */
class NoTmdbKeyException : IllegalStateException("No TMDB key")

/** TMDB unreachable (network, DNS, timeout), now or recently, or still busy after retries (429, gateway failure). */
open class TmdbUnreachableException(cause: Throwable?, message: String = "TMDB is unreachable") : IOException(message, cause)

/** TLS refused TMDB's certificate, most often for the device's clock: worded as a certificate ([FailureKind.CERTIFICATE]). */
class TmdbCertificateException(cause: Throwable) : TmdbUnreachableException(cause, "TMDB's certificate is refused")

/** TMDB refused the API key (401, 403), fixed in Settings; an [IOException], as OkHttp interceptors require. */
class TmdbKeyRefusedException : IOException("TMDB refused the API key")

/**
 * TMDB has no entry for the id (404): removed or merged since a search found it, unlike a library miss
 * ([NotFoundException]). An [IOException] because TmdbClient's OkHttp interceptor throws it.
 */
class TmdbNotFoundException : IOException("TMDB has no such entry")
