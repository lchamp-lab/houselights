#!/usr/bin/env bash
# Writes the test servers' sample files to tools/test-servers/media (git-ignored), laid out as a Synology's /volume1:
# small but real videos (4 s, 1920×1080, HEVC or H.264, two audio tracks with their languages and a subtitle track,
# in MKV and MP4; an AVI in two parts), named as people name them, an NFO and artwork for one film, personal videos
# and photos, and the folders a NAS hides. Encoded with the ffmpeg of Jellyfin's image (compose.yaml), in Docker,
# as the user running the script. Idempotent: a file already there is kept, so a second run writes nothing;
# rm -rf tools/test-servers/media starts again. The servers' tests (app/src/test/…/servers) expect this layout.
# Usage: tools/test-servers/make-media.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MEDIA="$HERE/media"
# Each sample is encoded once here, outside every share and export, then copied under each name.
BASE="$MEDIA/.base"
mkdir -p "$BASE"

# Jellyfin's ffmpeg, its paths under /media.
ffmpeg() {
    docker compose -f "$HERE/compose.yaml" run --rm -T --user "$(id -u):$(id -g)" ffmpeg "$@"
}

# encode NAME ARGUMENTS…: .base/NAME written by ffmpeg from ARGUMENTS (inputs and options), unless already there.
encode() {
    local name="$1"
    shift
    [[ -s "$BASE/$name" ]] && return
    echo "Encoding $name"
    ffmpeg -y "$@" "/media/.base/partial-$name"
    mv "$BASE/partial-$name" "$BASE/$name"
}

# put SAMPLE PATH [DATE]: the sample SAMPLE as media/PATH, dated DATE when given, unless already there.
put() {
    local target="$MEDIA/$2"
    [[ -e "$target" ]] && return
    mkdir -p "$(dirname "$target")"
    cp "$BASE/$1" "$target.partial"
    [[ -z "${3:-}" ]] || touch -d "$3" "$target.partial"
    mv "$target.partial" "$target"
}

# write PATH: standard input as media/PATH, unless already there.
write() {
    local target="$MEDIA/$1"
    if [[ -e "$target" ]]; then
        cat >/dev/null
        return
    fi
    mkdir -p "$(dirname "$target")"
    cat >"$target.partial"
    mv "$target.partial" "$target"
}

# The subtitles every film carries, in French.
[[ -s "$BASE/fr.srt" ]] || cat >"$BASE/fr.srt" <<'SRT'
1
00:00:00,500 --> 00:00:02,000
Fichier d'essai de Houselights.

2
00:00:02,200 --> 00:00:03,800
Sous-titres en français.
SRT

video_4s="testsrc2=size=1920x1080:rate=24:duration=4"
tone() { echo "sine=frequency=$1:sample_rate=48000:duration=4"; }

# A film as a remux is: HEVC, English AC-3 5.1 first, French AAC stereo, French subtitles.
encode film-hevc.mkv \
    -f lavfi -i "$video_4s" -f lavfi -i "$(tone 440)" -f lavfi -i "$(tone 660)" -i /media/.base/fr.srt \
    -map 0:v -map 1:a -map 2:a -map 3:s \
    -c:v libx265 -preset ultrafast -crf 34 -pix_fmt yuv420p -x265-params log-level=error:pools=none:frame-threads=1 \
    -c:a:0 ac3 -ac:a:0 6 -b:a:0 192k -c:a:1 aac -ac:a:1 2 -b:a:1 96k -c:s srt \
    -metadata:s:a:0 language=eng -metadata:s:a:0 "title=English 5.1" \
    -metadata:s:a:1 language=fre -metadata:s:a:1 title=Français \
    -metadata:s:s:0 language=fre -metadata:s:s:0 title=Français \
    -disposition:a:0 default -disposition:a:1 0 -disposition:s:0 0

# A film in MP4: H.264, English and French AAC, French subtitles (mov_text).
encode film-h264.mp4 \
    -f lavfi -i "$video_4s" -f lavfi -i "$(tone 440)" -f lavfi -i "$(tone 660)" -i /media/.base/fr.srt \
    -map 0:v -map 1:a -map 2:a -map 3:s \
    -c:v libx264 -preset ultrafast -crf 34 -pix_fmt yuv420p -threads 2 \
    -c:a aac -ac 2 -b:a 96k -c:s mov_text \
    -metadata:s:a:0 language=eng -metadata:s:a:1 language=fre -metadata:s:s:0 language=fre \
    -movflags +faststart

# A DVD-era rip in parts: MPEG-4 Part 2 (Xvid) and AC-3 in AVI.
encode part-xvid.avi \
    -f lavfi -i "testsrc2=size=720x304:rate=25:duration=4" -f lavfi -i "$(tone 440)" \
    -c:v mpeg4 -vtag XVID -q:v 8 -c:a ac3 -ac 2 -b:a 128k

# A phone's video: H.264 at 30 frames a second, one AAC track.
encode phone-h264.mp4 \
    -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=4" -f lavfi -i "$(tone 880)" \
    -c:v libx264 -preset ultrafast -crf 34 -pix_fmt yuv420p -threads 2 -c:a aac -ac 2 -b:a 96k -movflags +faststart

encode poster.jpg -f lavfi -i "testsrc2=size=1000x1500:rate=1:duration=1" -frames:v 1 -update 1 -q:v 5
encode fanart.jpg -f lavfi -i "testsrc2=size=1920x1080:rate=1:duration=1" -frames:v 1 -update 1 -q:v 5
encode photo.jpg -f lavfi -i "testsrc=size=2016x1512:rate=1:duration=1" -frames:v 1 -update 1 -q:v 5
encode thumb.jpg -f lavfi -i "testsrc=size=320x180:rate=1:duration=1" -frames:v 1 -update 1 -q:v 5

# Share « video », export /volume1/video: Films and Séries.
films="video/Films"
put film-hevc.mkv "$films/Dune (2021)/Dune (2021).mkv"
put poster.jpg "$films/Dune (2021)/poster.jpg"
put fanart.jpg "$films/Dune (2021)/fanart.jpg"
write "$films/Dune (2021)/Dune (2021).nfo" <<'NFO'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<movie>
    <title>Dune</title>
    <originaltitle>Dune</originaltitle>
    <year>2021</year>
    <plot>Fichier d'essai de Houselights : Paul Atreides arrive sur Arrakis.</plot>
    <runtime>155</runtime>
    <genre>Science-Fiction</genre>
    <genre>Aventure</genre>
    <director>Denis Villeneuve</director>
    <uniqueid type="tmdb" default="true">438631</uniqueid>
    <uniqueid type="imdb">tt1160419</uniqueid>
</movie>
NFO
# Synology's indexer leaves a folder of thumbnails beside each file.
put thumb.jpg "$films/Dune (2021)/@eaDir/Dune (2021).mkv/SYNOVIDEO_VIDEO_SCREENSHOT.jpg"
put part-xvid.avi "$films/Heat (1995)/Heat (1995) CD1.avi"
put part-xvid.avi "$films/Heat (1995)/Heat (1995) CD2.avi"
put film-hevc.mkv "$films/Blade Runner (1982) {tmdb-78}/Blade Runner (1982) {tmdb-78}.mkv"
# 1984 is part of the title, not its year (the film of 2020).
put film-hevc.mkv "$films/Wonder Woman 1984.mkv"
# A title TMDB does not know.
put film-hevc.mkv "$films/Le Grand Tournoi des escargots (2019)/Le Grand Tournoi des escargots (2019).mkv"
put film-h264.mp4 "$films/Alien (1979)/Alien (1979).mp4"
printf 'Bud1\0\0\0\0' | write "$films/.DS_Store"

shows="video/Séries"
for episode in 01 02 03; do
    put film-hevc.mkv "$shows/Severance (2022)/Season 01/Severance - S01E$episode.mkv"
done
# An anime numbered from its first episode, without seasons.
for episode in 1071 1072 1073; do
    put film-hevc.mkv "$shows/One Piece (1999)/One Piece - $episode.mkv"
done
put thumb.jpg "$shows/Severance (2022)/Season 01/@eaDir/Severance - S01E01.mkv/SYNOVIDEO_VIDEO_SCREENSHOT.jpg"

# What a NAS keeps at a share's root, never listed: its recycle bin and its indexer's folder.
put film-hevc.mkv "video/#recycle/Films/Ancien film (2001)/Ancien film (2001).mkv"
printf 'SYNOINDEX\n' | write "video/@eaDir/SYNOINDEX_MEDIA_INFO"

# Share « media », export /volume1/media: a second shared folder (issue #79), and personal videos.
put film-hevc.mkv "media/Films/Interstellar (2014)/Interstellar (2014).mkv"
put phone-h264.mp4 "media/Vidéos perso/2019-07 Vacances/VID_20190714_183522.mp4" "2019-07-14 18:35:22"
put phone-h264.mp4 "media/Vidéos perso/2019-07 Vacances/VID_20190714_190104.mp4" "2019-07-14 19:01:04"
put phone-h264.mp4 "media/Vidéos perso/2021-12 Noël/VID_20211224_201530.mp4" "2021-12-24 20:15:30"

# Share « photos ».
put photo.jpg "photos/2019/IMG_20190714_183000.jpg" "2019-07-14 18:30:00"
put photo.jpg "photos/2019/IMG_20190714_183105.jpg" "2019-07-14 18:31:05"
put thumb.jpg "photos/2019/@eaDir/IMG_20190714_183000.jpg/SYNOPHOTO_THUMB_M.jpg"

# Share « public », open to guests; export /volume1/public, which keeps Synology's default NFS rule.
put film-hevc.mkv "public/Films/Nosferatu (1922)/Nosferatu (1922).mkv"

# Export /volume1/private, allowed to one address no client has.
put film-hevc.mkv "private/Films/Le Film privé (2020)/Le Film privé (2020).mkv"

# Share « admin$ », which the account may not open.
printf 'Partage administratif de test.\n' | write "admin/Lisez-moi.txt"

# Readable by the servers' own users (Samba's account, unfs3's nobody), whatever the umask.
chmod -R a+rX "$MEDIA"
echo "Sample files in $MEDIA: $(find "$MEDIA" -path "$BASE" -prune -o -type f -print | wc -l) files."
