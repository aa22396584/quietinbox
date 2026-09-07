# QuietInbox Round 37 唯讀審查

## Verdict：REQUEST CHANGES

三態能表達「補記失敗，但證據必須保留」，這個方向合理；**目前實作的狀態機仍未閉合**。`claimLoss` 原本回傳 false，可能表示已記錄；加入 deferred 後，false 也可能表示「尚未記錄、這次不准重試」。呼叫端沒有區分兩者。兩次 replay 預取同一列後，其中一次將它延期，另一次可以直接 commit，清空唯一能補記損失的 journal payload。

這是本輪新增的 Critical，已由抽取正式 DAO SQL 的 host 狀態模型及編譯後的 coordinator JVM 重現測試交叉確認。原有 263 個 JVM 測試全綠，不能抵銷這個反例。

Round 36 指定的 201 列公平性案例已修好，來源分頁的 SQL 成本也確實由近似平方成長降為線性；但全域 reset 與 100 輪上限的組合，只把持續飢餓的門檻提高到 20,001 列。測試的證明力還有三個具體缺口。

## 範圍與本輪證據

- 唯一任務 brief：`/tmp/qi-r37-brief-safe.md`。未讀取工作樹內的 Round 37 BRIEF 或其他本輪審查報告；為重跑指定控制，讀取了 repository 中 Round 36 的 Codex／subagent 報告。
- 審查範圍：`44ce4843ffa9833f6f48ccc1f7a28436c81cafad..4797b13d36a6553f561bbef13258f530b5d40b02`，包含 `f3d4407`、`86c4401`、`4797b13`。本文行號以 `4797b13` 為準。
- 起始 HEAD 是 `21ccbc5f15330f990f124bec89995c37f5c0b750`，工作樹乾淨；它相對審查終點只新增 Round 37 brief。測試使用固定版本的隔離匯出副本。
- 未啟用 workflow mode，未修改原 repository 的產品程式碼；編譯、重現測試及變異均在暫存副本執行。
- 在 `/tmp/qi-r37-mutation.ifIz0A` 執行 `./gradlew test`：fresh XML 彙總 **263 tests，0 failures、0 errors、0 skipped**。
- Python SQLite **3.53.0**：從三個 revision 抽取正式 DAO SQL 與匯出 schema，重跑公平性、游標成本、舊快照交錯及分頁變異。控制流部分是 Python 模型，不是 Room／SQLCipher／Android 實跑。
- 未重跑 56 個 instrumented tests、lint、APK permission gate 或 strings gate；brief 的結果仍是提交者提供的紀錄。檢查時原 repository 另有 connected test 使用 emulator-5556，本審查沒有操作任何 emulator 或實機。
- WhatsApp 換行截斷、backup duplicate 的截短證據、停用來源失敗未回饋 UI，以及 issue #28，維持明列範圍外，不重報。

## Critical — push 前必須修正

### C1. 並行 replay 會把 deferred 的「未取得 claim」當成安全完成，清空尚未補記的證據

**位置：** [CaptureCoordinator.kt:1137](/Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1137)、同檔 `:1146`、`:1162`、`:1179`、`:1209`；[IngestRepository.kt:142](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:142)、`:193`；[Daos.kt:116](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:116)、`:130`、`:65`。

`replayJournal()` 沒有整次執行的互斥／合併機制。Ready、全域恢復、來源解除暫停、maintenance end 與新 retry 都可啟動它。resume 與 batch SELECT 位於 `pipelineMutex` 外，只有逐列處理在鎖內。

可達順序如下：

1. A、B 都先 resume，且都 SELECT 到同一筆 `PENDING / lossRecorded=0`。
2. A 取得 pipeline lock；gap INSERT 失敗，claim 回滾，再將該列寫成 `PENDING / 2`。
3. B 拿舊 batch 進 lock。`isJournalPending()` 只檢查 `state`，因此通過。
4. B 的 `claimLoss()` 因 `lossRecorded != 0` 回傳 0；repository 回傳 false，沒有執行 gap callback，也沒有丟例外。
5. `recordCarriedOverLoss()` 丟棄 Boolean；外層 `runCatching` 視為成功，繼續 `processJournaled()`。commit／SKIPPED／來源 fence 的 discard 均可清空 payload。

不是「先寫了 gap，另一個 caller 輸掉 claim」的正常冪等情境。這裡的 false 代表 gap 尚未存在。

**本輪驗證：** 在同一個正式 schema／SQL 模型固定上述順序，只讓 gap INSERT 被 trigger 拒絕：

| 版本 | A 失敗後 | B 的補記結果 | B 後的 journal | gap 數 |
| --- | --- | --- | --- | ---: |
| `44ce484` | PENDING／0，payload 完整 | 再次嘗試並丟錯 | PENDING／0，payload 完整 | 0 |
| `f3d4407` | PENDING／2，payload 完整 | false，沒有丟錯 | COMMITTED／2，payload 空字串 | 0 |

另在隔離副本加入 deterministic JVM 測試：兩次 `setPaused(false)` 啟動 replay，以 barrier 保證兩者先取得同一 snapshot，claim fake 遵守正式 `lossRecorded=0` 條件。未修改產品實作的編譯後結果為 **59 tests／1 failure**；新測試要求 commit 零次，MockK 實際觀察到 `evt-concurrent-defer` 被 commit 一次，呼叫路徑經過正式 `CaptureCoordinator.kt:1179`。

原 Harness 的 claim fake（`CaptureCoordinatorTest.kt:259`）以是否已在 `lossClaimed` 決定勝負，沒有把 `lossDeferred` 納入 claim eligibility。這不是單純少一個競態 assertion：現有 fake 也沒有表達造成此 regression 的新語意。

**如何修正：** 對攜帶可判定損失的列，只有本次成功補記或明確確認已是 durable settled，才可進入終態。鎖內重新確認 eligibility，並區分「已補記」「延期／尚未補記」「已非 PENDING」等 claim 結果；合併／序列化 replay，避免舊 batch 在不同 pass 間交錯。保留雙 replay barrier 回歸測試，驗證失敗時 payload、attempts 與 PENDING 均不被破壞。

**證據限制：** JVM 跑了正式 coordinator，但 repository 是 mock；SQL 模型跑了正式 SQL，但由人工安排合法交錯。兩者一起證實可達程式路徑與資料狀態，不是 Android SQLCipher 真實磁碟故障的端到端實測。暫存 guard control 能令新增重現測試轉綠；完整 class 尚有舊 mock fixture 的失敗，不能把該 control 報成已驗證可交付修法。

## Important

### I1. 每次 pass 全域重置，使第 20,001 列仍可在任意多次 replay 後永久挨餓

**位置：** [CaptureCoordinator.kt:1128](/Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1128)、`:1132`、`:1137`；[IngestRepository.kt:195](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:195)。

每頁預設 200 列，每次 replay 最多 100 輪。若最早 20,000 列的 gap 都失敗，這些列會用完全部 100 輪；第 20,001 列正常事件還沒被 SELECT。下一個 trigger 又把前面全部 `2→0`，再從最早列開始，得到同樣結果。

**本輪驗證：** `f3d4407`，20,000 筆失敗列加另一來源的一筆 clean tail，連續三次 replay；每次均為 **100 次 SELECT、20,000 次 claim、0 次 commit**。尾列始終 PENDING／0，前面 20,000 列為 PENDING／2，attempts 與 payload 完整。

這與已接受的「沒有 trigger 所以等待」不同：此反例有三次 trigger，仍無進展。100 輪是實際存在的單次上限；它不是跨 pass 公平性的保證。

**如何修正：** 將「繼續尚未走完的 sweep」與「重新啟用上一批 deferred」分開。保存 continuation／retry eligibility，讓舊 cohort 不會在每次 continuation 前重新插回隊首；或採用有公平排序的到期重試。不要只調高常數。回歸測試須跨越 `pageSize × roundLimit`，並證明持續失敗時後方正常來源仍能處理。

**證據限制：** 正式 SQL 加 replay 控制流模型；沒有建立 20,001 個 Android snapshot 或測量裝置耗時。100 輪常數早已存在，本輪新增全域 resume 使相同失敗前綴能在每次觸發時重新耗盡它。

### I2. 將正式 settle 變異為只讀第一頁，現有 coordinator suite 仍全綠

**位置：** [CaptureCoordinator.kt:1239](/Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1239)；[CaptureCoordinatorTest.kt:322](/Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:322) 的分頁 fake 與來源政策 fixtures；[JournalLossTransactionTest.kt:282](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/JournalLossTransactionTest.kt:282)。

Round 36 七變異的第 1 項仍未關閉。fake 現在會分頁，但來源政策案例沒有超過 200 列；新增 instrumented walk 測試是在測試本身的 while 中呼叫 repository，沒有經過正式 coordinator 的 while。

**本輪驗證：** 在暫存產品副本保留 resume，將正式 `settleCarriedOverLosses()` 改成只取得及處理第一頁。編譯後完整 `CaptureCoordinatorTest` **58 tests／0 failures**。首輪曾在既有非此行為的非同步 lock-out 測試偶發失敗；相同 mutant 重跑全綠，不能把那個紅燈計為抓到此變異。

錯誤版本會在 disable／remove 的後續 discard 清掉第 201 列以後尚未補記的 payload。實際提交的 while 正確；這是仍可放過資料遺失 regression 的測試缺口。

**如何修正：** 從正式 `setSourceEnabled(false)` 或 `removeSource()` 入口建立至少 201 筆帶 loss 的 pending rows，斷言尾列的 gap 在 discard 之前完成。用本次 first-page mutant 證明該測試變紅。

**證據限制：** 真正編譯及執行了 coordinator mutant suite；沒有執行 SQLCipher instrumented mutant。instrumented 測試為何不受此 coordinator mutation 影響，則是由其直接呼叫 repository 的 source trace 確認。

### I3. 查詢計畫測試沒有測正式 DAO SQL，且 assertions 分不出「找到索引」與「seek 到完整游標」

**位置：** [JournalLossTransactionTest.kt:334](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/JournalLossTransactionTest.kt:334)、`:336`、`:349`；[Daos.kt:98](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:98)。

測試重新手寫 SELECT，EXPLAIN 的不是 DAO 執行的 statement。正式查詢若退化，測試裡的副本仍能得到漂亮計畫。另外，僅要求命中指定 index 且沒有 `SCAN event_journal`／`TEMP B-TREE`，也容許只用 index 的等值前綴、逐頁重掃游標以前的列。

**本輪驗證：** 只把正式 SQL 游標的時間欄改成等值運算式：

```sql
(receivedAtEpochMs + 0, eventId) > (:afterTime, :afterId)
```

在正整數時間 fixture 上，走訪結果完全正確，但計畫只剩 `(packageName=? AND state=? AND lossRecorded=?)` 的 SEARCH。對 8,000 筆同時間戳列，41 頁成本由 **177,461 升至 2,152,898 VM 指令**；現有三項 plan assertions 即使套在這份退化 SQL 上，仍全部成立。測試現況更弱，因為它根本沒有讀到該 mutation。

另一個有實際重構意義的控制是將 row value 改回等價 OR：大量同時間戳時為 1,645,998 VM 指令，仍通過現有 plan assertions。分散時間戳時 SQLite 3.53.0 能優化 OR，所以不能泛稱「換回 OR 在所有資料分布都變慢」。

**如何修正：** 取得正式 DAO 實際執行的 query／bindings 作為 EXPLAIN 輸入；再驗證完整游標範圍或工作量，涵蓋大量 ties，不能只驗證 index 名稱。讓上述保持功能正確、卻失去完整 seek 的 mutation 變紅。

**證據限制：** 本輪實跑 SQLite 查詢計畫、VM 計數及現有 assertions 的等價判定；沒有重新編譯／執行該 SQLCipher instrumented mutant。正式提交的 row-value SQL 本身效能正確，見下表。

### I4. 「整頁不可解碼」測試的正常分頁沒有任何一頁全部不可解碼

**位置：** [JournalLossTransactionTest.kt:303](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/JournalLossTransactionTest.kt:303)、`:308`、`:317`；[IngestRepository.kt:184](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:184)。

fixture 有 p1 到 p5，limit=2，卻把 **p2、p3** 設為不可解碼。正常 raw pages 是 `[p1,p2]`、`[p3,p4]`、`[p5]`，每頁各有可解碼內容。它確實能抓到 decoded-last cursor mutation，因為錯誤游標先把頁面邊界改變，之後才產生 `[p2,p3]` 的空 snapshots；這不等於測到正式游標遇到整頁空 snapshots 時仍會繼續。

**本輪驗證：** 使用正式 SQL 與相同 fixture，加入 `snapshots.isEmpty()` 時直接回傳 `next=null` 的控制流 mutation，仍得到 `[p1,p4,p5]`，原 assertion 成立。把不可解碼列改為 **p3、p4** 後，正式版本會讀到 p5，mutant 則在空的第二頁停止，遺漏 p5。

**如何修正：** 使 p3、p4 或 p1、p2 構成真正完整的不可解碼 raw page，斷言該頁 snapshots 為空而 next 非 null，且其後有效列仍被走訪；保留「遇到空 snapshots 就停止」的負向控制。

**證據限制：** 正式 SQL 與分頁／解碼控制流模型，未重跑 Kotlin instrumented mutant。它不否定提交者的 decoded-last 控制曾變紅；指出的是另一個仍可穿過該 fixture 的錯誤實作。

## Minor

### M1. 「一次成功 gap 就能保證不產生 retry storm」需要明列故障前提

**位置：** [CaptureCoordinator.kt:542](/Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:542)、`:552`、`:961`、`:1173`。

某個 gap 成功，只能證明那次寫入成功。若舊列的 gap 持續失敗、新事件的 gap 可成功，每個新 lossy acceptance 都能消耗 flag、啟動 replay，而 replay 再次失敗又重新設定 flag。「每個 deferral episode 一次」並不是重試速率上限，因為每個重試都能創造下一個 episode。

**本輪驗證：** fault model 以 trigger 持續拒絕 source A 的 gap、接受 source B；8 次 B 的 lossy acceptance 對應 8 次 replay，A 始終 PENDING／2，沒有恢復。這是輸入驅動的工作量放大，沒有宣稱它會在完全沒有輸入時自發無限循環。

**如何修正：** 文件將保證限定在「所有相關 gap 寫入持續一律失敗」；實作若要提供更強的速率保證，需合併進行中的 replay 並加上 retry eligibility／退避。C1 的安全性不能依賴所有故障都全域一致。

**證據限制：** 條件式 trigger 是故障模型，並非 app 有這個 production trigger 或已量得裝置上的 storm。本項按保證過度延伸及潛在成本列 Minor，沒有把模型直接升格成實機事故。

### M2. UI 測試證明 chip 有無，沒有證明新的措辭

**位置：** [MessageBubbleSemanticsTest.kt:33](/Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/androidTest/kotlin/dev/quietinbox/feature/conversation/MessageBubbleSemanticsTest.kt:33)、`:112`。

expected text 與 UI 都讀同一個 `R.string.conv_truncated`。把文字改回舊文案，兩者會一起改，測試仍綠；註解「wording is checked too」不成立。移除 chip 或永遠顯示 chip，這兩個測試則確實有區分能力。

**如何修正：** 將測試宣稱限縮為渲染／flag 控制；若要固定歷史措辭，加入獨立的核准文案 expectation 或對應 resource regression check。

**驗證與限制：** expected resource 與 production resource 的 source trace；未執行 Compose／文案 mutant。本輪五語系的新文案本身沒有因此被判為錯誤。

## 指定 Round 36 harness 重跑

### I1：200 筆 gap 失敗列，加第 201 筆另一來源 clean row，連續三次 replay

| 版本／查詢 | 各次 SELECT 次數 | 各次 claim 嘗試 | 各次 commit | 三次後失敗列 |
| --- | --- | --- | --- | --- |
| `44ce484`，pending | 1／1／1 | 200／200／200 | 0／0／0 | 200 筆 PENDING／0；tail 未處理 |
| `f3d4407`，pending | 3／2／2 | 200／200／200 | 1／0／0 | 200 筆 PENDING／2；tail 已處理 |
| `4797b13`，pendingExcluding | 3／2／2 | 200／200／200 | 1／0／0 | 同上 |

`pendingExcluding` 的 excluded package 為另一個 paused source，失敗來源與 clean tail 均未被排除。SELECT 數包含最後的空頁探測。

移除拒絕 gap 的 trigger 並明確再執行一次 replay，`f3d4407` 的 200 筆 deferred 全部 commit，得到恰好 200 筆 gap。失敗期間其 attempts 一直為 0、payload 保留。這證實 brief 點名的修正與顯式恢復成立；不把顯式呼叫冒充自動 retry。

若連 defer UPDATE 也被 trigger 拒絕，則只有一頁／200 次 claim，沒有虛報進度，tail 留待恢復。這個「根本無法持久化排程狀態」的界線由新 `isReplayCandidate` 檢查正確保留。

### I2：來源 keyset walk 的頁數與 VM 指令

使用同來源 PENDING／0、不同時間戳、page size=200，只計正式分頁 SELECT，不包含 JSON、gap、claim 或 mutex。舊版本數字與 Round 36 報告的四列完全吻合。

| 列數 | SELECT 次數 | `44ce484` VM | `f3d4407` VM |
| ---: | ---: | ---: | ---: |
| 1,000 | 6 | 86,214 | 19,311 |
| 2,000 | 11 | 282,379 | 38,561 |
| 4,000 | 21 | 1,004,709 | 77,061 |
| 8,000 | 41 | 3,769,369 | 154,061 |

新計畫是：

```text
SEARCH event_journal USING INDEX
index_event_journal_packageName_state_lossRecorded_receivedAtEpochMs_eventId
(packageName=? AND state=? AND lossRecorded=? AND (receivedAtEpochMs,eventId)>(?,?))
```

8,000 列約減少 95.9% VM 指令，沒有 temp B-tree。把全部 8,000 列改為同時間戳，正式 row value 仍只用 177,461 VM 指令且沒有漏列。**來源 settle 的索引修正成立。** 這是 host SQLite 工作量，不能報成 Android 耗時、ANR 或磁碟寫入改善百分比。

## 模型、交易與排程的判斷

### 三態值得保留，但 terminal eligibility 必須與排程狀態一起建模

| 狀態 | 意義 | 對攜帶可判定 loss 的事件，能否直接 finalize |
| --- | --- | --- |
| PENDING／0 | 尚未補記、可嘗試 claim | 不可；必須先成功補記 |
| PENDING／1 | 已補記 | 可以繼續原本的 commit／discard fence |
| PENDING／2 | 尚未補記、目前延期 | 不可；必須保留 payload 與事件 attempts |

不帶可判定 loss 的 clean row 原本就可在 0 進入終態，不應錯改成「所有事件都必須先變 1」。

三個正常轉換 `0→1`、`0→2`、`2→0` 的 SQL 本身合理；問題是 caller 還在使用二態的 Boolean 結果，而且資料模型的有效狀態是 `(journal.state, lossRecorded, payload)`，不是單看 `lossRecorded`。C1 已構造出不應可達的 terminal／2／空 payload。

較好的形狀是：保留 durable settlement state，API 明確回傳可 finalize／已結算／延期／列已終止等結果；排程另外管理 sweep continuation 與 retry eligibility。僅把 Int 改名為 enum，不能自行修掉舊快照或跨 pass 公平性。

### 全域 resume 放在來源政策交易內：回滾正確，作用範圍較大

`Daos.kt:144` 全域 `2→0`，但 `CaptureCoordinator.kt:1240` 只走指定 package。A 的政策交易成功時，B 的 deferred 也會變回 0，而 A 的 settle 不會嘗試 B；若 A 的交易 abort，B 的 reset 同樣回滾，回到交易前的 2。沒有發現「A abort 卻永久改動 B」的新原子性缺陷。

這會讓來源政策操作改變其他來源的重試時機，並可在另一個 replay 途中重新插回候選列。因此「一列每個 pass 最多嘗試一次」只在沒有其他 pass／政策 reset 穿插時成立。建議 source walk 使用 per-package resume，全域調度由 replay 自己管理；本身不另列資料遺失 finding。

全域 reset 也有成本：新複合索引不以 `lossRecorded` 開頭，`resumeDeferredLosses` 的計畫為 `SCAN event_journal`。在 **0 筆 deferred**、分別 1,000／2,000／4,000／8,000 筆 terminal history 的模型中，仍花 3,010／6,010／12,010／24,010 VM 指令。這是新增的全表線性成本，不等同舊來源游標的平方回歸。

其他查詢計畫：`discardPending` 可使用新 index 的 `(packageName,state)` 前綴；`pending`／`pendingExcluding` 仍用 state index 加 temp B-tree，和舊版本相同。新索引沒有讓這兩個全域 replay 查詢取得排序能力，故不能把來源 walk 的效能改善推廣成所有 replay 都已線性。

### Progress、取消與 retry flag

`isReplayCandidate` 是讀取當下的 membership，不是 deferral 操作的原子收據。另一個 replay 在 `deferLoss` 與查詢之間執行全域 resume，會令它再次為 true；本次失敗就不算 progress。這不會突破每次 invocation 的 100 輪常數，但也不構成全系統有界嘗試或公平性證明，見 I1。

目前 retry flag 的設定與兩條消耗路徑都在 `pipelineMutex` 下，沒有找到普通的兩個 caller 同時讀 true／清 false 的 data race。**一個 flag 只消耗一次，仍不代表同時只有一個 replay：** 進行中的 pass 可以再 defer，下一次 acceptance 又可 launch。

`scope.launch` 不等待新 replay 取得 pipeline lock，因此沒有自我等待的 deadlock；`VaultMaintenance.work()` 登記 job，exclusive maintenance 設 flag、取消並 join，晚到的 work 也會拒絕。由 source trace 未找到新增 job 能繞過 maintenance 寫 vault 的路徑。這是生命周期控制的 source 驗證，不是全排程交錯的壓力測試。

### 不加 timer 的選擇

在已明列的契約下，**我不把「完全沒有 lossy event／lifecycle trigger 時繼續等待」另列新 finding，也不要求只為本輪加 timer。** 事件驅動 retry 可以是合理的省電選擇，但它提供的是條件式恢復，沒有恢復時間上限；與有上限、可取消、指數退避的 timer 相比，代價就是可能無限等待，收益是少做探測。

「local SQLCipher 的失敗不是 time-transient」不能當作一般技術前提。此 repository 捕捉的是廣泛 Exception，不只永久故障；SQLite 官方明確說明 `SQLITE_BUSY` 可以來自另一連線尚未結束的 transaction，等待對方完成後即可再試。[SQLite result codes](https://www.sqlite.org/rescode.html)

這不證明本 app 已在真機發生該錯誤，也不代表 timer 必然更好。需要修正的是 C1 的安全性、I1 的跨 pass 公平性，以及 M1 保證的範圍；是否承諾閒置裝置自動在某期限內恢復，才決定需不需要額外的計時排程。

## SQLite／SQLCipher 相容性與 schema 4 成本

專案固定 `net.zetetic:sqlcipher-android:4.18.0`（`gradle/libs.versions.toml:17`、`:92`），storage 直接依賴它，`DatabaseHolder.kt:108` 明確載入 `sqlcipher` 並將 `SupportOpenHelperFactory` 交給 Room。App minSdk=26（`app/build.gradle.kts:45`）。使用的是隨 AAR 打包的 SQLite engine，不是依 Android API 分別選用 framework SQLite。

官方 row-value 下限是 SQLite 3.15.0；SQLCipher Android 上游支援 API 23+ 及四種 ABI。已核對本機依賴 AAR checksum 與 verification metadata，含四 ABI 的 `libsqlcipher.so`；arm64 binary 可讀到 SQLite 3.53.4 版本字串。因此這個 SQL 語法的相容性基礎適用於目前配置的支援 API 範圍，不只某一台 emulator。[SQLite row values](https://sqlite.org/rowvalue.html)、[SQLCipher Android](https://github.com/sqlcipher/sqlcipher-android)、[Zetetic Android 整合文件](https://www.zetetic.net/sqlcipher/sqlcipher-for-android-community/)

**限制：** 這是依賴封裝、binary 與 builder 路徑的證據；沒有新 APK／AAB 封裝檢查，也沒有逐 API／ABI 執行 `sqlite_version()` 或 row-value query。host harness 的 3.53.0 與 bundled engine 的版本字串不混用。

`CREATE INDEX IF NOT EXISTS` 對真正執行 `3→4` 的 migration 合理；已經跑過較早 schema 4 的 vault，不會因這一句自動補索引，因為版本未變，不會執行 `3→4`。Room identity hash 也已變動。已有同名索引時，IF NOT EXISTS 只是不做任何事，並不驗證其定義。[SQLite CREATE INDEX](https://www.sqlite.org/lang_createindex.html)

這個 development-vault 開啟失敗成本已在目標 CHANGELOG 明列，前提是 schema 4 尚未發布，所以**不重報為新 Critical，也不在此要求升 5**。若將來要支援已有 schema 4 的使用者資料，才必須提供新的版本與 migration；目前的前提不能外推成已支援舊 4 升級。這部分是 source／migration 路徑核對，沒有新建並開啟一份舊 4 SQLCipher vault。

## 七項舊變異：本輪重跑結果

下表嚴格區分「編譯後的正式 caller 變異」與「正式 SQL／repository 游標演算法模型」。後者沿用目前 instrumented 測試的七列 fixture、page sizes `1/2/3/6/7/8` 與 visited assertion，不能稱為本輪 Android 測試紅燈。

| Round 36 變異 | 對目前實作的等價控制 | 本輪結果與界線 |
| --- | --- | --- |
| 1. settle 只讀一頁 | 正式 coordinator 去掉續頁 while | **仍 silent：fresh compiled 58 個 coordinator 測試全綠**，見 I2 |
| 2. next 固定 null | raw rows 仍照正式 SQL 讀，cursor 固定終止 | 模型在 limit=1/2/3/6 遺漏後列；目前 repository walk oracle 能辨識，未跑 Android mutant |
| 3. cursor 包含自己的 eventId | row-value `>` 改 `>=` | 模型在小頁重複邊界列，limit=1 反覆停在同一列；新 visited oracle 能辨識。不能沿用 Round 36「無害」的結論 |
| 4. `rows.size == limit` 改 `rows.isNotEmpty()` | 非空短頁仍給 next | 所有 visited assertions 仍綠，只多一次空查詢；屬目前語意等價控制，不把未殺死當缺陷 |
| 5. ORDER BY 去 eventId | 保留正式 row-value predicate 與新 index | 全綠；目前 index 仍供應相同 tie order。ORDER BY 的契約價值仍在，不冒充已測出漏列 |
| 6. cursor 取 decoded snapshots 最後一筆 | 正常 SQL，cursor 改用 decoded rows | 現有 p2/p3 fixture 的 visited assertion 變紅；但完整空頁的另一個 mutation 仍 silent，見 I4 |
| 7. 時間條件 `>` 改 `>=` | 保留 afterId 參數的等價 OR 條件，讓同時間戳重返頁面 | 模型出現重複／卡頁，新 visited oracle 能辨識；沒有使用 Room 不能編譯的 unused-parameter mutation |

這七項的結果不是「七項全殺死」。真正留下的有害缺口包括正式 caller 的第 1 項；第 4、5 項目前保持行為，無需為漂亮的 mutation score 編造紅燈。

## 每個新增／實質加固測試的鑑別力

「可辨識變異」欄未標實跑者，均是對現有 fixture／assertion 的 source 判斷；JVM 基線通過只證明測試可執行，並非聲稱那些 mutants 逐一重跑過。SQL 模型的實跑結果另有明列。短檔名對應前文完整路徑。

| 測試位置 | 可辨識的最小產品變更 | 本輪證據／限制 |
| --- | --- | --- |
| CaptureCoordinatorTest:1644，200+1 同來源 | 失敗 deferral 不計 progress | 新測試在 263 綠基線內；fixture 具鑑別力，未重跑此 Kotlin mutant |
| CaptureCoordinatorTest:1686，成功 gap 喚醒 deferred | 刪掉 lossy acceptance 的 retry 呼叫 | 同上；不證明並行安全或重試速率 |
| CaptureCoordinatorTest:1722，無 loss 不 retry | 將 lossOnAccept 條件改成一律 retry | 同上；是成功案例的有效負向配對 |
| CaptureCoordinatorTest:1752，disable 前恢復延期列 | 刪 settle walk 開頭 resume | 同上；只有單列，不保護正式續頁 while |
| CaptureCoordinatorTest:1773，remove 先補記 | 刪 removeSource callback 的 settle | 同上；fake 會實際清 pendingByPackage，使先後順序可觀察 |
| CaptureCoordinatorTest:1800，另一來源的第 201 列 | replay page 再包含 deferred | 同上；補上來源公平性，但沒有測 100 輪邊界或非空 excluded packages |
| JournalLossTransactionTest:267，順序走訪 | next=null／游標包含自身 | 正式 SQL＋游標模型可辨識；Android mutant 未跑 |
| JournalLossTransactionTest:303，解碼失敗 | cursor 改取 decoded 最後一列 | 模型可辨識這一個變異；整頁空 snapshots 控制仍漏，見 I4 |
| JournalLossTransactionTest:334，index seek | 移除新 index 的 entity 宣告，可使新建 vault 的計畫失敗 | 不是完全沒有產品變更能令它紅；但**單改 DAO SQL 不會改變它 EXPLAIN 的副本**，見 I3。Android 未跑 |
| JournalLossTransactionTest:369，defer 保留證據 | 不呼叫 deferLoss | candidate／resume assertions 可辨識；Android 未跑 |
| JournalLossTransactionTest:401，延期列讓出頁面 | pending 去掉 `lossRecorded != 2` | 正式 SQL 模型已檢查排除效果；Android mutant 未跑 |
| JournalLossTransactionTest:430，延期列可恢復為來源 walk 候選 | resume no-op | 能辨識 resume/filter；此測試本身並未執行 settle 或 discard，完整入口的另一半由 JVM:1752 承擔 |
| JournalLossTransactionTest:449，來源 walk 跳過已補記列 | 去掉 `lossRecorded=0` 過濾 | fixture 同時有 0、1，可辨識；Android 未跑 |
| SourcePolicyTransactionTest:410，中途刪除失敗回滾圖 | 將 graph deletions 搬出 transaction | 目前確實建立資料圖且在後段 deletion 注入 trigger；source trace 支持鑑別力，未確認提交者的 Android mutant 紅燈 |
| SourcePolicyTransactionTest:450，無資料刪除的 callback 回滾 | callback 搬到 transaction 前 | callback 先寫 gap 再 throw，既有缺口已補；source trace，Android 未跑 |
| MessageBubbleSemanticsTest:113，顯示 chip | 不繪製 truncation chip | source trace 支持鑑別力；Compose 未跑，措辭限制見 M2 |
| MessageBubbleSemanticsTest:119，完整內容無 chip | 無條件繪製 chip | source trace 支持鑑別力；Compose 未跑 |
| CaptureCoordinatorTest:1325，補強真實 body 與等待 commit | 接受成功後不再 commit | 綠基線已執行；現在等待 commit 本身，舊同步點問題已修正 |
| CaptureCoordinatorTest:1603，補記失敗不 commit／不花額度 | 失敗後直接 fall through | 綠基線已執行；已有 real body 及 commit=0 assertion，仍不涵蓋 claim=false 的 C1 |
| JournalLossTransactionTest:205，三次失敗與恢復 | defer／resume no-op | 每輪明確檢查恢復與重新 claim；Android 未跑。它不檢查兩個重疊 caller 的終態 fence |

### 三態新增而目前未覆蓋的 mutation／交錯

1. **Deferred 的 claim=false 被視為可 finalize：** 已是 C1 的實際產品缺陷。只靠 claim fake 的 lossClaimed 集合，不能表達這個分支。
2. **只從 `Daos.kt:59` 的 `pendingExcluding` 刪掉 `lossRecorded != 2`：** 新 JVM 公平性 fixtures 的 excluded collection 為空，storage 三態測試也沒有「存在 paused package，且另有 deferred 列」的組合。按 source trace，這個 DAO mutation 缺少對應 oracle；本輪沒有把它報成完整 Android suite 已實跑綠燈。root 的正常 SQL harness 額外跑過非空 excluding 的正確版本，但不會自動替 repository 建立回歸測試。
3. **遇到空 decoded page 就宣告結束：** 本輪模型實跑綠於目前 fixture，紅於真正完整空頁，見 I4。

建議至少加入「0／1／2 與 paused package 共存」的 DAO predicate matrix，以及保留兩個 replay 舊快照的 JVM 反例；不能只讓單次 defer/resume happy path 變綠。

### brief 的 14 項負向控制如何計入證據

未將那張表的「verified red」或其紅燈數量直接採信為本輪結果。#1–6、#8–9 的 coordinator／storage fixture，及 #12–14 的 transaction／chip assertions，已按上表追蹤其可觀察修改；本輪未逐一重跑這些 Kotlin／Android mutants。

#7 的 decoded-cursor、#10 的 tiebreak、#11 的 inclusive cursor 有本輪正式 SQL／游標模型支持；#7 的控制本身有鑑別力，但不足以支持「完整不可解碼頁已受保護」的廣泛結論。#12–13 現在的 observable callback write／後段 deletion trigger 確實比上一輪強，沒有沿用上一輪已失效的批評。

另外，本輪真正編譯實跑的 first-page-only 控制仍綠，說明既有 14 個紅燈即使全都可重現，也沒有關閉正式 settle loop 的缺口。

## 可追溯產物與交付界線

- SQL／控制流主程式：[review_sql.py](/var/folders/9w/6m1x9qr10zv2p2sztxyyxyq00000gn/T/qi-r37-astra-sql-15rmwko_/review_sql.py)，結果 [results.json](/var/folders/9w/6m1x9qr10zv2p2sztxyyxyq00000gn/T/qi-r37-astra-sql-15rmwko_/results.json)。同目錄保留三個 revision 的 DAO、repository、coordinator 與 schema 輸入。
- 七項變異程式：[mutation_models.py](/var/folders/9w/6m1x9qr10zv2p2sztxyyxyq00000gn/T/qi-r37-astra-sql-15rmwko_/mutation_models.py)，結果 [seven_mutation_results.json](/var/folders/9w/6m1x9qr10zv2p2sztxyyxyq00000gn/T/qi-r37-astra-sql-15rmwko_/seven_mutation_results.json)。
- 同目錄的 `cursor_performance_mutants.json`、`additional_results.json`、`global_resume_cost.json` 分別保留退化游標、條件式故障／其他 query plan、全域 reset 成本。
- 正式 coordinator 並行重現：[failing-result.xml](/tmp/qi-r37-mutation.ifIz0A/evidence/concurrent-replay/failing-result.xml)；同目錄 `CaptureCoordinatorTest.reproducer.kt` 保存 barrier 測試，`guard-control-result.xml` 保存局部防線控制的完整結果。
- 正式 first-page mutant：[passing-result.xml](/tmp/qi-r37-mutation.ifIz0A/evidence/first-page-only/passing-result.xml)，同目錄 `CaptureCoordinator.mutant.kt` 保存實際變更。

暫存副本的產品／測試來源已恢復至 target，重現材料另存 evidence。終檢時，原工作樹另出現 `Daos.kt` 的 `isReplayCandidate` 移除 deferred 過濾條件之差異；本審查沒有寫入或還原該差異，本文仍以固定 target 為準。

本審查在工作樹的交付僅本報告；沒有產品修正、commit、push、merge、release 或裝置驗證的完成宣稱。
