package app.houselights.core

import kotlinx.serialization.json.Json

/**
 * JSON as the app reads it: keys it does not know are passed over (a newer player's or server's
 * fields, one a later version of the app dropped). The settings and each file's media info are also
 * written with it, so an option added here changes what they hold. Jellyfin's and TMDB's clients
 * keep formats of their own.
 */
val lenientJson: Json = Json { ignoreUnknownKeys = true }
