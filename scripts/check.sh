#!/usr/bin/env bash
# The checks a branch passes before it is merged: CI's (.github/workflows/ci.yml), then the debug build
# scripts/preview.sh installs. Branches are merged locally and only main is pushed, so CI sees a branch
# once it is on main: run this on the branch first.
# Usage: scripts/check.sh [--heap SIZE] [--workers N] [--in-process] [other Gradle arguments…]
#   --heap SIZE    Gradle's maximum heap, 2g for CI's (default: gradle.properties's)
#   --workers N    at most N tasks at a time (default: one per core)
#   --in-process   compiles Kotlin inside Gradle's JVM, not in a daemon of its own: one JVM less
# CI's settings, for a machine short of memory: scripts/check.sh --heap 2g --workers 2 --in-process --no-daemon
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"

options=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --heap)
            [[ $# -ge 2 ]] || { echo "--heap needs a size, such as 2g." >&2; exit 1; }
            options+=("-Dorg.gradle.jvmargs=-Xmx$2 -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8")
            shift 2
            ;;
        --workers)
            [[ $# -ge 2 ]] || { echo "--workers needs a number." >&2; exit 1; }
            options+=("--max-workers=$2")
            shift 2
            ;;
        --in-process)
            options+=("-Pkotlin.compiler.execution.strategy=in-process")
            shift
            ;;
        *)
            options+=("$1")
            shift
            ;;
    esac
done

cd "$ROOT"
# Without keystore.properties the release is signed with this machine's debug key: it is checked
# here, never installed (CONTRIBUTING, Signing).
exec ./gradlew ${options[@]+"${options[@]}"} -Phouselights.debugSignedRelease=true \
    testDebugUnitTest lintDebug detekt detektDebug detektDebugUnitTest assembleRelease assembleDebug \
    :baselineprofile:compileNonMinifiedReleaseKotlin
