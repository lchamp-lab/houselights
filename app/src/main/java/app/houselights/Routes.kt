package app.houselights

import app.houselights.core.ArtworkKind
import app.houselights.core.LibraryKind
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

    @Serializable
    data class Library(val id: String, val kind: LibraryKind, val title: String? = null)

    @Serializable
    data class Item(val id: String)

    @Serializable
    data class Artwork(val id: String, val kind: ArtworkKind)

    @Serializable
    data class Identify(val id: String)

    @Serializable
    data class Nfo(val id: String)

    @Serializable
    data object Settings

    /** Debug builds only. */
    @Serializable
    data object Catalog
}
