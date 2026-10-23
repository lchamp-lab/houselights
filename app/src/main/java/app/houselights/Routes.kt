package app.houselights

import app.houselights.core.ArtworkKind
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Quality
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

    /**
     * Adds a folder, or edits the folder [folderId] in place, focus starting on [focus]. A first folder added with
     * [signOut] signs out of the server it replaces rather than keep it.
     */
    @Serializable
    data class AddFolder(val folderId: Long? = null, val focus: FolderField = FolderField.FIRST, val signOut: Boolean = false)

    @Serializable
    data object Home

    /**
     * A library's wall, opened pre-filtered from a facet ([filter]; [quality] by enum name, [person] labelled
     * [personName]). A films wall may show the source's [sagas] (a library id) instead. Opened as a [place] of the
     * bar, it lies under it.
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
        val place: Boolean = false,
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
