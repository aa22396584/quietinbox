#!/usr/bin/env python3
"""Validates connected instrumented test XML reports for specified modules.

Fails closed when:
- No module arguments are provided, or empty/whitespace module argument is passed.
- Any module has zero test report files.
- Any module XML is missing, unreadable, or malformed.
- Any XML has unsupported root element or structure.
- Declared summary counters (tests, failures, errors, skipped) contradict actual testcase children.
- Any failure or error child is present (even if summary counters say 0 or are missing).
- Any test is skipped or ignored (all tests are required; no skipped tests permitted).
- A module has zero executed tests (total tests = 0 or only skipped tests).
- One valid module cannot mask another invalid or failing module.
- One valid report cannot mask an unexecuted (tests=0) report within the same module.
- XML namespaces (e.g. xmlns="...") and encodings are handled cleanly.

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


def _local_name(tag: str) -> str:
    """Extract the local name from an XML tag, stripping namespace URI if present."""
    if tag.startswith("{"):
        return tag.split("}", 1)[1]
    return tag


def _is_tag(elem: ET.Element, expected: str) -> bool:
    """Case-insensitive check for element tag matching expected name (ignoring namespace)."""
    return _local_name(elem.tag).lower() == expected.lower()


def _parse_int_attr(elem: ET.Element, attr_name: str, file_path: pathlib.Path, default: int | None = None) -> int | None:
    val_str = elem.get(attr_name)
    if val_str is None:
        return default
    try:
        val = int(val_str)
        if val < 0:
            raise ValueError(f"Negative count attribute '{attr_name}=\"{val_str}\"' in {file_path}")
        return val
    except ValueError as exc:
        raise ValueError(f"Invalid integer attribute '{attr_name}=\"{val_str}\"' in {file_path}: {exc}") from exc


def _parse_skipped_attr(elem: ET.Element, file_path: pathlib.Path) -> int | None:
    skipped = _parse_int_attr(elem, "skipped", file_path, default=None)
    skips = _parse_int_attr(elem, "skips", file_path, default=None)
    if skipped is not None and skips is not None:
        if skipped != skips:
            raise ValueError(
                f"Contradictory skip attributes: skipped={skipped} and skips={skips} in {file_path}"
            )
        return skipped
    if skipped is not None:
        return skipped
    return skips


_ALLOWED_METADATA_TAGS = {"properties", "system-out", "system-err"}
_ALLOWED_RESULT_TAGS = {"failure", "error", "skipped", "ignored"}
_ALLOWED_TESTCASE_TAGS = _ALLOWED_METADATA_TAGS | _ALLOWED_RESULT_TAGS


def _validate_leaf_suite(suite_elem: ET.Element, file_path: pathlib.Path) -> SuiteCounts:
    """Validate a leaf testsuite element and its testcase children."""
    child_suites = [c for c in suite_elem if _is_tag(c, "testsuite")]
    if child_suites:
        raise ValueError(f"Unexpected nested <testsuite> inside leaf suite in {file_path}")

    for c in suite_elem:
        tag = _local_name(c.tag).lower()
        if tag not in ("testcase", *_ALLOWED_METADATA_TAGS, *_ALLOWED_RESULT_TAGS):
            raise ValueError(f"Unsupported element <{_local_name(c.tag)}> in <testsuite> in {file_path}")

    direct_testcases = [c for c in suite_elem if _is_tag(c, "testcase")]
    all_testcases = [e for e in suite_elem.iter() if _is_tag(e, "testcase")]
    if len(direct_testcases) != len(all_testcases):
        raise ValueError(f"Malformed or nested <testcase> hierarchy in {file_path}")

    actual_total = len(direct_testcases)
    if actual_total == 0:
        raise ValueError(f"<testsuite> contains zero testcases in {file_path}")

    # Check for suite-level failure or error tags (e.g. class setup failures)
    suite_level_failures = sum(1 for c in suite_elem if _is_tag(c, "failure"))
    suite_level_errors = sum(1 for c in suite_elem if _is_tag(c, "error"))
    suite_level_skipped = sum(1 for c in suite_elem if _local_name(c.tag).lower() in ("skipped", "ignored"))

    actual_failures = suite_level_failures
    actual_errors = suite_level_errors
    actual_skipped = suite_level_skipped
    actual_passed = 0

    for tc in direct_testcases:
        for c in tc:
            tag = _local_name(c.tag).lower()
            if tag not in _ALLOWED_TESTCASE_TAGS:
                raise ValueError(f"Unsupported element <{_local_name(c.tag)}> inside <testcase> in {file_path}")

        has_failure = any(_is_tag(e, "failure") for e in tc.iter())
        has_error = any(_is_tag(e, "error") for e in tc.iter())
        has_skipped = any(_local_name(e.tag).lower() in ("skipped", "ignored") for e in tc.iter())

        # Also inspect testcase status / result attributes if present
        tc_status = (tc.get("status") or "").lower()
        tc_result = (tc.get("result") or "").lower()
        if tc_status in ("failed", "failure") or tc_result in ("failed", "failure"):
            has_failure = True
        if tc_status in ("error", "errored") or tc_result in ("error", "errored"):
            has_error = True
        if tc_status in ("skipped", "ignored", "notrun") or tc_result in ("skipped", "ignored", "notrun"):
            has_skipped = True

        if has_failure:
            actual_failures += 1
        if has_error:
            actual_errors += 1
        if has_skipped:
            actual_skipped += 1
        if not (has_failure or has_error or has_skipped):
            actual_passed += 1

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

    declared_skipped = _parse_skipped_attr(suite_elem, file_path)
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
        raw_bytes = file_path.read_bytes()
    except Exception as exc:
        raise ValueError(f"Failed to read XML file {file_path}: {exc}") from exc

    if not raw_bytes.strip():
        raise ValueError(f"Empty XML file {file_path}")

    try:
        root = ET.fromstring(raw_bytes)
    except ET.ParseError as exc:
        raise ValueError(f"Malformed XML in {file_path}: {exc}") from exc

    leaf_counts: list[SuiteCounts] = []

    if _is_tag(root, "testsuites"):
        for c in root:
            tag = _local_name(c.tag).lower()
            if tag not in ("testsuite", *_ALLOWED_METADATA_TAGS, *_ALLOWED_RESULT_TAGS):
                raise ValueError(f"Unsupported element <{_local_name(c.tag)}> under <testsuites> in {file_path}")

        child_suites = [c for c in root if _is_tag(c, "testsuite")]
        direct_testcases = [c for c in root if _is_tag(c, "testcase")]
        if direct_testcases:
            raise ValueError(f"Unexpected direct <testcase> children under <testsuites> in {file_path}")

        all_suites = [e for e in root.iter() if _is_tag(e, "testsuite")]
        if len(child_suites) != len(all_suites):
            raise ValueError(f"Unsupported nested or wrapped <testsuite> hierarchy in {file_path}")

        if not child_suites:
            raise ValueError(f"<testsuites> contains zero <testsuite> children in {file_path}")

        for child in child_suites:
            nested = [c for c in child if _is_tag(c, "testsuite")]
            if nested:
                raise ValueError(f"Deeply nested <testsuite> inside <testsuite> in {file_path}")
            leaf_counts.append(_validate_leaf_suite(child, file_path))

        all_testcases = [e for e in root.iter() if _is_tag(e, "testcase")]
        sum_leaf_testcases = sum(c.tests for c in leaf_counts)
        if len(all_testcases) != sum_leaf_testcases:
            raise ValueError(f"Unsupported wrapped <testcase> hierarchy under <testsuites> in {file_path}")

        root_failures = sum(1 for c in root if _is_tag(c, "failure"))
        root_errors = sum(1 for c in root if _is_tag(c, "error"))
        root_skipped = sum(1 for c in root if _local_name(c.tag).lower() in ("skipped", "ignored"))

        sum_tests = sum(c.tests for c in leaf_counts)
        sum_failures = sum(c.failures for c in leaf_counts) + root_failures
        sum_errors = sum(c.errors for c in leaf_counts) + root_errors
        sum_skipped = sum(c.skipped for c in leaf_counts) + root_skipped

        if sum_tests == 0:
            raise ValueError(f"<testsuites> ran zero tests in {file_path}")

        decl_tests = _parse_int_attr(root, "tests", file_path, default=None)
        if decl_tests is not None and decl_tests != sum_tests:
            raise ValueError(f"<testsuites> declared tests={decl_tests} but child suites sum to {sum_tests} in {file_path}")

        decl_failures = _parse_int_attr(root, "failures", file_path, default=None)
        if decl_failures is not None and decl_failures != sum_failures:
            raise ValueError(f"<testsuites> declared failures={decl_failures} but child suites sum to {sum_failures} in {file_path}")

        decl_errors = _parse_int_attr(root, "errors", file_path, default=None)
        if decl_errors is not None and decl_errors != sum_errors:
            raise ValueError(f"<testsuites> declared errors={decl_errors} but child suites sum to {sum_errors} in {file_path}")

        decl_skipped = _parse_skipped_attr(root, file_path)
        if decl_skipped is not None and decl_skipped != sum_skipped:
            raise ValueError(f"<testsuites> declared skipped={decl_skipped} but child suites sum to {sum_skipped} in {file_path}")

    elif _is_tag(root, "testsuite"):
        child_suites = [c for c in root if _is_tag(c, "testsuite")]
        if child_suites:
            for c in root:
                tag = _local_name(c.tag).lower()
                if tag not in ("testsuite", *_ALLOWED_METADATA_TAGS, *_ALLOWED_RESULT_TAGS):
                    raise ValueError(f"Unsupported element <{_local_name(c.tag)}> under aggregate <testsuite> in {file_path}")

            direct_testcases = [c for c in root if _is_tag(c, "testcase")]
            if direct_testcases:
                raise ValueError(f"Mixed <testsuite> and <testcase> children under root in {file_path}")

            all_suites = [e for e in root.iter() if _is_tag(e, "testsuite") and e is not root]
            if len(child_suites) != len(all_suites):
                raise ValueError(f"Unsupported nested or wrapped <testsuite> hierarchy in {file_path}")

            for child in child_suites:
                leaf_counts.append(_validate_leaf_suite(child, file_path))

            all_testcases = [e for e in root.iter() if _is_tag(e, "testcase")]
            sum_leaf_testcases = sum(c.tests for c in leaf_counts)
            if len(all_testcases) != sum_leaf_testcases:
                raise ValueError(f"Unsupported wrapped <testcase> hierarchy under aggregate <testsuite> in {file_path}")

            root_failures = sum(1 for c in root if _is_tag(c, "failure"))
            root_errors = sum(1 for c in root if _is_tag(c, "error"))
            root_skipped = sum(1 for c in root if _local_name(c.tag).lower() in ("skipped", "ignored"))

            sum_tests = sum(c.tests for c in leaf_counts)
            sum_failures = sum(c.failures for c in leaf_counts) + root_failures
            sum_errors = sum(c.errors for c in leaf_counts) + root_errors
            sum_skipped = sum(c.skipped for c in leaf_counts) + root_skipped

            if sum_tests == 0:
                raise ValueError(f"Root <testsuite> ran zero tests in {file_path}")

            decl_tests = _parse_int_attr(root, "tests", file_path, default=None)
            if decl_tests is not None and decl_tests != sum_tests:
                raise ValueError(f"Root <testsuite> declared tests={decl_tests} but child suites sum to {sum_tests} in {file_path}")

            decl_failures = _parse_int_attr(root, "failures", file_path, default=None)
            if decl_failures is not None and decl_failures != sum_failures:
                raise ValueError(f"Root <testsuite> declared failures={decl_failures} but child suites sum to {sum_failures} in {file_path}")

            decl_errors = _parse_int_attr(root, "errors", file_path, default=None)
            if decl_errors is not None and decl_errors != sum_errors:
                raise ValueError(f"Root <testsuite> declared errors={decl_errors} but child suites sum to {sum_errors} in {file_path}")

            decl_skipped = _parse_skipped_attr(root, file_path)
            if decl_skipped is not None and decl_skipped != sum_skipped:
                raise ValueError(f"Root <testsuite> declared skipped={decl_skipped} but child suites sum to {sum_skipped} in {file_path}")
        else:
            leaf_counts.append(_validate_leaf_suite(root, file_path))
    else:
        raise ValueError(f"Unsupported XML root element <{root.tag}> in {file_path}")

    total_tests = sum(c.tests for c in leaf_counts)
    if total_tests == 0:
        raise ValueError(f"Report contains zero executed tests in {file_path}")

    final_failures = sum(c.failures for c in leaf_counts) + (root_failures if "root_failures" in locals() else 0)
    final_errors = sum(c.errors for c in leaf_counts) + (root_errors if "root_errors" in locals() else 0)
    final_skipped = sum(c.skipped for c in leaf_counts) + (root_skipped if "root_skipped" in locals() else 0)
    final_passed = sum(c.passed for c in leaf_counts)

    doc_failures = sum(1 for e in root.iter() if _is_tag(e, "failure"))
    doc_errors = sum(1 for e in root.iter() if _is_tag(e, "error"))
    doc_skipped = sum(1 for e in root.iter() if _local_name(e.tag).lower() in ("skipped", "ignored"))

    if doc_failures > final_failures:
        raise ValueError(f"Unaccounted <failure> nodes ({doc_failures} > {final_failures}) in {file_path}")
    if doc_errors > final_errors:
        raise ValueError(f"Unaccounted <error> nodes ({doc_errors} > {final_errors}) in {file_path}")
    if doc_skipped > final_skipped:
        raise ValueError(f"Unaccounted <skipped> nodes ({doc_skipped} > {final_skipped}) in {file_path}")

    return SuiteCounts(
        tests=total_tests,
        failures=final_failures,
        errors=final_errors,
        skipped=final_skipped,
        passed=final_passed,
    )


def check_module(module_str: str) -> tuple[bool, str]:
    """Validate reports for a single module. Returns (success, message)."""
    if not module_str or not module_str.strip():
        return False, "FAIL: Empty module directory specified."

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

        if counts.tests == 0:
            return False, f"FAIL: {module_str} report ({xml_file.name}) ran zero tests"

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
