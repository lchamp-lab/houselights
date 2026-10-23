package app.houselights.player

import android.net.Uri

/** Masks credentials in a URI before it is logged or shown, and detects URIs that carry them. */
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

    /**
     * [typed], edited from [stored] shown through [uri]: unchanged, it is [stored]; with the user info's mask left in
     * place, the mask stands for [stored]'s user and password. Anything else is taken as typed.
     */
    fun unmask(typed: String, stored: String): String {
        val masked = uri(stored)
        if (typed == masked) return stored
        val mask = "://$MASK@"
        val at = masked.indexOf(mask).takeIf { it >= 0 } ?: return typed
        // [stored] is [masked] with its user info in place of the mask: the same up to "://", the same after the @.
        val start = masked.substring(0, at) + "://"
        val end = "@" + masked.substring(at + mask.length)
        val found = stored.startsWith(start) && stored.endsWith(end) && stored.length >= start.length + end.length
        if (!found) return typed
        return typed.replaceFirst(mask, "://${stored.substring(start.length, stored.length - end.length)}@")
    }

    /**
     * [text], a log line or any free text, with its secrets masked: the user info of every URI, up to the last @
     * of its authority or, typed raw, of the URI ("smb://u:p/ss@nas" → "smb://•••@nas"); tokens, keys, passwords
     * and codes as query or header values, JSON fields or `name=value`; a Bearer or Basic credential.
     */
    fun text(text: String): String = TEXT_SECRETS.fold(text) { masked, (pattern, replacement) -> masked.replace(pattern, replacement) }

    /** What [text] writes in place of a secret. */
    const val TEXT_MASK = "•••"

    private const val SCHEME = """\b([a-zA-Z][a-zA-Z0-9+.\-]*://)"""

    /** Names whose value is a secret wherever it is written: Jellyfin's, Plex's and Trakt's tokens, keys and codes. */
    private const val SECRET_NAMES =
        "api_?key|access_?token|refresh_?token|id_token|token|x-emby-token|x-mediabrowser-token|x-plex-token|" +
            "password|passwd|pw|client_secret|device_code|user_code"

    private val TEXT_SECRETS = listOf(
        // The authority's user info; then one typed raw, where a slash, ? or # in the password ended the authority.
        Regex("""$SCHEME[^\s/?#]*@""") to "$1$TEXT_MASK@",
        Regex("""$SCHEME[^\s/?#@:]+:[^\s@]*@""") to "$1$TEXT_MASK@",
        // A JSON field ("access_token": "…"); an OAuth code only there or in a query, as "code" names much else.
        Regex("""(?i)"($SECRET_NAMES|code)"(\s*:\s*)"[^"]*"""") to "\"$1\"$2\"$TEXT_MASK\"",
        Regex("""(?i)([?&]code=)[^\s&#"']+""") to "$1$TEXT_MASK",
        // name=value in a query, a form or a header's parameters (Token="…" in Jellyfin's Authorization).
        Regex("""(?i)(?<![\w-])($SECRET_NAMES)(=)("?)[^\s&"',;)}\]]+""") to "$1$2$3$TEXT_MASK",
        // A header carrying a token as its value.
        Regex("""(?i)\b(x-emby-token|x-mediabrowser-token|x-plex-token)(\s*:\s*)\S+""") to "$1$2$TEXT_MASK",
        Regex("""(?i)\b(Bearer\s+)[\w\-.~+/]+=*""") to "$1$TEXT_MASK",
        Regex("""(?i)\b(Authorization\s*:\s*Basic\s+)\S+""") to "$1$TEXT_MASK",
    )

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
