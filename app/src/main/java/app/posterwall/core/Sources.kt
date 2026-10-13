package app.posterwall.core

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
    ) : SourceConfig
}
