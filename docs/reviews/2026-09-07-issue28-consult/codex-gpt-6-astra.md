# Issue #28 design consult — Codex `gpt-6-astra` (reasoning effort max), read-only, 2026-09-07

Dispatched from the `fix/issue-28` worktree at `29cfaf0` with `-s read-only`, answer on stdout only; the question is `BRIEF.md` beside this file. Archived verbatim.

---

我選 **B：把「是否已結算」與「是否延後」拆成兩個獨立位元**，並要求延後列只能在下一輪恢復，不能在同一輪反覆恢復、重試。

以下僅根據指定文件；沒有讀取其他檔案、修改程式或執行測試。文件未提供完整 DAO，因此 SQL 部分會精確列出應替換的運算與條件，保留原有主鍵、排序、排除清單及範圍限制，不臆造完整查詢。

**選 B 的理由**是它同時保住兩項必要條件：未能可靠記錄損失前，保留 journal payload；無法處理的列，可以離開當輪頁面，讓後面的列繼續處理。

A 保住資料，但沒有解決飢餓。即使觸發條件需要「特定事件持續提交失敗，而且 gap 寫入也失敗」，只要成立，前 200 列仍可永久擋住第 201 列。較難觸發並不使這個行為變成正確。

C 的「先 FAILED，再把損失放 RAM」會主動清除目前仍可恢復的 payload，留下程序死亡後無法追查的窗口。利用 attempts 做另一套延後狀態，也需要同步調整查詢、恢復與排程，並把重試次數混入排程語意，沒有明顯比 B 更容易驗證。

B 的成本是重新審查所有受影響的讀寫條件。這個成本可接受，因為 round 39 可以針對有限的狀態表、SQL 條件及真實 vault 測試做判定，不必接受「這種雙重失敗應該很少」的假設。

狀態應明確定義為遮罩 `0x01 = settled`、`0x02 = deferred`：

| lossRecorded | 已結算 | 已延後 | 可進入 replay 候選頁 |
|---|---|---|---|
| 0 | 否 | 否 | 是 |
| 1 | 是 | 否 | 是 |
| 2 | 否 | 是 | 否 |
| 3 | 是 | 是 | 否 |

核心不變量是：

- `0 → 2 → 0`：未結算的列延後，再恢復。
- `1 → 3 → 1`：已結算的列延後，再恢復。
- **延後與恢復不能把已結算變成未結算。**
- COMMIT_FAILED gap 與 `FAILED`／payload 清除必須一起提交或一起回滾。

SQL 應按下面修改。表中的 WHERE 條件是狀態部分；各 statement 原有的單列識別、package、游標、水位及其他限制全部保留。

| Statement | SET | WHERE 中的狀態條件 |
|---|---|---|
| `deferLoss` | `SET lossRecorded = lossRecorded + 2` | `state = 'PENDING' AND lossRecorded < 2` |
| `resumeDeferredLosses` | `SET lossRecorded = lossRecorded - 2` | `state = 'PENDING' AND lossRecorded >= 2` |
| 另一個 resume statement，文件未列名稱 | 同上 | 同上，保留其原有恢復範圍 |
| `pending` | 不適用 | `state = 'PENDING' AND lossRecorded < 2` |
| `pendingExcluding` | 不適用 | `state = 'PENDING' AND lossRecorded < 2` |
| `isReplayCandidate` | 不適用 | `state = 'PENDING' AND lossRecorded < 2` |

例如，**沒有其他範圍限制的全域恢復**是：

```sql
UPDATE event_journal
SET lossRecorded = lossRecorded - 2
WHERE state = 'PENDING'
  AND lossRecorded >= 2;
```

如果原本的恢復 statement 有 package 或 pass 水位限制，必須保留那些限制，不能直接用這個全域版本取代。

兩項既有條件應保持不變：

```sql
-- claimLoss：仍然只能取得尚未結算、未延後的 claim
SET lossRecorded = 1
-- 原 WHERE 中保留：
state = 'PENDING' AND lossRecorded = 0
```

```sql
-- pendingForPackageAfter：仍然只走尚未結算、未延後的列
-- 原 WHERE 中保留：
state = 'PENDING' AND lossRecorded = 0
```

`pendingLossState` 必須能讀到 2、3，**不能加入 `lossRecorded < 2`**。如果它目前只是查回整數，SQL 不必改，只需把結果分流改成：

```text
1       → ALREADY_RECORDED
2 或 3  → DEFERRED
0       → 保留既有的未結算處理
查不到 PENDING 列 → 保留既有處理
```

如果分流是在 SQL `CASE` 裡，延後分支應是：

```sql
WHEN lossRecorded IN (2, 3) THEN 'DEFERRED'
```

不要先用「settled 位元是否為 1」判斷 ALREADY_RECORDED，否則 3 會繞過延後語意。

這些加減法依賴值域只有 0、1、2、3。若實際 schema、轉換器或驗證邏輯限制只能是 0～2，就需要同步處理；「沒有新增欄位」本身不能證明完全不需要 schema 調整。

執行順序則應固定如下：

1. 提交事件失敗，進入耗盡重試的處理。
2. 在同一個 vault transaction 裡，寫入 COMMIT_FAILED gap，並將 journal 設成 FAILED、清除 payload。
3. 任一部分失敗，整筆交易回滾；依題述，attempts 仍是 `MAX−1`，payload 與原本的 settled 狀態仍在。
4. **回滾完成後，另行呼叫 `deferLoss`**，得到 0→2 或 1→3。

不能把 defer 放在必定一起回滾的交易裡。gap lambda 也必須參與同一個 vault transaction 並等待寫入完成，不能啟動背景工作後直接返回。

每輪只恢復一次上一輪的 deferred 列；本輪新延後的列留到下一輪。當輪頁面排空後，不可反覆「恢復全部 → 再失敗 → 再延後」，否則只是把頁首飢餓換成無限重試。既有喚醒／退避機制也必須涵蓋 deferred 列，不能因 `pending()` 回傳空清單，就永遠不再恢復它們。

這表示恢復後可以發生實際上的第四次提交嘗試。`MAX` 在這個分支代表「應嘗試可靠記錄終止損失的門檻」，不能再被描述成實體提交次數的絕對上限。

如果連獨立的 `deferLoss` UPDATE 也失敗，仍要保留 PENDING 與 payload。能繼續讀取時，可以利用本輪的 `pendingExcluding` 排除該列；若 vault 整體不可用，就結束本輪並等待恢復。**不能宣稱 SQL 延後在資料庫完全無法寫入時仍必然成功。**

`JournalLossTransactionTest` 至少應用真正 vault、DAO 與 transaction 固定以下行為；只 mock repository 的錯誤結果不足以證明原子性：

1. **耗盡交易回滾，分別從 0、1 開始。**  
   COMMIT_FAILED gap 寫入失敗後，journal 仍是 PENDING、attempts 仍是 `MAX−1`、payload 完整，lossRecorded 保持原值。另測 gap 已實際寫入、後續交易步驟失敗，確認 gap 也一起回滾。

2. **延後與恢復保留 settled 位元。**  
   驗證 0→2→0、1→3→1；重複 defer／resume 不再改值。非 PENDING 列不受影響；兩個 resume 各自遵守原有範圍限制。

3. **所有候選查詢一致。**  
   `pending`、`pendingExcluding`、`isReplayCandidate` 接受 0、1，排除 2、3；`pendingForPackageAfter` 只接受 0；`pendingLossState` 能辨識 3 為 DEFERRED。

4. **已結算列不再取得 carried-over claim。**  
   分別用 acceptance gap 與 carried-over gap 建立狀態 1，再走 1→3→1。恢復後，原 gap 不增加，`claimEventLoss` 不重新寫入同一份結算損失。

5. **可靠終止只產生一次 COMMIT_FAILED。**  
   gap 成功時，FAILED、payload 清除與 gap 一起落盤。驗證 package、reason，以及 `postedAt` 有值／為 NULL 時的兩組邊界。重複呼叫或再次 replay 不新增第二筆終止 gap。已有的 acceptance／carried-over gap 保持原樣。

6. **恢復後提交成功。**  
   延後列恢復後若提交成功，事件確實保存，不新增 COMMIT_FAILED。不能因曾達到重試門檻，就在恢復後無條件丟棄。

7. **真實的 200／201 頁首飢餓案例。**  
   前 200 列已結算、提交持續失敗，且終止 gap 寫入被拒絕；第 201 列已結算且可成功提交。驗證前 200 列變成 3、第 201 列仍能在本輪被處理，而且本輪有限結束。第 201 列應先設成已結算，避免它也被 carried-over gap 寫入失敗擋住，讓測試失去辨識力。

8. **重新開啟檔案型 vault。**  
   分別在「回滾後、尚未 defer」、「defer 完成」及「FAILED 交易完成」的已提交邊界關閉並重新開啟。確認可恢復的 payload 仍存在、3 恢復成 1、已完成的终止 gap 不重複。

9. **claim 與 defer 的兩種順序。**  
   claim 先成功：0→1→3，只記錄一次結算 gap。defer 先成功：0→2，後續 claim 回 DEFERRED，不寫 gap。終態列不能被遲到的 defer 改回待處理狀態。

10. **defer 本身失敗。**  
    確認 payload 不被清除、沒有回報已成功終止，也不在本輪無限重试。live 與 replay 兩個入口都必須覆蓋到耗盡失敗後的處理，不能只測 repository helper。

對 `claimEventLoss`，B 不需要增加第五種業務結果。文件沒有列出四個 case 的完整定義，因此無法聲稱已逐項核對原始實作；但本次必要的契約變更很明確：**新增儲存值 3，映射到既有 DEFERRED；1 仍映射 ALREADY_RECORDED；其餘既有 case 保持原義。**

另外，COMMIT_FAILED **不能透過 `claimEventLoss` 的「尚未結算才寫 gap」門檻處理**。lossRecorded=1 只表示 acceptance／carried-over 結算已有紀錄，不表示最終提交失敗已被記錄。即使是 1，耗盡交易仍必須寫 COMMIT_FAILED；這筆終止紀錄的重複保護來自 journal 的狀態檢查與同一筆交易。

對 replay coalescing，保留 `pendingForPackageAfter = 0` 就能維持關鍵集合：

- 0→2→0 暫時移出、之後重新加入未結算集合。
- 1→3→1 全程不會重新加入未結算集合。

因此，B 本身不要求改變 coalescing 的區間算法。真正會破壞它的是把恢復統一寫成 2／3→0，或把 package walk 改成 `< 2`，讓已結算列再次參與新 claim。DEFERRED 也不能被當作「已完成結算」來增加完成計數或丟棄重試責任。

round 39 應重新審查以上狀態轉移、所有受影響的 DAO 條件、live／replay 兩個入口，以及 200／201 的整合測試。索引是否因範圍條件退化，需由實際 query plan 判定；指定文件不足以證明「原本從未使用該索引」，但這項效能疑慮也不足以接受 A 已知的飢餓行為。
