#!/usr/bin/env bash
# Fails when a built APK's versionName differs from the version the release tag names (audit-2
# APK-07): the tag alone used to decide the file names, and nothing read the binary.
# Usage: tools/check-version.sh app/build/outputs/apk/release/app-release.apk 0.1.4
set -euo pipefail
APK="${1:?apk path required}"
WANT="${2:?version required}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
if [[ -z "$AAPT2" ]]; then echo "aapt2 not found under $SDK/build-tools" >&2; exit 2; fi
NAME="$("$AAPT2" dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
if [[ "$NAME" != "$WANT" ]]; then
  echo "FAIL: $APK carries versionName '$NAME', the tag says '$WANT'" >&2
  exit 1
fi
echo "OK: $APK carries versionName $NAME"
