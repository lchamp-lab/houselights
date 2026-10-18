package app.houselights.providers.local

import android.util.Log
import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser
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
    private val dao: LibraryDao,
    private val artwork: ArtworkCache,
    private val browsers: (SourceEntity) -> RemoteBrowser,
) {
    /** Decoded by [NfoParser.decode]; throws when the NAS is unreachable, else failures give [TextRead.Failed]. */
    suspend fun readText(source: SourceEntity, file: RemoteFile): TextRead = suspendRunCatching<TextRead> {
        val length = file.size.coerceIn(0, MAX_NFO_BYTES.toLong()).toInt().takeIf { it > 0 } ?: MAX_NFO_BYTES
        TextRead.Text(NfoParser.decode(browsers(source).readPrefix(source, file.relativePath, length)))
    }.getOrElse { e ->
        if (ScanErrors.isUnreachable(e)) throw e
        Log.w(TAG, "Could not read ${file.relativePath}; it is read again at the next scan", e)
        TextRead.Failed(e)
    }

    /** Reads [file] as [item]'s NFO, falling back to the stored one on failure; a path is never kept without text. */
    suspend fun nfo(source: SourceEntity, file: RemoteFile?, item: ItemEntity): KeptNfo =
        when (val read = file?.let { readText(source, it) }) {
            null -> KeptNfo(null, null)
            is TextRead.Text -> KeptNfo(file.relativePath, read.text)
            is TextRead.Failed -> KeptNfo(item.nfoPath.takeIf { item.nfo != null }, item.nfo, stale = true)
        }

    /** Probes the file when unprobed, probed by an older version, or changed, and stores media info and badges. */
    suspend fun withMediaInfo(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity {
        if (!MediaProbe.isStale(item.mediaInfo) && !item.sizeChanged(file)) return item
        val updated = mediaFields(source, file, item)
        dao.saveScanned(updated)
        return updated
    }

    suspend fun mediaFields(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity {
        val media = when (val probe = MediaProbe.probe(source, file, browsers(source))) {
            is ProbeResult.Read -> probe.info
            // A failed re-probe keeps the previous media info until the next attempt.
            is ProbeResult.Failed -> MediaProbe.decode(item.mediaInfo)?.takeIf { !item.sizeChanged(file) && probe.info.video == null }
                ?: probe.info
        }
        val badges = media.badges().ifEmpty { FilenameParser.badges(file.relativePath) }
        return item.withMedia(media).copy(badges = badges, fileSize = file.size)
    }

    /** Caches a sidecar image (see [ArtworkCache.copy]). */
    suspend fun copyArtwork(source: SourceEntity, file: RemoteFile): String? = artwork.copy(source, file, browsers(source))

    /** [image] if read for [SidecarKey] (cached, or never cacheable); null on failure so the next scan retries. */
    fun examined(image: RemoteFile?, copy: String?): RemoteFile? = image?.takeIf { copy != null || !artwork.holds(it) }

    private companion object {
        const val TAG = "ScanFiles"
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
