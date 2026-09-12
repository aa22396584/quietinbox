import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCENARIO_IDS = ("T001", "T004", "T016", "T017", "T045")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def markdown_row(document: str, scenario_id: str) -> str:
    match = re.search(
        rf"^\|\s*`?{re.escape(scenario_id)}`?\s*\|.*$",
        document,
        flags=re.MULTILINE,
    )
    if match is None:
        raise AssertionError(f"missing Markdown row for {scenario_id}")
    return match.group(0)


def issue_form_item(document: str, field_id: str) -> tuple[str, str]:
    match = re.search(
        rf"(?ms)^  - type: (?P<type>[^\n]+)\n    id: {re.escape(field_id)}\n"
        r"(?P<body>.*?)(?=^  - type:|\Z)",
        document,
    )
    if match is None:
        raise AssertionError(f"missing issue-form item {field_id}")
    return match.group("type"), match.group("body")


class CompatibilityDocumentationContractTest(unittest.TestCase):
    def test_real_source_scenarios_are_defined_as_unrun_protocols(self) -> None:
        documents = {
            "docs/TEST_MATRIX.md": ("protocol", "not execution evidence", "must not mark"),
            "docs/zh-Hant/TEST_MATRIX.md": ("流程", "不是執行證據", "不得"),
        }

        for path, required_phrases in documents.items():
            with self.subTest(path=path):
                document = read(path)
                for phrase in required_phrases:
                    self.assertIn(phrase, document)
                for scenario_id in SCENARIO_IDS:
                    row = markdown_row(document, scenario_id)
                    self.assertRegex(row, r"\b(?:UNKNOWN|UNVERIFIED)\b")
                    self.assertTrue(
                        any(token in row for token in ("must not mark", "不得標記")),
                        f"{scenario_id} must include the read-only source check",
                    )

    def test_scenario_definitions_cover_the_required_cases(self) -> None:
        documents = {
            "docs/TEST_MATRIX.md": {
                "T001": ("direct message",),
                "T004": ("sticker", "media"),
                "T016": ("group", "stacked"),
                "T017": ("identical", "repeated"),
                "T045": ("preview off",),
            },
            "docs/zh-Hant/TEST_MATRIX.md": {
                "T001": ("私訊",),
                "T004": ("貼圖", "媒體"),
                "T016": ("群組", "堆疊"),
                "T017": ("相同", "重複"),
                "T045": ("關閉預覽",),
            },
        }
        for path, required_terms in documents.items():
            document = read(path)
            for scenario_id, terms in required_terms.items():
                with self.subTest(path=path, scenario_id=scenario_id):
                    row = markdown_row(document, scenario_id).lower()
                    for term in terms:
                        self.assertIn(term, row)

        english = read("docs/TEST_MATRIX.md")
        self.assertIn(
            "other consenting test account",
            markdown_row(english, "T016").lower(),
        )
        self.assertIn(
            "only consenting synthetic test accounts",
            markdown_row(english, "T016").lower(),
        )
        self.assertNotIn("from two test members", markdown_row(english, "T016").lower())
        self.assertIn(
            "其他知情同意的合成測試帳號",
            markdown_row(read("docs/zh-Hant/TEST_MATRIX.md"), "T016"),
        )

    def test_compatibility_docs_contain_scenario_evidence_record_template(self) -> None:
        expected_headers = {
            "docs/COMPATIBILITY.md": (
                "Source",
                "Package",
                "Source versionCode",
                "Adapter / version",
                "QuietInbox commit",
                "Android / OEM / device",
                "System language",
                "Scenario",
                "Expected outcome",
                "Observed outcome",
                "Read-state verification",
                "Status",
                "Result / evidence",
            ),
            "docs/zh-Hant/COMPATIBILITY.md": (
                "來源",
                "Package",
                "來源 versionCode",
                "Adapter／版本",
                "QuietInbox commit",
                "Android／OEM／裝置",
                "系統語言",
                "情境",
                "預期結果",
                "觀察結果",
                "已讀狀態驗證",
                "狀態",
                "結果／證據",
            ),
        }

        for path, headers in expected_headers.items():
            with self.subTest(path=path):
                document = read(path)
                self.assertIn("SYNTHETIC_ONLY", document)
                self.assertIn("afa7818", document)
                self.assertTrue(
                    any(
                        line.startswith("|")
                        and all(f" {header} " in line for header in headers)
                        for line in document.splitlines()
                    ),
                    "missing complete scenario evidence table header",
                )

    def test_issue_form_collects_scenario_granular_evidence(self) -> None:
        issue_form = read(".github/ISSUE_TEMPLATE/compatibility_report.yml")
        required_field_ids = (
            "source",
            "source_version",
            "adapter",
            "device",
            "language",
            "quietinbox",
            "scenario",
            "shape",
            "expected",
            "observed",
            "result",
            "readstate",
            "evidence",
        )
        for field_id in required_field_ids:
            _, field_body = issue_form_item(issue_form, field_id)
            self.assertIn(
                "validations:\n      required: true",
                field_body,
                f"{field_id} must be required",
            )
        issue_form_item(issue_form, "privacy")
        for scenario_id in SCENARIO_IDS:
            self.assertIn(scenario_id, issue_form)
        self.assertRegex(issue_form, r"(?i)no real (?:names|chat|conversation)")

        readstate_type, readstate_body = issue_form_item(issue_form, "readstate")
        self.assertEqual("dropdown", readstate_type)
        for outcome in ("PASS", "FAIL", "UNKNOWN"):
            self.assertRegex(readstate_body, rf"(?m)^        - {outcome} \u2014")
        self.assertIn("validations:\n      required: true", readstate_body)
        self.assertNotIn("I confirmed", readstate_body)

        privacy_type, privacy_body = issue_form_item(issue_form, "privacy")
        self.assertEqual("checkboxes", privacy_type)
        self.assertIn("required: true", privacy_body)

    def test_promotion_is_scoped_to_one_exact_configuration(self) -> None:
        english = read("docs/COMPATIBILITY.md")
        self.assertIn("read-state check is `PASS`", english)
        self.assertIn(
            "same exact QuietInbox commit, adapter/version, source versionCode,",
            english,
        )
        self.assertIn("not a global\n   source status", english)

        traditional_chinese = read("docs/zh-Hant/COMPATIBILITY.md")
        self.assertIn("已讀狀態為 `PASS`", traditional_chinese)
        self.assertIn("完全相同的\n   QuietInbox commit", traditional_chinese)
        self.assertIn("不代表來源 App 全域相容", traditional_chinese)

    def test_ci_runs_documentation_contracts(self) -> None:
        workflow = read(".github/workflows/ci.yml")
        self.assertIn("name: Documentation contracts", workflow)
        self.assertIn(
            "python3 -m unittest discover -s tools/tests -p 'test_*.py'",
            workflow,
        )


if __name__ == "__main__":
    unittest.main()
