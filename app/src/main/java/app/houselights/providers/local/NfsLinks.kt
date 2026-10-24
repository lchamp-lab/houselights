package app.houselights.providers.local

import java.io.FileNotFoundException
import java.io.IOException

/**
 * A symbolic link neither the app nor the player can follow: absolute, leaving the export, or in a loop. Its message
 * names no path, as failures are also told apart by their words; the walk logs the path.
 */
internal class UnfollowedLinkException(why: String) : IOException("Symbolic link not followed: $why")

/** An entry as reached: its path from the export root without links, and its own handle and attributes. */
internal class NfsTarget(val path: String, val entry: NfsEntry)

/**
 * Follows symbolic links as the player's NFS client does: a relative target from the link's folder, within the
 * export. An absolute target resolves on the client, where the NAS's paths mean nothing, so it is not followed.
 * [folderAt] gives the handle of a folder by its path from the export root, without links.
 */
internal class NfsLinks(private val export: NfsExport, private val folderAt: (String) -> ByteArray) {
    /** [entry] of the folder at [folder], or what it leads to when a link; a broken link throws FileNotFoundException. */
    fun reached(folder: String, entry: NfsEntry): NfsTarget =
        if (entry.attributes?.isLink == true) follow(folder, entry) else NfsTarget(joined(folder, entry.name), entry)

    private fun follow(folder: String, link: NfsEntry): NfsTarget = Following(folder, target(link)).target()

    /** The names [link]'s target goes through, from the link's folder. */
    private fun target(link: NfsEntry): List<String> {
        val target = export.readlink(link.handle ?: throw FileNotFoundException("A symbolic link without its handle"))
        if (target.startsWith('/')) throw UnfollowedLinkException("its target is absolute")
        return target.split('/').filter { it.isNotEmpty() }
    }

    /** A link being followed: the folder reached so far, from [folder], and the names left. */
    private inner class Following(folder: String, names: List<String>) {
        private val at = folder.split('/').filterTo(mutableListOf()) { it.isNotEmpty() }
        private val left = ArrayDeque(names)
        private var dir: ByteArray? = null
        private var links = 1

        fun target(): NfsTarget {
            var found: NfsTarget? = null
            while (found == null) found = next(left.removeFirstOrNull())
            return found
        }

        /** Goes through [name]; the target once reached, a folder when the names end with "." or "..". */
        private fun next(name: String?): NfsTarget? = when (name) {
            null -> NfsTarget(at.joinToString("/"), NfsEntry(at.lastOrNull().orEmpty(), handle(), FOLDER))
            "." -> null
            ".." -> {
                at.removeLastOrNull() ?: throw UnfollowedLinkException("its target leaves the export")
                dir = null
                null
            }
            else -> enter(name)
        }

        /** Looks [name] up: a link puts its target's names first, a folder is entered, the last name is the target. */
        private fun enter(name: String): NfsTarget? {
            val entry = export.lookup(handle(), name)
            when {
                entry.attributes?.isLink == true -> {
                    if (++links > MAX_LINKS) throw UnfollowedLinkException("a loop, or more than $MAX_LINKS links")
                    left.addAll(0, target(entry))
                }
                left.isEmpty() -> return NfsTarget(joined(at.joinToString("/"), name), entry)
                // A file there fails the next lookup, as a broken link does.
                else -> {
                    at += name
                    dir = entry.handle
                }
            }
            return null
        }

        private fun handle() = dir ?: folderAt(at.joinToString("/"))
    }

    companion object {
        /** Links met while following one, so that a loop ends. */
        const val MAX_LINKS = 8

        private val FOLDER = NfsAttributes(isFolder = true, size = 0, lastModified = null)

        /** [name] in the folder at [folder], from the export root ("" for the root). */
        fun joined(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"
    }
}
