#!/bin/bash
# Build four test APKs (normal, normal-r8, system, system-r8), platform-sign each, and keep artifacts under ignored build/.
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
# Extract version name and name APKs with the version tag.
version_name=$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' "$repo/mobile/build.gradle.kts")
version_tag="v${version_name:-0.2.26}"
normal_apk="Apple-CarPlay-${version_tag}-normal.apk"
normal_r8_apk="Apple-CarPlay-${version_tag}-normal-r8.apk"
system_apk="Apple-CarPlay-${version_tag}-system.apk"
system_r8_apk="Apple-CarPlay-${version_tag}-system-r8.apk"

key="$signing_dir/platform.pk8"
cert="$signing_dir/platform.x509.pem"
for file in "$key" "$cert" "$tools/apksigner" "$tools/zipalign" "$tools/aapt2"; do
    [[ -f "$file" ]] || { printf 'Missing required input: %s\n' "$file" >&2; exit 1; }
done
mkdir -p "$output"

extra_gradle_args=()
if [[ ! -f "$ANDROID_HOME/ndk/28.2.13676358/source.properties" ]]; then
    for installed_ndk in "$ANDROID_HOME/ndk"/*; do
        if [[ -f "$installed_ndk/source.properties" ]]; then
            ndk_version=$(basename "$installed_ndk")
            init_script="$output/use-ndk.gradle"
            cat << EOF > "$init_script"
gradle.beforeProject { p ->
    p.plugins.withId("com.android.library") {
        p.extensions.getByName("androidComponents").finalizeDsl { dsl ->
            if (p.path == ":shared") dsl.ndkVersion = "$ndk_version"
        }
    }
}
EOF
            extra_gradle_args+=("--init-script" "$init_script")
            break
        fi
    done
fi

task_debug=:mobile:assembleDebug
task_optimized=:mobile:assembleOptimizedDebug
standalone=false
if [[ -n "${DIPLAY_AUTH_ASSETS_DIR:-}" || -d "$repo/offline-mfi" ]]; then
    task_debug=:mobile:assembleStandaloneDebug
    task_optimized=:mobile:assembleStandaloneOptimizedDebug
    standalone=true
    printf '%s\n' 'Using local CarPlay authentication input (standalone build).'
else
    printf '%s\n' 'No root offline-mfi/ or DIPLAY_AUTH_ASSETS_DIR: source-only test APKs.' >&2
fi
expected_cert=$(openssl x509 -in "$cert" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr '[:upper:]' '[:lower:]')
[[ -n "$expected_cert" ]] || { printf '%s\n' 'Cannot read signing certificate' >&2; exit 1; }

build_one() {
    local kind="$1" system="$2" r8="$3" package="$4" final="$5"
    local gradle_task="$task_debug"
    local input="$repo/mobile/build/outputs/apk/debug/mobile-debug.apk"
    if [[ "$r8" == true ]]; then
        gradle_task="$task_optimized"
        input="$repo/mobile/build/outputs/apk/optimizedDebug/mobile-optimizedDebug.apk"
    fi
    printf '\n==================================================\n'
    printf 'Building %s (flymeSystem=%s, r8=%s)\n' "$kind" "$system" "$r8"
    printf '==================================================\n'
    rm -f "$input"
    ./gradlew ${extra_gradle_args[@]+"${extra_gradle_args[@]}"} "-PflymeSystem=$system" "$gradle_task" --console=plain --no-configuration-cache
    [[ -s "$input" ]] || { printf 'Missing build output: %s\n' "$input" >&2; return 1; }
    if [[ "$standalone" == true ]]; then
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
    if [[ "$system" == true ]]; then
        grep -F 'android.uid.system' "$output/$kind-manifest.txt" > /dev/null || {
            printf '%s\n' 'System APK missing shared UID declaration' >&2; return 1;
        }
        grep -F 'com.shilapi.flymeeas.ENABLED' "$output/$kind-manifest.txt" > /dev/null || {
            printf '%s\n' 'System APK missing EAS metadata' >&2; return 1;
        }
    else
        if grep -E 'sharedUserId|com.shilapi.flymeeas.ENABLED' "$output/$kind-manifest.txt" > /dev/null; then
            printf '%s\n' 'Ordinary APK unexpectedly declares shared UID or EAS opt-in' >&2
            return 1
        fi
    fi
    mv "$candidate" "$output/$final"
    rm -f "$aligned" "$candidate.idsig"
    if [[ "$r8" == true ]]; then
        local mapping_src="$repo/mobile/build/outputs/mapping/optimizedDebug/mapping.txt"
        if [[ -f "$mapping_src" ]]; then
            local mapping_final="${final%.apk}-mapping.txt"
            cp "$mapping_src" "$output/$mapping_final"
            printf 'Archived R8 mapping: %s\n' "$output/$mapping_final"
        fi
    fi
    local size_bytes size_mib
    size_bytes=$(stat -f %z "$output/$final" 2>/dev/null || wc -c < "$output/$final")
    size_mib=$(awk "BEGIN {printf \"%.2f\", $size_bytes / 1048576}")
    printf 'Verified: %s (%s MiB, %s bytes)\n' "$output/$final" "$size_mib" "$size_bytes"
}

build_one normal    false false com.shihab.diplay.test       "$normal_apk"
build_one normal-r8 false true  com.shihab.diplay.test       "$normal_r8_apk"
build_one system    true  false com.shihab.diplay.flyme.test "$system_apk"
build_one system-r8 true  true  com.shihab.diplay.flyme.test "$system_r8_apk"

shasum -a 256 "$output/$normal_apk" "$output/$normal_r8_apk" "$output/$system_apk" "$output/$system_r8_apk" > "$output/SHA256SUMS-${version_tag}.txt"
printf '\nAll four test APKs verified successfully. Output directory:\n  %s\n' "$output"
printf 'SHA-256 sums written to:\n  %s\n' "$output/SHA256SUMS-${version_tag}.txt"
