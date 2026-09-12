# Manifest: Test Evidence Artifacts (Round 50, 2026-09-13)

This directory contains retrievable raw XML test reports, execution logs, negative control patches, and failure outputs for tasks E1, E2, and E3 of `ImL1s/quietinbox`.

All test runs were freshly executed on exact target commit SHAs using the isolated AVD `emulator-5554` (API 36 supplementary evidence; API 29/35 remain BLOCKED_ENV) or local JVM (Java 21). Physical USB device was strictly isolated. Historical unavailable test runs remain marked `UNVERIFIED`.

---

## 1. Environment & Device Identity

- **Host OS**: macOS Darwin 25.3.0 (arm64)
- **JDK**: OpenJDK 64-Bit Server VM (build 21.0.3+9-LTS)
- **Android Emulator**: `emulator-5554`
  - **AVD Name**: `Pixel_9(AVD)`
  - **API Level**: 36 (Android 16 / VanillaIceCream / Baklava preview)
  - **ABI**: arm64-v8a
- **Excluded / Blocked**:
  - Physical USB device: strictly excluded and not targeted.
  - API 29 / API 35: `BLOCKED_ENV` (system images not pre-installed and large downloads unauthorized).

---

## 2. Commit Identity & Historical Correction

- **PR #40 (`feat/rs04-source-evidence-ui-20260912`)**:
  - **Current Verified HEAD**: `6607cc9c9819d3fed7b6aabf5162293a76093184`
  - **Previous Round HEAD**: `15591ceb86140e0d0fbd12861f65d0a85ff998a3`
  - **Historical Correction Record**:
    The author's report in round 49 cited SHA `57595a38a7c64cf288cb43ee41a4575c3db6f28f`, which did not exist in git history. The actual commit at that point was `57595a3b7363b22f2e46928e50bfa0f6e62b440c` (a transcription error in the 8th hex digit: `8` vs `b`).
    Commit `6607cc9c9819d3fed7b6aabf5162293a76093184` further closes the loophole where `currentCohort == null` bypassed non-blank adapterVersion validation for synthetic evidence, and strengthens contract assertions to `shouldBe UNTESTED`.
    `core:model` test artifacts are freshly produced on `6607cc9c9819d3fed7b6aabf5162293a76093184` (earlier draft miswrote 40-char SHA as 6607cc90dfdf... from 7-char short hash). UI test artifacts are on `15591ceb86140e0d0fbd12861f65d0a85ff998a3` (core:model changes did not touch UI code).
- **PR #41 (`feat/issue33-atomic-terminal-gap-20260912`)**:
  - **Current Verified HEAD**: `f279c895e774f41257e900cee0cdd5b336175d4e`
  - **Previous Review Base**: `adca504642d17201bde34718ba470acad8bb8a77`
  - All artifacts in `pr41/` are freshly produced on `f279c895e774f41257e900cee0cdd5b336175d4e`.

---

## 3. File-by-File Manifest

### PR #40 Artifacts (`evidence/20260913-round50/pr40/`)

| File | Source Commit SHA | Test Class / Scope | Command & Return Code | Platform / Target | Tests (Exec/Skip/Fail) | SHA-256 Digest |
|---|---|---|---|---|---|---|
| `pr40/TEST-dev.quietinbox.core.model.SourceEvidenceTest.xml` | `6607cc9c9819d3fed7b6aabf5162293a76093184` | `dev.quietinbox.core.model.SourceEvidenceTest` | `./gradlew :core:model:test --rerun-tasks` (rc=0) | JVM (macOS / JDK 21) | 22 / 0 / 0 | `7bcdb4445cfb63e9fd7d13a499afa7673455370dde2dd232e2a69a80c1a416ae` |
| `pr40/TEST-dev.quietinbox.core.model.SearchNormalizerTest.xml` | `6607cc9c9819d3fed7b6aabf5162293a76093184` | `dev.quietinbox.core.model.SearchNormalizerTest` | `./gradlew :core:model:test --rerun-tasks` (rc=0) | JVM (macOS / JDK 21) | 6 / 0 / 0 | `230d6422e5beeea85d5261cdb1dd59595e54a767c33b557eefba3a7e26a06d16` |
| `pr40/TEST-feature-onboarding-SourcesStepSemanticsTest.xml` | `15591ceb86140e0d0fbd12861f65d0a85ff998a3` | `dev.quietinbox.feature.onboarding.SourcesStepSemanticsTest` | `ANDROID_SERIAL=emulator-5554 ./gradlew :feature:onboarding:connectedDebugAndroidTest` (rc=0) | Android API 36 (`dev.quietinbox.feature.onboarding.test`) | 1 / 0 / 0 | `141e2203edbb8bc2b6495b42149cffb2184cabd623a0b2bc434df7ce8d1f3f4f` |
| `pr40/logcat-SourcesStepSemanticsTest.txt` | `15591ceb86140e0d0fbd12861f65d0a85ff998a3` | `SourcesStepSemanticsTest.sourcesStepRendersHonestTierPerChoice` | Captured by Android Test Runner (rc=0) | Android API 36 (`emulator-5554`) | N/A (Logcat) | `f08e73a3a03bfce9d0b5f0f82f7ddca91a852805a2347133810dba0c594915c8` |
| `pr40/TEST-feature-health-SourceVerificationTagSemanticsTest.xml` | `15591ceb86140e0d0fbd12861f65d0a85ff998a3` | `dev.quietinbox.feature.health.SourceVerificationTagSemanticsTest` + `PolicyFailureDialogTest` | `ANDROID_SERIAL=emulator-5554 ./gradlew :feature:health:connectedDebugAndroidTest` (rc=0) | Android API 36 (`dev.quietinbox.feature.health.test`) | 10 / 0 / 0 | `16f5c4d2f8163927e47c4d690bdbd8f7205ccb08fe6181e0d737e888ee0b7d52` |
| `pr40/logcat-SourceVerificationTagSemanticsTest.txt` | `15591ceb86140e0d0fbd12861f65d0a85ff998a3` | `SourceVerificationTagSemanticsTest.addSourceSheetRendersHonestTierForSyntheticAndVerifiedSources` | Captured by Android Test Runner (rc=0) | Android API 36 (`emulator-5554`) | N/A (Logcat) | `28e689b9c070464626786c885e3f754e7f340b53413f63e323c70db127c094ae` |

### PR #41 Artifacts (`evidence/20260913-round50/pr41/`)

| File | Source Commit SHA | Test Class / Scope | Command & Return Code | Platform / Target | Tests (Exec/Skip/Fail) | SHA-256 Digest |
|---|---|---|---|---|---|---|
| `pr41/TEST-dev.quietinbox.platform.capture.CaptureCoordinatorTest.xml` | `f279c895e774f41257e900cee0cdd5b336175d4e` | `dev.quietinbox.platform.capture.CaptureCoordinatorTest` | `./gradlew :platform:capture:testDebugUnitTest --tests 'dev.quietinbox.platform.capture.CaptureCoordinatorTest'` (rc=0) | JVM Robolectric / Robolectric Unit (JDK 21) | 85 / 0 / 0 | `4579342222c96fe5640ab7280a72d78a95fb36ab92c0e49930e60046c4e885f5` |
| `pr41/TEST-platform-storage-JournalLossTransactionTest.xml` | `f279c895e774f41257e900cee0cdd5b336175d4e` | `dev.quietinbox.platform.storage.JournalLossTransactionTest` (scoped class, not full storage suite) | `ANDROID_SERIAL=emulator-5554 ./gradlew :platform:storage:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.quietinbox.platform.storage.JournalLossTransactionTest` (rc=0) | Android API 36 (`dev.quietinbox.platform.storage.test`) | 50 / 0 / 0 | `5f75d4e59185da05d4e27768cc7c58a0d5c1736c9af801cb95d8a904a74eab4a` |
| `pr41/logcat-realCoordinatorAndRoomReplayFailingDeferral.txt` | `f279c895e774f41257e900cee0cdd5b336175d4e` | `realCoordinatorAndRoomReplayFailingDeferralExitsBoundedAndPreservesPayloadAndAttempts` | Captured by Android Test Runner (rc=0) | Android API 36 (`emulator-5554`) | N/A (Logcat) | `795c082ca043f6e35aec703e26a2d2d6a424358c8304c1f50202d9ead388010a` |
| `pr41/logcat-concurrentCoroutinesCallingTerminal.txt` | `f279c895e774f41257e900cee0cdd5b336175d4e` | `concurrentCoroutinesCallingTerminalDoNotDuplicateGapsOrDeadlock` | Captured by Android Test Runner (rc=0) | Android API 36 (`emulator-5554`) | N/A (Logcat) | `b240a634bbd2fba8cb4859d6509955386bbf09c63cfd286b6c88a698ae7cfd31` |
| `pr41/logcat-missingReadySignalTimesOut.txt` | `f279c895e774f41257e900cee0cdd5b336175d4e` | `missingReadySignalTimesOutAndCleansUpWorkersPromptly` | Captured by Android Test Runner (rc=0) | Android API 36 (`emulator-5554`) | N/A (Logcat) | `83e205935776cb354b598cd5e350765595fbd6a6b5f6ec34e2decaebc74c41f1` |

### Negative Controls (`evidence/20260913-round50/negative-controls/`)

| File | Target / Scope | Description | SHA-256 Digest |
|---|---|---|---|
| `negative-controls/ui-mutation-negative-control.patch` | `OnboardingScreen.kt` & `HealthScreen.kt` | Forces `REAL_DEVICE_PASSED` in `SourcesStep` and `AddSourceSheet` mapping | `e7bda5d6ab689700abb30d124e0ab6ce0097d19b477846ad6e4273a908fc5df7` |
| `negative-controls/ui-mutation-negative-control-failure.txt` | `SourcesStepSemanticsTest` & `SourceVerificationTagSemanticsTest` | Failure output proving assertion fails (RED) with `Expected exactly '1' node` | `e38d8e58d033d2cbe315acf484c09a2b44dd34cd981f513f5caa7a4a2ece5896` |
| `negative-controls/capture-stopping-condition-negative-control.patch` | `CaptureCoordinator.kt:1295` | Mutates stopping condition from `progressed = batch.rawAdvanced` to `progressed = true` | `768aeab4c9bc78b54edc14252042c0764459a98e2ed6a273d3e6e10bfb18a89d` |
| `negative-controls/capture-stopping-condition-negative-control-failure.txt` | `JournalLossTransactionTest` | Failure output proving `pageCallCount.get() shouldBe 1` catches runaway replay loop (`expected:<1> but was:<199>`) | `df54e71b2e36b97fcea7c5c74a1b0e8e71c0427d30cd50ec3522ab62cfc9bdad` |

---

## 4. Tested APK Binaries

For connected Android instrumented tests on AGP library modules, tests run via self-instrumenting test runner APKs.

| Test Run / Scope | Tested APK File Path | Package Name | SDK (Min / Target / Compile) | File Size (Bytes) | SHA-256 Digest |
|---|---|---|---|---|---|
| `pr40/TEST-feature-health-...xml` | `feature/health/build/outputs/apk/androidTest/debug/health-debug-androidTest.apk` | `dev.quietinbox.feature.health.test` | Min 26 / Target 37 / Compile 37 | 35,379,703 | `6cce8e1fecf5f49533370d36b0f3f9ac5be762963af64ccd546ef49ad63dd8ff` |
| `pr40/TEST-feature-onboarding-...xml` | `feature/onboarding/build/outputs/apk/androidTest/debug/onboarding-debug-androidTest.apk` | `dev.quietinbox.feature.onboarding.test` | Min 26 / Target 37 / Compile 37 | 34,917,262 | `797ee0ef0b13409b73d69377fbc2ecd4b4aaadf0968f0d08149f51b40a87c6a9` |
| `pr41/TEST-platform-storage-...xml` | `platform/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk` | `dev.quietinbox.platform.storage.test` | Min 26 / Target 37 / Compile 37 | 21,171,294 | `cbe432083a4759e5e083798abc81ffb27eb2fb64ecc735e3431a46d005b1946a` |
| `pr40/TEST-dev.quietinbox.core.model.*.xml` | N/A (JVM Host Unit Test) | `dev.quietinbox.core.model` | JDK 21 (macOS) | N/A | N/A (Host JVM Execution) |
| `pr41/TEST-dev.quietinbox.platform.capture.*.xml` | N/A (Robolectric Host Unit Test) | `dev.quietinbox.platform.capture` | JDK 21 (macOS) | N/A | N/A (Robolectric Execution) |

---

## 5. Status of Historical Test Evidence

Any prior claims from rounds before round 50 that cannot be matched to the exact files and digests above remain strictly **UNVERIFIED**. No historical records were fabricated, back-dated, or re-labeled.

