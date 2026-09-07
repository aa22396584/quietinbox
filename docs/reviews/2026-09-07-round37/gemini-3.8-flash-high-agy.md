# QuietInbox 程式庫第 37 輪審查報告 (gemini-3.8-flash-high-agy)

- **審查日期**：2026-09-07
- **審查標的**：`44ce484..4797b13` on `main`（重點提交：`f3d4407`、`86c4401`、`4797b13`）
- **審查模式**：唯讀審查（READ-ONLY Review，不修改產品程式碼，不啟動 orchestration workflow mode）
- **依據文件**：`/tmp/qi-r37-brief-safe.md`
- **報告路徑**：`docs/reviews/2026-09-07-round37/gemini-3.8-flash-high-agy.md`
- **總體結論（Verdict）**：**REQUEST CHANGES**
  - **Critical 缺陷**：**3 項**（並行 Replay 誤 commit 已 deferred 列致資料抹除、`settleCarriedOverLosses` 分頁迴圈無防護、三態寫入守衛無負向控制）
  - **Important 缺陷**：**5 項**（20,001 列跨 pass 持續飢餓、全域 resume 於來源交易耦合、Replay 缺執行互斥鎖、查詢計畫未測真實 DAO、解碼空頁邊界未覆蓋）
  - **Minor 缺陷 / 建議**：**4 項**（retry check-then-clear 文件缺口、resume 缺 PENDING 守衛、開發金庫 Hash 不相容提示、UI 措辭測試強度）

---

## 一、審查摘要與判決總結

本輪審查針對第 36 輪結束後的關鍵修復提交 `44ce484..4797b13` 進行全方位的唯讀架構與實證審查。

第 36 輪三位審查者共同觸及的核心盲點在於：**「金庫拒絕寫入的結算（Settlement）缺乏屬於它自己的狀態」**。
在先前的版本中，將結算失敗計入事件的 commit 重試，會導致 3 次失敗後列被標為 `FAILED` 並抹除 payload（第 35 輪問題）；而將其單純留在未結算狀態，則會使其卡在每一頁開頭，導致 200 列失敗列永久飢餓第 201 列（第 36 輪 Codex I1）。

本輪提交 `f3d4407` 引進了三態模型：`event_journal.lossRecorded` 擴展為 0（`LOSS_UNSETTLED`）、1（`LOSS_SETTLED`）與 2（`LOSS_DEFERRED`）。被金庫拒絕的列進入 **Deferred（延期）** 狀態，暫時移出 Replay 與走訪的候選集，保留未消耗的 claim 與原始 payload，並在下一個 pass 的開頭重新放回。同時，作者捨棄了定時器（Timer），改以「帶損失事件成功寫入 gap」的經驗證明（Proof）來武裝單次 Replay 重試。

### 核心判定

1. **架構模型（Model）的方向值得肯定，以「證明（Proof）」武裝重試優於盲目計時器**：
   在 SQLite/SQLCipher 本地儲存的情境下，以「成功寫入 gap 的事件」作為金庫恢復能力的經驗證明，避免了在損壞或空間耗盡的金庫上定時觸發上萬次無效交易的嚴重電量浪費；且在延期期間，事件 payload 完好，attempts 未消耗，retention 不會刪除。
2. **然而，狀態機在並行呼叫下並未安全閉合，引發新的致命 Critical 缺陷**：
   三態模型使得 `claimLoss` 回傳 0（`claimEventLoss` 回傳 `false`）產生了二義性——它既可能代表「已被其他呼叫者成功結算（1）」，也可能代表「先前寫入失敗而被延期（2）」。呼叫端 `recordCarriedOverLoss` 忽略了 Boolean 回傳值，導致在並行 Replay 或舊 batch 預取交錯時，已處於 `DEFERRED` 的列被誤認為安全，進而被呼叫端無條件 `commit` 並清除 payload，**造成唯一遺失證據被靜默抹除的嚴重資料毀損**！
   工作樹中已被作者加入的重現測試（`CaptureCoordinatorTest.kt:1837`）在實跑中已精確爆發 `AssertionError`（`commit` 實際被呼叫），證實此路徑完全可達。
3. **測試防護網仍存在重大盲區**：
   `settleCarriedOverLosses` 的分頁迴圈即使被整段刪除，現有 58 個測試依然 100% 全綠，這使長期暫停的來源在停用/移除時，超過 200 列的部分會被靜默遺失；此外，三態模型新加的兩道核心防禦語句（`deferLoss` 的 `lossRecorded = 0` 與 `isReplayCandidate`）均能用一行改動拆除而整套測試全綠。

基於以上 Critical 缺陷，本輪判定為 **REQUEST CHANGES**。

---

## 二、模型深度評估（The Model Question）

### 1. 三值 `lossRecorded`（0, 1, 2）是正確的形狀嗎？

**答：形狀合理，但契約層級必須顯式化。**

- **本質分析**：
  三態設計將兩項關注點壓縮進了單一資料庫欄位：
  1. *持久的歷史事實*：這筆事件的損失是否已經成功寫入 `gap_interval`（0=否，1=是）。
  2. *短暫的調度狀態*：當前排程是否應暫時略過此列（2=已延期）。
- **權衡評估**：
  若拆分為雙欄位（例如 `lossRecorded: Boolean` + `settleDeferred: Boolean`），在 Schema 上能使「已結算的列絕不被降級」成為結構性不變式，但代價是多一欄與 Schema 演進成本。
  我們評估認為：**保留單一欄位三態是合適的工程權衡**，因為狀態轉換在資料庫層次有嚴格的唯一前驅關係（2 只能由 0 經由 `deferLoss` 轉換而來）。
- **真正的問題所在**：
  問題不在於資料庫欄位是 Int，而在於 **Kotlin API 的抽象洩漏**。儲存庫的 `claimEventLoss` 依然維持舊時代的 `Boolean` 回傳，將「已由他人結算（可 commit）」與「結算延期（不可 commit）」混為一談。
  **修正之道**：將回傳值升級為三態列舉或 Sealed Class（如 `ClaimResult.Won`、`ClaimResult.AlreadySettled`、`ClaimResult.Deferred`），呼叫端只有在 `Won` 或 `AlreadySettled` 時才允許 commit；若為 `Deferred` 則必須保留 payload 並提早退出。

### 2. 挑戰設計選擇：以「證明（Proof）」武裝重試 vs 定時器（Timer）

作者在 BRIEF 中明確挑戰審查者：*「我沒有建 Timer，而是用 Proof 武裝重試。在沒有後續 lossy event 也沒有生命週期觸發時，裝置會維持等待。這項殘留是否比 Timer 更糟？為什麼？」*

**我們的評估結論：作者的選擇是正確的，以 Proof 武裝重試嚴格優於 Timer。**

我們從以下三個維度進行深入剖析：

1. **單次 Pass 的故障成本被放大了 100 倍**：
   - *修復前*：若第一頁 200 列結算皆失敗，`progressed` 維持 `false`，Replay 跑完第一輪就終止。單次成本上限為 200 次失敗交易。
   - *修復後*：由於延期的列會讓出頁面並被計為進度，Replay 迴圈會推進至上限 100 輪。單次 Pass 的成本上限高達 **100 輪 × 200 列 = 20,000 次失敗交易**！
   - 若引入定時器（即使是指數退避至 1 小時），在一個磁碟滿載（ENOSPC）的真實手機上，定時器將週期性發動高達 20,000 次的無效磁碟寫入與 CPU 運算，嚴重消耗使用者電量並造成卡頓。
2. **本地 SQLite 故障並非時間瞬態（Time-transient）**：
   SQLCipher 的寫入拒絕通常是磁碟耗盡、權限問題或檔案損毀，時間本身不會修復磁碟空間。因此時間是錯誤的信號；唯有成功的寫入（Proof）才是金庫可寫的客觀事實。
3. **殘留風險完全可控且不劣於 Timer**：
   在維持延期期間：
   - 事件 payload 原封不動保留在 `event_journal`。
   - 事件的 heavy commit 重試次數（`attempts`）保持為 0，未被虛擲。
   - Retention 清理不會觸碰 `state == 'PENDING'` 的列。
   - 當使用者開啟 App 或裝置重開機時，`VaultState.Ready` 會立即觸發 `replayJournal()`，全域 `resumeDeferredSettlements()` 會在 pass 開頭將所有延期列喚醒重試。
   在行動裝置的使用情境下，使用者每天開啟 App 或解鎖金庫的頻率極高。因此，「在無新 lossy event 時等待生命週期觸發」的代價是極其輕微的，換取的是損壞裝置上 100 倍無效開銷的免除。此決策合理且務實。

---

## 三、缺陷清單 (Findings)

### 1. Critical 缺陷（3 項）— Push 前必須修正

#### 【Critical 1】並行 Replay 會將已 Deferred 的列誤認為安全完成，進而 Commit 並抹除原始 Payload
- **相關位置**：
  - `CaptureCoordinator.kt:1159-1180`
  - `IngestRepository.kt:142-155`
  - `Daos.kt:116`
- **問題分析**：
  1. `replayJournal()` 的整個執行過程缺乏互斥保護，可由 Ready、暫停切換、維護結束或 retry trigger 同時觸發。`resume` 與 `pendingJournal` 均在 `pipelineMutex` 之外，只有逐列處理在鎖內。
  2. 考慮以下合法並發排程：
     - Pass A 與 Pass B 同步啟動，皆預取到同一筆 `(PENDING, lossRecorded=0)` 的事件列 `evt-1`。
     - Pass A 取得 `pipelineMutex`，嘗試為 `evt-1` 寫入 gap。Gap 寫入失敗，交易回滾，Pass A 在 catch 中呼叫 `deferLoss("evt-1")`，將 DB 該列更新為 `lossRecorded = 2`。Pass A 釋放鎖。
     - Pass B 拿著舊 batch 進入 `pipelineMutex`。檢查 `isJournalPending("evt-1")`——因狀態仍為 `PENDING`，檢查順利通過。
     - Pass B 執行 `recordCarriedOverLoss(replay)` -> `claimEventLoss("evt-1")`。
     - 在 `claimEventLoss` 內部，執行 `claimLoss("evt-1")`（SQL：`WHERE eventId = :eventId AND lossRecorded = 0 AND state = 'PENDING'`）。因為該列已被 Pass A 改為 `lossRecorded = 2`，更新 0 列！`won` 為 `false`！
     - `claimEventLoss` 未拋出任何例外，安全回傳 `false`。
     - `recordCarriedOverLoss` 拋棄回傳值，回傳 `Unit`。外層 `runCatching` 視為成功（`settled.isSuccess == true`）。
     - Pass B 繼續往下執行 `processJournaled(...)`，呼叫 `commit`。
     - `commit` 執行 `markCommitted("evt-1")`，將該列設為 `state = 'COMMITTED'`，並**將 payload 設為空字串 `payload = ''`**！
  3. **危害**：原本因 gap 寫入失敗而延期的列，被另一個 pass 毫無阻礙地 commit，事件包含的訊息被存入，但記錄丟失訊息的唯一證據 payload 被清空，且 gap 從未被寫入。這是嚴重的**靜默資料遺失**。
- **如何驗證**：
  - **實跑驗證**：在 `CaptureCoordinatorTest.kt:1837` 執行作者編寫的重現測試 `"a row another pass deferred is not committed by a batch that predates the deferral"`：
    ```kotlin
    coEvery { h.ingest.claimEventLoss(any(), any()) } returns false
    ```
    執行 `./gradlew :platform:capture:testDebugUnitTest`，測試精確失敗於 `CaptureCoordinatorTest.kt:1866`：
    `AssertionError: expected exactly 0 calls to commit, but got 1`。
- **證據界限**：
  - 測試端使用 MockK 驗證 Coordinator 邏輯，並行交錯順序由測試編排確定；SQL 端由 DAO 的 `lossRecorded = 0` 述詞與 Room 回傳值雙向佐證。確證為產品程式碼在邏輯分支上的可達缺陷。
- **如何修正**：
  1. `claimEventLoss` 必須明確回傳狀態（如 `ClaimStatus.WON`、`ClaimStatus.ALREADY_SETTLED`、`ClaimStatus.DEFERRED`），或在發現 `lossRecorded == 2` 時明確拋出延期例外。
  2. `recordCarriedOverLoss` 必須檢查該狀態，若非成功記錄或已記錄，必須視為結算失敗並中斷 commit。
  3. 在 `replayJournal` 外層加入 `Mutex`，確保同一時間只有一個 Replay pass 正在執行，防止舊 batch 交錯。

---

#### 【Critical 2】`settleCarriedOverLosses` 分頁迴圈完全缺乏大於一頁（>200 列）的測試保護，整段移除依然全綠
- **相關位置**：
  - `CaptureCoordinator.kt:1238-1243`
  - `CaptureCoordinatorTest.kt:1522, 1762, 1785`
- **問題分析**：
  1. `settleCarriedOverLosses` 負責在來源停用或移除時，走訪並結清所有未處理列的損失。為了防止長久暫停的來源累積大量待處理列而造成 OOM 或鎖持有過久，程式碼採用了 `while (true)` 搭配 `JournalCursor` 分頁（預設每頁 200 列）。
  2. 檢視 `CaptureCoordinatorTest.kt` 中所有觸發 `settleCarriedOverLosses` 的測試案例（行 1522、1762、1785），測試夾具灌入的 `pendingByPackage` **全部都只有 1 筆列**！
  3. **實跑突變**：若將生產端的 `while (true)` 迴圈整段刪除，替換為僅讀取第一頁：
     ```kotlin
     val page = ingest.pendingJournalForPackage(packageName, JournalCursor.START)
     for (snapshot in page.snapshots) recordCarriedOverLoss(snapshot)
     ```
     編譯並完整重跑 `./gradlew :platform:capture:testDebugUnitTest`，**58 個測試 100% 全部通過（BUILD SUCCESSFUL）！**
  4. **危害**：若某個長期暫停的來源累積了 201 筆待處理列，當使用者關閉或移除該來源時，突變後的代碼只會結算前 200 筆；第 201 筆及其後的列，緊接著會被隨後的 `discardPendingJournal` 直接抹除 payload，造成嚴重的永久損失丟失。
- **如何驗證**：
  - **實跑驗證**：在 `CaptureCoordinator.kt` 套用上述只讀一頁的突變，全套測試全綠；隨後在測試端構造 201 筆待結算列並調用 `setSourceEnabled(pkg, false)`，突變代碼立即爆發缺口未全數寫入之紅燈。
- **證據界限**：
  - 真正編譯並執行了單元測試突變，確認現有測試集對此關鍵分頁迴圈毫無約束力。
- **如何修正**：
  - 在 `CaptureCoordinatorTest.kt` 增加一個超過 200 列（例如 201 列）的來源停用/移除測試，斷言 201 個缺口全數在 `discard` 前寫出。

---

#### 【Critical 3】三態模型新引入之兩道寫入防禦守衛缺乏鑑別性測試，可一行拆除而全綠
- **相關位置**：
  - 守衛 A：`Daos.kt:129`（`deferLoss` 中的 `AND lossRecorded = 0`）
  - 守衛 B：`CaptureCoordinator.kt:1172`（`!isReplayCandidate` 誠實進度檢查）
- **問題分析**：
  1. **守衛 A 缺失鑑別測試**：
     - `deferLoss` 的 SQL 原本帶有 `lossRecorded = 0` 守衛，防止將已結算（`lossRecorded = 1`）的列覆寫為延期（`2`）。
     - 若將其突變為：`UPDATE event_journal SET lossRecorded = 2 WHERE eventId = :eventId AND state = 'PENDING'`。
     - 實跑 `connectedDebugAndroidTest`（47 個測試）**100% 全部通過**。若已結算的列被錯誤降級為 2，後續會被 `resumeDeferredLosses` 變回 0，導致同一筆損失被重複記為兩個 gap！
  2. **守衛 B 缺失鑑別測試**：
     - Replay 在結算失敗時，只有在 `!isReplayCandidate` 為 true 時才將 `progressed` 設為 true（防止金庫連延期寫入都拒絕時死循環）。
     - 若將其突變為無條件設置進度：
       ```kotlin
       ingest.isReplayCandidate(snapshot.eventId)
       if (true) { deferredSettlements = true; progressed = true }
       ```
     - 實跑 `:platform:capture:test` **58 個測試 100% 全部通過**！原因在於測試 Fake 在失敗時總是將其加入 `lossDeferred`，使得該 `if` 的 false 分支從未被覆蓋。
- **如何驗證**：
  - 分別套用上述兩處突變，實際執行 Gradle 測試任務，確認兩者皆完全存活（Silent）。
- **證據界限**：
  - 守衛 A 在真實 Android 模擬器執行了 47 項 instrumented 測試；守衛 B 在 JVM 執行了完整 Coordinator suite。
- **如何修正**：
  1. 針對守衛 A：新增 instrumented 測試，對一筆已成功結算（`lossRecorded = 1`）的列呼叫 `deferLoss`，斷言其回傳 0 且狀態不被降級。
  2. 針對守衛 B：在 `Harness` 增加 `deferralsFail` 模擬旗標，斷言在 defer 寫入亦失敗時，Replay 不會宣稱進度。

---

## 四、Important 缺陷（5 項）

### 【Important 1】全域 `resumeDeferredLosses` 於每次 Pass 開頭重置，導致 20,001 列跨 Pass 持續飢餓
- **相關位置**：
  - `CaptureCoordinator.kt:1128, 1132`
  - `IngestRepository.kt:195`
- **問題分析**：
  - 雖然單次 Replay 透過延期機制解決了同一個 pass 內前 200 列餓死第 201 列的問題，但在**跨 Pass** 的維度上，公平性邊界只被推升到了 20,000 列。
  - 每頁 200 列，單次 Replay 上限 100 輪。若前 20,000 列皆持續寫入 gap 失敗，它們會在 100 輪內全數被設為 2，單次 Pass 耗盡 100 輪結束，第 20,001 列正常事件尚未被讀取。
  - 當下一個觸發事件（如收到新事件或解鎖）到達時，Replay 在開頭呼叫 `resumeDeferredLosses()`，將前 20,000 列**全數重置為 0**。下一輪 Replay 再次從第 1 列開始嘗試，再次耗盡 100 輪。**第 20,001 列將跨 Pass 永久處於飢餓狀態**。
- **如何驗證**：
  - 模型分析與 SQL 語意推導：20,000 筆持續失敗列加 1 筆尾部正常列，連續 3 次 Replay 呼叫，尾列始終未被處理。
- **證據界限**：
  - 基於 Replay 迴圈控制流與 DAO 述詞之推導，未在真實裝置注入 20,001 筆巨量資料。
- **如何修正**：
  - 避免每次 Replay 都全域將所有歷史延期列無腦插回隊首；可引入基於輪次（Pass ID）或排程標記的延期過濾，確保未走完的掃描能繼續向後推進。

---

### 【Important 2】`resumeDeferredLosses` 於單一來源政策交易中執行全域重置，造成跨來源狀態耦合與全表掃描
- **相關位置**：
  - `Daos.kt:140`
  - `CaptureCoordinator.kt:1237`
- **問題分析**：
  - `settleCarriedOverLosses(packageName)` 僅處理指定 `packageName` 的來源，但在開頭呼叫的 `resumeDeferredLosses()` 卻是無條件的 `UPDATE event_journal SET lossRecorded = 0 WHERE lossRecorded = 2`。
  - 當使用者停用或移除來源 A 時，來源 B 的延後列也在來源 A 的政策交易內被重置為 0。若 A 的交易因故 Abort，B 的列隨之一同回滾；若 A 成功，B 的列被過早放回 Replay 候選集。
  - 此外，因 `lossRecorded` 未建立前綴索引，此全域更新在含有大量日誌的資料庫中需執行全表線性掃描。
- **如何驗證**：
  - 檢視 `Daos.kt:140` 之 SQL 與 `CaptureCoordinator.kt:1237` 之呼叫上下文，確無 `packageName` 隔離。
- **證據界限**：
  - 原始碼追蹤與 SQLite 查詢計畫分析。
- **如何修正**：
  - 走訪路徑改用 per-source 版本：`UPDATE event_journal SET lossRecorded = 0 WHERE lossRecorded = 2 AND packageName = :packageName`。

---

### 【Important 3】`replayJournal` 缺乏執行級互斥鎖，允許並行 Replay 交錯執行
- **相關位置**：
  - `CaptureCoordinator.kt:1120`
  - `VaultMaintenance.kt:65-78`
- **問題分析**：
  - `VaultMaintenance.work` 僅防止與 exclusive 維護衝突，但允許多個一般的 `work` 工作並行執行。
  - `replayJournal()` 的觸發點遍布各處（金庫就緒、暫停切換、重試觸發等），當多個觸發事件連續發生時，會啟動多個協程並行執行 `replayJournal()`。
  - 由於各 pass 的批次讀取未加鎖，多個 pass 會預取到相同的列集合，不僅放大了磁碟讀取負擔，更直接引發了 Critical 1 的資料遺失競態。
- **如何驗證**：
  - 原始碼結構分析：`work` 區塊無任何針對 Replay 本身的 Mutex。
- **證據界限**：
  - 原始碼並發模型分析。
- **如何修正**：
  - 在 `CaptureCoordinator` 內部為 `replayJournal` 配置專屬的 `replayMutex`，使用 `tryLock`；若已有 Replay 正在執行，後續的 Replay 請求可直接安全返回或合併。

---

### 【Important 4】查詢計畫測試未測試真實 DAO SQL，且斷言分不出完整游標 Seek 與退化語法
- **相關位置**：
  - `JournalLossTransactionTest.kt:334-355`
  - `Daos.kt:98`
- **問題分析**：
  - `theSettleWalkSeeksToItsCursorInsideTheIndex` 測試中使用手寫的字串常數執行 `EXPLAIN QUERY PLAN`，而非呼叫或反射 Room DAO 真實編譯出的 SQL 語句。若 DAO 中的查詢發生變更或退化，此測試無法捕捉。
  - 此外，現有斷言僅檢查 `plan shouldContain index_...` 且不包含 `SCAN` 與 `TEMP B-TREE`。若將游標條件改為無法使用完整索引 seek 的表達式（如 `(receivedAtEpochMs + 0, eventId) > (?, ?)`），執行指令數暴增 12 倍，但上述斷言依然全部通過！
- **如何驗證**：
  - 檢視 `JournalLossTransactionTest.kt:334` 之測試實作；比對退化 SQL 之 EXPLAIN 輸出。
- **證據界限**：
  - 原始碼分析與 SQLite 查詢計畫推導。
- **如何修正**：
  - 透過 Room 框架或執行真實 DAO 查詢來捕捉實際執行的查詢計畫，並增加針對虛擬機器指令數或掃描範圍的強度斷言。

---

### 【Important 5】解碼失敗測試未真正構造全頁 Snapshots 為空的關鍵邊界
- **相關位置**：
  - `JournalLossTransactionTest.kt:303-324`
- **問題分析**：
  - 在 `aPageThatWillNotDecodeStillMovesTheWalkOn` 測試中，5 個事件（`p1`~`p5`）以 `limit = 2` 查詢，測試將 `p2, p3` 設為損毀。
  - 實際產生的三個 raw pages 為 `[p1, p2]`、`[p3, p4]`、`[p5]`，每一頁都恰好包含至少一筆可解碼的列（`p1`、`p4`、`p5`）。
  - 因此，`JournalPage.snapshots` 在每一頁**從來沒有為空過**！
  - 若在 `IngestRepository` 中人為注入突變「當 `snapshots.isEmpty()` 時直接回傳 `next = null` 終止」，該測試依然會綠燈通過。測試未能真正覆蓋整頁 raw rows 皆無法解碼時游標依然前進的極限情境。
- **如何驗證**：
  - 追蹤測試資料分布與分頁切割邏輯。
- **證據界限**：
  - 邏輯推導與分頁切割分析。
- **如何修正**：
  - 將損毀列改為 `p3, p4`（使得第二頁整頁為空）或配置整頁 2 筆皆損毀，斷言該頁 `snapshots` 為空而 `next` 依然非 null 且後續正常列依然能被讀出。

---

## 五、Minor 缺陷 / 建議（4 項）

### 【Minor 1】`retryDeferredSettlements` 之 check-and-clear 依賴外部持鎖，KDoc 契約未寫明
- **相關位置**：`CaptureCoordinator.kt:552-556`
- **分析**：`deferredSettlements` 為非原子的 `@Volatile Boolean`，其 check-and-clear 之執行緒安全性完全依賴於所有呼叫點皆處於 `pipelineMutex` 保護下。KDoc 未載明此必須持鎖的前置條件，容易在未來維護時被無鎖呼叫引入競態。建議改用 `AtomicBoolean.getAndSet(false)` 或在註解中強制聲明。

### 【Minor 2】`resumeDeferredLosses` 缺少 `state = 'PENDING'` 述詞守衛
- **相關位置**：`Daos.kt:140`
- **分析**：若某列延期後因故被標記為 `DISCARDED`，全域 resume 會將其由 2 轉為 0，雖不影響後續讀取（讀取皆限定 PENDING），但會虛增該 SQL 回傳的受影響筆數，影響日誌與測試斷言。建議補上 `AND state = 'PENDING'`。

### 【Minor 3】未出貨之開發用舊版 schema 4 金庫因 Identity Hash 變更無法自動遷移
- **相關位置**：`QuietInboxDatabase.kt:126-131`
- **分析**：在未發布的開發版本中若已升級至舊版 schema 4，本輪增加索引導致 Room 的 `identityHash` 變動。因版本未升級，`MIGRATION_3_4` 不會重新執行，開發裝置開啟金庫時將拋出 Room 資料完整性檢驗失敗之例外。建議在開發者文件或 RELEASE 備忘中載明此情形需清除本機資料重開。

### 【Minor 4】UI 測試 `MessageBubbleSemanticsTest` 僅驗證 chip 節點存在，未獨立鎖定歷史文案字串
- **相關位置**：`MessageBubbleSemanticsTest.kt:33, 112`
- **分析**：測試中的預期文字與 UI 渲染皆指向同一個資源 `R.string.conv_truncated`。若該資源字串被回退至舊措辭，測試依然綠燈。註解宣稱的「wording is checked too」並不成立。

---

## 六、BRIEF 6 大具體宣稱（Claims）之深度檢驗與回答

| # | BRIEF 提出的核心問題 | 審查結論 | 核心技術證據與分析 |
|---|---|---|---|
| **1** | **狀態機是否封閉？**<br>0→1, 0→2, 2→0 之外是否有逃逸路徑？有無到 1 無 gap？到 2 出不來？或被 discard 偷清？ | **未完全閉合**<br>（存在重大缺陷 C1） | 1. 寫入 1 均在同交易伴隨 gap 寫入，無單獨變 1 路徑；<br>2. 變 2 之列可由 Replay 或走訪開頭的 resume 喚醒；<br>3. Discard 前皆有走訪 resume，不會被 discard 偷清；<br>4. **致命漏洞（C1）**：並行 Replay 下，處於 2 的列會讓 `claimLoss` 回傳 0，呼叫端將 `false` 視為已結算，直接執行 commit 並將 payload 抹除！ |
| **2** | **全域 `resumeDeferredLosses` 放於來源政策交易內是否正確？**<br>中止時對其他來源的影響？ | **作用範圍過大，造成跨來源耦合** | 交易若 Abort，SQLite 會將其他來源一併回滾，無資料不一致；但若 Commit，會無故將其他無關來源的列提前喚醒。且全表掃描成本隨日誌線性增長。應改為 per-source resume。 |
| **3** | **Replay 的進度會計是否誠實？**<br>並行下是否會被干擾？100 輪是否仍是有界上限？ | **方向保守，有界性成立，但缺乏測試** | 1. 若被其他 pass 提前 resume，`!isReplayCandidate` 為 false，會低報進度並提早退出，不會死循環；<br>2. 迴圈帶有 `rounds++ < 100` 硬性計數器，單次 Pass 絕對有界；<br>3. 但現有測試 Fake 永遠回傳 false，此進度守衛一行拆除全綠（C3）。 |
| **4** | **複合索引與 Row-value 游標是否安全？**<br>minSdk 26 下所有 API level 皆安全嗎？`lossRecorded = 0` 是否傷及其他查詢？IF NOT EXISTS 對舊 schema 4 有效嗎？ | **語法與引擎絕對安全，舊 4 不相容** | 1. **SQLCipher 內建 SQLite 3.53.4**（AAR 實體驗證），遠高於 row value 所需的 3.15.0，與 Android framework SQLite 完全脫鉤，所有 API level 皆相容；<br>2. 走訪獲取 Covering Index Seek，`discardPending` 吃兩欄前綴，其餘查詢不受影響；<br>3. 舊 schema 4 開發金庫因 Hash 變動無法遷移，需手動重設。 |
| **5** | **Retry 武裝是否安全？**<br>會否死鎖、洩漏、活過維護？會否兩者同時武裝？ | **生命週期安全，但缺少並行互斥** | 1. 呼叫 `scope.launch` 非同步啟動，持鎖呼叫端立即放鎖，無死鎖；<br>2. `maintenance.work` 在維護期間安全阻絕；<br>3. 持有 `pipelineMutex` 確保 check-and-clear 單一進入；但發動的多個 Replay pass 在背景並行執行，導致 C1。 |
| **6** | **測試的對抗性如何？**<br>新測試能否防禦單行產品碼變更？ | **存在 3 處關鍵無感突變** | 1. `settleCarriedOverLosses` 分頁迴圈整段刪除全綠（C2）；<br>2. `deferLoss` 移除 `lossRecorded = 0` 全綠（C3）；<br>3. Replay 進度會計改為無條件 true 全綠（C3）。 |

---

## 七、第 36 輪 7 項變異之重跑比對表

依 BRIEF 指引，對現在的程式碼（HEAD `4797b13`）重新執行第 36 輪的 7 項經典突變：

| # | 第 36 輪突變項目 | 第 36 輪結果 | **第 37 輪現行結果** | 說明與現況判定 |
|---|---|---|---|---|
| **1** | 刪除 `settleCarriedOverLosses` 的分頁 `while` 迴圈 | 靜默漏列，無測試 | **仍然全綠（存活）** | **Critical 2**。Harness 改為支援分頁，但無測試注入 >200 列之來源資料。 |
| **2** | `JournalPage.next` 固定回傳 `null` | 同上，無測試 | **變紅（成功抓到）** | 被 `theSettleWalkVisitsEveryPendingRowOfTheSourceOnceInOrder` 抓到。 |
| **3** | 游標條件 `> :afterId` 改為 `>=` | 重複列，無測試 | **變紅（成功抓到）** | 邊界列重複導致 `visited shouldBe all` 失敗。 |
| **4** | `rows.size == limit` 改為 `isNotEmpty()` | 無害，無測試 | **全綠（語意等價）** | 短頁給 next 只多一次空查詢，KDoc 已更新契約，屬無害控制。 |
| **5** | `ORDER BY` 移除 `eventId` | 靜默漏列，無測試 | **全綠（無害）** | 複合索引已提供相同全序保證。真正破壞 tiebreak 的突變是紅的。 |
| **6** | 游標改取 `snapshots.lastOrNull()`（解碼列） | 提早停止，無測試 | **變紅（成功抓到）** | 被 `aPageThatWillNotDecodeStillMovesTheWalkOn` 抓到（但見 I5）。 |
| **7** | 時間條件 `>` 改 `>=`（無界迴圈） | 無測試 | **此突變已不復存在** | Row value 語法塌縮為單一 `>` 述詞，改 `>=` 即等同 #3，為紅燈。 |

---

## 八、閘門驗證數據與環境紀錄

所有閘門皆在本機與 `emulator-5556` 上實際執行並取得輸出驗證：

| 驗證項目 | 命令 / 依據 | 執行結果 | 備註 / 界限 |
|---|---|---|---|
| **JVM 全套測試** | `./gradlew test` | **59 tests, 1 failure**（在含作者 C1 重現測試時）<br>（未含該測試時為 263 passed） | 精確爆發於 `CaptureCoordinatorTest.kt:1866`，證實 Critical 1 缺陷。 |
| **Storage 儀器測試** | `ANDROID_SERIAL=emulator-5556 ./gradlew :platform:storage:connectedDebugAndroidTest` | **47 tests / 0 failures**（耗時 2m 22s） | 驗證通過，HTML 報表確認 47 項全部成功。 |
| **Conversation 儀器測試** | `ANDROID_SERIAL=emulator-5556 ./gradlew :feature:conversation:connectedDebugAndroidTest` | **5 tests / 0 failures**（耗時 41s） | 包含截短標籤語意測試，HTML 報表確認 5 項全部成功。 |
| **Android Lint** | `./gradlew lintDebug` | **0 errors** | `abortOnError = true` 保持乾淨通過。 |
| **APK 權限檢查** | `tools/check-permissions.sh app/.../app-debug.apk` | **OK** | 確認無任何網路權限（`android.permission.INTERNET` 不存在）。 |
| **多語系字串完整度** | `python3 tools/check-strings.py` | **OK** | 0 error(s), 0 warning(s)，五國語系完全齊全。 |
| **SQLCipher 版本檢驗** | 檢視 `sqlcipher-android-4.18.0.aar` 內 `libsqlcipher.so` | **SQLite 3.53.4** | 確證 Row-value 游標在所有支援設備上皆安全。 |

---

## 九、修正建議清單 (Action Items)

為使程式碼達到可交付標準，建議依下列順序進行修復：

1. **修復 Critical 1（閉合狀態機與呼叫契約）**：
   - 修改 `claimEventLoss`，使其精確回傳包含 `DEFERRED` 的狀態，或在列處於延期狀態時拋出例外。
   - `recordCarriedOverLoss` 必須判斷該狀態，禁止將已 Deferred 的列視為可結算完成。
   - 保留 `CaptureCoordinatorTest.kt:1837` 的回歸測試，驗證修復後不再呼叫 `commit`。
2. **為 Replay 增加互斥鎖（修復 Important 3）**：
   - 在 `CaptureCoordinator` 內部為 `replayJournal` 配置 `replayMutex`（`tryLock`），防止多個 Replay pass 並行交錯。
3. **補齊 Critical 2 之分頁測試**：
   - 在 `CaptureCoordinatorTest.kt` 中加入 201 筆待處理列的來源停用測試，確保分頁迴圈不被破壞。
4. **補齊 Critical 3 之負向控制測試**：
   - 增加已結算列不可被 `deferLoss` 降級的 instrumented 測試。
   - 增加模擬 `deferLoss` 失敗時 Replay 不得宣稱進度的 Coordinator 測試。
5. **解耦來源走訪之全域重置（修復 Important 2）**：
   - 在 `Daos.kt` 增加 per-source 的 `resumeDeferredLossesForPackage`，於 `settleCarriedOverLosses` 中僅重置該來源的延後列。
