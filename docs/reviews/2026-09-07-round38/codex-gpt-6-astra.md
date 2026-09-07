# QuietInbox Round 38 唯讀審查

**Verdict：REQUEST CHANGES**

審查範圍：`21ccbc5..50bfc10`，包含修正提交 `392e80c` 與審查索引提交 `50bfc10`。審查上下文採 `/tmp/qi-r38-brief-safe.md`。本輪沒有啟動工作流程模式，也沒有修改產品程式。

四態 `LossClaim` 已關閉 round 37 的資料遺失契約缺口。新的 coalescing gate 在正常完成時能接續請求，但在持有者被取消時仍可能留下「旗標為 true、gate 已解鎖、沒有執行者」的狀態。本輪用目標版本未修改的編譯產物重現了這個交接問題，因此要求修正後再通過。

## 審查版本與驗證範圍

- 開始審查時工作樹乾淨，分支為 `main`，HEAD 為 `29cfaf0dc60a6f787d101bbd84c91febf8469031`。
- HEAD 相對 `50bfc10` 只多一份 brief 文件；本輪引用的產品、測試與建置檔案和目標版本相同。以下行號均對應 `50bfc10`。
- 已重新執行 `CaptureCoordinatorTest`：**63 tests，0 failures，0 errors，0 skipped**。
- 已在指定的 **`emulator-5556`** 重新執行儲存模組裝置測試。實際產生的 XML 為 **51 tests，0 failures，0 errors，0 skipped**，其中包含 `JournalLossTransactionTest` 20 個及 `SourcePolicyTransactionTest` 13 個。
- 另有獨立 JVM probe，載入目標版本的真實 `CaptureCoordinator`、真實 `VaultMaintenance` 及既有測試 Harness，驗證正常交接與取消交接。
- brief 所列全專案 268 個 JVM、跨模組 60 個裝置測試、lint、APK 權限及字串檢查，是提供者的既有紀錄；本輪沒有把它們當作自己重新執行的結果。

## Critical

**無。** 本輪未找到四態修正仍會讓「gap 尚未寫入」的 row 被提交或由來源停用流程清除的反例。以下 Important 是重播請求的執行保證問題，不是已證實的 payload 遺失。

## Important

### I1：持鎖 replay 被取消時，已合併的請求可能無人接手

**位置**

- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1157`，尤其 `1164–1167` 的解鎖與外層重查。
- maintenance 觸發來源：同檔 `841–848`。
- 取消與等待邊界：`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/VaultMaintenance.kt:85–95`。

**問題與影響**

競爭 caller 設定 `replayRequested = true` 後，若 `tryLock()` 失敗就返回，把責任交給既有持鎖 caller。正常返回會執行解鎖後的外層 `while`；`CancellationException` 則在 `finally` 解鎖後直接向外傳遞，跳過這次重查。

可達交錯如下：

1. A 持有 `replayGate`，在 `maintenance.work` 內重播。
2. maintenance 取消 A 的 work，並等待已註冊的 work job 結束。
3. work 已結束，A 的外層 `replayJournal` 尚未執行完解鎖；maintenance 可以完成並呼叫 `onMaintenanceEnded`。
4. maintenance 結束啟動的 B 設旗標為 true，因 gate 仍由 A 持有而退出。
5. A 解鎖，隨即傳遞取消例外；旗標保持 true，沒有 caller 執行下一個 pass。

`joinAll()` 等待的是 `maintenance.work` 註冊的 coroutine job，沒有涵蓋外層 replay gate 的完整釋放與交接。這使一次實際收到的 maintenance 結束觸發仍可能被擱置。若沒有下一個觸發，待重播資料會繼續留在 journal。這與 brief 接受的「沒有後續觸發時等待」不同：這裡後續觸發已經發生。

**如何驗證**

獨立 probe 使用目標編譯產物，透過反射呼叫原有 `replayJournal`，沿用既有 Harness 的 repository mocks；沒有重寫 replay 演算法。

- 直接取消案例：A 停在 mocked page read；B 呼叫原方法並返回；A 收到 `CancellationException`。A 完成後，page read 只有一次，旗標為 true，gate 已解鎖。
- 真實 maintenance 案例：使用真實 `VaultMaintenance.exclusive` 與 coordinator 已註冊的結束 listener。測試只在第一次 `Mutex.unlock` 前加 latch，其他 Mutex 操作全部委派給原 mutex，以固定「work 結束、外層 gate 尚未釋放」的排程窗口。
- 正常完成對照：相同 unlock latch、相同晚到請求，在沒有取消例外時會執行第二個 pass 並清除旗標。

實際輸出：

```text
DIRECT_ACTUAL_COORDINATOR calls=1 requested=true gateLocked=false ownerCompleted=true
NEXT_TRIGGER_RECOVERS calls=2 requested=false gateLocked=false
NORMAL_LATE_REQUEST_CONTROL calls=2 requested=false gateLocked=false
FORCED_MAINTENANCE_BEFORE_UNLOCK calls=1 requested=true gateLocked=true exclusiveCompleted=true
FORCED_MAINTENANCE_AFTER_UNLOCK calls=1 requested=true gateLocked=false ownerCompleted=true
REAL_MAINTENANCE_OBSERVED=false trialsAtMost=1000
```

probe 編譯與最後執行均 exit 0。程式及輸出位於 `/tmp/qi-r38-codex-replay-probe.kt`、`/tmp/qi-r38-codex-replay-probe.log`。

**證據限制**

這是「真實 coordinator／maintenance 加 mocks 與可控制排程」的 JVM 執行證據。固定 maintenance 交錯使用了反射置入的 Mutex 委派包裝及 latch；沒有修改產品來源檔案。一般 `Dispatchers.Default` 排程的 1,000 次 maintenance 試跑未觀察到持續擱置，因此不能從本輪推算自然發生率。此 probe 沒有使用真實 SQLCipher row，也不是裝置上的完整還原流程測試。

**什麼修正才算完成**

讓持有者取消後，已到達的 caller 仍有明確接手機制。例如保留旗標合併，但讓競爭 caller 使用可取消的 `withLock` 等待；或在異常退出時解鎖後，將尚未處理的請求交給仍存活的 coordinator scope。修正必須同時維持取消傳遞、maintenance 期間不寫入，以及單一 replay 執行者。

回歸測試應固定上述 maintenance 結束／解鎖交錯，斷言無須第三次觸發就有後續 pass，並保留正常完成的晚到請求對照。

## Minor

### M1：NC23 的測試只鎖住 scoped repository 方法，尚未鎖住 policy caller 的選擇

**位置**

- `platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/JournalLossTransactionTest.kt:396–415`。
- 對應 caller：`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1311`。
- 現有停用 deferred row 的 caller 測試：`platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:1782–1801`。

**原因與修正**

新增裝置測試直接呼叫 `resumeDeferredSettlements(pkg)`，可以證明該 overload 的 SQL 不會恢復另一來源。若只把 coordinator 的 `1311` 改回無參數 global resume，這個直接呼叫 scoped overload 的測試仍會通過；現有 coordinator 停用案例只有一個 deferred 來源，也缺少另一來源狀態的斷言。

應加入兩個來源均有 deferred row 的 coordinator 案例，透過 `setSourceEnabled(A, false)` 執行，確認 A 已結算／清除，而 B 仍 deferred。這才能把 NC23 的保證延伸到實際 caller。

**如何驗證與限制**

已讀取目標 caller、mock 的兩個 resume overload，以及相關測試；實際 scoped SQL 裝置測試本輪通過。上述 caller 單行變異的存活判斷是原始碼追蹤，沒有修改產品程式來重跑 mutant。產品目前 `1311` 的 scoped 呼叫是正確的；這是回歸覆蓋缺口。

### M2：交易與 resume 的註解已落後於實作

**位置**

- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:133–145`、`178`。
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:142–145`。

**原因與修正**

`claimEventLoss` 仍保留「回傳是否本次寫入」的舊 Boolean 說明；resume KDoc 仍說兩個 pass 都在開頭恢復。DAO 註解的「每 row 每 pass 一次」也不精確：本輪一個原本未 deferred 的 row 可先失敗一次，在 drain resume 後再失敗一次；一次 resume 並不等於整個 pass 只嘗試一次。

另須區分交易層級：top-level claim 失敗後，catch 的 defer 是 rollback 之後另一筆寫入；當 claim 巢狀於來源 policy transaction 時，最外層交易尚未結束，catch 不會建立一筆獨立持久化的 deferral。policy 失敗最終一起 rollback，資料仍安全。

建議 KDoc 明列四種結果，把 replay 的 resume 時點改成 drain，並將重試次數與巢狀交易敘述限定到實際成立的情境。

**如何驗證與限制**

對照目標控制流程、SQL，以及本機使用的 Room／SQLCipher 交易實作；既有 `SourcePolicyTransactionTest.kt:242–260` 在失敗後可直接重新 claim 的案例也於本輪通過。巢狀 catch 當下的狀態是來源及依賴實作追蹤，沒有另做裝置內部狀態注錯探針；這項 finding 不主張產品存在交易安全回歸。

## 四態契約是否真正關閉原缺口

**已關閉原先的資料遺失 seam。** `claimLoss` 與理由查詢位於同一 `withTransaction`；只有 `RECORDED`、`ALREADY_RECORDED` 讓 `gapIsDurable` 成立。`recordCarriedOverLoss` 將此結果交回 caller，replay 的 `1230–1242` 在 false／例外時阻止提交，policy walk 的 `1319` 則中止整筆來源變更。巢狀 policy transaction 中的「已記錄」尚須等最外層 commit 才持久化，但 gap、來源變更與 discard 共同提交或 rollback，安全不變。I1 是新 gate 的取消交接問題，沒有把原本「未記 gap 卻清除 payload」的缺口搬到另一個 caller。

## 指定接縫逐項判斷

### 1. Coalescing 與 JVM 可見性

正常返回的控制流程沒有找到遺失訊號的交錯。請求在持有者清除旗標之前抵達，由即將開始的 pass 涵蓋；清除之後抵達，會由 inner loop 或解鎖後的 outer loop 接續。若解鎖後另一 caller 先取得 gate，處理責任就由它接手。

這裡不需要對 Boolean 做 atomic read-modify-write：只有 gate 持有者能清除旗標，其他 caller 只會設 true。`@Volatile` 提供跨執行緒可見性；不能把失敗的 `tryLock()` 當成提供 happens-before 的鎖取得操作。正常完成的晚到請求，本輪另有上述實際 JVM latch 對照通過。

取消例外使外層重查不再執行，屬 I1；換成 `AtomicBoolean` 本身不會補上缺少的執行者。

### 2. Rollback 後另一方先 settle，較晚的 defer 是否危險

實際 SQL 為：

```sql
UPDATE event_journal SET lossRecorded = 2
WHERE eventId = :eventId AND lossRecorded = 0 AND state = 'PENDING'
```

第二方已完成 `0 → 1` 與 gap 時，第一方的 late defer 命中零列。使用目標 SQL 的獨立 SQLite 實驗得到：第一方 claim 命中 1、rollback 後為 0、第二方 claim／gap 成功、late defer 命中 0，最終為 `PENDING / lossRecorded=1 / gap count=1`。這是普通 SQLite 的 SQL 驗證；真實 vault 上的 settled-row guard 裝置測試也已通過。

brief 所問「第一方是否回傳過時的 DEFERRED」沒有發生：`IngestRepository.kt:168–171` 始終重拋原例外。replay 會保守地停止該 row 本次處理；policy caller 會 rollback。沒有下游收到並誤用 catch 回傳的 stale enum。

正常 coordinator claimant 同時受 `pipelineMutex` 約束，replay/replay 又有 gate；上述 repository 外部交錯是對 SQL 防線的額外檢查。

### 3. Claim 更新零列，理由卻讀到 0

在目前 schema 與同一寫入交易內，存在且仍為 `PENDING / lossRecorded=0` 的指定 row 會匹配 claim 的 UPDATE，因此未找到「更新零列但理由是 0」的產品路徑。資料庫更新失敗會拋例外，走 catch；不會正常回報零列來掩飾寫入錯誤。

若未來的 trigger 或其他非現有條件產生這種狀態，`else → DEFERRED` 會阻止清除 payload；`isReplayCandidate` 仍為 true，該 row 不會被當成已取得進度。此判斷是 SQL 與來源碼追蹤，不是把不可達分支強行注入真實資料庫的測試。

### 4. Resume-at-drain 的終止與額度邊界

`resumed` 不會重設，`rounds` 持續增加。恢復後再次 defer 的 cohort，下一次空 batch 會因 `resumed == true` 結束；若連 defer 都失敗且其他 row 也無進展，`progressed == false` 會結束。空 batch 分支保留前輪的 `progressed`，使下一次查詢可以發生，沒有因此形成無限迴圈。

仍有明確的額度邊界：生產參數下，99 個一般頁面共 19,800 rows 處理完後，第 100 次空查詢可以把 deferred rows 恢復成 0，卻沒有剩餘輪數再次讀取它們。縮小模型是 `pageSize=2, rounds=3`，四個一般 rows 加一個 deferred row。這是控制流程算術推演，本輪未做此案例的額外 JVM／裝置執行。

因此一次 trigger 不保證所有被 resume 的 row 都會在本 pass 嘗試。下一次 trigger 可以繼續；若需要更強的執行保證，可在恢復成功但額度耗盡時明確保留續跑訊號。這裡保留 payload，也有原本的 bounded-pass 前提，本輪不把它升格為新的 Important。

vault 若在已恢復 cohort 的中途好轉，較早再次 deferred 的 row 也可能等待下一個 trigger。僅在 carried-over settlement 成功後沒有另啟 retry 的行為早於本輪存在。沒有新證據要求推翻一次 resume 或 brief 接受的無後續觸發殘留。

### 5. Global replay resume 與 per-source policy walk

global resume 不持有 `pipelineMutex`，但它與 policy transaction 的寫入仍受資料庫交易序列化約束。

- global 先完成：row 從 2 回到 0；policy 的 scoped resume 可為零列，後續 walk 仍會正常 claim 並結算。
- policy 先完成：row 已 settle／discard，兩個 resume 的 `state = 'PENDING'` 及 `lossRecorded = 2` 條件阻止它被恢復。
- policy rollback：來源旗標、gap、resume 與 discard 的未完成變更一起撤銷，payload 保留。

未找到 global resume 能在 policy 寫入交易中途把已結算 row 改回 deferred，或讓正確完成的 walk 因它誤觸 `check` 的交錯。這是來源碼與 SQL 交易推演；本輪裝置測試確認單來源 rollback、scoped resume 與 settled-row guard，沒有對此雙執行緒時序作完整裝置注錯。

## NC15–NC23：負向控制的實際保證

commit 訊息列出九個控制名稱，本輪沒有取得可逐一核對的 mutator diff 與當次紅燈 XML，也沒有修改產品程式重跑這九個 mutant。下表是對目標原始碼與測試斷言的核對；正常版本的相關測試已重新執行。

| 控制 | 可以變紅的對應行為 | 證據邊界或弱點 |
| --- | --- | --- |
| NC15：walk 只讀一頁 | coordinator 的 201-row 停用測試要求 201 個 claim／gap，並確認 discard 已執行 | 能跨過實際 caller 的 200-row 邊界；原始碼推演可將少讀一頁與斷言失敗直接連結。 |
| NC16：無條件宣稱進度 | cannot-defer 測試限制 `pendingJournal` 呼叫次數 | fake 明確保留 candidate；無條件進度會反覆讀同頁。它證明這個模擬失敗形狀，未模擬真實磁碟全面拒寫。 |
| NC17：在 head resume | 6-row failing prefix、2×3 額度及第二 trigger 的 tail commit | 對額度邊界有針對性，會把同一 prefix 每次復活造成的飢餓顯現出來。 |
| NC18：取消 coalescing | concurrent page read 的最大值可能超過 1 | 測試使用 50 ms delay，沒有 latch 保證兩個 trigger 重疊；只有 `maxConcurrent == 1`，沒有請求最終被處理的斷言。外層 `while` 改為 `if` 仍可保持此最大值並遺失 finish-window 請求。I1 的取消缺口也未被它捕捉。 |
| NC19：把 DEFERRED 當已記錄 | JVM stale-result 測試檢查不 commit／不 mark；裝置四態測試檢查 enum | 若變異點是 consumer／`gapIsDurable`，JVM 測試有意義；若變異點是 repository 分類，固定 stub 的 JVM 測試看不到，須靠裝置四態測試。 |
| NC20：移除 defer 的 0 guard | settled-row 裝置測試直接要求 defer 更新零列，後續仍為 ALREADY_RECORDED | 這項控制對準真實 DAO guard；不是只看 fake 宣稱的狀態。 |
| NC21：遇空 decoded page 就結束 | p3/p4 腐損的走訪與 q3/q4 的非空 cursor 斷言，可抓 repository 丟失 `next` | 兩個測試的 traversal 都在測試本身，沒有經過 coordinator 停用流程。若變異是 coordinator 在 `page.snapshots.isEmpty()` 時提前 return，這兩個 storage 測試不會驗到。應明列 NC 的變異位置，不能將 repository 的紅燈外推到所有 caller。 |
| NC22：失去 cursor index seek | EXPLAIN 使用 DAO 的 hoisted 常數，要求 row-value range seek | `(receivedAtEpochMs + 0, eventId)` 仍保留 binding、能編譯，卻會失去指定 seek 斷言，控制有效。未要求重新報告僅刪 ORDER BY tie-breaker 的已接受情況。 |
| NC23：resume 回到 global | 把 scoped repository overload 本身改成 global，會讓兩來源裝置測試變紅 | 只改 coordinator 的呼叫選擇則越過該測試，見 M1。紅燈需要註明是在 SQL／overload 還是實際 caller。 |

特別注意：若 NC18 的「去掉 gate」只刪 `tryLock()` 卻保留 `unlock()`，可能因解鎖未持有的 mutex 而失敗；若 SQL 變異移除 named binding 卻留下 DAO 參數，可能只是在 Room 編譯時失敗。本輪沒有這些 NC 的確切 patch，不能斷言先前實際發生過這種誤判，也不能僅靠「紅燈」文字排除它。

## 每個新增／實質修訂測試的對抗性檢查

以下列出五個新增 JVM 測試，以及 brief 指定的六個裝置案例。其中裝置案例有四個新增、兩個既有測試被實質加強。變異欄均為來源碼推演，未在本輪修改產品來執行。

| 測試與位置 | 對應的一行產品變異 | 它實際驗到什麼 |
| --- | --- | --- |
| `CaptureCoordinatorTest.kt:1867` stale batch／DEFERRED | 將 coordinator `1230` 改回 `if (settled.isFailure)` | 正常回傳 DEFERRED 會落入 commit，使負向斷言失敗。實際測試沒有 brief 所述的 latch 或第二個 pass；它是 consumer 契約測試。 |
| `CaptureCoordinatorTest.kt:1902` cannot defer | 將 coordinator `1238` 的條件改為 `if (true)` | 反覆讀候選頁面，超過測試的 atMost 2。 |
| `CaptureCoordinatorTest.kt:1931` 超過整個 pass 的 prefix | 在 `replayPass` drain loop 前新增 `ingest.resumeDeferredSettlements()` | 第二 trigger 又先讀六個失敗 rows，tail commit 不發生。 |
| `CaptureCoordinatorTest.kt:1973` 201 rows 停用 | 將 coordinator `1323` 改成直接 `return` | 僅 settle 第一頁，claim／gap 數停在 200。 |
| `CaptureCoordinatorTest.kt:2001` 兩個 trigger | 在 `replayJournal` 開頭加入 `replayPass(); return` 繞過 gate | 若兩次呼叫重疊，maxConcurrent 會超過 1；**沒有排程保證可使這項併發變異每次必紅**。把 gate 永久鎖住也能使它因「零次執行」逾時，但那不能證明互斥或交接防線有效。 |
| `JournalLossTransactionTest.kt:306` 腐損的中間整頁 | 在 repository `211` 建立 next 時，加上 `snapshots.isNotEmpty()` 條件 | 空 decoded 頁把 cursor 截斷，走訪漏掉 p5。 |
| `JournalLossTransactionTest.kt:338` 空頁仍有 next | 同上 | 第二頁的 `next shouldNotBe null` 直接失敗，並保護 q5 可達性。 |
| `JournalLossTransactionTest.kt:366` 四態結果 | 將 repository `164` 改為 `else -> LossClaim.ALREADY_RECORDED` | deferred 分類斷言失敗。測試沒有直接列舉四種 `gapIsDurable` 值，也沒有安排 reason-read 的競爭者。 |
| `JournalLossTransactionTest.kt:396` scoped resume | 將 repository scoped overload 的 `186` 改為委派 `resumeDeferredLosses()` | 兩來源都被恢復、回傳 2，與只恢復自身的斷言矛盾；caller 邊界見 M1。 |
| `JournalLossTransactionTest.kt:427` settled 不可 defer | 從 DAO `136` SQL 移除 `AND lossRecorded = 0` | defer 更新 1 列，第一個斷言即失敗。 |
| `JournalLossTransactionTest.kt:449` cursor seek | 將 hoisted SQL 中的 tuple 改成 `(receivedAtEpochMs + 0, eventId)` | row-value seek 的 plan 字串斷言失敗，避免只查到索引名稱就通過。 |

`SourcePolicyTransactionTest.kt:259` 及其餘舊案例主要是 Boolean → enum 的斷言調整；本輪沒有把它們計為新的並行或 caller 測試。

測試證據整體已比前一版強：跨頁停用、候選集進度、整 pass 的 prefix，以及真實 SQL 的狀態 guard 均有對應斷言。仍需補足的是 gate 的取消交接，以及 NC 宣稱涉及的 caller 層邊界。

## 本輪驗證紀錄

JVM 命令：

```sh
./gradlew :platform:capture:testDebugUnitTest \
  --tests dev.quietinbox.platform.capture.CaptureCoordinatorTest \
  --rerun --console=plain
```

結果：BUILD SUCCESSFUL；XML 記錄 63 tests、0 failures／errors。紀錄：`/tmp/qi-r38-codex-capture-jvm.log`。

裝置命令：

```sh
ANDROID_HOME=/Users/iml1s/Library/Android/sdk ANDROID_SERIAL=emulator-5556 \
  ./gradlew :platform:storage:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.quietinbox.platform.storage.JournalLossTransactionTest,dev.quietinbox.platform.storage.SourcePolicyTransactionTest \
  --rerun --console=plain
```

結果：BUILD SUCCESSFUL。雖然命令指定兩個類別，實際新產生的 XML 包含整個 storage 模組 51 tests，全部通過；報告採實際執行結果。紀錄：`/tmp/qi-r38-codex-storage-instrumented.log`。

取消 probe：使用 Kotlin 2.4.10 編譯器、專案實際 JVM 測試 classpath 與 coroutines 1.11.0；程式、編譯輸出與暫存 class 都放在 `/tmp`，沒有修改產品或正式測試來源。probe 同時包含正常交接對照、直接取消、固定 maintenance 交錯，以及一般排程觀察。

本輪結束條件為交付這份唯讀審查報告；I1 的修正與回歸測試仍是後續產品工作。brief 明確排除的 issue #28、WhatsApp separator、backup merge truncation、停用失敗 UI 回饋，以及已接受的字串／ORDER BY 意見，均未重新列為本輪新 finding。
