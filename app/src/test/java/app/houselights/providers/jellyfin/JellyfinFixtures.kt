package app.houselights.providers.jellyfin

import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SourceConfig

/** The account provider tests are signed in with. */
val account = SourceConfig.Jellyfin("http://nas:8096", "Maison", "u1", "lea", "token")

/** Heat, item "f1" on the test server. */
val film = MediaItem("f1", MediaKind.MOVIE, "Heat")
