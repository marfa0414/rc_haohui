#!/usr/bin/env python3
"""Archive sanitized, per-test results and source hashes after a successful clean verify.

Does not execute tests or mark any acceptance case complete. Raw XML may contain
environment properties and logs; only test identities, outcomes and hashes are saved.
"""

import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint")
    parser.add_argument("--scope", required=True)
    args = parser.parse_args()
    if not args.checkpoint.startswith("CP") or not args.checkpoint[2:].isdigit():
        parser.error("checkpoint must have the form CP03")
    out = Path("docs/reports") / args.checkpoint
    if (out / "results.json").exists():
        parser.error("evidence already exists; do not overwrite a completed checkpoint")
    suites = []
    for path in sorted(Path("target/surefire-reports").glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        counts = {key: int(root.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        cases = []
        for case in root.findall("testcase"):
            status = next((label for tag, label in (("failure", "FAIL"), ("error", "ERROR"),
                          ("skipped", "SKIP")) if case.find(tag) is not None), "PASS")
            cases.append({"name": case.get("name"), "class": case.get("classname"),
                          "seconds": case.get("time"), "status": status})
        if not cases or len(cases) != counts["tests"] or any(c["status"] != "PASS" for c in cases):
            raise SystemExit(f"FAIL: Missing or unsuccessful actual testcase nodes in {path}")
        if any(counts[key] for key in ("failures", "errors", "skipped")):
            raise SystemExit(f"FAIL: Suite reports failures/errors/skips: {path}")
        suites.append({"suite": root.get("name"), **counts, "cases": cases,
                       "rawXmlSha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    if not suites:
        raise SystemExit("FAIL: No test reports; run clean verify first")
    totals = {key: sum(s[key] for s in suites) for key in ("tests", "failures", "errors", "skipped")}
    paths = [p for d in ("src", ".mvn", ".github", "scripts", "examples")
             for p in Path(d).rglob("*") if p.is_file() and "__pycache__" not in p.parts]
    paths += [Path(p) for p in ("pom.xml", "mvnw", "mvnw.cmd", ".env.example", ".gitignore",
                               "Dockerfile", "compose.yaml") if Path(p).is_file()]
    out.mkdir(parents=True, exist_ok=True)
    (out / "results.json").write_text(json.dumps({"checkpoint": args.checkpoint,
        "suiteScope": args.scope, "suites": suites, "totals": totals}, ensure_ascii=False, indent=2) + "\n")
    (out / "source-sha256.txt").write_text("".join(
        f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p}\n" for p in sorted(set(paths))))
    print(json.dumps({"directory": str(out), "totals": totals}))


if __name__ == "__main__":
    main()
