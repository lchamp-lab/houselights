#!/usr/bin/env bash
# Runs the app in an Android TV 9 emulator close to a Zidoo Z1000 Pro (API 28, 2 GB of RAM).
# Usage: scripts/preview.sh
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
SDK="$ANDROID_HOME"
ADB="$SDK/platform-tools/adb"
AVD=houselights-tv
IMAGE="system-images;android-28;android-tv;x86"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ ! -e /dev/kvm ]]; then
    echo "KVM is not available: enable virtualization (SVM/VT-x) in the BIOS, then load the kvm module." >&2
    exit 1
fi

if ! "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD"; then
    echo "Creating the virtual TV $AVD…"
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d tv_1080p --force
    cfg="$HOME/.android/avd/$AVD.avd/config.ini"
    sed -i 's/^hw.ramSize=.*/hw.ramSize=2048/' "$cfg"
    grep -q '^hw.ramSize' "$cfg" || echo 'hw.ramSize=2048' >> "$cfg"
    echo 'hw.keyboard=yes' >> "$cfg"
    echo 'hw.gpu.enabled=yes' >> "$cfg"
    echo 'hw.gpu.mode=host' >> "$cfg"
fi

if ! "$ADB" devices | grep -q '^emulator-'; then
    echo "Starting the emulator…"
    nohup "$SDK/emulator/emulator" -avd "$AVD" -no-snapshot-save -no-boot-anim >/dev/null 2>&1 &
    "$ADB" wait-for-device
    until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
fi

echo "Building and installing…"
"$ROOT/gradlew" -p "$ROOT" installDebug --console=plain -q
"$ADB" -e shell am start -n app.houselights.debug/app.houselights.MainActivity >/dev/null
echo "Houselights is running. Arrows = navigate, Enter = select, Esc = back."
echo "Jellyfin on this computer? Use 10.0.2.2:8096 as the server address in the emulator."
