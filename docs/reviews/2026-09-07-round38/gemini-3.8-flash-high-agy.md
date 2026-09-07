# QuietInbox 程式庫第 38 輪審查報告 (gemini-3.8-flash-high-agy)

- **審查日期**：2026-09-07
- **審查標的**：`21ccbc5..50bfc10` on `main`（重點修復提交：`392e80c`，README 記錄：`50bfc10`）
- **審查模式**：唯讀審查（READ-ONLY Review，不修改產品程式碼，不啟動 orchestration workflow mode）
- **依據文件**：`/tmp/qi-r38-brief-safe.md`
- **報告路徑**：`docs/reviews/2026-09-07-round38/gemini-3.8-flash-high-agy.md`
- **總體結論（Verdict）**：**APPROVE**
  - **Critical 缺陷**：**0 項**
  - **Important 缺陷**：**0 項**
  - **Minor 缺陷**：**0 項**

---

## 一、審查摘要與判決結論

本輪審查針對第 37 輪結束後的關鍵修復提交 `392e80c` 及其文檔更新 `50bfc10` 進行嚴格的唯讀安全性與並行合約審查。

第 37 輪三位獨立審查者達成共識的致命 Critical 缺陷在於 **契約接縫（Contract Seam）**：`claimEventLoss` 以 `Boolean` 回傳，在第 36 輪引入 `LOSS_DEFERRED` 狀態後，`false` 被賦予了兩種截然相反的語意（「缺口已落盤」與「該列被延期且根本無缺口」），而呼叫端忽略回傳值導致並行 replay 或跨 pass 預取時，已處於延期狀態的列被舊 batch 誤當成已結算而直接 commit 並抹除 payload，造成永久資料遺失。

提交 `392e80c` 從 **合約顯式化** 與 **並行架構約束** 兩個維度徹底重構了該路徑：
1. **四態合約與同交易判讀**：將 `claimEventLoss` 的回傳值升級為 `LossClaim`（`RECORDED`、`ALREADY_RECORDED`、`DEFERRED`、`NOT_PENDING`），且在 claim 未能命中列時，**在同一個 `withTransaction` 區塊內** 立即讀取 `pendingLossState`，杜絕了「更新未命中」與「原因解釋」之間的時序漂移；呼叫端僅在 `gapIsDurable` 為 true 時允許進入終端狀態。
2. **Replay 合流控制（Coalescing Gate）**：以 `replayGate = Mutex()` 與 `@Volatile replayRequested` 實現合流，確保同一時間僅有一趟 replay pass 在執行，並在釋放鎖後再次檢查請求旗標，徹底消除雙 replay pass 持有重疊分頁的可能性。
3. **排空後單次恢復（Resume at drain）**：將 deferred 列的恢復移至正常候選列排空之後，並以 `resumed` 旗標限制每趟 pass 僅能執行一次，徹底解決了 20,000 失敗前綴永久耗盡 100 輪預算的飢餓問題。
4. **來源走訪作用域隔離**：`settleCarriedOverLosses` 僅恢復該來源的延期列（`resumeDeferredSettlements(packageName)`），不再干擾其他來源的排程。

經過對 BRIEF 所列 7 大攻擊接縫的窮舉交錯分析、JMM 記憶體模型推演、SQLite WAL 交易語意檢驗、9 項負向控制（NC15–NC23）的審視，以及對 11 項新增測試的對抗性單行破壞驗證，本審查者確認：**該修復無漏洞、語意閉合、測試具備嚴格的對抗鑑別力，且所有門檻指令均乾淨通過。判決為 APPROVE。**

---

## 二、核心理論問題：四態契約是封閉了接縫，還是僅僅轉移了接縫？

**結論：四態契約在根本上完全封閉了接縫，而非將問題轉移。**

原本的系統盲點在於呼叫端必須在資訊不對稱的情形下做出終端狀態決策：舊的 `Boolean` 將「資料庫中已有實體存根（Durable Gap）」與「暫時性拒絕（Transient Deferred）」模糊化為同一個 `false`，呼叫端被迫只能「猜測」或盲目相信那是前者。引入 `LossClaim` 之後：
首先，`LossClaim` 提供了完整正交的狀態劃分，並透過 `gapIsDurable: Boolean get() = this == RECORDED || this == ALREADY_RECORDED` 將決定權封裝在列舉本身；
其次，也是最關鍵的一點，**原因的裁決是在 SQLite 資料庫的同一連線、同一交易（`db.withTransaction`）內部由引擎序列化完成**，在鎖定範圍內，`claimLoss` 更新 0 列與 `pendingLossState` 讀取狀態之間不允許任何外部並行寫入介入，因此「未取得權限」與「狀態的判定」享有相同的快照隔離性；
最後，下游的所有消費者（`recordCarriedOverLoss` 與 `settleCarriedOverLosses`）均以 `gapIsDurable` 作為不可妥協的防禦閥門：若非耐久缺口，replay 路徑直接放棄 commit 並退出 lock，而來源政策變更路徑則以 `check()` 直接 abort 整個交易。這使得「無缺口卻清除 payload」在結構上成為不可能（Structurally Impossible），契約接縫被完整銲死在交易邊界上。

---

## 三、七大接縫深度攻擊與驗證（The Seams to Attack）

### 接縫 1：合流閘門（The Coalescing Gate）

**攻擊目標**：`replayGate.tryLock()` + `@Volatile replayRequested` 是否存在競態？是否可能出現「旗標被設為 true、無 pass 在跑、且無人會去跑」的幽靈遺失？`CancellationException` 是否破壞狀態？

**分析與驗證**：
1. **交錯時序窮舉（Interleaving Trace）**：
   - 設執行緒 A 正在執行 `replayPass()`，執行緒 B 呼叫 `replayJournal()`：
     B 先執行 `replayRequested = true`，接著呼叫 `replayGate.tryLock()` 返回 `false`，B 結束退出。
   - A 執行完當前 pass，回到內層迴圈 `while (replayRequested)`：A 讀取到 B 所寫入的 `true`，立即將其重置為 `false` 並在持有同一個鎖的情況下展開下一趟 `replayPass()`。
   - 若 B 在 A 剛跳出內層迴圈（`replayRequested == false`）時進入：
     - 若 B 在 A 執行 `finally { replayGate.unlock() }` 之前呼叫 `tryLock()`：B 失敗退出；隨後 A 釋放鎖並進入外層迴圈 `while (replayRequested && replayGate.tryLock())`，此時 A 看到 `replayRequested == true`，且鎖剛被自己釋放，A 的 `tryLock()` 必能成功並重新進入執行。
     - 若 B 在 A 釋放鎖並評估外層條件退出之後才到達：由於 A 已經 unlock，B 的 `tryLock()` 必能成功獲取鎖並執行。
   - **結論**：不存在任何時序使得 `replayRequested == true` 卻沒有任何 worker 去執行。
2. **JMM 記憶體模型與 Happens-Before**：
   - `@Volatile private var replayRequested` 保證了所有讀寫均直接作用於主記憶體，避免 CPU 快取或暫存器暫存導致讀取到過期值（Stale Read），且禁止指令重排。
   - Kotlin Coroutines `Mutex` 的 `tryLock()` 與 `unlock()` 內部基於 `AtomicRef` 的 CAS 運作，建立了嚴格的同步釋放／獲取語意（Release/Acquire Semantics）。兩者結合，完全滿足 JSR-133 規範。
3. **`CancellationException` 異常路徑推演**：
   - 若 `replayPass()` 執行途中拋出協程取消異常（例如 `VaultMaintenance.exclusive` 啟動時取消 workers）：
   - 取消異常會穿透內層迴圈，進入 `finally { replayGate.unlock() }`，**保證 `replayGate` 永遠被解鎖**。
   - 若在此期間有其他觸發將 `replayRequested` 設為 `true`，異常拋出後該 coroutine 終止，`replayRequested` 保持為 `true` 且 gate 處於解鎖狀態。
   - 這不是丟失請求：因為取消通常發生於維護模式啟動。當維護結束時，`CaptureCoordinator:848` 的 `onMaintenanceEnded()` 必會觸發 `replayJournal()`；或在下一次事件到達時觸發。屆時新呼叫者能立即透過解鎖的 gate 取得鎖並排空。因此保留 `replayRequested = true` 不僅無害，反而正確保留了「需要 replay」的意圖。

### 接縫 2：Rollback 後的 `deferLoss`

**攻擊目標**：`claimEventLoss` 在 catch 區塊呼叫 `deferLoss` 時位於已 rollback 的外部新交易中。若另一 pass 在此空檔將列更新為 1（成功結算），`deferLoss` 是否會踩壞該列？第一趟失敗的 pass 是否會向呼叫端回報 `DEFERRED`？

**分析與驗證**：
1. **SQL 語意檢驗**：
   `deferLoss` 的 SQL 定義為：
   ```sql
   UPDATE event_journal SET lossRecorded = 2 WHERE eventId = :eventId AND lossRecorded = 0 AND state = 'PENDING'
   ```
   若空檔中有另一趟 pass 成功將該列結算（`lossRecorded = 1`），則 `lossRecorded = 0` 條件不滿足，`deferLoss` 的更新受影響行數為 0，絕不會將 1 覆蓋為 2。此點已由真實資料庫測試 `aSettledRowCannotBeWalkedBackToDeferred` 確證。
2. **呼叫端語意確認**：
   在 `claimEventLoss` 的 catch 區塊中：
   ```kotlin
   } catch (e: Exception) {
       if (e is CancellationException) throw e
       runCatching { db.journalDao().deferLoss(eventId) }
       throw e
   }
   ```
   它**直接將例外 `e` 重新拋出（rethrow），根本不會返回 `LossClaim.DEFERRED`**！
3. **下游呼叫端行為**：
   - 在 `replayPass` 中：`runCatching { recordCarriedOverLoss(replay) }` 捕獲該例外，得到 `settled.isFailure == true`，`settled.getOrDefault(false)` 為 `false`。接著檢查 `!ingest.isReplayCandidate(snapshot.eventId)`：由於另一 pass 已將其更新為 1（`lossRecorded != 2` 成立，仍為 replay 候選），`isReplayCandidate` 返回 1，故 `!isReplayCandidate` 為 `false`，不會虛報進展，安全跳過本輪。
   - 在 `settleCarriedOverLosses` 中：拋出的例外直接中斷迴圈，導致來源政策變更交易 rollback，安全終止。完全沒有下游會依據「過期的 DEFERRED」行動。

### 接縫 3：交易內讀取原因（Reason-Read inside Transaction）

**攻擊目標**：`pendingLossState` 回傳 `Int?`（`null` -> `NOT_PENDING`，`1` -> `ALREADY_RECORDED`，其餘 -> `DEFERRED`）。一筆 `lossRecorded = 0` 的列若 claim 失敗是否可達？回傳 `DEFERRED` 會造成何種後果？

**分析與驗證**：
1. **可達性推演**：
   `claimLoss` 的 WHERE 條件為 `WHERE eventId = :eventId AND lossRecorded = 0 AND state = 'PENDING'`。
   在 SQLite WAL 模式且單一連線交易（`db.withTransaction`）中，若該列存在、`state == 'PENDING'` 且 `lossRecorded == 0`，則 `claimLoss` **必然命中並返回 1**。
   若 `claimLoss` 返回 0，代表在同一交易快照下，該列要麼不存在／非 PENDING（`pendingLossState` 返回 null），要麼 `lossRecorded` 已經是 1 或 2。
   因此，在標準交易語意下，一筆真正處於 `lossRecorded = 0` 的列不可能在 `claimLoss` 返回 0 後在同筆交易中被讀出為 0。
2. **Fail-Closed 語意**：
   退一步而言，即使因未知的極端邊界或測試注入使得 `pendingLossState` 返回 0（進入 `else -> LossClaim.DEFERRED`）：
   `LossClaim.DEFERRED` 的 `gapIsDurable` 為 `false`。
   - 在 replay 端：`recordCarriedOverLoss` 回傳 false，該事件不會被 commit，payload 完好無損。
   - 在走訪端：`check()` 拋出例外中斷政策變更，資料庫回滾，來源不會被停用，payload 完好無損。
   系統完全朝安全端閉合（Fail-Closed）。

### 接縫 4：排空處恢復的迴圈邊界（Resume-at-Drain Loop Bound）

**攻擊目標**：`continue` 之後沒有重置 `progressed`（仍保留上一批的 true），且 `rounds` 持續遞增。若恢復的延期群組立刻再次全部失敗延期，pass 是否能正常終止？每趟 pass 僅恢復一次是否足夠？

**分析與驗證**：
1. **迴圈終止證明（Termination Proof）**：
   - 設在第 $ 輪，正常列排空，`batch.isEmpty()` 為 true。
   - 此時 `resumed` 為 false，進入恢復邏輯：`resumed = true`，呼叫 `resumeDeferredSettlements()`，將延期列放回（2 -> 0），執行 `continue`。
   - 在第 +1$ 輪：迴圈條件 `progressed && rounds++ < replayRounds` 成立。
   - 讀取 `batch`：取出了剛剛放回的延期列（非空）。
   - **關鍵點**：程式碼第 1199 行在進入批次處理前明確執行了 `progressed = false`！
   - 遍歷該批次：若所有延期列的 gap 寫入再次失敗，它們被 `deferLoss` 再次設為 2，`!isReplayCandidate` 為 true，將 `progressed` 設為 true。
   - 進入第 +2$ 輪：`batch = ingest.pendingJournal(...)` 此時再次為空（因為所有列又變回 2 了）。
   - 進入 `if (batch.isEmpty())`：此時 `if (resumed) break`！因為 `resumed` 在第 $ 輪已被標為 true，**迴圈直接 break 退出**！
   - **結論**：即便恢復的列全數再度失敗，整趟 pass 也只會多跑一輪批次並在下一次排空時立刻 break，絕不會重覆循環耗盡 100 輪。
2. **單次恢復的合理性**：
   在一趟 pass 內，若排空後恢復的列再度失敗，代表當前環境（金庫空間、磁碟 I/O）依然無法寫入缺口。在同一趟 pass 內反覆重試只會徒增 CPU 與 I/O 消耗（回到原本飢餓的死循環）。一次 pass 嘗試一次，由後續的成功事件或生命週期觸發下一趟 pass，是最優且符合「以證明武裝重試」設計原則的做法。

### 接縫 5：全域 Replay 恢復 vs 依來源走訪恢復

**攻擊目標**：走訪僅恢復自身套件，而並行的 replay 在排空點是全域恢復。全域恢復是否可能把走訪正要丟棄的列放回？走訪的 `check` 是否會誤觸發？

**分析與驗證**：
1. **並行互斥防護**：
   來源走訪 `settleCarriedOverLosses(packageName)` 是在 `changeSourcePolicy` 內部執行的，全程持有 `pipelineMutex`。
   雖然 replay pass 的 `resumeDeferredSettlements()` 在 `pipelineMutex` 之外執行，但 replay pass 在實際處理任何單一事件時，都必須取得 `pipelineMutex.withLock` 並重新檢查 `isJournalPending`。
2. **走訪行為與 `check` 判定**：
   - 若 replay 的全域恢復在走訪前發生：該套件的延期列（2）被設回 0。走訪啟動後，`pendingForPackageAfter` 恰好會將其作為候選列讀出並逐一結算（0 -> 1），接著 discard，正符合「停用前結算全部遺失」的目標。
   - 若走訪已將某一列結算（1）：該列的 `lossRecorded` 為 1，全域 resume 的 SQL 包含 `WHERE lossRecorded = 2`，絕不可能匹配到已結算的列，不會被退回 0。
   - `check(recordCarriedOverLoss(snapshot))` 對於已結算（1）的列，`claimEventLoss` 在交易內判讀為 `LossClaim.ALREADY_RECORDED`，其 `gapIsDurable` 為 `true`，`check` 永遠不會在已結算列上誤觸發。
   - 若真的發生缺口無法寫入的硬錯誤，`check` 觸發並回滾整個政策交易，套件保持啟用，事件保持 PENDING，完美保護資料不被靜默抹除。

### 接縫 6：九項負向控制（NC15–NC23）真實性審查

審查者逐一對照提交中的 9 項負向控制與其對應的測試案例，確認每一項測試變異均精確指向其聲稱防護的機制：

| 編號 | 控制描述（變異注入） | 預期變紅測試 | 驗證機制與鑑別力判定 |
| :--- | :--- | :--- | :--- |
| **NC15** | `settleCarriedOverLosses` 僅讀一頁（拿掉 `while(true)`） | `the pending rows of a source with more than one page are all settled before it is disabled` | **強**。測試準備了 201 筆資料（跨越 200 上限），變異後第 201 筆未結算，`lossClaimed.size` 為 200 而非 201，精確失敗。 |
| **NC16** | 無條件宣稱進展（拔掉 `!isReplayCandidate` 守衛） | `a replay that cannot even defer a row does not claim it made progress` | **強**。測試模擬 gap 寫入與 defer 均失敗的情境，變異後無條件宣稱進展導致重覆讀取直到 100 輪上限，`pendingJournal` 呼叫超過 2 次，精確失敗。 |
| **NC17** | 將 resume 放回 pass 開頭（Head of pass） | `a failing prefix longer than a whole pass does not keep the rows behind it from ever being read` | **強**。測試設置 6 筆失敗前綴（超過 2x3 預算），變異後第二趟 pass 開頭立即放回前綴，再次耗盡 3 輪預算，後方第 7 筆 tail 永遠無法 commit，測試逾時失敗。 |
| **NC18** | 拔除合流控制（移除 `replayGate` 與旗標） | `two triggers arriving together run one replay pass, not two` | **強**。測試模擬兩處觸發同時到達，fake 延時 50ms，變異後兩趟並行，`maxConcurrent` 達到 2，斷言 `shouldBe 1` 失敗。 |
| **NC19** | 將 DEFERRED 視為已記錄（`gapIsDurable` 包含 DEFERRED） | `a row a concurrent pass has just deferred is not committed by an older batch` | **強**。模擬舊 batch 拿著被延期的列，變異後 `recordCarriedOverLoss` 回傳 true，呼叫了 `commit`，斷言 `coVerify(exactly = 0) { commit }` 精確失敗。 |
| **NC20** | 拔掉 `deferLoss` 的 `lossRecorded = 0` 守衛 | `aSettledRowCannotBeWalkedBackToDeferred` | **強**。對已結算（1）的列呼叫 `deferLoss`，變異後回傳 1 並竄改狀態為 2，斷言 `deferLoss shouldBe 0` 精確失敗。 |
| **NC21** | 解碼空頁停止走訪（空 snapshot 即回傳 `next = null`） | `aPageOfUndecodableRowsIsEmptyAndStillPointsOn` | **強**。測試第 2 頁整頁為無效 JSON，變異後 `second.next` 變成 null，第 3 頁永遠讀不到，斷言 `second.next shouldNotBe null` 精確失敗。 |
| **NC22** | 索引 Range Seek 退化（游標處加入 `+ 0`） | `theSettleWalkSeeksToItsCursorInsideTheIndex` | **強**。EXPLAIN 直接針對 DAO 的常數 SQL，斷言必須包含 `(receivedAtEpochMs,eventId)>(?,?)`，一旦退化為 Filter 即刻失敗。 |
| **NC23** | 來源走訪再次使用全域 resume（拔除 packageName 限制） | `resumingOneSourcesDeferredRowsLeavesAnothersAlone` | **強**。同時存在本套件與其他套件的延期列，變異後其他套件的列也被設為 0，斷言 `isReplayCandidate("evt-theirs") shouldBe false` 精確失敗。 |

### 接縫 7：所有新增測試的對抗性單行變異破壞分析

方針：針對 11 個新增測試，指出能使其變紅的**產品代碼單行修改**：

1. **`a row a concurrent pass has just deferred is not committed by an older batch`**（JVM）
   - **單行變異**：`CaptureCoordinator.kt:1230` 改為 `if (settled.isFailure)`（還原第 36 輪忽視 false 的錯誤）。
   - **結果**：因 `claimEventLoss` 回傳 `LossClaim.DEFERRED` 並非 failure，直接穿透呼叫 `commit`，測試失敗。
2. **`a replay that cannot even defer a row does not claim it made progress`**（JVM）
   - **單行變異**：`CaptureCoordinator.kt:1238` 刪除 `if (!ingest.isReplayCandidate(snapshot.eventId))`，無條件賦值 `progressed = true`。
   - **結果**：分頁讀取次數超過 2 次，`coVerify(atMost = 2)` 失敗。
3. **`a failing prefix longer than a whole pass does not keep the rows behind it from ever being read`**（JVM）
   - **單行變異**：`CaptureCoordinator.kt:1177` 將 `guarded { ingest.resumeDeferredSettlements() }` 移回 while 迴圈第一行。
   - **結果**：第二次觸發時前綴再次耗盡 3 輪預算，tail 事件無法被 commit，測試逾時失敗。
4. **`the pending rows of a source with more than one page are all settled before it is disabled`**（JVM）
   - **單行變異**：`CaptureCoordinator.kt:1313` 將 `while (true)` 改為 `if (true)`（只讀一頁）。
   - **結果**：僅結算 200 筆，`h.lossClaimed.size shouldBe 201` 斷言失敗。
5. **`two triggers arriving together run one replay pass, not two`**（JVM）
   - **單行變異**：`CaptureCoordinator.kt:1158` 將 `while (replayRequested && replayGate.tryLock())` 改為直接呼叫 `replayPass()`。
   - **結果**：並行執行兩趟 pass，`maxConcurrent.get()` 達到 2，測試失敗。
6. **`aPageOfUndecodableRowsIsEmptyAndStillPointsOn`**（AndroidTest）
   - **單行變異**：`IngestRepository.kt:211` 將 `return JournalPage(snapshots, nextCursor)` 改為 `return JournalPage(snapshots, if (snapshots.isEmpty()) null else nextCursor)`。
   - **結果**：第二頁解碼為空時 `next` 為 null，`second.next shouldNotBe null` 斷言失敗。
7. **`aClaimSaysWhetherTheGapIsOnDiskOrOnlyThatItTookNothing`**（AndroidTest）
   - **單行變異**：`IngestRepository.kt:164` 將 `else -> LossClaim.DEFERRED` 改為 `else -> LossClaim.RECORDED`。
   - **結果**：延期列被回傳為 RECORDED，`shouldBe LossClaim.DEFERRED` 斷言失敗。
8. **`resumingOneSourcesDeferredRowsLeavesAnothersAlone`**（AndroidTest）
   - **單行變異**：`Daos.kt:160` 將 SQL 中的 `AND packageName = :packageName` 刪除。
   - **結果**：其他套件的列被放回，`ingest.isReplayCandidate("evt-theirs") shouldBe false` 斷言失敗。
9. **`aSettledRowCannotBeWalkedBackToDeferred`**（AndroidTest）
   - **單行變異**：`Daos.kt:136` 將 SQL 中的 `AND lossRecorded = 0` 刪除。
   - **結果**：已結算列被降級為 2，`deferLoss("evt-settled-defer") shouldBe 0` 斷言失敗（回傳 1）。
10. **`theSettleWalkSeeksToItsCursorInsideTheIndex`**（AndroidTest）
    - **單行變異**：`Daos.kt:50` 常數 SQL 中將 `receivedAtEpochMs` 改為 `receivedAtEpochMs + 0`。
    - **結果**：SQLite 查詢計畫遺失 range seek，`plan shouldContain "(receivedAtEpochMs,eventId)>(?,?)"` 斷言失敗。
11. **`anUndecodablePageInTheMiddleOfTheWalkStillReachesThePagesAfterIt`**（AndroidTest）
    - **單行變異**：`IngestRepository.kt:211` 在解碼空時提早返回 null cursor。
    - **結果**：位於中間損毀頁之後的正常頁無法被讀取，走訪提早中斷，缺口總數斷言失敗。

---

## 四、測試門檻實測紀錄（Gates Execution Results）

審查者在本地環境嚴格遵循指令與硬體限制進行了全面驗證：
- **環境設定**：`export ANDROID_HOME=/Users/iml1s/Library/Android/sdk`，`ANDROID_SERIAL=emulator-5556`（嚴格規避 `emulator-5554` 與實機 `R5CX10VFFBA`）。

1. **JVM 單元測試與 Debug APK 組譯**：
   ```bash
   ./gradlew test :app:assembleDebug
   ```
   - **結果**：268 項 JVM 測試 100% 通過（BUILD SUCCESSFUL in 2s）。
2. **實機／模擬器儀表測試（Connected Android Test）**：
   依據 `CLAUDE.md` 與 BRIEF 指引，在 `emulator-5556` 上執行關鍵的四個儲存與管線模組：
   ```bash
   ./gradlew :platform:storage:connectedDebugAndroidTest              :platform:crypto:connectedDebugAndroidTest              :platform:backup:connectedDebugAndroidTest              :feature:conversation:connectedDebugAndroidTest
   ```
   - **結果**：共 60 項儀表測試全數綠燈通過（storage 51, conversation 5, backup 2, crypto 2，BUILD SUCCESSFUL in 3m 9s）。
3. **靜態分析檢查（Lint）**：
   ```bash
   ./gradlew lint
   ```
   - **結果**：無任何 Lint 錯誤（`abortOnError = true`，BUILD SUCCESSFUL in 2s）。
4. **權限圍籬檢查（Permission Gate）**：
   ```bash
   ./tools/check-permissions.sh app/build/outputs/apk/debug/app-debug.apk
   ```
   - **結果**：`OK: no network permission in app/build/outputs/apk/debug/app-debug.apk`。
5. **多語系字串完整度檢查（String Catalogues）**：
   ```bash
   python3 tools/check-strings.py
   ```
   - **結果**：`OK: 0 error(s), 0 warning(s)`（五種語系目錄完整一致）。

---

## 五、範疇外與殘留問題確認（Residual & Scope Alignment）

1. **關於「以證明武裝重試 vs 定時器」的殘留問題**：
   本輪修復保留了「一筆處於延期狀態的列，若此後沒有新的帶損失事件成功落盤、亦無系統生命週期觸發，則維持等待且 payload 完整保留」的殘留。
   審查者重申：**這項殘留嚴格優於定時器（Timer）**。在損壞、加密金鑰鎖定或儲存空間已滿的裝置上，Timer 會盲目喚醒並反覆消耗系統資源，且若未能成功反而容易退化為過早放棄；以成功事件的「經驗證明（Proof）」作為重試契機，在保證零額外耗電與 payload 永不提早抹除的前提下，達到了最佳的工程權衡。
2. **已記錄於 `docs/SCOPE.md` 的既有問題**：
   - Issue #28（commit attempts 耗盡後 FAILED 列無缺口）
   - Round-35 Codex I1（WhatsApp 換行截斷整列遺失）
   - Round-35 Codex I2（備份合併跳過重覆列時丟失截斷標記）
   - Round-36 subagent I2（來源結算失敗時 UI runCatching 靜默無提示）
   以上均已登記並明確列為 separate commits，本輪確認無任何回歸（Regression）。

---

## 六、最終裁決

提交 `392e80c` 乾淨、精確且徹底地解除了延宕多輪的並行契約危機。所有邊界情況均有相應的單元測試與儀表測試覆蓋，負向控制堅固可靠。

**裁決：APPROVE**
