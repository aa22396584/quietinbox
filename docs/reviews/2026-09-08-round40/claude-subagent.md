# Round 40 — Claude subagent 審查報告

範圍：`97e631d..866b2d6`（`32abcfc`、`37da486`、`866b2d6`）於 `main`。
方式：唯讀審查 + 在 `emulator-5554`（Pixel_9 AVD, API 36）上實跑 JVM 與 instrumented 測試。
未動任何產品程式碼，未執行任何 git 寫入指令。

---

## 結論

**APPROVE WITH MINOR FIXES**

Brief 的七個攻擊點全部成立且有測試背書；我實跑了全部相關 JVM 與 instrumented 測試，**沒有任何一個失敗**。
沒有 Critical。唯一的 Important 是一行可修的文件數字錯誤；其餘全部是 Minor，沒有一個會改變執行時行為。

---

## 我實際跑過的證據（非引用，非推論）

| 指令 | 結果 |
|---|---|
| `:platform:media :platform:backup :feature:health :platform:storage :compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL |
| `:feature:health:testDebugUnitTest :platform:capture:testDebugUnitTest :feature:onboarding:testDebugUnitTest :core:model:test --rerun-tasks` | BUILD SUCCESSFUL（強制重跑，非 UP-TO-DATE） |
| `:platform:backup:connectedDebugAndroidTest` | 12 tests / 0 failures / 0 skipped |
| `:platform:media:connectedDebugAndroidTest`（`--no-parallel`） | 2 tests / 0 failures（**第三次**；前兩次 Gradle 任務 FAILED 而 report 全綠，見 M-3） |
| `:feature:health:connectedDebugAndroidTest` | 6 tests / 0 failures |
| `:platform:storage:connectedDebugAndroidTest` | 63 tests / 0 failures |
| `python3 tools/check-strings.py` | `OK: 0 error(s), 0 warning(s)` |
| `tools/check-instrumented.sh platform/backup platform/media feature/health platform/storage` | 全部 OK（對真實 XML 驗證新 gate 本身可用） |

XML 逐檔實測數字，與文件宣稱逐一比對：

| 測試類別 | 實測 | 文件宣稱 | |
|---|---|---|---|
| `BackupCancellationTest` | 3 | 3（`docs/TEST_MATRIX.md:20`） | ✅ |
| `BackupRejectionTest` | 6 | 6（`docs/TEST_MATRIX.md:21`） | ✅ |
| `MediaDeletionRaceTest` | 2 | 2（`docs/TEST_MATRIX.md:22`） | ✅ |
| `PolicyFailureDialogTest` | 6 | 6（`docs/TEST_MATRIX.md:24`） | ✅ |
| `HealthViewModelTest` | 8 | 8（`docs/TEST_MATRIX.md:24`） | ✅ |
| `CaptureCoordinatorTest` | 76 | 76（`docs/TEST_MATRIX.md:29`） | ✅ |
| `OnboardingViewModelTest` | 6 | 6（`docs/TEST_MATRIX.md:32`） | ✅ |
| `JournalLossTransactionTest` | 32 | 32（round 39 的更正正確） | ✅ |
| `platform:storage` instrumented 合計 | 63 | 63（`docs/TEST_MATRIX.md:18`） | ✅ |
| `SearchNormalizerTest` | 6 | —（無數字宣稱） | ✅ |

**但**：`docs/SCOPE.md:20` 與 `docs/zh-Hant/SCOPE.md:18` 仍寫 `CaptureCoordinatorTest (52)`（見 Important-1）。

---

## 逐項對照 brief 的七個攻擊點

### 1. Policy-failure honesty（Codex I1）—— 成立

`CaptureCoordinator.changeSourcePolicy`（`platform/capture/.../CaptureCoordinator.kt:484-508`）把 write 與 reload 拆成兩段 try：

- `block()` 拋例外 → `PolicyChangeException.Refused` / `SettlementRefused`；
- `CancellationException` 與 `VaultUnavailableException` 原型別穿透（`:493`）；
- `loadSourcePolicy()` 失敗 → 再試一次 → `CommittedNotReloaded`（`:502-505`）。

`HealthViewModel.policyChange`（`feature/health/.../HealthViewModel.kt:135-152`）只依例外型別分類，`else -> UNKNOWN`。
**「一個泛型 `IllegalStateException` 不得使用 rollback 文案」有直接測試**（`HealthViewModelTest.kt` 的
`an unclassified throw does not become a rollback`，同時斷言 `shouldNotBe REFUSED` 與 `shouldNotBe SETTLEMENT`），
且我實跑通過。`PolicyFailureDialog.kt:53-59` 五種 `Kind` 各自映射到自己的 string resource，`PolicyFailureDialogTest`
以 `assertCountEquals(0)` 反向釘住「不得出現 rollback 文案」，6 個測試實機通過。

我額外檢查了 **語意**（不只 `check-strings.py` 的名稱／placeholder 對齊）：
`values-ja:199-201`、`values-ko:199-201`、`values-b+zh+Hans:199-201` 的 `_locked` / `_reload` / `_unknown`
三條在三個語系裡都沒有承諾「沒有任何變更」：

- `_reload`（ja/ko/zh-Hans）皆為「變更已儲存，但讀不回來源清單，重啟才會重讀」，語意正確；
- `_unknown` 皆為「無法完成，請確認清單中目前的設定」，沒有 rollback 承諾；
- `_locked` 說「沒有做這項變更」，而金庫鎖定確實在任何 transaction 之前拋出，成立。

一行反向改動即可讓測試變紅：把 `HealthViewModel.kt:150` 的 `else -> UNKNOWN` 改回 `REFUSED`，
或把 `PolicyFailureDialog.kt:58` 的 `UNKNOWN -> health_policy_failed_body_unknown` 改回 `..._body`。

### 2. CI JVM list（Codex M1）—— 成立

`.github/workflows/ci.yml:40` 已含 `:feature:health:testDebugUnitTest`。
確認 `:app:testDebugUnitTest` 不會帶到它：`feature/health` 是獨立 module，其 `testDebugUnitTest` 只有被點名才會跑。

### 3. `pendingExcluding`（Codex M2）—— 成立

`JournalLossTransactionTest.kt:754-766` 新增段落：加入 `com.example.paused` 的 `evt-p0`(0) 與 `evt-p1`(1)，
先斷言無排除時四筆都是候選，再以 `pendingJournal(excludingPackages = listOf(paused))` 斷言只剩 `evt-0, evt-1`。
三項要求全部覆蓋：被排除來源的 0/1 消失、included 來源的 0/1 仍在、included 來源被停放的 2/3 仍不在。
一行反向改動：拿掉 `Daos.kt:73` 的 `lossRecorded < 2` → `evt-2/evt-3` 冒出來；拿掉 `packageName NOT IN`
→ `evt-p0/evt-p1` 冒出來。兩者都會讓測試紅。實跑通過。

### 4. Restore interruption —— 成立

`BackupService.apply`（`platform/backup/.../BackupService.kt:444` 的 `writtenFiles.removeAll(usedFiles)`）
移到 `withTransaction` **內部**、`restoreProbe(IN_TRANSACTION)` 之後。三條出口路徑我逐一推過：

- transaction 內拋出（在 trim 之前）→ rows rollback，`writtenFiles` 仍是全部 → `catch` 全刪。✅
- `withTransaction` 回傳後取消（`:447` 的 `AFTER_COMMIT`）→ 清單已 trim → 只刪未被引用的。✅
- trim 之後、commit 之前失敗 → 已連結檔案殘留成孤兒檔，`:436-443` 的註解明白承認這是 leak 不是 loss，
  交給 retention sweep。誠實且可接受。

`BackupCancellationTest` 三個測試對應這三條，並且第一個測試跑兩次匯入（第二次是重複資料）以證明
「只刪那一個沒有 row 的 blob」。實機 3/3 通過。

**空 base64 的部分我實查了 AOSP 原始碼**（`$ANDROID_HOME/sources/android-35/android/util/Base64.java:184-201`）：
`DECODE` 表把所有非字母表字元映射為 `SKIP(-1)`，只有 `'='` 是 `EQUALS(-2)`；`'%'`(ASCII 37) 落在 `-1`。
因此 `Base64.decode("%%%%", NO_WRAP)` **不會拋 `IllegalArgumentException`，而是回傳空陣列**。
所以 `BackupService.kt:321` 的註解與 `bytes.isEmpty()` 這道 guard 是正確的，
且 `aBlobTheVaultCannotWriteLeavesItsMessageMarkedAndIsCountedInTheResult` 真的走到 `isEmpty()` 分支。
反向控制成立：拿掉 `isEmpty()` → `blob != null` → `BackupService.kt:414-418` 會插入 `media_blob` 並
`setMedia(newId, LOCAL_COPY, blobId)`，測試的三項斷言（`FAILED`、`mediaNotRestored == 1`、`mediaFiles() 為空`）全部會紅。

`BackupRejectionTest` 的 `vaultFingerprint()` 比對每個 table 的 row count **與每個媒體檔的位元組內容**，
六種拒絕情境全部前後一致，實機 6/6 通過。`aFileCutInsideTheHeader` 對應 `BackupService.kt:258` 新增的
`read < header.size` 檢查，反向控制明確（拿掉該行 → reason 變 `WRONG_KEY_OR_TAMPERED`，測試紅）。

### 5. Media deletion race —— 成立

`MediaDao.setMedia` 改回傳 `Int`（`Daos.kt:416-418`），`MediaCopier.store` 的 `:192-195` 在
`!= 1` 時於同一 transaction 內刪掉剛插入的 blob 列並回傳 `false`；因為 `written.clear()` 只在成功連結時執行，
`finally` 會把檔案一併刪掉。回傳 `MediaState.FAILED` 給 `copyPending`，其後的 `setMedia(id, FAILED, null)`
影響 0 列（訊息已不存在），無副作用。

**兩個 instrumented 測試都以 `Unit` 結尾**（`MediaDeletionRaceTest.kt:114`、`:126`），`@Before`/`@After`
也都回傳 `Unit`，符合 JUnit4 的要求。實機 2/2 通過。反向控制如註解所述成立。

`platform/media/build.gradle.kts` 的 `androidTestImplementation(project(":core:testing"))` 與
`platform/backup` 完全同型；`quietinbox.android.library` convention 已提供 runner 與
`androidx-test-ext-junit`，`:platform:storage` 以 `api` 傳遞 `core:parser/identity/reconcile`，
編譯與執行皆已實證。

### 6. Lock-out gap order（agy M1）—— 成立

`CaptureCoordinator.kt:1015-1029`：`vaultGapOpen` 與 `health.openGap` 先做，
`_status.update { vaultLocked = true, DEGRADED }` 移到最後一行。順序正確。

### 7. 每個新測試的反向控制與文件數字 —— 大致成立

新測試的一行反向改動我逐一列在上面各節，全部可讓對應測試變紅。
文件數字全部核對過（見上表），只有 `docs/SCOPE.md` 的 `(52)` 是錯的（Important-1）。
`CHANGELOG.md` 的 Unreleased 段落敘述與程式碼一致，我逐條比對了 ATOM-2/3/4、MED-7、O8、I1，
沒有發現宣稱超前實作的地方。`fastlane` 五語系 full_description 皆遠低於 4000 字元上限。

---

## Critical

無。

---

## Important

### I-1 `docs/SCOPE.md:20` / `docs/zh-Hant/SCOPE.md:18` 宣稱 `CaptureCoordinatorTest (52)`，實測 76

```
docs/SCOPE.md:20  | ... `CaptureCoordinatorTest` (52) |
docs/zh-Hant/SCOPE.md:18 | ... `CaptureCoordinatorTest`（52） |
```

實測 76（`platform/capture/build/test-results/testDebugUnitTest`：`tests=76 failures=0`），
`docs/TEST_MATRIX.md:29` 也已寫 76。此數字在 `97e631d` 時就已是 52（當時實際 73），
本區間又 +3 卻只更新了 TEST_MATRIX，差距擴大到 24。

`CLAUDE.md` 明文要求「Re-read `docs/SCOPE.md`, `CHANGELOG.md`, `docs/TEST_MATRIX.md` counts after adding tests；
reviewers flagged "docs ahead of code" in every round」，SCOPE 是被點名的三份之一。
兩行改成 `(76)` / `（76）` 即可。**不是 push blocker，但屬於專案自訂規則的直接違反，建議一併修掉。**

---

## Minor

### M-0 `CaptureCoordinator.kt:489` 的「Every change is one repository transaction」在 `removeSource` 上不成立，只是剛好不會爆

`Refused` 的文案承諾「沒有任何變更；這個 App 維持原狀」，其真偽完全依賴這句註解的前提。但：

- `SourceRepository.remove`（`platform/storage/.../SourceRepository.kt:97-114`）在 `withTransaction`
  **之後**還跑 `for (f in files) mediaDir.delete(f)`（`:113`）。若此處拋例外，
  `changeSourcePolicy` 會把它包成 `Refused`，而來源列與資料其實已經刪掉了 —— 對話框就會說謊。
- 目前之所以不會發生，只因為 `MediaDirectory.delete`（`platform/storage/.../retention/RetentionWorker.kt:161-163`）
  把 `File.delete()` 包在 `runCatching {}` 裡完全吞掉。這個不變量寫在**另一個檔案的另一個類別**裡，
  `changeSourcePolicy` 這一側既沒有測試也沒有註解記錄它。
- 另外 `SourceRepository.enable`（`:26-41`）是 `get` + `upsert` 兩個獨立語句、不在 transaction 內，
  嚴格說也不是「one transaction」（實務上只有一次寫入，結論仍成立）。

**今天不會觸發，不是 push blocker。** 但只要日後有人把 `MediaDirectory.delete` 改成會回報失敗
（例如「刪不掉要記 diagnostic」），`Refused` 的誠實承諾就會靜默失效，而這正是 Codex I1 要根除的那類問題。
建議在 `CaptureCoordinator.kt:489` 的註解補一句點名 `SourceRepository.remove` 的 post-commit 檔案刪除
必須維持不拋例外，或在 `remove` 內把該迴圈包起來。

### M-1 `platform/media/.../MediaCopier.kt:135-141`：`store()` 的 KDoc 被 `beforeLink` 搶走

```kotlin
135    /**
136     * Writes the blob (and thumbnail), then links row and message in one transaction. ...
137     */
138    /** Test seam: runs after the files are written and before the linking transaction; ... */
139    internal var beforeLink: suspend (messageId: Long) -> Unit = {}
140
141    private suspend fun store(messageId: Long, bytes: ByteArray, mimeType: String?): MediaState {
```

兩個連續 KDoc 區塊時，Kotlin 只把最後一個當作宣告的文件。結果是：`store()` 現在**沒有 KDoc**，
而 `beforeLink` 掛著一段描述 `store()` 的文件。把 `:135-137` 移到 `:140` 之後即可。
（`beforeLink` 是 `internal var` 且 production 從不設值，`platform:media` 的 androidTest 為 friend module，
可見性正確；只是缺 `@VisibleForTesting` 標註。`BackupService.restoreProbe:467` 同型但 KDoc 位置正確。）

### M-2 `tools/check-instrumented.sh:18`：不看 `skipped=`，全數 `@Ignore` 的 module 會通過這道 gate

```bash
18  if [[ $files -eq 0 || $total -eq 0 ]]; then ... elif [[ $bad -ne 0 ]]; then ...
```

`tests="N"` 包含被 skip 的測試，`failures`/`errors` 都是 0，所以一個把整個 class `@Ignore` 掉的
module 會被判為「OK: ran N instrumented tests」。這道 gate 是為 round 39 subagent M5 的
「crash 前 runner 沒起來、Gradle 卻綠」而生，方向對；但 `CLAUDE.md` 的 no-fake-completion 規則
也把 `test.skip` 列為 blocker，同一支腳本順手擋掉成本極低：把 `skipped` 一起解析，
在 `skipped == total` 或 `skipped > 0` 時報錯。

### M-3 `.github/workflows/ci.yml:112`：instrumented lane 在單一 emulator 上跑六個 module，我本機出現兩次「任務紅、報告全綠」

`gradle.properties:2` 是 `org.gradle.parallel=true`，CI 的 emulator step 在同一次 invocation 裡
點名六個 module（本次由五個增為六個，新增 `:platform:media`），沒有 `--no-parallel` 或 `--max-workers=1`。

我在本機同一顆 `emulator-5554` 上跑了三次，如實記錄（**這三次各自改動了不只一個變因，因此下面是觀察，不是已隔離的因果**）：

| 次 | 條件 | 結果 |
|---|---|---|
| 1 | `:platform:backup` + `:platform:media` 同一次 invocation（Gradle 平行） | `:platform:media` **FAILED**，但其 report 是 `2 tests 0 failures 100% successful`、`test-result-exit-code.txt` 為 `0` |
| 2 | `:platform:media` 單獨 + `--rerun-tasks`（123 tasks 全重跑），**同時另一個 Gradle daemon 在跑 JVM `--rerun-tasks`** | **FAILED**，log 寫出 `Skipping device 'Pixel_9(AVD)' for ':platform:media:': Unknown API Level` + `No compatible devices connected.`，其上是一段 ddmlib `PropertyFetcher` 的 timeout stack |
| 3 | `:platform:media` 單獨 + `--no-parallel`，主機上沒有其他 build | **BUILD SUCCESSFUL**，2/2 綠 |

可以確定的是：**測試本身在三次裡都是綠的**（第 1、2 次的 report 與 XML 都是 0 failures），
紅的是 Gradle 任務層的裝置偵測。可以確定的**不是**哪一個變因造成的 —— 三次之間至少有三個候選同時在變：

- (a) 兩個 `connectedDebugAndroidTest` 平行搶同一顆 emulator（只有第 1 次符合）；
- (b) 主機負載（第 2 次有第二個 Gradle daemon 在做 `--rerun-tasks`）拖垮 ddmlib 的 property fetch timeout；
- (c) adb 上同時掛著三台裝置（一台實體手機 + 兩顆 emulator），三次都相同，所以不是唯一解釋。

唯一成功的第 3 次同時排除了 (a) 與 (b)。`--no-parallel` 是**最便宜的一個變因**，不是已證實的病因。

`tools/check-instrumented.sh` 在這個狀態下會回報 OK（它讀的 XML 是綠的），所以不會有假綠；
但整個 instrumented job 會因為假紅而失敗、而 report 顯示 100% pass，很難 debug。
CI 是 Linux/KVM + `--no-daemon` + 單一裝置，行為未必相同，我沒有在 CI 上重現。
既然這條 lane 剛被本次 commit 加寬，建議先在 `ci.yml:112` 與 `CLAUDE.md:28-33` 的本機 recipe 加上
`--no-parallel`（或 `--max-workers=1`）觀察一輪，成本近乎零；若之後仍出現，再往 (b)/(c) 查。

### M-4 `platform/backup/.../BackupService.kt:283`：`freeBytes()` 是 `importNow` 裡唯一沒有被 try/catch 包住的平台呼叫

`importNow` 的契約是「任何失敗都變成 `BackupResult`」—— 上面每一段 IO/解密/staging 都有對應的 catch。
但 `:283` 的 `val free = freeBytes()` 落在所有 try 之外，而預設實作是
`android.os.StatFs(...)`（`:294`），其建構子在 `statvfs` 失敗時會拋 `IllegalArgumentException`。
實務上 `mediaDir.dir.parentFile` 就是 `filesDir`，永遠存在，所以今天不會觸發；
但這是該函式唯一一個能讓例外逃出 `maintenance.exclusive {}` 的點。包一層 `runCatching`
並在失敗時當作「空間未知、放行」或回 `Failed(IO)` 都比現在乾淨。

### M-5 `platform/backup/.../BackupService.kt:399`：匯出時就被略過的媒體，還原後仍是無限定詞的「Done」

```kotlin
397    mediaNotRestored++          // media 在檔案裡但寫不進金庫 → 有計數
399    if (media == null && m.mediaState == MediaState.LOCAL_COPY.name) mediaState = FAILED   // 沒有計數
```

第二種情況是「訊息說自己有本地副本，但備份檔裡根本沒有那筆 media 記錄」（匯出端因讀不到／過大而略過，
當時是用 `skippedMedia` 告訴**匯出**的使用者）。還原端的使用者會看到若干列被標成 `FAILED`，
而結果行只有「完成：N 個對話、M 則訊息、0 個媒體」，沒有任何限定詞。

`CHANGELOG.md` 對 ATOM-3 的說法是「A restore that lost media said "Done"」，這個相鄰情境仍然如此。
列本身有 `FAILED` 標記（符合「gaps are shown, never hidden」的最低要求），所以我列為 Minor 而非 blocker，
但建議記成 audit-2 的後續項：要嘛一併計入，要嘛在 `BackupService.kt:399` 註明為何刻意不計。

### M-6 `docs/TEST_MATRIX.md:18` 與 `docs/zh-Hant/TEST_MATRIX.md:18`：括號未閉合

兩份檔案的同一行，`SourcePolicyTransactionTest (13:` / `（13 個:` 這個外層括號從未關閉
（英文版 10 開 9 閉、中文版 12 開 11 閉，我用腳本數過）。純排版，讀起來會以為 `JournalLossTransactionTest`
還在 `SourcePolicyTransactionTest` 的括號裡。

### M-7 `feature/health/.../HealthViewModel.kt:18`：import 插在 `platform.capture` 群組中間

```
16 import dev.quietinbox.platform.capture.CaptureCoordinator
17 import dev.quietinbox.platform.capture.PolicyChangeException
18 import dev.quietinbox.platform.storage.db.VaultUnavailableException   ← 應在 :24 附近
19 import dev.quietinbox.platform.capture.CaptureStatus
```

破壞了本檔其餘部分維持的字母序（`:24` 已有 `platform.storage.db.VaultState`）。純風格，Lint 不會抓。

### M-8 `feature/onboarding/.../OnboardingViewModel.kt:142`：屬性宣告夾在兩個函式之間、且用完整套件名

```kotlin
142    private var testTimeout: kotlinx.coroutines.Job? = null
```

`kotlinx.coroutines.Job` 沒有加 import（本檔已 import 其他 `kotlinx.coroutines.*` 成員），
且屬性被放在使用它的 `sendTest()` 與下一個函式中間，而非類別頂端。行為正確
（`OnboardingViewModelTest` 的重試測試我實跑通過，且反向控制成立：拿掉 `testTimeout?.cancel()`，
第一個 timer 會在 `advanceTimeBy(TEST_TIMEOUT_MS/2 + 1)` 內觸發並把 `testFailed` 設成 true），純風格。

---

## 追蹤事項（不作為本次 push 的阻擋）

1. **PARSE_/DECODE 留在 PENDING 而不記 gap**：依 brief 指示不在本區間追究。我在通讀
   `CaptureCoordinator` 的 commit 路徑時沒有看到本區間新引入的相關退化。
2. **M-5**（匯出端就略過的媒體，還原後不計數）建議開成 audit-2 的後續 issue。
3. **M-3** 的 instrumented 假紅（本機三次中兩次，病因未隔離）若在 CI 上出現，會表現為
   「job 紅、report 100% pass」。建議先在 `ci.yml` 加 `--no-parallel` 觀察一輪，這是最便宜的一個變因。

---

## 為什麼這一輪可以過

三個 commit 的核心都是把「畫面能承諾什麼」綁回「程式碼能證明什麼」：
`PolicyChangeException` 讓 write/reload 的接縫變成型別、`writtenFiles.removeAll` 移進 transaction 讓
「取消時該留哪些檔」由 commit 邊界而非一個事後旗標決定、`setMedia` 回傳列數讓連結零列成為可觀測事件、
lock-out gap 先開再公告讓「立刻重開的金庫」也關得到。每一項都有一個能被一行產品改動弄紅的測試，
而我把那些測試全部實跑過，沒有一個紅。剩下的問題沒有一個會改變執行時行為。
