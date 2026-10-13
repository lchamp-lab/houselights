package app.posterwall.player

/** Hides credentials before a URI is written to logs. */
object Redact {
    private val USER_INFO = Regex("""(://)[^/\s]*@""")
    private val SECRET_PARAMS = Regex("""(?i)([?&](?:api_key|apikey|token|x-plex-token|password)=)[^&\s]+""")

    fun uri(uri: String): String = uri
        .replace(USER_INFO, "$1***@")
        .replace(SECRET_PARAMS, "$1***")
}
