#!/usr/bin/env python3
"""Validates connected instrumented test XML reports for specified modules.

Fails closed when:
- No module arguments are provided.
- Any module has zero test report files.
- Any module XML is missing, unreadable, or malformed.
- Any XML has unsupported root element or structure.
- Declared summary counters (tests, failures, errors, skipped) contradict actual testcase children.
- Any failure or error child is present (even if summary counters say 0 or are missing).
- Any test is skipped (all tests are required; no skipped tests permitted).
- A module has zero executed tests (total tests = 0 or only skipped tests).
- One valid module cannot mask another invalid or failing module.

Usage:
  tools/check-instrumented.sh <module-dir>...
  python3 tools/check-instrumented.py <module-dir>...
"""

import pathlib
import sys
import xml.etree.ElementTree as ET
from typing import NamedTuple


class SuiteCounts(NamedTuple):
    tests: int
    failures: int
    errors: int
    skipped: int
    passed: int


def _parse_int_attr(elem: ET.Element, attr_name: str, file_path: pathlib.Path, default: int | None = None) -> int | None:
    val_str = elem.get(attr_name)
    if val_str is None:
        return default
    try:
        return int(val_str)
    except ValueError:
        raise ValueError(f"Non-integer attribute '{attr_name}=\"{val_str}\"' in {file_path}")


def _validate_leaf_suite(suite_elem: ET.Element, file_path: pathlib.Path) -> SuiteCounts:
    """Validate a leaf testsuite element and its testcase children."""
    if suite_elem.findall("./testsuite"):
        raise ValueError(f"Unexpected nested <testsuite> inside leaf suite in {file_path}")

    direct_testcases = suite_elem.findall("./testcase")
    all_testcases = suite_elem.findall(".//testcase")
    if len(direct_testcases) != len(all_testcases):
        raise ValueError(f"Malformed or nested <testcase> hierarchy in {file_path}")

    # Check for suite-level failure or error tags (e.g. class setup failures)
    suite_level_failures = len(suite_elem.findall("./failure"))
    suite_level_errors = len(suite_elem.findall("./error"))

    actual_failures = suite_level_failures
    actual_errors = suite_level_errors
    actual_skipped = 0
    actual_passed = 0

    for tc in direct_testcases:
        has_failure = tc.find("./failure") is not None
        has_error = tc.find("./error") is not None
        has_skipped = tc.find("./skipped") is not None

        if has_failure:
            actual_failures += 1
        if has_error:
            actual_errors += 1
        if has_skipped:
            actual_skipped += 1
        if not (has_failure or has_error or has_skipped):
            actual_passed += 1

    actual_total = len(direct_testcases)

    declared_tests = _parse_int_attr(suite_elem, "tests", file_path, default=None)
    if declared_tests is None:
        raise ValueError(f"Missing required 'tests' attribute on <testsuite> in {file_path}")
    if declared_tests != actual_total:
        raise ValueError(
            f"<testsuite> declared tests={declared_tests} but has {actual_total} <testcase> children in {file_path}"
        )

    declared_failures = _parse_int_attr(suite_elem, "failures", file_path, default=None)
    if declared_failures is not None:
        if declared_failures != actual_failures:
            raise ValueError(
                f"<testsuite> declared failures={declared_failures} but has {actual_failures} failure children in {file_path}"
            )
    else:
        if actual_failures > 0:
            raise ValueError(f"<testsuite> has {actual_failures} failure children without 'failures' attribute in {file_path}")

    declared_errors = _parse_int_attr(suite_elem, "errors", file_path, default=None)
    if declared_errors is not None:
        if declared_errors != actual_errors:
            raise ValueError(
                f"<testsuite> declared errors={declared_errors} but has {actual_errors} error children in {file_path}"
            )
    else:
        if actual_errors > 0:
            raise ValueError(f"<testsuite> has {actual_errors} error children without 'errors' attribute in {file_path}")

    declared_skipped = _parse_int_attr(suite_elem, "skipped", file_path, default=None)
    if declared_skipped is None:
        declared_skipped = _parse_int_attr(suite_elem, "skips", file_path, default=None)

    if declared_skipped is not None:
        if declared_skipped != actual_skipped:
            raise ValueError(
                f"<testsuite> declared skipped={declared_skipped} but has {actual_skipped} skipped children in {file_path}"
            )
    else:
        if actual_skipped > 0:
            raise ValueError(f"<testsuite> has {actual_skipped} skipped children without 'skipped' attribute in {file_path}")

    return SuiteCounts(
        tests=actual_total,
        failures=actual_failures,
        errors=actual_errors,
        skipped=actual_skipped,
        passed=actual_passed,
    )


def _validate_xml_report(file_path: pathlib.Path) -> SuiteCounts:
    """Parse and validate a single TEST-*.xml file. Returns the aggregated SuiteCounts."""
    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as exc:
        raise ValueError(f"Failed to read XML file {file_path}: {exc}") from exc

    if not content.strip():
        raise ValueError(f"Empty XML file {file_path}")

    try:
        root = ET.fromstring(content)
    except ET.ParseError as exc:
        raise ValueError(f"Malformed XML in {file_path}: {exc}") from exc

    leaf_counts: list[SuiteCounts] = []

    if root.tag == "testsuites":
        child_suites = root.findall("./testsuite")
        direct_testcases = root.findall("./testcase")
        if direct_testcases:
            raise ValueError(f"Unexpected direct <testcase> children under <testsuites> in {file_path}")

        for child in child_suites:
            nested = child.findall("./testsuite")
            if nested:
                raise ValueError(f"Deeply nested <testsuite> inside <testsuite> in {file_path}")
            leaf_counts.append(_validate_leaf_suite(child, file_path))

        sum_tests = sum(c.tests for c in leaf_counts)
        sum_failures = sum(c.failures for c in leaf_counts)
        sum_errors = sum(c.errors for c in leaf_counts)
        sum_skipped = sum(c.skipped for c in leaf_counts)

        decl_tests = _parse_int_attr(root, "tests", file_path, default=None)
        if decl_tests is not None and decl_tests != sum_tests:
            raise ValueError(f"<testsuites> declared tests={decl_tests} but child suites sum to {sum_tests} in {file_path}")

        decl_failures = _parse_int_attr(root, "failures", file_path, default=None)
        if decl_failures is not None and decl_failures != sum_failures:
            raise ValueError(f"<testsuites> declared failures={decl_failures} but child suites sum to {sum_failures} in {file_path}")

        decl_errors = _parse_int_attr(root, "errors", file_path, default=None)
        if decl_errors is not None and decl_errors != sum_errors:
            raise ValueError(f"<testsuites> declared errors={decl_errors} but child suites sum to {sum_errors} in {file_path}")

        decl_skipped = _parse_int_attr(root, "skipped", file_path, default=None)
        if decl_skipped is None:
            decl_skipped = _parse_int_attr(root, "skips", file_path, default=None)
        if decl_skipped is not None and decl_skipped != sum_skipped:
            raise ValueError(f"<testsuites> declared skipped={decl_skipped} but child suites sum to {sum_skipped} in {file_path}")

    elif root.tag == "testsuite":
        child_suites = root.findall("./testsuite")
        if child_suites:
            direct_testcases = root.findall("./testcase")
            if direct_testcases:
                raise ValueError(f"Mixed <testsuite> and <testcase> children under root in {file_path}")
            for child in child_suites:
                leaf_counts.append(_validate_leaf_suite(child, file_path))

            sum_tests = sum(c.tests for c in leaf_counts)
            sum_failures = sum(c.failures for c in leaf_counts)
            sum_errors = sum(c.errors for c in leaf_counts)
            sum_skipped = sum(c.skipped for c in leaf_counts)

            decl_tests = _parse_int_attr(root, "tests", file_path, default=None)
            if decl_tests is not None and decl_tests != sum_tests:
                raise ValueError(f"Root <testsuite> declared tests={decl_tests} but child suites sum to {sum_tests} in {file_path}")

            decl_failures = _parse_int_attr(root, "failures", file_path, default=None)
            if decl_failures is not None and decl_failures != sum_failures:
                raise ValueError(f"Root <testsuite> declared failures={decl_failures} but child suites sum to {sum_failures} in {file_path}")

            decl_errors = _parse_int_attr(root, "errors", file_path, default=None)
            if decl_errors is not None and decl_errors != sum_errors:
                raise ValueError(f"Root <testsuite> declared errors={decl_errors} but child suites sum to {sum_errors} in {file_path}")

            decl_skipped = _parse_int_attr(root, "skipped", file_path, default=None)
            if decl_skipped is None:
                decl_skipped = _parse_int_attr(root, "skips", file_path, default=None)
            if decl_skipped is not None and decl_skipped != sum_skipped:
                raise ValueError(f"Root <testsuite> declared skipped={decl_skipped} but child suites sum to {sum_skipped} in {file_path}")
        else:
            leaf_counts.append(_validate_leaf_suite(root, file_path))
    else:
        raise ValueError(f"Unsupported XML root element <{root.tag}> in {file_path}")

    return SuiteCounts(
        tests=sum(c.tests for c in leaf_counts),
        failures=sum(c.failures for c in leaf_counts),
        errors=sum(c.errors for c in leaf_counts),
        skipped=sum(c.skipped for c in leaf_counts),
        passed=sum(c.passed for c in leaf_counts),
    )


def check_module(module_str: str) -> tuple[bool, str]:
    """Validate reports for a single module. Returns (success, message)."""
    module_path = pathlib.Path(module_str)
    search_dir = module_path / "build" / "outputs" / "androidTest-results" / "connected"
    if not search_dir.is_dir():
        return False, f"FAIL: {module_str} ran no instrumented test (files=0, tests=0)"

    xml_files = sorted(search_dir.glob("**/TEST-*.xml"))
    if not xml_files:
        return False, f"FAIL: {module_str} ran no instrumented test (files=0, tests=0)"

    module_tests = 0
    module_failures = 0
    module_errors = 0
    module_skipped = 0
    module_passed = 0

    for xml_file in xml_files:
        try:
            counts = _validate_xml_report(xml_file)
        except ValueError as exc:
            return False, f"FAIL: {module_str} report invalid ({xml_file.name}): {exc}"

        module_tests += counts.tests
        module_failures += counts.failures
        module_errors += counts.errors
        module_skipped += counts.skipped
        module_passed += counts.passed

    if module_tests == 0:
        return False, f"FAIL: {module_str} ran no instrumented test (files={len(xml_files)}, tests=0)"

    bad = module_failures + module_errors
    if bad > 0:
        return False, f"FAIL: {module_str} has {bad} failing/erroring instrumented tests"

    if module_skipped > 0:
        return False, f"FAIL: {module_str} has {module_skipped} skipped instrumented tests (all tests required)"

    if module_passed == 0:
        return False, f"FAIL: {module_str} ran no passed instrumented tests"

    if module_passed != module_tests:
        return False, f"FAIL: {module_str} count mismatch: passed={module_passed} != total={module_tests}"

    return True, f"OK: {module_str} ran {module_passed} instrumented tests"


def main() -> int:
    if len(sys.argv) < 2:
        print("FAIL: No module directories specified.", file=sys.stderr)
        return 1

    overall_status = 0
    for module in sys.argv[1:]:
        passed, msg = check_module(module)
        if passed:
            print(msg)
        else:
            print(msg, file=sys.stderr)
            overall_status = 1

    return overall_status


if __name__ == "__main__":
    sys.exit(main())
