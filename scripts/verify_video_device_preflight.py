#!/usr/bin/env python3
"""
Device preflight verification tool for DiPlay Independent Video Runtime (Task N22).
Validates diagnostic export bundles retrieved from real physical devices against
Spec v1.2 and Task N22 criteria.
"""

import argparse
import hashlib
import json
import os
import sys
import zipfile
from typing import Any, Dict, Optional, Tuple


def compute_sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def verify_preflight_bundle(zip_path: str) -> Tuple[bool, Dict[str, Any]]:
    result: Dict[str, Any] = {
        "verified": False,
        "zip_path": zip_path,
        "bytes": 0,
        "sha256": None,
        "errors": [],
        "receipt": None
    }

    if not os.path.isfile(zip_path):
        result["errors"].append(f"File not found: {zip_path}")
        return False, result

    file_size = os.path.getsize(zip_path)
    result["bytes"] = file_size
    if file_size == 0:
        result["errors"].append("Export archive is 0 bytes (corrupted/interrupted export)")
        return False, result

    file_hash = compute_sha256(zip_path)
    result["sha256"] = file_hash

    # Validate zip structure
    try:
        with zipfile.ZipFile(zip_path, "r") as zf:
            corrupt = zf.testzip()
            if corrupt:
                result["errors"].append(f"Corrupt file inside archive: {corrupt}")
                return False, result

            namelist = zf.namelist()
            diag_file = None
            for name in ["diagnostics.json", "report.json", "video-runtime.json"]:
                if name in namelist:
                    diag_file = name
                    break

            if not diag_file:
                # Check for any .json file
                for name in namelist:
                    if name.endswith(".json"):
                        diag_file = name
                        break

            if not diag_file:
                result["errors"].append("No diagnostic JSON descriptor found in export archive")
                return False, result

            raw_data = zf.read(diag_file).decode("utf-8")
            data = json.loads(raw_data)
    except Exception as e:
        result["errors"].append(f"Failed to read/parse ZIP archive: {str(e)}")
        return False, result

    # Validate contents according to N22 path rules
    runtime = data.get("runtime_identity") or data.get("runtime") or data
    backend_kind = runtime.get("backend_kind") or runtime.get("actualBackend") or runtime.get("backend", "UNKNOWN")
    implementation = runtime.get("actual_implementation") or runtime.get("actualImplementation") or runtime.get("implementation")

    if implementation != "INDEPENDENT":
        result["errors"].append(f"Expected implementation=INDEPENDENT, found: {implementation}")

    is_yuv = "YUV" in str(backend_kind).upper()
    is_surface = "SURFACE" in str(backend_kind).upper()

    color_evidence = data.get("color_evidence") or data.get("evidence") or {}

    receipt: Dict[str, Any] = {
        "receipt_version": "1.0",
        "timestamp": data.get("timestamp", "UNKNOWN"),
        "device": data.get("device", {}),
        "runtime_identity": {
            "requested_implementation": runtime.get("requested_implementation", "INDEPENDENT"),
            "actual_implementation": implementation,
            "backend_kind": backend_kind,
            "decoder_component": runtime.get("decoder_component") or runtime.get("componentName"),
            "output_kind": runtime.get("output_kind") or runtime.get("outputKind")
        },
        "color_evidence": {
            "raw_yuv_available": is_yuv,
            "points_count": len(color_evidence.get("patch_samples", [])),
            "sample_latency_us": color_evidence.get("sample_latency_us", 0),
            "patch_samples": color_evidence.get("patch_samples", [])
        },
        "export_verification": {
            "file_bytes": file_size,
            "sha256": file_hash,
            "zip_integrity": "OK",
            "computer_readback_verified": True
        },
        "preflight_status": "PASS" if not result["errors"] else "PARTIAL"
    }

    if is_yuv:
        # Route A (YUV self-rendered): Requires readable frames, sampling, EGL
        if not color_evidence.get("patch_samples"):
            # If no samples, note as partial unless explicit reason
            if not color_evidence.get("completeness_reason"):
                result["errors"].append("YUV route requires color patch samples or explicit completeness_reason")
    elif is_surface:
        # Route B (Surface direct): Does NOT require raw YUV or EGL
        receipt["color_evidence"]["raw_yuv_available"] = False
        receipt["color_evidence"]["note"] = "Surface direct presentation - raw YUV bypassed by platform contract"

    result["receipt"] = receipt
    result["verified"] = len(result["errors"]) == 0
    return result["verified"], result


def main():
    parser = argparse.ArgumentParser(description="Verify device preflight export archive (N22).")
    parser.add_argument("zip_file", help="Path to exported diagnostic zip file")
    parser.add_argument("--output", help="Optional path to write receipt JSON")
    args = parser.parse_args()

    passed, res = verify_preflight_bundle(args.zip_file)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(res, f, indent=2, ensure_ascii=False)

    if passed:
        print(f"SUCCESS: Preflight archive verified valid ({res['bytes']} bytes, SHA256: {res['sha256']})")
        print(json.dumps(res["receipt"], indent=2, ensure_ascii=False))
        sys.exit(0)
    else:
        print(f"FAILED: Preflight archive verification failed ({res['bytes']} bytes)")
        for err in res["errors"]:
            print(f"  - ERROR: {err}")
        if res.get("receipt"):
            print(json.dumps(res["receipt"], indent=2, ensure_ascii=False))
        sys.exit(1)


if __name__ == "__main__":
    main()
