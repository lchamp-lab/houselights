package app.houselights.player

import app.houselights.core.PathMapping
import app.houselights.core.escapeUriPath
import app.houselights.core.escapeUriPathEnds

/**
 * Turns a file path as seen by a media server into a URI the Zidoo player can open.
 * Returns null when no rule applies: the caller then falls back to HTTP streaming.
 *
 * A file's own name may hold "#", "?" or "%": escaped, they stay part of the path instead of
 * starting a fragment or a query the player would cut the name at.
 */
object PathResolver {
    fun resolve(serverPath: String?, mappings: List<PathMapping>): String? {
        if (serverPath.isNullOrBlank()) return null
        val normalized = serverPath.replace('\\', '/')

        // 1. User-defined rules, longest prefix first. What follows the prefix is the server's own path.
        mappings
            .filter { it.from.isNotBlank() && it.to.isNotBlank() }
            .sortedByDescending { it.from.length }
            .firstOrNull { normalized.startsWith(it.from.replace('\\', '/').trimEnd('/') + "/") }
            ?.let { m ->
                val from = m.from.replace('\\', '/').trimEnd('/')
                return m.to.trimEnd('/') + escapeUriPath(normalized.removePrefix(from))
            }

        // 2. The server already exposes a network URI ("shared network folder" option).
        if (normalized.startsWith("smb://") || normalized.startsWith("nfs://")) return escapeUriPathEnds(normalized)

        // 3. Windows UNC path: \\host\share\... -> smb://host/share/...
        if (normalized.startsWith("//")) return escapeUriPathEnds("smb:$normalized")

        return null
    }
}
