package app.houselights.providers.local

import app.houselights.core.NfoInfo
import app.houselights.core.suspendRunCatching

/** What both indexers read from a folder's files: NFO text, and what a video holds. */
internal class ScanFiles(private val dao: LibraryDao, val artwork: ArtworkCache) {
    /** null when the file can't be read; a NAS that stops answering throws, so the file is tried again later. */
    suspend fun readText(source: SourceEntity, file: RemoteFile): String? = suspendRunCatching {
        val length = file.size.coerceIn(0, MAX_NFO_BYTES.toLong()).toInt().takeIf { it > 0 } ?: MAX_NFO_BYTES
        String(RemoteBrowser.of(source).readPrefix(source, file.relativePath, length), Charsets.UTF_8)
    }.getOrElse { e -> if (ScanErrors.isUnreachable(e)) throw e else null }

    /** Reads the file once (again for an older probe) and stores what it contains, with format badges for filters. */
    suspend fun withMediaInfo(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity {
        if (!MediaProbe.isStale(item.mediaInfo)) return item
        val updated = mediaFields(source, file, item)
        dao.saveScanned(updated)
        return updated
    }

    suspend fun mediaFields(source: SourceEntity, file: RemoteFile, item: ItemEntity): ItemEntity {
        val media = MediaProbe.probe(source, file)
        val badges = media.badges().ifEmpty { FilenameParser.badges(file.relativePath) }
        return item.copy(mediaInfo = MediaProbe.encode(media), badges = badges)
    }

    private companion object {
        const val MAX_NFO_BYTES = 1 shl 20
    }
}

/** NFO stream details stand in when the file itself could not be read. */
internal fun ItemEntity.withNfoStreams(nfo: NfoInfo?): ItemEntity {
    val streams = nfo?.streams ?: return this
    val media = MediaProbe.decode(mediaInfo)
    if (media?.video != null) return this
    val merged = NfoStreamDetails.toMediaInfo(streams, media)
    return copy(mediaInfo = MediaProbe.encode(merged), badges = merged.badges().ifEmpty { badges })
}
