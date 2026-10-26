# Test servers

A NAS and a Jellyfin server on this machine, in Docker, to try Houselights against real servers from the Android TV
emulator without a NAS or a player at hand, and to run the app's integration tests (CI's `servers` job). They serve
small sample files `make-media.sh` writes, laid out as a Synology lays out its shared folders under `/volume1`.

| Server | What it is | Answers on | Account (test-only) |
|---|---|---|---|
| `samba` | Samba 4.23 on Alpine 3.24, set up as a Synology or QNAP: SMB2 to SMB3 (3.1.1 negotiated), signing and encryption offered, never required, no NetBIOS | `127.0.0.1:445` | `houselights` / `houselights-test`, read-only |
| `nfs` | unfs3 0.10 (NFSv3 in user space) and rpcbind 1.2 on Alpine 3.24; MOUNT answers EXPORT (`showmount -e`); each reply in one fragment, as a NAS sends it (below) | `127.0.0.1`: 111 (portmapper) and 892 (mountd), TCP and UDP; 2049 (NFS), TCP | none: NFS trusts the address |
| `nfs-fragments` | the same NAS as unfsd answers by itself, each reply above 64 KB in fragments of 64 KB (below), for the tests | `127.0.0.2`: 111, 892 and 2049, TCP | none |
| `jellyfin` | Jellyfin 12.3, the official image, with its profile only | `127.0.0.1:8096` (HTTP) | `houselights` / `houselights-test`, the server's administrator |

Every port is published on `127.0.0.1` alone, `nfs-fragments`' on `127.0.0.2`, this machine's own too: with Docker
Engine 28 or later, the network around (a hotel's, a train's) cannot reach them. The passwords open read-only access
to generated files and are written here on purpose; never reuse them.

**Shared folders.** Over SMB, the shares `video` (Films, Séries), `media` (a second shared folder: Films, Vidéos perso),
`photos`, `public` (open to guests) and `admin$` (listed, which the account may not open). Over NFS, the exports
`/volume1/video` and `/volume1/media` (every client, read-only, non-privileged ports allowed, root squashed),
`/volume1/private`, allowed to 192.0.2.10 alone, an address no device has, and `/volume1/public` with Synology's default
rule, non-privileged ports not allowed, which refuses the app.

**Samples.** In `video/Films`: `Dune (2021)/` with its NFO, poster and fanart, `Heat (1995)/` in two AVI parts (CD1,
CD2), `Blade Runner (1982) {tmdb-78}/`, `Wonder Woman 1984.mkv` (a year that is part of the title), `Le Grand Tournoi
des escargots (2019)/` (unknown to TMDB), `Alien (1979)/` in MP4. In `video/Séries`: `Severance (2022)/Season 01/`,
episodes S01E01 to S01E03, and `One Piece (1999)/` numbered from its first episode (1071 to 1073). Personal videos
(`VID_20190714_183522.mp4`…) in `media/Vidéos perso`, photos in `photos`, and what a NAS hides: `@eaDir`, `#recycle`,
`.DS_Store`. Each video lasts 4 s at 1920×1080: HEVC in MKV, H.264 in MP4, English then French audio, French
subtitles; 30 MB in all. In `media/Films`, `Interstellar (2014)/` has an NFO over 64 KB, its cast of 500 actors
numbered (`Acteur 001`…), that names no image.

## Start

Requires Docker Engine 28 or later, with Compose v2 (an older engine lets a device of the same network reach ports
published on 127.0.0.1), and curl and jq for Jellyfin. Ports 111, 445, 892, 2049 and 8096 must be free on 127.0.0.1,
and 111, 892 and 2049 on 127.0.0.2: a portmapper of this machine's own holds 111, as on Ubuntu with `nfs-common`, and
a Samba of its own 445.
`sudo systemctl stop rpcbind.socket rpcbind.service` (and `smbd`) frees them until the machine starts again; `start`
gives them back.

```sh
cd tools/test-servers
./make-media.sh                                     # the samples, in ./media (git-ignored); again: writes nothing
docker compose up -d --build --wait                 # the SMB server and both NFS servers
docker compose --profile jellyfin up -d --wait      # and Jellyfin (about 300 MB of memory, 1 GB at most)
./bootstrap-jellyfin.sh                             # its wizard, its libraries, their scan (half a minute)
```

`bootstrap-jellyfin.sh` completes Jellyfin's first-run wizard through its API (French, the account above), adds the
libraries Films (movies), Séries (tvshows) and Vidéos perso (homevideos), read from names and NFO alone (no metadata
from the internet, no images extracted), and waits for their scan. Run again, it keeps what is set up. Jellyfin's own
pages are at http://127.0.0.1:8096.

## In the app, on the emulator

The emulator (`scripts/preview.sh`) reaches this machine's 127.0.0.1 at **10.0.2.2**.

| | NFS | SMB | Jellyfin |
|---|---|---|---|
| Protocole | NFS | SMB | |
| Adresse du NAS / du serveur | `10.0.2.2` | `10.0.2.2` | `10.0.2.2:8096` |
| Export NFS / Partage | `/volume1/video` | `video` | |
| Dossier | `Films` (or `Séries`) | `Films` (or `Séries`) | |
| Utilisateur, Mot de passe | (Se présenter comme: empty) | `houselights`, `houselights-test` | `houselights`, `houselights-test` |

Jellyfin is not found by the search for servers (below): type its address. HTTPS is tried first and does not answer,
so it is reached over HTTP, « non chiffré ». What else to try:

- a refusal: the password `wrong`, the share `videos`, the folder `Films2`, the export `/volume1/private` (this device
  not allowed), the export `/volume1/photos` (a shared folder without an NFS rule), the export `/volume1/public`
  (Synology's default rule, non-privileged ports not allowed), the share `admin$`;
- the export typed `media`, as a Synology answers it (#59): refused, not « not found »;
- a second folder in another shared folder (#79): `/volume1/media` or `media`, folder `Films`;
- a guest: share `public`, user and password empty;
- a NAS gone silent: `docker compose pause samba` (or `nfs`), `docker compose unpause samba` after;
- replies in fragments, as unfsd sends them: `UNFS3_FRAGMENTS=1 docker compose up -d --wait nfs` (below); a scan
  reads films, NFOs and artwork as on a NAS sending whole replies.

Playing needs the Zidoo player, which the emulator has not, and a real player cannot reach servers bound to 127.0.0.1.

## Tests

The tests in `app/src/test/java/app/houselights/servers/` use the app's own classes against these servers: the NFS and
SMB browsers (a folder's test, its listing, what the NAS hides, reads, a film's tracks probed, each refusal as the
folder settings word it) and Jellyfin's client and provider (sign-in, its libraries and their kinds, a film's details,
a show's seasons, a token signed out). The NFS tests run on both NFS servers, `NfsFragmentsServerTest` adding on
`nfs-fragments` the reads whose replies come in fragments: 256 KB at once, an NFO over 64 KB. They are skipped unless
`HOUSELIGHTS_TEST_SERVERS=1`, so `scripts/check.sh` and CI's `check` job skip them; CI's `servers` job starts the
servers and runs them alone. With the variable set, Gradle runs them each time, never taking an earlier run, skipped
or against other servers, as up to date:

```sh
HOUSELIGHTS_TEST_SERVERS=1 ./gradlew :app:testDebugUnitTest --tests 'app.houselights.servers.*'
```

`HOUSELIGHTS_TEST_SERVERS_HOST` points them at another machine than 127.0.0.1, and
`HOUSELIGHTS_TEST_SERVERS_FRAGMENTS_HOST` the NFS tests in fragments at another address than 127.0.0.2. With the
first alone, `NfsFragmentsServerTest` is skipped: this machine's 127.0.0.2 is not the other's.

## Looking inside

The `client` service has smbclient, showmount, rpcinfo, and libnfs's `nfs-ls` and `nfs-cat`, which read an export
without mounting it; it reaches the servers by their names on the compose network. As root there, libnfs calls from a
privileged port: `/volume1/public`, which refuses the app, opens to it.

```sh
docker compose run --rm client smbclient -L //samba -U houselights%houselights-test
docker compose run --rm client showmount -e nfs
docker compose run --rm client rpcinfo -p nfs
docker compose run --rm client nfs-ls "nfs://nfs/volume1/video/Films"
docker compose logs samba nfs jellyfin
```

## Stop and wipe

```sh
docker compose --profile jellyfin down              # stops them; Jellyfin's setup is kept
docker compose --profile jellyfin down --volumes    # and forgets Jellyfin's setup
rm -rf media                                        # the samples
```

The images stay (`houselights-test-samba:1`, `houselights-test-nfs:1`, `houselights-test-client:1`, Jellyfin's);
`docker image rm` removes them.

## NFS replies in fragments

Over TCP, an RPC reply may be sent in several record fragments (RFC 5531, section 11). Linux's NFS server, the one
of a Synology, a QNAP, TrueNAS SCALE or Unraid, sends each reply in one; unfsd, on libtirpc, splits every reply above
64 KB into fragments of 64 KB. Houselights' NFS client, nfs-client 1.1.0, could not join them: its decoder lost a
reply's first fragments once the next came in another read, and `RecordMarkingUtil.removeRecordMarking` reads the
second fragment's mark 4 bytes too early. A read above 64 KB failed with « tcp IO error on the connection », which the
app takes for a NAS out of reach, and a scan stopped at its first MKV, whose probe reads 256 KB at once (#88). The app
now reads them: its own `RPCRecordDecoder` (`app/src/main/java/com/emc/ecs/nfsclient/network`) stands in for
nfs-client's and joins a reply's fragments before nfs-client reads it.

Both are tested. On `nfs`, `nfs/whole-replies.py` stands before unfsd on port 2049 and sends each reply on in one
fragment, as a NAS does; `nfs-fragments`, on 127.0.0.2, is unfsd answering by itself (`UNFS3_FRAGMENTS=1`). The
emulator reaches only 127.0.0.1: to try the app there against unfsd as it is, run `nfs` so too:

```sh
UNFS3_FRAGMENTS=1 docker compose up -d --wait nfs      # docker compose up -d --wait nfs to come back
```

## What they do not simulate

- **Discovery.** Nothing announces itself: no mDNS (`_smb._tcp`), WS-Discovery or NetBIOS, and Jellyfin's UDP
  discovery (port 7359) is not published; Docker's bridge carries no multicast or broadcast to the emulator anyway.
- **A NAS asleep**: disks spinning up (NFS3ERR_JUKEBOX), Wake-on-LAN, a network that drops or slows down. `docker
  compose pause` gives a NAS that stops answering, nothing finer.
- **Real NAS quirks.** A Synology answers MNT3ERR_ACCES to any export it does not have (#59); unfs3 answers so only for
  a path that exists without being exported (the shared folders' names alone exist at its root for that), and
  MNT3ERR_NOENT for one that does not exist (`/volume1/videos`). unfs3 answers READDIRPLUS with NFS3ERR_NOTSUPP, so the
  app lists with READDIR, where a Synology or QNAP answers READDIRPLUS: that path is held by `NfsListingTest` alone,
  over a fake export, and nfs-client's own READDIRPLUS by no test; NFS goes over TCP alone, and on `nfs` unfsd sees
  every NFS call (not the mounts) come from 127.0.0.1, through `whole-replies.py`. Samba here never requires signing,
  which a recent Synology or Windows 11 24H2 does for guests; nor SMB1 alone, NFSv4 alone, a NAS's own user mapping
  (the samples are readable by everyone, whatever user a folder presents), names in macOS's decomposed form, symbolic
  links, ACLs, or a large library.
- **Jellyfin**: HTTPS (8920), transcoding, plugins, accounts other than its administrator, metadata from the internet.
- **Playback**, the Zidoo player and its own NFS or SMB access.

## Versions

Alpine 3.24.3 and Jellyfin 12.3 are pinned by tag and digest (`*/Dockerfile`, `compose.yaml`); Samba, unfs3, rpcbind
and the client tools by their series in Alpine's branch, whose fixes they follow. The samples are encoded by
Jellyfin's ffmpeg (8.1.3-Jellyfin), from that same image. Updating one is changing its tag and digest.
