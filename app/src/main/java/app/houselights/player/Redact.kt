package app.houselights.player

/** Hides credentials before a URI is written to logs, and tells a URI that carries some. */
object Redact {
    private val USER_INFO = Regex("""(://)[^/\s]*@""")
    private val SECRET_PARAMS = Regex("""(?i)([?&](?:api_key|apikey|token|x-plex-token|password)=)[^&\s]+""")

    /** True when the URI carries a user name or password (smb://user:password@host/...). */
    fun hasUserInfo(uri: String): Boolean = USER_INFO.containsMatchIn(uri)

    /** True when the URI carries a secret: a user's password, or a token in its query (Jellyfin's api_key). */
    fun hasCredentials(uri: String): Boolean = hasUserInfo(uri) || SECRET_PARAMS.containsMatchIn(uri)

    fun uri(uri: String): String = uri
        .replace(USER_INFO, "$1***@")
        .replace(SECRET_PARAMS, "$1***")
}
