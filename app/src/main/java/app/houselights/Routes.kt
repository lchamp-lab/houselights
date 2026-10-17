package app.houselights

import app.houselights.core.ArtworkKind
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Quality
import app.houselights.ui.onboarding.FolderField
import kotlinx.serialization.Serializable

/** The app's screens and what each one needs, checked when the app is built rather than when it runs. */
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
     * A library's wall. Opened on part of it (a format of the library in numbers, a person), it starts filtered
     * by what that part names, a field of [LibraryFilter] each: a [quality] (by name), a [picture], a [sound], a
     * kind of file ([fileType]), a [person], whom its filter menu names [personName].
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
            /** [library]'s wall, filtered by what [filter] names of a door (its status, genre and decade are a wall's own). */
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
