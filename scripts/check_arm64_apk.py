#!/usr/bin/env python3
"""Verify native APK entries are ARM64 ELF files and include the required JNI libraries."""
import argparse
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {"liblocal_hotspot_radio.so", "libspeex_echo.so", "libxcertplay_i2c.so"}


def inspect(apk):
    entries = []
    with zipfile.ZipFile(apk) as archive:
        libraries = [entry for entry in archive.infolist()
                     if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        if not libraries:
            raise ValueError("APK has no native libraries")
        names = set()
        for entry in libraries:
            parts = entry.filename.split("/")
            if len(parts) != 3 or parts[1] != "arm64-v8a":
                raise ValueError(f"Unexpected native ABI/path: {entry.filename}")
            if entry.filename in names:
                raise ValueError(f"Duplicate native entry: {entry.filename}")
            names.add(entry.filename)
            with archive.open(entry) as stream:
                header = stream.read(20)
            if (len(header) != 20 or header[:4] != b"\x7fELF" or header[4] != 2
                    or header[5] != 1 or int.from_bytes(header[18:20], "little") != 183):
                raise ValueError(f"Not a little-endian ELF64 AArch64 library: {entry.filename}")
            entries.append({"path": entry.filename, "bytes": entry.file_size,
                            "compressedBytes": entry.compress_size})
        missing = REQUIRED - {Path(entry["path"]).name for entry in entries}
        if missing:
            raise ValueError("Missing required JNI libraries: " + ", ".join(sorted(missing)))
    return {"passed": True, "abi": "arm64-v8a", "apkBytes": apk.stat().st_size,
            "nativeBytes": sum(entry["bytes"] for entry in entries), "libraries": entries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="?", type=Path,
                        default=ROOT / "mobile/build/outputs/apk/debug/mobile-debug.apk")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = inspect(args.apk)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"ARM64 APK verification failed: {error}\n")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"ARM64 APK verified: {len(result['libraries'])} ELF64 AArch64 libraries; "
          f"APK {result['apkBytes']} bytes, native libraries {result['nativeBytes']} bytes")


if __name__ == "__main__":
    main()
