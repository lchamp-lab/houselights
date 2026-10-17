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

## Install

Each release is on the [Releases page](https://github.com/lchamp-lab/houselights/releases): one APK, its
SHA-256, and what changed. A release installs over an earlier one and keeps its library, watch history and
settings. Android may first ask you to allow installing apps from the file manager or downloader you use.

- **On a Zidoo**: copy `Houselights-x.y.z.apk` to a USB stick, plug it in, and open it from the player's
  file manager.
- **On any Android TV**, with the [Downloader](https://www.aftvnews.com/downloader/) app: enter
  `https://github.com/lchamp-lab/houselights/releases/latest/download/Houselights.apk`, always the latest
  release.
- **Kept up to date by itself**, with [Obtainium](https://github.com/ImranR98/Obtainium): add
  `https://github.com/lchamp-lab/houselights`; it installs each new release as it comes.
- **From a computer**: `adb install -r Houselights-x.y.z.apk`.

Every release is signed with the project's key. Android refuses to update the app from an APK signed with
another one, and you can check an APK yourself (`apksigner verify --print-certs`, or the AppVerifier app):
its certificate's SHA-256 is

```
C4:2C:70:C6:C8:AE:CA:07:F8:38:E3:90:6E:8F:D1:0E:17:86:33:9A:30:D4:63:30:0E:51:C2:7E:6A:55:70:73
```

A release may come without a TMDB key: the app then asks for yours (free, from
[themoviedb.org](https://www.themoviedb.org/settings/api)), in Settings > Library > TMDB API key.

## Support

If Houselights earns its place under your TV, you can buy the project a coffee on
[Ko-fi](https://ko-fi.com/lchamp_lab); the app shows the same page as a QR code, for your phone, in
Settings > About > Support Houselights.

## Building

Requirements: JDK 17 and the Android SDK (platform 35).

```sh
# Optional but recommended: a TMDB API key (free, https://www.themoviedb.org/settings/api)
echo "tmdb.apiKey=YOUR_KEY" >> local.properties

# Once, before the first installation on a player: your signing key (Signing, below)
./gradlew assembleRelease
scripts/install.sh release          # the APK with its baseline profile, compiled at install
```

The key can also come from the `TMDB_API_KEY` environment variable (CI), or be entered by users in the app
settings. **Never commit it**: `local.properties` is git-ignored.

To try the app in an Android TV emulator: `scripts/preview.sh`.

Before a branch is merged, `scripts/check.sh` runs the checks of CI (`.github/workflows/ci.yml`) and builds
the debug APK: branches are merged locally and only `main` is pushed, so CI sees a branch once it is on
`main`. It runs `./gradlew testDebugUnitTest lintDebug detekt detektDebug detektDebugUnitTest
assembleRelease assembleDebug`, the release signed with the debug key without a signing key; on a machine
short of memory, `scripts/check.sh --heap 2g --workers 2 --in-process --no-daemon` takes CI's settings.
`detekt` reads the sources alone; `detektDebug` and `detektDebugUnitTest` add the rules that need the
compiler's types (dispatchers, `!!`, unsafe casts). Lint and detekt keep today's findings in
`app/lint-baseline.xml`, `app/detekt-baseline.xml`, `app/detekt-baseline-debug.xml` and
`app/detekt-baseline-debugUnitTest.xml`: new code has to pass.

The release's `versionCode` is the number of commits (`git rev-list --count HEAD`), or `-PversionCode=…`
when given: Android then refuses to install a release over a newer one, whose library database it could
not open. CI checks out the whole history for it. The debug and `bench` builds stay at 1, so that the
build of a branch with fewer commits installs over another one; a database a newer branch changed may then
not open, and clearing the app's data starts it afresh. A debug or `bench` build installed before this rule
carries a higher `versionCode`, which Android will not go below: once, install the debug APK with
`adb install -r -d app/build/outputs/apk/debug/app-debug.apk` (a debuggable app may go back, its data
kept), and uninstall the bench build (`adb uninstall app.houselights.bench`).

### Signing

A release is signed with your own key, which a `keystore.properties` file (git-ignored) at the root of the
project names:

```sh
mkdir -p ~/.config/houselights && chmod 700 ~/.config/houselights
keytool -genkeypair -keystore ~/.config/houselights/houselights-release.jks -storetype PKCS12 \
    -alias houselights -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Houselights, O=Houselights"
```

```properties
storeFile=/home/you/.config/houselights/houselights-release.jks
storePassword=…
keyAlias=houselights
keyPassword=…
```

Choose it before installing on a player, and keep a copy of both files: Android only updates an app signed
with the same key, so changing keys later means uninstalling, and losing the library and watch history kept
on the player. Without the file, `assembleRelease` stops before writing the APK and says so, rather than
sign it with the debug key of the machine that built it. A build that will not be installed (CI, a check)
can ask for that key all the same with `-Phouselights.debugSignedRelease=true`. The `bench` build, which
installs beside the release, and the builds `:baselineprofile` makes are always signed with the debug key,
even with the file: those bear the release's package name (Benchmarks, below).

An APK given to other people should not carry your personal TMDB key: build it without one (users then enter
their own), or with a key created for the project that you can revoke.

### Releases

A tag `vX.Y.Z` publishes a release (`.github/workflows/release.yml`); `vX.Y.Z-beta.N` publishes a
pre-release. Set `versionName` in `app/build.gradle.kts` first: the workflow stops when the tag says
otherwise. It runs the checks of CI, builds the release signed with the project's key, stops unless its
certificate is the one above, and publishes the APK (also as `Houselights.apk`, the address that always
gives the latest), its SHA-256, and notes made of the commits since the previous tag.

```sh
git tag -a v0.2.0 -m "Houselights 0.2.0" && git push origin v0.2.0
```

It needs three repository secrets: `HOUSELIGHTS_KEYSTORE` (the keystore in base64:
`base64 -w0 houselights-release.jks`), `HOUSELIGHTS_KEYSTORE_PASSWORD`, and, optionally, `TMDB_API_KEY`, a
key created for the project.

## Performance

The players Houselights runs on are small machines. Two tools keep it smooth on them.

### Baseline profile

An app is shipped as bytecode: until Android compiles it, it runs interpreted and compiles on the fly,
and browsing stutters. This is the state of every fresh install and of every update. The baseline profile
lists the code of the main journeys (start-up, home's rows, a library's wall, a detail page, a series, the
settings), for Android to compile ahead of time. When it does depends on how the app is installed:

- **With `scripts/install.sh`** (`adb`, the APK and its dex metadata `.dm` in one session): compiled
  during the installation, smooth from the first launch.
- **The APK alone** (a file manager, a downloader app): `profileinstaller` hands the profile to Android at
  the first launch, and Android compiles it in the background, when the player is plugged in **and idle**:
  in standby, screen off, for a while. A player never left in standby keeps running the interpreter.

Measured on a Zidoo Z1000 Pro (bench build, the same journey, frames over 16.7 ms), home and a detail page:

| State | Home | Detail |
|---|---|---|
| Just installed, nothing compiled | 7 to 12 % | 3 to 5 % |
| Installed with `scripts/install.sh` | 0.6 to 0.8 % | 0.7 to 1.6 % |
| APK alone, after the background compilation | 0.5 to 0.7 % | 0.4 to 0.9 % |

The profile is checked in (`app/src/main/generated/baselineProfiles/`), so building needs no device.
Generate it again after a change to the screens, before a release:

```sh
# The library the journeys browse: a NAS folder the emulator reads over NFS (git-ignored file).
cat >> local.properties <<'PROPS'
baselineprofile.host=192.168.1.10
baselineprofile.export=/volume1/media
baselineprofile.movies=films
baselineprofile.shows=series
PROPS

scripts/preview.sh                      # the Android TV 9 emulator
adb root                                # Android 9 needs root to collect a profile
ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
```

The journeys live in `baselineprofile/` (`Journeys.kt`): on a fresh install they add the folders through
the app itself, then browse by what the screen says, in the device's language.

### Benchmarks

On a player, not the emulator, the same journeys measure cold start-up and frames while browsing,
without any compilation (a fresh install) and with the baseline profile:

```sh
ANDROID_SERIAL=<player> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

**Never run it, or any `connected…ReleaseAndroidTest`, on a player that holds the release you watch with.**
The build measured bears the release's package name (`app.houselights`), and the run ends by uninstalling
it. Signed with the debug key, it cannot replace a release signed with yours: its installation fails. A
release signed with this machine's debug key, though, would be replaced, then uninstalled, and the library
and watch history with it. To measure on that player, install the `bench` build instead (below).

Cold start-up empties the page cache first, which needs root: on a player's user build it is skipped.
Results: `baselineprofile/build/outputs/connected_android_test_additional_output/`, with a Perfetto trace
per run. On the Zidoo (CPU time of a frame, main and render threads, 5 runs each):

| Journey | No compilation, P95 / P99 | Baseline profile, P95 / P99 |
|---|---|---|
| Home's rows | 39 / 77 ms | 33 / 45 ms |
| A film's page, down to its cast | 45 / 176 ms | 36 / 60 ms |

To measure by hand, install the `bench` build (optimised like a release, beside it) with
`scripts/install.sh bench`, then read `adb shell dumpsys gfxinfo app.houselights.bench`. An APK installed
alone measures the interpreter, not what a viewer sees after the first night.

## Project layout

```
app/src/main/java/app/houselights/
  core/         backend-independent model and the MediaProvider interface
  providers/    local (SMB + TMDB + Room), jellyfin
  player/       Zidoo player launch, progress tracking, path mapping
  ui/design/    design system: tokens and components (see DESIGN.md)
  ui/           Compose for TV screens
baselineprofile/  baseline profile generator and benchmarks (macrobenchmark), run on a device
```

## Conventions

- Text people read follows the device's language: dates, durations, sizes, decimal commas. Data the app
  keeps or compares (sort keys, file names in the cache, numbers in file names) is written with
  `Locale.ROOT`, so a device in Arabic or Persian sorts and finds files like any other.
- Providers throw; screens turn failures into a `UiError`, a message people can act on, and log the
  failure itself. Cancellation is not a failure (`suspendRunCatching`).
- `Dispatchers.IO` wraps blocking reads only (SMB, NFS, files); Room and Retrofit suspend on their own.
- What a source can do is read from `ProviderCapabilities`, never from which source it is.

## Sources

Both sources show the same home, libraries, detail pages, technical sheets and playback. What only
"My files" offers, because the app builds that library itself:

- a film's saga on its page (Jellyfin's collections show as a library of their own);
- choosing a title's poster, backdrop and logo, and correcting its match;
- the NFO viewer, the decade filter, and quality filters beyond Ultra HD (HDR, Dolby Vision).

## Security notes

- Prefer NFS: it needs no credentials. With SMB, the Zidoo player receives the credentials inside the file
  URI, and Android logs started intents (readable over adb), so use a **dedicated read-only NAS account** for
  your player. Credentials are only ever given to the Zidoo player installed with the system: on another
  device, a password-protected SMB folder can't be played.
- Passwords, the Jellyfin token, a personal TMDB key and path mappings are stored sealed with a key kept in
  the Android Keystore, and Android backup is off: copying the app's files elsewhere reveals none of them.
- Jellyfin is reached over HTTPS when the server offers it; the settings show when the connection is not
  encrypted. Signing out of Jellyfin (changing source) revokes the app's token on the server.
- A Jellyfin stream played without path mappings carries the app's token in its URL (`api_key`). Outside
  the Zidoo player, it goes to another video app only through Android's app chooser: you pick the app,
  none takes the token without being asked.
- Artwork named by NFO files is fetched over HTTPS only. TMDB is reached over HTTPS only; plain HTTP stays
  allowed for servers on the home network.
- `nfs-client` depends on Netty 3.10 (end of life). Its known vulnerabilities concern HTTP and compression
  codecs that NFS doesn't use; it is watched until a maintained NFS client replaces it.

## License

Houselights is free software: you can redistribute it and/or modify it under the terms of the GNU General
Public License as published by the Free Software Foundation, either version 3 of the License, or (at your
option) any later version ([LICENSE](LICENSE), `GPL-3.0-or-later`).

The Archivo typeface is licensed under the SIL Open Font License 1.1
([third_party/archivo/OFL.txt](third_party/archivo/OFL.txt)). The Play glyph comes from Material Icons
(Apache License 2.0).

This product uses the TMDB API but is not endorsed or certified by TMDB. The TMDB logo is a trademark of
TMDB, shown under its attribution guidelines; it is not covered by the GPL.
