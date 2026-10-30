#!/bin/bash
# Build the Flyme test APK, platform-sign it, and keep artifacts under ignored build/.
set -euo pipefail
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$repo"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 25)
fi
signing_dir="${DIPLAY_PLATFORM_SIGNING_DIR:-$repo/AOSP-platform-signing}"
tools="$ANDROID_HOME/build-tools/36.1.0"
output="$repo/build/car-test-apks"
# Keep this APK and its checksum together under one local-time stamp.
build_stamp=$(date '+%Y%m%d-%H%M%S')
flyme_apk="Apple-CarPlay-flyme-test-$build_stamp.apk"
key="$signing_dir/platform.pk8"
cert="$signing_dir/platform.x509.pem"
for file in "$key" "$cert" "$tools/apksigner" "$tools/zipalign" "$tools/aapt2"; do
    [[ -f "$file" ]] || { printf 'Missing required input: %s\n' "$file" >&2; exit 1; }
done
mkdir -p "$output"

task=:mobile:assembleDebug
if [[ -n "${DIPLAY_AUTH_ASSETS_DIR:-}" || -d "$repo/offline-mfi" ]]; then
    task=:mobile:assembleStandaloneDebug
    printf '%s\n' 'Using local CarPlay authentication input (standalone build).'
else
    printf '%s\n' 'No root offline-mfi/ or DIPLAY_AUTH_ASSETS_DIR: source-only Flyme test APK.' >&2
fi
expected_cert=$(openssl x509 -in "$cert" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr '[:upper:]' '[:lower:]')
[[ -n "$expected_cert" ]] || { printf '%s\n' 'Cannot read signing certificate' >&2; exit 1; }

build_one() {
    local kind="$1" package="$2" final="$3"
    printf '\nBuilding %s\n' "$kind"
    ./gradlew "$task" --console=plain
    local input="$repo/mobile/build/outputs/apk/debug/mobile-debug.apk"
    [[ -s "$input" ]] || { printf 'Missing build output: %s\n' "$input" >&2; return 1; }
    if [[ "$task" == :mobile:assembleStandaloneDebug ]]; then
        "$JAVA_HOME/bin/jar" tf "$input" > "$output/$kind-apk-files.txt"
        for entry in assets/offline-mfi/identity.pk8 assets/offline-mfi/certificate.p7b; do
            grep -F -x "$entry" "$output/$kind-apk-files.txt" > /dev/null || {
                printf 'Required authentication asset missing from APK: %s\n' "$entry" >&2; return 1;
            }
        done
    fi
    local aligned="$output/$kind-aligned.apk" candidate="$output/$kind-candidate.apk"
    "$tools/zipalign" -f -P 16 4 "$input" "$aligned"
    "$tools/apksigner" sign --key "$key" --cert "$cert" --out "$candidate" "$aligned"
    "$tools/zipalign" -c -P 16 4 "$candidate"
    "$tools/apksigner" verify --verbose --print-certs "$candidate" > "$output/$kind-signature.txt"
    local actual_cert
    actual_cert=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$output/$kind-signature.txt" | tr '[:upper:]' '[:lower:]')
    [[ "$actual_cert" == "$expected_cert" ]] || { printf '%s\n' 'Signing certificate mismatch' >&2; return 1; }
    "$tools/aapt2" dump badging "$candidate" > "$output/$kind-badging.txt"
    "$tools/aapt2" dump xmltree "$candidate" --file AndroidManifest.xml > "$output/$kind-manifest.txt"
    grep -F "package: name='$package'" "$output/$kind-badging.txt" > /dev/null || {
        printf 'Unexpected package; expected %s\n' "$package" >&2; return 1;
    }
    grep -F 'android.uid.system' "$output/$kind-manifest.txt" > /dev/null
    grep -F 'com.shilapi.flymeeas.ENABLED' "$output/$kind-manifest.txt" > /dev/null
    mv "$candidate" "$output/$final"
    rm -f "$aligned" "$candidate.idsig"
    printf 'Verified: %s\n' "$output/$final"
}

build_one flyme-test com.shihab.diplay.flyme.test "$flyme_apk"
shasum -a 256 "$output/$flyme_apk" > "$output/SHA256SUMS-$build_stamp.txt"
printf '\nFlyme test APK verified. Output: %s\n' "$output"
printf '%s\n' 'The system APK declares shared UID; actual UID and OEM grants must still be checked after installation on the car.'
