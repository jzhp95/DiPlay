#!/bin/sh
# Exercise the real Gradle staging boundary using disposable, non-authentic test bytes.
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/offline-mfi"
printf 'fixture identity\n' > "$fixture/offline-mfi/identity.pk8"
printf 'fixture certificate\n' > "$fixture/offline-mfi/certificate.p7b"
printf 'not an APK asset\n' > "$fixture/SHOULD_NOT_BUNDLE.txt"
printf 'not a runtime key\n' > "$fixture/unrelated.pk8"
cd "$repo"
DIPLAY_AUTH_ASSETS_DIR="$fixture" ./gradlew :mobile:stageLocalAuthenticationAssets :mobile:rejectBundledCredentials --stacktrace
staged="$repo/mobile/build/generated/local-auth-assets"
cmp "$fixture/offline-mfi/identity.pk8" "$staged/offline-mfi/identity.pk8"
cmp "$fixture/offline-mfi/certificate.p7b" "$staged/offline-mfi/certificate.p7b"
count=$(find "$staged" -type f | wc -l | tr -d ' ')
[ "$count" = 2 ]
[ ! -e "$staged/SHOULD_NOT_BUNDLE.txt" ]
[ ! -e "$staged/unrelated.pk8" ]
printf '%s\n' 'Local authentication staging: exactly two selected files, no root-directory spillover.'
