package app.houselights

import app.houselights.core.ArtworkKind
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Quality
import app.houselights.ui.home.WatchRow
import app.houselights.ui.onboarding.FolderField
import app.houselights.ui.onboarding.LoginFill
import kotlinx.serialization.Serializable

/** Type-safe navigation routes: each screen and its arguments, checked at compile time. */
object Route {
    @Serializable
    data object Welcome

    /** Signs in to Jellyfin, the form filled in with [server] and [user] when given, else as [blank] says ([fill]). */
    @Serializable
    data class JellyfinLogin(val server: String? = null, val user: String? = null, val blank: Boolean = false) {
        val fill: LoginFill
            get() = when {
                server != null -> LoginFill.Server(server, user)
                blank -> LoginFill.Blank
                else -> LoginFill.Current
            }

        companion object {
            fun of(fill: LoginFill) = when (fill) {
                LoginFill.Current -> JellyfinLogin()
                LoginFill.Blank -> JellyfinLogin(blank = true)
                is LoginFill.Server -> JellyfinLogin(fill.address, fill.user)
            }
        }
    }

    /** Adds a folder, or edits the folder [folderId] in place, focus starting on [focus]. */
    @Serializable
    data class AddFolder(val folderId: Long? = null, val focus: FolderField = FolderField.FIRST)

    @Serializable
    data object Home

    /** « À regarder »: home's rows, opened on [row] (a `WatchRow` by name), over home. */
    @Serializable
    data class Watch(val row: String) {
        /** The row it opens on; the first for a name no row has any more. */
        val watchRow: WatchRow get() = WatchRow.entries.find { it.name == row } ?: WatchRow.CONTINUE

        companion object {
            fun of(row: WatchRow) = Watch(row.name)
        }
    }

    /**
     * A library's wall, opened pre-filtered from a facet ([filter]; [quality] by enum name, [person] labelled
     * [personName]). A films wall shows the source's [sagas] (a library id) among its films, or alone ([view], a
     * `WallView` by name, which its saved state starts with).
     */
    @Serializable
    data class Library(
        val id: String,
        val kind: LibraryKind,
        val title: String? = null,
        val quality: String? = null,
        val picture: String? = null,
        val sound: String? = null,
        val fileType: String? = null,
        val person: String? = null,
        val personName: String? = null,
        val sagas: String? = null,
        val view: String? = null,
    ) {
        /** What the wall starts filtered by; null for the whole library. */
        val filter: LibraryFilter?
            get() = LibraryFilter(
                quality = quality?.let { name -> Quality.entries.find { it.name == name } },
                picture = picture,
                sound = sound,
                fileType = fileType,
                person = person,
            ).takeIf { it.activeCount > 0 }

        /** The library the wall shows; a blank title, as a server may send one, is none. */
        val wallLibrary: app.houselights.core.Library
            get() = app.houselights.core.Library(id, kind, title?.ifBlank { null })

        /** The source's sagas, which a films' wall shows among its films or alone; null when it shows none. */
        val sagasLibrary: app.houselights.core.Library?
            get() = sagas?.let { app.houselights.core.Library(it, LibraryKind.COLLECTIONS, null) }

        /** The wall's name: its library's title, else [kindName], what its kind is called. */
        fun wallTitle(kindName: String): String = wallLibrary.title ?: kindName

        companion object {
            /** [library]'s wall filtered by [filter]'s facets; status, genre and decade stay the wall's own. */
            fun of(library: app.houselights.core.Library, filter: LibraryFilter, personName: String? = null) = Library(
                library.id, library.kind, library.title, filter.quality?.name, filter.picture, filter.sound, filter.fileType,
                filter.person, personName.takeIf { filter.person != null },
            )
        }
    }

    @Serializable
    data class Item(val id: String)

    @Serializable
    data class Artwork(val id: String, val kind: ArtworkKind)

    @Serializable
    data class Identify(val id: String)

    /** A person's page: their titles, before and behind the camera ([app.houselights.core.Catalog.person]). */
    @Serializable
    data class Person(val id: String)

    /** A search across the library: titles, people, sagas ([app.houselights.core.Catalog.search]). */
    @Serializable
    data object Search

    @Serializable
    data object Settings

    /** Debug builds only. */
    @Serializable
    data object Catalog
}
