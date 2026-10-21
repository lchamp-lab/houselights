# Contributing to Houselights

## Setup

Requirements: JDK 17 and the Android SDK (platform 35).

```sh
echo "tmdb.apiKey=YOUR_KEY" >> local.properties           # optional, free at themoviedb.org
echo "trakt.clientId=YOUR_CLIENT_ID" >> local.properties  # optional, see README, Trakt
scripts/preview.sh                                        # start the Android TV 9 emulator
./gradlew installDebug
```

The TMDB key can also come from the `TMDB_API_KEY` environment variable, or be entered in the app. Trakt's
client ID comes from `TRAKT_CLIENT_ID` as well; a client secret (`trakt.clientSecret`, `TRAKT_CLIENT_SECRET`)
only for a Trakt app that has one, and its redirect URI (`trakt.redirectUri`) when not the project's (README,
Trakt). `local.properties` is git-ignored: **never commit a key.**

## Project layout

```
app/src/main/java/app/houselights/
  core/          backend-independent model and the MediaProvider interface
  providers/     local (SMB, NFS, TMDB, Room), jellyfin and trakt (the folders' Trakt account)
  player/        Zidoo player launch, progress tracking, path mapping
  ui/design/     design system: tokens and components (DESIGN.md)
  ui/            Compose for TV screens
baselineprofile/ baseline profile generator and benchmarks, run on a device
```

## Checks

Run `scripts/check.sh` before pushing or opening a pull request. It runs what CI runs
(`.github/workflows/ci.yml`):

```sh
./gradlew testDebugUnitTest lintDebug detekt detektDebug detektDebugUnitTest assembleRelease assembleDebug
```

- Without a signing key, the release is signed with the debug key for the check.
- On a machine short of memory: `scripts/check.sh --heap 2g --workers 2 --in-process --no-daemon`.
- `detekt` reads sources only; `detektDebug` and `detektDebugUnitTest` add type-aware rules (dispatchers,
  `!!`, unsafe casts).
- Existing findings are kept in `app/lint-baseline.xml` and `app/detekt-baseline*.xml`. New code must pass.
- The design system has its own guard tests (DESIGN.md, *Guard*).

## Conventions

- **Locale.** Text people read follows the device's language (dates, durations, sizes, decimal commas).
  Data the app keeps or compares (sort keys, cache file names) uses `Locale.ROOT`.
- **Errors.** Providers throw; screens turn failures into a `UiError` people can act on and log the cause.
  Cancellation is not a failure (`suspendRunCatching`).
- **Threads.** `Dispatchers.IO` wraps blocking reads only (SMB, NFS, files); Room and Retrofit suspend on
  their own.
- **Sources.** What a source can do is read from `ProviderCapabilities`, never from which source it is.
  Both sources share the same screens; saga pages, artwork choice, manual matching, the NFO viewer and
  advanced quality filters are specific to "My files".

## Versioning

The release `versionCode` is the commit count (`git rev-list --count HEAD`), or `-PversionCode=…`.
Android therefore refuses to install an older release over a newer one whose database it could not open;
CI fetches the full history for this.

Debug and `bench` builds stay at `versionCode` 1 so that any branch installs over another. If a newer branch
changed the database, clear the app's data. A debug or `bench` build installed before this rule must be
replaced once: `adb install -r -d app/build/outputs/apk/debug/app-debug.apk`, and
`adb uninstall app.houselights.bench`.

## Signing

A release is signed with your own key, referenced by a git-ignored `keystore.properties` at the project root:

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

- **Choose the key before the first install on a player and back up both files.** Android only updates an
  app signed with the same key; changing it means uninstalling, and losing the library kept on the player.
- Without the file, `assembleRelease` stops rather than sign with the debug key.
  `-Phouselights.debugSignedRelease=true` allows it for builds that will not be installed (CI, checks).
- The `bench` build and the `:baselineprofile` builds are always debug-signed.
- An APK shared with others should not embed your personal TMDB key: build it without one, or with a
  revocable project key.

To install a release with its baseline profile compiled at install time:

```sh
./gradlew assembleRelease
scripts/install.sh release      # or: scripts/install.sh bench
```

## Releases

A tag `vX.Y.Z` publishes a release; `vX.Y.Z-beta.N` a pre-release (`.github/workflows/release.yml`).

1. Set `versionName` in `app/build.gradle.kts` to the tag's version (the workflow checks it).
2. `git tag -a v0.2.0 -m "Houselights 0.2.0" && git push origin v0.2.0`

The workflow runs the CI checks, builds the release with the project key, verifies its certificate against
the fingerprint in the README, and publishes the APK (also as `Houselights.apk`), its SHA-256 and notes
drawn from the commits since the previous tag.

Repository secrets: `HOUSELIGHTS_KEYSTORE` (`base64 -w0 houselights-release.jks`),
`HOUSELIGHTS_KEYSTORE_PASSWORD` and, optionally, `TMDB_API_KEY` (a project key), `TRAKT_CLIENT_ID` (the
project's Trakt app) and `TRAKT_CLIENT_SECRET` (only for a Trakt app that has one). CI reads the optional ones
too.

## Performance

Zidoo players are small machines. Measure there, never in the emulator, and with the app compiled as users
run it (see below): a debug or freshly installed build runs interpreted and shows jank users never see.

### Baseline profile

The baseline profile lists the code of the main journeys (start-up, home, a library wall, a detail page,
a series, settings) so Android compiles it ahead of time. It is checked in
(`app/src/main/generated/baselineProfiles/`).

- **Installed with `scripts/install.sh`** (APK and `.dm` in one session): compiled at install.
- **APK alone**: `profileinstaller` hands the profile to Android, which compiles it only while the device is
  plugged in and idle (standby). A player never left in standby keeps running interpreted.

Zidoo Z1000 Pro, bench build, share of frames over 16.7 ms:

| State | Home | Detail page |
|---|---|---|
| Fresh install, nothing compiled | 7–12 % | 3–5 % |
| Installed with `scripts/install.sh` | 0.6–0.8 % | 0.7–1.6 % |
| APK alone, after background compilation | 0.5–0.7 % | 0.4–0.9 % |

Regenerate it after changing screens, before a release. The journeys (`baselineprofile/…/Journeys.kt`)
browse a NAS library the emulator reads over NFS, configured in `local.properties`:

```sh
cat >> local.properties <<'PROPS'
baselineprofile.host=192.168.1.10
baselineprofile.export=/volume1/media
baselineprofile.movies=films
baselineprofile.shows=series
PROPS
scripts/preview.sh
adb root                                    # Android 9 needs root to collect a profile
ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
```

### Benchmarks

```sh
ANDROID_SERIAL=<player> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

> **Warning.** Never run this, or any `connected…ReleaseAndroidTest`, on a player holding the release you
> use. The benchmarked build uses the release package name (`app.houselights`) and is uninstalled at the
> end; if your release is signed with this machine's debug key, it is replaced and then removed with its
> library. Use the `bench` build on that player instead.

Results are in `baselineprofile/build/outputs/connected_android_test_additional_output/`, with a Perfetto
trace per run. Cold start-up needs root and is skipped on user builds.

For manual measurements, install the `bench` build (`scripts/install.sh bench`, optimised like a release,
installed beside it) and use:

- `adb shell dumpsys gfxinfo app.houselights.bench framestats` (main thread vs. GPU wait);
- `adb shell setprop debug.hwui.overdraw show` (overdraw);
- `adb shell cmd package compile -m speed-profile app.houselights.bench` to compile as Android would.
