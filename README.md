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
    Each film or show can be corrected from its page: pick its poster, backdrop or logo among every image TMDB
    has (with a live preview), or identify it manually by title, year or IMDb id. These choices are kept by
    later scans and full refreshes, and "Automatic" hands the decision back to Houselights.
    Films of a saga are gathered into collections, from the NFO's set or TMDB: a Collections library,
    a page per collection (its films in release order, how many you own out of those released, Play
    starting the next film to watch), and the saga on each film's page. By default a collection shows
    once you own two of its films; Settings can show every one, or none.
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

Before a change is pushed, the same checks as CI (`.gitea/workflows/ci.yml`) run with
`./gradlew testDebugUnitTest lintDebug detekt assembleRelease`. Lint and detekt keep today's findings in
`app/lint-baseline.xml` and `app/detekt-baseline.xml`: new code has to pass.

### Signing

Release builds are signed with the debug key unless a `keystore.properties` file (git-ignored) sits at the
root of the project:

```properties
storeFile=/path/to/houselights-release.jks
storePassword=…
keyAlias=houselights
keyPassword=…
```

Choose before installing on a player: Android only updates an app signed with the same key, so changing keys
later means uninstalling, and losing the library and watch history kept on the player.

An APK given to other people should not carry your personal TMDB key: build it without one (users then enter
their own), or with a key created for the project that you can revoke.

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

- Prefer NFS: it needs no credentials. With SMB, the Zidoo player receives the credentials inside the file
  URI, and Android logs started intents (readable over adb), so use a **dedicated read-only NAS account** for
  your player. Credentials are only ever given to the Zidoo player installed with the system: on another
  device, a password-protected SMB folder can't be played.
- Passwords, the Jellyfin token, a personal TMDB key and path mappings are stored sealed with a key kept in
  the Android Keystore, and Android backup is off: copying the app's files elsewhere reveals none of them.
- Jellyfin is reached over HTTPS when the server offers it; the settings show when the connection is not
  encrypted. Signing out of Jellyfin (changing source) revokes the app's token on the server.
- Artwork named by NFO files is fetched over HTTPS only. TMDB is reached over HTTPS only; plain HTTP stays
  allowed for servers on the home network.
- `nfs-client` depends on Netty 3.10 (end of life). Its known vulnerabilities concern HTTP and compression
  codecs that NFS doesn't use; it is watched until a maintained NFS client replaces it.

## License

[GPL-3.0](LICENSE). The Archivo typeface is licensed under the SIL Open Font License 1.1
([third_party/archivo/OFL.txt](third_party/archivo/OFL.txt)).

This product uses the TMDB API but is not endorsed or certified by TMDB.
