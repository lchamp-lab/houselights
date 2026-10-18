package app.houselights.player

import android.net.Uri

/** Masks credentials in a URI before it is logged, and detects URIs that carry them. */
object Redact {
    private val USER_INFO = Regex("""(://)[^/\s]*@""")
    private val SECRET_PARAMS = Regex("""(?i)([?&](?:api_key|apikey|token|x-plex-token|password)=)[^&\s]+""")

    /** Everything from "://" to the last @; no password extends past it. */
    private val UP_TO_LAST_AT = Regex("""(://).*@""")
    private const val MASK = "***"

    /** True when the URI carries user info (smb://user:password@host/), even typed unescaped ([typedRaw]). */
    fun hasUserInfo(uri: String): Boolean = USER_INFO.containsMatchIn(uri) || typedRaw(uri, ours = null)

    /** True when the URI carries a secret: a password, or a token in its query (Jellyfin's api_key). */
    fun hasCredentials(uri: String): Boolean = hasUserInfo(uri) || SECRET_PARAMS.containsMatchIn(uri)

    fun uri(uri: String): String {
        val masked = uri.replace(USER_INFO, "$1$MASK@").replace(SECRET_PARAMS, "$1$MASK")
        // An unescaped password may contain what ends the pattern (a space, a slash, an @): mask up to the last @.
        return if (typedRaw(masked, ours = MASK)) masked.replace(UP_TO_LAST_AT, "$1$MASK@") else masked
    }

    /** An @ followed by a slash ("@nas/share"): the host where an unescaped password ends. */
    private val AT_THEN_PATH = Regex("""@[^/]*/""")

    /**
     * User info the pattern misses, typed unescaped in a path mapping as [Uri] still parses it: "user:p w@nas"
     * (a space), or "user:p/ss@nas" cut at a slash into a non-numeric port, or a port before a segment holding
     * an @. A share named with an @ after a port is flagged too: a password is never missed. [ours] is the mask.
     */
    private fun typedRaw(uri: String, ours: String?): Boolean {
        val parsed = Uri.parse(uri)
        val authority = parsed.encodedAuthority ?: return false
        if ('@' in authority) return authority.substringBeforeLast('@') != ours
        // An IPv6 host is bracketed and contains colons.
        val hostAndPort = authority.substringAfterLast(']')
        if (':' !in hostAndPort) return false
        val port = hostAndPort.substringAfter(':')
        val rest = uri.substringAfter("://").removePrefix(authority)
        return !port.all(Char::isDigit) ||
            parsed.encodedPath.orEmpty().removePrefix("/").substringBefore('/').contains('@') ||
            AT_THEN_PATH.containsMatchIn(rest)
    }
}
