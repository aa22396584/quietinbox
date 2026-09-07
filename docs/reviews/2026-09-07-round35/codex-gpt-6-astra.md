# QuietInbox Round 35 唯讀審查

## Verdict: REQUEST CHANGES

審查範圍為 `022b99a3e2237c8b1ec374d4bb191fd06253fa53..f7a09edce9826f7efa10dcf554677f067e4e6fa6`。以下行號均以 **`f7a09ed`** 為準，舊 producer 的證據則明確標為 `v0.1.3`。

Round 34 的 acceptance transaction 修正確實保留，新增加的 conditional claim 也具備正確的交易結構。但本次把補記放進 replay 後，補記失敗會消耗既有 commit retry budget，最終可能在沒有任何 gap 的情況下清空唯一 payload。這是本輪必須阻擋的缺陷。此外，換行邊界的整列遺失、備份合併的截短證據合併，以及 deferred loss 的恢復觸發仍未完整處理。

本次依 BRIEF 先讀 Round 34 報告，再讀指定差異、相關正式程式與測試、`v0.1.3` producer、schema，以及 Room／SQLite 官方契約。未啟用 workflow mode，未執行 Gradle、Android instrumented test、模擬器或真機操作。實際執行的驗證限於唯讀 Git 查詢與 Python 標準函式庫的記憶體內運算／SQLite 模型；沒有建立額外測試檔或資料庫檔。

審查期間共享工作樹有其他工作推進 HEAD、修改程式和文件。本報告不採用那些後續修正，也不把它們當作本輪通過證據。唯一由本審查寫入的檔案是本報告。

## Critical — push 前必須修正

### C1. 補記 gap 的失敗會耗盡 replay 重試額度，將未結算的舊事件改成 FAILED 並清空 payload

**位置：**

- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1105`：新增的 `recordCarriedOverLoss(replay)` 與原有 `processJournaled` 共用同一個 catch；第 1109 行對兩種失敗都呼叫 `markJournalRetryable`。
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:133`：claim 與 gap 的交易；第 177–180 行在到達 `MAX_ATTEMPTS = 3` 時改為 `FAILED`。
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:60`：任何非 `PENDING` 的 `setState` 都把 payload 改成空字串；第 80 行又禁止非 `PENDING` 的 row 取得 claim。

**可達流程：** 一個可解碼、predicate 可判定已丟內容的 v0.1.3 pending row，`lossRecorded=0`。補記的 gap 寫入拋例外，claim transaction 正確回滾。可是外層隨後成功更新 retry state。三次這種失敗之後，row 成為 `FAILED`、payload 清空、`lossRecorded` 仍為 0，gap 數仍為 0。若舊 row 已有兩次失敗紀錄，升級後只需一次補記失敗便會發生。

這不要求「所有 SQLite statement 都永遠失敗」。缺陷條件是 gap 寫入失敗而後續 journal state 更新成功；例如兩者的配置／I/O 需求不同，或前者遇到暫時性錯誤。不能因為在完全不可寫的資料庫上兩者可能一起失敗，就排除這個分支。

**本次驗證：** 從 `f7a09ed` 抽取實際的 `claimLoss`、`setState` SQL，使用 exported schema 在 SQLite `:memory:` 建表，以 TEMP trigger 僅讓 gap INSERT 拋錯，依 production retry 判斷執行交易外的狀態更新。結果如下：

| 初始 attempts | 補記失敗次數 | state | attempts | payload 長度 | lossRecorded | gap 數 |
|---|---:|---|---:|---:|---:|---:|
| 0 | 1 | PENDING | 1 | 18 | 0 | 0 |
| 0 | 2 | PENDING | 2 | 18 | 0 | 0 |
| 0 | 3 | FAILED | 3 | 0 | 0 | 0 |
| 2 | 1 | FAILED | 3 | 0 | 0 | 0 |

移除故障後，兩個 FAILED case 的 claim affected rows 都是 **0**，仍無 gap。成功對照組則連續得到 `[1, 0, 0]`，gap 數為 **1**。

此模型將 payload 視為不透明字串，證明的是正式 SQL 與 retry 邏輯的組合；可解碼舊 snapshot 進入這段 catch 的可達性由呼叫順序及舊 producer 的檢查支持。本次沒有宣稱重現實際 SQLCipher 的磁碟滿載或 Android replay 整合測試。

`JournalLossTransactionTest.kt:124` 只測直接 claim 失敗後再次直接 claim 可以成功；它沒有經過 `markJournalRetryable`，因此抓不到這個組合缺陷。

**修正要求：** 把「尚未持久化的 loss 結算失敗」與「內容 commit 的重試額度」分開處理。只要該 loss 還沒落盤，就不能同時讓 claim 永久不可取得並刪除唯一證據；若必須終止內容重試，應先持久化足以保留損失事實的紀錄。補上真 repository／coordinator 組合測試，涵蓋初始 attempts 為 0 與 2、gap 失敗但 state update 成功、恢復後恰好一筆 gap。

## Important — push 前應修正

### I1. WhatsApp 不再誤標完整的 Bob，但整列消失的 Carol 沒有對應 loss gap

**位置：** `parsers/apps/src/main/kotlin/dev/quietinbox/parsers/apps/WhatsAppParser.kt:69`、`:75`、`:88`；`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:47`、`:54`、`:113`；`CaptureCoordinator.kt:901`、`:1174`。

新增測試本身就提供具體反例，見 `parsers/apps/src/test/kotlin/dev/quietinbox/parsers/apps/WhatsAppParserTest.kt:95`：

```kotlin
val prefix = "Alice: " + "a".repeat(2037) + "\n" +
    "Bob: " + "b".repeat(2045) + "\n" // 恰好 4096 字元
val raw = prefix + "Carol: gone"
```

`raw` 有 4107 字元。bound 後留下兩個完整的 sender row 與最後換行，Carol 整列消失。新 parser 對 Alice／Bob 回傳 `[false, false]` 是正確的每列判定，但 BigText 的 snapshot 只帶 `TEXT`／`BIG_TEXT`，不會因為這種字串內的換行切割產生 `LINES_DROPPED`；後者只來自 `EXTRA_TEXT_LINES` 陣列超量。acceptance 又只把三個 `*_DROPPED` flag 轉成 gap。

結果是 Carol 沒有被保存，也沒有 `MESSAGES_DROPPED` gap；兩個倖存 bubble 都沒有截短標記。`IngestRepository.kt:364` 保存的是各 candidate 的 flag，UI 在 `feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt:505` 依該 flag 顯示標籤。

**界線：** 通用 `TRUNCATED_INPUT`／`PARSE_WARNINGS` 診斷仍然存在，因此不能說所有證據都消失。問題是已知整列遺失只有通用診斷，沒有與該損失對應的 gap 或訊息品質呈現。這次修正移除了原本錯誤落在 Bob 上的標記，卻未補上正確的 aggregate loss 表達。

**驗證：** 靜態追蹤 factory → parser → acceptance → storage → UI，並在記憶體中重算 4107／4096 字元及兩列皆 false 的結果。未跑實際 Kotlin parser 或 Android UI。

**修正要求：** 保留 Bob 完整的判定，同時傳遞「已丟棄整列」的獨立證據並持久化。回歸測試應同時檢查倖存列未被誤標，以及遺失列仍有可見 loss 紀錄，不能只斷言兩個 false。

### I2. 正常的多次備份合併會丟掉 Known 分支新增的截短證據

**位置：** `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:383`、`:394`；`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:168`、`:183`、`:330`、`:344`、`:348`。

不必修改備份檔即可重現以下資料流程：

1. 保存一筆未標記截短的訊息，匯出備份 B1。
2. 收到同一則訊息的相同 bounded body，這次 `textTruncated=true`；新 Known 分支把原 row 的 `truncationFlags` 設成 `TEXT`，匯出 B2。
3. 在乾淨 vault 先匯入 B1，再合併匯入 B2。

Known 更新沒有改變原 row 的 `fingerprint`、`sortKey`、`observedAtEpochMs`。B2 雖然正確匯出了新 flag，import 仍以這三欄組成 duplicate key，命中 B1 後直接 `continue`，沒有合併 metadata。最終只有未標記的舊 row，B2 已保存的截短證據不見，使用者看到的 bubble 又像沒有被本程式截短。

**驗證：** 逐欄追蹤 production export/import 及 Known UPDATE，並在記憶體中確認更新前後 duplicate key 相同。這是 source 與資料模型驗證，未執行加解密備份 round trip。

**修正要求：** duplicate occurrence 命中時也合併既有 row 缺少的品質證據，保留目前的重複訊息 multiplicity 規則。加入 B1→B2 與 B2→B1 的自然備份序列測試，避免 import 順序決定截短證據是否存在。

### I3. deferred journal loss 的補記只靠 policy reload，正常恢復寫入不會觸發

**位置：** `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:467`、`:505`、`:513`、`:888`、`:916`、`:939`；`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SourceRepository.kt:19`。

在 vault 已 Ready、`sourcesLoaded=true` 的情況，journal 與 fallback gap 都失敗，`journalLossSince` 只保留在記憶體。之後資料庫恢復可寫，來源設定沒有變動、vault state 也沒有切換時，後續正常事件可以成功 journal／commit，但不會執行 `settleUnrecordedJournalLoss`。

該方法唯一的呼叫點在 `settleColdStartGap`，而這又只能由 `loadSourcePolicy` 進入。正常 capture 只在 `!sourcesLoaded` 時重新載入；source observer 觀察的是 source configuration，一般 message/journal 寫入不會使來源清單重新 emit。於是已有可寫機會，舊 loss 仍可長期只留在 RAM，最後隨程序結束而消失。

**驗證：** 靜態查找全部 settle 呼叫點及 observer 來源。`platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:1415` 的新增測試在第 1435 行主動 emit source list，證明的是「有下一次 policy load」的分支，沒有證明「不改設定而恢復寫入」的分支。

**修正要求：** 在 pipeline mutex 保護下，利用正常可寫機會補記，或提供不依賴來源設定變動的有限重試觸發；仍須寫入成功後才清除記憶。測試必須維持 source list、Ready state 不變，只讓寫入失敗後恢復。

若 `sources.sources()` 本身一直失敗，控制流在第 468 行就離開，settle 不可達；若 vault 永不重開，也不可能承諾寫入。RAM 記憶無法提供跨 process death 的持久保證，這個限制應直接寫進契約。第 7 個 hard check 的 outage 形狀與例外分流另見下文。

### I4. set-only 適合保留「曾截短」證據，但不能直接當成目前版本的完整性

**位置：** `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:282`、`:287`；`IngestRepository.kt:394`；`core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt:149`、`:174`；`core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Fingerprint.kt:13`。

反例是同一 notification／同一可識別訊息的三次觀察，sender、source timestamp 等對齊欄位不變：

| 來源這次提供的文字 | 保存的 bounded body | 本次 textTruncated | Known 更新後 |
|---|---|---|---|
| 恰好 4096 字元的 S | S | false | 未標記 |
| 編輯成 S + 額外文字 | S | true | TEXT |
| 再編輯回 S | S | false | 仍為 TEXT |

第三次的通知文字完整落在上限內，但儲存值一直是 S，fingerprint 也不含截短資訊，所以同一 post 的 overlap 或同 source id／同 body 仍走 Known，不會建立 Revision。使用者仍看到目前 bubble 的截短標記，沒有新 revision 能解釋它代表的是上一個來源版本。

**必要的反證控制：** 這不表示所有 edit 都不能清旗標。若可識別的訊息 body 實際變成另一個值，`Reconciler.kt:175` 會產生 Revision，`IngestRepository.kt:406`／`Daos.kt:278` 會替換 flag，包括改回 null。缺口是「不同來源版本得到相同 bounded body」的情況。

**驗證與限制：** 依 fingerprint、Known／Revision 分支及 UPDATE 推導；未宣稱某一版 WhatsApp 已實際送出此編輯序列。「完整」僅指這次通知文字未再被本程式截短，不代表通知提供了聊天服務的完整歷史。

**修正要求：** 明確區分「曾有觀察遭截短」與「目前所呈現版本的截短狀態」。若採歷史證據模型，標籤／說明應表達此語意；若採目前版本模型，需有版本／順序證據後再更新狀態。不能直接把任何 false replay 都當成清除依據，否則舊 replay 會抹掉較新的真實 loss。

### I5. decode failure 是繞過兩個 consumer 的第三種自動 PENDING exit

**位置：** `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:150`、`:157`、`:163`；`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:62`；`CaptureCoordinator.kt:1124`。

`pendingJournal()` 在 decode 失敗時直接 `setState(..., "FAILED", "DECODE")`，隨即清除 payload；row 還沒到 `recordCarriedOverLoss(replay)`。來源停用／移除路徑則使用 `mapNotNull` 跳過不可解碼 row，後續仍會將它 discard。兩者都不會因為這個 accepted event 的處理失敗而先保存 UNKNOWN loss。

**驗證：** 追蹤讀取方法中的例外分支與 `setState`／`discardPending` SQL；並全域搜尋 terminal mutation 與刪除呼叫。這直接否定「只有 replay 與 source discard 兩種出口，而且兩者 payload 都可讀」的無條件敘述。

**歸因界線：** 這個 decode 分支早已存在，`CHANGELOG.md:94` 也明示不可解碼 payload 不補記。沒有證據顯示正常 v0.1.3 factory payload 會因本輪 schema 變更而突然無法解碼，因此不把它誤報成新的 migration regression，也不與 C1 的可解碼事件重複計為新 Critical。

但無法判斷「丟了整列還是截短文字」，不等於不知道一個已接受的事件無法處理。應在 terminal clear 前保存合適的 UNKNOWN／decode-failure loss 事實，或把所承諾的保證明確縮小至可解碼 payload。後文列出其餘出口的逐項結果。

### I6. Round 34 I4 已有實質補強，但 remove 的 rollback 仍缺失敗控制

**位置：** `platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/JournalLossTransactionTest.kt:81`；`platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/SourcePolicyTransactionTest.kt:164`、`:186`、`:246`；`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SourceRepository.kt:97`。

現在不能再沿用 Round 34「只有 coordinator fake」的評語。真 `IngestRepository`／SQLCipher 測試已涵蓋 acceptance、重送、acceptance callback rollback、claim once、claim callback rollback、already-accounted 與 terminal row 共 7 個 case；disable 也有 nested claim 失敗後 source／pending／claim 一起回滾的測試。

不過 remove 的兩個測試仍是成功路徑：`deleteData=false` 關閉 gap；`deleteData=true` 關閉並匿名化來源 gap、保留另一來源。它們沒有在 claim／close／forget 或後續刪除圖執行途中注入失敗。若日後把 callback 移到交易外，這些 happy-path assertion 仍可全部成立。

**驗證：** 閱讀測試 setup、受測呼叫與 assertion，而非依測試名稱推定覆蓋。未跑 instrumented tests，也沒有觀察到目前 production 已發生半套 remove。

**修正要求：** 對兩種 remove 分支加入真庫 failure rollback 控制，檢查 source、checkpoint、journal payload／claim、gap 關閉／匿名化及相關資料圖全數回到原狀；另補 C1 的 retry 組合測試。現有測試支持交易原語，尚不足以支持「每條完整退出路徑都已受回歸保護」。

## Minor / nitpicks

### M1. 文件的靜態測試數量與 coverage 聲明仍有六個錯誤位置

| 文件位置 | 目前寫法 | `f7a09ed` 實際結果 |
|---|---|---|
| `docs/TEST_MATRIX.md:16`；`docs/zh-Hant/TEST_MATRIX.md:16` | MonogramTest 4 | **6**，`core/designsystem/src/test/kotlin/dev/quietinbox/core/designsystem/components/MonogramTest.kt:9` 起共有 6 個 `@Test`；另 TimeFormat 2，所以 design system 合計 **8** |
| `docs/TEST_MATRIX.md:20`；`docs/zh-Hant/TEST_MATRIX.md:20` | MessageBubbleSemanticsTest 2 | **3**，`feature/conversation/src/androidTest/kotlin/dev/quietinbox/feature/conversation/MessageBubbleSemanticsTest.kt:70`、`:79`、`:86` |
| `docs/TEST_MATRIX.md:102`；`docs/zh-Hant/TEST_MATRIX.md:87` | 除 Analytics 外 feature ViewModel 沒有 JVM 測試 | 已有 Search **5**、Conversation **1**、Onboarding **5**；同頁第 27–29 列也列出了它們 |

以上是兩份文件各三處，並非少了對應的 production 實作。另兩份矩陣第 25 行說明 parser 無內容時 filed as SKIPPED，但 `CaptureCoordinatorTest.kt:1210` 的 case 只驗證 gap，沒有 verify／assert SKIPPED；宜補 assertion 或縮小該 coverage 敘述。

### M2. gap site 與 migration 欄位總數的母集合／文字需要修正

- `CHANGELOG.md:157`、`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt:103` 的 **15 = 9 process-wide + 6 source-scoped**，只在母集合限定為 `CaptureCoordinator` 時成立。正式程式還有 `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/HealthRepository.kt:45` 的 PROCESS_RESTART，所以 repository-wide 是 **16 = 10 + 6**。
- `CHANGELOG.md:244`、`QuietInboxDatabase.kt:99` 仍以「two additive nullable columns」描述 3→4；第 122–124 行實際新增 **3 欄：2 nullable + 1 NOT NULL DEFAULT 0**。後文提及第三欄不能消除開頭的錯誤總結。
- `CHANGELOG.md:75` 宣稱 snapshot flag set 只用來產生通用 warning、沒有依 flag 種類分流；acceptance 的 `DROPPED_MESSAGES` 與新的 `carriesUnrecordedLoss` 都直接反證此句。
- `CHANGELOG.md:89` 說本版接受的每筆事件都會 set `lossRecorded`；`IngestRepository.kt:114` 實際是 `lossOnAccept != null`。沒有 acceptance loss callback 的普通新事件仍是 0。應寫「已在 acceptance 記錄 loss 的事件設為 1」。

### M3. ARCHITECTURE 的 core 範圍過廣；未發布不能推出沒有裝置跑過 schema 4

`docs/ARCHITECTURE.md:18`、`docs/zh-Hant/ARCHITECTURE.md:18` 將所有 `core:*` 都說成純 Kotlin/JVM。`core/designsystem/build.gradle.kts:1` 實際套用 Android Compose library plugin。純 JVM 是六個 core 模組加 `parsers:apps`，應列舉或明確排除 design system。

`CHANGELOG.md:99` 從「0.1.3 shipped schema 3」推導「no device has ever run 4」，證據不足。未被發布 tag 包含與未曾被開發者／模擬器執行是不同命題；具體失效方式及版本決策見下節。

## 已核對成立的宣稱、證據與限制

### 1. 舊 producer 的可判定 predicate 成立，senderName 截短不會破壞推導

本次直接讀取 `git show v0.1.3:platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt` 的 `LINES` 產生處與完整 `bound()`，沒有只照 CHANGELOG 推論。

| v0.1.3 的 payload 證據 | 舊 producer 事實 | 判定 |
|---|---|---|
| `LINES` | 第 56 行只在陣列超過 `MAX_TEXT_LINES=32` 時加入；第 57 行截短單行不會加入它 | 可確定有整列被丟棄 |
| `MESSAGES`，所有倖存 `text.truncated != true` | 第 152 行因 list 超過 `MAX_MESSAGES=64` 加 flag；第 156 行因倖存 text 截短加同一 flag | 第二原因已排除，可確定有整個 message 被丟棄 |
| `HISTORIC_MESSAGES`，所有 historic 倖存 text 未截短 | 第 68／69 行呼叫同一個 bound，只換 flag | 與 messages 對稱，推導成立 |
| `MESSAGES`／`HISTORIC_MESSAGES`，有倖存 text 截短 | 無法知道是否同時超過數量上限 | 不應憑這個 flag 捏造整列 loss |
| 只有 senderName 被截短 | 第 161 行獨立以 256 字元 bound name，沒有因此加入 messages flag | 不影響上述排除法 |

`f7a09ed` 的 `CaptureCoordinator.kt:1200` 確實按照這些條件判斷。`LINES` 分支可先成立，即使同一 payload 另有無法判定的 message text 截短，也不會抹掉已知的 line loss。

新 `SnapshotFactory.kt:56`、`:153` 把數量超限改為 `*_DROPPED`，`:157` 的舊 messages flag 只由倖存 text 截短產生。因此本版**正式 factory** 的正常輸出不會命中舊 predicate。這個不相交性來自 producer；`lossRecorded` 的職責則是防止已結算的同一 event 再被結算，並不是普遍的版本標籤。自造 snapshot 仍可以帶舊 shape；若它沒有 loss callback，column 不會替它自動標成已結算。

限制：coordinator 的舊 MESSAGES fixture 可測 consumer 條件，但不是完整的「舊 producer 產生 65 筆、保存 64 筆、schema migration、JSON decode、replay」整合測試。缺少 historic 獨立控制與 senderName 截短控制。predicate 的結論由 source 推導支持，不等於所有 upgrade 組合都已跑過。

### 2. claim 與 gap 的單一交易、nested Room transaction 結構正確；exactly-once 仍受 C1 限制

`Daos.kt:80` 的 conditional UPDATE 以 eventId 主鍵、`lossRecorded=0`、`state='PENDING'` 決定唯一 winner；`IngestRepository.kt:133` 把 UPDATE 與 gap callback 包在同一 `withTransaction`。`SourceRepository.kt:66`／`:99` 的外層交易包含政策變更、callback、後續 discard／刪除圖，沒有在呼叫 claim 前自行 commit。

本專案使用 Room 2.8.4。該版 `withTransaction` 使用 transaction context 讓 nested 呼叫沿用交易執行環境；正常完成才標記 successful，結束時提交／回滾。Android SQLite 的 nested transaction 只在最外層成功結束且各層成功時提交，內層不是可提前獨立提交的 durable boundary。這支持目前同一 database 實例、同一 coroutine 交易呼叫鏈的整體 rollback 推論。[Room 2.8.4 原始碼](https://android.googlesource.com/platform/frameworks/support/+/75ef81cced187631f0dd74666188bd9d4cd3358f/room/room-runtime/src/androidMain/kotlin/androidx/room/RoomDatabase.android.kt#2025)、[Android nested transaction 契約](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase#beginTransaction())

| 情境 | 審查結論 |
|---|---|
| claim／gap 都成功，同一 pending event 再次 replay | claim 為 0，不新增第二筆 gap |
| gap callback 拋錯 | 同一交易回滾 claim；直接重試可再取得 claim |
| disable 與 replay 同時到達 | coordinator 以 pipeline mutex 排序；DB conditional claim 再限制同一 event 的 winner，不能各寫一筆 |
| nested claim 失敗，例外離開 source-policy transaction | source 更新、claim、gap 及交易內其他修改一同回滾；disable 已有相應真庫測試宣告 |
| 補記反覆失敗而觸發外層 retry terminal state | C1：原語正確，整條流程仍會失去補記機會 |

`DatabaseHolder.kt:111` 使用 WAL。SQLite 的持久提交點是有效 WAL commit record：程序死在 claim UPDATE 後、有效 commit record 前，未提交 frames 不會成為恢復後的已提交資料；若 commit record 已成功寫入，claim 與 gap 都屬於已提交交易，即使尚未 checkpoint。這回答的是 SQLite 引擎契約，不能把 coroutine cancellation 測試當成實際程序死亡測試。[SQLite WAL 提交模型](https://www.sqlite.org/wal.html#how_wal_works)、[WAL recovery](https://www.sqlite.org/walformat.html#recovery)

本次未對專案使用的 SQLCipher binary 做 kill／reopen 測試，也未 fresh-run nested rollback instrumentation。可證的是交易結構與官方語意，加上記憶體 SQLite 的成功／失敗對照；目前不能給整條 replay 流程無條件 exactly-once 背書。

另，這個一次性以**同一 eventId 的保留 journal row**為界。`SnapshotFactory.kt:137` 每次建立 snapshot 都產生新 UUID；active-notification resync 產生的新 event 不是此 claim 所去重的對象。`CHANGELOG.md:60` 起對此限制有說明，不能擴大成「同一實體通知永遠只一筆 gap」。

### 3. 已逐項檢查 PENDING 的退出與清除路徑

| 路徑 | 主要位置 | 結果 |
|---|---|---|
| 新事件 acceptance | `IngestRepository.kt:116`；`CaptureCoordinator.kt:901` | 已知新 dropped loss 與 journal insert 同交易；後續 SKIPPED／DISCARDED 不會拿走已提交 gap |
| 已成功 decode 的 replay | `CaptureCoordinator.kt:1105` | 先 settle，再進 commit fence／parse／commit；成功路徑正確，失敗預算有 C1 |
| 停用來源 | `CaptureCoordinator.kt:680`、`:688`；`SourceRepository.kt:66` | callback 內先 settle 再 discard，與 enabled 更新同交易 |
| 移除來源，保留或刪除資料 | `CaptureCoordinator.kt:718`、`:720`；`SourceRepository.kt:99` | 外層交易內先 callback settle，再清 checkpoint／discard；刪資料時 gap 保留並去除來源名稱 |
| source/global pause、maintenance fence | `CaptureCoordinator.kt:961`；`VaultMaintenance.kt:85` | 暫停使 row 保持 pending；maintenance cancel/join 工作後取得 pipeline mutex，取消例外不走普通 retry failure |
| retention | `Daos.kt:96`；`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt:106` | 實際呼叫的 `deleteExpired` 明確排除 PENDING |
| `deleteAllExpired` | `Daos.kt:99` | 在 target 全域搜尋只有宣告，沒有正式 caller；目前不是可達清除出口 |
| backup import／export 的 maintenance | `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:307` 起 | import 合併備份資料，不清空或還原 event_journal；沒有找到另一個 journal purge 路徑 |
| vault 全刪／key failure reset | `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/VaultRepository.kt:47`、`:65`；`DatabaseHolder.kt:90` | 直接刪除整個 vault，自然包含 pending；這確實繞過 consumer，但屬明示全刪的產品契約，不應在新 vault 偷補被使用者要求刪除的舊紀錄 |
| decode 失敗 | `IngestRepository.kt:163`／`:150` | I5：第三種自動 terminal／bulk discard 路徑，未經可讀 payload 的 settle |

全刪入口另核對 `feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsScreen.kt:342` 起的確認流程，以及 key-failure reset 的明示操作。沒有把使用者明確要求的刪除算成新的 silent-loss regression。相對地，decode failure 和 C1 都不是使用者要求刪掉內容。

### 4. schema 3→4 對官方舊版資料的形狀成立；已跑過舊 schema 4 的開發資料庫會無法開啟

`v0.1.3` tag 內 `QuietInboxDatabase.VERSION` 為 3；本次本地 `git tag --contains 9e379d3` 沒有結果。`f7a09ed` 的 schema version 仍為 4，3→4 migration 新增 packageName、truncationFlags、lossRecorded 三欄，lossRecorded 是 `INTEGER NOT NULL DEFAULT 0`。

本次使用 v0.1.3 exported schema 3 在 SQLite `:memory:` 建表，套用 target 中的三條 ALTER TABLE，檢查已有 pending row 的 state、attempts、payload 保留，新 lossRecorded 為 0。這驗證 SQL 資料保留，沒有取代 Room schema validation 或 snapshot replay。

另比較 base／target 的 exported schema 4：

| 項目 | `022b99a` | `f7a09ed` |
|---|---|---|
| schema version | 4 | 4 |
| event_journal 欄位數 | 9 | 10 |
| identity hash | `0ccd485ff125d560c5c524cd1fc65202` | `485f8d770a42385710a7adda5f3a9ab4` |

如果一台開發裝置／AVD 已執行較早的 3→4，它的 userVersion 已經是 4，不會再次走 3→4。新 binary 所期待的 hash／欄位與該資料庫不同，Room 會拒絕開啟；本專案 `DatabaseHolder.kt:107`–`:119` 把開啟錯誤呈現為 `VaultState.Locked`，不會自動無損修好，也沒有自動 destructive fallback。這是同版本 schema 改寫的相容性問題，不是加密金鑰真的壞了。[Room 開啟時的 identity 驗證](https://android.googlesource.com/platform/frameworks/support/+/75ef81cced187631f0dd74666188bd9d4cd3358f/room/room-runtime/src/commonMain/kotlin/androidx/room/RoomConnectionManager.kt#269)

**版本決策：** 若明確只支援已發布 schema ≤3 的升級，且所有較早 schema 4 都被明確界定為可重建的開發資料，原地修正尚未發布的 4 是合理選擇，不需要為不存在的正式發布路徑機械式增加 5。若要保留任何已執行舊 4 的裝置資料，就需要版本化的 migration 或具體相容處理；不能只重跑相同版本的 3→4。需要重建開發資料時也必須說明資料會刪除，不能把它當成一般 retry。

本次沒有清查所有裝置、模擬器或遠端發布歷史，故不接受「沒有任何裝置跑過 4」的絕對宣稱。`MigrationTest.kt:111` 起雖測 3→4，但其中 journal payload 是局部 JSON fixture；它證明欄位保留，不是完整舊 snapshot 可解碼後成功補記的證據。

### 5. I3 的清除時機正確，但一個 interval 只代表粗略的不確定範圍

`CaptureCoordinator.kt:513`–`:520` 確實只在 gap 寫入成功後清掉 `journalLossSince`；失敗時不會在記憶體中忘掉它。這部分修正成立。

process-wide UNKNOWN／BOUNDED interval 可以作為「至少有 loss，詳細事件已無法保存」的摘要。但起點是第一個寫不進去的事件，終點是**補記成功的時間**，不必然是故障修復的時間，也不證明該區間內所有來源持續完全中斷。I3 的觸發缺口還可能讓 interval 包住一段正常擷取，或把多次獨立故障合併。文件與 UI 不應把這個範圍解讀成精確、連續的 outage 或精確漏收數。

`VaultUnavailableException` 在 `CaptureCoordinator.kt:920` 先於 generic catch，因此**同一次 primary exception**不會同時落入兩個 catch。可是 generic journal exception 之後，第 943 行 guarded fallback 仍可能因 vault 轉鎖而再拋 VaultUnavailableException，最後設下 journalLossSince；其他事件也可能同時設下 vaultGapSince。兩個來源代表的真實故障期間可以重疊，所以「兩種 outage 永遠互斥」並不成立。這不等同於已證明同一 event 被重複記了兩個精確 gap，應避免把 catch 分流擴大成這種結論。

### 6. 六份文件的測試數量已重新推導，總數是宣告數而非本次通過數

以下只計 target tree 中測試宣告，不採用審查期間後續加入的測試。尤其 `CaptureCoordinatorTest` 是 **50**，不是後續工作樹的數字。

| 模組 | JVM `src/test` | Android `src/androidTest` |
|---|---:|---:|
| app | 5 | 0 |
| core/analytics | 34 | 0 |
| core/designsystem | 8 | 0 |
| core/identity | 5 | 0 |
| core/model | 5 | 0 |
| core/parser | 13 | 0 |
| core/reconcile | 22 | 0 |
| feature/analytics | 8 | 0 |
| feature/conversation | 1 | 3 |
| feature/onboarding | 5 | 0 |
| feature/search | 5 | 0 |
| parsers/apps | 45 | 0 |
| platform/backup | 24 | 2 |
| platform/capture | 50 | 0 |
| platform/crypto | 3 | 2 |
| platform/media | 10 | 0 |
| platform/storage | 12 | 34 |
| **合計** | **255** | **41** |

主要文件子集合也已核對：

- core 純演算法測試 **79 = model 5 + parser 13 + identity 5 + reconcile 22 + analytics 34**，不包含 design system。
- platform/storage instrumented **34 = VaultRoundTrip 4 + DemoData 2 + DeletionGraph 5 + JournalLossTransaction 7 + MediaExportBound 1 + Migration 4 + SearchPaging 2 + SourcePolicyTransaction 9**。
- platform/storage JVM **12 = VaultMaintenance 5 + VaultRepository 3 + Suppression 4**。
- platform/backup JVM 總計 **24**；文件列的 **BackupStager 21** 是正確子集合，不應誤改為 24。
- reconcile 的 **2 × 1000** property cases、各自的固定 seed **20260905／20260906** 與一般測試宣告數是不同單位，不能把 2000 次 iteration 加到 255。
- demo seed 為 **3 sources、8 conversations、129 messages**；文件「約 130」成立，另有 **30 日、1 session、2 gaps、3 diagnostics、2 summaries**。

逐檔結果：

| 文件 | 核對結果 |
|---|---|
| `docs/TEST_MATRIX.md` | 上述主要子集合成立；Monogram、conversation semantics 與未涵蓋 ViewModel 聲明需依 M1 修正 |
| `docs/zh-Hant/TEST_MATRIX.md` | 與英文有相同三處錯誤，其餘上述子集合一致 |
| `docs/SCOPE.md:16` 起 | parser 13、identity 5、reconcile 22、Capture 50、VaultMaintenance 5、DeletionGraph 5、Search 5（其中 locked/opening 2）、Conversation 1、analytics 34+8、Media 10、BackupStager 21、Reminder 5 與 source 一致 |
| `docs/zh-Hant/SCOPE.md:14` 起 | 同上；migration 1→2／2→3／3→4、50,000 上限與 12h 排程等 source 數值也一致 |
| `docs/ARCHITECTURE.md` | schema、資料表、加密參數、UI 數量等見下表；core:* 純 JVM 聲明錯誤，兩個 consumer 不代表沒有其他 terminal exit |
| `docs/zh-Hant/ARCHITECTURE.md` | 與英文同步，具有相同範圍／退出路徑限制 |

這些結果不能寫成「本輪跑過 255／41 且全部綠燈」。測試檔存在、assertion 有涵蓋、測試實際通過，是三種不同證據。

### 7. ARCHITECTURE 的其他數字與來源

| 宣稱；英文／繁中位置 | source 核對結果 |
|---|---|
| 模組圖，`:7` 起 | `settings.gradle.kts:26` 起共 **21 modules**：app 1、純 JVM 7、Android platform 分組 6、feature 7。純 JVM 是六個 core 加 parsers/apps；文件沒有字面宣稱「四層」，不額外捏造這個 count |
| admission 檢查兩次，`:30`／`:29` | `CaptureCoordinator.kt:882`、`:889`，鎖前／鎖內各一次，成立 |
| acceptance 一個交易，`:32`／`:31` | `IngestRepository.kt:116`，成立；後續內容 commit 是另一個交易，不能說兩階段整體只有一個交易 |
| 單一 SQLCipher DB、14 tables、schema v4，`:73`／`:64` | `QuietInboxDatabase.kt:14` 起 entities 恰 **14**；VERSION=4；沒有 production destructive-migration fallback |
| CJK bigrams、Latin words + 3-grams，`:87`／`:75` | `Normalization.kt:86` 起產生 CJK 單字與相鄰 pair、Latin whole word 與長度 3 window；`Daos.kt:468` 起及 `SearchRepository.kt:52` 起使用 token 數並逐候選驗證 substring。`n` 是查詢 token 數，不是固定常數 |
| backup export 一個 read transaction，`:94`／`:80` | export 的 DB 快照讀取包在 transaction；source 結構成立，沒有以實際並行 export／cancel 測試證成 |
| AES-256-GCM；三個 secret 共用一個 wrapping key，`:100`／`:85` | `KeystoreWrapper.kt:85` 起 keySize=256 且同步建立；`KeyMaterial.kt:25`、`:28` 起共用 wrapper instance，成立 |
| 三個 32-byte random secrets，`:104`／`:87` | KeyMaterial 的 database／media／search 三份 key 與 WrappedSecretFile size=32 相符；32 位元組即 256 位元 |
| blob／backup AES-256、HKDF-SHA256，`:106`／`:89` | BlobCipher keySizeBytes=32；`BackupCrypto.kt:42` 起 deriveSha256／streaming key size 32，參數相符 |
| Material 3／Navigation 3，`:112`／`:95` | 為套件名稱，version catalog 有對應 dependency；不是三個 component 的數量宣稱 |
| 600dp rail、840dp two-pane，600–839dp 單 pane，`:114`／`:96` | `MainNavigation.kt:77` 起將 medium／expanded 常數分別用於 railLayout／twoPane；官方定義分別為 **600／840**，與文件相符。實際裝置視窗寬度／UI 行為未於本次量測。[AndroidX 常數原始碼](https://android.googlesource.com/platform/frameworks/support/+/d40efbfcc8684651783df15bccc0fb2e42d0d3c0/window/window-core/src/commonMain/kotlin/androidx/window/core/layout/WindowSizeClass.kt) |
| Activity 五個 tabs，`:119`／`:100` | `AnalyticsViewModel.kt:56` 起 enum 恰 **5**；`AnalyticsScreen.kt:191` 起涵蓋五個分支 |

架構段落的 QI 編號、規格章節數、版本名稱不是測試數量，未混入統計。文件列出的 parsing／identity／dedup／statistics／normalisation 五類演算法在對應 core 模組存在；這不會使 Android design system 變成純 JVM。

### 8. 無法從本次 source review 證成的歷史／外部數字

以下不是本次測試結果，也沒有因文件寫出來就視為真機／發布證據：

- SCOPE 中 SM-S9280、Android 16、capture 3/3、API 36、2076×2152、851dp／411dp／720dp、實機／AVD 已演練等屬歷史執行主張。
- Play 的 172 countries、同一 AAB、49 screenshots 已上傳及發布日期屬外部狀態；本次沒有重新登入商店或查驗發布 artifact。
- TEST_MATRIX 中 screenshot script 的 **7 shots、80KB gate、最多 5 次 tap** 可由腳本檢查，**1080×2400 校準、2076×2152 截圖、曾誤拍 3.3MB 桌布**則是歷史執行敘述。
- p95 **10ms／500ms／300ms @ 100k**、**72h** soak 在矩陣已明列未量測；本次沒有補足。API 29／35 CI 是否實際跑過也沒有 fresh-run 證據。

本輪可據以作出 REQUEST CHANGES 的核心是 C1 的實際 SQL 反例，以及 I1–I3 的具體可達流程；不需要把未執行的裝置／CI 測試或無法取得的發布史假裝成失敗或成功。
