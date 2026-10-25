package app.houselights.providers.trakt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Trakt API types, named after its own. Dates are ISO 8601 in UTC, as Trakt writes them. The account's history and
// pauses hold what every app sent: their fields may be missing, so they are nullable, and an entry the sync cannot
// place is left out.

@Serializable
data class DeviceCodeRequest(@SerialName("client_id") val clientId: String)

/** Codes to sign in with: [userCode] is shown, [deviceCode] polled every [interval] seconds for [expiresIn]. */
@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("interval") val interval: Int,
) {
    /** Never the device code, which a poll exchanges for tokens. */
    override fun toString() = "DeviceCode(userCode=$userCode, verificationUrl=$verificationUrl, expiresIn=$expiresIn, interval=$interval)"
}

/** A client secret is sent only by an app that has one. Never the code nor the secret in [toString]. */
@Serializable
data class DeviceTokenRequest(
    @SerialName("code") val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
) {
    override fun toString() = "DeviceTokenRequest(code=***, clientId=$clientId, clientSecret=${clientSecret?.let { "***" }})"
}

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
) {
    override fun toString() =
        "RefreshRequest(refreshToken=***, clientId=$clientId, clientSecret=${clientSecret?.let { "***" }}, redirectUri=$redirectUri)"
}

@Serializable
data class RevokeRequest(
    @SerialName("token") val token: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
) {
    override fun toString() = "RevokeRequest(token=***, clientId=$clientId, clientSecret=${clientSecret?.let { "***" }})"
}

/** Why OAuth refused a request: invalid_grant for a refresh token Trakt refuses. */
@Serializable
data class OAuthError(@SerialName("error") val error: String? = null)

/** Tokens: the access token lasts [expiresIn] seconds from [createdAt] (seconds since the epoch). */
@Serializable
data class TraktTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("scope") val scope: String? = null,
) {
    override fun toString() = "TraktTokens(accessToken=***, refreshToken=***, expiresIn=$expiresIn, createdAt=$createdAt)"
}

@Serializable
data class UserSettings(@SerialName("user") val user: TraktUser)

@Serializable
data class TraktUser(
    @SerialName("username") val username: String,
    @SerialName("name") val name: String? = null,
)

/** A title's ids; requests send the ones known, Trakt matches on any. */
@Serializable
data class TraktIds(
    @SerialName("trakt") val trakt: Long? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("imdb") val imdb: String? = null,
    @SerialName("tmdb") val tmdb: Int? = null,
    @SerialName("tvdb") val tvdb: Int? = null,
)

@Serializable
data class TraktMovie(
    @SerialName("ids") val ids: TraktIds = TraktIds(),
    @SerialName("title") val title: String? = null,
    @SerialName("year") val year: Int? = null,
)

@Serializable
data class TraktShow(
    @SerialName("ids") val ids: TraktIds = TraktIds(),
    @SerialName("title") val title: String? = null,
    @SerialName("year") val year: Int? = null,
)

@Serializable
data class TraktEpisode(
    @SerialName("ids") val ids: TraktIds? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("title") val title: String? = null,
)

/** When the account last changed what it watched ([watchedAt]) and where it paused ([pausedAt]). */
@Serializable
data class Activity(
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
)

@Serializable
data class LastActivities(
    @SerialName("movies") val movies: Activity = Activity(),
    @SerialName("episodes") val episodes: Activity = Activity(),
)

/** A film the account has watched, [plays] times. */
@Serializable
data class WatchedMovie(
    @SerialName("movie") val movie: TraktMovie? = null,
    @SerialName("plays") val plays: Int = 0,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
)

/** A show and the episodes of it the account has watched, by season. */
@Serializable
data class WatchedShow(
    @SerialName("show") val show: TraktShow? = null,
    @SerialName("plays") val plays: Int = 0,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("seasons") val seasons: List<WatchedSeason> = emptyList(),
)

@Serializable
data class WatchedSeason(
    @SerialName("number") val number: Int? = null,
    @SerialName("episodes") val episodes: List<WatchedEpisode> = emptyList(),
)

@Serializable
data class WatchedEpisode(
    @SerialName("number") val number: Int? = null,
    @SerialName("plays") val plays: Int = 0,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
)

/** A paused playback: a film, or an episode with its show ([type] "movie" or "episode"), at [progress] percent. */
@Serializable
data class PlaybackProgress(
    @SerialName("id") val id: Long? = null,
    @SerialName("progress") val progress: Float? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("movie") val movie: TraktMovie? = null,
    @SerialName("episode") val episode: TraktEpisode? = null,
    @SerialName("show") val show: TraktShow? = null,
)

/** Titles to add to the watch history or remove from it. */
@Serializable
data class HistoryItems(
    @SerialName("movies") val movies: List<HistoryMovie> = emptyList(),
    @SerialName("shows") val shows: List<HistoryShow> = emptyList(),
    @SerialName("episodes") val episodes: List<HistoryEpisode> = emptyList(),
)

/** A film watched at [watchedAt] (an ISO date, or "released"); without it, every play (removing). */
@Serializable
data class HistoryMovie(
    @SerialName("ids") val ids: TraktIds,
    @SerialName("watched_at") val watchedAt: String? = null,
)

/** A show's episodes by season and number, for files that name them so. */
@Serializable
data class HistoryShow(
    @SerialName("ids") val ids: TraktIds,
    @SerialName("seasons") val seasons: List<HistorySeason> = emptyList(),
)

@Serializable
data class HistorySeason(
    @SerialName("number") val number: Int,
    @SerialName("episodes") val episodes: List<HistorySeasonEpisode> = emptyList(),
)

@Serializable
data class HistorySeasonEpisode(
    @SerialName("number") val number: Int,
    @SerialName("watched_at") val watchedAt: String? = null,
)

/** An episode by its own ids. */
@Serializable
data class HistoryEpisode(
    @SerialName("ids") val ids: TraktIds,
    @SerialName("watched_at") val watchedAt: String? = null,
)

/** A playback that stopped at [progress] percent: a film, or an episode by its ids or by its show. */
@Serializable
data class ScrobbleRequest(
    @SerialName("progress") val progress: Float,
    @SerialName("movie") val movie: TraktMovie? = null,
    @SerialName("episode") val episode: TraktEpisode? = null,
    @SerialName("show") val show: TraktShow? = null,
)
