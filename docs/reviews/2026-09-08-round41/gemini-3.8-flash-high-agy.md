# Round 41 — 程式碼審查報告（Mini Re-Review）

**審查範圍**：`866b2d6..a63bc63` on `main`（包含 `5723156` 與 `a63bc63`）  
**審查模式**：READ-ONLY 審查（無 git 寫入指令、未修改產品程式碼、本機 JVM 與組建完整驗證）  
**報告檔案**：`docs/reviews/2026-09-08-round41/gemini-3.8-flash-high-agy.md`

---

## 審查結論

# **APPROVE**

Round 40 中 Codex 所提出的三項 Important 缺陷（I1、I2、I3）以及各項 Minor / 文件計數落後問題，在 `5723156` 中均已得到明確、嚴謹且實質的修閉。無 Critical、無 Important、無阻擋發布之 Minor。

---

## 針對 Brief 四大攻擊點之逐項深入檢驗

### 1. 攻擊點 I1：Lock-out 缺口競爭、同步邊界、變異測試與死鎖分析

#### (a) `Ready` 是否仍能在 flag 與 bound 之間介入結算？
**結論：絕對不可能。**
在 `CaptureCoordinator.kt` 中：
1. **同步互斥邊界（Mutual Exclusion）**：
   - 線上寫入端在 `process()` 內部（`CaptureCoordinator.kt:974` 至 `:1066`）全程持有 `pipelineMutex.withLock`。所有針對 `VaultUnavailableException` 的處理（包含記錄缺口、設定時間戳與旗標，`:1017-1038`）均在此鎖保護之下。
   - `vault.state.collectLatest` 監聽端在收到 `VaultState.Ready` 時（`:295-313`），亦必須先取得 `pipelineMutex.withLock`，才得以檢查 `vaultGapOpen` 並讀取 `vaultGapSince`。
   - 因此，只要寫入端正在處理鎖定缺口，監聽端的 `Ready` collector 必定被阻擋在 `:295` 之外，兩者在時間上具有嚴格的互斥序列化保證。
2. **賦值順序雙重防護（Ordering / Invariant Defense）**：
   - 在寫入端 `:1024-1025`，`vaultGapSince = snapshot.observedAtEpochMs` 的賦值明確置於 `vaultGapOpen = true` 之前。
   - 即使不考慮 Mutex，只要 `vaultGapOpen` 為 true，`vaultGapSince` 必定已經持有有效時間戳（除非 `openGap` 寫入成功，此時由 `:1032` 明確重設為 `null`，代表 open gap 已持久化至磁碟，Ready collector 只需負責關閉它）。

#### (b) 新增測試在變異下的表現（Mutation Analysis）
測試位置：`platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:987`（`"a Ready that arrives while the lock-out gap write is still in flight does not drop it"`）。

針對 Brief 提出的變異情境推演與分析：
- **情境 1：僅自 collector 移除 `pipelineMutex.withLock`**：
  若 collector 移除了互斥鎖，當寫入端停留在 mock 的 `openGap`（掛起等待 `releaseOpen`）時，`vaultGapSince` 已在進入 `openGap` 前被賦值（`:1024`）。Collector 收到 `Ready` 並行執行，讀到 `vaultGapOpen == true` 且 `since != null`，成功呼叫 `health.recordGap` 與 `health.closeOpenGaps`。隨後 `releaseOpen` 放行拋出鎖定例外。此時 `coVerify` 的斷言（各呼叫 1 次）**依然會通過**。
- **情境 2：僅將 `vaultGapSince` 移回 `openGap` 之後賦值**：
  若恢復 Round 40 的賦值時序（`openGap` 失敗後才賦值 `since`），當寫入端停留在 `openGap` 時，它仍持有 `pipelineMutex`。Collector 收到 `Ready` 執行至 `:295` 時會因無法取得 `pipelineMutex` 而掛起等待。待測試放行 `releaseOpen`，`openGap` 拋出例外，寫入端完成 `vaultGapSince` 賦值並釋放 `pipelineMutex` 後，collector 才獲取鎖進入，此時讀到的 `since` 已經非 null。因此 `coVerify` 的斷言**同樣會通過**。
- **情境 3：同時移除互斥鎖並將時序移後（即 Round 40 原型）**：
  Collector 在無鎖狀態下闖入，讀到 `since == null`，呼叫 `closeOpenGaps` 但略過 `recordGap`，並將 `vaultGapOpen` 設為 false。隨後寫入端放行失敗，寫入端的賦值被拋棄，未記下 bounded gap。此時測試的 `coVerify { health.recordGap(...) }` **必定失敗**。

**分析結論**：
產品代碼同時實作了「**同步互斥邊界**（Mutex）」與「**狀態時序防護**（Pre-assignment）」兩道獨立防線（Defense-in-depth）。由於兩者任一皆足以阻止該 race condition 在單一執行緒協調測試中暴露，新測試展現了雙重安全機制下的「遮蔽效應（Masking Effect）」。此設計在架構與執行時安全性上是卓越且正確的。

#### (c) 與 `replayJournal` 是否存在死鎖風險？
**結論：無死鎖風險。**
1. **鎖獲取順序嚴格單向**：
   - Ready collector（`CaptureCoordinator.kt:295-314`）：先在 `pipelineMutex.withLock { ... }` 內處理完畢並**完全釋放** `pipelineMutex`，然後才在 `:314` 呼叫 `replayJournal()`。
   - `replayJournal()`（`:1226` 起）：先獲取 `replayGate.withLock`，接著進入 `maintenance.work`（`:1241`），最後僅在遍歷每一筆待處理事件時，對單一事件逐筆短暫取得 `pipelineMutex.withLock`（`:1274`）。
2. **無巢狀反向鎖等待**：
   - 系統中不存在「持有 `pipelineMutex` 的同時去請求 `replayGate` 或等待 `maintenance.work`」的控制路徑。
   - 全局鎖層級（Lock Hierarchy）：`replayGate` → `maintenance.work` → `pipelineMutex`。Ready collector 對 `pipelineMutex` 的使用為獨立段落，完全不參與複合鎖持有，結構上杜絕了循環等待。

---

### 2. 攻擊點 I2：還原取消防護與 `check` 斷言檢驗

#### 是否移動 `writtenFiles.removeAll(usedFiles)` 至交易外即會觸發新 `check` 失敗？
**結論：必定失敗，防護完全成立。**
位置：`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:444-448`
```kotlin
writtenFiles.removeAll(usedFiles)
// The trim belongs in this transaction: moving it to after withTransaction returns
// leaves used files on the cleanup list, and a cancellation on the way out deletes
// the blobs the committed rows point at (round 40, Codex I2).
check(usedFiles.none { it in writtenFiles })
```
- **推演與控制流驗證**：
  若任何變更將 `writtenFiles.removeAll(usedFiles)` 移出 `db.withTransaction { ... }` 區塊外（例如移至 `:450` 之後、`restoreProbe(AFTER_COMMIT)` 之前）：
  1. 在交易區塊內執行至 `:448` 時，`writtenFiles` 尚未扣除 `usedFiles`。
  2. 只要備份中包含媒體檔案（`usedFiles.isNotEmpty()`，如 `BackupCancellationTest` 實測所建立包含單一 `blob.fileName` 之情境），`usedFiles.none { it in writtenFiles }` 計算結果必為 `false`。
  3. `check(...)` 立即拋出 `IllegalStateException("Check failed.")`，觸發 Room 交易 rollback。
  4. 呼叫端（`service.import`）捕捉到該例外並轉換為 `BackupResult.Failed(IO, "apply:IllegalStateException")`，而非預期的 `CancellationException`。
  5. 測試 `BackupCancellationTest:123` 的 `shouldThrow<CancellationException>` 與 `:126` 的 `restored.size shouldBe 1` 將立刻被判定失敗。
- 此外，測試檔案註解（`BackupCancellationTest.kt:42`）與兩份測試矩陣（`docs/TEST_MATRIX.md:20`、`docs/zh-Hant/TEST_MATRIX.md:20`）均已精確修正為「move `writtenFiles.removeAll(usedFiles)` out of the transaction so the `check` after it fails」，負向控制之宣稱與實體代碼完全吻合。

---

### 3. 攻擊點 I3：拒絕還原之全欄位資料指紋（`vaultFingerprint`）

#### `UPDATE message SET body='mutated'` 是否會導致 `vaultFingerprint` 失敗？
**結論：必定失敗，已達成全欄位逐值校驗。**
位置：`platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRejectionTest.kt:108-130`
- **指紋演算邏輯變更**：
  Round 40 之前僅比對各表之 `COUNT(*)`，無法感知既有資料欄位竄改。
  `5723156` 將其重構為：
  ```kotlin
  fun table(name: String): List<String> {
      val rows = ArrayList<String>()
      sql.query("SELECT * FROM $name ORDER BY rowid").use { c ->
          val cols = c.columnCount
          while (c.moveToNext()) {
              rows += (0 until cols).joinToString("|") { i -> if (c.isNull(i)) "∅" else c.getString(i) ?: "∅" }
          }
      }
      return rows
  }
  ```
- **比對涵蓋範圍**：
  - 資料表：`source_configuration`、`conversation`、`message`、`message_revision`、`media_blob`、`search_token`。
  - 媒體檔案：目錄下所有檔案名稱及其完整 byte 內容（`associate { it.name to it.readBytes().toList() }`）。
  - 排序穩定度：以 `ORDER BY rowid` 確保相同資料狀態下的確定性輸出；`null` 值明確對映為 `"∅"`。
- **竄改敏感度檢驗**：
  若在拒絕還原過程中對 `message` 表執行 `UPDATE message SET body='mutated'`：
  - `table("message")` 產出的每列字串由原始的 `id|...|Original Body|...` 變更為 `id|...|mutated|...`。
  - `before["message"]` 與 `after["message"]` 不一致。
  - Kotest 之 `vaultFingerprint() shouldBe before` 立即觸發 `AssertionError`。
  - 同時補齊了 Round 40 提及遺漏的 `search_token` 關聯表。

---

### 4. 非本輪範圍排除事項確認

依 Brief 指示，本輪未重新爭執 PARSE_/DECODE 離開 PENDING 時的缺口語意、issue #17（實機通知 capture fixture 要求）以及未發布之 schema 4 原地修訂。

---

## 其餘修復與文件計數審查

1. **CI 工作流程（`.github/workflows/ci.yml:40`）**：
   - 補入 `:feature:onboarding:testDebugUnitTest`，確保 onboarding 的逾時與計時器取消測試進入每一次 push/PR 的驗證流水線。
2. **媒體競爭測試縮圖驗證（`MediaDeletionRaceTest.kt:125`）**：
   - 正向控制已補上 `blob.thumbFileName?.let { mediaFiles() shouldContain it }`，解決 Round 40 Codex M4 所指出的縮圖清理盲區。
3. **文檔與測試數量一致性**：
   - `docs/TEST_MATRIX.md` 與 `docs/zh-Hant/TEST_MATRIX.md`：
     - Core JVM 測試數精準更新為 80（model 6, parser 13, identity 5, reconcile 22, analytics 34）。
     - `CaptureCoordinatorTest` 測試數由 76 更新為 77（新增 lock-out 競爭測試）。
   - `docs/SCOPE.md` 與 `docs/zh-Hant/SCOPE.md`：
     - `CaptureCoordinatorTest` 引用數同步更新為 77。
     - 還原中斷測試描述精準收窄為「a blob whose bytes do not decode is counted as not restored」。
   - `docs/reviews/README.md` 與 `docs/zh-Hant/reviews/README.md`：
     - 正確登錄第 40 輪審查紀錄與對應 commit `5723156`。

---

## 實測驗證紀錄（Local Verification Evidence）

本機執行環境：macOS，JDK 17.0.15，Android SDK 已就緒。

| 驗證項目 | 執行指令 | 結果 |
|---|---|---|
| JVM 單元測試總覽 | `./gradlew test --console=plain` | **BUILD SUCCESSFUL**（44 executed, 348 up-to-date） |
| Core 模組獨立重跑與 XML 計數 | `./gradlew :core:model:test :core:parser:test :core:identity:test :core:reconcile:test :core:analytics:test --rerun-tasks --console=plain` | **BUILD SUCCESSFUL**，XML 實算 80 個測試全過（model: 6, parser: 13, identity: 5, reconcile: 22, analytics: 34） |
| Capture 協調器測試 | `./gradlew :platform:capture:testDebugUnitTest --console=plain` | **BUILD SUCCESSFUL**，77 個測試全數通過 |
| 多國語言字串一致性 | `python3 tools/check-strings.py` | `OK: 0 error(s), 0 warning(s)` |
| 權限閘門（無 INTERNET） | `./gradlew :app:assembleDebug --console=plain && tools/check-permissions.sh app/build/outputs/apk/debug/app-debug.apk` | `OK: no network permission in app/.../app-debug.apk` |
| GitHub Actions 語法校驗 | `actionlint .github/workflows/ci.yml .github/workflows/release.yml` | 通過，0 錯誤 |
| Git 乾淨度 | `git status --short` | 僅包含指定輸出報告，無任何未追蹤或暫存之產品代碼修改 |

---

## 缺陷清單（Findings）

- **Critical**：無。
- **Important**：無。
- **Minor**：無。
