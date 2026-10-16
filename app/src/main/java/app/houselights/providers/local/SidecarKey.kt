package app.houselights.providers.local

/**
 * What the files beside a video were when it was last read ([ItemEntity.sidecarKey]): the role, size,
 * date and path of each. A scan compares it with what the folder lists: a sidecar placed, removed or
 * rewritten in place (an NFO keeps its size, not its date) has them read again, not the video.
 */
internal object SidecarKey {
    /**
     * What the listing shows beside a file of [kind], as its indexer reads it: an episode has its NFO and
     * its thumb, no poster, fanart or logo of its own.
     */
    fun listed(kind: SourceKind, local: VideoSidecars): String? = of(
        when (kind) {
            SourceKind.MOVIES -> local
            SourceKind.SHOWS -> VideoSidecars(nfo = local.nfo, thumb = local.thumb)
        }
    )

    /** The key of [read], the sidecars read of a file; null when there were none. */
    fun of(read: VideoSidecars): String? = listOfNotNull(
        read.nfo?.let { "nfo" to it },
        read.poster?.let { "poster" to it },
        read.fanart?.let { "fanart" to it },
        read.logo?.let { "logo" to it },
        read.thumb?.let { "thumb" to it },
    ).takeIf { it.isNotEmpty() }?.joinToString("\n") { (role, f) -> "$role:${f.size}:${f.lastModified ?: ""}:${f.relativePath}" }
}
