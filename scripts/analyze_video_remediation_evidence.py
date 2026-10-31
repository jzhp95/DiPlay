#!/usr/bin/env python3
"""
Offline log analysis tool for DiPlay video color, latency, and stutter remediation.
Extracts baseline evidence from decoder diagnostics and live CarPlay bundles.
"""

import argparse
import hashlib
import json
import os
import sys
import zipfile
from typing import Any, Dict, List, Optional, Tuple


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class EvidenceAnalyzer:
    def __init__(self, input_dir: str):
        self.input_dir = os.path.abspath(input_dir)
        self.archives: List[Dict[str, Any]] = []
        self.live_samples: Dict[str, Dict[str, Any]] = {}
        self.fixture_cases: Dict[str, Dict[str, Any]] = {}
        self.conflicts: List[Dict[str, Any]] = []

    def scan(self) -> Dict[str, Any]:
        if not os.path.isdir(self.input_dir):
            return {
                "archiveStatus": "missing_directory",
                "uniqueLiveSamples": 0,
                "fixtureCases": 0,
                "physicalPanelMeasured": False,
                "touchToPhotonMs": None,
                "archives": [],
                "errors": [f"Input directory does not exist: {self.input_dir}"]
            }

        files = sorted(os.listdir(self.input_dir))
        for fname in files:
            fpath = os.path.join(self.input_dir, fname)
            if not os.path.isfile(fpath):
                continue
            if not fname.endswith(".zip"):
                continue

            size = os.path.getsize(fpath)
            if size == 0:
                self.archives.append({
                    "name": fname,
                    "bytes": 0,
                    "sha256": None,
                    "archiveStatus": "unreadable_empty",
                    "kind": "unknown"
                })
                continue

            f_sha256 = sha256_file(fpath)
            try:
                with zipfile.ZipFile(fpath, "r") as z:
                    bad_file = z.testzip()
                    if bad_file:
                        self.archives.append({
                            "name": fname,
                            "bytes": size,
                            "sha256": f_sha256,
                            "archiveStatus": "corrupt",
                            "corruptedEntry": bad_file,
                            "kind": "unknown"
                        })
                        continue

                    entries = z.namelist()
                    kind = "unknown"
                    if "run.json" in entries or "manifest.json" in entries:
                        kind = "decoder_diagnostics"
                        self._process_decoder_diagnostics(z, fname, f_sha256, size)
                    elif "report.json" in entries or "bundle-manifest.json" in entries:
                        kind = "live_carplay"
                        self._process_live_carplay(z, fname, f_sha256, size)
                    else:
                        self.archives.append({
                            "name": fname,
                            "bytes": size,
                            "sha256": f_sha256,
                            "archiveStatus": "readable",
                            "kind": kind,
                            "entriesCount": len(entries)
                        })

            except zipfile.BadZipFile:
                self.archives.append({
                    "name": fname,
                    "bytes": size,
                    "sha256": f_sha256,
                    "archiveStatus": "corrupt",
                    "kind": "unknown"
                })
            except Exception as e:
                self.archives.append({
                    "name": fname,
                    "bytes": size,
                    "sha256": f_sha256,
                    "archiveStatus": "error",
                    "error": str(e),
                    "kind": "unknown"
                })

        return self.summary()

    def _process_decoder_diagnostics(self, z: zipfile.ZipFile, fname: str, f_sha256: str, size: int):
        run_id = None
        case_count = 0
        try:
            if "run.json" in z.namelist():
                run_data = json.loads(z.read("run.json").decode("utf-8"))
                run_id = run_data.get("id") or run_data.get("runId")
                results = run_data.get("results", [])
                for r in results:
                    cid = r.get("caseId") or r.get("id")
                    if not cid:
                        continue
                    r_str = json.dumps(r, sort_keys=True)
                    r_hash = sha256_bytes(r_str.encode("utf-8"))
                    key = f"{run_id}:{cid}" if run_id else f"{fname}:{cid}"
                    if key in self.fixture_cases:
                        existing = self.fixture_cases[key]
                        if existing["contentHash"] != r_hash:
                            self.conflicts.append({
                                "caseId": cid,
                                "runId": run_id,
                                "archiveA": existing["archive"],
                                "archiveB": fname,
                                "mergeStatus": "conflict"
                            })
                    else:
                        self.fixture_cases[key] = {
                            "archive": fname,
                            "runId": run_id,
                            "caseId": cid,
                            "contentHash": r_hash,
                            "case": r
                        }
                case_count = len(results)
            elif "manifest.json" in z.namelist():
                mani = json.loads(z.read("manifest.json").decode("utf-8"))
                run_id = mani.get("id") or mani.get("runId")
        except Exception:
            pass

        self.archives.append({
            "name": fname,
            "bytes": size,
            "sha256": f_sha256,
            "archiveStatus": "readable",
            "kind": "decoder_diagnostics",
            "runId": run_id,
            "caseCount": case_count
        })

    def _process_live_carplay(self, z: zipfile.ZipFile, fname: str, f_sha256: str, size: int):
        samples_in_bundle = 0
        session_id = None
        try:
            if "report.json" in z.namelist():
                rep = json.loads(z.read("report.json").decode("utf-8"))
                session_id = rep.get("sessionConfiguration", {}).get("sessionId")
                samples = rep.get("samples", [])
                samples_in_bundle = len(samples)
                for s in samples:
                    sid = s.get("sampleId")
                    if not sid:
                        continue
                    # Deduplicate by sampleId
                    s_str = json.dumps(s, sort_keys=True)
                    s_hash = sha256_bytes(s_str.encode("utf-8"))
                    if sid in self.live_samples:
                        existing = self.live_samples[sid]
                        if existing["contentHash"] != s_hash:
                            self.conflicts.append({
                                "sampleId": sid,
                                "archiveA": existing["archive"],
                                "archiveB": fname,
                                "mergeStatus": "conflict"
                            })
                    else:
                        self.live_samples[sid] = {
                            "archive": fname,
                            "contentHash": s_hash,
                            "sample": s
                        }
        except Exception:
            pass

        self.archives.append({
            "name": fname,
            "bytes": size,
            "sha256": f_sha256,
            "archiveStatus": "readable",
            "kind": "live_carplay",
            "sessionId": session_id,
            "samplesInArchive": samples_in_bundle
        })

    def summary(self) -> Dict[str, Any]:
        has_readable = any(a.get("archiveStatus") == "readable" for a in self.archives)
        return {
            "archiveStatus": "readable" if has_readable else "unreadable_empty",
            "duplicateBasis": "runId_and_entry_content",
            "uniqueLiveSamples": len(self.live_samples),
            "fixtureCases": len(self.fixture_cases),
            "physicalPanelMeasured": False,
            "touchToPhotonMs": None,
            "archivesCount": len(self.archives),
            "archives": self.archives,
            "conflicts": self.conflicts,
            "uniqueLiveSampleIds": sorted(list(self.live_samples.keys()))
        }


def main():
    parser = argparse.ArgumentParser(description="Analyze DiPlay video evidence archives.")
    parser.add_argument("--input-dir", required=True, help="Path to input directory containing zip logs.")
    parser.add_argument("--output", required=False, help="Path to write baseline-observations.json output.")
    args = parser.parse_args()

    analyzer = EvidenceAnalyzer(args.input_dir)
    res = analyzer.scan()

    out_str = json.dumps(res, indent=2, sort_keys=True)
    if args.output:
        os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(out_str)
        print(f"Wrote baseline observations to {args.output}")
    else:
        print(out_str)


if __name__ == "__main__":
    main()
