package app.houselights.player

/** Hides credentials before a URI is written to logs. */
object Redact {
    private val USER_INFO = Regex("""(://)[^/\s]*@""")
    private val SECRET_PARAMS = Regex("""(?i)([?&](?:api_key|apikey|token|x-plex-token|password)=)[^&\s]+""")

    /** True when the URI carries a user name or password (smb://user:password@host/...). */
    fun hasUserInfo(uri: String): Boolean = USER_INFO.containsMatchIn(uri)

    fun uri(uri: String): String = uri
        .replace(USER_INFO, "$1***@")
        .replace(SECRET_PARAMS, "$1***")
}
