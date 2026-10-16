package app.houselights.providers.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Jellyfin's REST types, named as its API names them (BaseItemDto is BaseItem here).

@Serializable
data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
    /** "Jellyfin Server"; another product (Emby) answers the same route. */
    @SerialName("ProductName") val productName: String? = null,
)

@Serializable
data class AuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

@Serializable
data class AuthResult(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<BaseItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class BaseItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("OriginalTitle") val originalTitle: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Taglines") val taglines: List<String> = emptyList(),
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("CriticRating") val criticRating: Double? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("People") val people: List<BaseItemPerson> = emptyList(),
    @SerialName("Studios") val studios: List<NameId> = emptyList(),
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentLogoItemId") val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoImageTag: String? = null,
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("UserData") val userData: UserData? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSource> = emptyList(),
    @SerialName("Path") val path: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("RecursiveItemCount") val recursiveItemCount: Int? = null,
    @SerialName("IsFolder") val isFolder: Boolean = false,
)

@Serializable
data class UserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
)

@Serializable
data class MediaSource(
    @SerialName("Id") val id: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Protocol") val protocol: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream> = emptyList(),
)

@Serializable
data class MediaStream(
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Profile") val profile: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Index") val index: Int = 0,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("VideoRangeType") val videoRangeType: String? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("BitDepth") val bitDepth: Int? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsHearingImpaired") val isHearingImpaired: Boolean = false,
    @SerialName("DvProfile") val dvProfile: Int? = null,
    @SerialName("RealFrameRate") val realFrameRate: Float? = null,
    @SerialName("BitRate") val bitRate: Int? = null,
    @SerialName("DvBlSignalCompatibilityId") val dvCompatibility: Int? = null,
    @SerialName("AverageFrameRate") val averageFrameRate: Float? = null,
)

@Serializable
data class BaseItemPerson(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
data class NameId(
    @SerialName("Name") val name: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class PlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long? = null,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
)

const val TICKS_PER_MS = 10_000L
