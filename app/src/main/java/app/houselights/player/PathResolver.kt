package app.houselights.player

import app.houselights.core.PathMapping

/**
 * Turns a file path as seen by a media server into a URI the Zidoo player can open.
 * Returns null when no rule applies: the caller then falls back to HTTP streaming.
 */
object PathResolver {
    fun resolve(serverPath: String?, mappings: List<PathMapping>): String? {
        if (serverPath.isNullOrBlank()) return null
        val normalized = serverPath.replace('\\', '/')

        // 1. User-defined rules, longest prefix first.
        mappings
            .filter { it.from.isNotBlank() && it.to.isNotBlank() }
            .sortedByDescending { it.from.length }
            .firstOrNull { normalized.startsWith(it.from.replace('\\', '/').trimEnd('/') + "/") }
            ?.let { m ->
                val from = m.from.replace('\\', '/').trimEnd('/')
                return m.to.trimEnd('/') + normalized.removePrefix(from)
            }

        // 2. The server already exposes a network URI ("shared network folder" option).
        if (normalized.startsWith("smb://") || normalized.startsWith("nfs://")) return normalized

        // 3. Windows UNC path: \\host\share\... -> smb://host/share/...
        if (normalized.startsWith("//")) return "smb:$normalized"

        return null
    }
}
