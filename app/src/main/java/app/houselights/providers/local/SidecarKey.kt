package app.houselights.providers.local

/**
 * Fingerprint of a video's sidecars, or a show folder's ([ofShow]), at the last read ([ItemEntity.sidecarKey]): role,
 * size, date and path of each. A sidecar added, removed or rewritten in place (same size, new date) triggers a
 * re-read of the sidecars, not the video.
 */
internal object SidecarKey {
    /** Key of the listed sidecars the indexer of [kind] uses; episodes only use their NFO and thumb. */
    fun listed(kind: SourceKind, local: VideoSidecars): String? = of(
        when (kind) {
            SourceKind.MOVIES -> local
            SourceKind.SHOWS -> VideoSidecars(nfo = local.nfo, thumb = local.thumb)
        },
    )

    /** Null when there are no sidecars. */
    fun of(read: VideoSidecars): String? = listOfNotNull(
        read.nfo?.let { "nfo" to it },
        read.poster?.let { "poster" to it },
        read.fanart?.let { "fanart" to it },
        read.logo?.let { "logo" to it },
        read.thumb?.let { "thumb" to it },
    ).takeIf { it.isNotEmpty() }?.joinToString("\n") { (role, f) -> "$role:${f.size}:${f.lastModified ?: ""}:${f.relativePath}" }

    /** Key of a show's sidecars in [dir] ([Sidecars.forShow]), prefixed by it, as a show may span folders. */
    fun ofShow(sourceId: Long, dir: String, read: VideoSidecars): String? = of(read)?.let { "$SHOW$sourceId:$dir\n$it" }

    /**
     * Whether to read a show's sidecars in [dir]: they changed since that folder was read, or they appear while the
     * show has none, or the folder they came from now lists none. Sidecars from another folder of the show (with a
     * year, or on another source) are kept, so two folders do not alternate every pass.
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

    /** True when a role in [stored] for this same [dir] is missing from [listed]; other folders never count. */
    fun showRemoved(stored: String?, sourceId: Long, dir: String, listed: VideoSidecars): Boolean {
        val read = stored?.takeIf { it.startsWith("$SHOW$sourceId:$dir\n") }?.lines()?.drop(1) ?: return false
        val now = mapOf("nfo" to listed.nfo, "poster" to listed.poster, "fanart" to listed.fanart, "logo" to listed.logo)
        return read.map { it.substringBefore(':') }.any { role -> role in now && now[role] == null }
    }

    private const val SHOW = "show:"
}
