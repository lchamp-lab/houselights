# Houselights

A cinema-style media browser for **Zidoo** players (Android TV boxes such as the Z1000 Pro, Z9X, UHD series)
that keeps playback in the **native Zidoo player** — so HDR10, HDR10+, Dolby Vision, audio passthrough and the
player's own menus all keep working.

> Status: early development. Not affiliated with Zidoo, Jellyfin, Emby or Plex.

## Features

- Cinematic 10-foot UI built with Jetpack Compose for TV on a dedicated design system ([DESIGN.md](DESIGN.md)):
  full-screen backdrops whose light spills into the room, clear logos, poster rows and walls, detail pages
  with cast, seasons and episodes, and the house lights dimming before the film starts.
- **Library sources**
  - **My files**: scans NFS or SMB shares on your NAS, identifies movies and TV shows from file and folder names,
    and fetches artwork and details from TMDB. Kodi-style NFO files and local artwork (poster.jpg, fanart.jpg,
    clearlogo.png…) take precedence, and NFO ids identify titles exactly. Technical details (resolution, HDR,
    Dolby Vision, audio formats, subtitles) are read from the files themselves. Resume points, watched state
    and favorites are stored locally.
  - **Jellyfin**: browses your server and reports playback progress back to it.
  - Emby and Plex: planned.
- **Native playback**: files are opened directly by the Zidoo player (`smb://`, `nfs://` or HTTP). Progress
  is read from the Zidoo local control API while playing, so resume points stay in sync.
- 4K60 interface output when the device allows it.
- English and French UI.

## Building

Requirements: JDK 17 and the Android SDK (platform 35).

```sh
# Optional but recommended: a TMDB API key (free, https://www.themoviedb.org/settings/api)
echo "tmdb.apiKey=YOUR_KEY" >> local.properties

./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

The key can also come from the `TMDB_API_KEY` environment variable (CI), or be entered by users in the app
settings. **Never commit it**: `local.properties` is git-ignored.

To try the app in an Android TV emulator: `scripts/preview.sh`.

## Project layout

```
app/src/main/java/app/houselights/
  core/         backend-independent model and the MediaProvider interface
  providers/    local (SMB + TMDB + Room), jellyfin
  player/       Zidoo player launch, progress tracking, path mapping
  ui/design/    design system: tokens and components (see DESIGN.md)
  ui/           Compose for TV screens
```

## Security notes

Prefer NFS: it needs no credentials. With SMB, the Zidoo player receives the credentials inside the file URI,
and Android logs started intents, so use a **dedicated read-only NAS account** for your player.

## License

[GPL-3.0](LICENSE). The Archivo typeface is licensed under the SIL Open Font License 1.1
([third_party/archivo/OFL.txt](third_party/archivo/OFL.txt)).

This product uses the TMDB API but is not endorsed or certified by TMDB.
