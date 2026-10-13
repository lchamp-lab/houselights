package app.houselights.providers.jellyfin

import android.util.Log
import app.houselights.core.HomeSection
import app.houselights.core.ImageRef
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.Page
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
import app.houselights.core.Person
import app.houselights.core.ProviderCapabilities
import app.houselights.core.SectionType
import app.houselights.core.SortOption
import app.houselights.core.SourceConfig
import app.houselights.core.UserState
import app.houselights.player.PathResolver
import app.houselights.player.Redact
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class JellyfinProvider(config: SourceConfig.Jellyfin, deviceId: String) : MediaProvider {
    private val client = JellyfinClient(config.serverUrl, deviceId, config.accessToken)
    private val api = client.api
    private val userId = config.userId

    override val name: String = config.serverName ?: "Jellyfin"

    override val capabilities = ProviderCapabilities(markPlayed = true, favorites = true, similar = true, pathMapping = true)

    override suspend fun libraries(): List<Library> =
        api.views(userId).items
            .filter { it.collectionType in SUPPORTED_COLLECTIONS }
            .map { Library(it.id, libraryKind(it.collectionType), it.name) }

    override suspend fun home(): List<HomeSection> = coroutineScope {
        val resume = async { runCatching { api.resume(userId, 20, ROW_FIELDS).items }.getOrDefault(emptyList()) }
        val nextUp = async { runCatching { api.nextUp(userId, 20, ROW_FIELDS).items }.getOrDefault(emptyList()) }
        val latest = libraries().map { lib ->
            async {
                val items = runCatching { api.latest(userId, lib.id, 24, ROW_FIELDS) }.getOrDefault(emptyList())
                HomeSection(SectionType.LATEST, items.map(::toMedia), landscape = false, libraryName = lib.title)
            }
        }
        buildList {
            resume.await().takeIf { it.isNotEmpty() }
                ?.let { add(HomeSection(SectionType.CONTINUE, it.map(::toMedia), landscape = true)) }
            nextUp.await().takeIf { it.isNotEmpty() }
                ?.let { add(HomeSection(SectionType.NEXT_UP, it.map(::toMedia), landscape = true)) }
            latest.forEach { section -> section.await().takeIf { it.items.isNotEmpty() }?.let(::add) }
        }
    }

    override suspend fun libraryPage(library: Library, sort: SortOption, unplayedOnly: Boolean, start: Int, limit: Int): Page {
        val types = when (library.kind) {
            LibraryKind.MOVIES -> "Movie"
            LibraryKind.SHOWS -> "Series"
            LibraryKind.COLLECTIONS -> "BoxSet"
            LibraryKind.MIXED -> null
        }
        val (sortBy, order) = when (sort) {
            SortOption.TITLE -> "SortName" to "Ascending"
            SortOption.ADDED -> "DateCreated,SortName" to "Descending"
            SortOption.YEAR -> "ProductionYear,PremiereDate,SortName" to "Descending"
            SortOption.RATING -> "CommunityRating,SortName" to "Descending"
        }
        val result = api.items(
            userId = userId,
            parentId = library.id,
            includeItemTypes = types,
            recursive = types != null,
            sortBy = sortBy,
            sortOrder = order,
            startIndex = start,
            limit = limit,
            fields = ROW_FIELDS,
            filters = if (unplayedOnly) "IsUnplayed" else null,
        )
        return Page(result.items.map(::toMedia), result.totalRecordCount)
    }

    override suspend fun item(id: String): MediaItem = toMedia(api.item(id, userId))

    override suspend fun seasons(series: MediaItem): List<MediaItem> =
        api.seasons(series.id, userId, ROW_FIELDS).items.map(::toMedia)

    override suspend fun episodes(series: MediaItem, season: MediaItem): List<MediaItem> =
        api.episodes(series.id, userId, season.id, EPISODE_FIELDS).items.map(::toMedia)

    override suspend fun nextUp(series: MediaItem): MediaItem? =
        runCatching { api.nextUp(userId, 1, ROW_FIELDS, series.id).items.firstOrNull()?.let(::toMedia) }.getOrNull()

    override suspend fun similar(item: MediaItem): List<MediaItem> =
        if (item.kind == MediaKind.EPISODE) emptyList()
        else runCatching { api.similar(item.id, userId, 16, ROW_FIELDS).items.map(::toMedia) }.getOrDefault(emptyList())

    override suspend fun setPlayed(item: MediaItem, played: Boolean) {
        if (played) api.markPlayed(item.id, userId) else api.markUnplayed(item.id, userId)
    }

    override suspend fun setFavorite(item: MediaItem, favorite: Boolean) {
        if (favorite) api.addFavorite(item.id, userId) else api.removeFavorite(item.id, userId)
    }

    override suspend fun samplePath(): String? = runCatching {
        api.items(userId, null, "Movie,Episode", true, "Random", "Ascending", 0, 1, "Path").items.firstOrNull()?.path
    }.getOrNull()

    override suspend fun playback(item: MediaItem, resume: Boolean, options: PlaybackOptions): PlaybackPlan {
        // List entries lack MediaSources: reload the full item.
        val full = api.item(item.id, userId)
        val source = full.mediaSources.firstOrNull()
        val container = (source?.container ?: full.container)?.substringBefore(',')
        val networkUri = if (options.forceHttp) null else PathResolver.resolve(source?.path ?: full.path, options.pathMappings)
        val uri = networkUri ?: client.streamUrl(full.id, source?.id, container)
        val media = toMedia(full)
        val startMs = if (resume) media.user.positionMs else 0L
        Log.i(TAG, "Play ${full.name} -> ${Redact.uri(uri)} (start ${startMs} ms)")

        return PlaybackPlan.Launch(
            uri = uri,
            container = container,
            title = listOfNotNull(media.seriesTitle, media.episodeLabel, media.title).joinToString(" · "),
            backdropUrl = media.backdrop?.url(1920),
            logoUrl = media.logo?.url(800),
            startMs = startMs,
            reporter = Reporter(full.id, source?.id),
        )
    }

    private inner class Reporter(private val itemId: String, private val mediaSourceId: String?) : PlaybackReporter {
        private fun report(positionMs: Long, paused: Boolean = false) = PlaybackReport(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            positionTicks = positionMs * TICKS_PER_MS,
            isPaused = paused,
        )

        override suspend fun started(positionMs: Long) { api.reportStart(report(positionMs)) }
        override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) {
            api.reportProgress(report(positionMs, paused))
        }

        // The server marks the item as played past 90 %.
        override suspend fun stopped(positionMs: Long, durationMs: Long?) { api.reportStopped(report(positionMs)) }
    }

    // --- Mapping -------------------------------------------------------------------------------

    private fun image(itemId: String?, type: String, tag: String?): ImageRef? =
        if (itemId != null && tag != null) ImageRef("$itemId/$type/$tag") { w -> client.imageUrl(itemId, type, tag, w) } else null

    private fun toMedia(dto: BaseItem): MediaItem {
        val kind = when (dto.type) {
            "Movie" -> MediaKind.MOVIE
            "Series" -> MediaKind.SERIES
            "Season" -> MediaKind.SEASON
            "Episode" -> MediaKind.EPISODE
            "BoxSet" -> MediaKind.COLLECTION
            "Video", "MusicVideo" -> MediaKind.VIDEO
            else -> MediaKind.FOLDER
        }
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
            overview = dto.overview,
            tagline = dto.taglines.firstOrNull(),
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
            badges = techBadges(dto),
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
        private const val TAG = "Jellyfin"
        private val SUPPORTED_COLLECTIONS = setOf("movies", "tvshows", "boxsets", "homevideos", "musicvideos", null)
        private const val ROW_FIELDS = "Overview,Genres,ProductionYear,OfficialRating,CommunityRating"
        private const val EPISODE_FIELDS = "Overview"

        private fun libraryKind(collectionType: String?) = when (collectionType) {
            "movies" -> LibraryKind.MOVIES
            "tvshows" -> LibraryKind.SHOWS
            "boxsets" -> LibraryKind.COLLECTIONS
            else -> LibraryKind.MIXED
        }

        /** Technical badges derived from the first media source streams. */
        fun techBadges(item: BaseItem): List<String> {
            val streams = item.mediaSources.firstOrNull()?.mediaStreams ?: return emptyList()
            val video = streams.firstOrNull { it.type == "Video" }
            val audio = streams.firstOrNull { it.type == "Audio" && it.isDefault } ?: streams.firstOrNull { it.type == "Audio" }
            return buildList {
                video?.width?.let { w ->
                    add(
                        when {
                            w >= 3800 -> "4K"
                            w >= 1900 -> "1080p"
                            w >= 1260 -> "720p"
                            else -> "SD"
                        }
                    )
                }
                when {
                    video?.videoRangeType?.startsWith("DOVI") == true -> add("DOLBY VISION")
                    video?.videoRangeType == "HDR10Plus" -> add("HDR10+")
                    video?.videoRangeType == "HDR10" -> add("HDR10")
                    video?.videoRangeType == "HLG" -> add("HLG")
                }
                if (audio != null) {
                    val title = (audio.displayTitle ?: "") + " " + (audio.profile ?: "")
                    when {
                        title.contains("Atmos", ignoreCase = true) -> add("ATMOS")
                        title.contains("DTS:X", ignoreCase = true) -> add("DTS:X")
                    }
                    when (audio.codec?.lowercase()) {
                        "truehd" -> add("TRUEHD")
                        "dts" -> add(if (title.contains("MA")) "DTS-HD MA" else "DTS")
                        "eac3" -> add("DD+")
                        "ac3" -> add("DD")
                        "flac" -> add("FLAC")
                    }
                    when (audio.channels) {
                        8 -> add("7.1")
                        6 -> add("5.1")
                        2 -> add("2.0")
                    }
                }
            }.distinct()
        }
    }
}

