# QuietInbox Round 36 唯讀審查

## Verdict: REQUEST CHANGES

Round 35 的「補記失敗消耗 commit 重試額度」已修正，也沒有發現同等級的新 payload 銷毀缺陷。但目前保留 PENDING 的做法會使失敗列永久占住 replay 首頁，新增的分頁查詢又在缺乏可用排序／游標索引的情況下反覆掃描整批待處理列。這兩項應在 push 前修正；新增測試仍有幾個無法區分錯誤實作的控制。

審查主體是 `f410809`、`8e3bb3b`，起點為 `f7a09ed`。開始時 HEAD 為 `c7a882e377e1cbba3c055a45aabf73ddd0721207`；共享分支後來加入 `44ce4843ffa9833f6f48ccc1f7a28436c81cafad`，只修改 import 排序、註解、CHANGELOG 與測試檔尾。已讀該差異，未改變本報告的行為結論。以下行號以 **44ce484** 為準。

依 BRIEF 讀取 Round 35 三份審查報告、兩筆指定提交及相關正式程式、測試、schema、文件。本輪其他審查員的報告未讀取。沒有啟用 workflow mode，沒有執行 Gradle、Android 測試、模擬器或真機操作。新執行的驗證是唯讀 Git／source 查詢，以及 Python 記憶體內的 SQLite 與控制流模型；沒有建立測試檔或資料庫檔。下文會區分模型實測、source 推導與既有測試紀錄。

**範圍排除：** Round 35 Codex I1（WhatsApp 在換行邊界截斷而遺失整列，沒有對應 gap）、I2（backup duplicate 命中時不合併截短證據）仍是明確延後的工作。本報告不把它們列成本範圍的 regression，也不要求把新 loss-writing path 塞回這兩筆修正。

## Critical — push 前必須修正

**未發現新的 Critical。**

新的 claim 失敗分支不會進入 `markJournalRetryable`，不會直接 commit／discard，也不會清空 payload。這個安全性修正成立；下列 Important 是其活性、效能與回歸保護仍未完成的部分。

## Important — push 前應修正

### I1. 永久 PENDING 的補記失敗列會擋住 replay 後頁；寫入恢復也不會自行重新排程

**位置：**

- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1094`、`:1099`、`:1124`、`:1126`、`:1134`。
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:176`。
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:46`、`:54`。

replay 使用的仍是「最早 200 列」查詢，沒有游標或本輪失敗列排除集合。新增分支在補記失敗時保留該列並離開這一次 `withLock`；若整頁都如此，`progressed` 維持 false，整次 replay 結束。

具體反例是前 200 列都帶有待補記損失，gap INSERT 持續失敗；第 201 列是不需補記、可以正常 commit 的事件。第 201 列永遠進不了 batch，即使它來自另一個正常來源、甚至使用者反覆觸發 replay，仍然如此。與修正前相比，失敗列現在不再因 attempts 耗盡而離開首頁；保留證據是正確的，但缺少讓後頁前進的安排。

**本次驗證：** 從目前 `Daos.kt` 抽取正式 `pending`、`claimLoss`、`setState` SQL，以 exported schema 在 SQLite `:memory:` 建表；TEMP trigger 只阻擋 gap INSERT，狀態更新仍可成功。依目前 replay 控制流執行：

| 輸入 | 執行結果 |
| --- | --- |
| 1 列補記失敗 + 同頁 1 列正常事件 | 正常事件成功處理；失敗列在下一輪再試一次後停止 |
| 200 列補記失敗 + 第 201 列正常事件 | 每次 replay 只做 200 次 claim，沒有 commit |
| 對上一列情境連續觸發 3 次 replay | 201 列仍全為 PENDING；正常尾列從未被處理 |

這不是「第一列失敗就跳過整個 batch」：同頁後面的列確實仍會跑。問題發生在失敗列填滿整頁時。

另有恢復觸發缺口：全部 `replayJournal()` 呼叫點是 vault Ready（`:281`）、全域恢復（`:385`）、來源解除暫停（`:713`）、維護結束（`:790`）。沒有失敗重試計時器；一般新事件成功接受也不會觸發 replay。`f410809` 新增的 `:928` 只補記 RAM 裡的 `journalLossSince`，不處理這些已持久化的舊列。因此即使 gap 寫入已恢復，只要沒有上述觸發，受阻列仍可無限期等待。恢復後**明確再呼叫一次** replay 可以成功，不能把它說成永遠不可恢復。

**建議：** 保留「補記不耗事件額度」的規則，同時讓一次 replay 能走訪後頁，並提供有限、可取消且有退避的恢復重試。測試涵蓋同頁成功列、201 列跨頁、來源之間公平性，以及 vault／來源設定不變的失敗後恢復。不要把 `progressed` 一律設 true，也不要用銷毀失敗列解除阻塞。

**證據限制：** SQL 與控制流模型，不是 SQLCipher 磁碟滿載或完整 coordinator 整合實測。缺少自動觸發則由全域呼叫點追蹤確認。

### I2. 新分頁在現有索引下反覆掃描所有 PENDING 列，使停用／移除的鎖定工作量接近平方成長

**位置：**

- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:80`。
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Entities.kt:47`。
- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:456`、`:1175`、`:1180`。

新增查詢依 `(receivedAtEpochMs, eventId)` 篩選與排序，但 journal 只有 eventId 主鍵、state 索引及 expiresAt 索引，沒有支援這次來源／排序查詢的索引。claim 只改 `lossRecorded`，不會將已處理列移出 PENDING 集合；於是每一頁都重新掃描相同的候選集合，再篩掉游標以前的列。

**本次驗證：** 用 schema 4 匯出的正式建表與索引 SQL，執行正式分頁 SELECT。SQLite 查詢計畫為：

```text
SEARCH event_journal USING INDEX index_event_journal_state (state=?)
USE TEMP B-TREE FOR ORDER BY
```

以 progress handler 計數 SQLite VM 指令，僅測讀取、未包含 JSON 解碼或 claim：

| 同來源 PENDING 列數 | 分頁查詢次數 | 新分頁總 VM 指令 | 原本一次讀取的 VM 指令 |
| ---: | ---: | ---: | ---: |
| 1,000 | 6 | 86,214 | 16,011 |
| 2,000 | 11 | 282,379 | 32,011 |
| 4,000 | 21 | 1,004,709 | 64,011 |
| 8,000 | 41 | 3,769,369 | 128,011 |

新增 LIMIT 降低了單頁物件／解碼峰值，這點成立；但所有頁仍在同一個 `changeSourcePolicy` mutex 與同一個 source transaction 內跑完，頁間不會釋放 pipeline 鎖。上述新增掃描成本都由等待中的 live capture 承擔。`:1177` 的「bounded per page」只能用來描述單頁大小，不能描述整次持鎖時間。

**建議：** 為這個查詢提供能實際從複合游標位置搜尋的 SQL 與配套索引，並驗證查詢計畫及大量同時間戳資料；不能只看到 LIMIT 就認定工作量已受限。保留 settle／discard 的交易原子性。

**證據限制：** 這是目前 exported schema 下的 SQLite 計畫與工作量測量，不是 Android 裝置耗時、ANR 或記憶體量測；不據此宣稱某個固定毫秒門檻已被突破。

### I3. 新 replay 測試仍由 fake 決定列何時消失；新分頁也沒有跨頁回歸控制

**位置：** `platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:265`、`:1516`、`:1529`、`:1541`。

新測試「a carried-over row whose loss cannot be written is not committed and spends no attempt」把 `pendingJournal` 設成只回傳一次，第二次查詢就回傳空清單，卻同時將 `isJournalPending` 固定為 true。它沒有表達正式資料庫的行為：未更新的列會繼續出現在後續查詢。

它能抓到「claim 失敗直接呼叫 markJournalRetryable」與目前 fixture 的 fall-through，因此不是毫無價值。但它不能證明持續 PENDING 的重試／停止／恢復行為，正好漏掉 I1。

**本次負向控制模型：** 在記憶體副本將失敗分支改成 `progressed = true; return@withLock`：

| 資料來源 | 正式分支 claim 次數 | 錯誤分支 claim 次數 | 現有 assertion 能否區分 |
| --- | ---: | ---: | --- |
| 測試的一次性 fake | 1 | 1（第二次查詢直接空） | 不能 |
| 會持續回傳 PENDING 的資料來源 | 1 | 100 | 仍不能：測試只要求 atLeast 1，沒有查詢／嘗試上限 assertion |

100 是現有 rounds 上限，所以這個 mutant 是大量空轉，不是字面上的無限迴圈。

分頁 fake 同樣省略了關鍵行為：`:268` 不理會 `after`、`limit`，一次回傳整個 package 的清單，並固定 `next = null`。把正式 `settleCarriedOverLosses` 改成只處理第一頁，使用該 fake 仍會處理全部 201 列；真實每頁 200 列的資料來源只會處理 200 列。搜尋全部相關測試，未找到接續非 null JournalCursor 的跨頁 case。

**建議：** 讓 fake 的 pending／terminal state 與游標查詢一致，再加入持續失敗的有界嘗試、恢復後完成、201 列、同時間戳、整頁不可解碼及整數頁尾的 assertions。上述 mutation 是記憶體控制流模型；本輪沒有重跑 Kotlin mutant suite。

### I4. remove 的兩個 failure controls 有補強，但沒有證到「完整刪除圖中途失敗全部回滾」

**位置：**

- `platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/SourcePolicyTransactionTest.kt:272`、`:286`、`:298`、`:306`。
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SourceRepository.kt:99`。

正式順序是 source delete → callback → checkpoint delete → pending discard → 選擇性的 media／conversation／suppression／summary／diagnostic 刪除。兩個新 case 都在 callback 內拋錯，後面的資料圖刪除根本還沒開始。

`aRemoveWithDataWhoseCallbackFailsLeavesTheWholeGraphInPlace` 只建立 source、pending journal 與 gap，沒有建立 checkpoint、conversation/message、media、suppression、summary 或 diagnostic。它不能支持「whole graph」的完整回滾宣稱。

可判別性亦不對稱：

- `deleteData=false` 的 callback 只有 `error()`。將 callback 移到 transaction **之前**，這個測試仍會在任何資料庫修改前拋錯，全部 assertion 仍成立。
- `deleteData=true` 先 close gap 再拋錯；相同的「移到 transaction 前」mutation 會留下已提交的 close，所以這一個控制確實能抓到它。
- 若只把 callback **之後**的刪除移出 transaction，兩個新 failure case 都會先在 callback 拋錯，仍無法區分失去後半原子性的版本。

以上前兩項已在 SQLite 記憶體交易模型驗證：false 分支 baseline／mutant 均保有 source、pending、open gap；true 分支 mutant 的 gap end 變成 4000，assertion 才失敗。

**建議：** 建立實際資料圖，至少在 callback 已成功、部分後續刪除已執行時注入一次失敗，驗證整張圖與 claim／gap 一起回滾；false 分支也應在 callback 內先做可觀察修改再拋錯。現有正式 `SourceRepository.remove` 的資料庫操作都仍在同一個 transaction 內，這是測試與覆蓋宣稱的缺口，沒有證據顯示目前 production 已經半套提交。檔案刪除在交易成功後的 `:113`，另有其原本的界線。

## Minor / nitpicks

### M1. 新增的 commit assertion 等錯了同步點，可能讓正確程式偶發失敗

`platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:1252` 等的是 acceptance callback 的 `recordGap`，`:1256` 卻立即以沒有 timeout 的 `coVerify(exactly = 1)` 檢查稍後才會發生的 `commit`。

正式順序是 gap → journal 返回 → deferred loss settle → parse／lookup → commit；看到前面的呼叫，不能推導後面的呼叫已發生。測試執行緒可以在這兩者之間被排程。應等待 commit 呼叫或明確的完成 barrier 後，再斷言沒有 SKIPPED。

本輪以 happens-before 關係確認競態；未實際重現 Kotlin 測試偶發失敗。新的 fixture 確實已有 body，已修正 Round 35 指出的「名稱說成功、實際只走 SKIPPED」。

### M2. historical 模型已清楚，使用者標籤與旁邊的註解仍有歧義

`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:307` 已把 Known 規則明確命名為歷史證據。但：

- `core/designsystem/src/main/res/values-b+zh+Hant/strings.xml:97` 仍顯示「文字被截短」，英文 `values/strings.xml:99` 為「text was shortened」。
- `core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Labels.kt:102` 說這是「儲存之前犧牲的內容」，然而 Known 可以在原 row 已保存之後才加上旗標。

對同一 bounded body S，觀察依序為 false → true → false 時，最後一次觀察並未被截短，旗標仍是 TEXT。這符合已選定的歷史模型。現有標籤也可以被讀成過去式，因此不把它判成資料模型錯誤；但貼在目前 bubble 上，仍容易被理解為目前這次通知不完整。

建議文案明示「曾在某次通知中被截短」，並修正 `Labels.kt` 的時間敘述。這是 copy／contract 一致性的問題，不應透過讓任意 false replay 清旗標來解決。

### M3. JournalPage.next 的文件把「可能還有下一頁」寫成精確的末頁判斷

`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:505` 寫「null when the page was the last」，但恰有 200 列時，該頁已是最後一個非空頁，`:170` 仍回傳 cursor；要再查一次空頁才得到 null。

行為正確，沒有漏列或迴圈；應將 KDoc 改成「非 null 表示需要繼續查詢；null 表示已確認結束」。不要把 non-null 解釋成保證還有一列。

## 已核對成立的宣稱、證據與限制

### 1. claim 的新位置確實保住事件重試額度，取消也能傳播

正式 `claimEventLoss` 在 `IngestRepository.kt:133` 將 conditional UPDATE 與 gap callback 放在同一個 transaction。補記失敗後，coordinator 在 `:1126` 離開本次 lock block，不進入 commit 的 try。

以正式 SQL、schema 與只失敗 gap INSERT 的 trigger，連續做三次失敗 pass：

| 初始 attempts | 失敗後 state | 失敗後 attempts | payload | lossRecorded | gap |
| ---: | --- | ---: | --- | ---: | ---: |
| 0 | PENDING | 0 | 原字串仍在 | 0 | 0 |
| 2 | PENDING | 2 | 原字串仍在 | 0 | 0 |

移除 trigger 並明確執行恢復 pass 後，兩組都能成功處理，且只有一筆 gap。payload 在此測試是四字元不透明代號，驗證的是 SQL／retry 組合，不是假裝跑過 snapshot JSON 解碼。

`runCatching` 會捕捉例外，但 `CaptureCoordinator.kt:1125` 在其外明確重新拋出 `CancellationException`；外層 `guarded` 的 `:1193` 也重新拋出，取消不會被轉成成功或一般補記失敗。`return@withLock` 只跳過這一列，正常釋放鎖，同頁後列仍可繼續。

`progressed` 對「本輪是否有列離開 PENDING」的判斷是合理的，能停止全失敗頁的忙迴圈；它不等於公平走訪或未來重試保證，後兩項見 I1。

### 2. 來源停用／移除的 keyset 分頁在目前交易邊界內不漏列，也不會無限重讀

這是 `pendingJournalForPackage`，與 I1 使用的普通 replay 查詢不同。

`Daos.kt:81` 的過濾條件與 `:83` 的排序同為 `(receivedAtEpochMs, eventId)`；eventId 是主鍵，故同時間戳也有唯一順序。claim 不改這兩欄。每個滿頁 cursor 嚴格大於前一個 cursor；不可解碼列雖不進 snapshots，仍以原始 rows 的最後一列推進，見 `IngestRepository.kt:165`、`:168`。

本次用正式 SQL 執行，包含超過一頁的時間戳 ties，且每次 claim 都刻意保留 PENDING：

| 原始列數 | 各頁原始列數 | 唯一訪問列數 |
| ---: | --- | ---: |
| 0 | 0 | 0 |
| 1 | 1 | 1 |
| 199 | 199 | 199 |
| 200 | 200、0 | 200 |
| 201 | 200、1 | 201 |
| 400 | 200、200、0 | 400 |
| 401 | 200、200、1 | 401 |

另模擬首 200 列全部解碼失敗，cursor 仍讓第 201 列被讀到。整數頁尾多一次空查詢，屬正常的 exhaustion probe。

**走訪途中插入：** 正式呼叫點都在 `SourceRepository.setFlag`／`remove` 的寫入 transaction 內，且外層持有 pipeline mutex。source 寫入已在 callback 之前發生；一般 capture 不能在此時插入 journal，外部 writer 也不能穿插提交。記憶體雙連線模型中，政策 write transaction 期間另一 writer 得到 `database table is locked`，交易結束後才可寫入。這個模型是 SQLite 交易互斥證據，並非 Room coroutine 排程實測。

若把這個 API 單獨拿到交易外使用，游標以前的新插入當然可能漏掉，持續插入也會改變終止條件；那不是目前 production caller 的用法。START 的 `(Long.MIN_VALUE, "")` 對正式 factory 產生的時間與 eventId 是有效起點，不主張它覆蓋任意手工損壞的極端資料。分頁的正確性不抵銷 I2 的成本問題。

### 3. settle／discard 順序測試現在確實可以區分反轉

正式位置是 `CaptureCoordinator.kt:694`／`:695`。本輪實際將這兩個呼叫在**記憶體中的 source 副本**交換，依新 Harness 的 pending/discard 狀態轉移執行：

| 順序 | 剩餘 pending | loss gap | 「gap 應為 1」assertion |
| --- | ---: | ---: | --- |
| settle → discard | 0 | 1 | 成立 |
| discard → settle | 0 | 0 | 失敗 |

對應會變紅的是 `CaptureCoordinatorTest.kt:1425`：

> pending rows discarded with their source are settled while their payload can still be read

失敗位置為第一次停用後的 `:1443`。目前這個測試是該檔唯一直接放入 `pendingByPackage` 舊列的 case；其他沒有待結算列的政策測試不會因此自動變紅。

`SourcePolicyTransactionTest.disablingASourceSettlesItsPendingRowsAndDiscardsThemTogether` 仍在測試內自己寫 settle／discard lambda，沒有呼叫 coordinator，故不會因正式 coordinator 那兩行交換而變紅。不能把它當作第二份獨立的順序控制。

**限制：** 實際執行的是 source-order 模型，沒有修改正式檔案，也沒有執行 Kotlin mutation suite；上述「哪個測試變紅」是對現有 fixture／assertion 的可達性推導，不是本輪 Gradle 紅燈紀錄。

### 4. event_journal mutation 全域盤點：目前沒有找到 issue #28 以外的新自動清除出口

以下短檔名分別指正式 storage 的 `db/Daos.kt`、`db/QuietInboxDatabase.kt`、`repo/IngestRepository.kt`、`repo/SourceRepository.kt`，以及正式 capture 的 `CaptureCoordinator.kt`。

| mutation／呼叫來源 | PENDING 與補記關係 |
| --- | --- |
| INSERT IGNORE：Daos:43；IngestRepository:95–120 | 建立 PENDING。新版本可判定的 whole-content loss 與 acceptance 同交易；只有帶 loss callback 的事件令 lossRecorded=true |
| claimLoss：Daos:100；IngestRepository:133 | 只將 PENDING 的 lossRecorded 由 0 改 1；gap 同交易，失敗回滾，不退出 PENDING |
| setState → COMMITTED：IngestRepository:269、480 | 同內容交易提交；live 已在 acceptance 記損失，replay 先經補記 gate |
| setState → DISCARDED：CaptureCoordinator:981 | 來源 commit fence；同樣已經過 live acceptance 或 replay 補記 gate |
| setState → FAILED/PARSE：CaptureCoordinator:1008 | 在 processJournaled 內，先前 gate 已經執行；不是另一條略過 carried-over loss 的出口 |
| setState → SKIPPED：CaptureCoordinator:1013 | 同上；parser 沒有內容不會抹掉先前已寫入的 loss |
| setState → FAILED/DECODE：IngestRepository:182 | payload 不可解碼，未能判定 carried-over loss；是明列例外。來源 discard 的讀取亦會跳過不可解碼 payload |
| retry → PENDING 或 FAILED：IngestRepository:199–203 | PENDING 保留 payload；第三次改 FAILED 並清 payload。commit exhaustion 無 gap 是已知 issue #28，不重報 |
| discardPending：Daos:104；disable／remove | disable 的 coordinator:694 先 settle；remove 的 coordinator callback:726 先 settle，SourceRepository:103 才 discard；失敗會中止政策交易 |
| deleteExpired：Daos:116；RetentionWorker:106 | 明確排除 PENDING，只刪除已終止的過期列 |
| deleteAllExpired：Daos:119 | SQL 能刪 PENDING，但目前 repository 沒有呼叫者；不是可達自動出口 |
| clear：Daos:128 | SQL 能刪全部，但目前沒有呼叫者 |
| schema 2→3／3→4：QuietInboxDatabase:92、125 | 新增 packageName／lossRecorded 欄位；不終止或移除既有列 |
| 整個 vault 的 deleteEverything：VaultRepository:47–65 | 使用者明確要求刪除整個 vault，屬產品刪除操作；不承諾在已刪除的 vault 裡保留 gap |
| backup import | 不寫 event_journal；沒有額外出口 |

schema 的 CREATE TABLE、測試 fixture 的直接 INSERT／UPDATE／DELETE 不構成新的正式 consumer。普通刪除訊息／會話的圖也不會透過 FK cascade 刪除 journal。

因此，四處改寫後的 invariant 若解讀為「正式 capture 流程對**可判定 carried-over loss**的結算邊界，並列出 decode／#28 例外」，已完整；不能把它升格成「任何 DAO 呼叫都不可能刪除未結算列」或「所有任意 loss 都已被記錄」。本輪未找到另一條可達的靜默 terminal mutation。I1 是未離開 PENDING 的活性缺陷，與 #28 分開。

### 5. set-only 已正確限定到 Known；其他 truncationFlags 寫入不服從單調規則

| 寫入路徑 | 目前行為 |
| --- | --- |
| IngestRepository:353–390，New／Ambiguous INSERT | 依該 candidate 的 textTruncated 寫 TEXT 或 null |
| IngestRepository:405–420，Known；Daos:314 | 只在原值為 null 時標記；相同 body 的歷史證據不因 false observation 清除 |
| IngestRepository:423–429，Revision；Daos:297 | body 與旗標一起替換，旗標可以回到 null |
| BackupService:338–370，新列 restore | 保存備份帶來的旗標；duplicate skip 的證據合併問題依 BRIEF 延後 |
| DemoDataRepository:215–250 | debug 資料未指定旗標，使用 null 預設值 |
| schema 3→4，QuietInboxDatabase:124 | 舊 message 新增 nullable 欄位，預設 null |
| MessageDao.update：Daos:291 | 全 entity 更新介面理論上可覆寫旗標，但目前沒有呼叫者 |

新的 Known KDoc 因此符合程式；它也沒有假裝 revision 保留所有過去版本的截短歷史。UI 的時間語意可再明確化，見 M2。

### 6. 文件數字已重新推導，測試數量更正成立

兩份 `TEST_MATRIX.md:11–29` 的測試數以 source 重新計數；包含 JUnit `@Test`、Kotest FunSpec 與 MediaRead 的 StringSpec，不能只 grep `test(`。兩份 `SCOPE.md` 的 coordinator 數分別位於英文 `:20`、繁中 `:18`。

| JVM 模組／類別 | 個數 |
| --- | ---: |
| core:model SearchNormalizer | 5 |
| core:parser StandardParser | 13 |
| core:identity IdentityResolver | 5 |
| core:reconcile Reconciler 20 + Property 2 | 22 |
| core:analytics ActivityAnalytics 6 + Insights 28 | 34 |
| core:designsystem Monogram 6 + TimeFormat 2 | 8 |
| parsers:apps Instagram 8 + Registry 4 + Telegram 7 + Messenger 8 + WhatsApp 10 + LINE 8 | 45 |
| app ReminderScheduler | 5 |
| platform:backup BackupStager 21 + Hkdf 3 | 24 |
| platform:capture CaptureCoordinator | 52 |
| platform:crypto RecoveryKeyCodec | 3 |
| platform:media MediaRead | 10 |
| platform:storage VaultMaintenance 5 + VaultRepository 3 + SuppressionRule 4 | 12 |
| feature Analytics 8 + Search 5 + Onboarding 5 + Conversation 1 | 19 |
| **JVM 合計** | **257** |

不含 designsystem 的 core 測試合計為 79；兩個 property case 各跑 1,000 次，不應加成 2,000 個測試。

| Android instrumented | 個數 |
| --- | ---: |
| storage：VaultRoundTrip 4 + Migration 4 + DemoData 2 + DeletionGraph 5 + SearchPaging 2 + MediaExportBound 1 + SourcePolicyTransaction 11 + JournalLossTransaction 9 | 38 |
| backup：BackupRoundTrip | 2 |
| crypto：WrappedSecretFile 1 + KeystoreWrapper 1 | 2 |
| conversation：MessageBubbleSemantics | 3 |
| **Android 合計** | **45** |

總計為 302 個 case。兩筆修正將 JVM 255 → 256 → 257，storage instrumented 34 → 38；Monogram 6、MessageBubbleSemantics 3，以及有 JVM 測試的四個 feature ViewModel 更正均與 source 一致。沒有找到另一個新的測試**計數**錯誤；測試的證明力限制不能由這個總數消除，見 I3、I4、M1。

其他兩語系文件的數值／架構宣稱也逐項區分：

| 宣稱 | 重推結果與界線 |
| --- | --- |
| schema 3→4 新增欄位 | 3 欄：gap.packageName、message.truncationFlags 可為 null；journal.lossRecorded 是 NOT NULL DEFAULT 0。矩陣只說「兩個 nullable 欄位」並另列 journal claim，並非仍說 migration 只有兩欄 |
| gap 寫入點 | coordinator 15 + HealthRepository:45 的 PROCESS_RESTART 1 = 16；其中 10 個 process-wide、6 個來源範圍。QuietInboxDatabase:103–105 已更正 |
| core 模組種類 | designsystem 是 Android Compose library；兩份 ARCHITECTURE:18 已明確排除，其他演算法 core／parsers 是 JVM |
| analytics 每 period 上限 | AnalyticsRepository:49 的 50,000；不是來源完整歷史保證 |
| retention | RetentionWorker:133 排程間隔 12 小時；不是本輪量測，也不能由此證明裝置必定準時執行 |
| restore staging | BackupRecords:130、132、135：2,000,000 records、16 × 1024² 個文字 code units、256 × 1024² media bytes；文件已標示 nominal limits／heap 風險 |
| cold start | CaptureCoordinator 的 MAX_HELD=256、timeout=15,000 ms；文件 256／15 s 相符 |
| commit retry | MAX_ATTEMPTS=3；只適用 commit 額度，現在不含 carried-over settlement |
| UI 寬度 | source 的 medium／expanded 分界與文件 600dp／840dp 相符；沒有重新量測 411／720／851dp 裝置畫面 |
| 金鑰配置 | 三份 32-byte secret、AES-256-GCM／HKDF-SHA256 是現有 source 配置；不是本輪密碼學或 Keystore 裝置認證 |
| 五個來源 adapter／五語系 | repository 列舉一致；來源測試仍是 SYNTHETIC_ONLY，不升格成真實來源 E2E |

SCOPE 的 10–14 週計畫、3/3 合成擷取紀錄、歷史 review round／issue 編號、版本碼 4–7 與 0.1.0–0.1.3、172 個地區、49 張截圖、Android/API 與螢幕尺寸數字，屬規劃或既有發布／裝置證據。它們不能靠數原始碼重新證明，本輪未向商店、CI 或裝置做 live 驗證。72 小時 soak、API 26、16 KB page size 等則是明列未完成的驗證界線，不應誤算成已通過項目。

另讀到既有 CaptureCoordinator JUnit XML：52 tests、0 failures/errors/skipped，時間為 `2026-09-07T12:19:52.236Z`。這是既有 artifact，未提供本輪 mutant、I1／I2 或全部 257／45 測試的新執行證據。本報告不宣稱重新跑過整套測試。

### 7. f410809 的正常寫入恢復觸發確實新增成功，限制也保留

`CaptureCoordinator.kt:922–930` 在 journal 成功接受後，會呼叫 `settleUnrecordedJournalLoss`；`:519–526` 只有 gap 寫入成功才清除記憶。一般補記例外由 guarded 吞下，不會妨礙本次內容處理或消耗該事件額度；取消仍向外傳播。

新增 `CaptureCoordinatorTest.kt:1481` 保持來源設定不變，送入下一筆可接受事件，並核對 policyLoads 未增加，確實針對 Round 35 的 RAM deferred-loss 觸發缺口。這與 I1 的「持久化舊 journal 列補記失敗後重播」是兩種不同義務。

保證仍有條件：程序須存活，之後須有可用的寫入機會；若 gap 再次失敗，記憶會保留，並不保證這一次就能落盤。`44ce484` 對 BOUNDED 區間可能包含中途成功擷取時段的補充亦與程式一致。

本審查唯一寫入的檔案是 `docs/reviews/2026-09-07-round36/codex-gpt-6-astra.md`。尚需的是 I1／I2 的修正與相應測試，以及 I3／I4 的可判別回歸控制；上述唯讀模型不替代正式 Kotlin／Room／SQLCipher 整合驗證。

