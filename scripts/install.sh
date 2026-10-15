#!/usr/bin/env bash
# Builds a variant and installs it with its baseline profile compiled at install time: the APK and
# its dex metadata (.dm) go in one session, so the player is smooth from the first launch instead of
# the first night it spends in standby (when Android compiles apps in the background).
# Usage: scripts/install.sh [release|bench] [device serial]   (default: bench, the only device)
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VARIANT="${1:-bench}"
SERIAL="${2:-${ANDROID_SERIAL:-}}"
adb() { if [[ -n "$SERIAL" ]]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

case "$VARIANT" in
    release | bench) ;;
    *) echo "Variant: release or bench (debug builds are not compiled ahead of time)." >&2; exit 1 ;;
esac

cd "$ROOT"
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
