package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser

/**
 * What a pass hands each indexer for the files of one folder: the folder, what it lists beside its videos
 * and which of them are films in parts, TMDB as the pass asks it, and whether titles are described anew.
 */
internal class ScanPass(
    val source: SourceEntity,
    /** What the folder lists beside its videos. */
    val sidecars: Sidecars,
    /** Its films in several files. */
    val stacks: Stacks,
    val tmdb: TmdbPass,
    /** TMDB's seasons, each asked once for the whole pass. */
    val seasons: ShowIndexer.SeasonCache,
    /** "Refresh all information": TMDB is asked again for every title read. */
    val refreshMetadata: Boolean,
)

/** What the NFO a row keeps says; null without one. */
internal fun ItemEntity.nfoInfo(): NfoInfo? = nfo?.let(NfoParser::parse)

/** The images of [local] in [source], copied into the app ([ScanFiles.copyArtwork]); null where none lies, or the copy failed. */
internal suspend fun ScanFiles.copyArt(source: SourceEntity, local: VideoSidecars): LocalArt = LocalArt(
    poster = local.poster?.let { copyArtwork(source, it) },
    fanart = local.fanart?.let { copyArtwork(source, it) },
    logo = local.logo?.let { copyArtwork(source, it) },
    thumb = local.thumb?.let { copyArtwork(source, it) },
)

/**
 * What was read of [local] ([SidecarKey]), its NFO kept as [nfo] and its images copied as [art]: an NFO or an
 * image that failed is left out, to be read again at the next pass.
 */
internal fun ScanFiles.sidecarsRead(local: VideoSidecars, nfo: KeptNfo, art: LocalArt): VideoSidecars = VideoSidecars(
    nfo = local.nfo.takeUnless { nfo.stale },
    poster = examined(local.poster, art.poster),
    fanart = examined(local.fanart, art.fanart),
    logo = examined(local.logo, art.logo),
    thumb = examined(local.thumb, art.thumb),
)
