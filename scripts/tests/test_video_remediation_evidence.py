#!/usr/bin/env python3
"""
Unit tests for analyze_video_remediation_evidence.py
"""

import json
import os
import shutil
import tempfile
import unittest
import zipfile

# Add parent directory to import path
import sys
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from analyze_video_remediation_evidence import EvidenceAnalyzer


class TestVideoRemediationEvidence(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="test_remediation_")

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_empty_zip_marked_unreadable_empty(self):
        empty_path = os.path.join(self.test_dir, "empty-bundle.zip")
        with open(empty_path, "wb") as f:
            pass  # 0 bytes

        analyzer = EvidenceAnalyzer(self.test_dir)
        res = analyzer.scan()
        self.assertEqual("unreadable_empty", res["archiveStatus"])
        empty = res["archives"][0]
        self.assertEqual("unreadable_empty", empty["archiveStatus"])
        self.assertIsNone(res["touchToPhotonMs"])
        self.assertFalse(res["physicalPanelMeasured"])

    def test_corrupt_zip_marked_corrupt(self):
        corrupt_path = os.path.join(self.test_dir, "corrupt.zip")
        with open(corrupt_path, "wb") as f:
            f.write(b"NOT_A_VALID_ZIP_HEADER_DATA_123456789")

        analyzer = EvidenceAnalyzer(self.test_dir)
        res = analyzer.scan()
        self.assertEqual("unreadable_empty", res["archiveStatus"])
        archive = res["archives"][0]
        self.assertEqual("corrupt", archive["archiveStatus"])

    def test_sample_deduplication_and_conflict_detection(self):
        # Create zip 1 with sample A
        zip1_path = os.path.join(self.test_dir, "live-1.zip")
        sample_a = {"sampleId": "sample-001", "duration": 20.0, "path": "surface_worker_output"}
        with zipfile.ZipFile(zip1_path, "w") as z:
            rep = {"samples": [sample_a]}
            z.writestr("report.json", json.dumps(rep))

        # Create zip 2 with identical sample A (should be deduplicated)
        zip2_path = os.path.join(self.test_dir, "live-2.zip")
        with zipfile.ZipFile(zip2_path, "w") as z:
            rep = {"samples": [sample_a]}
            z.writestr("report.json", json.dumps(rep))

        analyzer = EvidenceAnalyzer(self.test_dir)
        res = analyzer.scan()
        self.assertEqual("readable", res["archiveStatus"])
        self.assertEqual(1, res["uniqueLiveSamples"])
        self.assertEqual(0, len(res["conflicts"]))

        # Create zip 3 with conflicting sample A (different content)
        zip3_path = os.path.join(self.test_dir, "live-3.zip")
        sample_a_conflict = {"sampleId": "sample-001", "duration": 15.0, "path": "software_yuv"}
        with zipfile.ZipFile(zip3_path, "w") as z:
            rep = {"samples": [sample_a_conflict]}
            z.writestr("report.json", json.dumps(rep))

        analyzer2 = EvidenceAnalyzer(self.test_dir)
        res2 = analyzer2.scan()
        self.assertEqual(1, len(res2["conflicts"]))
        conflicting_sample = res2["conflicts"][0]
        self.assertEqual("conflict", conflicting_sample["mergeStatus"])
        self.assertEqual("sample-001", conflicting_sample["sampleId"])

    def test_fixture_cases_count_and_sample_ids(self):
        zip_path = os.path.join(self.test_dir, "decoder-diagnostics.zip")
        cases = [
            {"caseId": f"h264_light_full-P{i}", "execution": "completed"}
            for i in range(1, 7)
        ]
        with zipfile.ZipFile(zip_path, "w") as z:
            z.writestr("run.json", json.dumps({"id": "run-test-1", "results": cases}))

        analyzer = EvidenceAnalyzer(self.test_dir)
        res = analyzer.scan()
        self.assertEqual("readable", res["archiveStatus"])
        self.assertEqual(6, res["fixtureCases"])
        self.assertIsNone(res["touchToPhotonMs"])

    def test_fixture_cases_across_different_runs_are_preserved_not_collapsed(self):
        zip1_path = os.path.join(self.test_dir, "diag-run1.zip")
        cases1 = [{"caseId": "case-01", "execution": "completed", "fps": 30.0}]
        with zipfile.ZipFile(zip1_path, "w") as z:
            z.writestr("run.json", json.dumps({"id": "run-001", "results": cases1}))

        zip2_path = os.path.join(self.test_dir, "diag-run2.zip")
        cases2 = [{"caseId": "case-01", "execution": "completed", "fps": 60.0}]
        with zipfile.ZipFile(zip2_path, "w") as z:
            z.writestr("run.json", json.dumps({"id": "run-002", "results": cases2}))

        analyzer = EvidenceAnalyzer(self.test_dir)
        res = analyzer.scan()
        self.assertEqual(2, res["fixtureCases"])
        self.assertEqual(0, len(res["conflicts"]))

        zip3_path = os.path.join(self.test_dir, "diag-run1-conflict.zip")
        cases1_conflict = [{"caseId": "case-01", "execution": "failed"}]
        with zipfile.ZipFile(zip3_path, "w") as z:
            z.writestr("run.json", json.dumps({"id": "run-001", "results": cases1_conflict}))

        analyzer2 = EvidenceAnalyzer(self.test_dir)
        res2 = analyzer2.scan()
        self.assertEqual(1, len(res2["conflicts"]))
        self.assertEqual("case-01", res2["conflicts"][0]["caseId"])
        self.assertEqual("run-001", res2["conflicts"][0]["runId"])


if __name__ == "__main__":
    unittest.main()
