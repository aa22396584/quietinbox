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
4. **Guards Query API**: `ConversationDao.emptyOlderThan` is guarded with `NOT EXISTS (SELECT 1 FROM message m WHERE m.conversationId = conversation.id)` instead of `messageCount = 0`.

---

## 2. Evidence Files and SHA-256 Checksums

| File | Scope / Target | Command & Return Code | Exec/Skip/Fail | SHA-256 Digest |
|---|---|---|---|---|
| `TEST-dev.quietinbox.platform.storage.DeletionGraphTest.xml` | `dev.quietinbox.platform.storage.DeletionGraphTest` (9 tests) | `ANDROID_SERIAL=emulator-5554 ./gradlew :platform:storage:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.quietinbox.platform.storage.DeletionGraphTest` (rc=0) | 9 / 0 / 0 | `eb3261390ed865f6bad14db88055936a3d2f6208ae73c185c87270f56b71f2d8` |
| `negative-controls/nc1-sweep-revert-to-emptyolderthan.patch` | `Daos.kt` (`deleteEmptyOlderThan` mutated to `messageCount = 0`) | N/A (Source patch) | N/A | `4a70dfb0c08dbd1214c8d0d2338984b4c89ff42e356aa967b9e3268923078107` |
| `negative-controls/nc1-sweep-revert-to-emptyolderthan-failure.txt` | `DeletionGraphTest.emptyConversationSweepKeepsAnUnexpiredAmbiguousRepeat` | Connected test on emulator-5554 (rc=1) | 0 / 0 / 1 | `dca0c4ad017017e00c8bf4fb410cf41011863d331e165129ede1fd73c2fa33ad` |
| `negative-controls/nc2-emptyolderthan-messagecount-zero.patch` | `Daos.kt` (`emptyOlderThan` mutated to `messageCount = 0`) | N/A (Source patch) | N/A | `066470452ff7adc4bc689628e68f60ef88f8d5abb5297397e1ece84aa57eeb13` |
| `negative-controls/nc2-emptyolderthan-messagecount-zero-failure.txt` | `DeletionGraphTest.emptyOlderThanDoesNotReturnConversationWithUnexpiredAmbiguousRepeat` | Connected test on emulator-5554 (rc=1) | 0 / 0 / 1 | `16c19a10aa76e7f33c312c00336ba0610854e29c5e0dbba4ccc9f51580ec4198` |

---

## 3. Tested APK Binary

| Target | Path | Package Name | Size (Bytes) | SHA-256 Digest |
|---|---|---|---|---|
| Instrumented Storage Test APK | `platform/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk` | `dev.quietinbox.platform.storage.test` | 17,084,433 | `d1236bf0585913253fd81882048f80f1ccf671844ffa9375a7029b6b51a8f98c` |

---

## 4. Verification Gates Passed
- **Connected Instrumented Tests**: 9/9 PASS on Android API 36 (`emulator-5554`).
- **JVM Unit Tests**: `./gradlew test` BUILD SUCCESSFUL (all modules pass).
- **Assemble & Permissions**: `./gradlew :app:assembleDebug` and `tools/check-permissions.sh` pass (no network permissions).
- **Lint**: `./gradlew :platform:storage:lintDebug` BUILD SUCCESSFUL (0 errors).
