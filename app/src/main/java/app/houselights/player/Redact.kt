package app.houselights.player

import android.net.Uri

/** Hides credentials before a URI is written to logs, and tells a URI that carries some. */
object Redact {
    private val USER_INFO = Regex("""(://)[^/\s]*@""")
    private val SECRET_PARAMS = Regex("""(?i)([?&](?:api_key|apikey|token|x-plex-token|password)=)[^&\s]+""")

    /** Everything from "://" to the last @: past it, no password reaches. */
    private val UP_TO_LAST_AT = Regex("""(://).*@""")
    private const val MASK = "***"

    /**
     * True when the URI carries a user name or password (smb://user:password@host/...): one the
     * pattern finds, or one typed raw in a path mapping, as the player's [Uri] reads it ([typedRaw]).
     */
    fun hasUserInfo(uri: String): Boolean = USER_INFO.containsMatchIn(uri) || typedRaw(uri, ours = null)

    /** True when the URI carries a secret: a user's password, or a token in its query (Jellyfin's api_key). */
    fun hasCredentials(uri: String): Boolean = hasUserInfo(uri) || SECRET_PARAMS.containsMatchIn(uri)

    fun uri(uri: String): String {
        val masked = uri.replace(USER_INFO, "$1$MASK@").replace(SECRET_PARAMS, "$1$MASK")
        // A password typed raw may hold what ends the pattern (a space, a slash, an @): all up to the last @ goes.
        return if (typedRaw(masked, ours = MASK)) masked.replace(UP_TO_LAST_AT, "$1$MASK@") else masked
    }

    /** An @ with a slash after it: "@nas/share", the host a password typed raw ends at. */
    private val AT_THEN_PATH = Regex("""@[^/]*/""")

    /**
     * A user's part the pattern misses, typed raw in a path mapping: "user:p w@nas" holds a space,
     * which the player's [Uri] still reads as a user's part; "user:p/ss@nas" ends at its slash, as
     * "user:p", a port that is no number, or as "user:12" before a first segment holding the @, or
     * before an @ that a slash follows further on ("user:2024/10/17@nas/share", "user:12/3?4@nas/share").
     * A share named with an @ behind a port, or a folder, is taken for one too: a password is never
     * missed. [ours] is the user's part [uri] already writes in its place.
     */
    private fun typedRaw(uri: String, ours: String?): Boolean {
        val parsed = Uri.parse(uri)
        val authority = parsed.encodedAuthority ?: return false
        if ('@' in authority) return authority.substringBeforeLast('@') != ours
        // An IPv6 host is written in brackets, colons and all.
        val hostAndPort = authority.substringAfterLast(']')
        if (':' !in hostAndPort) return false
        val port = hostAndPort.substringAfter(':')
        val rest = uri.substringAfter("://").removePrefix(authority)
        return !port.all(Char::isDigit) ||
            parsed.encodedPath.orEmpty().removePrefix("/").substringBefore('/').contains('@') ||
            AT_THEN_PATH.containsMatchIn(rest)
    }
}
