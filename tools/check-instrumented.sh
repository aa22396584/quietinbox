#!/usr/bin/env bash
# Fails when an instrumented lane reports success without having run its tests (round 39, subagent
# M5: an instrumentation process that crashed before the runner started left Gradle green with
# `tests="0"`). Usage: tools/check-instrumented.sh <module-dir>... — every module must report at
# least one test and no failure or error in build/outputs/androidTest-results/connected/**/TEST-*.xml.
set -euo pipefail
status=0
for module in "$@"; do
  total=0; bad=0; files=0
  while IFS= read -r -d '' f; do
    files=$((files + 1))
    t=$(sed -n 's/.*<testsuite[^>]* tests="\([0-9]*\)".*/\1/p' "$f" | head -1)
    fl=$(sed -n 's/.*<testsuite[^>]* failures="\([0-9]*\)".*/\1/p' "$f" | head -1)
    er=$(sed -n 's/.*<testsuite[^>]* errors="\([0-9]*\)".*/\1/p' "$f" | head -1)
    total=$((total + ${t:-0})); bad=$((bad + ${fl:-0} + ${er:-0}))
  done < <(find "$module/build/outputs/androidTest-results/connected" -name 'TEST-*.xml' -print0 2>/dev/null)
  if [[ $files -eq 0 || $total -eq 0 ]]; then echo "FAIL: $module ran no instrumented test (files=$files, tests=$total)" >&2; status=1
  elif [[ $bad -ne 0 ]]; then echo "FAIL: $module has $bad failing/erroring instrumented tests" >&2; status=1
  else echo "OK: $module ran $total instrumented tests"; fi
done
exit $status
