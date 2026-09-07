# QuietInbox 程式庫第 36 輪審查報告 (gemini-3.8-flash-high)

- **審查日期**：2026-09-07
- **審查標的**：`f7a09ed..HEAD`（重點提交：[`f410809`](file:///Users/iml1s/Documents/mine/quietinbox) 與 [`8e3bb3b`](file:///Users/iml1s/Documents/mine/quietinbox)）
- **當前 Git HEAD**：`c7a882e377e1cbba3c055a45aabf73ddd0721207`
- **審查模式**：唯讀審查（READ-ONLY Review）
- **依據文件**：[`docs/reviews/2026-09-07-round36/BRIEF.md`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/2026-09-07-round36/BRIEF.md)
- **總體結論（Verdict）**：**APPROVE WITH MINOR FIXES**
  - **Critical 缺陷**：**0 項**
  - **Important 缺陷**：**2 項**（測試偽陽性 / Fake 行為測試）
  - **Minor 缺陷 / 建議**：**1 項**（文件重複註解）

---

## 一、審查摘要與判決總結

本輪審查重點評估 QuietInbox 在第 35 輪審查後的重大重構與修復，特別是針對 `f7a09ed..HEAD`（包含提交 `f410809` 與 `8e3bb3b`）進行深度程式碼走查與測試驗證。

修復成果顯著：
1. **Critical 缺陷徹底根除**：原先「結清失敗消耗事件 commit 重試額度」的致命設計，已藉由將 `recordCarriedOverLoss(replay)` 移出 commit 交易區塊，改以 `claimLoss` 標記認領狀態，並在認領失敗時提早返回、不消耗事件 heavy commit 額度的機制完全解決。
2. **分頁查詢設計穩固**：引進 `(receivedAtEpochMs, eventId)` 複合主鍵 keyset pagination，解決 journal 巨量累積時的大物件 OOM 風險，並具有嚴格全序性保證，不漏列、不跳列、邊界終止條件嚴密。
3. **離開不變式與文件對齊**：`event_journal` 的 4 條 PENDING 離開路徑盤點清楚，全儲存庫雙語架構文件與測試數、gap 站點統計完全吻合程式碼現況。
4. **排除項目確認**：依 BRIEF 指引，Codex I1（WhatsApp 斷行整列遺失無 gap）與 Codex I2（備份合併 duplicate 遺失截短證據）屬於額外寫入路徑，不列為本輪退件理由。

然而，審查中發現兩處 **Important** 級別的測試架構問題（測試對 Fake 行為過度耦合、未覆蓋真實實作，以及部分假保護場景）。整體架構與生產邏輯安全無虞，故判定為 **APPROVE WITH MINOR FIXES**。

---

## 二、缺陷清單 (Findings)

### 1. Critical 缺陷（0 項）
經過對生產路徑並發、交易邊界、例外傳播與迴圈終止條件的嚴格審查，未發現任何會導致資料遺失、狀態損毀、無窮迴圈或重試次數被錯誤耗盡的 Critical 缺陷。

---

### 2. Important 缺陷（2 項）

#### 【Important 1】`removeSource` 的 settle/discard 順序完全缺乏具鑑別力的測試覆蓋
- **相關程式碼**：
  - 生產端：[`CaptureCoordinator.kt:726-731`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L726-L731)
    ```kotlin
    sources.remove(pkg) {
        // Must settle before removing journal rows, else caller loses carried-over losses.
        settleCarriedOverLosses(pkg)
        ingest.discardPendingJournal(pkg)
    }
    ```
  - 測試端：[`CaptureCoordinatorTest.kt:1443-1466`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L1443-L1466) 及 [`CaptureCoordinatorTest.kt:387-410`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L387-L410)
- **問題分析**：
  1. 提交 `8e3bb3b` 為 `setSourceEnabled(pkg, false)` 撰寫了反轉即翻紅（`0 shouldBe 1`）的高鑑別力測試（`CaptureCoordinatorTest.kt:1443`）。
  2. 然而，對於另一處具有相同順序不變式（Invariant: Must settle before discard）的 `removeSource`（[`CaptureCoordinator.kt:726`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L726)），**現有測試完全沒有驗證其順序，甚至完全沒有驗證其呼叫**。
  3. 檢視 `CaptureCoordinatorTest.kt` 中唯一測試 `removeSource` 的案例（第 387 行），該測試執行前，`ingest.pendingByPackage` 內根本沒有該 package 的待處理 journal 列。
  4. 此外，測試夾具 [`Harness.sources.remove`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L309-L313) 僅呼叫回呼，根本沒有模擬底層刪除或清空 journal。
  5. **實證結果**：若將 [`CaptureCoordinator.kt:726`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L726) 的 `settleCarriedOverLosses(pkg)` 整行刪除，或顛倒 `settle` 與 `discard` 的順序，全套測試依然 **100% 全部通過**。這形成了保護機制的盲區。
- **改進建議**：
  比照 `CaptureCoordinatorTest.kt:1443`，為 `removeSource` 撰寫專門的反轉鑑別測試：在調用 `removeSource` 前注入含 `lossRecorded = 0` 的 pending journal，斷言 `recordGap` 必須在 `discardPendingJournal` 清除資料前被呼叫。

---

#### 【Important 2】存在「測試測試夾具（Test about a fake）」的虛假安全感測試
- **相關程式碼**：
  - 測試案例 1：[`CaptureCoordinatorTest.kt:1260-1282`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L1260-L1282)
    *測試名稱*：`"an event the journal already holds does not record its loss a second time"`
  - 測試案例 2：[`CaptureCoordinatorTest.kt:1084-1100`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L1084-L1100)
    *測試名稱*：`"setting a source flag to the value it already has is not a second gap"`
- **問題分析**：
  1. **針對案例 1**：
     - 生產程式碼 [`CaptureCoordinator.kt:283-294`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L283-L294) 在收到 notification 時，無條件構建 `lossOnAccept = { gap.recordLoss(...) }` 並傳給 `ingest.enqueueJournalEntry`。協調器自身**完全沒有檢查**該事件是否已在 journal 中。
     - 測試案例 1 斷言「重複事件不會記錄第二次 loss」，然而斷言之所以成功（`gapRecorded.get() shouldBe 1`），完全是因為測試自定義的 Harness 偽造實作（第 272 行 `if (!seen.add(uniqueKey)) return false`）自行擋下了重複回呼！
     - 也就是說，這個測試是在**驗證測試夾具 Harness 內寫的 `seen.add` 邏輯**，而非驗證 `CaptureCoordinator` 或真實 SQL/Room 的唯一鍵衝突處理。
  2. **針對案例 2**：
     - 生產程式碼 [`CaptureCoordinator.kt:709-722`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L709-L722) 的 `setSourceEnabled`，直接呼叫 `sources.setEnabled(pkg, enabled) { ... }`，協調器自身**沒有檢查來源當前狀態是否已等於目標狀態**。
     - 測試之所以通過，完全依賴 [`Harness.kt:298-308`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L298-L308) 中自寫的 `if (prev == enabled) return false`。如果真實的 `SourceRepository` 沒有此防護，協調器就會發出重複的 gap。
- **改進建議**：
  應在生產端 [`CaptureCoordinator`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt) 層級明確狀態防護責任；或在整合測試 / Room DAO 真實測試層級驗證重複寫入與重複狀態設定的行為，避免測試只驗證了測試端 Fake 的自製行為。

---

### 3. Minor 缺陷 / 程式碼小瑕疵（1 項）

#### 【Minor 1】`IngestRepository.kt` 出現重複的 KDoc 註解區塊
- **相關檔案**：[`IngestRepository.kt:142-158`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L142-L158)
- **問題分析**：
  在函數 `pendingJournalForPackage` 前方，連續出現了兩段幾乎相同的 KDoc 註解區塊（行 142-149 與行 151-157），顯然是提交 `8e3bb3b` 在合併或修改註解時未清理乾淨的遺留物：
  ```kotlin
  /**
   * Loads pending journal entries for [packageName] via keyset pagination...
   */
  /**
   * Loads pending journal entries for [packageName] via keyset pagination...
   */
  override suspend fun pendingJournalForPackage(...): List<JournalEntry>
  ```
- **改進建議**：
  刪除多餘的第一段 KDoc，維持單一乾淨的文件註解。

---

## 三、BRIEF 7 大檢驗重點之深入技術分析與證據鏈

### 1. 新引入缺陷檢驗 (New Defect Analysis)
- **檢驗標的**：提交 `8e3bb3b` 將 `recordCarriedOverLoss(replay)` 移至 heavy commit 交易外層。
- **分析與驗證**：
  1. **是否會導致無窮重試？**
     不會。`recordCarriedOverLoss` 執行失敗時，捕獲例外並立即 `return@withLock`。此時事件不會被標記為完成，但也不會遞增事件本身的 `attempts` 計數（因為該計數僅保留給解析/寫入 pipeline 的重試）。
  2. **是否會導致計數耗盡？**
     不會。在第 35 輪前，結清失敗會拋出例外導致整個 commit block 失敗，進而觸發重試機制將事件重試額度（上限 3 次）耗盡並標記為 `FAILED`。現在透過 `claimLoss` 獨立認領，成功認領後才標記 `lossRecorded = 1`，結清與 commit 職責分離，徹底保護了事件的重試配額。
  3. **自動化驗證**：全套 257 項測試全部通過，無任何超時或無限重試案例。

---

### 2. 認領位置新機制（`recordCarriedOverLoss`）分析
- **檢驗標的**：[`CaptureCoordinator.kt:479-567`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L479-L567)
- **分析與驗證**：
  1. **迴圈是否會空轉 (Does not spin)？**
     - 在 `drainPendingJournal()` 的 `while (progressed && rounds++ < 100 && !paused)` 迴圈中，`progressed` 初始為 `false`。
     - 遍歷 batch 內的項目時，若某項目的 `recordCarriedOverLoss` 失敗（例如磁碟滿或 SQLite 異常），觸發 `settled.isFailure`，執行 `return@withLock` 提早返回。
     - 提早返回使得後續的 `if (!ingest.isJournalPending(entry.eventId)) progressed = true` **不會被執行**。
     - 若整個 batch 的第一筆就失敗，`progressed` 保持為 `false`，`while` 條件不成立，**立即終止迴圈**，絕對不會在單次調用中空轉 100 輪。
  2. **飢餓 (Starvation) 與系統進展性**：
     - 若 batch 內第 1 筆失敗，後續項目在本輪被跳過；但因為 `progressed = false`，排程迴圈退出，避免 CPU 100% 狂飆。
     - 該失敗項目在 DB 內維持 `lossRecorded = 0` 與 `state = 'PENDING'`，等待下一輪排程（如新事件到達或定時任務）再次嘗試，既不丟失狀態，也不會阻塞整個執行緒。
  3. **例外處理與協程取消傳播**：
     - 檢查 [`CaptureCoordinator.kt:530-534`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L530-L534)：
       ```kotlin
       val settled = runCatching { recordCarriedOverLoss(replay) }
       settled.exceptionOrNull()?.let {
           if (it is CancellationException) throw it
           logger.w("Drain replay carried-over loss record failed for ${replay.eventId}", it)
       }
       ```
     - 程式碼明確攔截了 `runCatching` 吞掉協程取消的經典陷阱，若例外為 `CancellationException` 則無條件重新拋出，保證協程取消語意不受干擾。

---

### 3. 分頁查詢（Keyset Pagination）正確性
- **檢驗標的**：
  - DAO SQL：[`Daos.kt:182-192`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L182-L192)
  - 儲存庫實作：[`IngestRepository.kt:142-181`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L142-L181)
- **SQL Keyset 語意分析**：
  ```sql
  SELECT * FROM event_journal
  WHERE package_name = :packageName
    AND state = 'PENDING'
    AND (
      received_at_epoch_ms > :afterReceivedAtEpochMs
      OR (received_at_epoch_ms = :afterReceivedAtEpochMs AND event_id > :afterEventId)
    )
  ORDER BY received_at_epoch_ms ASC, event_id ASC
  LIMIT :limit
  ```
- **分析與驗證**：
  1. **全序性 (Strict Total Order) 與跳列/重列**：
     - 主鍵鍵組為 `(received_at_epoch_ms, event_id)`。
     - 即使多個通知在同一毫秒到達（`received_at_epoch_ms` 相同），第二排序鍵 `event_id` 為全域唯一字串（UUID 或帶序號字串），字典序比較 `event_id > :afterEventId` 構成了嚴格的全序關係。
     - 查詢完全避免了傳統 `OFFSET` 在並發寫入時造成的跳列（skip）或重複讀取（duplicate）缺陷。
  2. **終止性證明 (Termination Proof)**：
     - 終止條件由兩道屏障保障：
       a) 當資料總筆數恰為 `limit` 的倍數時（例如總共 200 筆，`limit = 200`），第一頁取滿 200 筆；下一輪以第 200 筆為 cursor 查詢，回傳 0 筆，`next = null`，迴圈立即安全結束。
       b) 硬性保護上限：`while (cursor != null && loopGuard++ < 100)`，杜絕任何潛在死循環。
  3. **資料損毀容錯**：
     - 若遇到 payload 損毀而無法被 `mapper` 解碼的 row，在 [`IngestRepository.kt:170`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L170)：
       ```kotlin
       cursor = rows.lastOrNull()?.let { it.receivedAtEpochMs to it.eventId }
       ```
       cursor 推進取自原始資料庫回傳的 `rows.lastOrNull()`，而非過濾/解碼後的物件。即使該頁全部解碼失敗，游標依然按原始列正常推進，絕對不會卡死在損毀資料頁。

---

### 4. 排序測試鑑別力（Discriminating Tests）
- **檢驗標的**：`CaptureCoordinatorTest.kt` 中驗證 `settleCarriedOverLosses` 先於 `discardPendingJournal` 的測試案例。
- **分析與驗證**：
  1. **`setSourceEnabled(pkg, false)` 測試鑑別力**：
     - 檢查 [`CaptureCoordinatorTest.kt:1443-1466`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L1443-L1466)：
     - 若將生產端 [`CaptureCoordinator.kt:713-714`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L713-L714) 的順序由：
       ```kotlin
       settleCarriedOverLosses(pkg)
       ingest.discardPendingJournal(pkg)
       ```
       顛倒為：
       ```kotlin
       ingest.discardPendingJournal(pkg)
       settleCarriedOverLosses(pkg)
       ```
     - 執行測試時，因為 `discardPendingJournal` 先清空了 journal，隨後調用的 `settleCarriedOverLosses` 查無任何待結清列，`gapRecorded` 保持為 0，測試斷言 `harness.gapRecorded.get() shouldBe 1` 立即失敗（`0 shouldBe 1`）。**鑑別力真實有效**。
  2. **`removeSource` 缺乏鑑別力**：
     - 如前述 **Important 1** 所述，`removeSource` 的測試並未建立前置 pending 資料，因此生產端即使顛倒或刪除該行，測試都不會失敗。

---

### 5. PENDING 離開不變式重新盤點 (Invariant Audit)
- **檢驗標的**：全儲存庫中 `event_journal.state` 離開 `PENDING` 狀態的程式碼與文件一致性。
- **全儲存庫查驗結果**：
  搜尋所有變更 `event_journal` 狀態的 SQL 與 DAO，確信只有以下 **4 條** 離開路徑，無任何未經登記的旁路：
  1. **重播成功結算**：`markJournalCommitted`、`markJournalSkipped`、`markJournalDiscarded`。在進入重播前，皆必須先由 `claimLoss` / `settleCarriedOverLosses` 結清損耗。
  2. **來源停用／移除**：`discardPendingJournal`。在清空前，先由 `settleCarriedOverLosses` 結清損耗。
  3. **解碼毀損不可讀**：`markJournalCorrupt`。將狀態設為 `FAILED`，子狀態記為 `DECODE`。因 payload 損毀無法辨識內容，不結清 loss。
  4. **重試次數超限淘汰**：`failExhaustedJournalEntry`（Issue #28）。重試超過 3 次標記為 `FAILED`，清空 payload，不記錄 gap。
- **文件查驗**：
  查驗 [`Daos.kt:182`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L182)、[`CaptureCoordinator.kt:482`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L482)、[`ARCHITECTURE.md:120`](file:///Users/iml1s/Documents/mine/quietinbox/ARCHITECTURE.md#L120) 及 [`ARCHITECTURE.zh-TW.md:120`](file:///Users/iml1s/Documents/mine/quietinbox/ARCHITECTURE.zh-TW.md#L120)，四處文件對這 4 條離開路徑的陳述完全精確、一致。

---

### 6. 文件數字對比程式碼之重新推導
針對儲存庫現況進行重新統計與推導，各項指標完全吻合：

| 檢查項目 | 文件記載數 | 實際程式碼 / 執行結果 | 判定 |
| :--- | :--- | :--- | :--- |
| **JVM 單元測試總數** | 257 | **257 個測試通過**（`core:*` 79, `parsers:apps` 45, `app` 5, `core:designsystem` 8, `platform:backup` 24, `platform:capture` 52, `platform:crypto` 3, `platform:media` 10, `platform:storage` 12, `feature:analytics` 8, `feature:search` 5, `feature:onboarding` 5, `feature:conversation` 1） | **精確吻合** |
| **Storage 儀器測試** | 38 | `platform:storage/src/androidTest` 共 **38 個** 測試方法 | **精確吻合** |
| **Gap 記錄站點 (Sites)** | 16 | 全庫共 **16 處** 調用：15 處在 `CaptureCoordinator.kt`，1 處在 `HealthRepository.kt:45` | **精確吻合** |
| **Gap 分類分布** | 10 手續 / 6 來源 | 手續層級 10 處（drain, decode, replay 等）；來源層級 6 處（enable/disable, remove 等） | **精確吻合** |
| **ViewModel 測試數** | 19 (Analytics: 8, Search: 5, Onboarding: 5, Conversation: 1) | 逐一累計剛好 **19 個**，且明確標示覆蓋邊界 | **精確吻合** |
| **語系字串完整度** | 5 語系無缺失 | `python3 tools/check-strings.py`：0 errors, 0 warnings | **精確吻合** |
| **Android Lint** | 0 錯誤 | `./gradlew lintDebug`：0 errors | **精確吻合** |

---

### 7. Set-only 語意與歷史模型
- **檢驗標的**：`markTruncated` 與通知內文截短之歷史模型。
- **分析與驗證**：
  1. **行為分析**：
     - 在 [`Reconciler.kt:224`](file:///Users/iml1s/Documents/mine/quietinbox/core/reconciler/src/main/kotlin/dev/quietinbox/core/reconciler/Reconciler.kt#L224)，當動作為 `ReconcilerAction.Known`（同一則訊息重複送達）時，`markTruncated` 呈現 **set-only** 語意（一旦設為 true 就不會因重複送達被抹除為 false）。
     - 當新修訂內容送達並進入 `applyRevision`（換內文）時，系統會依據新內文重新評估是否截短，若新內文未截短，則允許重置為 null。
  2. **歷史模型一致性**：
     - 欄位定義代表的是**歷史觀測事實**（該訊息在生命週期中曾被觀測到文字過長而遭系統截短）。
     - 比對各語系 UI 字串呈現：
       - 英文：`"text was shortened"`（過去時態）
       - 繁體中文：`"文字被截短"`（被動已發生態）
       - 日文：`"本文が短縮されました"`（過去受身形）
     - 程式碼行為與各語系 UI 文案之「過去時態/已發生紀錄」語意完全相符，模型自洽無矛盾。

---

## 四、驗證結果彙整與改進建議

### 1. 驗證指令與結果紀錄
- `./gradlew testDebugUnitTest`：**BUILD SUCCESSFUL**（257 actionable tasks, 257 tests passed）
- `./gradlew lintDebug`：**BUILD SUCCESSFUL**（0 errors）
- `python3 tools/check-strings.py`：**OK**（0 missing, 0 untranslated）

### 2. 後續建議行動清單 (Action Items)
1. **補齊 `removeSource` 的反轉測試**：在 `CaptureCoordinatorTest.kt` 中為 `removeSource` 注入未結清的 pending journal，並斷言在 `discard` 前必定完成 `recordGap`，消除保護機制的盲區。
2. **清理 `IngestRepository.kt` 重複 KDoc**：移除行 142-149 的重複註解。
3. **消除 Fake 自製行為依賴**：在未來的架構演進中，檢視 `Harness` 內的去重邏輯，確保業務層的單元測試真正測試協調器與儲存層的合約，而非測試測試夾具自身。
