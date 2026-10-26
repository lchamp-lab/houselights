# Contributing to Houselights

Everyone taking part follows the [Code of conduct](CODE_OF_CONDUCT.md). Security flaws are reported privately
([SECURITY.md](SECURITY.md)).

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
  providers/     local (SMB, NFS, the player's own disks, TMDB, Room), jellyfin and trakt (the folders' Trakt account)
  player/        Zidoo player launch, progress tracking, audio and subtitle tracks, path mapping
  settings/      the app's settings (DataStore), display modes, text size, interface language, source switching
  journal/       the app's own log, masked, served to a phone on the home network (Settings > About)
  update/        the in-app updater: GitHub's releases, the download and its checks, Android's installer
  ui/design/     design system: tokens and components (DESIGN.md)
  ui/            Compose for TV screens
baselineprofile/ baseline profile generator and benchmarks, run on a device
tools/test-servers/ a Samba and an NFS NAS and a Jellyfin server in Docker, on sample files (its README)
tools/readme-images/ frames the README's images, which a test shoots (Releases, README's images)
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
- **Against real servers.** `tools/test-servers` starts a Samba and an NFS NAS and a Jellyfin server in Docker, on
  generated samples and on 127.0.0.1 alone, which the emulator reaches at 10.0.2.2 (and, for the tests, the same NFS
  NAS sending its replies in fragments, on 127.0.0.2): try the app there without a NAS at hand. Their tests
  (`app.houselights.servers`) run only with `HOUSELIGHTS_TEST_SERVERS=1`, in CI's `servers` job; `scripts/check.sh`
  skips them. See [its README](tools/test-servers/README.md).

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

A tag `vX.Y.Z` publishes a release; `vX.Y.Z-beta.N` a pre-release (`.github/workflows/release.yml`). The app's
updater offers the newest release above the installed one, and betas to those who chose them (README, Install).

1. Set `versionName` in `app/build.gradle.kts` to the tag's version, without its `-beta.N` (the workflow checks it).
2. Write what is new in `docs/releases/X.Y.Z/en.md`, and `fr.md` if you can: a line a change, 4000 characters at
   most each (the workflow refuses more). The release's notes and the app's update sheet read them, in the app's
   language when there, else in English; without the folder, both fall back to the subjects of the commits since the
   previous tag.
3. Commit, then `git tag -a v0.2.0 -m "Houselights 0.2.0" && git push origin v0.2.0`
4. Once the checks pass, approve the release job (Actions, the run, Review deployments).

**A final needs at least one commit after its last beta**, its notes giving one: the versionCode is the commit count,
and the app offers a release only above the installed versionCode. Before any build, a step of the check job, the
only one with its read-only token, reads the versionCode of every published release from its `update.json` (946,
0.3.0's, before them) and stops a tag that is not above them all: a final tagged on its beta's commit, a hotfix
branched from an old tag, a rewritten history.

The workflow runs the CI checks in a job with no secret. Once they pass, a second job reads the project's certificate
from `gradle.properties` (its only copy, which the app pins too; `UpdaterGuardTest` keeps the README's equal to it),
writes the project key, builds the release alone under its tag's full name (`-PversionName`, 0.4.0-beta.1 for a
beta), erases the key and verifies the APK's signer against that certificate. It then writes `update.json` from the
APK itself (`aapt2 dump badging`: package, versionCode, versionName, minSdk) with its size, SHA-256 and notes, checks
it with `jq`, and creates the release as a draft holding the APK (also as `Houselights.apk`), its SHA-256, R8's
mapping to read its logs and `update.json`, published only once every file is there.

**A dry run before a first beta.** The updater of a version that never ran it installs the next one: try it before
tagging. With `versionName` at 0.4.0, build two releases signed with the project's key (`keystore.properties`), the
second newer, both reading a test repository of yours:

```sh
./gradlew assembleRelease -Phouselights.updateRepo=you/houselights-test -PversionCode=2000 -PversionName=0.4.0-beta.1
./gradlew assembleRelease -Phouselights.updateRepo=you/houselights-test -PversionCode=2001 -PversionName=0.4.0-beta.2
```

Publish the second as a pre-release `v0.4.0-beta.2` of that repository, with `Houselights-0.4.0-beta.2.apk` and the
`update.json` the workflow would write for it (its notes in `en`); install the first on a player that holds no
release, then check it finds the second, downloads, verifies and installs it, and says so on home. Uninstall it
afterwards: its versionCode, above the releases', would refuse their updates. The property names where the release
reads, nothing else; without it, the project's repository.

The key lives in the `release` environment, which only `v*` tags reach and whose jobs wait for the maintainer's
approval: `HOUSELIGHTS_KEYSTORE` (`base64 -w0 houselights-release.jks`) and `HOUSELIGHTS_KEYSTORE_PASSWORD`.
Repository secrets, optional: `TMDB_API_KEY` (a project key), `TRAKT_CLIENT_ID` (the project's Trakt app),
`TRAKT_CLIENT_SECRET` (only for a Trakt app that has one) and `TRAKT_REDIRECT_URI` (only for one declared with
another URI); CI reads the key and the client ID and secret too. `v*` tags can be neither moved nor deleted.

### README's images

The README's images are the app's own screens: a test shoots them (`app/src/test/java/app/houselights/readme`), in
English at 1920 × 1080, over a library of recent films and shows that the app's scanner names from TMDB, then
`tools/readme-images/compose.py` frames them: home on a TV lit by its own picture (`hero.webp`, `hero-dark.webp`
for GitHub's dark theme, and `social-preview.jpg`, GitHub's link preview), each screen on a card, cut where a row
would show as a sliver (`CARDS`), and the animation from home to Play (`tour.webp`), cut on the cues the test films.
Make them again before a release, once its screens are final:

1. Push the branch whose screens they show as `readme-images/<anything>` (`git push origin HEAD:readme-images/0.4.0`),
   or, once the workflow is on `main`, Actions, README images, Run workflow on that branch.
2. Download the run's artifact « readme-images » and put its files in `docs/images`, over the old ones.
3. Look at each and at the README's alt texts, which say what each shows, then commit them; upload
   `social-preview.jpg` in the repository's Settings, General, Social preview.

The films and shows are listed by TMDB id in `ReadmeTitles`, with their files' picture and sound, what was watched,
and, where TMDB's own poster reads badly (all but black, lettering where home's doors cut their figures), another of
TMDB's by its file path; their texts and pictures are TMDB's, read at each run (the `TMDB_API_KEY` secret) and never
committed but in the images. A title TMDB does not name by its id, or a chosen poster it no longer lists, fails the
run, which says what it holds. The artifact « readme-captures » keeps the unframed shots (« To identify »'s,
Identify's and the update sheet's too, which the README does not show), the frames and their cues, and what TMDB
named and rated (`library.txt`), to frame them again with `--only` (hero, social, cards, tour). On a machine with a
TMDB key in `local.properties` and Pillow 11 (`tools/readme-images/requirements.txt`):

```sh
HOUSELIGHTS_README=/tmp/readme ./gradlew :app:testReleaseUnitTest --tests 'app.houselights.readme.*'
python3 -I tools/readme-images/compose.py /tmp/readme docs/images
```

## Performance

Zidoo players are small machines. Measure there, never in the emulator, and with the app compiled as users
run it (see below): a debug or freshly installed build runs interpreted and shows jank users never see.

### Baseline profile

The baseline profile lists the code of the main journeys (start-up, home's doors, a library wall, the
sagas, a detail page from To watch, a series, the search, settings) so Android compiles it ahead of
time. It is checked in (`app/src/main/generated/baselineProfiles/`); `BaselineProfileGuardTest` fails once it
names files since removed or lacks home's grid.

- **Installed with `scripts/install.sh`** (APK and `.dm` in one session): compiled at install.
- **APK alone**: `profileinstaller` hands the profile to Android, which compiles it only while the device is
  plugged in and idle (standby). A player never left in standby keeps running interpreted.

Zidoo Z1000 Pro, bench build, on 2026-10-18 (home then in rows, its grid not measured yet), share of frames over
16.7 ms:

| State | Home | Detail page |
|---|---|---|
| Fresh install, nothing compiled | 7–12 % | 3–5 % |
| Installed with `scripts/install.sh` | 0.6–0.8 % | 0.7–1.6 % |
| APK alone, after background compilation | 0.5–0.7 % | 0.4–0.9 % |

Regenerate it after changing screens, before a release. The journeys (`baselineprofile/…/Journeys.kt`)
reach each door by where it lies on screen and browse a NAS library the emulator reads over NFS, films
alone or films and shows, configured in `local.properties`:

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
