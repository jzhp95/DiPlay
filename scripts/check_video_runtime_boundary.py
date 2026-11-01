#!/usr/bin/env python3
"""
Boundary check script for CarPlay video runtime pipeline.
Ensures new code in com.shilapi.xcertplay.media.video.* does NOT import or call prohibited legacy video classes:
- AndroidVideoPipeline
- SoftwareYuvRenderer
- Yuv420Frame
- SoftwareYuvColor
- VideoOutputFreshness
- VideoPlanResolver
"""

import os
import sys
from pathlib import Path

PROHIBITED_SYMBOLS = [
    "AndroidVideoPipeline",
    "SoftwareYuvRenderer",
    "Yuv420Frame",
    "SoftwareYuvColor",
    "VideoOutputFreshness",
    "VideoPlanResolver",
]

def scan_file(filepath: Path) -> list:
    violations = []
    # LegacyVideoEndpoint is the only allowed legacy adapter
    if filepath.name == "LegacyVideoEndpoint.kt":
        return violations

    try:
        content = filepath.read_text(encoding="utf-8")
        for line_num, line in enumerate(content.splitlines(), start=1):
            stripped = line.strip()
            # Ignore comments
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            for symbol in PROHIBITED_SYMBOLS:
                if symbol in line:
                    violations.append((filepath, line_num, symbol, line.strip()))
    except Exception as e:
        violations.append((filepath, 0, "READ_ERROR", str(e)))
    return violations

def check_boundaries(root_dir: Path) -> bool:
    video_dir = root_dir / "shared" / "src" / "main" / "java" / "com" / "shilapi" / "xcertplay" / "media" / "video"
    video_test_dir = root_dir / "shared" / "src" / "test" / "java" / "com" / "shilapi" / "xcertplay" / "media" / "video"

    all_violations = []

    for d in [video_dir, video_test_dir]:
        if not d.exists():
            continue
        for root, _, files in os.walk(d):
            for f in files:
                if f.endswith(".kt") or f.endswith(".java"):
                    violations = scan_file(Path(root) / f)
                    all_violations.extend(violations)

    if all_violations:
        print("ERROR: Boundary violations detected in independent video package:")
        for path, line, symbol, text in all_violations:
            print(f"  {path}:{line}: references prohibited '{symbol}' -> '{text}'")
        return False

    print("SUCCESS: All video runtime boundary checks passed clean.")
    return True

if __name__ == "__main__":
    repo_root = Path(__file__).resolve().parent.parent
    success = check_boundaries(repo_root)
    sys.exit(0 if success else 1)
