package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser

/** Per-source context a pass hands to the indexers. */
internal class ScanPass(
    val source: SourceEntity,
    val sidecars: Sidecars,
    val stacks: Stacks,
    val tmdb: TmdbPass,
    /** TMDB seasons, each fetched once per pass. */
    val seasons: ShowIndexer.SeasonCache,
    /** "Refresh all information": every title read is looked up on TMDB again. */
    val refreshMetadata: Boolean,
)

/** The row's stored NFO, parsed; null without one. */
internal fun ItemEntity.nfoInfo(): NfoInfo? = nfo?.let(NfoParser::parse)

/** Caches [local]'s images ([ScanFiles.copyArtwork]); null where missing or the copy failed. */
internal suspend fun ScanFiles.copyArt(source: SourceEntity, local: VideoSidecars): LocalArt = LocalArt(
    poster = local.poster?.let { copyArtwork(source, it) },
    fanart = local.fanart?.let { copyArtwork(source, it) },
    logo = local.logo?.let { copyArtwork(source, it) },
    thumb = local.thumb?.let { copyArtwork(source, it) },
)

/** The sidecars actually read, for [SidecarKey]; a failed NFO or image is left out so the next pass retries it. */
internal fun ScanFiles.sidecarsRead(local: VideoSidecars, nfo: KeptNfo, art: LocalArt): VideoSidecars = VideoSidecars(
    nfo = local.nfo.takeUnless { nfo.stale },
    poster = examined(local.poster, art.poster),
    fanart = examined(local.fanart, art.fanart),
    logo = examined(local.logo, art.logo),
    thumb = examined(local.thumb, art.thumb),
)
