# Evidence Manifest: Issue #32 C0 (Empty-Conversation Sweep & Safe Cascade Boundary)

- **Date**: 2026-09-13
- **Branch**: `feat/issue32-empty-conversation-safety-20260913`
- **Target Issue**: Refs #32, Refs #38
- **Platform**: Android API 36 Emulator (`emulator-5554`, Pixel_9(AVD), arm64) + JDK 21 macOS Host

---

## 1. Summary of Scope & Verification

This package isolates and verifies the **#32 C0** security boundary for empty-conversation cleanup:
1. **Atomic Sweep**: `RetentionWorker` replaces `emptyOlderThan` scan + `delete(id)` loop with atomic `deleteEmptyOlderThan(before)`, executing:
   `DELETE FROM conversation WHERE createdAtEpochMs < :before AND NOT EXISTS (SELECT 1 FROM message m WHERE m.conversationId = conversation.id)`.
2. **Preserves Unexpired Messages**: Conversations containing only `AMBIGUOUS_REPEAT` message rows are never deleted by the retention sweep, protecting them from cascade deletion.
3. **Eliminates Race Footgun**: `deleteIfEmptyAndOlderThan(id, before)` verifies `NOT EXISTS` at delete time, ensuring a message committed after an empty-scan is not cascade-deleted.
4. **Guards Query API**: `ConversationDao.emptyOlderThan` is guarded with `NOT EXISTS (SELECT 1 FROM message m WHERE m.conversationId = conversation.id)` instead of `messageCount = 0`, and documented with explicit KDocs warning against unguarded two-phase scan-then-delete patterns.
5. **Concurrent Commit Resilience**: Verified that concurrent commit and retention sweep (`deleteEmptyOlderThan`) under multi-threaded IO execution never drop committed messages or trigger SQLite locking failures.
6. **Full Ambiguous Repeat Lifecycle**: Verified that unexpired ambiguous repeats prevent conversation deletion, while expired ambiguous repeats are properly reaped and their empty conversations cleaned up.
7. **Young Conversation Policy**: Verified that empty conversations younger than 7 days (`createdAtEpochMs >= now - 7L * DAY_MS`) are preserved.

---

## 2. Evidence Files and SHA-256 Checksums

| File | Scope / Target | Command & Return Code | Exec/Skip/Fail | SHA-256 Digest |
|---|---|---|---|---|
| `TEST-dev.quietinbox.platform.storage.DeletionGraphTest.xml` | `dev.quietinbox.platform.storage.DeletionGraphTest` (12 tests) | `ANDROID_SERIAL=emulator-5554 ./gradlew :platform:storage:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.quietinbox.platform.storage.DeletionGraphTest -Dcom.android.ddmlib.tools.timeout=60000` (rc=0) | 12 / 0 / 0 | `5d6e7f7ffe3e7ab8cc2ebda9aa0f8ecfdb462eefe0747b5fd248ce4c7635e01f` |
| `negative-controls/nc1-sweep-revert-to-emptyolderthan.patch` | `Daos.kt` (`deleteEmptyOlderThan` mutated to `messageCount = 0`) | N/A (Source patch) | N/A | `0692b3bbf17be42d761c26fa81d6c34064546592e93a8c96d8f445e246c6d620` |
| `negative-controls/nc1-sweep-revert-to-emptyolderthan-failure.txt` | `DeletionGraphTest.emptyConversationSweepKeepsAnUnexpiredAmbiguousRepeat` | Connected test on emulator-5554 (rc=1) | 0 / 0 / 1 | `e37e88d2bdb0e66ccd7a255596c82ef3fa3ec09627afaedf576a8b6ee3cd5e20` |
| `negative-controls/nc2-emptyolderthan-messagecount-zero.patch` | `Daos.kt` (`emptyOlderThan` mutated to `messageCount = 0`) | N/A (Source patch) | N/A | `5b14e10f2007c2b2e0b935c014782dbc07d5d5b16c365743ff262b5d8bbb5b66` |
| `negative-controls/nc2-emptyolderthan-messagecount-zero-failure.txt` | `DeletionGraphTest.emptyOlderThanDoesNotReturnConversationWithUnexpiredAmbiguousRepeat` | Connected test on emulator-5554 (rc=1) | 0 / 0 / 1 | `55c26f490275f26451a96e51ef1a52928edb0802287883924c4300fef7eb3afb` |

---

## 3. Tested APK Binaries

| Target | Path | Package Name | Size (Bytes) | SHA-256 Digest |
|---|---|---|---|---|
| Instrumented Storage Test APK | `platform/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk` | `dev.quietinbox.platform.storage.test` | 17,005,142 | `0d8b1af8499940965c8e0a17807adf43280ef36179e4b882850960cd1d90c389` |
| App Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | `dev.quietinbox.app.debug` | 33,161,838 | `b7c5a5f2203a4ea2b2672efb8a226bc2bacaf506e7ee7cf228305212473905d5` |

---

## 4. Verification Gates Passed
- **Connected Instrumented Tests**: 12/12 PASS on Android API 36 (`emulator-5554`).
- **JVM Unit Tests**: `./gradlew test` BUILD SUCCESSFUL (all modules pass).
- **Assemble & Permissions**: `./gradlew :app:assembleDebug` and `tools/check-permissions.sh` pass (no network permissions).
- **Lint**: `./gradlew :platform:storage:lintDebug` BUILD SUCCESSFUL (0 errors).
