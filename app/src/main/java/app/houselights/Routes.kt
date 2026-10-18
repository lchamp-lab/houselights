package app.houselights

import app.houselights.core.ArtworkKind
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Quality
import app.houselights.ui.onboarding.FolderField
import kotlinx.serialization.Serializable

/** Type-safe navigation routes: each screen and its arguments, checked at compile time. */
object Route {
    @Serializable
    data object Welcome

    @Serializable
    data object JellyfinLogin

    /** Adds a folder, or edits the folder [folderId] in place, focus starting on [focus]. */
    @Serializable
    data class AddFolder(val folderId: Long? = null, val focus: FolderField = FolderField.FIRST)

    @Serializable
    data object Home

    /**
     * A library's wall, opened pre-filtered from a facet ([filter]; [quality] by enum name, [person] labelled
     * [personName]). A films wall may show the source's [sagas] (a library id) instead.
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

    @Serializable
    data class Nfo(val id: String)

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
