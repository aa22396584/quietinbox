# Round 34 — Claude 子代理獨立審查（`42f8d18..60fa4f0`）

審查對象：`f5f9581`、`a258662`、`234e5cd`、`60fa4f0` 四個修正 commit。
方式：唯讀。`git show` / `git log` / `grep` / `cat`，未執行 gradle、測試或 lint。
除本檔外未修改倉庫任何檔案。

---

## Verdict

**REQUEST CHANGES**

`a258662` 為了修正「舊版留下的矛盾缺口」而新增的 `reconcileSourceGaps`，會關閉**同一次呼叫剛剛開啟**的暫停缺口。
走完「暫停 → 停用 → 重新啟用」這條完全可由 UI 觸達的路徑後，來源處於「已啟用且暫停」——`isCapturable`
回傳 false、擷取確實停止——但健康頁面上沒有任何未結束的缺口說明這件事。這正是本專案第一條硬規則
（gaps are shown, never hidden）被違反，而且是這四個 commit 之一**新引入**的缺陷。BRIEF 第 3 點要求
「證明它不可能關掉同一次呼叫剛開的缺口」；證明不成立。

其餘三個 commit 的程式邏輯我沒有找到 Critical。cluster c 的逐則截斷、cluster a 的接受期交易、
retention 的未結束缺口保護，經逐行追蹤後成立。但測試誠實度有實質問題（見 Important 1、4），
文件仍有一處落後（Minor 1）。

---

## Critical

### C1. 政策載入時的缺口調和，會關掉同一次呼叫剛開的「暫停」缺口；停用後再啟用，暫停中的來源就再也沒有任何缺口

**位置**

- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:448`
  `pausedPackages = list.filter { it.enabled && it.paused }.map { it.packageName }.toSet()`
  ——**注意 `it.enabled &&`**：一個「已停用但 paused = true」的來源不在 `pausedPackages` 裡。
- `CaptureCoordinator.kt:425`
  `GapReason.SOURCE_PAUSED_BY_USER.name -> pkg !in pausedPackages`
- `CaptureCoordinator.kt:428`
  `if (contradicted || pkg !in known) health.closeGap(gap.id, now)`
- `CaptureCoordinator.kt:449` `reconcileSourceGaps(...)` 在 `loadSourcePolicy()` 裡，
  而 `loadSourcePolicy()` 由 `changeSourcePolicy` 在**每一次**政策變更後呼叫（`CaptureCoordinator.kt:433-438`）。
- `CaptureCoordinator.kt:659` 開啟 `SOURCE_PAUSED_BY_USER` 缺口的地方。
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:25-26`
  `UPDATE source_configuration SET enabled = :enabled` ——停用**不會**清掉 `paused`。
- `feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt:420-423`
  暫停按鈕與啟用 Switch 是兩個獨立控制項，暫停按鈕**沒有**依 `source.enabled` 停用；
  兩者可任意順序點擊。

**觸發序列一（BRIEF 字面要求的那個：同一次呼叫開了又關）**

1. 來源已停用（`enabled = false`），健康頁上有一條未結束的 `SOURCE_DISABLED_BY_USER`。
2. 使用者點 `HealthScreen.kt:420` 的暫停鍵 → `setSourcePaused(pkg, true)`。
3. `SourceRepository.setFlag` 判定 `paused` 由 false 轉 true，是真實 transition，
   在交易內執行 lambda → `CaptureCoordinator.kt:659` 開啟 `SOURCE_PAUSED_BY_USER` 缺口。交易提交。
4. `changeSourcePolicy` 接著呼叫 `loadSourcePolicy()`：`CaptureCoordinator.kt:448` 因為 `it.enabled` 為 false，
   `pausedPackages` **不含**該套件。
5. `reconcileSourceGaps` 讀到剛剛那一列（`health.openSourceGaps()` 只查這兩個 reason，命中），
   `CaptureCoordinator.kt:425` 判定 `pkg !in pausedPackages` → `contradicted = true` →
   `CaptureCoordinator.kt:428` `closeGap(id, now)`。

**同一個 `setSourcePaused` 呼叫先開後關，缺口的 start 與 end 相隔數毫秒。**
`CaptureCoordinator.kt:412-413` 的註解「The flag and its gap are written in one transaction now,
so this cannot fire for anything this version wrote」與 `CHANGELOG.md:96`
「Rows an earlier version left contradicting their own policy are closed when the policy loads」
都不成立：它對這一版自己寫的列就會開火。

**觸發序列二（可觀察的錯誤行為）**

1. 來源已啟用、使用者按暫停 → `SOURCE_PAUSED_BY_USER` 缺口開啟（此時 `enabled && paused`，
   `pausedPackages` 含它，調和不動它）。
2. 使用者把 Switch 關掉 → `setSourceEnabled(pkg, false)`：交易內開 `SOURCE_DISABLED_BY_USER` 缺口，
   `enabled = false`。接著 `loadSourcePolicy` → `pausedPackages` 不再含它 →
   **步驟 1 的暫停缺口被關閉**。
3. 使用者把 Switch 再打開 → `setSourceEnabled(pkg, true)`：交易內
   `closeOpenGapsForSource(..., SOURCE_DISABLED_BY_USER)`（`CaptureCoordinator.kt:644`）
   ——只關停用缺口。`Daos.kt:25-26` 沒動 `paused`，所以來源回到 `enabled = true, paused = true`。
   `loadSourcePolicy` 重算 `pausedPackages`：現在**含**它，調和不再認為暫停缺口矛盾——
   但那條缺口早在步驟 2 就被關掉了，而 `reconcileSourceGaps` 只會關、永遠不會開。

**結果**：來源處於 `enabled = true, paused = true`。
`CaptureCoordinator.kt:393-399` `isCapturable` = `pkg in enabledPackages && pkg !in pausedPackages` → **false**；
`offerCaptured`（`CaptureCoordinator.kt:787`）與 `admitted()`（`CaptureCoordinator.kt:823-829`）同樣擋下。
擷取確實停止，而健康頁面上**沒有任何未結束的區間說明它停止了**。之後使用者按「恢復」時，
`CaptureCoordinator.kt:661` 的 `closeOpenGapsForSource(..., SOURCE_PAUSED_BY_USER)` 找不到可關的列，
整段暫停視窗從此不存在於記錄裡。

`addSource` 走的 `SourceRepository.enable`（`SourceRepository.kt:26-41`）對既有列是
`existing?.copy(enabled = true, ...)`，**不重設 `paused`**，所以「移除後重新加入」也會落到同一個終局。

**現有測試不覆蓋**：`CaptureCoordinatorTest.kt` 內所有 `setSourcePaused` / `setSourceEnabled` 呼叫
（行 429、947、956、969、970、971、993、1023、1060、1065）沒有任何一個測試在**同一個套件**上同時使用
暫停與停用。行 1060/1065 是「啟用狀態下暫停再恢復」，行 969-971 的 `setSourcePaused(ENABLED_PKG, false)`
是對一個本來就沒暫停的來源呼叫（無 transition）。所以這個組合完全沒有測試。

**修正方向（一行）**：調和判定不應該讀 `pausedPackages`（那是「實際會不會擷取」的衍生集合，
已經把 `enabled` 混進去了），而應該直接讀該來源列的 `it.paused`；或者在 `setSourceEnabled` 的交易裡
一併處理暫停缺口的開關，讓「停用」與「暫停」兩條區間彼此獨立。

---

## Important

### I1. `IngestRepository.journal` 的 `if (accepted)` 與交易耦合是零覆蓋；`f5f9581` 宣稱受控的那個 guard 在測試 fake 裡

`f5f9581` 的 commit message 寫：

> Tests: ... and an event the journal already holds does not record it twice,
> **which controls the `if (accepted)` guard**.

這句不成立。

- 生產程式的 guard 在
  `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:113-117`：
  ```kotlin
  return db.withTransaction {
      val accepted = db.journalDao().insert(row) != -1L
      if (accepted) lossOnAccept?.invoke()
      accepted
  }
  ```
- 該測試（`CaptureCoordinatorTest.kt:1087`）整個 `ingest` 是 mock，
  fake 在 `CaptureCoordinatorTest.kt:156-165` 自己重寫了同一個 guard：
  ```kotlin
  val accepted = answer(snapshot)
  if (accepted) arg<(suspend () -> Unit)?>(3)?.invoke()
  ```
  而 `h.journalAnswers { seen.add(it.eventId) }`（`CaptureCoordinatorTest.kt:1092`）的去重也是 fake 的
  `MutableSet`，不是 primary key。把 `IngestRepository.kt:115` 的 `if (accepted)` 刪掉、
  或把 `withTransaction` 拆開，這個測試仍然全綠。

- 我對全庫做過 `\.journal(` 的搜尋：唯一以**非 null `lossOnAccept`** 呼叫 `journal` 的地方是生產程式
  `CaptureCoordinator.kt:872`。所有 instrumented 測試
  （`VaultRoundTripTest.kt:85,103,145`、`DeletionGraphTest.kt:100,120,127,208`、
  `SearchPagingTest.kt:72`、`MediaExportBoundTest.kt:79`、`BackupRoundTripTest.kt:82`）
  都用三參數版本。**沒有任何測試曾經讓 `lossOnAccept` 非 null**，
  因此「缺口與 journal 列同交易提交」「lambda 只在插入真的建立列時執行」這兩件 cluster a 的核心承諾
  在真實 SQLCipher 上完全沒有驗證。

公平地說：那個測試**確實**控制了另一件事——把缺口寫回 `processJournaled`（舊位置）會讓
`recordGap` 被呼叫兩次而變紅。它是「位置」的有效控制，不是「`if (accepted)`」的控制。
cluster b 為它自己的等價問題補了 `SourcePolicyTransactionTest`（真實金庫、六個測試）；
cluster a 沒有對等的補強。

**建議**：在 `platform/storage/src/androidTest/` 加一個測試，對同一個 `eventId` 呼叫兩次
`journal(snapshot, gen, ttl) { health.openGap(...) }`，斷言第二次回傳 false 且 `gap_interval` 只有一列；
再加一個 lambda 拋例外的案例，斷言 `event_journal` 沒有該列（對應
`SourcePolicyTransactionTest.aFlagChangeWhoseGapFailsIsNotCommittedEither` 的寫法）。

### I2. 「exactly once however often the event is delivered」不成立：`ACTIVE_RESYNC` 每次重連都會為同一則通知鑄造新的 `eventId`

`CHANGELOG.md:61` 與 `f5f9581` 的 commit message 都主張：

> the event id is the journal's primary key, so acceptance — and the gap with it —
> happens exactly once however often the event is delivered.

`eventId` 是 `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:137`
的 `UUID.randomUUID().toString()`——**每次擷取都是新的**，不是通知的穩定身分。
`CaptureCoordinator.kt:290,299`：

```kotlin
val resync = runCatching { service.activeNotifications?.toList() }.getOrNull().orEmpty()
...
for (sbn in resync) offer(sbn, CaptureOrigin.ACTIVE_RESYNC)
```

每次 listener 重連都會把仍留在通知欄的通知整批重新 offer。一則帶 `MESSAGES_DROPPED` 的通知只要還在欄上，
每次重連就會產生一個新 `eventId` → 新的 journal 列 → **再寫一次同一筆遺失的缺口**。
`journal()` 的 primary key 只保證「同一個 eventId 不會被接受兩次」，不保證「同一筆遺失只被記一次」。

需要說清楚：**這不是這四個 commit 新引入的**。舊的 `processJournaled` 寫法有完全相同的性質
（round-33 subagent I1 抱怨的是重播重複，那個確實修好了）。新的是這個**宣稱**——
commit message 與 CHANGELOG 都把 primary key 說成結構性的 exactly-once。宣稱應該收斂成
「同一個事件不會被接受兩次」，或者在缺口寫入前加上 notification key + post time 的去重。

### I3. `removeSource(deleteData = true)` 的真實路徑沒有端到端測試

- Coordinator 端（`CaptureCoordinatorTest.kt:988`）驗證 `h.health.forgetGapSource(ENABLED_PKG)` 被呼叫，
  但那個 lambda 是由 Harness fake `coEvery { sources.remove(any(), any(), any()) }`
  （`CaptureCoordinatorTest.kt:200-204`）呼叫的，不是真實 `SourceRepository.remove`。
- Instrumented 端（`SourcePolicyTransactionTest.kt` 的
  `forgettingASourceKeepsTheIntervalAndDropsTheName`）是**直接**呼叫 `health.forgetGapSource(pkg)`，
  完全沒有經過 `sources.remove(pkg, deleteData = true) { ... }`。
- `removingASourceClosesItsGapInTheSameTransaction` 用的是 `deleteData = false`。

也就是說：`deleteData = true` 時，`alsoInTransaction` 在
`SourceRepository.kt:96-104` 的刪除圖交易裡實際會不會執行、`forgetGapSource` 的
`UPDATE gap_interval SET packageName = NULL`（`Daos.kt:504`）會不會與整個刪除圖一起原子提交，
沒有任何測試碰過。

我逐行讀過 `SourceRepository.remove`，`alsoInTransaction?.invoke()` 在
`if (!deleteData) return@withTransaction emptyList()` **之前**，所以兩條路徑都會執行——
邏輯我認為是對的，但這是我讀出來的，不是測出來的。

### I4. Harness fake 現在在四處複製生產邏輯，使數個測試對它們命名的行為變成同義反覆

`a258662` 自承第一版 coordinator 測試是 theatre。修正後 fake 反而複製了**更多**生產邏輯：

| 位置 | fake 複製的生產邏輯 |
| --- | --- |
| `CaptureCoordinatorTest.kt:163` | `if (accepted) lambda()`（`IngestRepository.kt:115`） |
| `CaptureCoordinatorTest.kt:180` | `sourceList[i].enabled == enabled` 才視為 transition（`SourceRepository.kt:68`） |
| `CaptureCoordinatorTest.kt:192` | `sourceList[i].paused == paused` 才視為 transition（同上） |
| `CaptureCoordinatorTest.kt:202` | `remove` 呼叫 `alsoInTransaction`（`SourceRepository.kt:101`） |

受影響的測試：

- `CaptureCoordinatorTest.kt:962`「setting a source flag to the value it already has is not a second gap」
  ——`SourceRepository.kt:68` 的 `if (unchanged(current)) return@withTransaction false`
  拿掉之後這個測試仍然綠（fake 自己擋掉了）。commit 已經誠實承認這點，而且
  `SourcePolicyTransactionTest.settingTheFlagToTheValueItAlreadyHasWritesNothing` 在真實金庫上補了；
  但測試名稱仍宣稱它在測 idempotency。
- `CaptureCoordinatorTest.kt:1087`（見 I1）。
- `CaptureCoordinatorTest.kt:988` / `:1016`（見 I3）——它們控制的是「coordinator 傳了什麼給 repository」，
  這是有價值的，但不是「移除真的關掉了缺口」。

這不是要求現在重寫，而是要指出：這四行 fake 是一個會隨時間靜默侵蝕的模式。
註解已經寫了「the fake has to do the same, or a test about where a loss is written would be a test
about nothing」——那句話同時也是這個模式的風險說明。至少 I1 那條需要在 `platform:storage` 補真實覆蓋。

---

## Minor

### M1. `docs/zh-Hant/TEST_MATRIX.md:18` 仍是舊的 17 個，未列 `SourcePolicyTransactionTest`

`60fa4f0` 的 commit message 說「Test counts brought up in both TEST_MATRIX and both SCOPE files」。

- `docs/TEST_MATRIX.md`（英文）instrumented storage 那列已更新成 23 並列出
  `SourcePolicyTransactionTest`（6 個）。
- `docs/zh-Hant/TEST_MATRIX.md:18` 仍寫「——共 17 個」，列表停在 `MediaExportBoundTest`。
  同檔的 L1 列（:11，79 / parser 13）與擷取協調器列（:25，43）都更新了，只有這一列漏掉。

我實際數過：`platform/storage/src/androidTest/` 的 `@Test` 為
`DeletionGraphTest` 5 + `DemoDataTest` 2 + `MediaExportBoundTest` 1 + `MigrationTest` 4 +
`SearchPagingTest` 2 + `SourcePolicyTransactionTest` 6 + `VaultRoundTripTest` 3 = **23**，
英文那份是對的，zh-Hant 那份落後。這也表示 round-33 agy 的 Important-3（zh-Hant 文件落後）只修了一半。

### M2. `f5f9581` commit message 對「缺口寫入失敗」的機制描述錯誤（結果對）

commit 寫：

> If the gap insert throws, the transaction takes the journal row with it,
> **`journal()` returns false**, and the round-11 path records the loss as an UNKNOWN gap instead.

實際上 `IngestRepository.kt:113` 的 `db.withTransaction { ... }` 會把例外**往外拋**，不是回傳 false。
如果真的回傳 false，`CaptureCoordinator.kt:872` 的 `if (!ingest.journal(...)) return` 會直接 return，
**不會**寫任何缺口。結論之所以正確，是因為例外被
`CaptureCoordinator.kt:886` 的 `catch (e: Exception)` 接住，且此時 `journaled == false`，
走 `CaptureCoordinator.kt:894-900` 的 round-11 分支寫 `GapReason.UNKNOWN`。
機制敘述錯，終局正確。

### M3. 缺口寫入失敗現在會連帶丟掉本來能存下的訊息（行為改變，commit 未言明）

舊寫法是 `guarded { health.recordGap(...) }`——寫失敗被吞掉，事件照常 journal、照常 commit，
倖存的訊息會被存下來，只是少了缺口記錄。新寫法把它放進接受交易，`recordGap` 一失敗，
journal 列跟著回滾，事件根本沒被接受，**這則通知裡倖存的訊息也一起沒了**（只留下一條 UNKNOWN 缺口）。

在誠實規則下這個取捨可以辯護（寧可少內容也不要藏遺失），而且觸發窗很窄（DB 已經在出錯了），
但 commit 只寫了「Less precise, never absent」，沒說「也可能少存內容」。
專案的 working rule「Best-effort bookkeeping uses `guarded {}`」在這裡被刻意放棄，值得在 CHANGELOG 講明。

### M4. WhatsApp「truncation takes the tail」在邊界情況會把完整的最後一行標成截短

`parsers/apps/src/main/kotlin/dev/quietinbox/parsers/apps/WhatsAppParser.kt:80`
`textTruncated = truncatedBody && index == pairs.lastIndex`。

- 「本文被切在行中間」：被切的那半行仍是最後一行，
  若它已經不含 `Sender: ` 前綴，`WhatsAppParser.kt:59` 的
  `if (split.any { it == null }) return super.appSingleCandidates(...)` 會整批退回 super
  （單則候選，用 `bounded.truncated`），不會走到 `lastIndex` 這條。這條路徑是對的。
- 「本文剛好切在換行處」或「被切掉的殘行 trim 後成空字串被
  `WhatsAppParser.kt:56` 的 `filter(String::isNotEmpty)` 濾掉」：最後一個 pair 其實是完整的，
  卻被標成截短。方向是保守的（寧可多標一則），與 cluster c「不要對完整內文標截短」的目標相反，
  但資訊上無法分辨，我認為可以接受。單行本文（`lines.size < 2`）走 super，正確。

### M5. `LINES` 掉行被記成 `GapReason.MESSAGES_DROPPED`，且 enum 註解未更新

`CaptureCoordinator.kt:1088-1093` 把 `TruncationFlag.LINES` 加進 `DROPPED_MESSAGES`。
我確認語意是對的：`SnapshotFactory.kt:56` 只在 `arr.size > Limits.MAX_TEXT_LINES` 時升起
`LINES`，也就是「整行被丟棄」，而不是「某一行的文字被截短」（後者在每個
`BoundedText.truncated`，由 `StandardParser.kt:190` 讀）。

兩個小問題：
1. 健康頁面會把它顯示成 `gap_reason_messages_dropped`（`Labels.kt:82`）——InboxStyle 掉的是「行」不是「訊息」，
   標籤略有出入。沒有新增字串資源，五語系 parity 不受影響。
2. `core/model/src/main/kotlin/dev/quietinbox/core/model/NotificationSnapshot.kt:54` 的
   `LINES` 仍與 `TITLE, TEXT, BIG_TEXT` 排在一起且無註解，而 `MESSAGES` / `MESSAGES_DROPPED`
   都有註解說明兩者差別。既然 `LINES` 現在的語意等同 `*_DROPPED`，這個 enum 需要一行註解，
   否則下一個讀者會重犯同樣的分類錯誤。

### M6. 已檢查、**不是**問題的幾點（避免下一輪重查）

- `message_revision` 沒有截斷欄位，但
  `feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt:502`
  只顯示 `revisionCount`，**不顯示 revision 內文**，所以 cluster c 的缺陷沒有在歷史版本上重演。
- `setSourceEnabled` 把 `ingest.discardPendingJournal` 改成只在 `changed` 時執行
  （`CaptureCoordinator.kt:649`）。停用狀態下的來源不可能通過 fence 產生新的 PENDING 列
  （`admitted()` / `commitFenced` 都擋），所以重複停用不再清 journal 沒有影響。
- `forgetGapSource`（`Daos.kt:504`）是 `WHERE packageName = :packageName`，
  SQL 的 `= NULL` 永不命中，所以 process-wide（`packageName IS NULL`）缺口與其他來源的缺口都不受影響。
  BRIEF 第 3 點問的這一項成立。
- `reconcileSourceGaps` 的 `gap.packageName ?: continue`（`CaptureCoordinator.kt:422`）
  也讓 process-wide 缺口免疫。
- 帶 `packageName` 的**未結束**缺口只有 `SOURCE_DISABLED_BY_USER`（:646）與
  `SOURCE_PAUSED_BY_USER`（:659）兩處；`MESSAGES_DROPPED`（:863）與 `QUEUE_OVERFLOW`（:812）
  都是 `recordGap(start, end, ...)` 直接寫成已結束。所以 `openSourceGaps()` 只查兩個 reason 是完備的。

---

## Claims checked and found true

### cluster a（`f5f9581`）

1. **「缺口寫在接受事件的交易內，之後每條終局路徑都繼承它」** — 成立。
   `CaptureCoordinator.kt:855-872` 建構 `lossOnAccept` 並傳進 `ingest.journal`，
   `IngestRepository.kt:113-117` 在 `db.withTransaction` 裡執行。舊的
   `processJournaled` 寫入點（在 `commitFenced` 之後）已完全移除。
2. **「重播不會再記一次」** — 成立且有真實控制。
   `replayJournal`（`CaptureCoordinator.kt:1025-1055`）走的是 `processJournaled(replay, ...)`，
   完全不經過 `journal()`。測試 `CaptureCoordinatorTest.kt:1109` 斷言
   `h.journaled shouldBe emptyList()` 且 `recordGap(MESSAGES_DROPPED)` 為 0；
   把缺口寫回 `processJournaled` 會讓它變紅。**這是真控制。**
3. **「Schema 4 未被動到」** — 成立。四個 commit 的 diffstat 都沒有 `platform/storage/schemas/`，
   `MIGRATION_3_4`（`QuietInboxDatabase.kt:107-113`）未修改，
   `applyRevision` 只是多寫既有的 `truncationFlags` 欄位。
4. **「lambda 拋例外時，round-11 路徑會寫 UNKNOWN 缺口」** — 結論成立，機制敘述有誤（見 M2）。

### cluster b（`a258662`）

5. **「設定與缺口同交易、只在真實 transition 時執行」** — 生產程式成立。
   `SourceRepository.kt:65-73` 的 `setFlag` 先 `get()`（:67）、`if (unchanged(current)) return@withTransaction false`（:68）、
   再 `write(db)`（:69）與 `alsoInTransaction?.invoke()`（:70），全在 `db.withTransaction`（:66）內。
6. **「removeSource 在刪除圖的交易裡關掉自己的缺口」** — 成立。
   `SourceRepository.kt:99-104`：`alsoInTransaction?.invoke()`（:101）在
   `if (!deleteData) return@withTransaction emptyList()`（:104）之前，兩條路徑都會執行。
7. **「刪資料時保留區間、只抹掉名稱」** — 成立。
   `Daos.kt:504` 是 `UPDATE ... SET packageName = NULL`，不是 `DELETE`。
8. **「第一版 coordinator 測試是 theatre，但它仍控制某些真實的東西」** — 兩半都成立。
   `CaptureCoordinatorTest.kt:962` 對 idempotency 是同義反覆（fake 在 :180 擋掉），
   但把缺口寫法移回交易外會讓 `openGap` 被呼叫兩次而變紅。
9. **`SourcePolicyTransactionTest` 六個測試在真實金庫上** — 成立，我逐個讀過。
   `aFlagChangeWhoseGapFailsIsNotCommittedEither` 是這批裡最有力的一個：
   lambda `error(...)` 後斷言 `sources.get(pkg)!!.enabled shouldBe true` 且無缺口，
   把 `withTransaction` 拆掉必然變紅。

### cluster c（`234e5cd`）

10. **「三個 StandardParser 建構點都填了逐則真相」** — 成立。
    `StandardParser.kt:153`（MessagingStyle，`m.text?.truncated`）、
    `:190`（InboxStyle，`line.truncated`）、
    `:225`（單則，`bounded.truncated`）。`pickBodyBounded`（`:231-239`）保留了原本
    bigText → text 的選擇順序，`pickBody` 變成它的 `.value` 包裝，行為不變。
11. **「兩個 parser 測試有真實控制」** — 成立，而且我驗證過控制的方向。
    `Fixtures.messaging` 的 `message(..., truncated = true)`（`Fixtures.kt:162-165`）**只**設定
    `BoundedText.truncated`，不碰 `shape.truncated`。所以：
    - `StandardParserTest.kt:21`：`shape.truncated` 是空集合，若把 `textTruncated` 改回讀
      snapshot 層旗標會得到 `[false,false,false]` ≠ 期望的 `[false,true,false]` → 紅。
    - `StandardParserTest.kt:36`：`shape.truncated = {TITLE, TEXT}`，改回讀 snapshot 層會得到
      `[true,true,true]` ≠ 期望的 `[false,false,false]` → 紅。
    commit 說「reverting textTruncated to the notification-wide flag turns the first two red」——**屬實**。
12. **K6：舊 payload 的 snapshot 層旗標不再決定任何事** — 我做了完整搜尋，成立。
    全庫（排除測試）讀 `shape.truncated` 的地方只有四處：
    - `StandardParser.kt:38`、`AppParser.kt:170`、`AppParser.kt:192`：
      `if (shape.truncated.isNotEmpty()) warnings += ParseWarning.TRUNCATED_INPUT`
      ——批次層警告，語意（「有東西被裁掉」）對新舊 payload 一致，沒有變窄。
    - `CaptureCoordinator.kt:858`：`DROPPED_MESSAGES` 判定，只在**接受路徑**上，
      重播的舊列走 `processJournaled` 完全碰不到它。
    沒有任何一處會把 v0.1.3 的 `MESSAGES` 讀成新的窄語意。
13. **「逐則真相在舊 payload 裡已經存在」** — 成立。我比對了
    `git show v0.1.3:core/model/.../NotificationSnapshot.kt`：`BoundedText(value, truncated = false)`
    的定義與現在**逐字相同**，`MessagingMessageShape.text: BoundedText?` 也相同。
    所以 0.1.3 寫下的 journal payload 反序列化後，每則訊息自己的 `truncated` 都在。
    `v0.1.3` 的 enum 是 `{TITLE, TEXT, BIG_TEXT, LINES, MESSAGES, HISTORIC_MESSAGES, ACTIVE..., ACTIONS, EXTRAS, URI}`
    ——確認沒有 `MESSAGES_DROPPED`，所以 commit 承認的「0.1.3 的 pending 列重播時不會有 dropped 缺口」
    是誠實的描述，不是隱瞞。
14. **「Boolean 網域欄位沒有資訊損失」** — 成立。
    改動前唯一的消費者是 `Labels.kt:104-106` 的 `truncationLabel`，它本來就只判斷
    `flags.isEmpty()`。備份不受影響：`BackupService.kt:183,365` 搬的是資料庫欄位字串
    （`BackupRecords.kt:88` 的 `truncationFlags: String?`），不經過網域 `Message`。
    `Mappers.kt:68` 的 `!truncationFlags.isNullOrBlank()` 同時也實現了 round-33 Codex I5
    要求的「新版寫的未知 token 仍算作損失」。
15. **`applyRevision` 也寫截斷欄位（subagent I3）** — 成立。
    `Daos.kt:263-264` 加了 `truncationFlags = :truncationFlags`，
    `IngestRepository.kt:366` 傳 `truncationColumn(c.textTruncated)`。
16. **「沒有其他 adapter 把 snapshot 層狀態放到訊息列上」** — 成立。
    全庫 `MessageCandidate(` 的建構點只有四個：`StandardParser.kt:144,183,217` 與
    `WhatsAppParser.kt:73`，四個都已改用逐則來源。

### importants（`60fa4f0`）

17. **「retention 只清理已結束的區間」** — 成立且有真實控制。
    `Daos.kt:512` 的 `DELETE ... AND endEpochMs IS NOT NULL`，
    唯一呼叫點是 `RetentionWorker.kt:111`。測試
    `SourcePolicyTransactionTest.retentionExpiresClosedGapsAndKeepsTheOnesStillHappening`
    同時斷言「未結束的還在」**和**「已結束的真的被掃掉」（`all.size shouldBe 1`），
    所以它不是一個什麼都不掃的 sweep。把 `AND endEpochMs IS NOT NULL` 拿掉必然變紅。
18. **「LINES 是掉行不是截短」** — 成立，見 M5 的第一段。
    測試 `CaptureCoordinatorTest.kt:1138` 是真控制（把 `LINES` 從
    `DROPPED_MESSAGES` 移除即變紅）。
19. **「0.1.3 會用 UNSUPPORTED_VERSION 拒絕 0.1.4 的備份」** — 我讀了
    `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupRecords.kt:74-88`
    改後的註解，敘述與 round-33 Codex I1 / subagent I4 的要求一致。
    我**沒有**逐行驗證 `BackupStager` 的 manifest 版本比較邏輯（超出這四個 commit 的 diff），
    所以這一項我只能說「註解與 reviewer 的結論一致」，不能說「我獨立驗證了拒絕行為」。
20. **兩份 ARCHITECTURE 的 schema v3 → v4** — 成立。
    `docs/ARCHITECTURE.md:66-69` 與 `docs/zh-Hant/ARCHITECTURE.md:59-62` 都改成 v4。
21. **測試計數** — 我實際數過，除 M1 那一列外全部屬實：
    - JVM 總數 **246**：各 `src/test` 目錄的 Kotest `test(` + JUnit `@Test` 合計 236，
      加上 `platform/media` 的 `StringSpec` 10 個案例 = 246。
    - `core:parser` **13**（`docs/SCOPE.md:16`、`docs/zh-Hant/SCOPE.md:14`、兩份 TEST_MATRIX 的 L1 列）。
    - `CaptureCoordinatorTest` **43**（兩份 TEST_MATRIX 都已更新）。
    - `platform:storage` instrumented **23**（英文 TEST_MATRIX 正確；zh-Hant 落後，見 M1）。
    - `SourcePolicyTransactionTest` **6** 個 `@Test`。

### round 33 findings 對照

我把三份 round-33 報告的每個 Critical / Important 標題對到這四個 commit：

| round 33 findings | 對應 commit | 狀態 |
| --- | --- | --- |
| Codex C1（設定已提交、缺口在鎖外） | `a258662` | 已處理 |
| Codex C2（缺口寫入失敗被遺忘 + 重播重複） | `f5f9581` | 已處理 |
| Codex C3（缺口晚於第一道 fence） | `f5f9581` | 已處理（移到接受交易，早於 fence） |
| Codex C4（journal 內持久化的 TruncationFlag、升級誤讀） | `234e5cd` | 已處理（以「舊旗標不再決定任何事」回應，並誠實承認 0.1.3 pending 列不補缺口） |
| Codex I1 / subagent I4（0.1.4 備份會被 0.1.3 拒絕） | `60fa4f0` | 註解已改 |
| Codex I2 / agy C1 / subagent C2（逐則旗標其實取自整批） | `234e5cd` | 已處理 |
| Codex I3（LINES 當成截短、行內截短未標） | `60fa4f0` + `234e5cd` | 兩半都已處理 |
| Codex I4 / agy C2 / subagent C1（移除來源不處理缺口、刪資料留下名稱） | `a258662` | 已處理 |
| Codex I5（未知旗標降級成「沒截短」） | `234e5cd`（`Mappers.kt:68`） | 已處理 |
| Codex M1 / M2 / subagent I6（測試名稱與 conversationId 論證） | `60fa4f0` CHANGELOG | 已處理 |
| agy I1 / subagent（idempotency） | `a258662` | 已處理 |
| agy I2 / subagent I2（缺口開關在 pipelineMutex 之外） | `a258662` | 已處理 |
| agy I3（zh-Hant 文件落後） | `60fa4f0` | **只修一半**，見 M1 |
| agy Minor-1（`toDomain` 對 null 的無謂 split） | `234e5cd`（`Mappers.kt:68`） | 已處理 |
| subagent I1（重播重複寫缺口） | `f5f9581` | 已處理 |
| subagent I3（`applyRevision` 不更新截斷欄位） | `234e5cd` | 已處理 |
| subagent I5（兩份 ARCHITECTURE 寫 schema v3） | `60fa4f0` | 已處理 |
| subagent I7（retention 刪掉未結束缺口） | `60fa4f0` | 已處理 |

---

## 我沒有驗證的事

- **沒有執行任何測試、gradle 或 lint。** 所有「這個測試會變紅」的判斷，都是讀程式推得的，
  不是跑出來的。commit message 宣稱的「verified by removing each」我無法獨立確認，
  只能確認被移除的那段程式**確實在測試的因果路徑上**（或不在，如 I1）。
- **沒有裝置或模擬器驗證。** C1 的觸發序列是從 `HealthScreen.kt:420-423` 的控制項與
  `CaptureCoordinator` / `SourceRepository` / `Daos` 的程式路徑推導的，我沒有在裝置上實際點過。
- **`BackupStager` 的 manifest 版本比較**（見 Claims 19）超出這四個 commit 的 diff，我沒有讀。
- **五語系字串 parity** 我沒有跑 `tools/check-strings.py`；不過這四個 commit 的 diffstat
  完全沒有動 `core/designsystem` 的任何 `values*/strings.xml`，也沒有新增 `stringResource` 引用，
  所以我判斷 parity 不受這批改動影響。
