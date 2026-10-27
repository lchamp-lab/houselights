#!/usr/bin/env bash
# ktlint 0.50.0, the version of detekt's formatting rules (gradle/libs.versions.toml, detekt-formatting), for the
# pre-commit hook and by hand, with .editorconfig's settings: fetched once from ktlint's releases into this machine's
# cache, its SHA-256 checked, then run with no network.
# Usage: scripts/ktlint.sh --fetch               fetch it into the cache, if not there yet (scripts/install.sh hooks)
#        scripts/ktlint.sh [ktlint arguments]   run it on the files given, such as:
#          scripts/ktlint.sh --format app/src/main/java/app/houselights/Routes.kt
#        scripts/ktlint.sh --offline [arguments] the same, never fetching it (the hook)
set -euo pipefail

VERSION=0.50.0
URL="https://github.com/pinterest/ktlint/releases/download/$VERSION/ktlint"
# The SHA-256 of that file, which ktlint's signing key signs (ktlint.asc beside it, key 995EFBF4A3D20BEB).
SHA256=a2bd6f5bbed489fb37eb955012e6cbe8d3aebc3caa724610d94f4fca66411007
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/houselights"
KTLINT="$CACHE/ktlint-$VERSION"

sha256() {
    if command -v sha256sum > /dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d ' ' -f 1
}

fetch() {
    [[ -f "$KTLINT" ]] && return 0
    command -v curl > /dev/null || { echo "Fetching ktlint $VERSION needs curl." >&2; exit 1; }
    mkdir -p "$CACHE"
    local part
    part="$(mktemp "$CACHE/ktlint-$VERSION.XXXXXX")"
    echo "Fetching ktlint $VERSION into $CACHE…" >&2
    if ! curl --fail --silent --show-error --location --retry 3 --output "$part" "$URL"; then
        rm -f "$part"
        echo "Could not fetch $URL." >&2
        exit 1
    fi
    local found
    found="$(sha256 "$part")"
    if [[ "$found" != "$SHA256" ]]; then
        rm -f "$part"
        echo "ktlint $VERSION from $URL has SHA-256 $found, not $SHA256: not kept." >&2
        exit 1
    fi
    mv "$part" "$KTLINT"
}

case "${1:-}" in
    --fetch)
        fetch
        exit 0
        ;;
    --offline)
        shift
        if [[ ! -f "$KTLINT" ]]; then
            echo "ktlint $VERSION is not in $CACHE: scripts/install.sh hooks fetches it, once." >&2
            exit 2
        fi
        ;;
    *)
        fetch
        ;;
esac

# The JDK of JAVA_HOME, else the java on the PATH; ktlint 0.50.0 runs on Java 11 and later.
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java > /dev/null; then
    JAVA=java
else
    echo "ktlint needs Java 11 or later: set JAVA_HOME, or put java on the PATH." >&2
    exit 2
fi

# As ktlint's own launcher runs it.
exec "$JAVA" --add-opens java.base/java.lang=ALL-UNNAMED -Xmx512m -jar "$KTLINT" "$@"
