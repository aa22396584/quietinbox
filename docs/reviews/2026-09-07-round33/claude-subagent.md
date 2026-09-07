# Round 33 — 獨立審查：commit `9e379d3`（schema 4：缺口可以指名來源，訊息可以承認自己被切掉了）

審查者：Claude subagent（Opus 5, 1M context）
日期：2026-09-07
範圍：`git show 9e379d3` 全部 26 個檔案，加上為了判斷影響而讀的既有程式碼
（`CaptureCoordinator`、`SourceRepository`、`BackupStager`、`RetentionWorker`、`Daos`、
`HealthScreen`/`HealthViewModel`、`Fixtures`、`schemas/3.json`、`schemas/4.json`）。

## 我實際做了什麼、沒做什麼

做了：

- 用 `python3` 逐欄位比對 `schemas/3.json` 與 `schemas/4.json`（表、欄位、`notNull`、
  `defaultValue`、`createSql`、indices、views），再比對 `MIGRATION_3_4` 的兩行 `ALTER TABLE`。
- 逐模組計算 JVM 測試數（`test(` / `@Test` / Kotest `StringSpec` 的字串區塊），合計 **236**；
  `platform/storage/src/androidTest` 的 `@Test` 合計 **17**。
- 執行 `python3 tools/check-strings.py`（唯讀）：`OK: 0 error(s), 0 warning(s)`。
- 追過 `offer → enqueue → process → processJournaled → replayJournal` 的每一個早退。

沒做（也不該在唯讀審查裡做）：**沒有跑任何 gradle 指令**，因此沒有執行
`migrate3To4AddsNullableColumnsAndKeepsRows`、沒有跑 lint、沒有跑 JVM 測試、沒有裝置驗證。
schema 與 migration 的一致性、以及 `createAllTables` 與 migration 的收斂，是我以檢視
JSON 與 SQL 得出的結論，不是 `runMigrationsAndValidate` 的執行結果。

---

## Verdict

**REQUEST CHANGES**

migration 本身是乾淨的——兩個可為 null 的附加欄位、沒有任何列被改寫、匯出的 schema 與
`ALTER TABLE` 逐欄位相符、兩條建表路徑會收斂。commit 自己說「這批的風險從來不是 migration」，
這點我同意。但它把風險放錯了地方：真正的問題不在備份來回，而在**缺口的生命週期**與
**截斷旗標的粒度**。有兩個必須在 0.1.4 出去之前修掉的問題：移除來源會把一筆缺口永久留在
開啟狀態（並且讓套件名活過「刪除資料」），以及逐則訊息的截斷標籤其實是逐則通知的，會在完整
的訊息上標「文字被截短」。

---

## Critical

### C1. 移除來源會把它的缺口永遠留在「未結束」，而且讓套件名活過「移除並刪除資料」

**檔案**：`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:637-639`、
`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SourceRepository.kt:64-80`、
`feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt:270-282`、
`feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt:326-327`

**觸發序列**（全部在健康頁上，都是一般操作）：

1. 把某個來源的開關關掉 → `CaptureCoordinator.kt:619` 插入一列
   `reason=SOURCE_DISABLED_BY_USER`、`endEpochMs=NULL`、`packageName=com.foo` 的缺口。
2. 在同一列按「移除」，兩顆按鈕任選一顆（`HealthScreen.kt:326` 保留資料 /
   `HealthScreen.kt:327` 刪除資料）。
3. `removeSource`（`CaptureCoordinator.kt:638-639`）只做
   `changeSourcePolicy { sources.remove(...) }`，完全沒有碰 `gap_interval`。
   `SourceRepository.remove`（`SourceRepository.kt:64-80`）在一個 transaction 內刪掉 source 列、
   checkpoint、pending journal，並在 `deleteData` 時刪掉 conversation、media、suppression、
   summary 與 **diagnostics**——`SourceRepository.kt:76` 的
   `db.diagnosticsDao().deleteForPackage(packageName)` 就在那裡。唯一沒有被清掉的、帶 package
   的資料，就是本 commit 新增的 `gap_interval.packageName`。

**觀察到的錯誤行為**：

- `HealthScreen.kt:272` 把 `endEpochMs == null` 呈現為 `health_gap_open`，所以健康頁上
  會永遠掛著一列「你把這個來源關掉了 · com.foo」的未結束缺口，指向一個已經不存在的來源。
- 沒有任何路徑能關掉它。重新加入該來源走的是 `addSource`
  （`CaptureCoordinator.kt:599-600`）→ `SourceRepository.enable`（`SourceRepository.kt:26-41`），
  那條路徑不呼叫 `closeOpenGapsForSource`；只有 `setSourceEnabled(pkg, true)` 會關，而在
  來源被移除之後，清單裡已經沒有那一列可以切換了。
- `deleteData = true` 時使用者選的字面意思是「移除並刪掉這個來源的資料」
  （`health_remove_delete_data`）。這個 commit 讓套件名第一次能夠活過刪除圖。專案規則
  「Deletion is a graph」在同一個 transaction 內對 diagnostics 成立，對新的 gap 欄位不成立。

**建議**：在 `SourceRepository.remove` 的 transaction 內把該 package 未結束的缺口收尾
（或整列刪掉），或在 `CaptureCoordinator.removeSource` 之後補一次
`health.closeOpenGapsForSource(now, packageName, SOURCE_DISABLED_BY_USER, SOURCE_PAUSED_BY_USER)`。
兩者擇一都要決定「移除本身要不要留下一段可見的缺口」——我傾向留下但把它收尾。

### C2. 「逐則訊息的截斷」其實是逐則通知的，而且會把標題／副標的截斷當成內文被截短

**檔案**：`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:314-320`
（旗標的來源）、`IngestRepository.kt:39-46`（`TEXT_TRUNCATION`）、
`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:111-119`
（哪些欄位升哪個旗標）、
`core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Labels.kt:103-108`、
`feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt:505`

這段（`IngestRepository.kt:314-320`）雖然寫在 per-candidate 的迴圈裡，值卻整個來自
`snapshot.shape.truncated`——那是**整批通知**的旗標集合，不是這一則訊息的。於是同一則通知
解析出來的每一則訊息，都會被寫入同一組 `truncationFlags`，而 `Labels.kt:105-108` 只要集合
非空就回傳「文字被截短」。

**具體輸入 A**：一則 `MessagingStyle` 通知帶 3 則訊息，只有第 2 則超過
`Limits.MAX_TEXT_CHARS`（4096）→ `SnapshotFactory.kt:157` 升起 `MESSAGES` →
3 則訊息全部寫入 `truncationFlags="MESSAGES"` → 對話畫面上 3 顆泡泡都標「文字被截短」，
其中兩顆是完整的。

**具體輸入 B**（更糟，因為連一則被截短的訊息都沒有）：一則通知的 3 則訊息全部完整，但
`EXTRA_SUB_TEXT` 或 `EXTRA_SUMMARY_TEXT` 超長 → `SnapshotFactory.kt:115-116` 升起
`TEXT`（`SnapshotFactory.kt:47-52` 的 `text()` 在 `b.truncated` 時把旗標加進整批的集合，見 `:50`），而 `TEXT` 在 `TEXT_TRUNCATION` 裡 → 3 顆完整的泡泡都被標成被截短。
`TITLE`（`SnapshotFactory.kt:111-112, 119`）與 `LINES`（`SnapshotFactory.kt:56`，InboxStyle
的行數）同理。

**範圍**：這個問題只出現在「一個 snapshot 產出多個 candidate」的 template——`MESSAGING`
與 `INBOX`。對 `BIG_TEXT` / `BASE` 這類只產出一則訊息的通知，整批層級的旗標剛好就是那一則
訊息自己的，標籤是正確的。修法必須保留這個情況。

**為什麼是 Critical**：這正是本 commit 開頭指出的錯誤形態——「一個旗標由兩種不同的損失升起」
——換一個位置重演；而且它產生的是**假的誠實標籤**。對一個以誠實標註為賣點的 App 來說，
在完整的訊息上寫「文字被截短」比不標更糟：使用者會去別處找那段不存在的缺文。

**逐則的真相確實存在，但在 parser 邊界被丟掉了**：
`core/model/.../NotificationSnapshot.kt:70-82` 的 `MessagingMessageShape.text` 是
`BoundedText`，帶著逐則的 `truncated` 位元，`SnapshotFactory.kt:153, 157` 就是讀它來分別升 `droppedFlag` 與 `textFlag` 的。
但 `MessageCandidate`（`core/model/.../ParsedBatch.kt:120-132`）沒有對應欄位，所以那個位元
到不了 ingest。要做到 commit 宣稱的 per-message，需要在 `MessageCandidate` 加一個欄位並由
`StandardParser` 與各 adapter 帶過去——比這個 commit 做的多。在那之前，最小的修法是：
只有當這個 snapshot 產出**單一** candidate 時才寫入整批旗標（此時它確實是那一則訊息的），
多 candidate 時要嘛不寫、要嘛改用一個描述整則通知的標籤文字。單純把 `TEXT_TRUNCATION`
縮到只剩 `MESSAGES` / `HISTORIC_MESSAGES` 並不夠——那會讓 `BIG_TEXT` 通知裡真的被切掉的
單一則訊息失去標籤。

---

## Important

### I1. 重播與重試會為同一則事件重複寫下 `MESSAGES_DROPPED` 缺口，並把別的缺口擠出畫面

**檔案**：`CaptureCoordinator.kt:871-873`（記錄點）、`CaptureCoordinator.kt:982-988`（重播也走
`processJournaled`）、`IngestRepository.kt:124-131`（`markJournalRetryable` 保持 PENDING）、
`feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthViewModel.kt:71`、
`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:493`

缺口記在 `processJournaled` 的開頭，而 `processJournaled` 會被 `replayJournal` 對每一筆仍是
`PENDING` 的 journal 列**再跑一次**。兩條會留下 `PENDING` 的路徑：

- **(a)** 第二道 `commitFenced`（`CaptureCoordinator.kt:903`）在 parse 與身分解析期間有暫停或
  維護落下 → `return false`，列仍是 `PENDING`；恢復後重播 → 再記一筆。
- **(b)** `ingest.commit` 丟例外 → `process()` 的 catch → `markJournalRetryable`
  （`IngestRepository.kt:127-131`）在 `MAX_ATTEMPTS = 3`（`IngestRepository.kt:73`）之前都維持
  `PENDING` → 同一則事件最多寫出 3 筆一模一樣的 `MESSAGES_DROPPED`。

**觀察到的錯誤行為**：健康頁上出現數筆起訖時間完全相同的「該則通知帶的訊息多到讀不完」。
放大效應：`HealthViewModel.kt:71` 只取 `observeGaps(30)`，而 `Daos.kt:493` 是
`ORDER BY createdAtEpochMs DESC LIMIT :limit`——重複列會把真正的
`LISTENER_DISCONNECTED` / `COLD_START` 擠出畫面。一個為了「缺口一定被看見」而加的機制，
在這裡反過來把別的缺口藏起來。

同樣的形狀還有一個較弱的版本：同一則會話若持續帶超過 `Limits.MAX_MESSAGES`（64，
`core/model/.../Limits.kt:10`）則訊息，來源 app 每次重貼通知都是一個新的 `eventId`，就會各記
一筆缺口。我無法驗證有哪個真實 app 會這樣做（倉庫裡刻意沒有真實 app 的擷取樣本），所以只作為
第二個理由提出。

**建議**：以 `eventId` 去重（記錄前先查同一事件是否已有列，或把 `eventId` 也存進缺口列），
或把記錄點移到「這次真的走完 commit」之後只做一次。

### I2. 缺口的開／關寫在 `pipelineMutex` 之外，快速切換會把狀態寫反

**檔案**：`CaptureCoordinator.kt:609-622`、`CaptureCoordinator.kt:624-635`、
`HealthViewModel.kt:107-108`

policy 的寫入在 `changeSourcePolicy` 內（持 `pipelineMutex`，符合 QI-SEC-001），但
`openGap` / `closeOpenGapsForSource` 在鎖**外**，而且 `HealthViewModel.kt:107-108` 每次點擊都
各起一個 coroutine。

**序列**：使用者連續點兩下（關、開）。
A 取得鎖寫 `enabled=false`、放鎖；B 取得鎖寫 `enabled=true`、放鎖。接著 B 的
`closeOpenGapsForSource`（`:617`）先被排到並執行——此時 A 的缺口列還沒插入，什麼都關不到；
A 的 `openGap`（`:619`）才執行。

**觀察到的錯誤行為**：來源明明是啟用且正在擷取的，健康頁卻掛著一筆永遠不會結束的
「你把這個來源關掉了 · com.foo」，直到使用者剛好再完整做一次「關 → 開」為止。

註：全域的 `setPaused`（`CaptureCoordinator.kt:369-372`）是同樣形狀，屬於既有而非本 commit
新增；但每來源的開關在同一份清單裡可以連點多個，命中機率高得多。

### I3. `applyRevision` 不更新 `truncationFlags`：被截短的改寫仍然顯示成完整

**檔案**：`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:262-263`、
`IngestRepository.kt:351`

```
@Query("UPDATE message SET body = :body, revisionCount = revisionCount + 1, eventId = :eventId WHERE id = :id")
suspend fun applyRevision(id: Long, body: String, eventId: String)
```

**序列**：某則訊息第一次擷取時內文完整（`truncationFlags` 為 null）→ 來源 app 改寫該則訊息，
第二次的 snapshot 內文超過 4096 被截短 → `applyRevision` 換掉 `body`、旗標仍是 null →
泡泡把一段**被截短的內文畫成完整的**。這正是本 commit 宣稱修掉的行為
（「a body that had been cut was drawn exactly like a complete one」），只是換到改寫路徑上。

反向也成立：先被截短、後被改寫成完整內文，會留下一個過期的「文字被截短」標籤。

### I4. 「舊版讀取器會忽略這個欄位」對整份備份檔並不成立

**檔案**：`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupStager.kt:61-63`、
`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:157`、
`CHANGELOG.md`（本 commit 新增的 `### Changed` 段）

`BackupService.kt:157` 把 `schemaVersion = QuietInboxDatabase.VERSION`（現在是 4）寫進
manifest；`BackupStager.kt:61` 的閘門是
`r.formatVersion != FORMAT_VERSION || r.schemaVersion > QuietInboxDatabase.VERSION` → 丟
`UNSUPPORTED_VERSION`。所以 0.1.3（`VERSION = 3`）打開 0.1.4 的備份，會在讀到第一筆 manifest
時就整份拒絕，**根本走不到 `ignoreUnknownKeys`**；欄位層級的相容性在這個方向上沒有作用。

拒絕較新的 schema 是刻意且安全的設計，程式沒有錯。錯的是 commit message 與 CHANGELOG 對
使用者說的話：「appended and defaulted so an older reader ignores it and a newer reader
restoring an older file gets null」——後半句是真的（見 Claims #4），前半句在整份檔案的層級
是假的。這屬於「文件跑在程式前面」，而 CHANGELOG 是使用者會讀的。

### I5. 兩份 ARCHITECTURE 都還寫著 schema v3

**檔案**：`docs/ARCHITECTURE.md:69`、`docs/zh-Hant/ARCHITECTURE.md:61-62`

> the schema is exported to `platform/storage/schemas/` (**v3**) and
> `fallbackToDestructiveMigration()` is not used.

本 commit 把 `VERSION` 改成 4 並匯出了 `4.json`，卻沒有更新這兩行。CHANGELOG 與兩份
TEST_MATRIX 都改了，ARCHITECTURE 被漏掉。

### I6. 「沒有任何缺口站點能知道 conversation」這個論證，在新的 `MESSAGES_DROPPED` 站點上不成立

commit 的理由是：「identity 是在 ingest 期間解析的，而那正是被丟棄的事件沒有發生的事。」
這對其他六個原因成立，對它自己新增的這一個不成立：

- `CaptureCoordinator.kt:872` 記下缺口；
- `CaptureCoordinator.kt:887` `identity.resolve(snapshot, batch)`；
- `CaptureCoordinator.kt:904-912` `ingest.commit(...)` 回傳
  `CommitOutcome.conversationId`（`IngestRepository.kt:48-49`）。

也就是說，這個站點的事件**確實被 ingest 了**——那正是這個 bug 的定義（「a gap hidden inside
a success」）。被丟掉的是同一則 `MessagingStyle` 通知裡較舊的訊息，它們和留下來的訊息屬於
同一個 stream、同一個 conversation，而那個 conversation 在記錄缺口之後的數十毫秒內就已知。

我沒有把它列成 Critical，因為沒有加欄位就沒有錯誤資料落地，整體「先不加 conversation 欄位」
的結論也可能仍然正確。但這個**論據**是錯的，而且它正好擋住 issue #24（`inbox-incomplete-capture-marker`）
想要的東西：對話畫面與收件匣沒辦法標出「這個對話漏了較舊的訊息」，使用者只能在健康頁看到一筆
帶套件名的行程層級缺口。請把 commit / `MIGRATION_3_4` kdoc 裡這段論證改成
「目前沒有站點在記錄的當下知道 conversation」，否則下一輪會把它當成已定案的結論。

### I7. 一個關了很久的來源，它的未結束缺口會被 retention 整列刪掉

**檔案**：`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt:110-111`、
`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/settings/SettingsRepository.kt:26, 98`

```
val gapCutoff = now - s.retentionDays.toLong() * DAY_MS
db.healthDao().deleteGapsBefore(gapCutoff)
```

`deleteGapsBefore`（`Daos.kt:499-500`）只看 `createdAtEpochMs`，不管 `endEpochMs` 是不是 NULL。
預設保留天數 30 天，最低可設到 1 天（`SettingsRepository.kt:98`）。一個被關掉超過保留天數的
來源，它那筆未結束的缺口會被整列刪除；之後重新啟用時 `closeOpenGapsForSource` 什麼都關不到，
而那整段沒有擷取的期間就從紀錄裡消失了。

刪除規則是既有的（`PAUSED_BY_USER`、`LISTENER_DISCONNECTED` 一樣適用），但本 commit 第一次
造出「常態上會開著好幾週甚至好幾個月」的缺口類型——「關掉某個來源」本來就是長期狀態——
所以這個交互作用在 0.1.4 才變成會實際發生的事。

---

## Minor

- **M1. 兩個 KDoc 相鄰，等於把註解掛錯。**
  `IngestRepository.kt:37-48`：原本的
  `/** Result of a committed snapshot; ids are used to kick off media work. */` 現在懸空
  （Kotlin 只把最靠近的 KDoc 綁到宣告上），`TEXT_TRUNCATION` 拿到的是新寫的那一段，而
  `data class CommitOutcome`（`:48`）變成沒有註解。
- **M2. `MESSAGES_DROPPED` 的缺口視窗不是損失的視窗。**
  `CaptureCoordinator.kt:872` 用 `[postedAtEpochMs ?: observedAt, observedAt]`，兩端通常只差
  毫秒。被丟掉的是「這批裡最舊的那些訊息」，它們的發生時間遠早於 `postedAt`。
  `HealthScreen.kt:271-275` 會把它畫成「2026-09-07 14:03 → 14:03」，看起來像一個瞬間的損失。
  給 `startEpochMs = null`（會顯示 `health_gap_unknown_time`）會更誠實。
- **M3. 被丟棄的 pending journal 落在記錄的視窗之外。**
  `setSourceEnabled(pkg, false)` 在 `changeSourcePolicy` 內就丟掉該來源的 PENDING journal
  （`CaptureCoordinator.kt:612`），但缺口從 `now` 才開始（`:614, :619`）。那些被丟掉的事件是
  更早觀察到的。
- **M4.「七個記錄缺口的地方」是以 `GapReason` 計數，不是呼叫點。**
  實際的插入點有 14 個：`HealthRepository.kt:45`；`CaptureCoordinator.kt:260, 317, 371, 451,
  565, 586, 619, 629, 687, 761, 811, 827, 872`。以「原因」計數的話，改動前確實是七個原因、
  五個行程層級、兩個知道套件（`QUEUE_OVERFLOW` 與 journal 失敗的 `UNKNOWN`）——所以這句話的
  意思是對的，字面不對。
- **M5. 兩處註解因為新欄位而過期。**
  `Daos.kt:585-590` 與
  `platform/storage/src/debug/.../DemoDataRepository.kt:281-283` 都說
  「`gap_interval` 沒有可以做標記的欄位」；`packageName` 就是了。demo 的缺口仍以
  `createdAtEpochMs` 比對刪除。
- **M6. 健康頁直接顯示 raw package name。** `HealthScreen.kt:279` 顯示
  `com.whatsapp` 而不是來源清單裡已有的 `displayName`。
- **M7. 每列訊息、每個旗標都做一次 enum 線性掃描。**
  `Mappers.kt:68-71` 的 `enumValues<TruncationFlag>().firstOrNull { it.name == name }` 在對話
  畫面每次載入都會跑；`TruncationFlag.entries.associateBy { it.name }` 或
  `runCatching { enumValueOf<TruncationFlag>(name) }` 即可。
- **M8. import 順序。** `CaptureCoordinator.kt:15` 把 `TruncationFlag` 放在
  `NotificationSnapshot` 之前；`Mappers.kt:20` 把 `core.model.TruncationFlag` 夾在
  `platform.storage.db.*` 之間。我在 `build-logic`、`gradle`、`app` 內（排除 `build/`）找不到
  ktlint / spotless / detekt 設定，所以**沒有任何 gate 會擋下這個**——純粹是一致性。
- **M9. 新測試只覆蓋了「parse 結果為空」的那一半。**
  正向案例 `CaptureCoordinatorTest.kt:954-966` 用 `Fixtures.base(title = null, text = null)` 建 snapshot，
  走的是 `SKIPPED` 分支。沒有任何案例斷言「訊息被丟棄**且**其餘訊息照常 commit」時缺口一樣被
  寫下——那正是 commit message 開頭說的主要情境（「藏在成功裡的缺口」）。
- **M10. `docs/SCOPE.md:50` 現在會誤導。** 它說 per-id 的刪除 token「needs schema v4」；v4 已經
  到了但沒有帶這個改動。

---

## Claims checked and found true

以下逐條對照 commit message 的宣稱，以及我是怎麼驗的。

1. **旗標分割是真的、而且分對了。** `SnapshotFactory.kt:152-160`：
   `if (list.size > Limits.MAX_MESSAGES) truncated += droppedFlag`（整則訊息被丟）與
   `if (text?.truncated == true) truncated += textFlag`（保留下來但文字被切）確實是兩個不同的
   旗標，historic 版本同理（`SnapshotFactory.kt:68-69`）。
   `CaptureCoordinator.kt:1015` 的 `DROPPED_MESSAGES` 只含兩個 `*_DROPPED`。
   負向控制存在且有效：`CaptureCoordinatorTest.kt:968-979` 送出
   `setOf(MESSAGES, BIG_TEXT)` 並斷言 `recordGap(..., MESSAGES_DROPPED, ...)` 為
   `exactly = 0`——如果分割被拿掉、或 `DROPPED_MESSAGES` 誤含 `MESSAGES`，這個測試會紅。**✔**

2. **缺口確實記在 parse 結果能短路它之前。** `CaptureCoordinator.kt:871-873` 位於
   `registry.parserFor`（`:874`）、`parser.parse`（`:876`）、parse 例外的 `FAILED` 早退
   （`:877-881`）與空批次的 `SKIPPED` 早退（`:882-886`）之前。commit 說「一個測試抓到了」的
   那個案例（空 parse → 只被記成 skipped）確實被涵蓋。**✔**

   brief 要我找第二個漏網的早退，我把 snapshot 進入到 commit 之間的每一個早退列出來：

   | 早退點 | 損失有沒有被記錄 |
   | --- | --- |
   | `offer` 的 `gen == null` / `paused` / 非來源（`:697-709`） | 尚未建立 snapshot（held 或直接不讀），與此無關 |
   | `enqueue` 佇列滿（`:761`） | 記 `QUEUE_OVERFLOW`，本 commit 起帶套件名 |
   | `process` 第一道 `admitted`（`:787-790`） | 只加 `droppedAfterRevoke`，無缺口（既有行為） |
   | `process` 鎖內第二道 `admitted`（`:794-797`） | 同上 |
   | `ingest.journal` 回傳 false（`:799`） | 無缺口（既有行為） |
   | `VaultUnavailableException`（`:803-816`） | `openGap UNKNOWN`，寫不進去就記在 `vaultGapSince` |
   | 一般例外（`:817-830`） | 已 journal → retryable；未 journal → 記 `UNKNOWN` 缺口 |
   | **`processJournaled` 第一行 `commitFenced`（`:864`）** | **見下** |
   | 第二道 `commitFenced`（`:903`） | 列留 `PENDING`，重播時補記（但見 I1 的重複） |

   **第二個案例**：事件被接受並 journal 之後、還沒被處理之前，使用者把來源關掉。
   `commitFenced`（`CaptureCoordinator.kt:842-852`，其中 `DISCARDED` 在 `:845-847`）會 `markJournal(DISCARDED, "SOURCE_DISABLED")`
   並 `return true`，位置在 `MESSAGES_DROPPED` 記錄點**之前**。那一次的訊息丟棄不會有自己的
   缺口。它只被本 commit 新增的 `SOURCE_DISABLED_BY_USER` 缺口**間接**涵蓋，而那個缺口的起點
   （`now`，`:614`）晚於該事件被觀察到的時間。我把它列在這裡而不是列為缺陷，因為那段時間終究
   是有紀錄的——但它不是「無懈可擊」，涵蓋是間接的。

3. **`MIGRATION_3_4` 與匯出的 `4.json` 完全相符，沒有任何列被改寫。**
   我用 python 逐鍵比對兩份 schema JSON：14 張表、views 全部存在且沒有增刪，除了
   `gap_interval` 與 `message` 各多一個欄位以外，其餘 12 張表逐字相同。兩個新欄位在 4.json 都是
   `{"fieldPath": ..., "columnName": ..., "affinity": "TEXT"}`——沒有 `notNull`（即可為 null）、
   沒有 `defaultValue`——與 `QuietInboxDatabase.kt:112-113` 的
   `ALTER TABLE gap_interval ADD COLUMN packageName TEXT` /
   `ALTER TABLE message ADD COLUMN truncationFlags TEXT` 產生的欄位一致（SQLite 的
   `ADD COLUMN` 不加 `NOT NULL`、不加 `DEFAULT`）。所以 `validateMigration` 在這兩張表上應該
   會過——但我沒有實際執行它。
   **兩條建表路徑會收斂**：兩個新欄位在各自 entity 都是最後一個屬性
   （`Entities.kt:43`、`Entities.kt:156`），4.json 的 `createSql` 也把它們排在最後
   （`... createdAtEpochMs INTEGER NOT NULL, packageName TEXT)` /
   `... expiresAtEpochMs INTEGER, truncationFlags TEXT, FOREIGN KEY(...)`），
   與 `ALTER TABLE` 附加到尾端的位置相同；Room 的驗證本來也不比對欄位順序。
   migration 有被掛上（`QuietInboxDatabase.kt:117` 的 `MIGRATIONS` 陣列，由
   `DatabaseHolder.kt:112` 的 `addMigrations(*MIGRATIONS)` 使用），全庫沒有任何
   `fallbackToDestructiveMigration`。**✔**

4. **備份往前相容（0.1.4 讀 0.1.3 的檔）是真的。** manifest 的 `schemaVersion = 3 ≤ 4` 通過
   `BackupStager.kt:61` 的閘門；`BackupRecord.Message` 缺少 `truncationFlags` 時取預設 null
   （`BackupRecords.kt:82`），`BackupService.kt:365` 把那個 null 寫進 `MessageEntity`。**✔**
   **往後（0.1.3 讀 0.1.4）是假的** —— 見 I4。
   **位置式建構的危害確實被消掉了**：`BackupService.kt:171-185` 現在全部具名。**✔**
   附帶確認：`MessageDao.exportPage`（`Daos.kt:332-333`）是 `SELECT *` 回傳 `MessageEntity`，
   所以新欄位確實會被匯出，不需要改投影類別。

5. **七個站點都沒有塞 conversationId，資料上是乾淨的。** 我逐一看過所有 14 個插入點，
   沒有任何一個能取得（也沒有任何一個試圖取得）conversation。**但「永遠不可能知道」這個論證
   在 `MESSAGES_DROPPED` 站點上是錯的**——見 I6。

6. **「`TruncationFlag` 既沒有被持久化、也沒有被窮舉 switch，所以分割不花成本」是半真。**
   - 「沒有窮舉 switch」**為真**：全庫唯一相關的 `when` 是 `Labels.kt:105` 的 `when { ... }`
     （對集合是否為空），不是 `when (flag)`。加兩個 enum 值不會讓任何地方編譯失敗。**✔**
   - 「沒有被持久化」**在這個 commit 之後為假**：`message.truncationFlags`（`Entities.kt:156`）、
     備份記錄（`BackupRecords.kt:82`）；而且**在這個 commit 之前就已經**以 journal payload 的
     形式被持久化了——`IngestRepository.kt:96` 把整個 `NotificationSnapshot` 序列化成 JSON，
     `TruncationFlag` 是 `@Serializable`（`NotificationSnapshot.kt:40-50`）。
   - 分割本身仍然安全，理由是 commit 沒寫出來的三件事：舊資料庫沒有那個欄位；寫進資料庫的
     名字被 `TEXT_TRUNCATION` 過濾成不含 `*_DROPPED` 的子集；`Mappers.kt:68-71` 對不認得的
     名字降級成「沒有旗標」而不是崩潰。
   - 「未來版本讀到不認得的旗標名」在資料庫方向是安全的（同上）。在 journal payload 方向不是：
     降版之後 `pendingJournal`（`IngestRepository.kt:107-119`）解不開 payload，會把該列標成
     `FAILED/"DECODE"` 並**不記缺口**。這是既有行為、只在不支援的降版情境出現，我不列為缺陷，
     但既然 commit 主張「沒有被持久化」，這個持久化點應該被知道。

7. **測試數字全部對得上。** JVM 合計 **236**（我逐模組數，capture 36、parsers/apps 43、
   analytics 34、backup 24、storage 12、media 10 …）；instrumented storage 合計 **17**
   （`MigrationTest` 4、`DeletionGraphTest` 5、`VaultRoundTripTest` 3、`SearchPagingTest` 2、
   `DemoDataTest` 2、`MediaExportBoundTest` 1）。兩份 TEST_MATRIX 都寫 36 / 17，與程式碼一致。
   新增的四個 capture 案例（`CaptureCoordinatorTest.kt:916-979`）與 TEST_MATRIX 的描述相符。**✔**
   我沒有執行它們。

8. **字串目錄 parity 為真。** `python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`。
   四個新字串（`conv_truncated`、`gap_reason_source_disabled`、`gap_reason_source_paused`、
   `gap_reason_messages_dropped`）在五份目錄（`values`、`values-b+zh+Hant`、`values-b+zh+Hans`、
   `values-ja`、`values-ko`）都存在，都沒有佔位符與複數形。`gapReasonLabel`
   （`Labels.kt:71-84`）是對 `GapReason` 的窮舉 `when`，三個新原因都補上了——這是本 commit
   裡真正需要窮舉更新的那個 enum，而它做對了。**✔**

9. **升級後的第一次執行（舊列 `packageName = NULL`）行為正確。**
   `closeOpenGapsForSource`（`HealthRepository.kt:63-68`）以
   `gap.packageName == packageName` 比對，NULL 永遠不匹配，所以升級前留下的舊缺口不會被任何
   來源層級的「恢復」誤關；而全域的 `closeOpenGaps` 只依 reason 過濾（`Daos.kt:496`），
   `PAUSED_BY_USER` 與新的 `SOURCE_PAUSED_BY_USER` 是不同的 enum 值，兩邊不會互相干擾。
   **行程在開／關之間死掉**也是安全的：`openGaps` 的查詢是
   `WHERE endEpochMs IS NULL AND reason IN (:reasons)`，不依賴任何行程內狀態，所以重新啟用時
   仍然關得到（前提是 retention 還沒把它刪掉——見 I7）。**✔**

10. **「pause 一個來源不會關掉另一個來源的缺口」為真。** `HealthRepository.kt:63-68` 逐列比對
    package；`CaptureCoordinatorTest.kt:936-952` 另外斷言全域的
    `closeOpenGaps(any(), SOURCE_PAUSED_BY_USER)` 被呼叫 `exactly = 0`。
    **「會不會被開兩次？」**：`setSourceEnabled(pkg, false)` 對一個已經停用的來源會插入第二列，
    但健康頁的開關產不出這個呼叫（已經是關的就沒有「再關一次」）；而且
    `closeOpenGapsForSource`（`HealthRepository.kt:63-68`）是逐列迭代所有符合的未結束缺口，
    所以一次重新啟用會把兩列都收尾。不是缺陷，但 brief 有問，故記錄。
    我另外檢查了 pause 與 disable 交錯的順序：暫停 A（開 `SOURCE_PAUSED_BY_USER`）→ 停用 A
    （開 `SOURCE_DISABLED_BY_USER`，`SourceRepository.setEnabled` 不動 `paused` 欄位）→
    重新啟用 A（只關 `DISABLED`）→ 來源回到 enabled 且仍 paused，`PAUSED` 缺口正確地留著開啟，
    直到恢復為止。這一組順序是對的。**✔**
