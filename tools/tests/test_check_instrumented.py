#!/usr/bin/env python3
"""Regression tests for tools/check-instrumented.sh (and check-instrumented.py).

Verifies that the instrumented test gate fails closed against:
- missing reports
- unexecuted/zero tests
- skipped tests
- failures/errors (both declared and hidden in testcase children)
- malformed/empty XML
- count vs child structure contradictions
- zero module arguments and empty/whitespace module arguments
- one good module masking one bad module
- one good report masking an unexecuted (zero tests) report in the same module
- nested XML without double-counting
- cross-cwd invocations
- XML namespaces (e.g. xmlns="...")
- negative counter attributes
- ignored tags and status attributes
- UTF-8 with BOM encodings
"""

import os
import pathlib
import subprocess
import tempfile
import unittest

GOOD = '<testsuite name="Good" tests="1" failures="0" errors="0" skipped="0"><testcase name="works" classname="Good"/></testsuite>\n'
FAILED = '<testsuite name="Bad" tests="1" failures="1" errors="0" skipped="0"><testcase name="fails" classname="Bad"><failure message="synthetic failure"/></testcase></testsuite>\n'
ERROR = '<testsuite name="Bad" tests="1" failures="0" errors="1" skipped="0"><testcase name="errors" classname="Bad"><error message="synthetic error"/></testcase></testsuite>\n'
ZERO = '<testsuite name="Empty" tests="0" failures="0" errors="0" skipped="0"/>\n'
SKIPPED = '<testsuite name="Skipped" tests="1" failures="0" errors="0" skipped="1"><testcase name="notRun" classname="Skipped"><skipped/></testcase></testsuite>\n'
HIDDEN_FAILURE = '<testsuite tests="1"><testcase name="bad"><failure message="synthetic failure"/></testcase></testsuite>\n'
MALFORMED = '<testsuite tests="1" failures="0" errors="0"><testcase\n'

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT_PATH = REPO_ROOT / "tools" / "check-instrumented.sh"


class TestCheckInstrumentedCLI(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT_PATH.exists(), f"Script not found at {SCRIPT_PATH}")

    def _run_cli(self, *modules: str, cwd: pathlib.Path | None = None) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", str(SCRIPT_PATH), *modules],
            capture_output=True,
            text=True,
            cwd=str(cwd) if cwd else None,
            timeout=15,
        )

    def _create_module_with_xml(
        self, base_dir: pathlib.Path, module_name: str, xml_content: str | bytes | None, filename: str = "TEST-case.xml"
    ) -> pathlib.Path:
        module_path = base_dir / module_name
        if xml_content is not None:
            results_dir = module_path / "build" / "outputs" / "androidTest-results" / "connected" / "device"
            results_dir.mkdir(parents=True, exist_ok=True)
            target = results_dir / filename
            if isinstance(xml_content, bytes):
                target.write_bytes(xml_content)
            else:
                target.write_text(xml_content, encoding="utf-8")
        else:
            module_path.mkdir(parents=True, exist_ok=True)
        return module_path

    # --- The 10 fixtures from #37 reproduce harness ---

    def test_01_valid_executed_test_passes(self):
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", GOOD)
            p = self._run_cli(str(mod))
            self.assertEqual(p.returncode, 0, f"Expected 0, got {p.returncode}. Stderr: {p.stderr}")
            self.assertIn("OK: ", p.stdout)
            self.assertIn("1 instrumented tests", p.stdout)

    def test_02_reported_failure_fails(self):
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", FAILED)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Reported failure must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_03_reported_error_fails(self):
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", ERROR)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Reported error must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_04_missing_report_fails(self):
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", None)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Missing report must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_05_zero_tests_fails(self):
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", ZERO)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Zero tests must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_06_all_skipped_fails(self):
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", SKIPPED)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "All skipped tests must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_07_failure_child_missing_counters_fails(self):
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", HIDDEN_FAILURE)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Hidden failure child must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_08_malformed_xml_fails(self):
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", MALFORMED)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Malformed XML must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_09_zero_modules_fails(self):
        p = self._run_cli()
        self.assertNotEqual(p.returncode, 0, "CLI with zero module arguments must exit non-zero")
        self.assertIn("FAIL: ", p.stderr)

    def test_10_one_valid_one_all_skipped_fails(self):
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod0 = self._create_module_with_xml(pathlib.Path(tmp), "mod0", GOOD)
            mod1 = self._create_module_with_xml(pathlib.Path(tmp), "mod1", SKIPPED)
            p = self._run_cli(str(mod0), str(mod1))
            self.assertNotEqual(p.returncode, 0, "Good module + all-skipped module must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    # --- Additional contract tests: cross-cwd, nested XML, count mismatches ---

    def test_11_cross_cwd_invocation(self):
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            root = pathlib.Path(tmp)
            mod = self._create_module_with_xml(root, "my_module", GOOD)
            other_dir = root / "other_subdir"
            other_dir.mkdir()
            rel_path = os.path.relpath(mod, other_dir)
            p = self._run_cli(rel_path, cwd=other_dir)
            self.assertEqual(p.returncode, 0, f"Expected 0 from different cwd, got {p.returncode}. Stderr: {p.stderr}")
            self.assertIn("OK: ", p.stdout)

    def test_12_nested_xml_valid_no_double_count(self):
        nested = (
            '<testsuites tests="2" failures="0" errors="0" skipped="0">\n'
            '  <testsuite name="SuiteA" tests="1" failures="0" errors="0" skipped="0">\n'
            '    <testcase name="testA" classname="SuiteA"/>\n'
            '  </testsuite>\n'
            '  <testsuite name="SuiteB" tests="1" failures="0" errors="0" skipped="0">\n'
            '    <testcase name="testB" classname="SuiteB"/>\n'
            '  </testsuite>\n'
            '</testsuites>\n'
        )
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", nested)
            p = self._run_cli(str(mod))
            self.assertEqual(p.returncode, 0, f"Expected 0, got {p.returncode}. Stderr: {p.stderr}")
            self.assertIn("OK: ", p.stdout)
            self.assertIn("2 instrumented tests", p.stdout)
            self.assertNotIn("4 instrumented tests", p.stdout)

    def test_13_nested_xml_with_failure_in_subsuite_fails(self):
        nested_fail = (
            '<testsuites tests="2" failures="1" errors="0" skipped="0">\n'
            '  <testsuite name="SuiteA" tests="1" failures="0" errors="0" skipped="0">\n'
            '    <testcase name="testA" classname="SuiteA"/>\n'
            '  </testsuite>\n'
            '  <testsuite name="SuiteB" tests="1" failures="1" errors="0" skipped="0">\n'
            '    <testcase name="testB" classname="SuiteB">\n'
            '      <failure message="bad"/>\n'
            '    </testcase>\n'
            '  </testsuite>\n'
            '</testsuites>\n'
        )
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", nested_fail)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Failure in nested subsuite must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_14_count_mismatch_tests_attr_greater_than_children_fails(self):
        xml = '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase name="c1" classname="s"/></testsuite>\n'
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "tests attribute > children must fail")
            self.assertIn("FAIL: ", p.stderr)

    def test_15_count_mismatch_tests_attr_less_than_children_fails(self):
        xml = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="c1" classname="s"/><testcase name="c2" classname="s"/></testsuite>\n'
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "tests attribute < children must fail")
            self.assertIn("FAIL: ", p.stderr)

    def test_16_count_mismatch_hidden_error_fails(self):
        xml = '<testsuite tests="1"><testcase name="c1" classname="s"><error message="boom"/></testcase></testsuite>\n'
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Hidden error child must fail")
            self.assertIn("FAIL: ", p.stderr)

    def test_17_count_mismatch_attribute_claims_failure_without_child_fails(self):
        xml = '<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase name="c1" classname="s"/></testsuite>\n'
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Contradictory failures counter must fail")
            self.assertIn("FAIL: ", p.stderr)

    def test_18_count_mismatch_attribute_claims_zero_but_has_failure_child_fails(self):
        xml = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="c1" classname="s"><failure message="fail"/></testcase></testsuite>\n'
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Zero failures attribute with failure child must fail")
            self.assertIn("FAIL: ", p.stderr)

    def test_19_unsupported_root_element_fails(self):
        xml = '<notatestsuite tests="1"><testcase name="c1"/></notatestsuite>\n'
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Unsupported XML root must fail")
            self.assertIn("FAIL: ", p.stderr)

    def test_20_empty_xml_file_fails(self):
        xml = ''
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Empty XML file must fail")
            self.assertIn("FAIL: ", p.stderr)

    # --- Robustness & Edge Cases: unexecuted report masking, XML namespaces, negative attributes ---

    def test_21_one_module_with_good_and_zero_reports_fails(self):
        """A valid report must NOT mask an unexecuted (tests=0) report in the same module."""
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            root = pathlib.Path(tmp)
            mod = self._create_module_with_xml(root, "mod0", GOOD, filename="TEST-good.xml")
            self._create_module_with_xml(root, "mod0", ZERO, filename="TEST-zero.xml")
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Good report + zero-tests report in same module must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_22_xml_with_default_namespace_passes(self):
        """XML test reports with a default xmlns namespace (e.g. Surefire / standard JUnit) must be supported."""
        xml = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<testsuite xmlns="http://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd" '
            'name="Namespaced" tests="1" failures="0" errors="0" skipped="0">\n'
            '  <testcase name="works" classname="Namespaced"/>\n'
            '</testsuite>\n'
        )
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertEqual(p.returncode, 0, f"Namespaced valid XML must pass. Got {p.returncode}. Stderr: {p.stderr}")
            self.assertIn("OK: ", p.stdout)
            self.assertIn("1 instrumented tests", p.stdout)

    def test_23_xml_with_default_namespace_and_failure_fails(self):
        """Namespaced XML containing a failure must be detected and rejected."""
        xml = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<testsuite xmlns="http://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd" '
            'name="NamespacedBad" tests="1" failures="1" errors="0" skipped="0">\n'
            '  <testcase name="fails" classname="NamespacedBad">\n'
            '    <failure message="namespaced failure"/>\n'
            '  </testcase>\n'
            '</testsuite>\n'
        )
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Namespaced XML with failure must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_24_xml_with_default_namespace_and_skipped_fails(self):
        """Namespaced XML with skipped test must be detected and rejected."""
        xml = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<testsuite xmlns="http://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd" '
            'name="NamespacedSkipped" tests="1" failures="0" errors="0" skipped="1">\n'
            '  <testcase name="skip" classname="NamespacedSkipped">\n'
            '    <skipped/>\n'
            '  </testcase>\n'
            '</testsuite>\n'
        )
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Namespaced XML with skipped must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_25_negative_counter_attribute_fails(self):
        """Negative count attributes must fail immediately as invalid."""
        xml = '<testsuite tests="1" failures="-1" errors="0" skipped="0"><testcase name="c1" classname="s"/></testsuite>\n'
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Negative count attribute must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_26_empty_or_whitespace_module_arg_fails(self):
        """Empty or whitespace-only module argument must fail."""
        p = self._run_cli("")
        self.assertNotEqual(p.returncode, 0, "Empty module argument must exit non-zero")
        self.assertIn("FAIL: ", p.stderr)

        p2 = self._run_cli("   ")
        self.assertNotEqual(p2.returncode, 0, "Whitespace module argument must exit non-zero")
        self.assertIn("FAIL: ", p2.stderr)

    def test_27_ignored_tag_and_skipped_status_fails(self):
        """Testcases with <ignored/> tag or status='skipped' must fail closed."""
        xml_ignored = '<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase name="c1"><ignored/></testcase></testsuite>\n'
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml_ignored)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Ignored testcase must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

        xml_status = '<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase name="c2" status="skipped"/></testsuite>\n'
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", xml_status)
            p = self._run_cli(str(mod))
            self.assertNotEqual(p.returncode, 0, "Status='skipped' testcase must exit non-zero")
            self.assertIn("FAIL: ", p.stderr)

    def test_28_xml_with_utf8_bom_passes(self):
        """XML file with UTF-8 BOM must parse successfully."""
        bom_xml = b'\xef\xbb\xbf<?xml version="1.0" encoding="UTF-8"?>\n<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="ok"/></testsuite>\n'
        with tempfile.TemporaryDirectory(prefix="qi-test-") as tmp:
            mod = self._create_module_with_xml(pathlib.Path(tmp), "mod0", bom_xml)
            p = self._run_cli(str(mod))
            self.assertEqual(p.returncode, 0, f"UTF-8 BOM XML must pass. Got {p.returncode}. Stderr: {p.stderr}")
            self.assertIn("OK: ", p.stdout)


if __name__ == "__main__":
    unittest.main()
