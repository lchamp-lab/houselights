#!/usr/bin/env bash
# Builds a variant and installs it with its baseline profile compiled at install time: the APK and
# its dex metadata (.dm) go in one session, so the player is smooth from the first launch instead of
# the first night it spends in standby (when Android compiles apps in the background).
# Usage: scripts/install.sh [release|bench] [device serial]   (default: bench, the only device)
#        scripts/install.sh hooks   instead, the pre-commit hook for this clone and the ktlint it runs (CONTRIBUTING)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
VARIANT="${1:-bench}"

# The hook (.githooks/pre-commit) for every commit of this clone and its worktrees, and ktlint, fetched once so that the
# hook never needs the network.
if [[ "$VARIANT" == hooks ]]; then
    git -C "$ROOT" config core.hooksPath .githooks
    "$ROOT/scripts/ktlint.sh" --fetch
    echo "The pre-commit hook is on (git config core.hooksPath .githooks); git commit --no-verify skips it."
    exit 0
fi

SERIAL="${2:-${ANDROID_SERIAL:-}}"
adb() { if [[ -n "$SERIAL" ]]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

case "$VARIANT" in
    release | bench) ;;
    *) echo "Variant: release or bench (debug builds are not compiled ahead of time), or hooks." >&2; exit 1 ;;
esac

cd "$ROOT"
# The build refuses a release without your key; say here what to do about it.
if [[ "$VARIANT" == release && ! -f keystore.properties ]]; then
    cat >&2 <<'MSG'
No keystore.properties: a release is signed with your own key, chosen once, before its first
installation on the player. Android only updates an app signed with the same key: changing keys
later means uninstalling, which loses the library and watch history kept on the player.

  mkdir -p ~/.config/houselights && chmod 700 ~/.config/houselights
  keytool -genkeypair -keystore ~/.config/houselights/houselights-release.jks -storetype PKCS12 \
      -alias houselights -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Houselights, O=Houselights"

Then write keystore.properties as CONTRIBUTING.md shows (Signing), and keep a copy of both files.
Meanwhile, `scripts/install.sh bench` installs an optimised build beside the release.
MSG
    exit 1
fi
./gradlew -q "assemble${VARIANT^}"

OUT="app/build/outputs/apk/$VARIANT"
APK="$OUT/app-$VARIANT.apk"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"

# The dex metadata made for this device's Android version, listed by the build beside the APK.
DM="$(python3 - "$OUT/output-metadata.json" "$API" <<'PY'
import json, sys
meta = json.load(open(sys.argv[1]))
api = int(sys.argv[2])
for entry in meta.get("baselineProfiles", []):
    if entry["minApi"] <= api <= entry["maxApi"]:
        print(entry["baselineProfiles"][0])
PY
)"

if [[ -z "$DM" ]]; then
    echo "No baseline profile for API $API: installing the APK alone." >&2
    adb install -r "$APK"
    exit 0
fi

# The metadata must bear the APK's name for the installer to pair them.
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$APK" "$STAGE/houselights.apk"
cp "$OUT/$DM" "$STAGE/houselights.dm"
adb install-multiple -r "$STAGE/houselights.apk" "$STAGE/houselights.dm"

PACKAGE="app.houselights$([[ "$VARIANT" == bench ]] && echo .bench || true)"
adb shell dumpsys package dexopt | grep -A2 "\[$PACKAGE\]" | grep -o "status=[a-z-]*" || true
