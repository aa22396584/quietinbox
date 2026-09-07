# Round 40 審查報告：來源政策失敗分類、還原中斷防護與測試矩陣對抗性審查

審查範圍：`97e631d..866b2d6`（包含 `32abcfc`、`37da486`、`866b2d6` 三個 commit）。
審查路徑：`/Users/iml1s/Documents/mine/quietinbox`。
審查模式：READ-ONLY（未修改產品代碼，未執行任何 git write 命令）。

---

## 審查結論 (Verdict)

### **APPROVE**

本次 commit 範圍（`97e631d..866b2d6`）精確落實了 Round 39 遺留的所有重要事項與 audit-2 批次項目：
1. **來源政策失敗誠實性（Codex I1）**：在寫入與重載接縫處建立了明確的 `PolicyChangeException` 階層，徹底杜絕了在重載失敗、金庫鎖定或未知例外時向使用者承諾「未作任何變更（nothing was changed）」的虛假文案；ViewModel 與 Compose Dialog 均以資源字串實體驅動，且涵蓋 5 種語言資源目錄。
2. **CI JVM 測試矩陣完整性（Codex M1）**：在 `.github/workflows/ci.yml` 的 `jvm-tests` 任務中明確補上了 `:feature:health:testDebugUnitTest`。
3. **四值日誌狀態候選讀取（Codex M2）**：`JournalLossTransactionTest.theCandidateReadsAgreeOnAllFourValues` 補齊了非空排除清單（`excludingPackages`）調用，嚴格驗證包含來源的 0/1 是候選、停放的 2/3 非候選、且被排除來源的 0/1 列完全消失。
4. **還原中斷原子性（Restore Interruption）**：還原過程的檔案修剪（`writtenFiles.removeAll(usedFiles)`）移至 Room 交易內部；提交後中斷保留關聯檔案並清理孤兒檔，交易內中斷完整回滾資料列並清除所有檔案；無效 Base64（空位元組）不再被誤標為 `LOCAL_COPY`。
5. **媒體刪除競態（Media Deletion Race）**：`MediaCopier.store` 檢查 `setMedia` 更新筆數，在訊息已刪除（更新 0 列）時即時撤銷 `media_blob` 並刪除磁碟檔案；測試均正確回傳 `Unit`。
6. **鎖定缺口開放順序（agy M1）**：`CaptureCoordinator` 在拋出 `VaultUnavailableException` 時，先設置 `vaultGapOpen = true` 與記錄缺口，最後才發布 `_status.update { vaultLocked = true }`，消除了金庫即時重新開啟時漏記缺口的競態。
7. **測試對抗性與文檔精確度**：每個新增／修改測試均能被單行產品代碼更動擊垮；`TEST_MATRIX.md`（英文與繁中）及 `CHANGELOG.md` 記載之測試計數與行為與現有代碼庫 100% 吻合。

---

## 問題分級清單 (Findings)

### Critical
無。

### Important
無。

### Minor (觀察與提示)

1. **`tools/check-instrumented.sh:12` 正則表達式匹配 `<testsuites>` 根節點的行為特性**
   - **檔案位置**：[`tools/check-instrumented.sh:12-15`](file:///Users/iml1s/Documents/mine/quietinbox/tools/check-instrumented.sh#L12-L15)
   - **現象說明**：在 `tools/check-instrumented.sh` 中：
     ```bash
     t=$(sed -n 's/.*<testsuite[^>]* tests="\([0-9]*\)".*/\1/p' "$f" | head -1)
     fl=$(sed -n 's/.*<testsuite[^>]* failures="\([0-9]*\)".*/\1/p' "$f" | head -1)
     er=$(sed -n 's/.*<testsuite[^>]* errors="\([0-9]*\)".*/\1/p' "$f" | head -1)
     ```
     正則模式 `<testsuite[^>]*` 會前綴匹配到 AGP 輸出的根節點 `<testsuites tests="N" failures="0" ...>`（因為 `s` 屬於 `[^>]*`）。配合 `head -1`，這剛好直接抓到了整個 XML 的彙總數值（而非僅第一個 `<testsuite>` 子節點），因此在多 testsuite 的檔案中也能正確統計全體數字。
   - **建議**：雖然目前在 Android Gradle Plugin 的標準輸出格式下完全正常工作，但若未來解析器或格式變更，建議可將 pattern 明確寫為 `<testsuite[s ]` 或使用 xml/xpath 工具解析，以避免對字串前綴匹配的隱式依賴。

2. **`HealthViewModel.kt:135` 對 `Throwable` 的捕捉邊界**
   - **檔案位置**：[`feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthViewModel.kt:137`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/HealthViewModel.kt#L137)
   - **現象說明**：`policyChange` 內部採用 `catch (e: Exception)`。如果協調器端發生非 `Exception` 的 `Throwable`（例如 `AssertionError`），該錯誤會直接往外拋至協調器 CoroutineExceptionHandler 而非進入 `PolicyFailure.Kind.UNKNOWN`。這符合標準 Kotlin 慣例（不捕捉 Error），無需修改，僅在此記錄。

---

## 專題深入審查與對抗性驗證

### 1. 來源政策失敗誠實性 (Codex I1)

#### (1) 協調器寫入 vs 重載接縫分類
在 [`CaptureCoordinator.kt:483-507`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L483-L507)：
```kotlin
private suspend fun changeSourcePolicy(block: suspend () -> Unit) {
    pipelineMutex.withLock {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException || e is VaultUnavailableException) throw e
            throw if (e is SettlementFailedException) PolicyChangeException.SettlementRefused(e) else PolicyChangeException.Refused(e)
        }
        try {
            loadSourcePolicy()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            runCatching { loadSourcePolicy() }.onFailure {
                if (it is CancellationException) throw it
                throw PolicyChangeException.CommittedNotReloaded(it)
            }
        }
    }
}
```
- **交易回滾（Rollback）**：`block()` 內拋出的例外（非取消且非金庫鎖定）保證是交易回滾，分類為 `PolicyChangeException.SettlementRefused`（若為結清走訪拋出）或 `PolicyChangeException.Refused`。
- **重載失敗（Committed, Reload Failed）**：若 `block()` 成功提交但 `loadSourcePolicy()` 連續兩次失敗，則明確拋出 `PolicyChangeException.CommittedNotReloaded`。
- **金庫鎖定（Locked Vault）**：在交易開始前金庫鎖定拋出的 `VaultUnavailableException` 會原樣向外傳遞，不會被偽裝成政策變更拒絕。

#### (2) UI 誠實性與對話框文案承諾
在 [`HealthViewModel.kt:144-152`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/HealthViewModel.kt#L144-L152) 與 [`PolicyFailureDialog.kt:47-62`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/PolicyFailureDialog.kt#L47-L62)：
- `PolicyFailure.Kind.COMMITTED_NOT_RELOADED` 對應 `R.string.health_policy_failed_body_reload`：
  > *"The change was saved, but the app could not read its source list back, so capture may still follow the previous setting until the vault next opens. Restarting the app does that."*
  明確向使用者告知**「變更已儲存」**，絕不承諾「未作任何變更」。
- `PolicyFailure.Kind.LOCKED` 對應 `R.string.health_policy_failed_body_locked`：
  > *"The vault is locked, so the change was not made. Unlock it and try again."*
  告知金庫已鎖定，不使用回滾文案。
- `PolicyFailure.Kind.UNKNOWN` 對應 `R.string.health_policy_failed_body_unknown`：
  > *"This change could not be completed. Check the app’s current setting in the list and try again."*
  任何協調器拋出的通用 `IllegalStateException`（未分類例外）均落入 `UNKNOWN`，絕不承諾「未作任何變更」。
- `PolicyFailure.Kind.REFUSED` 與 `SETTLEMENT`：僅在確信已回滾時，才使用標明「沒有任何變更」的 `health_policy_failed_body` 或說明結清損失原因的 `health_policy_failed_body_settle`。
- 上述 6 個字串資源在 `values/`、`values-b+zh+Hans/`、`values-b+zh+Hant/`、`values-ja/`、`values-ko/` 五個語系目錄均已完整翻譯與對齊。

#### (3) 測試完整驅動
- `HealthViewModelTest.kt`：新增 6 項測試，完整覆蓋 `SETTLEMENT`、`REFUSED`、`COMMITTED_NOT_RELOADED`、`LOCKED`、`UNKNOWN`（並斷言 `failure.kind shouldNotBe REFUSED`）及取消不回報。
- `PolicyFailureDialogTest.kt`：6 項 Android Instrumented 測試，直接由 resources 讀取各文字（非寫死英文），斷言每個對應分支只顯示該分支文案，其他分支 count 均為 0。

---

### 2. CI JVM 測試列表驗證 (Codex M1)

- **檔案**：[`.github/workflows/ci.yml:40`](file:///Users/iml1s/.github/workflows/ci.yml#L40)
- **變更確認**：
  ```yaml
  - :feature:analytics:testDebugUnitTest :feature:search:testDebugUnitTest :feature:conversation:testDebugUnitTest :app:testDebugUnitTest
  + :feature:analytics:testDebugUnitTest :feature:search:testDebugUnitTest :feature:conversation:testDebugUnitTest :feature:health:testDebugUnitTest :app:testDebugUnitTest
  ```
  已正確補上 `:feature:health:testDebugUnitTest`。本地執行 `./gradlew :feature:health:testDebugUnitTest` 確認全數 8 項單元測試通過。

---

### 3. pendingExcluding 四值測試 (Codex M2)

- **檔案**：[`JournalLossTransactionTest.kt:754-767`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/JournalLossTransactionTest.kt#L754-L767)
- **測試邏輯**：
  1. 建立包含來源 `pkg` 的 4 筆事件：
     - `evt-0` (LOSS_UNSETTLED = 0)
     - `evt-1` (LOSS_SETTLED = 1)
     - `evt-2` (LOSS_DEFERRED = 2)
     - `evt-3` (LOSS_DEFERRED_SETTLED = 3)
  2. 建立被排除的暫停來源 `paused` 的 2 筆事件：
     - `evt-p0` (LOSS_UNSETTLED = 0)
     - `evt-p1` (LOSS_SETTLED = 1)
  3. 無排除清單時：`pendingIds()` 回傳 `["evt-p0", "evt-p1", "evt-0", "evt-1"]`（0 和 1 為候選，停放的 2 和 3 被排除）。
  4. 帶非空排除清單 `excludingPackages = listOf(paused)` 時：
     `ingest.pendingJournal(excludingPackages = listOf(paused)).map { it.second.eventId }` 回傳 `listOf("evt-0", "evt-1")`。
     - 包含來源的 0/1（`evt-0`, `evt-1`）如期保留。
     - 包含來源停放的 2/3（`evt-2`, `evt-3`）維持排除。
     - 排除來源的 0/1（`evt-p0`, `evt-p1`）完全消失。
  5. 測試方法結尾回傳 `Unit`。

---

### 4. 還原中斷與拒絕原子性 (Restore Interruption & Rejection)

#### (1) 還原取消與檔案連結性
在 [`BackupService.kt:435-446`](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/BackupService.kt#L435-L446)：
- `writtenFiles.removeAll(usedFiles)` 置於 Room 的 `db.withTransaction` 區塊結尾處（在提交前）：
  - **若中斷發生在提交之後**：`usedFiles` 已從 `writtenFiles` 中剔除，外層 `finally` 或 `catch` 只會刪除真正未被引用的孤兒檔，已提交資料列關聯的媒體檔案得以完整保留。
  - **若中斷發生在交易內部**：資料庫交易回滾，`writtenFiles` 尚未剔除 `usedFiles`，外層 `catch` 中的 `for (f in writtenFiles) mediaDir.delete(f)` 會清除本次還原寫入的所有暫存檔案，不留任何孤兒檔。
- `BackupCancellationTest.kt`（3 項測試）對此兩處出口進行了實測，並驗證二次還原重疊備份時的孤兒檔修剪與重複資料處理。

#### (2) 無效 Base64 解碼與空間檢查
- **無效 Base64**：[`BackupService.kt:323`](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/BackupService.kt#L323) 判定 `bytes == null || bytes.isEmpty() || bytes.size > BackupLimits.MAX_MEDIA_BYTES`。Android `Base64.decode` 遇到全無效字元（如 `"%%%%"`）會回傳長度為 0 的 byte array；代碼將空陣列視為無效媒體，記錄 `mediaState = FAILED` 並累計 `mediaNotRestored++`，絕不將 0 位元組檔案建立為 `LOCAL_COPY`。
- **儲存空間不足**：[`BackupService.kt:282-286`](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/BackupService.kt#L282-L286) 在解密寫入磁碟前，預先檢查 `free < mediaBytes + LOW_SPACE_FLOOR_BYTES`（保留 32 MB 給資料列與 WAL），不足則直接拒絕並回傳 `LOW_SPACE`，不寫入任何檔案。
- **標頭截斷**：[`BackupService.kt:256`](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/BackupService.kt#L256) 檢查 `read < header.size`，若檔案長度不足標頭長度，立即回傳 `TRUNCATED`，避免落入 Tink 解密被誤報為密鑰錯誤。
- `BackupRejectionTest.kt`（6 項測試）針對「錯密鑰」、「密文竄改」、「內文截斷」、「標頭截斷」、「非備份檔」、「空間不足」進行完整金庫指紋比對，證明金庫資料列與媒體檔案逐位元組原樣未動。

---

### 5. 媒體刪除競態清理 (Media Deletion Race)

- **檔案**：[`MediaCopier.kt:185-201`](file:///Users/iml1s/Documents/mine/quietinbox/platform/media/MediaCopier.kt#L185-L201)
  ```kotlin
  val linked = db.withTransaction {
      val id = db.mediaDao().insert(...)
      if (db.messageDao().setMedia(messageId, MediaState.LOCAL_COPY.name, id) != 1) {
          db.mediaDao().delete(listOf(id))
          return@withTransaction false
      }
      written.clear()
      true
  }
  return if (linked) MediaState.LOCAL_COPY else MediaState.FAILED
  ```
- 當訊息在複製處理期間被刪除，`setMedia` 更新列數為 0（`!= 1`）：
  1. 交易內立刻刪除剛插入的 `media_blob` 列；
  2. 跳過 `written.clear()` 並回傳 `false`；
  3. 外層 `finally` 區塊執行 `for (f in written) dir.delete(f)`，清除磁碟上的圖片與縮圖檔案；
  4. 函數乾淨回傳 `MediaState.FAILED`。
- `MediaDeletionRaceTest.kt`：包含 2 項測試（競態刪除、正常連結保留），兩者均回傳 `Unit`。實機執行確認通過。

---

### 6. 鎖定缺口開放順序 (agy M1)

- **檔案**：[`CaptureCoordinator.kt:1015-1029`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/CaptureCoordinator.kt#L1015-L1029)
  ```kotlin
  } catch (e: VaultUnavailableException) {
      if (!vaultGapOpen) {
          vaultGapOpen = true
          var written = false
          guarded {
              health.openGap(snapshot.observedAtEpochMs, GapReason.UNKNOWN, GapPrecision.BOUNDED, snapshot.observedAtEpochMs)
              written = true
          }
          if (!written) vaultGapSince = snapshot.observedAtEpochMs
      }
      _status.update { it.copy(vaultLocked = true, listenerState = ListenerState.DEGRADED) }
  }
  ```
- **順序驗證**：原本先發布 `_status.update { vaultLocked = true }`，再設置 `vaultGapOpen = true`。若金庫在兩者之間瞬間解鎖，解鎖監聽者在收到狀態變更時會發現 `vaultGapOpen` 仍為 false，導致缺口未被關閉且漏記記錄。修正後將缺口標記與開啟移至前面，發布狀態放在最後，徹底消除 Round 39 揭露的 1/6 機率 flake。

---

### 7. 對抗性分析：每一項新增／修改測試的擊垮條件 (Adversarial Analysis)

| 測試案例 | 所屬測試檔案 | 能讓該測試失敗的一行代碼變更 |
| :--- | :--- | :--- |
| `a settlement that refuses the write is a settlement refusal` | `CaptureCoordinatorTest` | 在 `CaptureCoordinator.kt:493` 將拋出 `SettlementRefused` 改為直接拋出 `Refused` |
| `a committed policy whose reload fails is not a rollback` | `CaptureCoordinatorTest` | 在 `CaptureCoordinator.kt:504` 將 `throw PolicyChangeException.CommittedNotReloaded(it)` 改為 `throw PolicyChangeException.Refused(it)` |
| `a locked vault is not wrapped as a refused change` | `CaptureCoordinatorTest` | 在 `CaptureCoordinator.kt:492` 移除 `\|\| e is VaultUnavailableException` |
| `a settlement rollback is surfaced...` | `HealthViewModelTest` | 在 `HealthViewModel.kt:146` 將映射改為 `PolicyFailure.Kind.REFUSED` |
| `a refused pause is a rollback, not a settlement` | `HealthViewModelTest` | 在 `HealthViewModel.kt:147` 將 `Refused` 映射改為 `PolicyFailure.Kind.SETTLEMENT` |
| `a change that committed and could not be read back...` | `HealthViewModelTest` | 在 `HealthViewModel.kt:148` 將 `CommittedNotReloaded` 映射改為 `PolicyFailure.Kind.REFUSED` |
| `a locked vault is the lock case, not a rollback` | `HealthViewModelTest` | 在 `HealthViewModel.kt:145` 將 `VaultUnavailableException` 映射改為 `PolicyFailure.Kind.REFUSED` |
| `an unclassified throw does not become a rollback` | `HealthViewModelTest` | 在 `HealthViewModel.kt:149` 將 `else` 映射改為 `PolicyFailure.Kind.REFUSED` |
| `aSettlementRollbackNamesTheApp...` | `PolicyFailureDialogTest` | 在 `PolicyFailureDialog.kt:54` 將 `SETTLEMENT` 分支改用 `R.string.health_policy_failed_body` |
| `anUnclassifiedFailureDoesNotUseTheRollbackCopy` | `PolicyFailureDialogTest` | 在 `PolicyFailureDialog.kt:57` 將 `UNKNOWN` 分支改用 `R.string.health_policy_failed_body` |
| `aCommittedChangeThatCouldNotBeReadBack...` | `PolicyFailureDialogTest` | 在 `PolicyFailureDialog.kt:56` 將 `COMMITTED_NOT_RELOADED` 分支改用 `R.string.health_policy_failed_body` |
| `aLockedVaultUsesTheLockCopy` | `PolicyFailureDialogTest` | 在 `PolicyFailureDialog.kt:55` 將 `LOCKED` 分支改用 `R.string.health_policy_failed_body` |
| `theCandidateReadsAgreeOnAllFourValues` (pendingExcluding) | `JournalLossTransactionTest` | 在 DAO 查詢中忽略 `excludingPackages` 條件過濾 |
| `aCancellationLandingAfterTheCommit...` | `BackupCancellationTest` | 在 `BackupService.kt` 將 `writtenFiles.removeAll(usedFiles)` 移至 `withTransaction` 區塊之外 |
| `aBlobTheVaultCannotWrite...` | `BackupCancellationTest` | 在 `BackupService.kt:323` 移除 `\|\| bytes.isEmpty()` 檢查 |
| `aCancellationLandingInsideTheTransaction...` | `BackupCancellationTest` | 在 `BackupService.kt:453` 的 `catch` 區塊中移除 `for (f in writtenFiles) mediaDir.delete(f)` |
| `aFileCutInsideTheHeaderIsRefusedAsIncomplete...` | `BackupRejectionTest` | 在 `BackupService.kt:256` 移除 `if (read < header.size)` 檢查 |
| `aVaultWithoutRoomForTheMediaRefuses...` | `BackupRejectionTest` | 在 `BackupService.kt:284` 移除空間檢查邏輯 |
| `aMessageDeletedWhileItsCopyIsInFlight...` | `MediaDeletionRaceTest` | 在 `MediaCopier.kt:192` 移除 `if (db.messageDao().setMedia(...) != 1)` 檢查 |
| `a retry cancels the earlier wait...` | `OnboardingViewModelTest` | 在 `OnboardingViewModel.kt:135` 移除 `testTimeout?.cancel()` |
| `an emoji-only query yields no tokens...` | `SearchNormalizerTest` | 在 `SearchNormalizer.kt` 讓分詞器發射 emoji token |

---

## 文檔與計數核實 (Docs & Matrix Verification)

1. **`docs/TEST_MATRIX.md` 與 `docs/zh-Hant/TEST_MATRIX.md`**：
   - `CaptureCoordinatorTest`：記錄 76 項測試，實際代碼中 `@Test` / `test(...)` 恰好 76 個。
   - `HealthViewModelTest`：記錄 8 項 JVM 測試，實際代碼恰好 8 個。
   - `PolicyFailureDialogTest`：記錄 6 項真機測試，實際代碼恰好 6 個。
   - `BackupCancellationTest`：記錄 3 項真機測試，實際代碼恰好 3 個。
   - `BackupRejectionTest`：記錄 6 項真機測試，實際代碼恰好 6 個。
   - `MediaDeletionRaceTest`：記錄 2 項真機測試，實際代碼恰好 2 個。
   - `OnboardingViewModelTest`：記錄 6 項 JVM 測試，實際代碼恰好 6 個。
   - `JournalLossTransactionTest`：記錄 32 項真機測試，實際代碼恰好 32 個；儲存真機測試總計 63 項，實際統計亦精確為 63 項。
2. **`CHANGELOG.md`**：
   - 明確記錄還原取消檔案修剪移入交易、無效/超大媒體標記 FAILED、剩餘空間不足拒絕、標頭截斷拒絕、媒體刪除競態修復、政策變更失敗誠實分類等，無誇大或未實裝行為。
3. **應用市集描述與已知限制**：
   - `docs/COMPATIBILITY.md`、`docs/zh-Hant/COMPATIBILITY.md`、五國語言 `fastlane/metadata/android/*/full_description.txt` 同步更新「當聊天室正在前景畫面上開啟時可能完全不發通知」之限制說明，保持誠實公開。

---

## 本地測試執行結果 (Verification Execution)

- **JVM 單元測試**：
  `./gradlew testDebugUnitTest`：**BUILD SUCCESSFUL**（包含 `:feature:health:testDebugUnitTest` 8 項全數通過）。
- **Android Instrumented 測試**（於連接之 API 36 模擬器 `emulator-5554` 執行）：
  - `:feature:health:connectedDebugAndroidTest`：**通過（6/6 測試全數通過）**。
  - `:platform:media:connectedDebugAndroidTest`：**通過（2/2 測試全數通過）**。
  - `:platform:backup:connectedDebugAndroidTest`：**通過（12/12 測試全數通過，含取消 3 項、拒絕 6 項、往返 3 項）**。
  - `tools/check-instrumented.sh` 驗證：各已執行模組回報正確數量且 0 failures/errors。

---

## 審查總結

本次提交整體架構嚴密，在資料持久性、還原邊界、競態清理與使用者介面誠實性方面表現出色。所有測試具有強健的對抗性，文檔記載精確無訛。建議予以 **APPROVE** 合併。
