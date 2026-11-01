import tempfile
import unittest
from pathlib import Path
import sys

# Add scripts directory to path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from check_video_runtime_boundary import scan_file, PROHIBITED_SYMBOLS

class VideoRuntimeBoundaryTest(unittest.TestCase):

    def test_clean_file_passes(self):
        with tempfile.NamedTemporaryFile("w+", suffix=".kt", delete=False) as f:
            f.write("package com.shilapi.xcertplay.media.video\nclass CleanClass {}\n")
            f.flush()
            violations = scan_file(Path(f.name))
            self.assertEqual([], violations)

    def test_prohibited_import_detected(self):
        with tempfile.NamedTemporaryFile("w+", suffix=".kt", delete=False) as f:
            f.write("package com.shilapi.xcertplay.media.video\nimport com.shilapi.xcertplay.media.AndroidVideoPipeline\n")
            f.flush()
            violations = scan_file(Path(f.name))
            self.assertEqual(1, len(violations))
            self.assertEqual("AndroidVideoPipeline", violations[0][2])

    def test_commented_prohibited_symbol_ignored(self):
        with tempfile.NamedTemporaryFile("w+", suffix=".kt", delete=False) as f:
            f.write("// Prohibited: AndroidVideoPipeline must not be used\n")
            f.flush()
            violations = scan_file(Path(f.name))
            self.assertEqual([], violations)

if __name__ == "__main__":
    unittest.main()
