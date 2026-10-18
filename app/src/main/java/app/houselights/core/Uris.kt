package app.houselights.core

/** Escapes what would end or alter a URI path ("%", "#", "?"); spaces and accents stay, as the Zidoo player expects. */
fun escapeUriPath(path: String): String = path.replace("%", "%25").replace("#", "%23").replace("?", "%3F")

/** Escapes "#" and "?" in [uri]'s path, but not "%": a server administrator's network path may be escaped already. */
fun escapeUriPathEnds(uri: String): String {
    val authorityStart = uri.indexOf("://").takeIf { it >= 0 }?.plus("://".length) ?: return uri
    val pathStart = uri.indexOf('/', authorityStart).takeIf { it >= 0 } ?: return uri
    return uri.substring(0, pathStart) + uri.substring(pathStart).replace("#", "%23").replace("?", "%3F")
}

/** True for an address another app can fetch (http or https), not a file of this app's own. */
fun isWebUrl(url: String): Boolean = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
