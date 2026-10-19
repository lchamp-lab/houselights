# Houselights

A cinema-style media browser for **Zidoo** players (Z1000 Pro, Z9X, UHD series and other Android TV boxes)
that hands playback to the **native Zidoo player**, so HDR10, HDR10+, Dolby Vision, audio passthrough and the
player's own menus keep working.

> **Status:** early development. Not affiliated with Zidoo, Jellyfin, Emby or Plex.

## Features

- **A 10-foot interface built for the living room**: full-screen backdrops, clear logos, poster rows and
  walls, detail pages with cast, seasons and episodes. Built with Jetpack Compose for TV on its own design
  system ([DESIGN.md](DESIGN.md)).
- **My files**: scans NFS or SMB shares, identifies films and shows from their names, and fetches artwork and
  details from TMDB.
  - Kodi-style NFO files and local artwork (`poster.jpg`, `fanart.jpg`, `clearlogo.png`…) take precedence;
    NFO ids identify titles exactly.
  - Details come in the device's language, or another chosen in Settings > Library > Details language;
    changing it updates every title.
  - Technical details (resolution, HDR, Dolby Vision, audio formats, subtitles) are read from the files.
  - Each title can be corrected from its page: poster, backdrop and logo chosen among TMDB's images, or a
    manual match by title, year or IMDb id. Corrections survive later scans.
  - Films of a saga are gathered, with a page per saga showing the films you own and those you don't.
- **Jellyfin**: browses your server and reports playback progress back to it. Emby and Plex are planned.
- **Native playback**: files open directly in the Zidoo player (`smb://`, `nfs://` or HTTP). Progress is
  read from the player while it plays, so resume points stay in sync.
- 4K60 interface output where the device allows it. English and French.

## Install

Each [release](https://github.com/lchamp-lab/houselights/releases) provides one APK, its SHA-256 and its
changes. A new release installs over the previous one and keeps your library, history and settings.

| Method | How |
|---|---|
| Zidoo | Copy `Houselights-x.y.z.apk` to a USB stick and open it from the player's file manager. |
| Any Android TV | In [Downloader](https://www.aftvnews.com/downloader/), enter `https://github.com/lchamp-lab/houselights/releases/latest/download/Houselights.apk`. |
| Automatic updates | Add `https://github.com/lchamp-lab/houselights` to [Obtainium](https://github.com/ImranR98/Obtainium). |
| Computer | `adb install -r Houselights-x.y.z.apk` |

Android may first ask you to allow installs from the app you use.

**Verify the signature.** Every release is signed with the project key; Android refuses an update signed
with another one. Check an APK with `apksigner verify --print-certs` or the AppVerifier app. The
certificate's SHA-256 is:

```
C4:2C:70:C6:C8:AE:CA:07:F8:38:E3:90:6E:8F:D1:0E:17:86:33:9A:30:D4:63:30:0E:51:C2:7E:6A:55:70:73
```

**TMDB key.** A release may ship without a TMDB key. The app then asks for yours (free, from
[themoviedb.org](https://www.themoviedb.org/settings/api)) in Settings > Library > TMDB API key.

## Security

- **Prefer NFS**, which needs no credentials. With SMB, the Zidoo player receives the credentials in the file
  URI and Android logs started intents (readable over adb): use a **dedicated read-only NAS account**.
  Credentials are only passed to the Zidoo player installed with the system.
- Passwords, the Jellyfin token, a personal TMDB key and path mappings are encrypted with a key held in the
  Android Keystore. Android backup is disabled.
- Jellyfin is reached over HTTPS when the server offers it; the settings show when a connection is not
  encrypted. Signing out revokes the app's token on the server. A stream played without path mappings
  carries the token in its URL and is only handed to another app through Android's app chooser.
- TMDB and artwork named by NFO files are fetched over HTTPS only. Plain HTTP is allowed for local servers.
- `nfs-client` depends on Netty 3.10 (end of life). Its known vulnerabilities affect HTTP and compression
  codecs that NFS does not use; it will be replaced when a maintained NFS client is available.

## Support

If Houselights earns its place under your TV, you can support the project on
[Ko-fi](https://ko-fi.com/lchamp_lab). The same page is available as a QR code in Settings > About.

## Development

Requires JDK 17 and the Android SDK (platform 35).

```sh
echo "tmdb.apiKey=YOUR_KEY" >> local.properties   # optional; never commit it
./gradlew assembleDebug
scripts/preview.sh                                # Android TV emulator
scripts/check.sh                                  # the checks CI runs
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for checks, signing, releases and performance tooling, and
[DESIGN.md](DESIGN.md) for the design system.

## License

Houselights is free software, released under the GNU General Public License, version 3 or (at your option)
any later version ([LICENSE](LICENSE), `GPL-3.0-or-later`).

- The Archivo typeface is licensed under the SIL Open Font License 1.1
  ([third_party/archivo/OFL.txt](third_party/archivo/OFL.txt)).
- The Play glyph comes from Material Icons (Apache License 2.0).
- This product uses the TMDB API but is not endorsed or certified by TMDB. The TMDB logo is a trademark of
  TMDB, shown under its attribution guidelines, and is not covered by the GPL.
