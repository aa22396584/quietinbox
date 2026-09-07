# 第 34 輪唯讀審查：第 33 輪的四個修正提交

審查範圍：`42f8d18..60fa4f0`，依序核對 `f5f9581`、`a258662`、`234e5cd`、`60fa4f0`。基準完整 SHA 為 `60fa4f0d9f7e96553957455a5b5952f3725a1c94`。本文所有現行程式行號均指這個提交；歷史程式另標 `v0.1.3`。

依指定 BRIEF 讀取第 33 輪報告、四個提交及相關呼叫鏈，未啟動工作流程模式。本輪只寫入本報告。審查期間工作樹出現其他修正與新增測試，交付前 HEAD 已移至 `022b99a3e2237c8b1ec374d4bb191fd06253fa53`；那些後續變更不屬於指定範圍，也不納入本輪結論，未加以修改。

## Verdict

**REQUEST CHANGES**

兩項必須在 0.1.4 出貨前修正的問題仍存在：新增的來源缺口調和邏輯會關掉仍有效的暫停缺口；正式 v0.1.3 留下的、可確定曾丟失內容的 journal，在升級重播後仍會漏記缺口。另有 WhatsApp 截點誤標、Known 路徑遺失逐則截短資訊、接受失敗的補記缺口及測試控制力問題。

本輪在儲存庫外以 `git archive 60fa4f0` 建立副本，實際重跑 **246 個 JVM 測試，0 failures、0 errors、0 skipped**。測試 task 強制重新執行，沒有把快取的測試結果算成新執行；編譯可使用既有快取。恢復所有審查用修改後，比對副本全部 tracked blobs，與 `60fa4f0` 完全相同。`:platform:capture:lintDebug`、`:platform:storage:lintDebug` 通過；原工作樹以唯讀方式執行五語系字串檢查，結果為 **0 errors、0 warnings**。

**既有測試全綠不代表下列反例不存在。** 本輪另外在外部副本加入反例或暫時移除保護，再讀取測試結果；結果詳列於各 finding。未在裝置執行 SQLCipher／Room instrumentation、未重跑實際備份密文來回還原，也未以真實來源 App 重現。協調器反例使用真正的協調器與 mocked repositories；不把它們說成真資料庫交易或實機證據。

## Critical — 0.1.4 出貨前必須修正

### C1. 調和邏輯把「停用中的暫停」當成「沒有暫停」，同一次載入就會關掉剛開的缺口

**位置：** `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:425`、`:448`、`:642`、`:657`；`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:25`。

`loadSourcePolicy()` 把 `pausedPackages` 建成 `enabled && paused` 的集合。這是擷取過濾所使用的集合，並不等於來源設定表裡所有 `paused=true` 的來源。新增的 `reconcileSourceGaps()` 卻用 `pkg !in pausedPackages` 判斷暫停缺口與設定矛盾。

**具體操作：**

1. A 原本啟用且未暫停，使用者停用 A，留下開放的停用缺口。
2. 在停用的 A 上按暫停。`setSourcePaused(true)` 的 transaction 正確保存 `paused=true` 並開啟暫停缺口。
3. 同一次呼叫隨後執行 `loadSourcePolicy()`；因 A 仍停用，不在 `pausedPackages`，剛開的暫停缺口立即被 `closeGap()` 關閉。
4. 再啟用 A。`setEnabled` 只改 `enabled`，保留 `paused=true`，並關閉停用缺口；沒有任何地方重開暫停缺口。

最後是 **`enabled=true`、`paused=true`、沒有開放的來源缺口**。`isCapturable()`（`:398`）仍拒絕 A 的通知，健康頁卻把兩段缺口都畫成已結束。重新啟用到真正恢復之間的未擷取期間沒有留下缺口歷史。

這是一般 UI 可達路徑：`feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt:420` 的暫停按鈕在來源停用時仍可按。順序改成「暫停 → 停用 → 再啟用」同樣失敗。對既有 `paused=true` 來源呼叫 `addSource()` 也不會解決，因 `SourceRepository.enable()` 保留該旗標。

**本輪驗證：** 外部副本新增協調器測試，讓健康 repository fake 保留實際開／關的 interval，再執行「停用 → 暫停 → 啟用」。既有 43 案通過，新案失敗：期望 pause gap 的 `endEpochMs=null`，實際已有結束時間。只在副本讓調和判斷看到持久化的 pause 狀態後，44 案全部通過。這證明缺陷在真正的調和邏輯；不是 fake 自己製造期望值。

**修正要求：** 分開「來源設定的 paused」與「目前有效的擷取過濾集合」。若產品選擇停用時結束暫停區間，再啟用一個仍暫停的來源時，就必須原子地重開相應區間。回歸測試需包含上述兩個順序，以及 disabled／paused 四種旗標組合。

### C2. 舊 journal 的確定內容遺失仍被升級重播略過；K6 的「證據不能判斷」不適用於所有舊資料

**位置：** `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:857`、`:872`、`:1037`、`:1052`；`core/model/src/main/kotlin/dev/quietinbox/core/model/NotificationSnapshot.kt:148`；`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:60`。

修復後的新事件在 acceptance transaction 寫缺口；replay 直接進 `processJournaled()`，不再呼叫 `journal()`。這能避免新列重複記錄，卻漏掉正式舊版已接受、尚未寫過缺口的列。

有兩種無須猜測的舊版輸入：

| 正式 v0.1.3 的 payload | 為何可以確定丟過內容 |
| --- | --- |
| 33 個有效 InboxStyle 行被保留成最後 32 行，帶 `LINES` | `v0.1.3:platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:54` 只在原陣列行數超限時設 `LINES`；它不是行內文字截短的旗標。 |
| 65 則短訊息被保留成 64 則，帶 `MESSAGES`，而所有保留文字的 `truncated` 都是 false | 同檔歷史版本 `:151` 的 `bound()` 只有「原列表超過 64」或「保留文字被截短」兩種原因會設該旗標。後者已由逐則證據排除，所以前者確定成立。historic 訊息同理。 |

**具體序列：** v0.1.3 接到上述通知，journal 的 PENDING payload 已落盤，程序在 commit 前死亡；通知之後已不在活動列表，無法靠重新擷取補回。升級至本提交後重播，倖存內容可以正常 commit，但沒有 dropped-content gap。terminal transition 又會清空 payload，原本足以證明遺失的證據就此消失。

`MESSAGES` 在「保留了 64 則、且其中某則也被截短」時，確實不能單靠該旗標確認有沒有額外整則遺失；這不構成放棄上表確定子集的理由。`LINES` 更沒有這種歧義。

`parserInputVersion` 在 v0.1.3 與此提交都是 1，沒有 consumer。對 `LINES` 而言，修復後的 PENDING 列已在 acceptance 記過 gap，舊 PENDING 列尚未記錄，現行資料卻沒有可供這條相容路徑辨認的接受版本／完成標記。單純在所有 replay 再寫一次，會重新引入重複缺口。

**本輪驗證：** 依正式 v0.1.3 producer 契約重建兩份 pending snapshot，分別為 32 行加 `LINES`、64 則完整短訊息加 `MESSAGES`。在外部副本執行真正的協調器 replay；兩案均先通過「確實呼叫 ingest.commit」的斷言，再因 **完全沒有呼叫 dropped-content recordGap** 而失敗。既有 43 案仍通過。這是重播路由驗證，不是宣稱已在 SQLCipher 上安裝兩版 APK 做升級。

**修正要求：** 實作有實際 consumer 的舊 journal 相容處理，保留可確定的整則／整行損失，並讓「此 event 的損失已記錄」具備可驗證的冪等邊界。在 terminal discard、commit 或清 payload 前處理；只有改版本常數、或只改 parser 的 Boolean，都無法完成這件事。

## Important — 建議出貨前修正

### I1. WhatsApp 恰在換行處截斷時，把完整的上一列誤標為內文截短

**位置：** `parsers/apps/src/main/kotlin/dev/quietinbox/parsers/apps/WhatsAppParser.kt:56`、`:83`；`core/model/src/main/kotlin/dev/quietinbox/core/model/NotificationSnapshot.kt:32`。

目前先 split、trim、移除空行，再把整份 body 的截短旗標指派給最後一個 surviving pair。截點若在完整訊息後的換行，最後留下來的訊息根本沒有丟失內文。

可直接重現的輸入：

```kotlin
val prefix =
    "Alice: " + "a".repeat(2037) + "\n" +
    "Bob: " + "b".repeat(2045) + "\n"
// prefix.length == 4096
val raw = prefix + "Carol: missing"
val shape = Fixtures.bigText("Family", raw, bigText = raw)
```

BoundedText 保留完整 Alice、完整 Bob，以及 Bob 後面的換行。WhatsApp parser 產出兩列，卻給出 `[false, true]`：Bob 的 bubble 顯示內文截短，真正消失的是 Carol 整列。這條路徑的 snapshot 只有 body 截短旗標，不會因此產生 dropped-content gap。

**本輪驗證：** 外部副本的 parser 反例先斷言兩列 body 都完整，再斷言 `[false, false]`；後一斷言失敗，實際為 `[false, true]`。原有 43 個 adapter 測試通過。

「只有最後一列可能失去文字」作為上界可以成立，**「整體被截短就代表最後一列一定失去文字」不成立**。應保留截點與行分隔的關係；無法可靠歸屬時，不應把確定完整的一列標成截短。

其他指定邊界已查核：截在最後一行的 body 中段，最後一列標 true 正確；只剩單行時走 StandardParser fallback，仍攜帶 true；最後只剩不可解析的 sender prefix 時，整份 body 回退為單一候選，該整體候選的截短標記成立。

### I2. 只有截短證據改變、保留 body 相同時，Known 路徑把新資訊丟掉

**位置：** `core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Fingerprint.kt:13`；`core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt:149`、`:174`；`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:351`、`:366`。

第一次收到恰好 4096 字的完整文字；同一 notification stream、sender、source timestamp 的後續觀測追加了文字，又被截成完全相同的 4096 字 prefix。第二次 candidate 的 `textTruncated=true`，但 fingerprint 不含此欄位，正常的視窗比對判為 `Known(REPOST)`。

Known 分支既不更新 `truncationFlags`，也不保存這次 REPOST 的逐則截短證據；只有 New／AmbiguousRepeat 與 Revision 分支寫該欄位。結果是這份新觀測已知被截短，訊息仍維持原來的未截短標記。反向亦同：先截短、後續完整文字恰等於原 prefix 時，原標記不會清除。

**本輪驗證：** 在外部副本執行真正的 Reconciler，使用不帶 sourceMessageId 的兩個 candidate，只改 `textTruncated`；第二次確實得到 `Known(REPOST)`。未在真資料庫執行此案例；Known 分支不寫欄位的結論來自上述正式實作。顯示端為 `Mappers.kt:68` → `ConversationScreen.kt:505`。

`applyRevision` 的 SQL 修正本身成立，但它碰不到這條路徑。目前正式 parser 都不宣稱 sourceMessageId；Reconciler 的 Revision 又需要已知 source id 與 body 變化，因此不能拿該分支的更新代表 live path 已完整涵蓋。

**修正要求：** 明確定義同文但截短證據不同時的合併規則，更新或另存新的逐則證據。若選擇保留先前較完整的副本，也須保留後續觀測的損失資訊；不要僅把 Boolean 加進 fingerprint，造成同一訊息多生一列。

### I3. acceptance callback 與 UNKNOWN fallback 連續寫入失敗後，仍沒有待補記義務

**位置：** `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:113`；`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:873`、`:876`、`:890`、`:900`、`:1067`。

讓新 dropped-content gap 的寫入因一般資料庫例外失敗，例如持續的儲存空間不足。acceptance transaction 正確 rollback，例外向上傳遞，此時 `journaled` 仍為 false。coordinator 進入 JOURNAL_FAILED 分支並嘗試 source-scoped UNKNOWN gap。

若資料庫仍無法寫 gap，第二次 `health.recordGap()` 又拋例外，`guarded` 就吞掉它；這條一般例外路徑沒有保存 `vaultGapSince` 或其他待補寫義務。後續即使儲存恢復，也沒有 PENDING event 可重播，沒有待補記 interval，倖存訊息與損失紀錄都不存在。

`IngestRepository.diagnostic()` 自己會吞掉非取消例外；它即使成功寫入 JOURNAL_FAILED，也不是 gap interval。這不是「diagnostic 拋例外擋住下一行」的問題，而是 fallback 本身也失敗後沒有恢復途徑。

單次暫態故障時，新的 UNKNOWN fallback 確實改善結果；對持續故障，提交訊息所稱「Less precise, never absent」仍超出實作。這條雙次失敗序列為原始碼追蹤，**本輪未做實際磁碟滿或真庫故障注入**。

**修正要求：** 非 VaultUnavailable 的接受失敗也應保留有界的待補記損失，寫入成功後才清除；測試須讓 acceptance gap 與 fallback 都先失敗，再恢復 repository 並觸發下一次載入。

### I4. exactly-once 的正式 guard 沒有被新增測試控制；部分 transaction 測試名稱也超過斷言範圍

**位置：** `platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:156`、`:1087`、`:1109`；`platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/SourcePolicyTransactionTest.kt:124`、`:143`。

duplicate-event 測試沒有使用真正的 IngestRepository。它自行用 `seen.add(eventId)` 製造 acceptance 結果，再由 harness 的 `if (accepted)` 執行 gap lambda。正式程式的 guard 被移除，fake 的 guard 還在。

**本輪已實際執行的 mutation：** 在外部副本把正式 `IngestRepository.kt:115` 的

```kotlin
if (accepted) lossOnAccept?.invoke()
```

改成無條件 `lossOnAccept?.invoke()`，重新執行 **43 個 capture 測試，全部通過**。因此 f5f9581 所稱 duplicate test「controls the if (accepted) guard」對正式 repository 並不成立。刪掉正式 callback 或拆掉它的 transaction，也不會讓這兩個 mock 測試證明真正的資料庫保證。

replay test 仍有價值：它確保實際協調器的 replay 不再呼叫 gap site；把該 site 放回 `processJournaled` 會被其零次呼叫斷言抓到。但它沒有建立、查詢或 rollback 真實 acceptance transaction。

cluster b 的真庫測試確實補到了 `setFlag` 的 transition guard 與 rollback；其 remove 測試卻只驗證成功後 source 不存在、open gap 不存在，callback 移到 transaction 外仍符合斷言。forget 測試直接呼叫 `health.forgetGapSource()`，沒有執行 `remove(deleteData=true)`，因此不控制整合位置或刪除失敗時的 rollback。

**修正要求：** 補真正 repository 的首次接受、PK conflict、callback 失敗兩列一起 rollback；補 removal 的 close／forget 失敗時 source 與 deletion graph 不得半提交。不要以同值 fake 的成功當成 SQL 交易證據。以下逐案表另外列出所有新增測試的有效範圍。

## Minor — 文件與敘述尚未同步

### M1. CHANGELOG 仍留下已被修正註解否定的備份承諾，部分絕對敘述仍不成立

- `CHANGELOG.md:194` 仍寫新欄位使「older reader ignores it」。正式 v0.1.3 的 `BackupStager.kt:61` 先以 schema 4 > VERSION 3 拒絕 archive，得到 UNSUPPORTED_VERSION，尚未解碼 Message。`BackupRecords.kt:77` 的新註解已正確限制成新版讀舊檔，CHANGELOG 卻沒有同步。這是相容性文字錯誤，未發現此拒絕路徑會破壞舊金庫。
- `CHANGELOG.md:65`、`:69` 的「nothing downstream decides anything」「It is not read」過度：`StandardParser.kt:38` 仍依 `shape.truncated.isNotEmpty()` 加 `TRUNCATED_INPUT`，`CaptureCoordinator.kt:994` 會保存 PARSE_WARNINGS 診斷。沒有找到它改變訊息 Boolean、fingerprint 或 reconcile quality 的路徑；這個泛用診斷本身合理，應把宣稱縮到逐則文字判定，而非所有 outcome。真正升級漏 gap 見 C2。
- `CHANGELOG.md:99` 所說 add 路徑「opens and closes nothing」忽略 `changeSourcePolicy → loadSourcePolicy → reconcileSourceGaps`。正常重加／對既有來源啟用，確實可能關閉舊 gap；只是 close 不在 `SourceRepository.enable()` 的 upsert transaction 內。不能據此宣稱所有 add／enable 都有同一個原子邊界。
- `CHANGELOG.md:104` 的「七個記錄點、五個 process-wide」仍對不上此提交：協調器有 13 個 `health.openGap/recordGap` 呼叫位置，其中 5 個傳 packageName（兩個來源開關、queue overflow、acceptance loss、journal failure），8 個沒有傳。可以依原因把多個呼叫合併計算，但文件沒有定義這種分組。acceptance 當下尚未執行 identity resolution 這個新論述成立；`QuietInboxDatabase.kt:103` 與 `CaptureHealth.kt:55` 還保留舊的「ingest 沒發生」論述。

另，f5f9581 提交訊息的「lambda throws，journal() returns false」不精確：false 只代表 `INSERT IGNORE` 回傳 -1；lambda 例外會 rollback 並向上拋出。UNKNOWN 是 coordinator catch 後的補救，不是 false 的處理分支。

### M2. 兩語文件的數量與涵蓋描述只更新了一部分

| 文件位置 | 此提交的狀態 |
| --- | --- |
| `docs/TEST_MATRIX.md:11`、`docs/zh-Hant/TEST_MATRIX.md:11` | parser 13、所列純 core 合計 79、apps parser 43 正確。 |
| `docs/TEST_MATRIX.md:18` | storage instrumented 23、SourcePolicyTransactionTest 6 正確，指原始碼宣告數；本輪未執行這 23 案。 |
| `docs/zh-Hant/TEST_MATRIX.md:18` | 仍列 17，完全沒列新增的 SourcePolicyTransactionTest 6 案，與英文版不一致。 |
| 兩份 TEST_MATRIX 的 capture 列 | 43 正確；但仍以空 parser fixture 描述「ingest 成功」。`CaptureCoordinatorTest.kt:107` 的 fixture 沒有 body，實際走 SKIPPED，沒有驗證訊息 commit。 |
| `docs/SCOPE.md:16`、`docs/zh-Hant/SCOPE.md:14` | parser 13 已更新。 |
| `docs/SCOPE.md:20`、`docs/zh-Hant/SCOPE.md:18` | CaptureCoordinatorTest 仍列 32，實際 43。 |
| `docs/SCOPE.md:19`、`docs/zh-Hant/SCOPE.md:17` | MigrationTest 只列 1→2、2→3，漏掉已存在的 3→4 案。 |
| `docs/ARCHITECTURE.md:69`、`docs/zh-Hant/ARCHITECTURE.md:62` | schema v4 已正確同步。 |

英文 TEST_MATRIX 的 capture table row 另被拆到 `:25`–`:28`，續行缺少完整欄位分隔；應恢復為正常 Markdown 表格列。`Entities.kt:154` 等仍描述 snapshot-wide flag 語意的註解也應隨逐則 Boolean 一併更新。

## Claims checked and found true — 已查核成立的主張與證據界線

### 四個提交

| 提交 | 本輪確認成立的部分 | 方法與限制 |
| --- | --- | --- |
| `f5f9581` | 新事件的 journal insert 與 loss callback 在同一個 Room `withTransaction`；只有 insert 非 -1 才執行 callback；新事件一旦接受，後續 commit、skip、pause 或 source discard 不需再寫同一損失。 | `IngestRepository.kt:102`–`:117`、`JournalDao.insert` 與完整 live／replay 呼叫鏈。結構成立；真庫故障與 PK 控制尚未由本提交測試證明，見 I3／I4；舊事件見 C2。 |
| `a258662` | `setFlag` 在交易內讀取現值，只有真實 transition 才修改旗標與執行 gap callback；remove 的 close／forget 確實位於 removal transaction。 | `SourceRepository.kt:59`–`:72`、`:97` 起與 coordinator callback；正常交易結構及 scope 成立。調和的雙旗標組合失敗，見 C1。 |
| `234e5cd` | StandardParser 三處由真正 body 的 BoundedText 取逐則資訊；new row 只存 `TEXT` 或 null；Revision SQL 與呼叫端都寫新值；未知非空 column token 仍顯示通用截短標記。 | `StandardParser.kt:153`、`:190`、`:225`；`IngestRepository.kt:44`、`:332`、`:366`；`Daos.kt:263`；`Mappers.kt:68`。已實跑 parser mutation；WhatsApp 邊界、Known 路徑與舊 journal 不在這項成功保證內。 |
| `60fa4f0` | retention 的 SQL 只刪除已結束且早於 cutoff 的 gap；LINES 已加入新 acceptance 的 dropped-content flags；BackupRecords 註解正確說明拒絕向舊 schema 還原；兩份 ARCHITECTURE 都是 v4。 | `Daos.kt:512`、`CaptureCoordinator.kt:1089`、`BackupRecords.kt:77`；對照正式 v0.1.3 stager、目前版本常數與文件。retention 使用相同 SQL 的記憶體 SQLite 檢查及真庫測試原始碼審核；未重新跑 instrumentation。 |

四個提交的 JVM 原始碼宣告數分別為 **238、242、245、246**，與提交訊息一致；本輪實際全量執行的是最後的 **246**，沒有宣稱重新執行過前三個歷史提交。storage instrumented 的宣告數最後為 23；backup 2、crypto 2 的宣告存在，這些裝置測試未於本輪執行。作者聲稱過去已執行的 mutation／模擬器控制，若本輪沒有重跑，僅能確認測試的控制結構，不能證明歷史執行紀錄。

### journal 接受、失敗與重播路徑

| 路徑 | 查核結果 |
| --- | --- |
| 正式 live／active-resync／synthetic 事件 | 最後進同一個 `process()`；policy／generation 等 admission fence 先檢查，鎖內再次檢查，再到唯一的正式 `ingest.journal(..., lossOnAccept)` 呼叫。 |
| 同一 eventId 再次送入、原 journal row 尚存在 | PK + IGNORE 回 -1，callback 不執行，不會再加同一 acceptance gap；這是 repository 結構結論。 |
| lambda 拋一般例外 | Room rollback 並重拋；coordinator 嘗試 UNKNOWN。兩次 gap write 都失敗沒有待補義務，見 I3。 |
| lambda／journal 遇 VaultUnavailableException | 進專門 lock-out 路徑；寫不了 interval 時保存 `vaultGapSince`，Ready 時補 bounded UNKNOWN，成功後才清除（`CaptureCoordinator.kt:250`、`:876`）。 |
| 取消 | CancellationException 往上傳，不當成普通成功；未完成的 Room transaction rollback。不能把程序記憶體內、尚未 durable 的事件說成可跨 process death 恢復。 |
| 已由新版接受的 PENDING replay | `replayJournal()` 不呼叫 journal，不重開 acceptance gap；成功／terminal discard 都不會刪掉該 interval。 |
| 舊版 PENDING replay | 沒有 acceptance gap 可繼承；本提交沒有相容補記，見 C2。 |
| TTL | 一般 `JournalDao.deleteExpired()` 保留 PENDING；terminal row 過期刪除後，該 eventId 的 PK 去重記憶也消失。正式 factory 每次產生 UUID，未找到它在 TTL 後重送同一 eventId 的生產路徑；不把這個理論界線另列成產品缺陷。 |

### 來源狀態、add／remove 與 scope

以下 D 表示停用 gap、P 表示暫停 gap；均指 package 相同的來源。

| 操作／順序 | 此提交的實際結果 |
| --- | --- |
| 啟用來源：disable → enable | D 開啟再關閉；同值重送不新增 interval。 |
| 啟用來源：pause → resume | P 開啟再關閉；同值重送不新增 interval。 |
| pause → disable → enable，或 disable → pause → enable | P 被調和提早關掉；最後仍 paused、卻無開放缺口，C1。 |
| disabled + paused 時 resume，再 enable | resume 後 policy 的 paused=false，最後可以擷取；但此前已被提早關掉的 P 歷史不會補回。 |
| add 新來源 | 建立 enabled=true、paused=false；沒有需要結束的既有本人政策區間。 |
| add 既有來源 | 保留 paused 等設定並設 enabled=true；後續 load 可能關 D，不能說 add 完全不碰 gap。upsert 與這個調和 close 沒有共用 transaction；若保留 paused=true，還涉及 C1。 |
| remove，deleteData=false | removal transaction 內關 D／P，保留 interval 與 packageName；重新加入時無懸空的舊 D／P。 |
| remove，deleteData=true | 同上，再把該 package 的所有 gap 名稱設 null；保留 interval，包括非政策的既有 loss gap。 |
| 移除 A 時，B 或 process-wide gap | `closeOpenGapsForSource` 以 package equality 篩選；`forgetGapSource` 的 SQL 也是 exact package match。B 與原本為 null 的 interval 不會被這兩個操作關閉或匿名化。 |

來源隔離與保留／匿名化結果已另用記憶體 SQLite 套相同查詢核對。調和仍會按 B 自己的政策關閉 B 的矛盾舊列；這是每次 policy load 的全域調和效果，與「移除 A 的 SQL 誤改 B」不同。null 的 process-wide source gap 在調和中也明確跳過。

### 逐則 Boolean 與 adapter

MessagingStyle 使用各自的 `m.text.truncated`，包括 historic；InboxStyle 使用各自的 `line.truncated`；single-body 使用實際挑出的 `bounded.truncated`。其餘 adapter 的 `copy` 後處理會保留新增欄位，自行建立多個候選的 WhatsApp 則有 I1。

目前 domain 的消費端只做「顯示或不顯示截短標籤」，因此對本提交新寫入的 `TEXT`／null 列，Boolean 沒有丟掉任何目前使用的分類資訊。原始 column／backup 仍保留 String，純未知非空 token 不再被 mapper 丟成「沒有截短」。早期未發行 schema-4 開發資料若已存入 snapshot-wide 值，則不會被本提交自動轉換；這與 C2 的正式 schema-3 升級缺陷應分開看待，未將它冒充已發布版本的 migration regression。

### 16 個新增測試逐案核對

下表的「靜態控制」表示已閱讀正式實作與測試，判斷移除指定行為是否會破壞斷言；只有註明本輪實跑的項目，才表示實際執行過 mutant。所有列出的 JVM 原案均包含在本輪 246 個綠燈內。

| 提交／測試位置 | 能控制的行為 | 沒有證明的範圍 |
| --- | --- | --- |
| f5，`CaptureCoordinatorTest.kt:1087`，重複 event | 協調器交付 callback 的方式與 fake 去重後的可觀察結果。 | 正式 repository guard、交易與 PK；移除正式 guard，本輪實跑仍全綠。 |
| f5，`:1109`，PENDING replay | 真正 replay 路徑不再寫 dropped gap；把 site 放回此路徑會破壞零次呼叫斷言。 | 首次 gap 曾落盤、接受 transaction、舊 payload 相容。 |
| a258，`:962`，同值來源旗標 | 協調器把 gap 動作交給 transition callback；fake 可避免第二次 gap。 | 真 SourceRepository 的 idempotence；由下列真庫案補。 |
| a258，`:988`，移除並刪資料 | 協調器呼叫該來源 close 與 forget。 | SQL transaction、實際 final rows、failure rollback。 |
| a258，`:1016`，移除但留資料 | 協調器呼叫 close、不呼叫 forget。 | 真庫 interval 是否保留及其 package／end 值。 |
| a258，`:1032`，舊矛盾 gap | 真正調和會關閉 fixture 中 enabled source 的 stale disabled gap；刪掉調和便無 close 呼叫。 | disabled+paused 的組合、完整操作順序；因此未抓 C1。 |
| a258，`SourcePolicyTransactionTest.kt:75`，disable 共存 | 真庫 happy path 同時有新旗標與 gap。 | 單獨拿掉 transaction 仍可能綠，不能單靠此案證原子性。 |
| a258，`:90`，重複旗標不寫 | 真庫的 transition guard；拿掉 unchanged check，回傳值與 gap 數都不符。 | 未重跑裝置 mutant；也沒有獨立 pause setter 的同型案。 |
| a258，`:108`，gap 失敗不提交旗標 | 真庫的 `setFlag` rollback；拿掉 transaction，旗標已改成 false，斷言會失敗。 | 本輪未跑 instrumentation；未涵蓋 removal 的獨立交易。 |
| a258，`:124`，remove 關 gap | removal 確實呼叫 close，之後沒有 open gap。 | close 移到 transaction 外仍可綠，名稱中的 SameTransaction 未被控制。 |
| a258，`:143`，forget 留 interval | 真 HealthDao UPDATE 保留 gap 並去掉名稱。 | 完全沒有呼叫 remove(deleteData=true)，不證明其整合或 rollback。 |
| 234，`StandardParserTest.kt:21`，只有實際截短者標記 | 逐則 parser wiring。 | factory 的界限擷取、storage、其他 template；本輪改成 snapshot-wide Boolean 後變紅。 |
| 234，`:36`，全部完整不標記 | title／notification 旗標不得污染訊息 Boolean。 | 真 title 截取 producer、DAO；本輪同一 mutant 變紅。 |
| 234，`:52`，舊 MESSAGES serializer round-trip | enum 名稱可往返，兩個明示 BoundedText 的逐則結果不被彙總旗標控制；本輪 mutant 也變紅。 | 使用當前 serializer，沒有真正的升級 replay、gap 或 warning outcome 斷言，未抓 C2。 |
| 60fa，`CaptureCoordinatorTest.kt:1138`，LINES gap | 協調器把 LINES 放進 dropped-content 判斷；移除集合中的 LINES，gap 呼叫不會發生。 | Fixture 直接注入旗標，不經 SnapshotFactory；也沒有測倖存列的文字標記。 |
| 60fa，`SourcePolicyTransactionTest.kt:161`，retention | 同時驗舊 open 保留、舊 closed 刪除；恢復舊的無 end 條件 DELETE，open 斷言會失敗；把刪除整段拿掉，總數斷言也失敗。 | 本輪未重新執行真庫 mutant，亦非 UI／長時間 retention 實機測試。 |

### schema、備份與可追溯驗證

這四個提交沒有改動 schema JSON、entity 結構或 `MIGRATION_3_4`；仍為兩個 additive nullable columns，沒有新增 destructive migration。新的 gap 與逐則值在既有 schema 4 的欄位中表達。這項結構核對不等於本輪重新執行了 Room 的 migration validation。

備份方向核對如下：新版 reader 可接受 schema 3，舊 Message 缺少 `truncationFlags` 時預設 null；正式 v0.1.3 reader 則在 manifest 拒絕 schema 4。新註解與前者／拒絕行為一致，CHANGELOG 的反向敘述仍需修正。未把 JSON 的 `ignoreUnknownKeys` 當成整份 archive 的雙向相容保證。

本輪執行紀錄保留在儲存庫外：

- 完整 JVM：`/tmp/quietinbox-round34-all-jvm.log`、`/tmp/quietinbox-round34-all-jvm-counts.json`。
- lint：`/tmp/quietinbox-round34-lint.log`。
- 正式 journal guard 移除後仍綠：`/tmp/quietinbox-round34-unguarded-journal.log`。
- 來源反例與只改調和判斷後的控制：`/tmp/quietinbox-round34-policy-failure.xml`、`/tmp/quietinbox-round34-policy-control-pass.xml`。
- WhatsApp 換行反例：`/tmp/quietinbox-round34-whatsapp-failure.xml`。
- 舊 LINES／MESSAGES replay：`/tmp/quietinbox-round34-legacy-failure.xml`。
- parser snapshot-wide mutation：`/tmp/quietinbox-round34-parser-mutation.xml`。

上述暫存證據不是 repository 內的交付檔，也不保證永久保留；本報告已包含觸發輸入、實際結果、行號與證據界線，可據此重建驗證。審查結論固定針對 `60fa4f0`，不因後續另有尚未審查的提交或工作樹修正而改為通過。
