#!/usr/bin/env bash
set -euo pipefail

apk="${1:?Pass the path to the APK to verify.}"
apksigner="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK.}/build-tools/35.0.0/apksigner"

# Check the legacy JAR signature as well as the modern APK signing block.
report="$(LC_ALL=C "$apksigner" verify --verbose --min-sdk-version 23 "$apk")"
printf '%s\n' "$report"
for scheme in 1 2 3; do
    if ! grep -Eq "^Verified using v${scheme} scheme .*: true$" <<< "$report"; then
        printf 'APK signature scheme v%s is missing or invalid.\n' "$scheme" >&2
        exit 1
    fi
done
