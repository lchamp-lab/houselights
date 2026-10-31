Updates in the app, folders picked rather than typed, titles that ask before they go, and a finer design.

- **Updates in the app.** Once a day Houselights looks for a new version, offers it once on home, downloads and checks it, then Android asks you to confirm (Settings > About > Updates).
- **Add a folder by picking it.** The NAS, its shared folder and the folder are chosen from lists, typing only as a last resort. A folder can also be on the player: Internal storage or a USB disk (#33). A second folder is no longer refused for a full path (#79).
- **Text fields.** Left and Right move the cursor while typing, a ✕ clears the field, and Identify's year can stay empty (#78).
- **The first scan** shows your library building itself, posters filling a wall; Go to Home lets it go on behind.
- **Titles identified more surely.** Ids in file names ({tmdb-…}, [tvdbid-…]) are read, and TMDB's results are weighed rather than the first taken. Those in doubt wait in Settings > Library > To identify, with suggestions.
- **Deleted files** leave the library at the next scan (#62); what you did with them comes back if they return, even renamed by Radarr or Sonarr.
- **Titles that ask before they go.** When many titles of a folder vanish at once, or the folder is deleted or moved on a NAS that answers, nothing leaves: home asks, Keep or Remove.
- **Menu on a poster** marks it watched, adds it to favorites or opens Edit.
- **Pictures on a contact sheet**, by language. A saga's poster and backdrop can be chosen too.
- **A saga's page is a polyptych:** a tall panel per film, the focused one opening onto it.
- **Episodes.** Anime numbered from the start (One Piece - 1071) land in TMDB's seasons, and Edit > Episode order offers DVD or absolute order. A one-season show says its watched tile marks the season (#31).
- **Design.** Finer type in three weights, squarer corners, denser rows; on a title page the tagline leads the overview and the credits are billed as on a poster; Welcome's two choices are doors, as on home.
- **A film in several copies.** Resume plays the copy you played last; the page shows the formats of the copy Play plays; copies keep their numbers in Versions; removing the folder of its first copy no longer loses its chosen poster or copy. Jellyfin plays the copy chosen in Versions.
- **Scans.** A scan no longer makes what you do wait for TMDB or the NAS, and pauses while a film plays. NFS servers in user space (unfs3) no longer look unreachable, and a link leading nowhere counts as a deleted file. Names copied from a Mac no longer double a show. NAS unreachable is said wherever a folder is out of reach.
- **Sturdier.** On a test-keys firmware (the Z1000 Pro's), an updated Zidoo player gets your passwords only once you trust it. A password the keystore cannot open for now is said kept, never refused. A default track language saved with a region (ar-EG) shows chosen. Launch, Settings and Trakt's sync stay responsive.

## Beta notes

- 0.3.0 has no updater: install this version by hand (README, Install); the next ones come through the app.
- Tested on a Zidoo Z1000 Pro (Android 9) with a Synology NAS over NFS; SMB, USB disks, Jellyfin and Trakt less so: reports are welcome in the issues.
- Translations other than English and French are not reviewed by native speakers yet.
- Prefer NFS. With SMB, use a dedicated read-only NAS account (README, Security).
