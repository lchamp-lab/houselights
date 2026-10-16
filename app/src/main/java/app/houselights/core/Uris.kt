package app.houselights.core

/**
 * Escapes the characters that would end or alter a URI path ("%", "#", "?"). Spaces and accents stay
 * as they are, as the Zidoo player has always received them.
 */
fun escapeUriPath(path: String): String = path.replace("%", "%25").replace("#", "%23").replace("?", "%3F")

/**
 * Escapes what would end the path of [uri] ("#", "?") after its authority, but not "%": a network path
 * written by a server's administrator may already be escaped.
 */
fun escapeUriPathEnds(uri: String): String {
    val authorityStart = uri.indexOf("://").takeIf { it >= 0 }?.plus("://".length) ?: return uri
    val pathStart = uri.indexOf('/', authorityStart).takeIf { it >= 0 } ?: return uri
    return uri.substring(0, pathStart) + uri.substring(pathStart).replace("#", "%23").replace("?", "%3F")
}

/** True for an address another app can fetch (http or https), not a file of this app's own. */
fun isWebUrl(url: String): Boolean = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
