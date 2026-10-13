#!/usr/bin/env python3
"""Fail a checkpoint if fresh Surefire reports are missing, failing, or skipped.

Run Maven clean verify immediately before this script so reports match current sources.
"""

import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_directory", nargs="?", default="target/surefire-reports")
    args = parser.parse_args()
    report_directory = Path(args.report_directory)
    reports = sorted(report_directory.glob("TEST-*.xml"))
    if not reports:
        print(f"FAIL: No Surefire XML reports found in {report_directory}", file=sys.stderr)
        return 1

    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    details = []
    try:
        for report in reports:
            suite = ET.parse(report).getroot()
            counts = {key: int(suite.attrib.get(key, "0")) for key in totals}
            if counts["tests"] == 0:
                raise ValueError(f"Empty test suite: {report}")
            for key in totals:
                totals[key] += counts[key]
            details.append({"suite": suite.attrib.get("name", report.name), **counts})
    except (ET.ParseError, ValueError, OSError) as error:
        print(f"FAIL: Invalid test evidence: {error}", file=sys.stderr)
        return 1

    passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    result = {"suites": details, "totals": {**totals, "passed": passed}}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if totals["tests"] == 0 or any(totals[key] for key in ("failures", "errors", "skipped")):
        print("FAIL: Checkpoint requires executed tests with zero failures, errors, and skips.", file=sys.stderr)
        return 1
    print("PASS: All reported tests executed without failures, errors, or skips.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
