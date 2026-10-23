<p align="center">
  <img src="docs/images/houselights.svg" alt="Houselights: a cinema-style media browser for Zidoo players" width="100%">
</p>

<h3 align="center">Your films and shows, on the big screen.</h3>

<p align="center">
  A cinema-style media browser for <b>Zidoo</b> players that hands playback to the <b>native Zidoo player</b>,<br>
  so HDR10, HDR10+, Dolby Vision, audio passthrough and the player's own menus keep working.
</p>

<p align="center">
  <a href="https://github.com/lchamp-lab/houselights/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/lchamp-lab/houselights?style=flat-square&label=release&color=FF7A4D"></a>
  <img alt="Android 9 or later" src="https://img.shields.io/badge/Android-9%2B-555?style=flat-square">
  <a href="LICENSE"><img alt="GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-555?style=flat-square"></a>
</p>

<p align="center">
  <a href="#install"><b>Install</b></a> ·
  <a href="#features">Features</a> ·
  <a href="#screenshots">Screenshots</a> ·
  <a href="https://ko-fi.com/lchamp_lab">Support</a>
</p>

![Home: the focused film's backdrop, logo and summary above a row of posters](docs/images/home.jpg)

> **Beta.** Made for Zidoo players (Android 9 with Zidoo's launcher), tested on a Z1000 Pro: reports from other
> models are welcome in the [issues](https://github.com/lchamp-lab/houselights/issues). Not affiliated with Zidoo,
> Jellyfin, Emby or Plex.

**En français.** Houselights est une médiathèque façon salle de cinéma pour les lecteurs **Zidoo** (testée sur le
Z1000 Pro) : vos films et séries d’un **NAS** (NFS ou SMB, avec les NFO Kodi et TMDB) ou d’un serveur **Jellyfin**,
lus par le **lecteur natif Zidoo** (HDR10, HDR10+, Dolby Vision, passthrough audio), avec le suivi **Trakt**. Libre et
gratuite, l’application est entièrement en français. [Installer](#install)

## Features

- **The Zidoo player plays.** Files open directly in it (`smb://`, `nfs://` or HTTP), with everything it
  supports. Progress is read from the player while it plays, so resume points stay in sync.
- **A 10-foot interface built for the living room**: full-screen backdrops, clear logos, poster rows and
  walls, detail pages with cast, seasons and episodes, in 4K60 where the device allows it. Built with Jetpack
  Compose for TV on its own design system ([DESIGN.md](DESIGN.md)).
  - Home is a grid of doors: the title you were watching to resume in one OK (or a film not seen yet), the next
    episode and what is new, a door per library with its number of titles cut in its posters, the sagas, search
    and the app's state. The rows of what to watch are one door away. Back on home asks once more before leaving
    the app.
  - Each wall keeps a bar of places, Movies · TV shows (or a Jellyfin server's libraries); it slides away once you
    browse the posters. A place opens on OK, Back climbs to the bar, then home.
- **My files**: scans NFS or SMB shares, identifies films and shows from their names, and fetches artwork and
  details from TMDB.
  - Kodi-style NFO files and local artwork (`poster.jpg`, `fanart.jpg`, `clearlogo.png`…) take precedence;
    NFO ids identify titles exactly.
  - Technical details (resolution, HDR, Dolby Vision, audio formats, subtitles) are read from the files.
  - Each title can be corrected from its page: poster, backdrop and logo chosen among TMDB's images, or a
    manual match by title, year or IMDb id. Corrections survive later scans.
  - Films of a saga are gathered, with a page per saga showing the films you own and those you don't.
  - A film, a season or an episode is marked watched or unwatched from its page; Menu or Pop Menu (or a long
    press) opens the options of an episode (mark it, play it from the start, its audio and subtitles) and of a
    show's mark (the season or the whole show).
  - Your [Trakt](https://trakt.tv) account connects in Settings > Trakt, with a code entered on your phone
    or computer, and keeps what you watch in your folders (Jellyfin has its own Trakt plugin):
    - a title watched on either side is watched on both, at the date it was seen; in every folder, a title
      is watched from 80 % of its length, as on Trakt;
    - where you stopped goes by the latest of Trakt's pause, the last play here (of the copy that plays)
      and the last play Trakt knows, so a pause older than a play never comes back;
    - marking a title unwatched removes every play of it from Trakt's history, those of other devices
      too; a title only in progress loses its pause alone;
    - marks and stops wait while Trakt cannot be reached, or until the account is connected again once
      Trakt ended its connection; disconnecting forgets them. Under a server as the source, Trakt pauses,
      still connected, and sends what waited once your folders are the source again; Settings > Trakt says
      so meanwhile, and disconnects it.
- **Jellyfin**: browses your server and reports playback progress back to it. Switching to your folders keeps
  the server signed in: it comes back from Settings in one OK, without signing in again. Emby and Plex are
  planned.
- **In your language**: the interface in English, French, German, Spanish, Italian, Portuguese, Dutch,
  Polish, Chinese (simplified and traditional), Japanese and Korean, and details in the language of your
  choice. Both follow the device unless chosen in the settings.

## Screenshots

<table>
  <tr>
    <td width="50%"><img src="docs/images/film.jpg" alt="A film's page"><br><sub>A film's page: what its file holds, read from the file itself.</sub></td>
    <td width="50%"><img src="docs/images/show.jpg" alt="A show's episodes and cast"><br><sub>A show's episodes and cast.</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/images/saga.jpg" alt="A saga's page"><br><sub>A saga: the films you own among its films, resumed where you left off.</sub></td>
    <td width="50%"><img src="docs/images/library.jpg" alt="The library's poster wall"><br><sub>The library as a poster wall, lit by the focused poster.</sub></td>
  </tr>
</table>

## Install

Each [release](https://github.com/lchamp-lab/houselights/releases) provides one APK, its SHA-256 and its
changes. A new release installs over the previous one and keeps your library, history and settings.

| Method | How |
|---|---|
| Zidoo | Copy `Houselights-x.y.z.apk` to a USB stick and open it from the player's file manager. |
| Automatic updates | Add `https://github.com/lchamp-lab/houselights` to [Obtainium](https://github.com/ImranR98/Obtainium). |
| Computer | `adb install -r Houselights-x.y.z.apk` |

Android may first ask you to allow installs from the app you use.

**Verify the signature.** Every release is signed with the project key; Android refuses an update signed
with another one. Check an APK with `apksigner verify --print-certs` or the AppVerifier app. The
certificate's SHA-256 is:

```
CA:0A:DF:07:52:64:58:F6:CB:3E:57:E4:C5:84:66:C4:55:B6:86:F5:84:3A:E6:4D:45:A3:76:A2:93:EB:4D:A8
```

**TMDB key.** A release may ship without a TMDB key. The app then asks for yours (free, from
[themoviedb.org](https://www.themoviedb.org/settings/api)) in Settings > Library > TMDB API key.

## Security

- **Prefer NFS**, which needs no credentials. With SMB, the Zidoo player receives the credentials in the file
  URI and Android logs started intents (readable over adb): use a **dedicated read-only NAS account**.
  Credentials only go to the Zidoo's player: a system app or one signed like the firmware's apps; failing
  those, the app under a Zidoo name that first received them, remembered by its certificate. Any other app,
  or that one signed by someone else, receives none.
- Passwords, the Jellyfin token, a personal TMDB key, path mappings and Trakt's tokens are encrypted with a key
  held in the Android Keystore. Android backup is disabled. Disconnecting Trakt revokes the app's token there.
- Jellyfin is reached over HTTPS when the server offers it. A certificate the player refuses stops the sign-in
  rather than fall back to plain HTTP, which only typing `http://` chooses; the settings show when a connection
  is not encrypted. Requests carrying the token or the password follow no redirect to another host, or from
  HTTPS to HTTP. A stream played without path mappings carries the token in its URL and is only handed to
  another app through Android's app chooser.
- Changing source signs out of nothing. A Jellyfin server left for your folders keeps its token, encrypted
  like the source's under its own key, so that it comes back without signing in; one server at most, signed out
  of instead when the Keystore cannot encrypt it. Its token is revoked on the server when you forget it or sign
  out of it, or when a new sign-in replaces it, and is never used again after; a token the server refuses is
  forgotten.
- Send the log (Settings > About) serves the app's own log on your home network only while its panel is open and the
  app on screen, ten minutes at most: read-only, on the player's private IPv4 address on its home network, never a
  VPN's, at an address holding a random 100-bit code that only the panel shows, each line masked of passwords and
  tokens. The page loads nothing from elsewhere.
- TMDB and Trakt are reached over HTTPS only, trusting the system's certificate authorities alone. Artwork
  named by NFO files is fetched over HTTPS only, redirects included. Other servers may use plain HTTP: home
  servers are reached by their address, which no rule can name.
- `nfs-client` depends on Netty 3.10 (end of life). Its known vulnerabilities affect HTTP and compression
  codecs that NFS does not use; it will be replaced when a maintained NFS client is available.

To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Support

If Houselights earns its place under your TV, you can support the project on
[Ko-fi](https://ko-fi.com/lchamp_lab). The same page is available as a QR code in Settings > About.

**Reporting a problem.** Right after it happens, open Settings > About > Send the log and scan its QR code with your
phone: it opens Houselights' own log, a crash's lines included, with a line to paste at the top (version, player and
firmware, source, Trakt), to copy or download and attach to your
[issue](https://github.com/lchamp-lab/houselights/issues/new/choose). Passwords and tokens are masked; the names of
your files, shares and NAS stay, so read it before posting. With adb, the lines of the running app only:
`adb logcat -d --pid=$(adb shell pidof -s app.houselights)`.

## Development

Requires JDK 17 and the Android SDK (platform 35).

```sh
echo "tmdb.apiKey=YOUR_KEY" >> local.properties           # optional; never commit it
echo "trakt.clientId=YOUR_CLIENT_ID" >> local.properties  # optional: Trakt, below
./gradlew assembleDebug
scripts/preview.sh                                        # Android TV emulator
scripts/check.sh                                          # the checks CI runs
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for checks, signing, releases and performance tooling, and
[DESIGN.md](DESIGN.md) for the design system.

### Trakt

Settings > Trakt needs the client ID of a Trakt app, built into the APK. Without one, the build says it has
no Trakt key and everything else works.

1. Create the app on Trakt's developer portal ([new API app](https://app.trakt.tv/settings/apps/api/new));
   Trakt first asks you to link a GitHub account, to prove who you are.
2. Choose the Production type, and declare `https://github.com/lchamp-lab/houselights` as its redirect URI
   (`TraktClient.REDIRECT_URI`): connecting with a code never opens it, but renewing the tokens names it. An
   app declared with another URI gives it as `trakt.redirectUri` (`TRAKT_REDIRECT_URI`).
3. Put its client ID in `local.properties` as `trakt.clientId`, or in the `TRAKT_CLIENT_ID` environment
   variable. A client secret is needed only by an app that has one, as `trakt.clientSecret`
   (`TRAKT_CLIENT_SECRET`); built into the APK, it can be read there.

## License

Houselights is free software, released under the GNU General Public License, version 3 or (at your option)
any later version ([LICENSE](LICENSE), `GPL-3.0-or-later`).

- The Archivo typeface is licensed under the SIL Open Font License 1.1
  ([third_party/archivo/OFL.txt](third_party/archivo/OFL.txt)).
- Icons are Lucide's (https://lucide.dev), under the ISC license, those derived from Feather under the MIT
  license ([third_party/lucide/LICENSE](third_party/lucide/LICENSE)).
- This product uses the TMDB API but is not endorsed or certified by TMDB. The TMDB logo is a trademark of
  TMDB, shown under its attribution guidelines, and is not covered by the GPL.
