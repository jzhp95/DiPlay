#!/bin/bash
# Build two test APKs, platform-sign both, and keep artifacts under ignored build/.
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
key="$signing_dir/platform.pk8"
cert="$signing_dir/platform.x509.pem"
for file in "$key" "$cert" "$tools/apksigner" "$tools/zipalign" "$tools/aapt2"; do
    [[ -f "$file" ]] || { printf 'Missing required input: %s\n' "$file" >&2; exit 1; }
done
mkdir -p "$output"
# Remove only previous final outputs; a failed run must not look like a successful new build.
rm -f "$output/Apple-CarPlay-normal.apk" "$output/Apple-CarPlay-system-uid.apk"

task=:mobile:assembleDebug
if [[ -n "${DIPLAY_AUTH_ASSETS_DIR:-}" ]]; then
    task=:mobile:assembleStandaloneDebug
else
    printf '%s\n' 'No DIPLAY_AUTH_ASSETS_DIR: source-only test APKs, without standalone iPhone authentication.' >&2
fi
expected_cert=$(openssl x509 -in "$cert" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr '[:upper:]' '[:lower:]')
[[ -n "$expected_cert" ]] || { printf '%s\n' 'Cannot read signing certificate' >&2; exit 1; }

build_one() {
    local kind="$1" system="$2" package="$3" final="$4"
    printf '\nBuilding %s (flymeSystem=%s)\n' "$kind" "$system"
    ./gradlew "-PflymeSystem=$system" "$task" --console=plain
    local input="$repo/mobile/build/outputs/apk/debug/mobile-debug.apk"
    [[ -s "$input" ]] || { printf 'Missing build output: %s\n' "$input" >&2; return 1; }
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
    if [[ "$system" == true ]]; then
        grep -F 'android.uid.system' "$output/$kind-manifest.txt" > /dev/null
        grep -F 'com.shilapi.flymeeas.ENABLED' "$output/$kind-manifest.txt" > /dev/null
    else
        if grep -E 'sharedUserId|com.shilapi.flymeeas.ENABLED' "$output/$kind-manifest.txt" > /dev/null; then
            printf '%s\n' 'Ordinary APK unexpectedly declares shared UID or EAS opt-in' >&2
            return 1
        fi
    fi
    mv "$candidate" "$output/$final"
    rm -f "$aligned" "$candidate.idsig"
    printf 'Verified: %s\n' "$output/$final"
}

build_one normal false com.shihab.diplay.hudtest Apple-CarPlay-normal.apk
build_one system-uid true com.shihab.diplay.flyme.hudtest Apple-CarPlay-system-uid.apk
shasum -a 256 "$output/Apple-CarPlay-normal.apk" "$output/Apple-CarPlay-system-uid.apk" > "$output/SHA256SUMS.txt"
printf '\nBoth APKs verified. Output: %s\n' "$output"
printf '%s\n' 'The system APK declares shared UID; actual UID and OEM grants must still be checked after installation on the car.'
