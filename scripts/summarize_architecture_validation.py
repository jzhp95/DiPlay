#!/usr/bin/env python3
"""Report actual JUnit results; missing/invalid reports and command failures stay failures."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

MODULES = {"flyme-eas": "integrations/flyme-eas", "shared": "shared", "common": "common"}

def summarize(root, command_exit):
    modules = {}
    for name, directory in MODULES.items():
        reports = sorted((root / directory / "build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
        result = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "reports": [], "problems": []}
        if not reports:
            result["problems"].append("missing_reports")
        for report in reports:
            result["reports"].append(str(report.relative_to(root)))
            try:
                tree = ET.parse(report)
                cases = list(tree.iter("testcase"))
                if not cases:
                    raise ValueError("no_testcases")
                for case in cases:
                    result["tests"] += 1
                    for tag, field in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
                        if case.find(tag) is not None:
                            result[field] += 1
            except (OSError, ET.ParseError, ValueError) as error:
                result["problems"].append(report.name + ":" + str(error))
        result["passed"] = not result["problems"] and not result["failures"] and not result["errors"]
        modules[name] = result
    return {"schemaVersion": 1, "commandExit": command_exit, "modules": modules,
            "passed": command_exit == 0 and all(row["passed"] for row in modules.values()),
            "scope": "local_software_checks_only; vehicle_validation_not_performed"}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--command-exit-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        command_exit = int(args.command_exit_file.read_text().strip())
    except (OSError, ValueError):
        command_exit = None
    result = summarize(args.root, command_exit)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 1

if __name__ == "__main__":
    raise SystemExit(main())
