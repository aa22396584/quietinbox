# Round 39 — Claude subagent（獨立審查）

**Verdict: APPROVE WITH MINOR FIXES**

範圍：`29cfaf0..4ba8652`（`main`，審查時 HEAD 為 `f7f6bd9`，僅多一筆 docs commit）。
唯讀審查；未改動主樹任何產品程式碼。所有變異實驗都在自建的 scratch worktree
`/tmp/qi-r39-sub-wt`（detached at `4ba8652`）中進行，結束後已還原。

| 等級 | 數量 |
| --- | --- |
| Critical | 0 |
| Important | 1（範圍外的既有洞，不視為本次 push 的阻擋項，見 I1） |
| Minor | 6 |

---

## 我實際跑了什麼（證據等級）

全部在 `export ANDROID_HOME=$HOME/Library/Android/sdk` 之下。

| 動作 | 位置 | 結果 |
| --- | --- | --- |
| `./gradlew test` 全套 | `/tmp/qi-r39-sub-wt` | **285 tests, 0 failures**（由各模組 `test-results/**/*.xml` 加總），與 brief 宣告的 285 完全相符 |
| `:platform:storage:connectedDebugAndroidTest` | scratch worktree，`ANDROID_SERIAL=emulator-5556` | **63 tests, 0 failures** |
| `:platform:backup:connectedDebugAndroidTest` | 同上 | **3 tests, 0 failures** |
| `:feature:health:connectedDebugAndroidTest` | 同上 | **3 tests, 0 failures** |
| `CaptureCoordinatorTest` 重跑 | 主樹 | 見 M5；乾淨環境下的兩次執行皆 **73 tests, 0 failures** |
| NC27 / NC29 / NC30 / NC26 四個負向控制 | scratch worktree | 全部如宣告變紅，見下 |

未跑：`:feature:conversation`（5）與 `:platform:crypto`（2）的 instrumented 測試、`lint`、
`check-permissions.sh`、`check-strings.py`。brief 宣告的 76 = 63 + 3 + 3 + 5 + 2，我親自驗證了其中 69 個。

負向控制（每個都是單獨套用、跑完即 `git checkout --` 還原）：

| NC | 我做的一行變異 | 變紅的測試 |
| --- | --- | --- |
| NC27 | `replayGate.withLock { … }` → `if (!replayGate.tryLock()) return` + `try/finally unlock`（round-37 形狀） | `a request that arrives while the running pass is cancelled is still served`（1 紅 / 73） |
| NC29 | 刪掉 `retryDeferredSettlements()` 裡的 `deferredSettlements = false` | `one deferral episode arms at most one retry, however many gap writes then succeed`（1 紅 / 73） |
| NC30 | `settleCarriedOverLosses` 的 `resumeDeferredSettlements(packageName)` → 全域 overload | `switching one source off resumes only that source's deferred rows`（1 紅 / 73） |
| NC26 | `commitFailureLoss` 的 `GapReason.COMMIT_FAILED` → `GapReason.UNKNOWN` | 3 紅：`a row whose commit attempts run out…`、`a record that cannot be written parks the row…`、`the live entrance hands in the same record as the replay` |

**這四個是我實際跑出來的，不是推論。** NC26 恰好三紅，與 commit message 宣告的「NC26（3 tests）」一致。
NC24／NC25／NC28／NC31 我沒有實跑，僅為 commit message 的宣稱。

---

## 兩段專論

### 一、兩位元的欄位是不是對的形狀

**是，而且是封閉的。** 我列舉了 `lossRecorded` 的**全部**寫入者，證據是對整個 repo 的 grep
（`--include="*.kt"`，排除 `build/`）加上逐一閱讀：

1. `IngestRepository.kt:121` 插入時寫 `LOSS_SETTLED`(1) 或 `LOSS_UNSETTLED`(0)。
2. `Daos.kt:125` `claimLoss`：`SET 1 WHERE lossRecorded = 0 AND state = 'PENDING'` → 只有 0→1。
3. `Daos.kt:139` `deferLoss`：`SET +2 WHERE lossRecorded < 2 AND state = 'PENDING'` → 只有 0→2、1→3。
4. `Daos.kt:160` `resumeDeferredLosses`：`SET −2 WHERE lossRecorded >= 2` → 只有 2→0、3→1。
5. `Daos.kt:169` `resumeDeferredLossesForPackage`：同上，加來源條件。

`setState`(`:85`)、`fileFailed`(`:195`)、`discardPending` 都不碰這個欄位；migration 3→4
（`QuietInboxDatabase.kt:125`）以 `NOT NULL DEFAULT 0` 新增；`BackupService` 完全不寫
`event_journal`（grep `journalDao|event_journal` 於 `BackupService.kt` 為空）。因此值域嚴格封閉在
0–3，**沒有任何路徑能跑到 0–3 之外**，加法與減法的守衛條件互為反函數，resume 必定把列還回它原本的
settled 位元。這是 source trace + grep 窮舉，不是執行證據；但 `theCandidateReadsAgreeOnAllFourValues`
（`JournalLossTransactionTest.kt:734`）在真實 vault 上把四個值一次擺出來，我跑過且綠。

「一個讀取把 3 當成 settled-and-safe」（round 37 的 Critical 再往前一格）**沒有發生**：
`claimEventLoss`（`IngestRepository.kt:162`）的 `when` 只把 `LOSS_SETTLED`（恰為 1）對應到
`ALREADY_RECORDED`，3 落進 `else -> DEFERRED`，而 `LossClaim.gapIsDurable`（`:638`）只對
`RECORDED`／`ALREADY_RECORDED` 為真。這是正確的保守答案：3 這列既然停放在頁面外，就不該被 commit。

brief 問「一列在 0、但 claim 仍然沒更新到——可達嗎？」**不可達。** `claimLoss` 與
`pendingLossState` 在同一個 `db.withTransaction` 內（`IngestRepository.kt:163-178`），SQLite 對這條
連線是序列化的，兩個語句看到同一個快照；`claimLoss` 回 0 而 `pendingLossState` 回 0 需要兩者對同一列
的 `lossRecorded` 有不同看法，在同一交易內不可能。就算真的發生，`else -> DEFERRED` 是安全側。

一個真正的設計優點值得指出：把延後做成**位元**而不是第三個值，讓「這列的自身損失結清了沒」與
「這列現在在不在 replay 的視野裡」兩個正交問題各自有欄位。round 37 的 `SET 2 WHERE 0` 形狀在 issue #28
之後必定壞掉，因為耗盡的列有一半是從 1 出發的（live event 帶損失就以 1 插入，replay 也會先結清再試
commit）。Codex 的 consult（`docs/reviews/2026-09-07-issue28-consult/`）選這條是對的。

### 二、waiting gate 是不是 round-38 I1 的正確修法

**是，而且它有一個會辨別的測試。** 我把 gate 還原成 round-37 的 `tryLock` 形狀（NC27），
`a request that arrives while the running pass is cancelled is still served` 立刻變紅，其餘 72 個全綠。
這不是推論，是我跑出來的。

正確性的三個前提我逐一查證：

- **不會 deadlock。** `replayJournal()` 的五個呼叫點——`init` 的 vault-state collector（`:309`）、
  `setPaused(false)`（`:413`）、`retryDeferredSettlements`（`:584`）、`setSourcePaused(false)`（`:771`）、
  `onMaintenance(false)`（`:848`）——**全部在 `scope.launch { … }` 之內**，沒有任何一個持有
  `pipelineMutex`。特別是 `retryDeferredSettlements` 的呼叫端確實持有管線鎖，但它只 launch 不 await
  （`:581-585`），KDoc 也把這點寫明了。反方向也成立：持有 `replayGate` 的人會去拿 `pipelineMutex`，
  但沒有持有 `pipelineMutex` 的人會去等 `replayGate`，所以沒有鎖序反轉。
- **waiter 不會無界堆積、也不會 spin。** `replayPass()` 是 `maintenance.work { … } != null`；
  `VaultMaintenance.work`（`VaultMaintenance.kt:65-77`）只在 `_active` 為真時回 null，block 本身回
  `Unit`，所以 `false` 嚴格等價於「維護中」。維護中的分支是 `replayRequested = true; return`——**return，
  不是 continue**——所以每個 waiter 只做一次極廉價的旗標檢查就離開，N 個 waiter 是 N 次 O(1)，不是自旋。
  而且維護期間沒有 pass 會跑，`deferredSettlements` 不會被設起來，`retryDeferredSettlements` 也就不會
  自我餵食新的 waiter：waiter 數量由外部生命週期事件決定，不由這段程式碼決定。
- **被取消的 waiter 不會留下壞狀態。** waiter 在等 gate 之前就把旗標設為 true；它被取消時旗標仍然
  站著，下一個持有者會服務它。被取消的**持有者**則是 round-38 修的那件事：`withLock` 在 finally 釋放，
  下一個 waiter 拿到 gate 並看到請求還在。

brief 說「維護中那條的 restore 旗標是 belt and braces、沒有能辨別的測試」——**這個推理是對的。**
`VaultMaintenance.exclusive`（`:85-95`）的 `finally` 先 `_active.value = false` 再呼叫
`onMaintenanceEnded()`，而 `exclusiveMutex` 讓 run 之間序列化，所以 `onMaintenance(false)` 的
`maintenanceStartedAt ?: return` 永遠不會早退，`scope.launch { … replayJournal() }` 永遠會發生。旗標
被清或不被清，結果都一樣，因此確實不可能有測試能分辨——commit message 說出這點而不是假裝有控制，是對的。

---

## Important

### I1（範圍外、既有）另外兩條離開 PENDING 的路，仍然讓一個已接受的事件無聲消失

`CaptureCoordinator.kt:1070` — parser 丟例外時 `markJournal(eventId, "FAILED", "PARSE_…")`；
`IngestRepository.kt:237` — `pendingJournal()` 解不出 payload 時 `setState(row.eventId, "FAILED", "DECODE")`。
兩者都經由 `Daos.kt:79-85` 的 `setState` 把 `payload` 清成 `''`，而**都不寫任何 gap**。

這正是 issue #28 剛剛在第三條路上補起來的形狀：一個已經被接受、被計數、使用者被告知「已收到」的事件，
在時間軸上不留任何痕跡。差別只在：

- `PARSE_` 至少寫了一筆 `PARSE_EXCEPTION` diagnostic（`:1071`），健康頁的 diagnostics 區塊會顯示它；
- `DECODE` **什麼都沒有**——沒有 gap、沒有 diagnostic，只有 journal 列上的 `failureCode`，
  而那一列會被 retention 的 `deleteExpired`（`Daos.kt` 的 `state != 'PENDING'` 條件）在 TTL 後刪掉。
  之後這個事件在裝置上不存在任何紀錄。

我不認為這是本輪 push 的阻擋項，理由要說清楚：

1. **不在 diff 內**，範圍內的五個 commit 沒有讓它變差；
2. round 36 的 Codex 報告（`docs/reviews/2026-09-07-round36/codex-gpt-6-astra.md:222,224`）已經逐條列舉
   過這兩個出口並判定為「明列例外」，round 35 的 Claude subagent 也提過 DECODE
   （`2026-09-07-round35/claude-subagent.md:80-83`）；
3. 但**那兩次的判定回答的是比較窄的問題**——「這是不是另一條略過 *carried-over loss* 的出口」——
   而不是「一個已接受的事件會不會整個消失而不留紀錄」。issue #28 的修正把後者立成了原則
   （`Daos.kt:118-123` 的 KDoc 就是這樣寫的），這兩條路現在是唯一還不遵守它的。
4. 可達性偏低但不是零：`DECODE` 需要本 app 自己寫出的 JSON 讀不回來，最現實的觸發是
   `NotificationSnapshot` 在有 pending 列跨越 app 升級時發生不相容變更
   （`Json { ignoreUnknownKeys = true }` 只擋得住新增欄位，擋不住移除或改名）。

**建議**：照 #28 自己走過的路——開一個 issue，不要塞進這一輪。`PARSE_` 與 `DECODE` 各記一筆
`GapReason` 更誠實的 bounded gap（例如 `PARSE_FAILED` / `PAYLOAD_UNREADABLE`），寫在同一個 transaction 裡。

**驗證方式**：對 `platform/capture/src/main`、`platform/storage/src/main` 全域 grep `"FAILED"` 與
`GapReason`，逐一讀 `CaptureCoordinator.kt:1063-1080`、`IngestRepository.kt:230-240`、
`Daos.kt:79-85`；再回讀 round 35／36 的報告確認先前的處置。**證據的界線**：全部是 source trace，
我沒有寫測試去觸發 DECODE，也沒有在裝置上重現它。

附帶一點（同一處）：`IngestRepository.kt:237` 的這個 `setState` 是在 `replayPass` 的**頁讀取階段**發生的，
而頁讀取在 `pipelineMutex` 之外（`CaptureCoordinator.kt:1215` 讀，`:1236` 才拿鎖）。`setState`
（`Daos.kt:79-85`）沒有 `state = 'PENDING'` 的守衛，所以它可以覆蓋一個 policy transaction 剛剛寫成
`DISCARDED` 的列。後果只是 `failureCode` 與 `attempts` 的差異（兩者都已終止、payload 都已清空），
所以我把它放在這裡當註腳，不另立一條。

---

## Minor

### M1 測試數字：三處宣稱都比實際多一

實際（`grep -c "@Test"`，並由 instrumentation runner 自己印出的
`run started: 32 tests` / `run finished: 32 tests, 0 failed` 交叉確認）：

| commit | `JournalLossTransactionTest` 實際 | 對應文件宣稱 |
| --- | --- | --- |
| `29cfaf0`（本輪之前） | 20 | brief 與 TEST_MATRIX 皆稱 21 |
| `bb8c2ba` | 23 | commit message 稱 24 |
| `4b31f28`（至 HEAD） | 32 | commit message 與 brief 皆稱 33 |

`docs/TEST_MATRIX.md:18` 與 `docs/zh-Hant/TEST_MATRIX.md:18` 仍寫
`` `JournalLossTransactionTest` (21: … ) `` ／`（21 個…）`，但同一格末尾的模組總數「63 tests／共 63 個」
是**正確的**（我實跑：63 tests, 0 failures；且 5+2+32+1+4+2+13+4 = 63）。所以錯的是逐檔數字，不是總數；
若把逐檔改成 33，總數反而會變成 64 而錯。**正確值是 32。**

同一行還有一個未閉合的括號：`SourcePolicyTransactionTest` (13: … decided here)` 之後直接接
`` `JournalLossTransactionTest` (21: ``，前一個清單的括號沒有收掉（英文與 zh-Hant 版皆同）。

這正是 CLAUDE.md 反覆點名的「docs ahead of code」。

**驗證方式**：`grep -c "@Test"` 逐檔 + `git show <sha>:<path> | grep -c "@Test"` 逐 commit 回溯 +
真機 runner 的 logcat 輸出。**界線**：Kotest 的 JVM 測試不用 `@Test`，這個計數法只對 instrumented
（JUnit 4）檔案有效；JVM 側我改用 gradle 的 XML 報告計數（285）。

### M2 `claimEventLoss` 的 catch 會吞掉 `deferLoss` 自己丟出的 `CancellationException`

`IngestRepository.kt:180-184`：

```kotlin
} catch (e: Exception) {
    if (e is CancellationException) throw e
    runCatching { db.journalDao().deferLoss(eventId) }
    throw e
}
```

而**同一個檔案、同一輪 commit（`4b31f28`）**新寫的 `markJournalRetryable` 用的是正確形狀
（`:292-294`）：

```kotlin
val parked = runCatching { db.journalDao().deferLoss(eventId) }
    .onFailure { if (it is CancellationException) throw it }
    .getOrDefault(0)
```

前者的 `runCatching` 會把 `deferLoss` 途中發生的取消一起吞掉，然後 `throw e` 用原本那個非取消例外繼續
往上拋——違反 CLAUDE.md 的「Best-effort bookkeeping uses `guarded {}`；never swallow
`CancellationException`」。修法就是照 `:293` 補一行 `.onFailure { if (it is CancellationException) throw it }`。

**這一行是 `f3d4407`（round 37/38）引入的，不在本輪 diff 內**；我提出來是因為本輪在同一檔案寫下了
正確的姊妹版本，兩種寫法並存會讓下一位讀者以為兩者等價。

**驗證方式**：讀 `IngestRepository.kt:162-186` 與 `:268-300`；`git log -S` 追出處
（`4b31f28` 與 `f3d4407` 各動過那一行，現行的無守衛版本來自 `f3d4407`）。**界線**：source trace，
我沒有構造一個在 `deferLoss` 中途取消的測試。

### M3 金庫上鎖時，對話框給的補救建議是錯的

`HealthViewModel.kt:133-140` 的 `policyChange` 對任何非取消例外都記一筆 `PolicyFailure`，
`PolicyFailureDialog.kt:32` 依 `settle` 選兩段文案之一。兩段的結尾都是
「請再試一次；若持續失敗，**請檢查裝置的可用儲存空間**」（`values/strings.xml:199-200`、
`values-b+zh+Hant/strings.xml:197-198`）。

但 `VaultUnavailableException`（金庫上鎖）走的是同一條路，而它跟儲存空間無關；更尷尬的是
`HealthScreen.kt:160-172` 就在同一頁上方已經顯示了「金庫已鎖定」的橫幅。可達性：來源列
（`HealthScreen.kt:240-247`）沒有被 `vaultFailure` 擋掉，`stateIn` 會保留最後一次發射的來源清單，
所以「使用者停在健康頁 → 自動上鎖 → 再點開關」是真實的路徑。

**建議**：`policyChange` 對 `VaultUnavailableException` 給第三段文案（「金庫已鎖定，請先解鎖再試」），
或至少讓對話框在 `vaultFailure != null` 時換句話說。

**驗證方式**：讀 `HealthViewModel.kt:104-141`、`PolicyFailureDialog.kt`、`HealthScreen.kt:155-250`、
兩份 strings.xml。**界線**：純 source trace，我沒有在裝置上把金庫鎖起來再點開關。

### M4 現場入口丟棄 `markJournalRetryable` 的回傳值——可以接受，但理由要寫對

`CaptureCoordinator.kt:1010`：

```kotlin
guarded { ingest.markJournalRetryable(snapshot.eventId, e::class.java.simpleName, commitFailureLoss(snapshot)) }
```

回傳值被丟掉，所以現場入口即使拿到 `FAILED_DEFERRED` 也**不會**設 `deferredSettlements = true`
（replay 入口在 `:1291` 會設）。乍看是不對稱，實際上**無害，而且理由是「這條分支不可達」**：

`process()` 在 `if (!ingest.journal(...)) return` 之後才進 `processJournaled`，而 `journal()` 用
`OnConflictStrategy.IGNORE` + `insert(row) != -1L`（`IngestRepository.kt:117-124`），重複的 `eventId`
一律回 false 並提早 return。所以現場入口處理的永遠是一列 `attempts = 0` 的新列，第一次失敗只會走到
`attempts = 1 < MAX_ATTEMPTS(3)`，**永遠到不了耗盡分支**。要 attempts 走到 2 只能經由 replay。

因此 `4b31f28` 宣告的 NC25「the live entrance hands in no record」所固定的，是一個在生產環境中
**不會被觸發的 callback**——測試證明了協調器把紀錄交了出去（這是有價值的、防未來回歸的），
但沒有、也不可能證明它被用到。建議把這點寫進 KDoc 或測試註解，免得下一輪有人把這個不對稱當成 bug 修
（或更糟：當成已驗證的行為）。

**驗證方式**：讀 `CaptureCoordinator.kt:939-1035`、`IngestRepository.kt:106-127`、`:268-300`，
以及 `CaptureCoordinatorTest.kt:2254-2277`（`the live entrance hands in the same record as the replay`
的本體，它自己就是直接呼叫 `handedIn.await()` 拿到的 lambda 再手動 `loss()`）。**界線**：source trace；
我沒有嘗試構造一個能讓現場入口耗盡的情境去證明它真的不可達，只是把三個守衛串起來。

### M5 `connectedDebugAndroidTest` 可以「零測試綠燈」

我第一次跑 `:platform:storage:connectedDebugAndroidTest` 得到 **BUILD SUCCESSFUL、exit 0**，
但 `platform/storage/build/outputs/androidTest-results/connected/debug/TEST-*.xml` 是
`<testsuites tests="0" failures="0" errors="0" .../>`，HTML 報告也是「0 tests」。緊接著手動
`adb shell am instrument` 回 `INSTRUMENTATION_FAILED` / `shortMsg=Process crashed`，logcat 顯示
`W/ActivityManager: Crash of app dev.quietinbox.platform.storage.test running instrumentation`。
再跑一次（乾淨環境、`--rerun-tasks`）就正常了：storage 63、backup 3、health 3，全綠。

也就是說：**instrumentation 根本沒起來的那一次，Gradle 仍然回報成功**。這對 review gate 有實際影響——
`docs/reviews/README.md` 與 release 流程把「emulator lane 綠」當成證據，但 exit code 為 0 不代表跑過任何
測試。最可能的觸發是同一台 `emulator-5556` 被並行使用（本輪有三位審查者共用），我無法確定根因。

**建議**：在跑 instrumented 的地方（CI 或 `docs/RELEASE.md` 的配方）加一句對測試數的斷言，例如從
`androidTest-results/**/TEST-*.xml` 取 `tests="N"` 並要求 `N > 0`（storage 應為 63）。

**驗證方式**：兩次實跑的 XML 與 HTML 報告、`test-result-exit-code.txt`、logcat。**界線**：
我重現了現象但沒有重現根因；不能排除是我這台機器上的並行使用造成的環境問題，而非工具鏈缺陷。

### M6 兩個測試層面的小事

- `theCandidateReadsAgreeOnAllFourValues`（`JournalLossTransactionTest.kt:734`）的**名字**不精確：四個讀取
  在值 1 上刻意**不一致**——`pendingForPackageAfter` 用 `lossRecorded = 0`（`Daos.kt:50`），
  `pending`／`pendingExcluding`／`isReplayCandidate` 用 `< 2`（`:65`、`:73`、`:186`）。
  **好消息是測試本體是誠實的**：`:746` 斷言 replay 讀到 `evt-0, evt-1`，`:749` 斷言走訪只讀到 `evt-0`，
  正好把這個不對稱釘住了。只有名字在暗示一個不存在的對稱性。
- `HealthViewModelTest`（5 個）沒有涵蓋 `addSource`（`HealthViewModel.kt:119`），它也走同一條
  `policyChange`、`settle = false`。不是缺陷，是覆蓋率的一個小缺口。

**驗證方式**：讀測試本體與 DAO 的四個 query。**界線**：我沒有為這兩點寫變異實驗。

---

## 逐項回答 brief 的七個攻擊點

1. **兩位元欄位** — 見專論一。值域封閉在 0–3；resume 必還原正確的 settled 位元；沒有讀取把 3 當成安全；
   「0 但 claim 失敗」在同一交易內不可達，且落在安全側。**無發現。**
2. **`markJournalRetryable` 的交易** — 交易頭部的 `state != "PENDING"` 檢查是**真正的重複守衛**：
   第二次耗盡在 `lossOnExhaust()` 之前就回 `NOT_PENDING`，不會寫第二筆 gap；instrumented
   `aRowThatAlreadyLeftPendingIsNeitherChargedNorRecorded` 在真實 vault 上釘住這點（我跑過，綠）。
   `NOT_PENDING` **不會藏起遺失的紀錄**：一列離開 PENDING 只有四種去處，COMMITTED／SKIPPED／DISCARDED
   本來就沒有遺失，FAILED 則要嘛來自本方法的耗盡分支（帶著紀錄），要嘛來自 `PARSE_`／`DECODE`
   ——後兩者就是 I1，跟這個 `check` 無關。`check(fileFailed == 1)` 在同一交易內不可能為假
   （頭部剛剛確認過 PENDING），它是斷言不是控制流；若真的為假，catch 會把列停放而不是讓 gap 孤立，
   方向正確。現場入口丟棄回傳值 — 見 M4，**可以接受，因為那條分支不可達**。
3. **等待式的 gate** — 見專論二。五個呼叫點我逐一確認都在 `scope.launch` 內、都不持有管線鎖；
   waiter 不會無界堆積也不會 spin（`return` 而非 `continue`，且維護期間沒有 pass 能設起
   `deferredSettlements` 來自我餵食）；被取消的 waiter 只會把旗標留著站著，由下一個持有者服務；
   restore 那行沒有可辨別測試的**推理是對的**。**無發現。**
4. **`lossOnCommit` 的兩個出口** — `IngestRepository.commit` 內 `setState(…, "COMMITTED", …)` 只出現兩次
   （`:374`、`:585`），兩次前一行都是 `lossOnCommit?.invoke()`；兩者之間只有一個
   `return@withTransaction`（`:375`，就是早退出口本身）。**沒有第三個出口。**
   `SKIPPED` 路徑（`CaptureCoordinator.kt:1075`）確實不是交易式的，但它**扛不住這個旗標**：
   `StandardParser.kt:69` 把旗標與 `messages.size >= 2` 做 `&&`，而 `SKIPPED` 的條件是
   `batch.messages.isEmpty() && batch.summary == null`，兩者互斥。
   **adapter 覆寫能不能繞過？不能** — `AppParser.parse` 是 `final override`（`AppParser.kt:56`），
   主線一律 `super.parse(snapshot)`（也就是 `StandardParser.parse`，守衛在裡面）；它自己建構
   `ParsedBatch` 的兩處（`:173` `noticeBatch`、`:200` `summaryOnlyBatch`）都是 `messages = emptyList()`
   且不設旗標；兩處 `base.copy(warnings = …)` 保留 super 的值。adapter 能覆寫的只有
   `wholeMessagesLost(shape, messages)` 這個 protected hook，`&&` 的左邊不歸它管。
   `WhatsAppParser.wholeMessagesLost`（`:78`）第一行就對 `shape.messages`／`historicMessages`／`textLines`
   非空回 false，跟 `appSingleCandidates` 被呼叫的條件完全一致，兩者不可能對「看的是哪個 body」有分歧。
   另外我確認 `commitFenced` 的第二次檢查（`:1095`，第一次在 `:1065`）不可能把一個已解析、帶旗標的批次變成 DISCARDED：
   `enabledPackages` 只在 `pipelineMutex` 下改，而整個 `processJournaled` 在同一次持鎖內，
   所以第二次檢查只會因 `paused`／`maintenance.isActive` 為真而擋下，那兩者都留在 PENDING、之後重播重解析。
   **無發現。**
5. **備份合流** — 多重性不變：`preExisting` 由 count 換成 `ArrayDeque<MessageEntity>`
   （`BackupService.kt:330-336`），命中時 `removeFirstOrNull()` 恰好消耗一列（`:346`），與舊的
   `remaining - 1` 等價。旗標不同時：`:356-357` 只在 `existing.truncationFlags == null` 時才寫，
   所以既有列的旗標**永遠不會被覆蓋或清掉**，備份帶來的不同旗標就被忽略。這是對的，
   因為這個欄位在本 app 的寫入路徑上只會是 `TruncationFlag.TEXT.name` 或 null
   （`IngestRepository.kt:41-47` 的 `truncationColumn`），而讀取端
   （`Mappers.kt:68` `bodyTruncated = !truncationFlags.isNullOrBlank()`）只看空不空、**沒有 `valueOf`**，
   所以一個外來值既不會 crash 也不會被誤讀。**無發現。**（我實跑 `BackupRoundTripTest` 3/3 綠。）
6. **對話框** — `policyChange`（`HealthViewModel.kt:133-140`）確實 rethrow 取消、其餘全部記錄，
   `HealthViewModelTest` 的第五個測試釘住這點。`VaultUnavailableException` 會被**當成被拒絕的變更**
   ——這在「什麼都沒改」這一點上是誠實的，但補救建議是錯的，見 M3。
   「在 gap open 那一步失敗、卻拿到 settle 文案」**可以接受**：`setSourceEnabled(false)` 的
   `health.openGap` 與 `settleCarriedOverLosses` 都是同一個交易裡的 gap 表寫入
   （`CaptureCoordinator.kt:744-757`，`openGap` 在 `:748`），失敗原因同類、補救同一句，而文案的兩個事實斷言
   （「沒有任何變更」「擷取仍在進行」）在兩種情況下都為真。
7. **每個新測試** — 四個負向控制我實跑驗證（見上表），它們都**確實會辨別**。JVM 的
   `Harness.installRetry`（`CaptureCoordinatorTest.kt:312-335`）是誠實的 fake：它計數 attempts、
   執行 record callback、失敗時還原 attempts 並依 `deferralsFail` 回 `FAILED_DEFERRED`／`RETRYABLE`，
   與真實實作的回傳語意一致。**它沒有模擬的是**：交易頭部的狀態檢查、rollback、
   `check(fileFailed == 1)`、以及 `deferLoss` 的 SQL 守衛——這四件事只在真實 vault 上被
   `JournalLossTransactionTest` 判定（我實跑 63/63 綠）。Harness 的 KDoc（`:308-311`）自己把這個分工
   寫明了，這一點值得肯定。`WhatsAppParserTest` 的四個案例（切在分隔符／切在最後一列內／未被切的群組
   ／adapter 不拆分的 body）互為控制，覆蓋了 `truncatedBody && !cutInsideLastRow` 的兩個因子。
   `HealthViewModelTest` 的取消測試會因為拿掉 `if (e is CancellationException) throw e` 而變紅。
   缺口見 M6。

---

## brief 列為「已陳述、不重報」的三項

- 維護中分支的 restore 旗標沒有可辨別測試 — 我確認了推理正確（見專論二），不當作發現。
- round-37 commit message 誇大了 stale-batch 測試、由 `15a5229` 更正 — 我讀過更正，接受。
- `CaptureCoordinatorTest` 那個六跑一紅的偶發 — **我沒有重現。** 在乾淨環境下跑到綠的兩次都是
  73/73；另外四次是 `java.io.EOFException`（Gradle test worker 在 0.5s 內死掉，與並行的 gradle
  build 搶資源有關），那是基礎設施失敗，不是測試失敗，我不把它算成重現。因此我也**無法回答**
  「等待式 gate 是否讓它更容易發生」——沒有觀測到，就不猜。

---

## 給下一輪的一句話

範圍內的五個修正我找不到 Critical 或 Important；兩位元欄位是封閉的，等待式 gate 有真正會辨別的測試
（我跑過 NC27），285 個 JVM 測試與 69 個我親自跑的 instrumented 測試全綠。要動的只有 M1 的三個數字、
M2 的一行守衛、M3 的一段文案，以及 M5 的那一句計數斷言；I1 建議照 #28 的先例開 issue，不要塞進這一輪。
