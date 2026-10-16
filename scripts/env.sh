# Sourced by the scripts: the JDK, the Android SDK and adb of whatever machine runs them, the
# environment's first. Needs ROOT, the project's directory.

# JAVA_HOME as set; else the JDK 17 some machines keep under ~/.local/share/jdk-17, when there is one;
# else none, and gradlew runs the java on the PATH.
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/.local/share/jdk-17/bin/java" ]]; then
    export JAVA_HOME="$HOME/.local/share/jdk-17"
fi

# ANDROID_HOME as set; else ANDROID_SDK_ROOT; else local.properties's sdk.dir (Android Studio writes
# it); else ~/Android/Sdk, Android Studio's default on Linux, when it exists.
if [[ -z "${ANDROID_HOME:-}" ]]; then
    sdk_dir=""
    if [[ -f "$ROOT/local.properties" ]]; then
        sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -n 1)"
    fi
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        export ANDROID_HOME="$ANDROID_SDK_ROOT"
    elif [[ -n "$sdk_dir" ]]; then
        export ANDROID_HOME="$sdk_dir"
    elif [[ -d "$HOME/Android/Sdk" ]]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    fi
    unset sdk_dir
fi

# adb: the one on the PATH, else the SDK's.
ADB="$(command -v adb || echo "${ANDROID_HOME:-}/platform-tools/adb")"
