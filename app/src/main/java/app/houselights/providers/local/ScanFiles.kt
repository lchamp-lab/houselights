package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser
import app.houselights.core.RepeatedFailures
import app.houselights.core.suspendRunCatching

/** Result of reading a text file; a failure is retried at the next scan. */
internal sealed interface TextRead {
    data class Text(val text: String) : TextRead

    /**
     * Lock, denial or timeout. Even a denial is retried next scan (a small read that notices restored permissions
     * at once); meanwhile the item keeps its previous data, so screens see no change.
     */
    data class Failed(val cause: Throwable) : TextRead
}

/** An item's NFO path and text, both or neither; [stale] means the read failed and the previous NFO stands in. */
internal data class KeptNfo(val path: String?, val text: String?, val stale: Boolean = false) {
    val info: NfoInfo? by lazy { text?.let(NfoParser::parse) }
}

/** File reads shared by both indexers: NFO text, media probing, local artwork. */
internal class ScanFiles(
    private val artwork: ArtworkCache,
    private val browsers: (SourceEntity) -> RemoteBrowser,
) {
    /** Text files that could not be read, often again at every pass: each logged whole the first time, then counted. */
    private val unread = RepeatedFailures(TAG)

    /**
     * Decoded by [NfoParser.decode]; throws when the NAS is unreachable, else failures give [TextRead.Failed]. Each read of
     * this class is [collected]: within a run under the scan's lock, answered from what was read before it.
     */
    suspend fun readText(source: SourceEntity, file: RemoteFile): TextRead = collected(NasRead(TEXT, source.id, listOf(file))) {
        readTextNow(source, file)
    }

    private suspend fun readTextNow(source: SourceEntity, file: RemoteFile): TextRead {
        val key = "${source.id}/${file.relativePath}"
        return suspendRunCatching<TextRead> {
            val length = file.size.coerceIn(0, MAX_NFO_BYTES.toLong()).toInt().takeIf { it > 0 } ?: MAX_NFO_BYTES
            TextRead.Text(NfoParser.decode(browsers(source).readPrefix(source, file.relativePath, length)))
        }.onSuccess { unread.recovered(key) }.getOrElse { e ->
            if (ScanErrors.isUnreachable(e)) throw e
            unread.failed(key, "Could not read ${file.relativePath}; it is read again at the next scan", e)
            TextRead.Failed(e)
        }
    }

    /** One line for [source]'s text files that could not be read again in this pass, if any. */
    fun summarize(source: SourceEntity) = unread.summarize { "${source.name}: files unreadable again, each logged before: $it" }

    /**
     * Reads [file] as [item]'s NFO, falling back to [stored], its stored text ([NfoDao.nfoText]), on failure; a path
     * is never kept without text.
     */
    suspend fun nfo(source: SourceEntity, file: RemoteFile?, item: ItemEntity, stored: String?): KeptNfo =
        when (val read = file?.let { readText(source, it) }) {
            null -> KeptNfo(null, null)
            is TextRead.Text -> KeptNfo(file.relativePath, read.text)
            is TextRead.Failed -> KeptNfo(item.nfoPath.takeIf { stored != null }, stored, stale = true)
        }

    /** [item] with media info and badges, probed when unprobed, probed by an older version, or changed; not saved. */
    suspend fun withMediaInfo(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity =
        if (!MediaProbe.isStale(item.mediaInfo) && !item.sizeChanged(file)) item else mediaFields(source, file, item)

    suspend fun mediaFields(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity {
        val media = when (val probe = probe(source, file)) {
            is ProbeResult.Read -> probe.info
            // A failed re-probe keeps the previous media info until the next attempt.
            is ProbeResult.Failed -> MediaProbe.decode(item.mediaInfo)?.takeIf { !item.sizeChanged(file) && probe.info.video == null }
                ?: probe.info
        }
        val badges = media.badges().ifEmpty { FilenameParser.badges(file.relativePath) }
        return item.withMedia(media).copy(badges = badges, fileSize = file.size)
    }

    /** [file]'s header and tracks ([MediaProbe.probe]). */
    suspend fun probe(source: SourceEntity, file: RemoteFile): ProbeResult =
        collected(probeOf(source, file)) { MediaProbe.probe(source, file, browsers(source)) }

    /** What [probe] is collected by. */
    fun probeOf(source: SourceEntity, file: RemoteFile): Any = NasRead(HEADER, source.id, listOf(file))

    /** Caches a sidecar image (see [ArtworkCache.copy]). */
    suspend fun copyArtwork(source: SourceEntity, file: RemoteFile): String? = copyArtwork(source, listOf(file)).single()

    /** Caches sidecar [images] at once, for one request, null for none ([copyArtwork]). */
    suspend fun copyArtwork(source: SourceEntity, images: List<RemoteFile?>): List<String?> {
        if (images.all { it == null }) return images.map { null }
        return collected(NasRead(ART, source.id, images)) {
            images.map { image -> image?.let { artwork.copy(source, it, browsers(source)) } }
        }
    }

    /** [image] if read for [SidecarKey] (cached, or never cacheable); null on failure so the next scan retries. */
    fun examined(image: RemoteFile?, copy: String?): RemoteFile? = image?.takeIf { copy != null || !artwork.holds(it) }

    /** A read on the NAS, by which it is [collected]: [files] of folder [sourceId], read as [what]. */
    private data class NasRead(val what: Int, val sourceId: Long, val files: List<RemoteFile?>)

    private companion object {
        const val TAG = "ScanFiles"
        const val TEXT = 0
        const val HEADER = 1
        const val ART = 2
        const val MAX_NFO_BYTES = 1 shl 20
    }
}

/** Replaced under the same name, or read mid-copy; rows without a known size never count as changed. */
internal fun ItemEntity.sizeChanged(file: RemoteFile): Boolean = fileSize != null && fileSize != file.size

/** Fills media info from NFO stream details when the probe found no video stream. */
internal fun ItemEntity.withNfoStreams(nfo: NfoInfo?): ItemEntity {
    val streams = nfo?.streams ?: return this
    val media = MediaProbe.decode(mediaInfo)
    if (media?.video != null) return this
    val merged = NfoStreamDetails.toMediaInfo(streams, media)
    return withMedia(merged).copy(badges = merged.badges().ifEmpty { badges })
}
