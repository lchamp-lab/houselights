package app.houselights.player

import app.houselights.core.PathMapping
import app.houselights.core.escapeUriPath
import app.houselights.core.escapeUriPathEnds

/**
 * Turns a media server's file path into a URI the Zidoo player opens, escaping "#", "?" and "%" so the
 * player does not cut the name; null when no rule applies, and the caller streams over HTTP.
 */
object PathResolver {
    fun resolve(serverPath: String?, mappings: List<PathMapping>): String? {
        if (serverPath.isNullOrBlank()) return null
        val normalized = serverPath.replace('\\', '/')
        // 1. User-defined rules ([mapped]).
        return mapped(normalized, mappings) ?: when {
            // 2. The server already exposes a network URI ("shared network folder" option).
            normalized.startsWith("smb://") || normalized.startsWith("nfs://") -> escapeUriPathEnds(normalized)
            // 3. Windows UNC path: \\host\share\... -> smb://host/share/...
            normalized.startsWith("//") -> escapeUriPathEnds("smb:$normalized")
            else -> null
        }
    }

    /** User-defined rules, longest prefix first. What follows the prefix is the server's own path. */
    private fun mapped(path: String, mappings: List<PathMapping>): String? = mappings
        .filter { it.from.isNotBlank() && it.to.isNotBlank() }
        .sortedByDescending { it.from.length }
        .firstOrNull { path.startsWith(it.from.replace('\\', '/').trimEnd('/') + "/") }
        ?.let { m ->
            val from = m.from.replace('\\', '/').trimEnd('/')
            m.to.trimEnd('/') + escapeUriPath(path.removePrefix(from))
        }
}
