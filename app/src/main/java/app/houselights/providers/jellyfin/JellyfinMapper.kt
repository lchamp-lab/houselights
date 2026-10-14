package app.houselights.providers.jellyfin

import app.houselights.core.Typeset
import app.houselights.core.AudioTrack
import app.houselights.core.HdrFormat
import app.houselights.core.MediaInfo
import app.houselights.core.SubtitleTrack
import app.houselights.core.VideoInfo
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.core.UserState

/** Jellyfin's items as the app's [MediaItem]s, with their artwork at the size each place asks for. */
internal class JellyfinMapper(private val client: JellyfinClient) {
    fun image(itemId: String?, type: String, tag: String?): ImageRef? =
        if (itemId != null && tag != null) ImageRef("$itemId/$type/$tag") { w -> client.imageUrl(itemId, type, tag, w) } else null

    fun toMedia(dto: BaseItem): MediaItem {
        val kind = when (dto.type) {
            "Movie" -> MediaKind.MOVIE
            "Series" -> MediaKind.SERIES
            "Season" -> MediaKind.SEASON
            "Episode" -> MediaKind.EPISODE
            "BoxSet" -> MediaKind.COLLECTION
            "Video", "MusicVideo" -> MediaKind.VIDEO
            else -> MediaKind.FOLDER
        }
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
            title = dto.name ?: "",
            originalTitle = dto.originalTitle,
            overview = Typeset.text(dto.overview),
            tagline = Typeset.text(dto.taglines.firstOrNull()),
            year = dto.productionYear,
            endYear = dto.endDate?.take(4)?.toIntOrNull(),
            continuing = dto.status == "Continuing",
            runtimeMs = dto.runTimeTicks?.div(TICKS_PER_MS),
            officialRating = dto.officialRating,
            communityRating = dto.communityRating,
            genres = dto.genres,
            cast = dto.people.filter { it.type == "Actor" }.take(30).map {
                Person(it.name ?: "", it.role, image(it.id, "Primary", it.primaryImageTag))
            },
            directors = dto.people.filter { it.type == "Director" }.mapNotNull { it.name },
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
            seriesTitle = dto.seriesName,
            seasonId = dto.seasonId,
            seasonNumber = if (kind == MediaKind.SEASON) dto.indexNumber else dto.parentIndexNumber,
            episodeNumber = if (kind == MediaKind.EPISODE) dto.indexNumber else null,
            childCount = dto.childCount,
        )
    }

    companion object {
        /** What the first media source contains, in the shared [MediaInfo] model. */
        fun mediaInfo(item: BaseItem): MediaInfo? {
            val source = item.mediaSources.firstOrNull() ?: return null
            val streams = source.mediaStreams
            val video = streams.firstOrNull { it.type == "Video" }?.let { v ->
                val range = v.videoRangeType.orEmpty()
                VideoInfo(
                    codec = when (v.codec?.lowercase()) {
                        "hevc", "h265" -> "HEVC"
                        "h264", "avc" -> "AVC"
                        "av1" -> "AV1"
                        "vp9" -> "VP9"
                        "mpeg2video" -> "MPEG-2"
                        "vc1" -> "VC-1"
                        else -> v.codec?.uppercase() ?: "?"
                    },
                    width = v.width,
                    height = v.height,
                    bitDepth = v.bitDepth,
                    hdr = buildList {
                        if (range.startsWith("DOVI")) add(HdrFormat.DOLBY_VISION)
                        when {
                            range == "HDR10Plus" -> add(HdrFormat.HDR10_PLUS)
                            range == "HDR10" || range == "DOVIWithHDR10" -> add(HdrFormat.HDR10)
                            range == "HLG" || range == "DOVIWithHLG" -> add(HdrFormat.HLG)
                        }
                    },
                    dolbyVisionProfile = v.dvProfile,
                )
            }
            val audio = streams.filter { it.type == "Audio" }.map { a ->
                val profile = "${a.profile.orEmpty()} ${a.displayTitle.orEmpty()} ${a.title.orEmpty()}"
                AudioTrack(
                    codec = when (a.codec?.lowercase()) {
                        "truehd" -> "TrueHD"
                        "dts" -> if (profile.contains("MA")) "DTS-HD MA" else if (profile.contains("HRA")) "DTS-HD HRA" else "DTS"
                        "eac3" -> "E-AC3"
                        "ac3" -> "AC3"
                        "aac" -> "AAC"
                        "flac" -> "FLAC"
                        "opus" -> "Opus"
                        else -> if (a.codec?.startsWith("pcm") == true) "PCM" else a.codec?.uppercase() ?: "?"
                    },
                    channels = a.channels,
                    language = a.language,
                    title = a.title,
                    atmos = profile.contains("Atmos", ignoreCase = true),
                    dtsX = profile.contains("DTS:X", ignoreCase = true),
                    default = a.isDefault,
                )
            }
            val subtitles = streams.filter { it.type == "Subtitle" }.map { s ->
                SubtitleTrack(
                    language = s.language,
                    title = s.title,
                    format = when (s.codec?.lowercase()) {
                        "subrip", "srt" -> "SRT"
                        "pgssub", "pgs" -> "PGS"
                        "ass", "ssa" -> "ASS"
                        "dvdsub", "vobsub" -> "VobSub"
                        "webvtt" -> "WebVTT"
                        else -> s.codec?.uppercase() ?: "?"
                    },
                    forced = s.isForced,
                    hearingImpaired = s.isHearingImpaired || s.title?.contains("SDH", ignoreCase = true) == true,
                    default = s.isDefault,
                )
            }
            return MediaInfo(
                container = source.container?.substringBefore(',')?.uppercase(),
                sizeBytes = source.size,
                durationMs = (source.runTimeTicks ?: item.runTimeTicks)?.div(TICKS_PER_MS),
                video = video,
                audio = audio,
                subtitles = subtitles,
            )
        }
    }
}
