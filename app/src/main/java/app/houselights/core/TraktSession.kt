package app.houselights.core

import kotlinx.serialization.Serializable

/** The Trakt account connected on this device, kept sealed in the settings. */
@Serializable
data class TraktSession(
    val accessToken: String,
    /** Single-use: each renewal brings the next one. */
    val refreshToken: String,
    /** When [accessToken] stops working, in seconds since the epoch. */
    val expiresAt: Long,
    /** The account's name on Trakt; null until read. */
    val username: String? = null,
    /** Trakt refused to renew the tokens: the account must be connected again. */
    val ended: Boolean = false,
) {
    /** Never the tokens: a log line may quote the settings. */
    override fun toString() =
        "TraktSession(accessToken=***, refreshToken=***, expiresAt=$expiresAt, username=$username, ended=$ended)"
}
