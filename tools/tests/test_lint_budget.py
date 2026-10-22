"""Lint budget protects identities and duplicate counts while tolerating line movement."""
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import lint_budget


class LintBudgetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.report = self.root / "lint.xml"
        self.baseline = self.root / "baseline.json"
        self.warning = dict(id="DrawAllocation", file="app/src/main/Chart.java", message="Avoid allocation during draw")
        self.report_warnings([self.warning])
        lint_budget.update(self.report, self.root, self.baseline, "Existing allocation; optimize before next rendering change")

    def report_warnings(self, warnings):
        root = ET.Element("issues")
        for warning in warnings:
            item = ET.SubElement(root, "issue", id=warning["id"], severity=warning.get("severity", "Warning"),
                                 message=warning["message"])
            ET.SubElement(item, "location", file=warning.get("absolute", str(self.root / warning["file"])),
                          line=str(warning.get("line", 10)))
        self.report.write_text(ET.tostring(root, encoding="unicode"), encoding="utf-8")

    def evaluate(self):
        return lint_budget.evaluate(self.report, self.root, self.baseline)

    def test_line_number_and_slash_changes_do_not_create_warning(self):
        self.report_warnings([{**self.warning, "line": 900, "absolute": str(self.root / self.warning["file"]).replace("/", "\\")}])
        self.assertTrue(self.evaluate()["passed"])

    def test_same_warning_identity_increased_count_requires_review(self):
        self.report_warnings([self.warning, {**self.warning, "line": 99}])
        result = self.evaluate()
        self.assertFalse(result["passed"])
        self.assertEqual(1, result["newWarnings"][0]["count"])

    def test_warning_reduction_passes_without_baseline_refresh(self):
        self.report_warnings([])
        result = self.evaluate()
        self.assertTrue(result["passed"])
        self.assertEqual(1, result["removedWarnings"][0]["count"])

    def test_same_total_does_not_hide_different_issue_file_or_message(self):
        for change in ({"id": "DifferentIssue"}, {"file": "app/src/main/Other.java"}, {"message": "Different message"}):
            with self.subTest(change=change):
                self.report_warnings([{**self.warning, **change}])
                result = self.evaluate()
                self.assertFalse(result["passed"])
                self.assertEqual(1, result["warnings"])

    def test_message_whitespace_is_normalized(self):
        self.report_warnings([{**self.warning, "message": " Avoid\n  allocation during\t draw "}])
        self.assertTrue(self.evaluate()["passed"])

    def test_dependency_updates_are_visible_advisories_not_code_additions(self):
        notice = dict(id="GradleDependency", file="app/build.gradle.kts",
                      message="A newer version of androidx.test:runner than 1.5.2 is available: 1.7.0")
        self.report_warnings([self.warning, notice, notice])
        result = self.evaluate()
        self.assertTrue(result["passed"])
        self.assertEqual(3, result["warnings"])
        self.assertEqual(1, result["budgetWarnings"])
        self.assertEqual([], result["newWarnings"])
        self.assertEqual([{**notice, "count": 2}], result["advisoryWarnings"])
        self.assertIn(notice["message"], lint_budget.summary(result))

    def test_latest_version_drift_for_upgrade_notices_does_not_change_budget(self):
        for issue in ("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion"):
            for version in ("1.7.0", "99.0.0"):
                with self.subTest(issue=issue, version=version):
                    notice = dict(id=issue, file="app/build.gradle.kts",
                                  message=f"A newer version of sample:dependency than 1.0 is available: {version}")
                    self.report_warnings([self.warning, notice])
                    self.assertTrue(self.evaluate()["passed"])

    def test_unrecognized_issue_or_message_is_still_gated(self):
        for notice in (
                dict(id="OtherIssue", file="app/build.gradle.kts",
                     message="A newer version of sample:dependency than 1.0 is available: 2.0"),
                dict(id="GradleDependency", file="app/build.gradle.kts", message="Unrecognized dependency problem")):
            with self.subTest(notice=notice):
                self.report_warnings([self.warning, notice])
                self.assertFalse(self.evaluate()["passed"])

    def test_real_warning_addition_is_not_hidden_by_update_notices(self):
        notice = dict(id="GradleDependency", file="app/build.gradle.kts",
                      message="A newer version of sample:dependency than 1.0 is available: 2.0")
        self.report_warnings([self.warning, self.warning, notice])
        result = self.evaluate()
        self.assertFalse(result["passed"])
        self.assertEqual([{**self.warning, "count": 1}], result["newWarnings"])

    def test_missing_update_notice_is_not_reported_as_code_reduction(self):
        notice = dict(id="NewerVersionAvailable", file="app/build.gradle.kts",
                      message="A newer version of sample:dependency than 1.0 is available: 2.0")
        self.report_warnings([self.warning, notice])
        lint_budget.update(self.report, self.root, self.baseline, "Recorded update advice")
        self.report_warnings([self.warning])
        result = self.evaluate()
        self.assertTrue(result["passed"])
        self.assertEqual([], result["removedWarnings"])

    def test_upgrade_issue_with_error_severity_still_fails(self):
        for severity in ("Error", "Fatal"):
            with self.subTest(severity=severity):
                self.report_warnings([self.warning, dict(id="GradleDependency", file="app/build.gradle.kts",
                    message="A newer version of sample:dependency than 1.0 is available: 2.0", severity=severity)])
                result = self.evaluate()
                self.assertFalse(result["passed"])
                self.assertEqual(["GradleDependency"], result["errors"])

    def test_review_reason_is_required_and_persists(self):
        with self.assertRaisesRegex(lint_budget.BudgetError, "requires --reason"):
            lint_budget.update(self.report, self.root, self.baseline, "  ")
        self.report_warnings([self.warning, self.warning])
        lint_budget.update(self.report, self.root, self.baseline, "Accepted bounded duplicate while chart is visible")
        self.assertTrue(self.evaluate()["passed"])
        self.assertIn("Accepted bounded duplicate", self.baseline.read_text())

    def test_lint_errors_cannot_be_approved(self):
        self.report_warnings([{**self.warning, "severity": "Error"}])
        self.assertFalse(self.evaluate()["passed"])
        with self.assertRaisesRegex(lint_budget.BudgetError, "containing Lint errors"):
            lint_budget.update(self.report, self.root, self.baseline, "not allowed")

    def test_missing_baseline_fails(self):
        self.baseline.unlink()
        with self.assertRaisesRegex(lint_budget.BudgetError, "Missing reviewed"):
            self.evaluate()


if __name__ == "__main__":
    unittest.main()
