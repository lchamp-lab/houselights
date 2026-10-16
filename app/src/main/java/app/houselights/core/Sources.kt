package app.houselights.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Persisted connection settings of the active library source. */
@Serializable
sealed interface SourceConfig {
    /** Library built by the app itself from SMB shares (see providers.local). */
    @Serializable
    @SerialName("local")
    data object Local : SourceConfig

    @Serializable
    @SerialName("jellyfin")
    data class Jellyfin(
        val serverUrl: String,
        val serverName: String?,
        val userId: String,
        val userName: String?,
        val accessToken: String,
    ) : SourceConfig {
        /** Never the token: a log line or an error message may quote the source. */
        override fun toString() =
            "Jellyfin(serverUrl=$serverUrl, serverName=$serverName, userId=$userId, userName=$userName, accessToken=***)"
    }
}
