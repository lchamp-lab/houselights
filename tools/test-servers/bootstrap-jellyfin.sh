#!/usr/bin/env bash
# Sets up the test Jellyfin server (compose.yaml, --profile jellyfin) as its first-run wizard would, through its HTTP
# API: French, the account houselights / houselights-test (test-only), then the libraries Films (movies), Séries
# (tvshows) and Vidéos perso (homevideos) on the sample files, read from their names and NFO alone (no metadata from
# the internet, no images extracted, so a scan takes seconds and gives the same titles everywhere), and waits for
# their scan. Idempotent: a server already set up is signed in to, a library already there is kept, and the scan runs
# again. Needs curl and jq.
# Usage: tools/test-servers/bootstrap-jellyfin.sh [server address, http://127.0.0.1:8096 by default]
set -euo pipefail

SERVER="${1:-http://127.0.0.1:8096}"
USER_NAME=houselights
PASSWORD=houselights-test
AUTH='MediaBrowser Client="Houselights test servers", Device="bootstrap", DeviceId="houselights-test-servers", Version="1"'
TOKEN=""

# api METHOD PATH [JSON]: the server's answer, failing on an HTTP error.
api() {
    local header="$AUTH"
    [[ -z "$TOKEN" ]] || header="$AUTH, Token=\"$TOKEN\""
    local body=()
    [[ $# -lt 3 ]] || body=(--data "$3")
    curl -fsS -X "$1" -H "Authorization: $header" -H "Content-Type: application/json" "${body[@]}" "$SERVER$2"
}

uri() { jq -rn --arg v "$1" '$v | @uri'; }

echo "Waiting for Jellyfin at $SERVER"
info=""
for _ in $(seq 1 180); do
    # 503 while it starts.
    info="$(curl -fsS "$SERVER/System/Info/Public" 2>/dev/null)" && break
    sleep 1
done
[[ -n "$info" ]] || { echo "Jellyfin did not answer at $SERVER" >&2; exit 1; }
jq -r '"\(.ProductName) \(.Version), wizard completed: \(.StartupWizardCompleted)"' <<<"$info"

if [[ "$(jq -r .StartupWizardCompleted <<<"$info")" != true ]]; then
    api POST /Startup/Configuration \
        '{"ServerName":"Houselights test","UICulture":"fr","MetadataCountryCode":"FR","PreferredMetadataLanguage":"fr"}'
    # The first user, made by the server at its first start, renamed and given its password.
    api GET /Startup/User >/dev/null
    api POST /Startup/User "$(jq -nc --arg name "$USER_NAME" --arg password "$PASSWORD" '{Name: $name, Password: $password}')"
    api POST /Startup/RemoteAccess '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}'
    api POST /Startup/Complete
    echo "Wizard completed: user $USER_NAME, French"
fi

signed_in="$(api POST /Users/AuthenticateByName \
    "$(jq -nc --arg name "$USER_NAME" --arg password "$PASSWORD" '{Username: $name, Pw: $password}')")"
TOKEN="$(jq -r .AccessToken <<<"$signed_in")"

existing="$(api GET /Library/VirtualFolders | jq -r '.[].Name')"

# library NAME TYPE PATH ITEM_TYPES…: the library NAME of collection TYPE on PATH, its ITEM_TYPES read from the files
# alone, unless there already.
library() {
    local name="$1" type="$2" path="$3"
    shift 3
    if grep -qxF "$name" <<<"$existing"; then
        echo "Library $name: already there"
        return
    fi
    local options
    options="$(jq -nc '{LibraryOptions: {
        EnableRealtimeMonitor: false,
        EnablePhotos: false,
        EnableChapterImageExtraction: false,
        ExtractChapterImagesDuringLibraryScan: false,
        EnableTrickplayImageExtraction: false,
        ExtractTrickplayImagesDuringLibraryScan: false,
        SaveLocalMetadata: false,
        MetadataSavers: [],
        PreferredMetadataLanguage: "fr",
        MetadataCountryCode: "FR",
        TypeOptions: [$ARGS.positional[] | {Type: ., MetadataFetchers: [], ImageFetchers: []}]
    }}' --args "$@")"
    api POST "/Library/VirtualFolders?name=$(uri "$name")&collectionType=$type&paths=$(uri "$path")&refreshLibrary=false" \
        "$options" >/dev/null
    echo "Library $name ($type): $path"
}

library Films movies /volume1/video/Films Movie
library Séries tvshows /volume1/video/Séries Series Season Episode
library "Vidéos perso" homevideos "/volume1/media/Vidéos perso" Video

# make-media.sh's titles: 6 films (Heat's two parts are one), 6 episodes.
films=6
episodes=6
api POST /Library/Refresh
echo "Scanning"
counts=""
for _ in $(seq 1 300); do
    sleep 1
    state="$(api GET '/ScheduledTasks?isHidden=false' | jq -r '.[] | select(.Key == "RefreshLibrary") | .State')"
    counts="$(api GET /Items/Counts)"
    if [[ "$state" == Idle ]] \
        && (( $(jq .MovieCount <<<"$counts") >= films && $(jq .EpisodeCount <<<"$counts") >= episodes )); then
        jq -r '"Scanned: \(.MovieCount) films, \(.SeriesCount) shows, \(.EpisodeCount) episodes"' <<<"$counts"
        api GET /Library/VirtualFolders | jq -r '.[] | "  \(.Name) (\(.CollectionType)): \(.Locations | join(", "))"'
        exit 0
    fi
done
echo "The scan did not find the sample files in 5 minutes: $counts" >&2
exit 1
