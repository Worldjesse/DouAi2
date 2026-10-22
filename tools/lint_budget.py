"""Gate code warnings while reporting network-dependent version advice separately."""
from __future__ import annotations

import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


VERSION_NOTICE_IDS = frozenset({"GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion"})
VERSION_NOTICE_MESSAGE = re.compile(r"A newer version of .+ than .+ is available: .+")


class BudgetError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise BudgetError(message)


def normalized_file(value, root):
    if not value:
        return "(project)"
    value = value.replace("\\", "/")
    base = str(root.resolve()).replace("\\", "/").rstrip("/")
    if value.casefold().startswith((base + "/").casefold()):
        value = value[len(base) + 1:]
    else:
        require(not value.startswith("/") and not re.match(r"^[A-Za-z]:", value),
                f"Lint location is outside project: {value}")
    while value.startswith("./"):
        value = value[2:]
    require(value and ".." not in value.split("/"), "Invalid relative Lint location")
    return value


def normalized_message(value, root):
    value = value.replace(str(root.resolve()), "<project>")
    value = value.replace(str(root.resolve()).replace("\\", "/"), "<project>")
    return re.sub(r"\s+", " ", value).strip()


def lint_warnings(report, root):
    require(report.is_file(), f"Missing Lint report: {report}")
    document = ET.parse(report).getroot()
    require(document.tag == "issues", "Expected Android Lint issues XML")
    warnings = Counter()
    errors = []
    for issue in document.findall("issue"):
        severity = issue.get("severity", "")
        if severity in ("Error", "Fatal"):
            errors.append(issue.get("id", "unknown"))
        if severity != "Warning":
            continue
        location = issue.find("location")
        key = (issue.get("id", ""), normalized_file(location.get("file", "") if location is not None else "", root),
               normalized_message(issue.get("message", ""), root))
        require(key[0] and key[2], "Lint warning lacks an issue ID or message")
        warnings[key] += 1
    return warnings, errors


def baseline_entries(path):
    require(path.is_file(), f"Missing reviewed Lint baseline: {path}")
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    require(data.get("schemaVersion") == 1, "Unsupported Lint baseline schema")
    counts = Counter()
    for entry in data.get("warnings", []):
        key = (entry.get("id", ""), entry.get("file", ""), entry.get("message", ""))
        require(all(key) and key not in counts, "Missing or duplicate Lint baseline key")
        require(isinstance(entry.get("count"), int) and not isinstance(entry["count"], bool) and entry["count"] > 0,
                "Lint baseline count must be a positive integer")
        require(isinstance(entry.get("reason"), str) and entry["reason"].strip(),
                "Each Lint baseline entry requires a review reason")
        counts[key] = entry["count"]
    return counts


def entry(key, count):
    return dict(id=key[0], file=key[1], message=key[2], count=count)


def is_version_notice(key):
    # Match both identity and wording, not every diagnostic from these issue IDs.
    # Error/Fatal entries are collected separately before Warning classification.
    return key[0] in VERSION_NOTICE_IDS and VERSION_NOTICE_MESSAGE.fullmatch(key[2]) is not None


def evaluate(report, root, baseline):
    actual, errors = lint_warnings(report, root)
    approved = baseline_entries(baseline)
    budget_actual = Counter({key: count for key, count in actual.items() if not is_version_notice(key)})
    budget_approved = Counter({key: count for key, count in approved.items() if not is_version_notice(key)})
    advisories = [entry(key, count) for key, count in sorted(actual.items()) if is_version_notice(key)]
    added = [entry(key, count - budget_approved[key]) for key, count in sorted(budget_actual.items())
             if count > budget_approved[key]]
    reduced = [entry(key, count - budget_actual[key]) for key, count in sorted(budget_approved.items())
               if count > budget_actual[key]]
    return {"schemaVersion": 1, "passed": not errors and not added,
            "warnings": sum(actual.values()), "approvedWarnings": sum(approved.values()),
            "budgetWarnings": sum(budget_actual.values()), "approvedBudgetWarnings": sum(budget_approved.values()),
            "advisoryWarnings": advisories, "errors": errors, "newWarnings": added, "removedWarnings": reduced}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def update(report, root, baseline, reason):
    require(reason.strip(), "Updating the Lint baseline requires --reason")
    actual, errors = lint_warnings(report, root)
    require(not errors, "Cannot approve a baseline containing Lint errors")
    previous = {}
    if baseline.is_file():
        baseline_entries(baseline)  # Do not silently replace malformed review data.
        data = json.loads(baseline.read_text(encoding="utf-8-sig"))
        previous = {(item["id"], item["file"], item["message"]): item for item in data["warnings"]}
    rows = []
    for key, count in sorted(actual.items()):
        old = previous.get(key)
        approved_reason = old["reason"] if old and count <= old["count"] else reason.strip()
        rows.append({**entry(key, count), "reason": approved_reason})
    write_json(baseline, {"schemaVersion": 1, "key": ["id", "file", "message"], "warnings": rows})


def summary(result):
    state = "PASSED" if result["passed"] else "FAILED"
    budget_count = result.get("budgetWarnings", result["warnings"])
    lines = ["## Lint warning budget", "", f"**{state}**: {result['warnings']} total warnings; "
             f"{budget_count} budgeted warnings; "
             f"{sum(i['count'] for i in result['newWarnings'])} unreviewed additions; {len(result['errors'])} errors.", ""]
    for item in result["newWarnings"]:
        lines.append(f"- +{item['count']} `{item['id']}` in `{item['file']}`: {item['message']}")
    if result["removedWarnings"]:
        lines.append(f"Warning reductions accepted: {sum(i['count'] for i in result['removedWarnings'])}.")
    advisories = result.get("advisoryWarnings", [])
    if advisories:
        lines.extend(["", "### Dependency and toolchain update notices", "",
                      f"{sum(i['count'] for i in advisories)} advisory warnings (non-blocking):", ""])
        for item in advisories:
            lines.append(f"- {item['count']} `{item['id']}` in `{item['file']}`: {item['message']}")
    lines.extend(["", "The budget ignores line numbers. New budgeted warning keys or increased counts require an explicit baseline update with a review reason.",
                  "Standard dependency/toolchain upgrade notices remain visible advice because repository metadata can change independently of the checked-out source. Errors and unrecognized diagnostics still fail.", ""])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "update"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, default=Path(__file__).resolve().with_name("lint-baseline.json"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--reason", default="")
    args = parser.parse_args()
    try:
        if args.command == "update":
            update(args.report, args.root, args.baseline, args.reason)
            print(f"Updated reviewed warning inventory: {args.baseline}")
            return 0
        result = evaluate(args.report, args.root, args.baseline)
        report_summary = summary(result)
        print(report_summary)
        if args.output:
            write_json(args.output, result)
            args.output.with_suffix(".md").write_text(report_summary, encoding="utf-8")
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
                output.write(report_summary)
        return 0 if result["passed"] else 1
    except (BudgetError, OSError, ValueError, ET.ParseError) as error:
        print(f"Lint budget failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
