# Round 36 — Claude subagent（獨立審查）

**審查範圍**：`f7a09ed..44ce484`
**審查時間**：2026-09-07
**審查者**：Claude Opus 5 (1M context)，獨立 subagent

## 工作樹在審查期間移動了

BRIEF 指定的範圍是 `f7a09ed..HEAD`，並點名 `f410809` 與 `8e3bb3b` 兩個 commit。我開始審查時 HEAD 是
`c7a882e`（round 36 brief）；審查進行中另一個 agent 提交了 `44ce484`（"Round 35 minors"）。

**我判斷的是 `f7a09ed..44ce484`。** 我逐行讀了 `44ce484`：它只動了三處 —— `CaptureCoordinator.kt` 的
import 排序與 `DROPPED_MESSAGES` 的一段註解、`MigrationTest.kt` 的檔尾換行、`CHANGELOG.md` 兩句話。
**沒有任何一處觸及本報告的任何一項發現**，我在 `44ce484` 之後重新確認過每一項發現仍然成立。
`docs/reviews/2026-09-07-round36/gemini-3.8-flash-high-agy.md` 也在審查期間出現（另一位 reviewer 的報告），
我沒有讀它，本報告是獨立得出的。

---

## Verdict：**REQUEST CHANGES**

C1 是本輪的「上一輪修正引入的新缺陷」——不是邏輯錯誤（分頁 SQL 我實測證明是對的），而是
**round 35 subagent I2 的修正本身，整個 repository 裡沒有任何一個測試會因為它被改壞而變紅**。
JVM harness 的 fake 把 `JournalPage.next` 寫死成 `null`，結構上無法表達新程式碼所做的事；
instrumented 測試則完全繞過協調器的分頁迴圈。這正是 round 34 I1、round 35 C3 的同一個模式
（「斷言其實是在斷言一個 fake」）在第三個地方復發，也正是 BRIEF 第 4 點要我去找的東西。

我把它評為 Critical 的理由**不是**「它會 hang」——我把每一個候選變異都放進 sqlite 實際跑過，
原本以為會無限迴圈的兩個其實都會終止（詳見 C1 的表格與我對自己推測的修正）。理由是：
**這是 round 34 I1 → round 35 C3 的同一個結構性缺陷第三次出現，而且這一次它守著的是本輪自己的
修正**。JVM fake 把 `next` 寫死為 `null`、完全忽略 `after` 游標，因此它在結構上無法表達
round 35 I2 所引入的機制；instrumented 測試則整個繞過協調器的分頁迴圈。實測顯示，兩個
**會靜默漏掉待結清列**（也就是這個修正本身要防止的那件事）的單字元改動，今天不會讓任何測試變紅。

我同時明確聲明：**目前送出的程式碼行為是正確的**——我用 production 的 SQL 原文在 sqlite 上
逐頁走訪過，不漏、不重、會終止。**這不是線上的資料遺失 bug，而是一個沒有防護網的新機制。**
維護者若判斷回歸風險可接受，這一項可以降級為 Important；我不會為此爭辯，但依 BRIEF 自己訂的
標準（「For each new or changed test, state what change to production code would make it fail.
If none, that is a finding」），它必須被記錄下來。

---

## Critical（push 前必須修）

### C1. 分頁讀取是本輪的新機制，而全 repository 沒有任何測試能證明它是對的

**位置**
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:159-171`
  （`pendingJournalForPackage`，`next` 游標的計算）
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:76-86`
  （`pendingForPackageAfter` 的 keyset SQL）
- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1180-1187`
  （`settleCarriedOverLosses` 的 `while (true)` 分頁迴圈）

**我怎麼驗證的**

先確認送出的程式碼是對的（避免把「未測試」誤報成「有 bug」）：

1. 從 `platform/storage/schemas/dev.quietinbox.platform.storage.db.QuietInboxDatabase/4.json`
   取出 `event_journal` 的 `createSql`：

   ```
   CREATE TABLE IF NOT EXISTS `event_journal` (`eventId` TEXT NOT NULL, `generation` TEXT NOT NULL,
   `receivedAtEpochMs` INTEGER NOT NULL, `expiresAtEpochMs` INTEGER NOT NULL, `state` TEXT NOT NULL,
   `attempts` INTEGER NOT NULL, `failureCode` TEXT, `payload` TEXT NOT NULL, `packageName` TEXT,
   `lossRecorded` INTEGER NOT NULL, PRIMARY KEY(`eventId`))
   ```

   `receivedAtEpochMs` 是 `INTEGER NOT NULL` —— 若它可為 null，`receivedAtEpochMs > :afterTime`
   對那些列會求值為 NULL，於是**每一頁都排除它們、永遠讀不到**，那會是一個真正的 Critical。
   它不可為 null，所以這個故障模式不存在。`eventId` 沒有 `COLLATE` 宣告，因此 `>` 與
   `ORDER BY` 都用 SQLite 預設的 BINARY 定序，兩者一致。

2. 用 sqlite3 依上面的 `createSql` 建表，插入 6 列、在 `receivedAtEpochMs` 上刻意製造平手
   （100×3、200×2、300×1，且 `eventId` 的字典序與插入序相反），以 production 的 SQL 原文
   逐頁走訪，並模擬 production 的行為（claim 之後列仍然是 `PENDING`）：

   ```
   limit=2 queries=4 visited=[e-a, e-b, e-c, e-y, e-z, e-m] unique=True count=6
   limit=3 queries=3 visited=[e-a, e-b, e-c, e-y, e-z, e-m] unique=True count=6
   limit=6 queries=2 visited=[e-a, e-b, e-c, e-y, e-z, e-m] unique=True count=6
   ```

   **不漏、不重、會終止**，平手也正確。`limit=3` 那一列正好回答 BRIEF 的第 3 問：最後一頁剛好
   等於 `limit` 時，`next` 是非 null，於是多送一次回傳空集的查詢後才停 —— 正確，只是多一次 round-trip。
   `limit=6` 同理（2 次查詢）。這是**實測復現**，不是 source trace。

於是問題不在邏輯，而在防護網。接著我列舉所有呼叫點：

3. **JVM harness 的 fake 結構上無法表達分頁**
   `platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:266-269`：

   ```kotlin
   coEvery { ingest.pendingJournalForPackage(any(), any(), any()) } answers {
       val pkg = firstArg<String>()
       JournalPage(synchronized(pendingByPackage) { pendingByPackage[pkg]?.toList().orEmpty() }, null)
   }
   ```

   `next` **永遠是 `null`**，而且 `after` 游標（第二個參數）被完全忽略。因此
   `settleCarriedOverLosses` 的 `while (true)` 迴圈在每一個 JVM 測試裡都只會跑一圈。

4. **instrumented 測試根本不呼叫那個迴圈**
   `SourcePolicyTransactionTest.kt:152, 171` 與 `JournalLossTransactionTest.kt:201, 226` 都是
   手工展開的 `for (s in ingest.pendingJournalForPackage(pkg).snapshots) …`，用的是預設
   `limit = 200`，而測試各自只插入 1 列（`SourcePolicyTransactionTest.kt:292` 明確斷言
   `snapshots.size shouldBe 1`）。**沒有任何一個測試曾讓 `rows.size == limit` 成立。**

**因此，以下每一個對 production 的變更都不會讓任何測試變紅**（這是 BRIEF 要求的「what change
would make it fail」清單）。**每一列我都用上面那個 sqlite harness 實際跑過，而不是用推理的**——
其中三列跑出來的結果與我原本的推測不同，我把實測結果寫在這裡：

| # | 變更 | **實測**結果 | 有測試抓到嗎 |
|---|---|---|---|
| 1 | 刪掉 `settleCarriedOverLosses` 的 `while` 迴圈，只呼叫一次 | 第一頁之後的損失全部靜默不記錄 | 否 |
| 2 | `JournalPage.next` 永遠回傳 `null` | 同 #1 | 否 |
| 3 | SQL `eventId > :afterId` → `>=` | **會終止**；每個分頁邊界重複回傳一列（`e-b, e-c, e-y, e-z, e-m` 各兩次）。因為 `claimLoss` 帶 `AND lossRecorded = 0`，重複不會寫出第二個 gap → **實際無害** | 否 |
| 4 | `rows.size == limit` → `rows.isNotEmpty()` | **會終止**，走訪順序與 production 完全相同，只多一次空查詢 → **無害** | 否 |
| 5 | `ORDER BY receivedAtEpochMs, eventId` 去掉 `eventId` | `e-c` 被回傳三次，而 **`e-y` 從未被走訪** → **靜默漏掉列，真實資料遺失** | 否 |
| 6 | `next` 改用 `snapshots.lastOrNull()` 而非 `rows.lastOrNull()` | 一整頁都解不出來時 `last == null` → `next = null` → **提早停止，靜默漏掉其後所有列** | 否 |
| 7 | SQL `receivedAtEpochMs > :afterTime` → `>=` | **唯一真正無界的迴圈**：`OR` 被吸收，同一 `receivedAtEpochMs` 的列數 ≥ `limit` 時同一頁永遠重複 | 否 |

三點修正與說明，避免這份表被誤用：

- **我原本以為 #3 與 #4 是無界迴圈，實測證明不是。** 兩者都會終止，#4 甚至完全無害。
  真正會無限迴圈的是 #7，那是我原本沒列出來的。這也是為什麼下面 Critical 的理由**不建立在
  「會 hang」之上**。
- **#7 的可達性是低的**：它需要同一個 package 有 ≥ `limit`（預設 200）列共用同一個
  `receivedAtEpochMs`。該欄位來自 `snapshot.observedAtEpochMs`（`IngestRepository.kt:105`），
  而它是每則通知各自在 callback 中取的 `System.currentTimeMillis()`
  （`CaptureCoordinator.kt:811`；held 的走 `h.heldAtEpochMs`，`:601`），不是整批共用一個時戳。
  cold-start resync 的 hold buffer 上限是 256，理論上有機會讓不少通知落在同一毫秒，
  但要單一來源湊到 200 列同毫秒實務上極不可能。**我把 #7 列為「若被改壞則後果嚴重」，
  而不是「今天有風險」。**
- **#5 與 #6 才是這張表裡真正危險的兩列**：兩者都會**靜默漏掉待結清的列**，也就是這個
  修正本身要防止的那件事（損失沒被記錄），而且都不會 hang、不會拋例外、沒有任何徵兆。
  #6 尤其值得一提：現行程式碼用 `rows.lastOrNull()`（原始列）而非 `snapshots.lastOrNull()`
  （解碼後）是**刻意且正確**的，`IngestRepository.kt` 的新 KDoc 也寫明了理由
  （「it still moves the cursor, or the page after it would never be reached」）——
  但這個刻意的決定同樣沒有任何測試守著。

**建議的最小修正**：在 `JournalLossTransactionTest` 加一個 instrumented 測試，插入 `limit + 1` 列
（用 `limit` 參數傳一個小值，例如 2，插 5 列，其中兩列 `receivedAtEpochMs` 相同），走完整個
`pendingJournalForPackage` 分頁迴圈，斷言每一列剛好被走訪一次；並在 JVM harness 讓 fake 真正
依 `after` 與 `limit` 分頁，使 `settleCarriedOverLosses` 的迴圈成為可觀察的。

---

## Important（push 前應該修）

### I1. 新測試的名字說「不會被 commit」，但它沒有斷言這件事 —— 這正是本 commit 在別處剛修好的那個瑕疵

**位置**：`platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:1516-1547`
（`"a carried-over row whose loss cannot be written is not committed and spends no attempt"`）

測試的斷言是：

```kotlin
coVerify(exactly = 0) { h.ingest.markJournalRetryable(any(), any()) }
coVerify(exactly = 0) { h.ingest.markJournal("evt-claim-fails", any(), any()) }
h.gaps.isEmpty() shouldBe true
```

**我怎麼驗證的**：`git grep '"COMMITTED"'` 顯示成功的 commit 是在
`IngestRepository.kt:270` 與 `:480` 由 `db.journalDao().setState(snapshot.eventId, "COMMITTED", null)`
**在 `ingest.commit(...)` 內部**寫下的，**不會**經過 `markJournal`。因此
`coVerify(exactly = 0) { h.ingest.markJournal(...) }` 涵蓋不到「被 commit 了」這個情況。

那這個測試現在為什麼還是有鑑別力？因為它的 fixture 是

```kotlin
Fixtures.base(title = null, text = null).copy(truncated = setOf(TruncationFlag.LINES))
```

—— 沒有 title、沒有 text，於是 `processJournaled` 走到
`if (batch.messages.isEmpty() && batch.summary == null)` 這一支，呼叫
`markJournal(eventId, "SKIPPED", …)`（`CaptureCoordinator.kt:1013`）。也就是說：**刪掉
`if (settled.isFailure) return@withLock` 這一行確實會讓測試變紅，但變紅的原因是「走了 SKIPPED」，
不是「被 commit 了」**。守衛有被控制住，但測試證明的命題比它名字宣稱的弱一階。

這與 round 35 Codex M1 在 dropped-messages 測試上找到的是**同一個瑕疵**——「以成功 commit 命名，
但 fixture 沒有 body，所以走的是 skipped 路徑」——而本 commit 才剛在那一處把它修掉
（`CaptureCoordinatorTest.kt:1239-1249`，改用帶 body 的 `survived` fixture 並加上
`coVerify(exactly = 1) { h.ingest.commit(…) }`）。同一輪、同一個檔案、同一種錯誤，在新寫的測試裡復發。

**建議**：把 fixture 換成帶 body 的（照 `survived` 的寫法），並加上
`coVerify(exactly = 0) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any()) }`。

### I2. 結清失敗會讓使用者的「停用來源」靜默失敗，而本輪把這個行為的適用範圍擴大到了 `remove`

**位置**
- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:680-698`
  （`setSourceEnabled`：`settleCarriedOverLosses` 在 `sources.setEnabled` 的 transaction 內）
- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:721-737`
  （`removeSource` 同理）
- `feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthViewModel.kt:107-116`

**我怎麼驗證的**：`recordCarriedOverLoss` → `ingest.claimEventLoss` → `db.withTransaction { … }`，
巢狀在 `sources.setEnabled` 的外層 transaction 內。gap 寫入失敗會拋例外、外層 transaction 整個
rollback ——「來源仍然是啟用的」。這正是 instrumented 測試所斷言的：
`SourcePolicyTransactionTest.kt:166-181`（**本範圍之前就存在**），以及 `docs/TEST_MATRIX.md:18` 寫的
「a settlement that fails leaves the row pending, the claim unspent and **the source switched on**」。

**這裡我要修正自己一開始的措辭**：`setEnabled` 這一半**不是**本輪才釘下的。
`8e3bb3b` 對 `SourcePolicyTransactionTest.kt:171` 的改動（`@@ -168,7 +168,7 @@`）只是把
`pendingJournalForPackage(pkg)` 改成 `.snapshots` 以配合新的回傳型別 —— 那個測試在本範圍之前
就存在了。本範圍**新增**的是兩個 `remove` 分支的失敗控制（`SourcePolicyTransactionTest.kt:272-321`）。
所以正確的說法是：本輪把「失敗就整個 rollback」這個已釘下的語意**擴大適用到 `removeSource`**，
而使用者端的另一半（告訴使用者失敗了）在兩個分支上都仍然缺席。發現本身成立，我原本的框架
過度宣稱了它的新穎性。

而 UI 端：

```kotlin
fun setSourceEnabled(packageName: String, enabled: Boolean) =
    viewModelScope.launch { runCatching { coordinator.setSourceEnabled(packageName, enabled) } }
```

`runCatching { … }` 的結果被丟棄，沒有任何錯誤 surface。四個 source policy handler
（`setSourceEnabled` / `setSourcePaused` / `addSource` / `removeSource`）全都是這個形狀。

後果：使用者把某個 App 的擷取關掉，開關（綁在 Room 的 `observeSources()` flow 上）會彈回「開啟」，
而**沒有任何說明**，擷取繼續進行。對一個以隱私為賣點的 App，「停止擷取這個 App」靜默不生效
是有份量的；而 gap 頁面在這個情境下也不會有任何記錄，因為 gap 寫入正是失敗的那一步。

我把它列為 Important 而非 Critical，因為：(a) 觸發條件是 gap 寫入失敗（磁碟滿／金庫錯誤），罕見；
(b) 開關會視覺上彈回，使用者不是完全沒有訊號；(c) 這個語意本身不是本輪引入的。
但本輪把它**擴大適用到 `removeSource`** 並為之補上失敗控制測試，卻在兩個分支上都沒有補上
使用者端的另一半 —— 一個規格被刻意加固、加寬的時候，正是補齊它的時候。

順帶回答 BRIEF 隱含的一問：`CaptureCoordinator.kt:1119-1122` 的註解說
「The symmetry with live capture is exact」。就它所在的 replay 位置而言**這句是對的**
（live：損失寫不下去 → 事件不被接受、記在 `journalLossSince`；replay：損失寫不下去 → 列維持
PENDING、下一輪重試）。但第三條路徑（discard）並不對稱：它讓使用者的動作失敗。這是 I2 的內容，
不是那句註解的錯。

---

## Minor / nitpicks

### M1. `IngestRepository.kt:142-158` 有兩段連續的 KDoc，舊的那段沒有刪掉，而且與新的互相矛盾

```kotlin
/**
 * Pending rows of one source, decoded. …
 * A row that cannot be decoded is left alone rather than failed: …
 */
/**
 * One page of a source's pending rows, decoded. …
 * A row that cannot be decoded is skipped rather than failed … but it still moves the cursor …
 */
suspend fun pendingJournalForPackage(
```

第一段是分頁改寫前的殘留。Kotlin 只會把緊鄰宣告的那一段當 KDoc，第一段成了懸空註解。
兩段對同一件事的描述已經不一致（"left alone" vs "skipped … still moves the cursor"），
而「文件跑在程式前面」是本專案每一輪都被點名的問題。刪掉第一段即可。

### M2. `deleteAllExpired` 是死碼，而它會違反同一個檔案自己宣告的不變式

`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:119-120`：

```kotlin
@Query("DELETE FROM event_journal WHERE expiresAtEpochMs < :now")
suspend fun deleteAllExpired(now: Long): Int
```

它**沒有** `AND state != 'PENDING'`，也就是會刪掉 PENDING 列。我 grep 過整個非 build 的原始碼樹，
它**沒有任何呼叫點**（只有 KSP 產生的 `JournalDao_Impl.kt`）。`RetentionWorker.kt:106` 用的是
安全的 `deleteExpired`。

所以它今天不是缺陷，但它是一顆裝好的地雷：`Daos.kt:71` 的 KDoc 才剛寫下
「retention never deletes a `PENDING` row」，而這個方法就在同一個介面裡、名字更「順手」。
建議刪掉，或加上一段說明為什麼它存在、以及為什麼不能用在 journal 上。

（同一個 grep 也確認 `journalDao().clear()` 目前沒有 production 呼叫點。）

### M3. `progressed` 在結清失敗時的語意沒有測試

`CaptureCoordinator.kt:1126`（`if (settled.isFailure) return@withLock`）之後 `progressed` 保持原值。
我以 source trace 確認這是對的（見下方「已查證為真的主張」第 3 點）。但新測試
`CaptureCoordinatorTest.kt:1531-1533` 用

```kotlin
coEvery { h.ingest.pendingJournal(any(), any()) } coAnswers {
    if (served++ < 1) listOf("gen-old" to legacy) else emptyList()
}
```

只供應一次，第二輪必然拿到空 batch 而 `break`。因此**把 `progressed = true` 加在結清失敗的分支上，
這個測試仍會通過**。這一項是 C1 的同類，但風險低得多（`rounds++ < 100` 兜住了上界），所以列在 Minor。

### M4. 最後一頁剛好等於 `limit` 時會多送一次空查詢

`IngestRepository.kt:169`。行為正確（會終止），只是多一次 round-trip。實測見 C1 的 `limit=3` 那一列。
不需要改，記錄下來是因為 BRIEF 明確問了這一點。

### M5. `docs/TEST_MATRIX.md:11` 的「79 tests in `core:*`」不含 `core:designsystem` 的 8 個

79 = model 5 + parser 13 + identity 5 + reconcile 22 + analytics 34，逐項都對（見下方驗證），
但 `core:designsystem` 也是 `core:*`，它有 8 個測試（`MonogramTest` 6 + `TimeFormatTest` 2），
在同一份表格的第 16 列另外列出。這在本輪之前不算矛盾，但**本輪的 `docs/ARCHITECTURE.md:18`
才剛新增一句話**把 `core:designsystem` 明確歸類為 `core:*` 的成員（「`core:*` except
`core:designsystem` — which is an Android Compose library」），使得「79 tests in `core:*`」讀起來
更容易被誤解。建議寫成「79 tests in the JVM `core:*` modules」。

### M6. `Daos.kt:300-315` 對 set-only 模型的「分歧」舉例，我無法從程式碼證實

新 KDoc 說：

> The two diverge when the source sends a longer text and then the original again — the stored
> body never changes, so the reconciler sees a repost and the flag stays.

但若來源送出更長的文字，那應該是一次 `Decision.Revision`，而 `IngestRepository.kt:428` 的
`applyRevision(id, c.body, snapshot.eventId, truncationColumn(c.textTruncated))` 會**替換 body 並重算
旗標**，「the stored body never changes」的前提就不成立。這個舉例可能描述的是 reconciler 不把它
判為 Revision 的某個情況，但 KDoc 沒說清楚。命題本身（set-only 是 `Known` 路徑的規則、revision 會
重算回 null）我已驗證為真（見下），只是這個例子不精確。

---

## 我查證過、且證實為真的主張（含證據與證據的極限）

### 1. `f410809` 沒有引入新缺陷 —— 新的 settle 呼叫點是安全的

`CaptureCoordinator.kt:928` 的 `settleUnrecordedJournalLoss(System.currentTimeMillis())`：

- 它內部（`:519-527`）整段包在 `guarded {}` 裡，而 `guarded` 會吞掉所有 `Throwable`、只重拋
  `CancellationException`。所以它**只可能拋出 `CancellationException`**。
- 該呼叫位於 `:892` 的 try 內，其 `catch (e: Exception)`（`:945`）第一行就是
  `if (e is CancellationException) throw e`。`CancellationException` 繼承
  `IllegalStateException` → `RuntimeException` → `Exception`，所以會被接到再正確重拋。
- 它排在 `catch (e: VaultUnavailableException)`（`:931`）之前無妨，因為 `guarded` 已經吞掉了
  `VaultUnavailableException`，那個 catch 不會被這個呼叫觸發。
- `journalLossSince ?: return` 讓它在沒有待結清損失時是零成本，所以不會替每個被接受的事件
  在 pipeline lock 內加一次 DB 寫入。
- 讀寫 `journalLossSince` 的三處（`:520`、`:526`、`:961`）全都在 `pipelineMutex` 之下，
  成功後設回 null，所以「恰好一次」成立。

**限制**：這是 source trace，我沒有跑 mutation。

### 2. `f410809` 的新測試是有鑑別力的

`CaptureCoordinatorTest.kt:1443-1477`。關鍵在於測試把
`recordGap(…, GapReason.UNKNOWN, GapPrecision.EXACT, …)` stub 成拋例外，而
`settleUnrecordedJournalLoss` 寫的是 `GapPrecision.BOUNDED` —— 兩者不同，所以結清那一次會成功。
移除 `:928` 的新呼叫後，唯一能寫出這個 gap 的路徑是 policy load，而測試以
`policyLoads shouldBe loadsSoFar` 證明 policy 沒有再載入過，因此 gap 永遠不會出現、測試變紅。

**限制**：source trace + fake 語意推導，未跑 mutation。

### 3. replay 迴圈不會空轉，也不會讓任何一列被無限期餓死到失控

`CaptureCoordinator.kt:1090-1140`。逐案推導：

- 單列永久失敗：round 1 `progressed` 維持 `false` → `while` 條件不成立 → 退出。**不空轉。**
- 混合 batch `[A(失敗), B(成功)]`：round 1 B 成功 → `progressed = true`；round 2 batch 只剩 A →
  `progressed = false` → 退出。共 2 輪。
- 上界由 `rounds++ < 100` 兜住。

`progressed` 的處理是對的：結清失敗時**不**設 `progressed = true`，正確，因為那一列確實沒有前進。

**`runCatching` 內的 `CancellationException` 重拋是正確的**（BRIEF 第 2 點的最後一問）。
`runCatching` 捕捉的是 `Throwable`，因此它**會**吞掉 `CancellationException`；
`CaptureCoordinator.kt:1125` 的

```kotlin
settled.exceptionOrNull()?.let { if (it is CancellationException) throw it }
```

在 `isFailure` 被讀取**之前**就把傳播恢復了，所以協程取消不會被降級成「結清失敗、跳過這一列」。
順序也對：重拋在前、`if (settled.isFailure) return@withLock` 在後。
副作用是它同時吞掉 `Error` 的子類別（`OutOfMemoryError` 等），但這與同檔案 `guarded {}`
（`:1190-1197`，`catch (_: Throwable)`）既有的契約一致，不是本輪引入的新模式。

至於「A 會不會永遠卡在 PENDING」：會，而這是**刻意的設計**（commit message 明講「A failure leaves
the row exactly as it was — pending, payload readable, claim unspent」），是「寧可保住 payload」
與「寧可清掉」之間的取捨。取捨本身合理，我不列為發現。

值得記錄的是它的邊界條件：`pendingJournal(limit = 200)` 依 `receivedAtEpochMs` 排序，若有 ≥ 200 列
同時卡在結清失敗，較新的列將永遠進不了 batch（head-of-line blocking）。但要觸發這個，
`carriesUnrecordedLoss` 必須對 200 列都成立 —— 而該述詞（`:1243-1253`）只在 legacy 形狀上為真：
`TruncationFlag.LINES` 的 KDoc（`core/model/.../NotificationSnapshot.kt:59-67`）明寫
「Legacy only. … This release never writes it」，而 `MESSAGES` / `HISTORIC_MESSAGES` 那兩支要求
「旗標有設、但沒有任何一則訊息帶著 `text.truncated`」，那是現行 `SnapshotFactory` 不會產生的組合。
所以這條路徑只有 0.1.3 遺留列會走，實務暴露面很小。**我原本擔心的是現代事件也會走進來，
查證後不成立。**

### 4. `lossOnAccept` 與 `carriesUnrecordedLoss` 是互補的，沒有重複記錄

`lossOnAccept`（`:907`）的條件是 `snapshot.shape.truncated.any { it in DROPPED_MESSAGES }`；
`carriesUnrecordedLoss`（`:1244`）的第一行就是 `if (shape.truncated.any { it in DROPPED_MESSAGES }) return false`。
兩者的定義域嚴格互斥。加上 `claimLoss` 的 `AND lossRecorded = 0`，同一列不可能被記兩次。

### 5. PENDING 的出口列舉是完整的（BRIEF 第 5 點）

我列舉了 repository 裡對 `event_journal` 的每一個 mutation：

| DAO 方法 | 效果 | 是否為 PENDING 出口 | 有先結清嗎 |
|---|---|---|---|
| `insert` | 建立 PENDING | — | — |
| `setState`（經 `markJournal`）→ `DISCARDED`/`SOURCE_DISABLED`（`:981` commit fence） | 離開 PENDING | 是 | 有（live 在 acceptance；replay 在 `:1124`） |
| `setState` → `FAILED`/`PARSE_*`（`:1008`） | 離開 PENDING | 是 | 同上 |
| `setState` → `SKIPPED`（`:1013`） | 離開 PENDING | 是 | 同上 |
| `setState` → `COMMITTED`（`IngestRepository.kt:270,480`，在 `commit` 內） | 離開 PENDING | 是 | 同上 |
| `setState` → `FAILED`/`DECODE`（`IngestRepository.kt:181`） | 離開 PENDING | 是 | **不結清** — 已列為「payload 解不出來」 ✅ |
| `markJournalRetryable` 用盡 attempts → `FAILED` | 離開 PENDING | 是 | **不結清** — 已列為 issue #28 ✅ |
| `claimLoss` | 設 `lossRecorded`，維持 PENDING | 否 | — |
| `discardPending(packageName)` | 離開 PENDING | 是 | 有（`settleCarriedOverLosses` 在同一 transaction 內先跑） |
| `deleteExpired` | `AND state != 'PENDING'` | 否 ✅ | — |
| `deleteAllExpired` | 會刪 PENDING | **死碼，無呼叫點** | 見 M2 |
| `clear()` | 刪全部 | 無 production 呼叫點 | — |

**結論：四處（`Daos.kt:88-99`、`CaptureCoordinator.kt:1143-1153`、`docs/ARCHITECTURE.md:48-52`、
`docs/zh-Hant/ARCHITECTURE.md:46-48`）的敘述在活的程式碼路徑上是完整且正確的。**
前五個 `setState` 出口都是「經由 replay 或 live 抵達」，所以被「replay」這一支涵蓋；
真正不結清的只有 DECODE 與 issue #28 兩個，正如文件所說。唯一沒被涵蓋的是 `deleteAllExpired`，
而它是死碼（M2）。

另補一個一致的細節：一列 payload 解不開、其來源又正被停用時，
`pendingJournalForPackage` 的 `mapNotNull` 會跳過它，接著 `discardPending` 把它清掉，全程沒有 gap。
這與「an undecodable payload has nothing to settle」是同一類，不是額外的洞。

### 6. set-only 語意的新敘述是真的（BRIEF 第 7 點）

我列舉了每一個寫 `truncationFlags` 的路徑：

| 路徑 | 位置 | 行為 |
|---|---|---|
| 新訊息 insert | `IngestRepository.kt:386` | `truncationColumn(c.textTruncated)`，可為 null，從頭算 |
| `Known` 路徑 | `IngestRepository.kt:416` → `markTruncated` | `WHERE truncationFlags IS NULL`，**只設不清** |
| `Revision` 路徑 | `IngestRepository.kt:428` → `applyRevision` | 無條件 SET，`truncationColumn` 可回 null → **確實會清回 null** |
| backup import | `BackupService.kt:183, 365` | insert 新列，帶著紀錄自身的旗標 |

所以 KDoc 的兩句話都成立：「Only ever sets the flag, never clears it」對 `markTruncated` 為真；
「Set-only is not a database-wide invariant … A revision … recomputes the flag from scratch,
including back to null」對 `applyRevision` 為真（`applyRevision` 的第四個參數型別就是 `String?`）。
**BRIEF 問的「wording 是否對每一條寫入路徑為真」——是。**

使用者標籤：`Mappers.kt:68` `bodyTruncated = !truncationFlags.isNullOrBlank()` →
`Labels.kt:104` `truncationLabel` → `R.string.conv_truncated`。五語對照：

| locale | 字串 | 時態 |
|---|---|---|
| en | text was shortened | 過去式 ✅ |
| zh-Hant / zh-Hans | 文字被截短 | 中文無時態，讀作狀態，可接受 |
| ja | 本文が短縮されました | 過去／完成 ✅ |
| ko | 본문이 잘렸습니다 | 過去 ✅ |

**標籤與「historical」模型相符**（都是「曾經被截短」而非「螢幕上這一版是截短的」），
`Labels.kt:103` 的 KDoc「What a message had to give up before it was stored」也是過去導向。
**這不是針對 copy 的發現，也不是針對 model 的發現。** 唯一的不精確是 M6 的那個舉例。

### 7. 排序測試現在確實有鑑別力（BRIEF 第 4 點的前半）

`CaptureCoordinatorTest.kt:1420-1450`。harness 現在是有狀態的
（`pendingByPackage` 被 `pendingJournalForPackage` 讀、被 `discardPendingJournal` 清空），
所以我把 `CaptureCoordinator.kt:690-695` 的兩行對調後逐步推導：
`ingest.discardPendingJournal(pkg)` 先跑 → `pendingByPackage.remove(pkg)` → 接著
`settleCarriedOverLosses` 讀到空 list → 不呼叫 `recordCarriedOverLoss` → 不寫 gap →
`h.gaps.count { it.reason == MESSAGES_DROPPED.name && it.packageName == ENABLED_PKG } shouldBe 1`
**失敗**。round 35 C3 確實被修好了。

**限制**：這是依 fake 語意所做的決定性推導，不是實際跑過的 mutation（我在唯讀約束下不能改原始碼）。
fake 的語意夠簡單，我對這個結論有高度把握。

### 8. gap 站點的數字是對的

`QuietInboxDatabase.kt:103-105` 與 `CHANGELOG.md` 改成「sixteen … fifteen in the coordinator and
one in `HealthRepository` … ten are process-wide … The six that name a source」。逐一數過：

- coordinator 內 `health.recordGap` / `health.openGap` 共 **15** 處
  （行 271, 328, 382, 500, 523, 635, 656, 690, 707, 788, 862, 910, 939, 956, 1159）
- `HealthRepository.kt:45` 的 `PROCESS_RESTART` **1** 處 → 合計 **16** ✅
- 帶 `packageName` 參數的：690, 707, 862, 910, 956, 1159 = **6** ✅
- 其餘 9 處 + `HealthRepository.kt:45` = **10 process-wide** ✅

上一輪的「fifteen / nine」確實是錯的，這一輪改對了。

### 9. `lossRecorded` 的修正敘述是對的

`IngestRepository.kt:114`：`lossRecorded = lossOnAccept != null`。
所以它只對「這個版本在 acceptance 時記錄了損失」的事件為 1，一個沒有損失的普通事件維持 0。
CHANGELOG 從「for everything this release accepts」改成「for an event whose loss this release
recorded at acceptance」**是正確的修正**。

### 10. 測試數字全部重新推導過，全部相符（BRIEF 第 6 點）

我沒有採信 commit message，而是從 `**/build/test-results/**/TEST-*.xml` 的 `tests=` 屬性重新加總。
`./gradlew test --console=plain` 回報 `BUILD SUCCESSFUL`、`379 actionable tasks: 379 up-to-date`，
表示這些 XML 就是 HEAD 的結果（`44ce484` 只改了 instrumented 的 `MigrationTest` 與註解，
不影響 JVM 計數）。

| 文件宣稱 | 實測 | |
|---|---|---|
| `core:*` 79（model 5 / parser 13 / identity 5 / reconcile 22 / analytics 34） | 5 / 13 / 5 / 22 / 34 = 79 | ✅ |
| `parsers:apps` 45 | 4+8+8+8+7+10 = 45 | ✅ |
| `app` 5 | ReminderSchedulerTest 5 | ✅ |
| `MonogramTest` 6、`TimeFormatTest` 2 | 6 / 2 | ✅（`f410809` 的修正正確） |
| instrumented storage 38 | 5+2+9+1+4+2+11+4 = 38 | ✅ |
| `SourcePolicyTransactionTest` 11 | 11 | ✅ |
| `JournalLossTransactionTest` 9 | 9 | ✅ |
| `DeletionGraphTest` 5 / `SearchPagingTest` 2 / `MediaExportBoundTest` 1 | 5 / 2 / 1 | ✅ |
| `MessageBubbleSemanticsTest` 3 | feature/conversation androidTest = 3 | ✅（`f410809` 的修正正確） |
| instrumented backup 2、crypto 2 | 2 / 2 | ✅ |
| `BackupStagerTest` 21 | 21 | ✅ |
| `MediaReadTest` 10 | 10 | ✅ |
| `CaptureCoordinatorTest` 52 | 52 | ✅ |
| `VaultMaintenanceTest` 5 / `VaultRepositoryTest` 3 / `SuppressionRuleTest` 4 | 5 / 3 / 4 | ✅ |
| Analytics 8 / Search 5 / Onboarding 5 / Conversation 1 | 8 / 5 / 5 / 1 | ✅ |
| commit message 的 257 JVM tests | 全檔加總 = **257** | ✅ |

`docs/zh-Hant/TEST_MATRIX.md` 第 10-20 列逐項比對，與英文版數字**完全一致**
（79、5/13/5/22/34、45、5、6、2、3、2、38、11、9、…）。
`docs/SCOPE.md` 與 `docs/zh-Hant/SCOPE.md` 的 13 / 5 / 22 / 34 / 8 / 10 / 21 / 5 也都相符。

**這一輪的文件數字我找不到任何一個是錯的。** 上一輪六個錯誤都被修好了。唯一的措辭問題是 M5。

### 11. Gradle 閘門

- `./gradlew test --console=plain` → `BUILD SUCCESSFUL`，exit 0
- `./gradlew lint --console=plain` → `BUILD SUCCESSFUL`，exit 0

依 BRIEF 指示**沒有**執行 `connectedDebugAndroidTest`（其中一台裝置持有真實使用者資料）。
所有 instrumented 測試我都是讀原始碼推理的；`SourcePolicyTransactionTest` 與
`JournalLossTransactionTest` 新增的四個測試我逐一讀過，它們檢查的是真實 transaction 失敗後
資料庫的實際狀態（`sources.get(pkg)!!.enabled`、`ingest.isJournalPending(...)`、`allGaps()`），
**不是對 fake 的斷言**，具備鑑別力。

### 12. BRIEF 明列的範圍邊界，我確認沒有把它們當成本輪的迴歸

Codex I1（WhatsApp 在行分隔符上截斷、整列消失而無 gap）與 Codex I2（backup merge 在重複命中時
丟掉截斷證據）**確實仍然存在**於這個範圍 —— 我在讀 `truncationFlags` 的寫入路徑時看到
`BackupService.kt:183, 365` 的形狀與 I2 描述相符。依 BRIEF 指示，**我不把它們列為對本範圍的發現**，
只在此記錄它們尚未修復、且將由各自的 commit 處理。

---

## 給下一輪的建議

本輪要求的七項我都查了。若要我指出下一輪最可能出問題的地方：**`settleCarriedOverLosses` 的分頁
迴圈跑在 `sources.setEnabled` 的寫入 transaction 之內，而且持有 `pipelineMutex`**。目前每頁 200 列、
每列一次巢狀 `withTransaction`，一個長期暫停的來源累積數千列時，這個「使用者按一下開關」的動作會
在鎖內做數千次交易。這不是本輪的缺陷（分頁反而改善了記憶體），但它是下一個會被量測到的地方，
而 C1 指出的正是：今天沒有任何測試會在它退化時告訴你。
