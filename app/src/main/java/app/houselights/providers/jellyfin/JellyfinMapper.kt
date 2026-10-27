package app.houselights.providers.jellyfin

import app.houselights.core.Typeset
import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.HdrFormat
import app.houselights.core.MediaInfo
import app.houselights.core.SubtitleTrack
import app.houselights.core.VideoInfo
import app.houselights.core.bpsToKbps
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.epochMillisOf
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.core.UserState
import app.houselights.core.yearOf

/** Maps Jellyfin items to [MediaItem]s, with artwork URLs resolved at the requested width. */
internal class JellyfinMapper(private val client: JellyfinClient) {
    fun image(itemId: String?, type: String, tag: String?): ImageRef? =
        if (itemId != null && tag != null) ImageRef("$itemId/$type/$tag") { w -> client.imageUrl(itemId, type, tag, w) } else null

    fun toMedia(dto: BaseItem): MediaItem {
        val kind = kindOf(dto.type)
        val media = mediaInfo(dto)
        val backdrop = image(dto.id, "Backdrop", dto.backdropImageTags.firstOrNull())
            ?: image(dto.parentBackdropItemId, "Backdrop", dto.parentBackdropImageTags.firstOrNull())
        val poster = if (kind == MediaKind.EPISODE) image(dto.seriesId, "Primary", dto.seriesPrimaryImageTag)
        else image(dto.id, "Primary", dto.imageTags["Primary"])
        val thumb = when (kind) {
            MediaKind.EPISODE -> image(dto.id, "Primary", dto.imageTags["Primary"])
            else -> image(dto.id, "Thumb", dto.imageTags["Thumb"]) ?: image(dto.parentThumbItemId, "Thumb", dto.parentThumbImageTag)
        }
        return MediaItem(
            id = dto.id,
            kind = kind,
            // Typeset for display only; the server sorts and searches its raw text.
            title = Typeset.text(dto.name.orEmpty()),
            originalTitle = Typeset.text(dto.originalTitle),
            overview = Typeset.text(dto.overview),
            tagline = Typeset.text(dto.taglines.firstOrNull()),
            year = dto.productionYear,
            addedAt = epochMillisOf(dto.dateCreated),
            endYear = yearOf(dto.endDate),
            continuing = dto.status == "Continuing",
            runtimeMs = dto.runTimeTicks?.div(TICKS_PER_MS),
            officialRating = dto.officialRating,
            communityRating = dto.communityRating,
            genres = dto.genres,
            cast = dto.people.filter { it.type == "Actor" }.take(CAST_KEPT).map {
                Person(Typeset.text(it.name.orEmpty()), Typeset.text(it.role), image(it.id, "Primary", it.primaryImageTag))
            },
            directors = dto.people.filter { it.type == "Director" }.mapNotNull { it.name }.map { Typeset.text(it) },
            badges = media?.badges().orEmpty(),
            media = media,
            poster = poster,
            backdrop = backdrop,
            logo = image(dto.id, "Logo", dto.imageTags["Logo"]) ?: image(dto.parentLogoItemId, "Logo", dto.parentLogoImageTag),
            thumb = thumb,
            user = UserState(
                played = dto.userData?.played == true,
                favorite = dto.userData?.isFavorite == true,
                positionMs = (dto.userData?.playbackPositionTicks ?: 0L) / TICKS_PER_MS,
                unplayedCount = dto.userData?.unplayedItemCount,
            ),
            seriesId = dto.seriesId,
            seriesTitle = Typeset.text(dto.seriesName),
            seasonId = dto.seasonId,
            seasonNumber = if (kind == MediaKind.SEASON) dto.indexNumber else dto.parentIndexNumber,
            episodeNumber = if (kind == MediaKind.EPISODE) dto.indexNumber else null,
            // A season counts its episodes on disk, as its unwatched count does.
            childCount = if (kind == MediaKind.SEASON) dto.recursiveItemCount ?: dto.childCount else dto.childCount,
        )
    }

    companion object {
        /** The cast a title keeps, in billing order, as a scan keeps of TMDB's. */
        private const val CAST_KEPT = 30

        /** What a server's item type is to the app; any other is a folder. */
        private fun kindOf(type: String?): MediaKind = when (type) {
            "Movie" -> MediaKind.MOVIE
            "Series" -> MediaKind.SERIES
            "Season" -> MediaKind.SEASON
            "Episode" -> MediaKind.EPISODE
            "BoxSet" -> MediaKind.COLLECTION
            "Video", "MusicVideo" -> MediaKind.VIDEO
            else -> MediaKind.FOLDER
        }

        /**
         * HDR formats for each VideoRangeType value (Jellyfin 10.9 to 10.11). DOVIInvalid plays as its
         * base layer, whose format the server does not name, so it maps to none.
         */
        private val VIDEO_RANGES = mapOf(
            "SDR" to emptyList(),
            "HDR10" to listOf(HdrFormat.HDR10),
            "HDR10Plus" to listOf(HdrFormat.HDR10_PLUS),
            "HLG" to listOf(HdrFormat.HLG),
            "DOVI" to listOf(HdrFormat.DOLBY_VISION),
            "DOVIWithHDR10" to listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10),
            "DOVIWithHDR10Plus" to listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10_PLUS),
            "DOVIWithHLG" to listOf(HdrFormat.DOLBY_VISION, HdrFormat.HLG),
            "DOVIWithSDR" to listOf(HdrFormat.DOLBY_VISION),
            "DOVIWithEL" to listOf(HdrFormat.DOLBY_VISION),
            "DOVIWithELHDR10Plus" to listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10_PLUS),
            "DOVIInvalid" to emptyList(),
        )

        /** HDR formats of a VideoRangeType; an unknown value starting with DOVI counts as Dolby Vision. */
        internal fun hdrFormats(range: String?): List<HdrFormat> = range?.let { r ->
            VIDEO_RANGES[r] ?: listOf(HdrFormat.DOLBY_VISION).takeIf { r.startsWith("DOVI") }
        }.orEmpty()

        /** Stream details of a media source (the item's first by default) as a [MediaInfo]. */
        fun mediaInfo(item: BaseItem, source: MediaSource? = item.mediaSources.firstOrNull()): MediaInfo? {
            if (source == null) return null
            val streams = source.mediaStreams
            val video = streams.firstOrNull { it.type == "Video" }?.let { v ->
                VideoInfo(
                    codec = Codecs.video(v.codec),
                    width = v.width,
                    height = v.height,
                    bitDepth = v.bitDepth,
                    hdr = hdrFormats(v.videoRangeType),
                    dolbyVisionProfile = v.dvProfile,
                    frameRate = v.realFrameRate ?: v.averageFrameRate,
                    dolbyVisionCompatibility = v.dvCompatibility.takeIf { v.dvProfile != null },
                    // Jellyfin reports bitrates in bits per second.
                    bitrateKbps = v.bitRate?.let { bpsToKbps(it.toLong()) },
                )
            }
            // In the server's order, which is the file's: the player numbers tracks by it.
            val audio = streams.filter { it.type == "Audio" }.sortedBy { it.index }.map { a ->
                val profile = "${a.profile.orEmpty()} ${a.displayTitle.orEmpty()} ${a.title.orEmpty()}"
                AudioTrack(
                    codec = Codecs.audio(a.codec, profile),
                    channels = a.channels,
                    language = a.language,
                    title = a.title,
                    atmos = profile.contains("Atmos", ignoreCase = true),
                    dtsX = profile.contains("DTS:X", ignoreCase = true),
                    default = a.isDefault,
                    bitrateKbps = a.bitRate?.let { bpsToKbps(it.toLong()) },
                    external = a.isExternal,
                )
            }
            val subtitles = streams.filter { it.type == "Subtitle" }.sortedBy { it.index }.map { s ->
                SubtitleTrack(
                    language = s.language,
                    title = s.title,
                    format = Codecs.subtitle(s.codec),
                    forced = s.isForced,
                    hearingImpaired = s.isHearingImpaired || s.title?.contains("SDH", ignoreCase = true) == true,
                    default = s.isDefault,
                    external = s.isExternal,
                )
            }
            return MediaInfo(
                container = source.container?.substringBefore(',')?.uppercase(),
                sizeBytes = source.size,
                durationMs = (source.runTimeTicks ?: item.runTimeTicks)?.div(TICKS_PER_MS),
                video = video,
                audio = audio,
                subtitles = subtitles,
                tracksInOrder = true,
            )
        }
    }
}
