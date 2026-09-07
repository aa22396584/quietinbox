# Round 35 — Claude subagent（獨立審查）

審查範圍：`022b99a..f7a09ed`（`b88e58e` 只動 review 文件，不影響判定）。
方法：對照 `git show v0.1.0/v0.1.1/v0.1.2/v0.1.3:<path>` 的實際原始碼、`./gradlew test`、
`./gradlew lint`、以及對每個新測試逐一問「production 端改什麼會讓它變紅」。
未執行 `connectedDebugAndroidTest`（依 brief 要求），instrumented 測試以閱讀原始碼判定。

**重要環境註記（影響本報告的邊界）**：審查開始時 HEAD 是 `b88e58e`、工作樹乾淨；審查途中
工作樹被本 session 的其他成員推進到 `3f34703`（兩個 commit 皆只含 review 文件），並且
`CHANGELOG.md`、`docs/TEST_MATRIX.md`、`docs/zh-Hant/TEST_MATRIX.md`、
`CaptureCoordinator.kt`、`CaptureCoordinatorTest.kt` 出現**未 commit 的修改**
（`git blame` 顯示 `Not Committed Yet`，內容標記為「round 35 agy I1」）。
撰寫報告期間那些修改又被 commit 成 `f410809`（HEAD 現為 `ahead 11`）。
本報告一律以 **`f7a09ed` 這個 commit 的內容**為判定對象；凡是後續 commit 已修掉的項目我會明講。

**行號基準**：文中的 `file:line` 以 **HEAD（`f410809`）** 為準。`f410809` 只動了
`CaptureCoordinator.kt`（兩個 hunk，各 +5 行）與三份文件，所以若要對回 `f7a09ed`：
該檔第 510–915 行區間為 `f7a09ed` 行號 **+5**，第 916 行之後為 **+10**，
第 509 行以前相同。其餘檔案行號兩者一致。

---

## Verdict: **REQUEST CHANGES**

先講結論的另一半，因為它比缺陷清單重要：**C2 這個修法的核心機制是對的，而且我沒有找到會
遺失資料或憑空捏造 gap 的執行期缺陷。** 判定 REQUEST CHANGES 的理由全部集中在
「宣稱與程式碼不符」與「commit 自己的標題論點沒有任何測試守著」這兩類——而這正是本專案
每一輪都當成 blocker 的兩類。

---

## Critical（push 前必須修）

### C1. `CHANGELOG.md:247-249` 描述的 schema 3→4 被這個 commit 自己改成假的

```
- Database schema 3 → 4: two additive nullable columns, `gap_interval.packageName` and
  `message.truncationFlags`, with `MIGRATION_3_4`, an exported `schemas/4.json` and a migration test
  that asserts existing rows survive with both columns null.
```

實際上（`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt:120-125`）
`MIGRATION_3_4` 現在有 **三個**欄位，而第三個 **不是 nullable**：

```kotlin
db.execSQL("ALTER TABLE gap_interval ADD COLUMN packageName TEXT")
db.execSQL("ALTER TABLE message ADD COLUMN truncationFlags TEXT")
db.execSQL("ALTER TABLE event_journal ADD COLUMN lossRecorded INTEGER NOT NULL DEFAULT 0")
```

`schemas/.../4.json` 也已寫入 `"lossRecorded" ... "notNull": true`。而且
`MigrationTest.kt` 的 KDoc 在**同一個 commit 裡**已經更新成「adds three columns / a pending
journal row … comes back unsettled」，測試本身也多斷言了 `lossRecorded` 為 0——所以這不是
「文件先行」，而是 CHANGELOG 這一段被同一次修改遺漏。這一段位於 `## [Unreleased]`
（`grep -n "^## " CHANGELOG.md` 顯示 `[Unreleased]` 在第 5 行、`[0.1.3]` 在第 267 行），
是 0.1.4 要出貨的正式敘述。

驗證方式：`git show f7a09ed -- CHANGELOG.md` 確認這一段未被觸碰；讀 `QuietInboxDatabase.kt`
與 `4.json` 的實際內容；讀 `MigrationTest.kt` 已更新的 KDoc 對照。

### C2. 「離開 PENDING 只有兩條路」這個不變式，敘述是錯的（而且其中一條被這個 commit 加寬了）

宣稱出現在四個地方：
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:76-78`
  （"whichever of the two paths out of PENDING reaches it first"）
- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1135-1136`
  （"the two ways a pending row leaves PENDING — replayed, or discarded with its source"）
- `docs/ARCHITECTURE.md:47-48`（"Both ways out of `PENDING`"）／`docs/zh-Hant/ARCHITECTURE.md:45`
- commit message（"Those are the only two — deleteExpired excludes PENDING and deleteAllExpired has no caller"）

brief 要求我實測這一點。`deleteExpired` 確實排除 PENDING
（`Daos.kt:96` `... AND state != 'PENDING'`），`deleteAllExpired`（`Daos.kt:100`）確實沒有
呼叫者；backup import 是 merge-restore，完全不碰 `event_journal`
（`grep -n "journal" platform/backup/src/main/kotlin/**/*.kt` 無任何命中）；
vault reset 是刪整個資料庫檔（`VaultRepository.kt:47-60`），使用者本人要求刪光，無損失可報。
到這裡 brief 點名的路徑都是乾淨的。

但還有**兩條**會清空 payload 的路：

1. `IngestRepository.kt:159-167` — `pendingJournal()` 解不出 payload 時
   `setState(row.eventId, "FAILED", "DECODE")`；`Daos.kt:57-63` 的 `setState` 對任何非 PENDING
   狀態都寫 `payload = ''`。**實際影響為零**（payload 讀不出來，本來就無從判定），但它是
   第三條路。
2. `IngestRepository.kt:177-181` — `markJournalRetryable` 在 `attempts >= MAX_ATTEMPTS`（3）時
   把列標成 `FAILED`，同樣清空 payload，**而且沒有任何地方為這一列寫 gap**
   （我對 `platform/capture`、`platform/storage`、`feature/health` 全域 grep 過 `FAILED`，
   只有 `markJournal(..., "PARSE_...")` 與這一處，兩者都不寫 gap）。

第 2 條之所以是 Critical 而不只是措辭問題：**這個 commit 把 `recordCarriedOverLoss(replay)`
放進 `replayJournal` 同一個 `try` 區塊裡**（`CaptureCoordinator.kt:1112-1120`），而 catch 的動作
就是 `markJournalRetryable`。也就是說，**單純只做記帳的 claim + gap 寫入失敗，現在會消耗掉
事件本體的 commit 重試額度**；三次之後該列變 FAILED、payload 被清空——結果是
**事件的倖存訊息永遠不會被儲存，而且 loss 也沒被記錄，健康頁上什麼都不會出現**。

這個底層的洞（重試耗盡 = 靜默丟棄且無 gap）是既有的，不是 f7a09ed 造成的；但
f7a09ed 新增了一個觸發它的來源，而且 `IngestRepository.kt:174-175` 的 KDoc 仍寫著
「Transient errors therefore never lose an accepted event」——對持續性錯誤（磁碟滿）而言
這句話是假的。`IngestRepository.kt:130-133` 新增的 `claimEventLoss` KDoc 說
「is tried again by the next pass」也只在**再兩次**之內為真，之後證據就被銷毀了。

要修的至少是：把四處敘述改成正確的（例如「所有離開 PENDING 的路徑都在 settle 之後」，
並列出 DECODE 與重試耗盡兩個例外及其理由），並修正 `IngestRepository.kt:174-175` 的
KDoc。重試耗盡不寫 gap 這個洞本身，建議另開 issue，不要塞進這一輪。

驗證方式：`grep -rn "PENDING\|deleteAllExpired\|discardPending\|journalDao()"` 全域列舉；
逐一讀 `Daos.kt:57-100`、`IngestRepository.kt:155-181`、`CaptureCoordinator.kt:1105-1125`；
backup / reset 路徑以 grep 與閱讀原始碼排除。全部為 source-trace，未實測。

### C3. 這個 commit 的標題論點「先 settle 再 discard」沒有任何測試會因為它反轉而變紅

commit message 明講：「Both in this transaction, and in this order … Discarding first, or
afterwards as a second write, loses it.」對應
`CaptureCoordinator.kt:689-694`：

```kotlin
health.openGap(now, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, now, packageName)
settleCarriedOverLosses(packageName)      // :693
ingest.discardPendingJournal(packageName) // :694
```

兩層測試都證不到這個順序：

- JVM 端 `CaptureCoordinatorTest`「pending rows discarded with their source are settled while
  their payload can still be read」：`ingest` 是 `mockk(relaxed = true)`
  （`CaptureCoordinatorTest.kt:125`），`pendingJournalForPackage` 被固定 stub 成
  `returns listOf(legacy)`，`discardPendingJournal` 是 relaxed mock、沒有任何副作用。
  把 `:693` 和 `:694` 對調，這個測試**照樣綠**。
- Instrumented 端 `SourcePolicyTransactionTest.disablingASourceSettlesItsPendingRowsAndDiscardsThemTogether`
  沒有呼叫 `CaptureCoordinator.setSourceEnabled`，而是在測試裡**自己重寫一份 lambda**：

  ```kotlin
  sources.setEnabled(pkg, false) {
      health.openGap(...)
      for (s in ingest.pendingJournalForPackage(pkg)) ingest.claimEventLoss(s.eventId) { recordLoss() }
      ingest.discardPendingJournal(pkg)
  }
  ```

  它斷言的是它自己寫的順序，不是 production 的順序。production 反轉一樣綠。

這正是 round 34 I1 的同一類發現（「那些測試是在測 fake」）被換一個位置重演。commit 的
負向控制清單裡也沒有「discard 排在 settle 之前」這一條——七個負向控制我逐條對照過，
沒有一條涵蓋這個順序。

修法建議（擇一即可）：讓 instrumented 測試改成把 `CaptureCoordinator` 真的接上真實
repository 跑一次 disable；或在 `SourcePolicyTransactionTest` 裡把 lambda 換成
「先 discard 再 settle」寫一個必須失敗的對照；或者最小成本——在 JVM Harness 裡讓
`discardPendingJournal` 的 stub 真的把 `pendingJournalForPackage` 的回傳清成 `emptyList()`，
順序反轉就會少一個 gap。

驗證方式：讀 `CaptureCoordinatorTest.kt:124-132`（Harness 的 mock 宣告）、
`:251`（`pendingJournalForPackage` 固定 stub）與新測試本體；讀
`SourcePolicyTransactionTest.kt` 新增的兩個 `@Test`。source-trace only——我沒有改
production 程式碼去實測（本輪為唯讀審查）。

---

## Important（push 前應該修）

### I1. gap site 數量少算一處：實際是十六處（十處 process-wide），不是十五／九

`QuietInboxDatabase.kt:103-107` 與 `CHANGELOG.md:160-161` 都說
「of the fifteen places that record a gap, nine are process-wide … six name a source」。

我實際列舉（`grep -rn "\.recordGap(\|\.openGap(" --include="*.kt"`，排除測試）：
`CaptureCoordinator.kt` 的 270、327、381、499、522、634、655、689、706、787、861、909、937、954、1141
= **15 處**，其中帶 `packageName` 的是 689、706、861、909、954、1141 = **6 處**，正確。

但還有第 16 處：`HealthRepository.kt:44-46`，`startSession` 在偵測到 dangling session 時
`recordGap(null, now, GapReason.PROCESS_RESTART, GapPrecision.UNKNOWN, now)`——這是一個
會出現在健康頁上的真實 gap，而且是 process-wide。因此正確數字是
**十六處、十處 process-wide、六處具名來源**。
（另有 `DemoDataRepository.kt:282,291` 兩處，但那是 debug-only 的示範資料，不算。）

這個數字在這一輪剛從「七／五」修成「十五／九」，方向對，但仍差一。

### I2. `pendingJournalForPackage` 是唯一沒有上限的 pending 查詢，而且跑在政策 transaction 裡

`Daos.kt:69-70`：

```kotlin
@Query("SELECT * FROM event_journal WHERE state = 'PENDING' AND packageName = :packageName")
suspend fun pendingForPackage(packageName: String): List<EventJournalEntity>
```

其他每一個 pending 讀取都有上限（`Daos.kt:46` `LIMIT :limit`、`:54` 同樣，
`IngestRepository.pendingJournal` 預設 200，`replayJournal` 還特地以 batch 迴圈分次抽乾，
註解寫著「a long lock-out can leave > 200 rows」）。而
`IngestRepository.kt:148-153` 的 `pendingJournalForPackage` 會把該來源**全部**待處理列取出並
逐一 JSON 反序列化，而且是在 `SourceRepository.update` / `remove` 的 `withTransaction` 之內
（`SourceRepository.kt:65-73`、`:97-110`），整段又握著 `pipelineMutex`
（`CaptureCoordinator.changeSourcePolicy`）。

暫停中的來源會依設計持續累積 PENDING 列（`replayJournal` 以 `excludingPackages = pausedPackages`
排除它們，`deleteExpired` 又不清 PENDING），所以「很多列」不是假想情境。使用者停用一個
長期暫停的來源時，這會在單一 SQLite 寫入交易裡做完整解碼＋N 次 claim，期間 live capture 全部
擋住。建議比照其他路徑加 LIMIT + 迴圈，或至少在 KDoc 說明為何此處可以無上限。

source-trace only。

### I3. 記住的 loss 只掛在 policy load 上，磁碟恢復後可能永遠不寫

`CaptureCoordinator.kt:962` 在 journal insert 與 fallback gap 都失敗時把時間點記進
`journalLossSince`（`:199` 的 `@Volatile` 記憶體欄位），而唯一的寫出點是
`settleUnrecordedJournalLoss`（`:517-526`），它只從 `settleColdStartGap`（`:494-516`）被呼叫，
而後者只從 `loadSourcePolicy`（`:465-484`）被呼叫。`loadSourcePolicy` 只在
`changeSourcePolicy`（使用者改來源設定）、來源清單 flow 有新值、以及
`processJournaled`/`process` 中 `!sourcesLoaded` 時才跑。

也就是說：**磁碟空間恢復、capture 一切正常，但使用者沒有再碰任何來源設定時，這筆 loss 會
無限期留在記憶體裡，process 一死就徹底消失。** 這與 CHANGELOG 對這一項的敘述
（「never absent」的修補）不相稱：它把「可能不存在」從一個窗口搬到另一個窗口。

**這一項在我撰寫報告期間已被修掉並 commit（`f410809`「Round 35 agy I1: a remembered loss no
longer waits for a policy change」）**：`CaptureCoordinator.kt:923-927` 在 journal 接受事件之後
直接呼叫 `settleUnrecordedJournalLoss`，`CaptureCoordinatorTest` 多了一個對應測試
（斷言 `policyLoads` 沒有增加，所以真的是 acceptance 觸發的）。我在看到那份修改之前獨立走到
同一個結論，這裡記錄下來只是為了讓本輪的 finding 集合完整；就 `f7a09ed` 而言本項成立，
就目前的 HEAD 而言已消。

順帶一個未被修到的小點：`:522` 的延後寫入沒有帶 `packageName`
（`health.recordGap(since, now, GapReason.UNKNOWN, GapPrecision.BOUNDED, now)`），
而它替代的即時 fallback（`:954`）是帶的。同一個損失，晚寫就變成不知道屬於哪個 app，
健康頁上會少一個使用者本來看得到的資訊。

### I4. 兩個測試數字在 `f7a09ed` 當下是錯的（已在 `f410809` 修正）

- `docs/TEST_MATRIX.md:16` / `docs/zh-Hant/TEST_MATRIX.md:16`：`MonogramTest` **(4)**，
  實際 `core/designsystem/src/test/.../MonogramTest.kt` 有 **6** 個 `@Test`
  （JVM 執行結果 XML 也是 6）。這個「4」是 round 19（`c90e75f`）寫下的，
  測試在 `08c2b10` 之後變成 6，文件從未跟上。
- `docs/TEST_MATRIX.md:20` / `docs/zh-Hant/TEST_MATRIX.md:20`：
  `MessageBubbleSemanticsTest` **(2)**，實際檔案有 **3** 個 `@Test`
  （round 31 `ed98b49` 起就是 3）。而**同一個 commit 的 message 自己寫的是
  「conversation 3 on emulator-5556」**——commit 內部就自相矛盾。

兩者在我審查期間都已被 `f410809` 一併改成 6 / 3（`CHANGELOG.md` 對 I3 的敘述也在同一個
commit 改好了）。既然 f7a09ed 明確聲稱「M1 and M2, all of them … the SCOPE counts, and the
English TEST_MATRIX capture row」掃過這兩份檔案，這兩個殘留仍屬本輪 finding；就目前的 HEAD
而言已消。**注意 C1（CHANGELOG:247 的 schema 段落）與 I1（gap site 數量）`f410809` 都沒有動到，
仍然成立。**

### I5. `markTruncated` 的 KDoc 把一條非全域的規則寫成全域

`Daos.kt:281-288`：「Only ever sets the flag, never clears it … a message cannot become less
lost than it already was」。但同一個檔案的 `applyRevision`（`Daos.kt:278-279`）在
`IngestRepository.kt:406`（`Decision.Revision` 分支）會用
`truncationColumn(c.textTruncated)` **覆寫**該欄位，值為 `false` 時就是寫回 null——
訊息確實可以「變得沒那麼 lost」。

這在語意上是對的（body 換了，標籤當然要重算），但 KDoc 的措辭讀起來像是資料庫層的
不變式，而它不是。建議把範圍限縮成「在 `Decision.Known` 這條路上」。

同一段還有一個值得作者判斷的邊界（brief 第 6 點問的「哪裡 set-only 是錯答案」）：
`WhatsAppParser.kt:75` 的 `cutInsideLastRow` 依作者自己的註解只是**上界推測**
（"still only an upper bound"）。它算出的 `textTruncated=true` 現在可以透過
`markTruncated` **永久**蓋在一個當初以「完整」寫入的列上，而且除非 body 之後被 revision
換掉，否則永遠拔不掉。要走到這一格需要「同一列 body 逐字相同、但新快照說被切」，
條件相當窄（我推演過幾種 WhatsApp 群組切分情境，都需要 Bob 的原文在兩次觀測之間變長），
所以我放在 Important 的尾巴而不是 Critical——但「上界猜測可以永久蓋掉一次真實觀測」這件事
和專案「honest labels」的原則有張力，值得在 CHANGELOG 講清楚。

---

## Minor / nitpicks

1. **`MigrationTest.kt` 檔尾被弄壞**：`git show f7a09ed` 顯示
   `-}` → `+\n+}` 且 `\ No newline at end of file`。`od -c` 確認檔案現在以 `}\n\n}` 結尾、
   沒有換行。多一個空行、少一個結尾換行，兩者都是這個 commit 引入的。lint 沒抓到
   （本專案沒有 ktlint gate）。
2. **import 順序**：`CaptureCoordinator.kt:16-18` 把
   `TruncationFlag` 放在 `NotificationShape` 之前，破壞了字母序（第 17 行的
   `NotificationShape` 是這次新增的，插錯位置）。
3. **`GapReason.MESSAGES_DROPPED` 用來記 `LINES` 的損失**：`CaptureCoordinator.kt:1141-1149`
   對 `LINES`（掉行）與 `MESSAGES`（掉訊息）寫的是同一個 reason。使用者在健康頁上會看到
   「訊息被丟棄」，實際是 InboxStyle 的行。既有的 `lossOnAccept`（`:909`）也是這樣，所以是
   一致的，但既然這一輪的主題就是「用同一個名字講兩件事」的代價，值得順手記一筆。
4. **`settleUnrecordedJournalLoss` 對「同一次 outage 一個區間」的形狀**（brief 第 7 點）：
   `:962` 只在 `journalLossSince == null` 時記錄，所以第一筆失敗到最後成功寫入之間會被合成
   **一個** BOUNDED 區間。這個區間會覆蓋到中間其實成功擷取的時段——是「寧可多報不可少報」
   的方向，與專案原則一致，但 CHANGELOG 把它寫成「One interval covers the outage」時
   沒有說出這個過度覆蓋，可以補一句。
5. **`VaultUnavailableException` 路徑確實與泛型路徑互斥**（brief 第 7 點的另一半）：
   `CaptureCoordinator.kt:930` 的 `catch (e: VaultUnavailableException)` 排在 `:945` 的
   `catch (e: Exception)` 之前，`VaultUnavailableException` 是 `IllegalStateException` 子類
   （`DatabaseHolder.kt:35`），Kotlin 依序比對，所以鎖住的金庫走的是 `vaultGapSince`
   那條既有義務，不會落到 `journalLossSince`。**這一點 commit 說得對。**
6. **工作樹漂移**：審查期間 `main` 從 `ahead 8` 變成 `ahead 10`，並帶著未 commit 的修改。
   若 round 35 的三份報告是要拿來對同一個樹狀態下判斷，建議下一輪把 fix 與 review 的
   時序分開，否則各 reviewer 看到的不是同一份程式碼。

---

## 我查證過、確認為真的宣稱（含證據與其限度）

### 1. `carriesUnrecordedLoss` 的判定基礎是正確的 —— 這是我這輪最重要的正面結論

brief 第 3 點要求我自己對 `git show v0.1.3:...SnapshotFactory.kt` 驗證。我對
**v0.1.0、v0.1.1、v0.1.2、v0.1.3 四個 tag 全部**檢查過，四者的 `bound()` 完全相同：

```kotlin
private fun bound(list, flag, truncated, self, selfName): List<MessagingMessageShape> {
    if (list.size > Limits.MAX_MESSAGES) truncated += flag      // :152  成因一
    return list.takeLast(Limits.MAX_MESSAGES).map { m ->
        val text = BoundedText.of(m.text)
        if (text?.truncated == true) truncated += flag          // :156  成因二
        ...
        senderName = person?.name?.let { BoundedText.of(it, 256) },   // :161 不升旗
```

- **`MESSAGES` 只有兩個成因**，`:156` 的成因二只在**倖存的**訊息上觸發（`map` 跑在
  `takeLast` 之後），所以「沒有任何倖存訊息自身被截短 ⇒ 成因二被排除 ⇒ 只剩成因一」
  的推論成立。`CaptureCoordinator.kt:1203-1206` 的判定式與此完全對應。
- **`senderName` 截短不會升起任何旗標**（四個 tag 皆然，本版
  `SnapshotFactory.kt:154` 亦然）——brief 問的這一點，答案是「不相關」，判定式不需要處理它。
- **`HISTORIC_MESSAGES` 分支確實對稱**：四個 tag 都是
  `bound(historicMessages, TruncationFlag.HISTORIC_MESSAGES, ...)`，同一個函式、同樣兩個成因，
  而 `CaptureCoordinator.kt:1208-1209` 的寫法與 `MESSAGES` 分支一致。
- **`LINES` 在四個 tag 都只由 `arr.size > Limits.MAX_TEXT_LINES` 升起**
  （`SnapshotFactory.kt:56`），過長的單行由 `BoundedText.of` 靜默截短、不升旗。
- **判定式讀得到 `truncated` 這個欄位**：`BoundedText` 在 v0.1.0 起就是
  `val truncated: Boolean = false` 的 `@Serializable` 欄位，而 v0.1.3 的
  `IngestRepository` 用的是 `Json { ignoreUnknownKeys = true; encodeDefaults = true }`
  （`git show v0.1.3:...IngestRepository.kt` 第 57 行），所以 `"truncated":true` 必然出現在
  舊 payload 裡。（即使 `encodeDefaults` 是 false，`true` 也不是預設值，一樣會被寫出。）
  —— 這是我認為最容易出錯、也最該明講已驗證的一點：**如果這個欄位當年沒被序列化，
  每一筆舊的 `MESSAGES` 都會被誤判成 dropped，憑空製造 gap。它沒有。**
- **這一版寫不出符合判定式的 payload**：`LINES` 本版永不寫出
  （`SnapshotFactory.kt:56` 已改為 `LINES_DROPPED`，全域 grep 確認 `TruncationFlag.LINES`
  在 main source 只剩判定式本身讀它）；本版的 `MESSAGES` 只由倖存者被截短觸發
  （`SnapshotFactory.kt:152-156` 已拆成 `textFlag` / `droppedFlag` 兩個參數），
  所以 `none { it.text?.truncated == true }` 必為 false。加上
  `lossRecorded` 欄位在 `IngestRepository.kt:112` 隨 insert 一起設定，
  **旗標判定與欄位是雙保險**，欄位那一層是結構性的、不依賴讀對旗標。
- 四個 tag 的 `TruncationFlag` enum 都是
  `{ TITLE, TEXT, BIG_TEXT, LINES, MESSAGES, HISTORIC_MESSAGES, ACTIONS, EXTRAS, URI }`，
  沒有任何 `*_DROPPED`，所以 `CaptureCoordinator.kt:1201` 的提早 return
  （`shape.truncated.any { it in DROPPED_MESSAGES }`）不可能誤傷舊 payload。
- 旗標以名稱序列化（kotlinx enum 預設），`LINES_DROPPED` 插在 `LINES` 與 `MESSAGES` 之間
  不影響任何既存 payload；`message.truncationFlags` 存的也是名稱
  （`IngestRepository.kt:45`）。

**限度**：以上全部是原始碼比對，不是在裝置上重放真實 0.1.3 payload。要真正閉環，需要一個
「拿 v0.1.3 的 `SnapshotFactory` 產生的 JSON 餵給本版判定式」的測試，目前沒有。

### 2. 例外一次性（brief 第 4 點）的四個子問題

- **不斷失敗的 replay**：`Daos.kt:80` 的
  `UPDATE ... WHERE eventId = ? AND lossRecorded = 0 AND state = 'PENDING'` 是單一原子敘述，
  `IngestRepository.kt:134-141` 把它與 `writeGap()` 包在同一個 `withTransaction`。
  gap 寫失敗 ⇒ 整個交易回滾 ⇒ claim 未消耗。`JournalLossTransactionTest`
  的 `aClaimWhoseGapCannotBeWrittenIsNotSpent` 在真實金庫上斷言這一點，**不是 theatre**。
- **並行的 disable**：`replayJournal` 的 claim 在 `pipelineMutex.withLock` 內
  （`CaptureCoordinator.kt:1108-1122`），`setSourceEnabled` 走
  `changeSourcePolicy` → 同一把 `pipelineMutex`，兩者序列化；SQL 層另有條件 UPDATE 兜底。
- **claim 與 commit 之間 process 死掉**：兩者同一個 SQLite 交易，未 commit 的交易在重開時
  整段回滾，claim 與 gap 一起消失，下次再來。正確。
- **巢狀 `withTransaction`**：`claimEventLoss` 的 `db.withTransaction` 位於
  `SourceRepository.update` / `remove` 的 `withTransaction` 之內。Room 的
  `withTransaction` 以 coroutineContext 中的 `TransactionElement` 判斷是否已在交易中，
  巢狀時重用同一個 transaction dispatcher 而不另取執行緒；`alsoInTransaction` 是直接在
  外層 block 裡 `invoke()` 的 suspend lambda，context 未被切換，所以不會死鎖。
  內層失敗未標成功時，Android `SQLiteDatabase` 的巢狀交易會讓外層 `endTransaction` 整段
  回滾——**外層回滾是完整的**。`SourcePolicyTransactionTest.aDiscardWhoseSettlementFailsLeavesTheRowAndTheSourceWhereTheyWere`
  在真實金庫上證了這一點。
- `HealthRepository.recordGap` / `openGap`（`:53-54`、`:84-86`）是裸 insert、自己不開交易，
  作為 `writeGap` 傳入是安全的。

### 3. schema 4 就地修改（brief 第 5 點）

- `git ls-tree v0.1.3 platform/storage/schemas/...` 只有 `1.json`、`2.json`、`3.json`
  —— **0.1.3 確實出貨 schema 3**。
- `git tag --contains 9e379d3` 回傳空 —— **沒有任何 tag 含引入 schema 4 的那個 commit**。
- `DatabaseHolder.kt:109-112` 只有 `.addMigrations(*MIGRATIONS)`，
  `QuietInboxDatabase.kt:12` 明寫禁止 `fallbackToDestructiveMigration()`。

**結論：對使用者而言就地修改是對的**，加一個 5 版反而是替沒有人會走的路徑寫遷移。
**代價要說清楚**：任何已經跑過舊版 schema 4 的裝置或模擬器，其
`room_master_table.identity_hash` 是 `0ccd485ff125d560c5c524cd1fc65202`，
而新的是 `485f8d770a42385710a7adda5f3a9ab4`；版本號同為 4 ⇒ 不觸發遷移 ⇒ Room 直接
`IllegalStateException: Room cannot verify the data integrity`，而且沒有 destructive fallback。
專案自己的 `QuietInbox_Phone` / `Foldable_Test` / `emulator-5556` 在 round 33、34 的
device walkthrough 中很可能已經跑過舊的 schema 4——下一次裝 debug build 之前需要
`adb shell pm clear`。這件事目前 CHANGELOG 與 `docs/RELEASE.md` 都沒寫。
（Round 33 對前兩個欄位做過同樣的事，所以這是慣例延續，不是新決定。）

### 4. commit message 的量化宣稱

- **「255 JVM tests」為真**。`./gradlew test --console=plain` 退出碼 0；
  我從 33 份 `TEST-*.xml` 解析出 **256** 個非 skipped 測試，其中 1 個是工作樹**未 commit**
  的 agy I1 測試（我用測試名稱確認了）。扣掉即 **255**。
  分佈：`core:*` 79（model 5 / parser 13 / identity 5 / reconcile 22 / analytics 34，
  與 `TEST_MATRIX.md:11` 的分項逐一相符）、`parsers:apps` 45、`app` 5、
  `platform:capture` 50、`platform:backup` 24、`platform:storage` 12、`platform:media` 10、
  `platform:crypto` 3、`core:designsystem` 8、feature 各模組 8/1/5/5。零失敗。
- **「lint … clean」為真**：`./gradlew lint --console=plain` 退出碼 0。
- **「storage instrumented 34」為真**：以 `@Test` 計數
  MediaExportBound 1 + DemoData 2 + Migration 4 + DeletionGraph 5 + VaultRoundTrip 4 +
  JournalLoss 7 + SearchPaging 2 + SourcePolicy 9 = **34**，
  且 `TEST_MATRIX.md:18` 的每一個分項數字（5 / 2 / 1 / 9 / 7）都對得上。
- **`docs/SCOPE.md` 與 `docs/zh-Hant/SCOPE.md` 的 `CaptureCoordinatorTest`(50) 為真**
  （XML 51 − 1 未 commit = 50）。
- **「conversation 3」與它自己改的 `TEST_MATRIX`(2) 打架** —— 見 I4。

### 5. 各項 M1/M2 更正我逐一查過，內容屬實

- **backup 宣稱**：`BackupStager` 先驗 manifest 版本再解碼，所以附加欄位不會讓
  0.1.3 讀得懂 schema-4 封存 —— 敘述正確。
- **「nothing downstream reads it」的收回**：`StandardParser.kt:38` 與
  `AppParser.kt:170,192` 確實仍以 `shape.truncated.isNotEmpty()` 產生 `TRUNCATED_INPUT`；
  更正後的措辭（「非空集合會升 `TRUNCATED_INPUT`，但沒有東西看它裝的是哪一面旗」）正確。
  同時這也證實 **`LINES` → `LINES_DROPPED` 改名對 parser 與 UI 無影響**：
  InboxStyle 每一行的截短走的是 `StandardParser.kt:190` 的 `line.truncated`，
  UI 端 `Labels.kt:104-105` 走的是 `Message.bodyTruncated`，
  由 `Mappers.kt:68` 的 `!truncationFlags.isNullOrBlank()` 算出，與旗標名稱無關。
- **重新加入來源「opens and closes nothing」的收回**：`loadSourcePolicy` 內的
  `reconcileSourceGaps`（`CaptureCoordinator.kt:440-452`）確實會關掉與設定矛盾的列，
  且在 upsert 交易之外 —— 更正後的敘述正確。
- **`Entities.kt` 與 `CaptureHealth.kt` 的註解**已改成 per-message / per-site 的語意，屬實。

### 6. I1（WhatsApp）與 I2（repost）本身是好的修法

- `WhatsAppParser.kt:75` 的 `cutInsideLastRow` 邏輯正確：切點落在換行上時，最後一列完整。
  兩個新測試 `WhatsAppParserTest`（含 `prefix.length shouldBe 4096` 這個把邊界釘死的斷言）
  互為正負控制，production 端把 `cutInsideLastRow` 改回 `truncatedBody` 就會有一個變紅。
  這**不是** theatre。
- `VaultRoundTripTest.aRepostThatKnowsTheBodyWasCutMarksARowThatSaidItWasWhole`
  跑在真實金庫上、用真的 `StandardParser` / `IdentityResolver` / `Reconciler`，
  而且**先斷言 `r2.decisions.single().shouldBeInstanceOf<Decision.Known>()`**
  才斷言結果——把 `markTruncated` 從 `IngestRepository.kt:396` 拿掉就會變紅。
  這是本輪品質最高的一個測試。
- `Fixtures.message(..., truncated)` 確實寫進 `BoundedText(it, truncated = truncated)`
  （`core/testing/.../Fixtures.kt:162-165`），所以那個「ambiguous 留白」的負向控制
  真的是在測 `it.text?.truncated == true` 這條分支，不是在測 fixture。

### 7. 關於 JVM 端 claim 測試「是不是 theatre」

`CaptureCoordinatorTest` 的 Harness 用一個 `Collections.synchronizedSet(HashSet())`
假造 `claimEventLoss` 的一次性——所以那些「exactly once」的 JVM 斷言**確實是在測 fake**。
但作者在同一段 KDoc 裡把這件事講明了：「That exactly-once is SQL, and SQL is decided on a
real vault in `JournalLossTransactionTest`. What these tests decide is the other half: whether the
coordinator asks for the claim at all」，而 `JournalLossTransactionTest` 的四個新
`@Test` 確實都跑真實 SQL、都會因為拿掉 `WHERE lossRecorded = 0 AND state = 'PENDING'` 而變紅。
**這是對 round 34 I1 的正確回應，我不把它算成 finding。** 分層講清楚的假物件是工具，
沒講清楚的才是 theatre。真正的 theatre 是 C3 那一條——順序這件事兩層都沒人守。

---

## 我沒有做到的事（限度聲明）

- 沒有執行任何 `connectedDebugAndroidTest`（brief 明令禁止），instrumented 測試全部以閱讀
  原始碼判定；因此「這 34 個 instrumented 測試會通過」不是我驗證過的事實。
- C2、C3、I1、I2、I5 全部是 source-trace，沒有實際反轉 production 程式碼跑測試證明
  （本輪為唯讀審查，我沒有修改任何 repository 檔案，除了本報告）。
- 沒有實際重放一筆真實的 0.1.3 journal payload；第 1 節的結論建立在四個 tag 的原始碼比對上。
- `./gradlew test` / `lint` 跑的是**含未 commit 修改**的工作樹，不是 `f7a09ed` 本身；
  測試數字我以「XML 256 減去可辨識的 1 個未 commit 測試」還原到 255。
