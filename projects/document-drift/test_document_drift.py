import json
from pathlib import Path
import subprocess
import sys
import unittest

from document_drift import compare, parse


ROOT = Path(__file__).resolve().parent


class DocumentDriftTest(unittest.TestCase):
    def test_example_via_cli(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / "document_drift.py"),
             str(ROOT / "examples" / "before.md"), str(ROOT / "examples" / "after.md"), "--json"],
            check=True, capture_output=True, text=True, encoding="utf-8",
        )
        report = json.loads(result.stdout)
        changes = {change["kinds"][0]: change for change in report["changes"]}
        self.assertEqual(set(changes), {"modified", "moved", "removed", "added"})
        self.assertEqual(changes["moved"]["before"]["line"], 21)
        self.assertEqual(changes["moved"]["after"]["line"], 17)
        self.assertEqual(changes["moved"]["edits"], [])
        edit = changes["modified"]["edits"][0]
        self.assertEqual(edit["before"], [{"line": 5, "text": "Установите начальное значение 12."}])
        self.assertEqual(edit["after"], [{"line": 5, "text": "Установите начальное значение 15."}])
        self.assertEqual(report["ambiguous"], [])

    def test_identical_titles_matched_by_content(self):
        report = compare(
            "# Steps\n## Check\nalpha\n## Check\nbeta",
            "# Steps\n## Check\nbeta\n## Check\nalpha",
        )
        self.assertEqual([c["kinds"] for c in report["changes"]], [["moved"]])
        self.assertEqual(report["ambiguous"], [])

    def test_duplicate_titles_with_no_unique_match(self):
        report = compare("# Check\nA\n# Check\nB", "# Check\nC\n# Check\nD")
        self.assertEqual(report["changes"], [])
        self.assertEqual([item["line"] for item in report["ambiguous"][0]["before"]], [1, 3])
        self.assertEqual([item["line"] for item in report["ambiguous"][0]["after"]], [1, 3])

    def test_shifted_lines_are_not_moves(self):
        report = compare("# A\none\n# B\ntwo", "# A\none\n# New\nthree\n# B\ntwo")
        self.assertEqual([item["kinds"] for item in report["changes"]], [["added"]])

    def test_renamed_parent_keeps_unchanged_children(self):
        report = compare("# Old\nsummary\n## Child\nitem", "# New\nsummary\n## Child\nitem")
        self.assertEqual([item["kinds"] for item in report["changes"]], [["renamed"]])

    def test_fence_and_preamble(self):
        old = "Intro\r\n\r\n" + chr(96) * 4 + "\r\n" + chr(96) * 3 + "\r\n# fenced\r\n"
        old += chr(96) * 4 + "\r\n# Header ###\r\nbody"
        self.assertEqual([(section.title, section.line) for section in parse(old)],
                         [("(preamble)", 1), ("Header", 7)])
        report = compare(old, old.replace("fenced", "inside"))
        self.assertEqual(report["changes"][0]["edits"][0]["before"][0]["line"], 5)

    def test_empty_and_same_document(self):
        self.assertEqual(compare("", ""), {"changes": [], "ambiguous": []})
        self.assertEqual(compare("# H\nabc\n", "# H\nabc\n"), compare("", ""))


if __name__ == "__main__":
    unittest.main()
