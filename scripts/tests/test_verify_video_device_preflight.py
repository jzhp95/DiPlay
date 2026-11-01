#!/usr/bin/env python3
import json
import os
import tempfile
import unittest
import zipfile

import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from verify_video_device_preflight import verify_preflight_bundle, compute_sha256


class VerifyVideoDevicePreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.temp_dir.cleanup()

    def create_zip(self, name: str, data: dict) -> str:
        zip_path = os.path.join(self.temp_dir.name, name)
        with zipfile.ZipFile(zip_path, "w") as zf:
            zf.writestr("diagnostics.json", json.dumps(data))
        return zip_path

    def test_zero_byte_zip_rejected(self):
        zero_zip = os.path.join(self.temp_dir.name, "empty.zip")
        with open(zero_zip, "wb") as f:
            pass
        passed, res = verify_preflight_bundle(zero_zip)
        self.assertFalse(passed)
        self.assertEqual(res["bytes"], 0)
        self.assertIn("0 bytes", res["errors"][0])

    def test_yuv_backend_with_samples_passes(self):
        data = {
            "runtime_identity": {
                "actual_implementation": "INDEPENDENT",
                "backend_kind": "PLATFORM_YUV",
                "decoder_component": "OMX.google.h264.decoder",
                "output_kind": "SURFACE_VIEW_EGL"
            },
            "color_evidence": {
                "sample_latency_us": 150,
                "patch_samples": [
                    {"name": "patch_black", "y": 16, "u": 128, "v": 128},
                    {"name": "patch_white", "y": 235, "u": 128, "v": 128}
                ]
            }
        }
        zip_path = self.create_zip("yuv.zip", data)
        passed, res = verify_preflight_bundle(zip_path)
        self.assertTrue(passed)
        self.assertTrue(res["receipt"]["color_evidence"]["raw_yuv_available"])
        self.assertEqual(res["receipt"]["color_evidence"]["points_count"], 2)

    def test_surface_backend_does_not_require_raw_yuv(self):
        data = {
            "runtime_identity": {
                "actual_implementation": "INDEPENDENT",
                "backend_kind": "PLATFORM_SURFACE",
                "decoder_component": "c2.qti.avc.decoder",
                "output_kind": "SURFACE_VIEW_DIRECT"
            },
            "color_evidence": {
                "completeness_reason": "surface_raw_bypassed"
            }
        }
        zip_path = self.create_zip("surface.zip", data)
        passed, res = verify_preflight_bundle(zip_path)
        self.assertTrue(passed)
        self.assertFalse(res["receipt"]["color_evidence"]["raw_yuv_available"])
        self.assertEqual(res["receipt"]["color_evidence"]["points_count"], 0)

    def test_legacy_implementation_rejected(self):
        data = {
            "runtime_identity": {
                "actual_implementation": "LEGACY",
                "backend_kind": "SOFTWARE_YUV"
            }
        }
        zip_path = self.create_zip("legacy.zip", data)
        passed, res = verify_preflight_bundle(zip_path)
        self.assertFalse(passed)
        self.assertIn("Expected implementation=INDEPENDENT", res["errors"][0])


if __name__ == "__main__":
    unittest.main()
