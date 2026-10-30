# Build DiPlay

Use this procedure to build the DiPlay app for an Android head unit.
Run the commands from the repository root, where `gradlew` and `settings.gradle.kts` are located.

## Select the application

This branch builds one Android application, `mobile`, for the target Flyme Auto head unit.
Select `mobile` to build the main DiPlay app.

| Gradle module | Purpose | Debug APK |
| --- | --- | --- |
| `:mobile` | Main DiPlay app for Android head units | `mobile/build/outputs/apk/debug/mobile-debug.apk` |

The `:common` and `:shared` modules are libraries.
The main application uses these libraries.

The independent Automotive, Home and map-host applications have been removed.

## Prepare the build environment

Install these tools:

- JDK 25.
- Android SDK Platform 37 (`platforms;android-37.0`).
- Android SDK Build-Tools 36.1.0.
- Android SDK Platform-Tools.
- Android NDK 28.2.13676358.

Use the Gradle wrapper included in this repository.
The first build needs an internet connection to download dependencies.

1. Open the repository root in Android Studio.
2. Install the Android SDK packages with **Tools → SDK Manager**.
3. Set **Gradle JDK** to JDK 25 in the Android Studio Gradle settings.
4. Wait for Gradle sync to finish.

For a terminal build, set `JAVA_HOME` to your JDK 25 directory.
Set `ANDROID_HOME` to your Android SDK directory.
You can also specify the SDK directory in the root `local.properties` file:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Keep `local.properties` outside Git.
Check the Java version before the build:

```sh
java -version
```

The output must identify Java 25.
On Windows, use `gradlew.bat` in place of `./gradlew` in the commands below.

## Build the main app

When neither the root `offline-mfi/` directory nor `DIPLAY_AUTH_ASSETS_DIR` is provided, build without runtime authentication assets:

```sh
./gradlew :mobile:assembleDebug
```

Check that this file exists:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
```

The target head unit ABI is **arm64-v8a**. Both the shared native build and application packaging are restricted to arm64-v8a; APKs are not intended for 32-bit ARM or x86 devices/emulators. Check the built archive and ELF headers with `python3 scripts/check_arm64_apk.py`.

By default, the branch builds Flyme system packages. The debug application ID is `com.shihab.diplay.flyme.test`.
The release application ID is `com.shihab.diplay.flyme`.
Both declare `android.uid.system` and enable Flyme EAS metadata.
Use `-PflymeSystem=false` for ordinary packages without system UID or EAS opt-in; their debug and release application IDs are `com.shihab.diplay.test` and `com.shihab.diplay`.
The `optimizedDebug` build retains the corresponding debug application ID, so each R8/non-R8 pair replaces the same installation rather than installing side by side.
The debug version suffix is `-test`; the base version and version code are unchanged.

The former `com.shihab.diplay.flyme.hudtest` test package is a different Android application.
Its private settings do not automatically migrate to `.flyme.test`; keep the old installation and export reports before moving.
This build change does not uninstall the old package or erase its data.

The source APK contains no accessory identity unless you supply runtime authentication assets.
Standalone CarPlay needs these assets to connect to an iPhone.
Use the car-test procedure below for that purpose.
Tests generate synthetic identities at runtime.
Keep test private keys outside Git.

### Use Android Studio

1. Open **Run → Edit Configurations**.
2. Select an **Android App** configuration, or create one.
3. Set **Module** to `mobile`.
4. Select this configuration in the toolbar.
5. Select the Android device.
6. Build the APK, then use the platform-signing procedure below before installing it on the target head unit. The default Gradle debug signature does not satisfy the system UID requirement.

To build an APK without installation, run `:mobile:assembleDebug` in the Android Studio terminal.
Select the APK from the `mobile` output directory.

## Check the source build

Run the unit tests, lint checks, and main debug build:

```sh
./gradlew :flyme-eas:testDebugUnitTest :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

Also verify both merged manifests without needing release signing credentials:

```sh
./gradlew :mobile:processDebugMainManifest :mobile:processReleaseMainManifest
python3 scripts/check_flyme_manifest.py
```

Check that Gradle reports `BUILD SUCCESSFUL`.
For the complete CI procedure, see [the Android workflow](../.github/workflows/android.yml).

## Build a standalone car-test APK

Place your locally provisioned files in the ignored repository-root `offline-mfi/` directory for automatic detection, or prepare an external directory with this layout:

```text
runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

Only `identity.pk8` and `certificate.p7b` are staged into APK assets; the resource root is never bundled wholesale. Use only the intended runtime files in this directory.
Keep the directory and its private key outside Git.
The build rejects unexpected credential containers in APK assets.

1. For an external directory, set `DIPLAY_AUTH_ASSETS_DIR` to its absolute path. It overrides the repository-root `offline-mfi/` fallback.
2. Run the standalone debug task.

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

The task stops if either required file is absent or empty.
Its APK output is `mobile/build/outputs/apk/debug/mobile-debug.apk`.
Check both files in the APK against your selected local inputs:

- `assets/offline-mfi/identity.pk8`.
- `assets/offline-mfi/certificate.p7b`.

The APK must be signed with the target platform certificate before installing as a system UID app.
Run `integrations/flyme-eas/build-apks.sh` (or `build-apks.ps1` on Windows) to build and platform-sign the four test APKs:
- `Apple-CarPlay-v0.2.26-normal.apk`: Ordinary package (`com.shihab.diplay.test`), without system UID, non-R8.
- `Apple-CarPlay-v0.2.26-normal-r8.apk`: Ordinary package (`com.shihab.diplay.test`), without system UID, with R8 optimization.
- `Apple-CarPlay-v0.2.26-system.apk`: Flyme system package (`com.shihab.diplay.flyme.test`), with `android.uid.system` and EAS opt-in, non-R8.
- `Apple-CarPlay-v0.2.26-system-r8.apk`: Flyme system package (`com.shihab.diplay.flyme.test`), with `android.uid.system` and EAS opt-in, with R8 optimization.

Output directory: `build/car-test-apks/`.
Updates with the same new application ID and signing key preserve that installation’s settings.
Different application IDs do not automatically share settings, even when using a shared UID.

## Build a release APK

Prepare the runtime assets as described in the car-test procedure.
Set these environment variables locally:

| Variable | Value |
| --- | --- |
| `DIPLAY_AUTH_ASSETS_DIR` | Optional absolute-path override for the runtime assets root; otherwise use root `offline-mfi/` |
| `ANDROID_KEYSTORE_PATH` | Path to the target-platform-compatible Android signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Keep the keystore and passwords outside Git.
Run the release checks and build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

The output is `mobile/build/outputs/apk/release/mobile-release.apk`.
The APK contains the runtime identity you supplied.
Recipients can extract that identity from the APK.
The APK excludes the Android signing key.
See [the project notices](THIRD_PARTY_NOTICES.md) for the experimental identity used in public releases.

The public source archive matches the release tag.
It excludes runtime identities, signing keys, local configuration, and build output.
The retired `build-beta.py` helper is no longer part of the build procedure.

## Correct build problems

| Symptom | Action |
| --- | --- |
| Gradle cannot find the Android SDK | Set `ANDROID_HOME` or the `sdk.dir` value in `local.properties`. |
| A required SDK or NDK package is absent | Install the listed package with SDK Manager. |
| Java or Gradle JDK version is incorrect | Set the terminal JDK and Android Studio Gradle JDK to Java 25. |
| Source APK cannot start standalone CarPlay | Supply runtime authentication assets. Use `:mobile:assembleStandaloneDebug`. |
| Standalone task reports missing authentication files | Check root `offline-mfi/` or the explicit external directory path and both required files. |
| Android rejects an app update | Use the same application ID and signing key as the installed app. |
