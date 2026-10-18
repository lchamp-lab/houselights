package app.houselights.core

import kotlinx.serialization.json.Json

/**
 * The app's JSON format, ignoring unknown keys (a newer server's fields). Settings and media info are written
 * with it too, so an option added here changes what they store; Jellyfin and TMDB clients have their own.
 */
val lenientJson: Json = Json { ignoreUnknownKeys = true }
