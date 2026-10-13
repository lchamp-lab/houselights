# Posterwall

A cinema-style media browser for **Zidoo** players (Android TV boxes such as the Z1000 Pro, Z9X, UHD series)
that keeps playback in the **native Zidoo player** — so HDR10, HDR10+, Dolby Vision, audio passthrough and the
player's own menus all keep working.

> Status: early development. Not affiliated with Zidoo, Jellyfin, Emby or Plex.

## Features

- Immersive 10-foot UI built with Jetpack Compose for TV: full-screen backdrops, clear logos, poster rows,
  detail pages with cast, seasons and episodes. Designed for D-pad navigation.
- **Library sources**
  - **My files**: scans SMB shares on your NAS, identifies movies and TV shows from file and folder names,
    and fetches artwork and details from TMDB. Resume points, watched state and favorites are stored locally.
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
app/src/main/java/app/posterwall/
  core/         backend-independent model and the MediaProvider interface
  providers/    local (SMB + TMDB + Room), jellyfin
  player/       Zidoo player launch, progress tracking, path mapping
  ui/           Compose for TV screens and components
```

## Security notes

The Zidoo player receives SMB credentials inside the file URI, and Android logs started intents.
Use a **dedicated read-only NAS account** for your player.

## License

[GPL-3.0](LICENSE)

This product uses the TMDB API but is not endorsed or certified by TMDB.
