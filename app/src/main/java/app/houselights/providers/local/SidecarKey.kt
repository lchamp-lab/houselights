package app.houselights.providers.local

/**
 * What the files beside a video, or in a show's folder ([ofShow]), were when it was last read
 * ([ItemEntity.sidecarKey]): the role, size, date and path of each. A scan compares it with what the
 * folder lists: a sidecar placed, removed or rewritten in place (an NFO keeps its size, not its date)
 * has them read again, not the video.
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

    /**
     * The key of a show's own sidecars, [read] in its folder [dir] of source [sourceId] ([Sidecars.forShow]).
     * The folder heads it, as a show may lie in several ([showDue]).
     */
    fun ofShow(sourceId: Long, dir: String, read: VideoSidecars): String? = of(read)?.let { "$SHOW$sourceId:$dir\n$it" }

    /**
     * Whether a show's own sidecars are read when it is met in [dir] of source [sourceId], its row holding
     * [stored]: placed, rewritten in place or removed since that folder was read; or placed in a show that
     * has none read, or whose folder they were read in lists none now (renamed, emptied). Those of another
     * of its folders (one giving its year, or on another source) stay: two folders of one show do not take
     * turns at every pass. [sidecars]: what the source lists.
     */
    fun showDue(stored: String?, sourceId: Long, dir: String, sidecars: Sidecars): Boolean {
        val listed = ofShow(sourceId, dir, sidecars.forShow(dir))
        if (listed == stored) return false
        val from = stored?.takeIf { it.startsWith(SHOW) }?.substringBefore('\n')?.removePrefix(SHOW)
        val fromSource = from?.substringBefore(':')?.toLongOrNull()
        val fromDir = from?.substringAfter(':')
        return when {
            fromSource == sourceId && fromDir == dir -> true
            listed == null -> false
            fromSource == null || fromDir == null -> true
            else -> fromSource == sourceId && ofShow(sourceId, fromDir, sidecars.forShow(fromDir)) == null
        }
    }

    private const val SHOW = "show:"
}
