#!/usr/bin/env bash
# Fails when an instrumented lane reports success without having run its tests,
# or when reports contain failures, errors, skipped tests, malformed XML, or
# inconsistent counters. Usage: tools/check-instrumented.sh <module-dir>...
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "${SCRIPT_DIR}/check-instrumented.py" "$@"
