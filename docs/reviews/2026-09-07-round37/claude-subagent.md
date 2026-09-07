# Round 37 — Claude subagent（獨立審查）

**審查範圍**：`44ce484..4797b13`（`f3d4407` 修正、`86c4401` 文件、`4797b13` 測試）
**審查時間**：2026-09-07
**審查者**：Claude Opus 5 (1M context)，獨立 subagent
**執行環境**：`emulator-5556`（`QuietInbox_Phone`, API 36），`ANDROID_SERIAL` 全程綁定；工作樹在
`21ccbc5`（round-37 brief）。審查期間我對產品碼做過 12 次暫時性突變，**每次都在同一個 python
程序的 `finally` 裡還原**，結束時 `git status --short` 只剩另一位 reviewer 的
`docs/reviews/2026-09-07-round37/codex-gpt-6-astra.md`（未追蹤，我沒碰）。

---

## Verdict：**REQUEST CHANGES**

**這個模型是對的。** 我沒有找到任何會遺失資料的缺陷，三值 `lossRecorded` 的狀態機是封閉的，
row value 游標在這個 app 上是安全的（下面有實測），以「證明」而非計時器來武裝重試的判斷我同意，
而且我可以說明為什麼它比計時器好（見〈模型問題〉）。

REQUEST CHANGES 的理由**只有一條**，而且正是 BRIEF 第 6 點自己點名的那條：**這次 commit 新引入的
兩個守衛，各自都可以用一行改動拆掉而整套測試全綠**；再加上 round-36 C1 那條我上一輪點名的
突變，**至今仍然是綠的，而且我證明了它會靜默遺失資料**。三條都是我實跑出來的，不是推理。

| 突變 | 結果 | 抓到的測試 |
|---|---|---|
| `settleCarriedOverLosses` 的分頁 `while` 迴圈刪掉，只讀一頁 | **GREEN（存活）** | 無 |
| `deferLoss` 拿掉 `AND lossRecorded = 0` | **GREEN（存活）** | 無 |
| replay 的 `if (!isReplayCandidate(...))` 改成無條件 | **GREEN（存活）** | 無 |

這三條之外，BRIEF 附的 14 條負向控制我抽查了 6 條（#3 #4 #5 #6 加上 DAO 的四個述詞），**全部確實變紅**，
那張表是可信的。修正成本是三個測試，沒有任何產品碼需要改。

---

## 我實際跑了什麼（先說證據，再說結論）

| 項目 | 指令 | 結果 |
|---|---|---|
| JVM 全套 | `./gradlew test` | **263 tests / 0 failures**，與 BRIEF 宣稱相符（我用 python 掃過所有 `TEST-*.xml` 加總） |
| instrumented storage | `ANDROID_SERIAL=emulator-5556 ./gradlew :platform:storage:connectedDebugAndroidTest` | **47 tests / 0 failures**，2m21s |
| instrumented conversation | `ANDROID_SERIAL=emulator-5556 ./gradlew :feature:conversation:connectedDebugAndroidTest` | **5 tests / 0 failures**（含本次新增的截短標籤兩條，BRIEF 控制 #14） |
| `:platform:capture:test` | `--rerun-tasks` | **58 tests / 0 failures**，33s |
| JVM 突變 | 7 條，每條完整重跑 `:platform:capture:test` | 4 紅 3 綠（見上表與 C1/C2） |
| instrumented 突變 | 5 條 DAO 述詞，每條完整重跑 47 個 instrumented 測試 | 4 紅 1 綠 |
| SQL 語意 | python `sqlite3`（3.53.0），依 `4.json` 的 `createSql` 建表 | row value 與舊 OR 形式在 6 種 page size × 帶平手的 7 列資料下**逐列相同** |
| 查詢計畫 | `EXPLAIN QUERY PLAN` | `SEARCH … USING COVERING INDEX …(packageName=? AND state=? AND lossRecorded=? AND (receivedAtEpochMs,eventId)>(?,?))` |
| SQLCipher 內含的 SQLite | 解開 `sqlcipher-android-4.18.0.aar`，`strings jni/arm64-v8a/libsqlcipher.so` | **3.53.4**（2026-07-24 build） |
| schema 4 是否出貨過 | `git ls-tree v0.1.3 platform/storage/schemas/…/` | 只有 `1.json 2.json 3.json` —— **v4 從未出貨** |
| Room 的 identity 檢查 | `strings` `room-runtime-android/2.8.4` 的 `classes.jar` | 確有 `Room cannot verify the data integrity … Expected identity hash:` |

**每一條「我驗證的方式」與「證據的界限」都寫在各發現底下。**

---

## Critical（push 前必須修）

### C1. round-36 C1 的那條突變仍然是綠的，而且我證明了它會靜默遺失資料

**位置**
- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1237-1243`
  （`settleCarriedOverLosses` 的 `resume` + `while (true)` 分頁迴圈）

**為什麼是錯的**

上一輪我把「分頁機制沒有任何測試守著」評為 C1。這一輪的修正把 JVM harness 的
`pendingJournalForPackage` fake 改成**真的會分頁**（`CaptureCoordinatorTest.kt:323-345`：排序、
seek 游標、`rows.size == limit` 才給 `next`）——這是對的，而且必要。

**但沒有任何測試把超過一頁的資料放進去。** 我逐一查過所有寫入 `pendingByPackage` 的測試：
`CaptureCoordinatorTest.kt:1522, 1762, 1785`，**三處都是 `mutableListOf(legacy)`，一列**。
production 的 `limit` 預設是 200（`IngestRepository.kt:171`），所以那個 `while (true)` 在每一個
JVM 測試裡都只跑一圈。instrumented 端的
`theSettleWalkVisitsEveryPendingRowOfTheSourceOnceInOrder`（`JournalLossTransactionTest.kt:271-295`）
測的是 `pendingJournalForPackage` 這個**查詢**，它自己手寫 `while` 驅動游標，**完全沒有經過
協調器的那個迴圈**。

**我怎麼驗證的（實跑，不是推理）**

1. 把 `CaptureCoordinator.kt:1238-1243` 換成單頁版本：

   ```kotlin
   val page = ingest.pendingJournalForPackage(packageName, JournalCursor.START)
   for (snapshot in page.snapshots) recordCarriedOverLoss(snapshot)
   ```

   完整重跑 `:platform:capture:test` → **BUILD SUCCESSFUL，58/58 綠。**

2. 為了證明這條突變**真的有害**（避免把「沒測試」誤報成「無所謂」），我在
   `CaptureCoordinatorTest.kt` 尾端暫時加了一個 probe：同一個來源放 **201** 列待結清的舊版列，
   然後 `setSourceEnabled(ENABLED_PKG, false)`，斷言寫出 **201** 個 `MESSAGES_DROPPED` 缺口。

   - 在**現行程式碼**上：**綠**（201 個缺口都寫出來了 —— 分頁迴圈是對的）。
   - 加上突變後：**紅** ——
     `REVIEWER PROBE: a source with more pending rows than one page has all of them settled FAILED`。

   probe 已在驗證完成後移除，`CaptureCoordinatorTest.kt` 已還原成 `4797b13` 的內容。

**危害**：第 201 列之後的列在 `discardPending` 清掉 payload 之前**沒有被結清**，它們帶進來的
損失**永遠不會出現在健康頁上**，而 payload 是那份證據的唯一副本。這正是 round 33 的發現、
round 35 subagent I2 的修正、round 36 C1 想守住的那件事。

**怎麼修（一個測試，不動產品碼）**

把上面那個 probe 收進 `CaptureCoordinatorTest.kt`：201 列（>`limit`）→ `setSourceEnabled(false)`
→ 斷言 201 個缺口。`removeSource` 那條也值得配一個同樣形狀的（remove 不可逆，漏掉就是永久的）。

**證據的界限**：這是**實跑**。我沒有驗證的是「真實裝置上單一來源真的會累積到 201 列待處理」——
那需要長時間暫停一個來源；但 `settleCarriedOverLosses` 的 KDoc（`:1230-1235`）自己就說這個迴圈
存在的理由正是「長期暫停的來源會累積待處理列」，所以可達性是這份程式碼自己主張的。

---

### C2. 這次 commit 新加的兩個守衛，各自一行就能拆掉而全綠

這兩條**不是**上一輪的遺留，是 `f3d4407` 自己引入的新表面。BRIEF 問的「三值狀態讓哪些**新的**
突變變成可能而沒人抓得到」，答案就是這兩條。

#### C2-a. `deferLoss` 的 `AND lossRecorded = 0`

**位置**：`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:129`

```sql
UPDATE event_journal SET lossRecorded = 2 WHERE eventId = :eventId AND lossRecorded = 0 AND state = 'PENDING'
```

**它守的是什麼**：這一句的 KDoc（`:123`）自己寫明「`lossRecorded = 0` 在述詞裡是為了不覆蓋已結清的列」。
它是整個設計的冪等邊界的一半：`1 → 2` 一旦發生，`resumeDeferredLosses`（`:140`）會把它變成 `0`，
下一輪 `claimLoss`（`:116`）就會**為同一筆損失寫出第二個缺口**。

**我怎麼驗證的**：把 `:129` 改成
`UPDATE event_journal SET lossRecorded = 2 WHERE eventId = :eventId AND state = 'PENDING'`，
完整重跑 `:platform:storage:connectedDebugAndroidTest` → **exit 0，47/47 綠。**

**可達性（我的分析，不是實跑）**：`deferLoss` 只從 `claimEventLoss` 的 catch 進來
（`IngestRepository.kt:152`）。要打到一個已在 `1` 的列，需要 `db.withTransaction { claimLoss(...) }`
在 `claimLoss` 已回傳 0（沒贏）之後才拋——例如 commit 階段的磁碟錯誤。窄，但不是不可能，
而且這正是「守衛」存在的意義。**我沒有實際構造出這個競態；我只證明了守衛沒有測試。**

**怎麼修**：instrumented 一個測試 ——「一個已結清的列，其後任何失敗的結清都不能把它降級」：
`journal(… ){ recordLoss() }`（列為 1）→ 直接呼叫 `holder.db().journalDao().deferLoss(id)`
→ 斷言回傳 0、`resumeDeferredSettlements() shouldBe 0`、且再 `claimEventLoss` 仍 `shouldBe false`。

#### C2-b. replay 的「誠實進度」判斷

**位置**：`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1172-1176`

```kotlin
if (!ingest.isReplayCandidate(snapshot.eventId)) {
    deferredSettlements = true
    progressed = true
}
```

**它守的是什麼**：註解（`:1166-1171`）說得很清楚 —— 只有在該列**真的離開候選集**時才算進度，
否則「連 deferral 都寫不進去的金庫」會讓迴圈把同一頁重讀到 round limit。

**我怎麼驗證的**：把它換成

```kotlin
ingest.isReplayCandidate(snapshot.eventId)
if (true) { deferredSettlements = true; progressed = true }
```

完整重跑 `:platform:capture:test` → **BUILD SUCCESSFUL，58/58 綠。**

**為什麼沒被抓到**：JVM harness 的 fake（`CaptureCoordinatorTest.kt:265-271`）在 `claimEventLoss`
失敗時**一定**會 `lossDeferred += eventId`，所以 `isReplayCandidate` 永遠回 `false`，
**那個 `if` 的 false 分支從來沒有被執行過**。instrumented 測試則完全不驅動協調器。

**危害**：金庫連 `deferLoss` 都拒絕時（例如 `holder.db()` 本身還活著但 journal 表寫不進去），
每輪 replay 會把同一頁重讀 100 次 × 200 列 = 20,000 次失敗交易。有界、不會 hang，
但那是**假的進度**，而且守衛存在就是為了不讓它發生。

**怎麼修**：給 `Harness` 一個 `deferralsFail` 旗標（fake 在該旗標下**不**把 id 放進 `lossDeferred`），
然後斷言：這種情況下 replay 不會宣稱進度（例如 `coVerify` 的 `pendingJournal` 呼叫次數保持在
一輪的量級，或直接斷言 `markJournalRetryable` 仍為 0 且不會出現 100 輪的重讀）。

---

## Important

### I1. `resumeDeferredLosses` 在來源政策交易裡是全域的，這是不必要的耦合

**位置**
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:140`（`WHERE lossRecorded = 2`，無 package 條件）
- `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1237`（在 `settleCarriedOverLosses` 頭上呼叫）

**BRIEF 第 2 點問的兩件事，我的回答：**

1. **從單一來源的政策交易裡做全域 reset 正確嗎？** 結果上不會出錯，但**不是必要的、而且是純副作用**。
   走訪查詢是 `packageName = :packageName`（`Daos.kt:97`），它只需要**這個來源**的延後列回到候選集。
   把來源 B 的 200 列一併 reset，是在 B 完全沒被使用者碰到的情況下，把它們放回 replay 的候選集。

2. **那個交易中止時對另一個來源的延後列做了什麼？** 一起 rollback，回到 `2`。**這正是問題所在**：
   B 的列的狀態，現在取決於 A 的政策變更成不成功。成功 → B 的列在 `0`，下一輪 replay 會白白重試
   一遍（每列一次失敗交易）；失敗 → B 的列回到 `2`。B 的排程被 A 的交易結果決定，而 B 與這件事無關。

**我怎麼驗證的**：這是**原始碼追蹤**，不是實跑 —— 我讀了 `Daos.kt:140`（沒有 package 述詞）、
`SourceRepository.kt:99-113`（`remove` 的 `alsoInTransaction` 在 `discardPending` 之前）、
`CaptureCoordinator.kt:713-726` 與 `:750-767`（兩個呼叫點都在交易內）。
**另外**：`SourcePolicyTransactionTest.kt:241-260`
（`aDiscardWhoseSettlementFailsLeavesTheRowAndTheSourceWhereTheyWere`）第 258 行
`ingest.claimEventLoss("evt-carried-2") { recordLoss() } shouldBe true` 確實是有鑑別力的斷言 ——
若該列停在 `2`，`claimLoss` 會回 0、這一行會變 `false`。**BRIEF 說的「deferLoss 隨政策交易一起
rollback」我確認過那個斷言真的守得住，這一點我同意，不再重推。**

**怎麼修**：`settleCarriedOverLosses` 用一個 per-source 版本

```sql
UPDATE event_journal SET lossRecorded = 0 WHERE lossRecorded = 2 AND packageName = :packageName
```

replay 的頭上維持全域（那裡的候選集本來就是全域的）。這樣「一個來源的政策交易改到另一個來源的
列」這件事就不存在了，rollback 的耦合也一併消失。**這是嚴格更好的，我想不到全域版本在走訪路徑上
有任何額外價值** —— `packageName IS NULL` 的舊列（pre-v3）本來就不在走訪的集合裡，它們唯一的
讀者是 replay。

### I2. 兩個 replay 可以並行，而 `resume` 沒有在 pipeline 鎖裡

**位置**
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/VaultMaintenance.kt`（`work {}` 只擋 exclusive，**不互斥**）
- `CaptureCoordinator.kt:1128`（replay 的 `resume` 在 `pipelineMutex` **之外**）
- `CaptureCoordinator.kt:1172`（`deferLoss` → `isReplayCandidate` 在 `pipelineMutex` **之內**）

**為什麼**：`VaultMaintenance.work` 的實作是「把 job 登記進 `workers`、檢查 `_active`、跑 block」——
**沒有任何互斥**。而 `replayJournal()` 的觸發點有五個：vault Ready（`:290`）、暫停切換（`:394`）、
取消暫停（`:742`）、維護結束（`:819`）、以及 `retryDeferredSettlements`（`:555`）。
另外 replay 迴圈內的 `if (!sourcesLoaded) loadSourcePolicy()`（`:1146`）會一路走到
`settleColdStartGap → settleUnrecordedJournalLoss → retryDeferredSettlements` → **在 replay 裡再
launch 一個 replay**。

**後果**：replay A 的頭部 `resume`（全域、無鎖）可以落在 replay B 的 `deferLoss` 與
`isReplayCandidate` 之間 → B 看到「還是候選」→ 不算進度 → B 的迴圈提早結束。
**不會 hang、不會遺失資料**（A 會處理那些列），但 BRIEF 第 3 點問的「並行下這個進度會計誠實嗎」，
嚴格答案是：**不誠實的方向是保守的（低報進度），所以安全；但它確實會受另一個 pass 干擾。**
反方向（A 的 resume 在 B 讀完一頁之後）會讓 B 的下一輪重新拿到已經 defer 過的列，把一輪 pass 的
成本乘上並行的 replay 數。

**100 輪的上限仍然是上限**：`rounds++ < 100`（`:1132`）無條件遞增，不受 `progressed` 影響。
這一點沒有問題。

**怎麼修（低成本）**：`replayJournal()` 外面加一個 `Mutex`（`tryLock`，拿不到就直接 return ——
已經有一個 pass 在跑，就不需要第二個）。這同時也讓 `retryDeferredSettlements` 的「一次 deferral
episode 只 launch 一次」這個宣稱變成真的成立。

**證據的界限**：這是**原始碼追蹤 + `VaultMaintenance.kt` 的實作閱讀**，我沒有構造出並行 replay 的
實測。我不主張它今天會造成任何可觀察的錯誤。

---

## Minor

### M1. `retryDeferredSettlements` 的 check-then-clear 不是原子的，安全性靠的是 pipeline 鎖而 KDoc 沒說

`CaptureCoordinator.kt:552-556`：

```kotlin
if (!deferredSettlements) return
deferredSettlements = false
scope.launch { replayJournal() }
```

`@Volatile`（`:209`）只保證可見性，不保證 check-and-clear 原子。它今天是安全的，因為**三個存取點
全部在 `pipelineMutex` 底下**：`:537`（`settleUnrecordedJournalLoss`，其 KDoc 已寫 must run under
pipelineMutex）、`:961`（接受路徑，持鎖）、`:1173`（replay 的 `withLock` 內）。
但 KDoc（`:549`）說的是「the flag is cleared before the launch」，讀起來像在主張原子性。
**建議**：KDoc 加一句「Must be called under `pipelineMutex`」，或改用 `AtomicBoolean.getAndSet(false)`。

**我怎麼驗證的**：逐一讀了三個存取點的呼叫鏈。**未實跑。**

### M2. 「一次 deferral 只重試一次」沒有測試

把 `:554` 的 `deferredSettlements = false` 拿掉，`:platform:capture:test` **58/58 綠**（實跑）。
危害很低（旗標本來就會被下一個 defer 的 pass 重設，而金庫壞掉時根本不會有成功的 gap write 來武裝），
但 KDoc 把「at most one replay per deferral」寫成設計主張，就值得一條控制。

### M3. `resumeDeferredLosses` 沒有 `state = 'PENDING'` 守衛

`Daos.kt:140`。一個先 defer、之後被 `discardPending` 打成 `DISCARDED` 的列（`discardPending`
不看 `lossRecorded`，`Daos.kt:148`），會被 resume 從 `2` 改回 `0`。行為上無害（`pending` / `claimLoss`
都要求 `PENDING`），但**回傳的筆數會灌水**，而 `JournalLossTransactionTest.kt:147, 215, 225, 384, 417, 438`
都在斷言那個數字。加上 `AND state = 'PENDING'` 讓「回傳值」與「放回候選集的列數」一致。

我試圖構造這個路徑：defer → 停用該來源。但停用會先 `resume` 再走訪再結清，所以走不到。
**目前不可達；這是防禦性的建議，不是缺陷。**

### M4. 已經在 schema 4 的開發用金庫，這個 migration 救不了它（BRIEF 第 4 點的第三小問）

`QuietInboxDatabase.kt:126-131` 在 `MIGRATION_3_4` 裡加了 `CREATE INDEX IF NOT EXISTS`。
`IF NOT EXISTS` 讓 3→4 這條路徑冪等，**但一個已經在 version 4 的金庫根本不會跑這條 migration**。
而 `4.json` 的 `identityHash` 從 `485f8d770a42385710a7adda5f3a9ab4` 變成
`08e4980716344a6d688b2149256c8cdc`，Room 2.8.4 在開啟時比對 `room_master_table` 的 hash，
不合就丟 `Room cannot verify the data integrity …`（我從
`room-runtime-android/2.8.4/classes.jar` 的字串表確認這段訊息確實存在於這個版本）。

**這不影響任何使用者**：`git ls-tree v0.1.3 platform/storage/schemas/…/` 只有 `1.json 2.json 3.json`，
**schema 4 從未出貨**。受影響的只有開發機與模擬器上跑過前一版 schema 4 的 debug 金庫 ——
也就是這一輪 reviewer 與維護者自己的裝置。

**怎麼修**：不必改 code（失敗是大聲的，符合「不做破壞性 migration」）。但
`docs/RELEASE.md` 或這個 commit 的 device-walkthrough 註記應該寫明：**本次 device verify 必須從
乾淨安裝或 vault reset 開始**，否則 app 一開就炸，會被誤判成這次修正的 bug。

**我怎麼驗證的**：`git ls-tree`（實跑）、`4.json` 的 diff（實跑）、Room jar 的字串（實跑）。
**我沒有實際做出一個舊 hash 的 v4 金庫來看它炸** —— `emulator-5556` 上的
`dev.quietinbox.app.debug` 目前沒有 `databases/` 目錄（`adb shell run-as … ls databases/` 回
`No such file or directory`），所以那台機器上沒有可以示範的舊金庫，也沒有東西會壞。
**這一條的機制是原始碼與 jar 字串層級的推論，不是一次執行。**

---

## BRIEF 六個問題，逐條回答

### 1. 狀態機是封閉的嗎？

**是。** 我 grep 過整棵樹（排除 `build/`）：`lossRecorded` 只出現在 `Daos.kt`、`Entities.kt`、
`IngestRepository.kt:116`、`QuietInboxDatabase.kt:125` 與測試裡；**沒有第二處 SQL 碰它**，備份
（`BackupService`）完全不碰 `event_journal`。

- **有沒有路徑到 `1` 而沒有 gap？** 沒有。寫 `1` 只有兩處：`journal()` 的 insert
  （`IngestRepository.kt:116`，`lossOnAccept` 在**同一個交易內**被呼叫，拋了就整個 rollback，
  連 insert 都不存在）與 `claimLoss`（`Daos.kt:116`，`writeGap` 在同一個交易內）。
- **有沒有路徑到 `2` 而永遠出不來？** 兩個 resume 點：replay 的頭（`:1128`）與走訪的頭（`:1237`）。
  replay 的觸發點包含 `VaultState.Ready`（`:290`），所以**程序重啟 + 金庫解鎖就一定會 resume**。
  BRIEF 承認的殘留（沒有新的帶損失事件、沒有生命週期事件）確實存在，但見〈模型問題〉：
  **它不比修正前更糟**。
- **有沒有 `2` 的列會在任何 pass resume 它之前被 discard 掉？** 沒有。`discardPending` 只有兩個
  呼叫點：`CaptureCoordinator.kt:724`（在 `settleCarriedOverLosses` 之後）與
  `SourceRepository.kt:103`（在 `alsoInTransaction` 之後，而 `removeSource` 的
  `alsoInTransaction` 第一件事就是 `settleCarriedOverLosses`）。兩者都在同一個交易內，
  而走訪的第一行就是 resume。retention 的 `deleteExpired`（`Daos.kt:159`）帶
  `state != 'PENDING'`，動不到。**這一項有 instrumented 測試守著**
  （`aDeferredRowIsStillSettledBeforeItsSourceIsDiscarded`）。

### 2. 全域 resume — 見 I1。

### 3. 進度會計 — 見 C2-b（守衛沒有測試）與 I2（並行下會被干擾，但方向是保守的）。100 輪仍然是上限。

### 4. 索引與 row value 游標

- **row value 在每個支援的 API level 上都安全嗎？** **是，而且和 API level 無關。**
  production 只有一處 `Room.databaseBuilder`（`DatabaseHolder.kt:109-110`），且**一定**掛
  `SupportOpenHelperFactory`（SQLCipher）。SQLCipher 4.18.0 靜態連進 app 的 SQLite 是 **3.53.4**
  （我解開 aar、`strings libsqlcipher.so` 讀出來的）。row value 需要 3.15，差了 38 個小版本。
  **裝置本身的 framework SQLite 完全不參與**。（附帶：即使參與也安全 —— minSdk 26 對應
  Android 8.0 的 SQLite 3.18。但這一句是我的背景知識，不是我在這裡量到的。）
- **語意有沒有變？** 沒有。我用 `4.json` 的 `createSql` 建表，塞 7 列（100×3、200、300×2、400，
  `eventId` 字典序刻意與插入序交錯），用 row value 與舊 OR 形式各走 6 種 page size（1/2/3/6/7/8），
  **逐列輸出完全相同，不漏不重且都會終止**。
- **中間那個 `lossRecorded = 0` 會傷到別的查詢嗎？** 不會，**反而變好**。`EXPLAIN QUERY PLAN`：
  - 走訪查詢：`SEARCH … USING COVERING INDEX …(packageName=? AND state=? AND lossRecorded=? AND (receivedAtEpochMs,eventId)>(?,?))` —— covering index seek，沒有 temp B-tree。
  - `discardPending`：**現在也吃這個索引的兩欄前綴**（`packageName=? AND state=?`），
    以前只能用 `index_event_journal_state`。
  - `pending()` / `pendingExcluding()`：仍是 `index_event_journal_state` + `USE TEMP B-TREE FOR ORDER BY`
    —— 與修正前相同，`lossRecorded != 2` 只是同一個計畫上多一個過濾，**沒有新增成本**。
  - 沒有索引時（假設一個開發金庫卡在舊 schema 4）：退化成 `index_event_journal_state` + temp B-tree，
    也就是 round 36 Codex I2 量到的那個形狀。
- **migration 的 `IF NOT EXISTS` 對已經在 schema 4 的開發金庫對嗎？** 見 M4：**不對，但它救不了也
  不該救** —— Room 的 identity hash 會先大聲拒絕。`MigrationTest.migrate3To4…` 用
  `runMigrationsAndValidate(name, 4, true, …)`，**會驗證索引**，所以「migration 忘了建索引」這件事
  是有測試守著的（我沒有單獨突變驗證這一條）。

### 5. 重試的武裝 — 不會死鎖、不會洩漏、不會活過維護

- **死鎖**：不會。`retryDeferredSettlements` 是 `fun`（非 suspend）+ `scope.launch`，
  被 launch 的 `replayJournal()` 要拿 `pipelineMutex` 時呼叫端早已放掉了。
- **活過維護**：不會。`maintenance.work {}` 在 `_active` 為真時直接回 `null` 不跑 block；
  維護結束時 `:819` 會再 launch 一次 replay，所以「retry 被維護吃掉」是會被補回來的
  （旗標雖然已經清掉，但下一個 pass 的 resume 是全域的，等價）。
- **洩漏**：`scope` 是協調器自己的 scope，服務結束時一起取消。
- **兩個同時武裝？** 不會 —— 三個存取點全在 `pipelineMutex` 底下。但這件事沒有寫在 KDoc 裡（M1）。

### 6. 測試的對抗性 — 見上面的表與 C1/C2。

---

## 上一輪七條突變表，對著現在的程式碼重跑

BRIEF 指名要這張表。**「現在」欄的每一格我都實跑過**（JVM 突變重跑 `:platform:capture:test`，
DAO 突變重跑 47 個 instrumented 測試）。

| # | round 36 的突變 | round 36 結果 | **現在** | 說明 |
|---|---|---|---|---|
| 1 | 刪掉 `settleCarriedOverLosses` 的 `while` 迴圈 | 靜默漏列，無測試 | **仍然 GREEN** | **C1。** fake 修好了，但沒有 >200 列的測試去驅動它 |
| 2 | `JournalPage.next` 永遠 `null` | 同 #1，無測試 | **被抓到** | `theSettleWalkVisitsEveryPendingRowOfTheSourceOnceInOrder`（limit=1 時只會看到第一列） |
| 3 | 游標 `> :afterId` → `>=` | 重複但無害，無測試 | **被抓到** | 現在是 `(a,b) >= (x,y)`，邊界列重複 → `visited shouldBe all` 變紅（BRIEF 控制 #11） |
| 4 | `rows.size == limit` → `isNotEmpty()` | 無害，無測試 | 仍然無測試，仍然無害 | `JournalPage` 的新 KDoc 已把「非 null 只代表『再問一次』」寫清楚 |
| 5 | `ORDER BY` 去掉 `eventId` | 靜默漏列，無測試 | 綠，但**現在無害** | 複合索引供給順序（BRIEF 已實測過，我同意）。真正會漏列的是拆掉游標的 tiebreak，那條是紅的 |
| 6 | `next` 用 `snapshots.lastOrNull()` | 提早停止，無測試 | **被抓到** | `aPageThatWillNotDecodeStillMovesTheWalkOn` |
| 7 | `receivedAtEpochMs > :afterTime` → `>=`（唯一的無界迴圈） | 無測試 | **這條突變已不存在** | row value 形式沒有可以被 `OR` 吸收的那半邊；它塌縮成 #3，而 #3 是紅的 |

**三值狀態讓哪些新突變變成可能而沒人抓得到（BRIEF 最在意的一問）：**

| 新突變 | 位置 | 結果 |
|---|---|---|
| `deferLoss` 拿掉 `AND lossRecorded = 0` | `Daos.kt:129` | **GREEN（C2-a）** |
| replay 的 `isReplayCandidate` 進度守衛改成無條件 | `CaptureCoordinator.kt:1172` | **GREEN（C2-b）** |
| `retryDeferredSettlements` 不清旗標 | `CaptureCoordinator.kt:554` | **GREEN（M2）** |
| `resumeDeferredLosses` 拿掉 `WHERE lossRecorded = 2` | `Daos.kt:140` | 紅（`rowsThatCannotSettleStopBlockingTheRowsBehindThem` 的 `shouldBe 3` 會變 4） |
| `isReplayCandidate` 拿掉 `AND lossRecorded != 2` | `Daos.kt:144` | 紅 |
| `pending` 拿掉 `AND lossRecorded != 2` | `Daos.kt:51` | 紅 |
| 走訪的 `lossRecorded = 0` 放寬成 `!= 2` | `Daos.kt:97` | 紅（`theSettleWalkSkipsRowsWhoseLossIsAlreadyRecorded`） |

四個「讀」的述詞都有測試守著；**兩個「寫」的守衛都沒有** —— 這是很整齊的一個缺口形狀。

**BRIEF 那 14 條負向控制，我抽查的部分全部成立**：#3（沒有重試 → 紅）、#4（任何接受都重試 → 紅，
`an event accepted without a loss…`）、#5（走訪沒有 resume → 紅）、#6（replay 沒有 resume → 紅）。
我沒有重跑 #1、#7–#14。

---

## 模型問題：三值的 `lossRecorded` 是對的形狀嗎？

**是的，而且我認為不用計時器是對的。** 但我要把它為什麼對說清楚，因為理由跟 BRIEF 寫的略有不同。

**一、三值把兩件正交的事塞進一個欄位。** 那兩件事是：
(a) **持久、單調的事實** —— 這筆損失記錄過了嗎？（否／是）
(b) **短暫的排程事實** —— 這一列現在該不該被一個 pass 讀到？

`2` 編碼的是「(a) 否 **且** (b) 不該」。因為共用一欄，(b) 的每一次寫入都有機會踩壞 (a) ——
而這正是 C2-a 那個突變證明的事。守衛（`deferLoss` 的 `lossRecorded = 0`、`resumeDeferredLosses`
的 `WHERE lossRecorded = 2`）都在、都正確，只是其中一個沒有測試。

**替代形狀**：`lossRecorded` 維持布林，另開一欄 `settleDeferred`。那樣「已結清的列不可能被降級」
就變成**schema 層級不可表達**，而不是靠三個語句各自的述詞維持。代價是一欄 + 一次 migration。

**我的判斷：不值得改。** 因為 `2` 在建構上**只能從 `0` 到達**（`deferLoss` 的述詞），對一個 PENDING
的列來說三個狀態確實互斥，單欄換來單一索引與單一述詞。**正確的回應是給守衛補一條負向控制
（C2-a），而不是重做模型。**

**二、全域 resume 才是這個模型裡我唯一想改的地方。** 見 I1：走訪路徑不需要全域，per-source 嚴格
更好。如果要一個「更好的形狀」，它是這個，而不是三值本身。（更徹底的做法是把「恢復」變成
**pass 的屬性**而非**表的狀態** —— 存 `deferredInPass`，讀取述詞寫成
`lossRecorded != 2 OR deferredPass != :currentPass`，這樣就不需要任何全域寫入，
跨來源干擾與 I2 的競態一起消失。代價是再一欄 + 一個單調計數器。對這個 app 我認為不划算，
但這是「有沒有更好的形狀」的誠實答案。）

**三、不用計時器是對的，理由比 BRIEF 寫的更強。** BRIEF 的論證是「計時器會重試進一個還在拒絕
一切的金庫」。我同意，但真正的關鍵在成本的形狀：

- **修正前**：一頁 200 列全部結清失敗 → `progressed` 從頭到尾是 `false` → 迴圈**跑完第一輪就結束**。
  每次 replay 的成本 = 200 次失敗交易，第 201 列永遠到不了。
- **修正後**：每一列 defer 掉、讓出位置、算進度 → 迴圈會一路往下推進，
  一次 pass 最多 100 輪 × 200 列 = **20,000 次失敗交易**。

**這就是這個修正的代價，而且它是 100 倍。** 買到的是第 201 列會被處理。這個交換是對的
（正確性優先於一台已經壞掉的裝置上的 CPU），但它也正是**為什麼計時器會是錯的**：
一個週期性計時器會把這個 20,000 次的 pass 變成排程性的重複開銷，跑在一台金庫已經壞掉的裝置上。
以「證明」武裝就沒有這個問題 —— 金庫壞掉時根本產生不出成功的 gap write，也就武裝不了任何重試。
**我認為這一點值得寫進 CHANGELOG 或 ARCHITECTURE**：目前的文字只說了「不會變成 replay 的洪流」，
沒有說單一 pass 的成本上限提高了兩個數量級。

**四、殘留是否比計時器的殘留更糟？不。** 因為修正前的重試節奏**完全一樣**依賴同一批觸發點
（vault Ready / 取消暫停 / 維護結束 / 政策載入）。修正沒有拿掉任何重試機會，只是多加了一個
（帶損失的接受）。而 `VaultState.Ready`（`:290`）意味著**程序重啟 + 金庫解鎖就會 resume**，
在一支真實手機上這件事發生得夠頻繁。**BRIEF 的自我評估是準確的，我沒有要補充的反例。**

---

## 範圍外，我確認沒有回歸

BRIEF 列的三項延後損失（WhatsApp 群組行分隔、備份合併跳過重複列、結清失敗讓「停止擷取」靜默無效）
與 issue #28，我沒有把它們當成新發現。其中第三項這次 commit 確實**多了一個觸發點** ——
`settleCarriedOverLosses` 的第一行 `ingest.resumeDeferredSettlements()`（`:1237`）沒有 `guarded`，
而且**每一次停用／移除都會執行**，即使該來源一列待處理都沒有。但它與政策寫入是同一張 DB 的同一個
交易，能拒絕它的金庫也會拒絕政策寫入，所以我判斷失敗面沒有實質變寬，**不列為發現**。

## 這次 commit 我認為做得對的地方（不是客套，是我實際查過而沒找到問題的）

- `deleteAllExpired` / `clear` 移除：我 grep 過整棵樹沒有呼叫者，263 個 JVM 測試 + 47 個
  instrumented 測試在移除後全綠 —— 沒有隱藏的呼叫者。
- 文件計數與程式碼一致：`TEST_MATRIX.md` 宣稱 storage 47 / `SourcePolicyTransactionTest` 13 /
  `JournalLossTransactionTest` 16 / `CaptureCoordinatorTest` 58 / `MessageBubbleSemanticsTest` 5，
  我逐一數過原始碼的 `@Test` 與 `test(` **全部相符**；storage instrumented（47）、conversation
  instrumented（5）與 JVM（263）的實跑數字也相符。**backup 2 與 crypto 2 我沒有重跑。**
  「文件跑在程式碼前面」這一輪沒有發生。
- `theSettleWalkSeeksToItsCursorInsideTheIndex`（`JournalLossTransactionTest.kt:329-357`）在真實
  SQLCipher 上跑 `EXPLAIN QUERY PLAN` 並斷言沒有 `SCAN` 與 `TEMP B-TREE` —— 這是我在這個 repo 裡
  看過最好的一種效能測試：它守的是查詢計畫，不是一個會飄的時間數字。
- `aRemoveThatFailsPartWayThroughTheGraphRollsAllOfItBack`（`SourcePolicyTransactionTest.kt:409-449`）
  用 trigger 讓第七道刪除中止，然後逐表數回來 —— 這確實回應了 round 36 Codex I4 的批評
  （舊的控制在圖形刪除**開始之前**就拋了）。

---

## 修正清單（全部是測試，產品碼可以不動）

1. **C1**：`CaptureCoordinatorTest` 加一條 201 列的 `setSourceEnabled(false)` 測試
   （`removeSource` 同形一條）。我已驗證這條測試在現行碼上綠、在單頁突變下紅。
2. **C2-a**：instrumented 加一條 —— 已結清（`1`）的列不可被 `deferLoss` 降級。
3. **C2-b**：`Harness` 加 `deferralsFail` 旗標，斷言 deferral 也失敗時 replay 不宣稱進度。
4. **I1**（建議，改一行 SQL + 一條測試）：走訪用 per-source 的 resume。
5. **M1 / M2 / M3 / M4**：KDoc 一句、一條控制、一個 `AND state = 'PENDING'`、
   device-walkthrough 的乾淨安裝註記。

修好 1–3 之後我會給 APPROVE。
