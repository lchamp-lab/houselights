package app.houselights.providers.local

import android.util.Log
import app.houselights.core.NfoInfo
import app.houselights.core.NfoParser
import app.houselights.core.suspendRunCatching

/** What reading a text file gave: its text, or a failure the next scan tries again. */
internal sealed interface TextRead {
    data class Text(val text: String) : TextRead

    /**
     * A lock, a refusal, a timeout: not the file's last word. Even refused, it is read again at the next
     * scan (a small read, and the right given back on the NAS is seen at once), but the item keeps what
     * it had, so the screens are not told of a change.
     */
    data class Failed(val cause: Throwable) : TextRead
}

/** The NFO an item keeps: where it lies and what it says, both or neither. */
internal data class KeptNfo(val path: String?, val text: String?) {
    val info: NfoInfo? by lazy { text?.let(NfoParser::parse) }
}

/** What both indexers read from a folder's files: NFO text, what a video holds, local artwork. */
internal class ScanFiles(
    private val dao: LibraryDao,
    private val artwork: ArtworkCache,
    private val browsers: (SourceEntity) -> RemoteBrowser,
) {
    /** A NAS that stops answering throws, so the file is tried again later; any other failure is [TextRead.Failed]. */
    suspend fun readText(source: SourceEntity, file: RemoteFile): TextRead = suspendRunCatching<TextRead> {
        val length = file.size.coerceIn(0, MAX_NFO_BYTES.toLong()).toInt().takeIf { it > 0 } ?: MAX_NFO_BYTES
        TextRead.Text(String(browsers(source).readPrefix(source, file.relativePath, length), Charsets.UTF_8))
    }.getOrElse { e ->
        if (ScanErrors.isUnreachable(e)) throw e
        Log.w(TAG, "Could not read ${file.relativePath}; it is read again at the next scan", e)
        TextRead.Failed(e)
    }

    /**
     * The NFO [item] keeps, from the [file] next to it: read now; or, when it cannot be read now, what
     * the item had. A path is never kept without its text: the next scan reads the file again.
     */
    suspend fun nfo(source: SourceEntity, file: RemoteFile?, item: ItemEntity): KeptNfo =
        when (val read = file?.let { readText(source, it) }) {
            null -> KeptNfo(null, null)
            is TextRead.Text -> KeptNfo(file.relativePath, read.text)
            is TextRead.Failed -> KeptNfo(item.nfoPath.takeIf { item.nfo != null }, item.nfo)
        }

    /**
     * Reads the file once (again for an older probe, or when it changed) and stores what it contains,
     * with format badges for filters.
     */
    suspend fun withMediaInfo(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity {
        if (!MediaProbe.isStale(item.mediaInfo) && !item.sizeChanged(file)) return item
        val updated = mediaFields(source, file, item)
        dao.saveScanned(updated)
        return updated
    }

    suspend fun mediaFields(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity {
        val media = when (val probe = MediaProbe.probe(source, file, browsers(source))) {
            is ProbeResult.Read -> probe.info
            // Read again for an older probe and cut short: what the file was known to hold stays until then.
            is ProbeResult.Failed -> MediaProbe.decode(item.mediaInfo)?.takeIf { !item.sizeChanged(file) && probe.info.video == null }
                ?: probe.info
        }
        val badges = media.badges().ifEmpty { FilenameParser.badges(file.relativePath) }
        return item.copy(mediaInfo = MediaProbe.encode(media), badges = badges, fileSize = file.size)
    }

    /** The cached copy of an image placed next to a video or a show (see [ArtworkCache.copy]). */
    suspend fun copyArtwork(source: SourceEntity, file: RemoteFile): String? = artwork.copy(source, file, browsers(source))

    private companion object {
        const val TAG = "ScanFiles"
        const val MAX_NFO_BYTES = 1 shl 20
    }
}

/**
 * The file is not the one read last: replaced under the same name, or read while it was being
 * copied. Rows that never knew their size are not read again for it.
 */
internal fun ItemEntity.sizeChanged(file: RemoteFile): Boolean = fileSize != null && fileSize != file.size

/** NFO stream details stand in when the file itself could not be read. */
internal fun ItemEntity.withNfoStreams(nfo: NfoInfo?): ItemEntity {
    val streams = nfo?.streams ?: return this
    val media = MediaProbe.decode(mediaInfo)
    if (media?.video != null) return this
    val merged = NfoStreamDetails.toMediaInfo(streams, media)
    return copy(mediaInfo = MediaProbe.encode(merged), badges = merged.badges().ifEmpty { badges })
}
