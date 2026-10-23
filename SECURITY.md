# Security policy

## Supported versions

Only the latest release receives fixes: update to it before reporting.

## Reporting a vulnerability

Please do not open a public issue. Report it privately through GitHub:
[Report a vulnerability](https://github.com/lchamp-lab/houselights/security/advisories/new), in the repository's
Security tab. Only the maintainers can read it.

Include the version (Settings > About), the player and its firmware, the source (folders over NFS or SMB, or
Jellyfin), what an attacker gains and from where (the same network, another app on the player, a crafted file or
server), and the steps to reproduce. Never include your own passwords or tokens.

## What to expect

- An answer within a week.
- A fix in the next release, sooner for a serious flaw; the advisory is published once that release is out.
- Credit in the advisory and the release notes, if you wish.

## Scope

In scope: the app, how it stores and sends credentials and tokens, the files it reads (NFO, Matroska), what it
serves on the local network, and the release workflow and signing.

Out of scope: flaws in Jellyfin, Trakt, TMDB, the Zidoo firmware or its player (report them to their authors), and
the limits README > Security already describes.

How the app protects credentials: [README, Security](README.md#security).
