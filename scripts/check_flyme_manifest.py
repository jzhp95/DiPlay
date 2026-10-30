#!/usr/bin/env python3
"""Verify actual Gradle-merged Flyme manifests, including build-type isolation."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = Path(__file__).resolve().parents[1]


def check_manifest(path: Path, expected_package: str, debug: bool):
    manifest = ET.parse(path).getroot()
    if manifest.get("package") != expected_package:
        raise ValueError(f"{path}: expected package {expected_package}")
    if manifest.get(ANDROID + "sharedUserId") != "android.uid.system":
        raise ValueError(f"{path}: missing system UID declaration")
    application = manifest.find("application")
    if application is None:
        raise ValueError(f"{path}: missing application")
    enabled = [
        item.get(ANDROID + "value")
        for item in application.findall("meta-data")
        if item.get(ANDROID + "name") == "com.shilapi.flymeeas.ENABLED"
    ]
    if enabled != ["true"]:
        raise ValueError(f"{path}: EAS must be enabled exactly once")
    if (application.get(ANDROID + "debuggable", "false") == "true") != debug:
        raise ValueError(f"{path}: unexpected debuggable flag")
    authorities = {
        item.get(ANDROID + "authorities", "")
        for item in application.findall("provider")
    }
    for suffix in (".crash-record", ".diagnostic-reports"):
        if expected_package + suffix not in authorities:
            raise ValueError(f"{path}: provider identity mismatch for {suffix}")
    if any(".hudtest" in value for value in authorities):
        raise ValueError(f"{path}: obsolete provider authority")
    sdk = manifest.find("uses-sdk")
    if sdk is None or sdk.get(ANDROID + "minSdkVersion") != "28":
        raise ValueError(f"{path}: Android 9 minimum must be retained")
    return manifest.get(ANDROID + "versionCode"), manifest.get(ANDROID + "versionName")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--debug", type=Path, default=ROOT / "mobile/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
    parser.add_argument("--release", type=Path, default=ROOT / "mobile/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
    args = parser.parse_args()
    debug_code, debug_name = check_manifest(args.debug, "com.shihab.diplay.flyme.test", True)
    release_code, release_name = check_manifest(args.release, "com.shihab.diplay.flyme", False)
    if not release_code or not release_name or debug_code != release_code or debug_name != release_name + "-test":
        raise ValueError("Debug/release version codes must match; only the -test name suffix may differ")
    print(f"Flyme manifests verified: release=com.shihab.diplay.flyme {release_name}/{release_code}; debug=com.shihab.diplay.flyme.test {debug_name}/{debug_code}")


if __name__ == "__main__":
    main()
