#!/usr/bin/env bash
set -euo pipefail

sdk="$RUNNER_TEMP/phone-agent-sdk"
staging="$(mktemp -d "$RUNNER_TEMP/phone-agent-sdk-install.XXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
archive="$staging/android-tools.zip"

curl --fail --show-error --silent --location --retry 3 \
    https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
    --output "$archive"
printf '%s  %s\n' '7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee' "$archive" | sha256sum -c -
unzip -q "$archive" -d "$staging"
mkdir -p "$sdk/cmdline-tools"
mv "$staging/cmdline-tools" "$sdk/cmdline-tools/latest"
sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
"$sdkmanager" --sdk_root="$sdk" --licenses < <(yes) > /dev/null
"$sdkmanager" --sdk_root="$sdk" 'platforms;android-35' 'build-tools;35.0.0'
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$sdk" "$sdk" >> "$GITHUB_ENV"
