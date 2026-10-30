import tempfile
import unittest
from pathlib import Path
from summarize_architecture_validation import MODULES, summarize

class ValidationSummaryTest(unittest.TestCase):
    def reports(self, root, case="<testcase classname='A' name='one'/>"):
        for module in MODULES.values():
            folder=root/module/"build/test-results/testDebugUnitTest"
            folder.mkdir(parents=True)
            (folder/"TEST-one.xml").write_text("<testsuite>"+case+"</testsuite>")
    def test_all_modules_and_successful_command_are_required(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name);self.reports(root)
            self.assertTrue(summarize(root,0)["passed"])
            self.assertFalse(summarize(root,1)["passed"])
            self.assertFalse(summarize(root,None)["passed"])
    def test_missing_module_is_never_success(self):
        with tempfile.TemporaryDirectory() as name:
            result=summarize(Path(name),0)
            self.assertFalse(result["passed"])
            self.assertEqual(["missing_reports"],result["modules"]["common"]["problems"])
    def test_counts_failures_errors_and_skips_from_real_cases(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name)
            self.reports(root,"<testcase name='failure'><failure/></testcase><testcase name='error'><error/></testcase><testcase name='skip'><skipped/></testcase>")
            result=summarize(root,0);row=result["modules"]["common"]
            self.assertFalse(result["passed"])
            self.assertEqual((3,1,1,1),(row["tests"],row["failures"],row["errors"],row["skipped"]))
    def test_malformed_or_empty_report_cannot_pass(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name);self.reports(root,"")
            self.assertFalse(summarize(root,0)["passed"])
            (root/"common/build/test-results/testDebugUnitTest/TEST-one.xml").write_text("<broken")
            self.assertTrue(summarize(root,0)["modules"]["common"]["problems"])

if __name__=="__main__":unittest.main()
