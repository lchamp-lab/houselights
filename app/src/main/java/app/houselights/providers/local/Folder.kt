package app.houselights.providers.local

/** A folder as home and the settings list it, without its credentials; its form edits the full [SourceEntity]. */
data class Folder(
    val id: Long,
    val name: String,
    val kind: SourceKind,
    val protocol: Protocol,
    val host: String,
    /** Where it is, without scheme nor credentials: "nas/volume1/media/films". */
    val address: String,
    /** When its last scan ended; 0 until one has. */
    val lastScanAt: Long,
)

internal fun SourceEntity.toFolder() = Folder(id, name, kind, protocol, host, address(withScheme = false), lastScanAt)
