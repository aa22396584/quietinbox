# Round 38 — Claude subagent（獨立審查）

**審查範圍**：`21ccbc5..50bfc10`（`392e80c` 修正 + `50bfc10` README 列），main，HEAD `29cfaf0`
**性質**：唯讀。主樹只寫這一個檔案。變異實驗全部在自建 worktree `/tmp/qi-r38-sub-wt`（`29cfaf0`）進行，結束時移除。
**未啟動任何 orchestration workflow mode。**

---

## Verdict：**APPROVE WITH MINOR FIXES**

| | 數量 |
|---|---|
| Critical | 0 |
| Important | 1 |
| Minor | 4 |

Round 37 的 Critical（`claimEventLoss` 的 Boolean 契約在並行下被誤讀）**確實被關掉了**，而且是關在兩個層次上：
契約本身（四值 + 在同一個交易內讀出原因）與結構（replay 合流，讓「兩趟 pass 各持一頁」這個前提消失）。
我對這個修正的正確性沒有異議。

我這一輪找到的問題**全部在測試的鑑別力上**，不在產品碼的行為上。最重要的一條是：commit message 宣稱
的九條負向控制裡，**NC23 是空的**——把 round-37 agy I2 / subagent I1 的修正（結清走訪只放回自己來源的列）
在呼叫點一行改回全域版本，JVM 測試 3/3 全綠，instrumented 測試也抓不到，因為它直接呼叫 repository、
從來沒有經過 coordinator。這正是第 35、36、37 輪連續三輪都在抓的同一個形狀：**新加的守衛沒有控制**。

---

## 我實際跑了什麼（先說證據，再說結論）

全部在 `/tmp/qi-r38-sub-wt`（`git worktree add`，detached `29cfaf0`），`ANDROID_HOME=$HOME/Library/Android/sdk`。

| # | 內容 | 結果 |
|---|---|---|
| 1 | 基準 `./gradlew :platform:capture:test` | BUILD SUCCESSFUL，`CaptureCoordinatorTest` **63 tests / 0 failures**（與 `docs/TEST_MATRIX.md:25` 的「63 tests」相符） |
| 2 | NC15（走訪只讀一頁）：`CaptureCoordinator.kt:1323` `after = page.next ?: return` → `return` | **RED** — `the pending rows of a source with more than one page are all settled before it is disabled` |
| 3 | NC16（無條件宣稱進度）：`:1238` `if (!ingest.isReplayCandidate(...))` → `if (true)` | **RED** — `a replay that cannot even defer a row does not claim it made progress` |
| 4 | NC17（resume 移回 pass 開頭）：`:1192-1197` 換成 `break`，並在 `:1175` 前插入全域 resume | **RED** — `a failing prefix longer than a whole pass...` |
| 5 | NC18（拿掉合流）：`:1157-1167` 整段換成 `replayPass()` | **RED 7 次 / GREEN 1 次（8 次 `--rerun`）** — 見 Minor 1 |
| 6 | NC19（deferred 當成 recorded）：`:1230` `if (settled.getOrDefault(false) != true)` → `if (settled.isFailure)` | **RED** — `a row another pass deferred is not committed by a batch that predates the deferral` |
| 7 | NC23（走訪改回全域 resume）：`:1311` `resumeDeferredSettlements(packageName)` → `resumeDeferredSettlements()` | **GREEN 3/3** — 見 Important 1 |
| 8 | round-37 subagent M2 重跑：拿掉 `:583` 的 `deferredSettlements = false` | **GREEN 2/2** — 見 Minor 3 |
| 9 | 跨模組 caller 盤點（`claimEventLoss` / `recordCarriedOverLoss` / `resumeDeferredSettlements`，排除 test） | 見 Seam 2 |

**證據的界限**：

- 我**沒有跑 instrumented 測試**。NC20（`deferLoss` 的 `lossRecorded = 0` 守衛）、NC21（整頁不可解碼）、
  NC22（索引 seek）三條控制都只活在 `:platform:storage:connectedDebugAndroidTest`。我判斷它們是
  **確定性的 SQL / query-plan 斷言**，可以靠閱讀判定（理由寫在 Seam 6 表格內），而且我這一輪的任何一條
  發現都不依賴它們；同時 `emulator-5556` 這一輪由三位審查者共用，我不想在別人跑到一半時 install/uninstall。
  **所以：NC20–NC22 是我的推論，不是我的實跑。** 這一點請以 Codex / agy 的執行結果為準。
- 我沒有重跑完整的 268 條 JVM、60 條 instrumented、`lint`、`check-permissions.sh`、`check-strings.py`。
  我只重跑了 `:platform:capture`。
- 併發相關的結論（Seam 1、2、5）是**原始碼追蹤 + JVM 記憶體模型 / Android SQLite 交易語意的推理**，
  我沒有對真實金庫構造兩個並行 coroutine 去實測。每一條我都在下面標明了。

---

## Important

### I1. 結清走訪「只放回自己來源」這個修正，在它被決定的那一層沒有任何控制

**位置**：`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1311`

```kotlin
ingest.resumeDeferredSettlements(packageName)
```

**問題**：把這一行改成 `ingest.resumeDeferredSettlements()`（也就是 round 37 之前的行為，agy I2 / subagent I1
指出的缺陷），`:platform:capture:testDebugUnitTest --rerun` **連跑三次全綠**。

instrumented 那一側也抓不到。新加的 `resumingOneSourcesDeferredRowsLeavesAnothersAlone`
（`JournalLossTransactionTest.kt`）測的是 **repository**：

```kotlin
ingest.resumeDeferredSettlements(pkg) shouldBe 1
ingest.isReplayCandidate("evt-mine") shouldBe true
ingest.isReplayCandidate("evt-theirs") shouldBe false
```

它證明的是「per-source 這個 DAO 語句本身正確」，不是「走訪選了哪一個 overload」。這兩件事之間就是缺陷所在。

**我怎麼確認沒有別的東西守著它**：`resumeDeferredSettlements` 在
`platform/capture/src/test/kotlin/.../CaptureCoordinatorTest.kt` 全檔只出現兩次（`:320`、`:329`），
兩次都是 `coEvery` 的 fake 定義，**沒有任何一處 `coVerify`**。所以呼叫點的選擇從頭到尾沒有被斷言過。

**為什麼是 Important 而不是 Critical**：這個守衛保護的不是資料。全域 resume 跑在 A 來源的政策交易裡，
最壞的後果是 B 來源的 deferred 列被 `2 → 0`（交易若回滾則再被拉回 `2`），加上一次全表 update。
沒有 payload 被清、沒有缺口漏記。Round 37 裡 agy（I2）與 subagent（I1）都把它評為 Important 而非 Critical；
Codex 在「全域 resume 放在來源政策交易內」一段討論過它，但沒有列進編號的 finding 清單。
但「一行改回去而全綠」這件事本身，在這個專案已經是第四輪重複出現的形狀，值得一條控制。

**怎麼修（約十行，harness 已經備好）**：`Harness` 的 `resumeDeferredSettlements(any<String>())` fake
（`CaptureCoordinatorTest.kt:329-339`）已經照 `packageName` 正確地縮限了範圍，全域版 fake（`:320`）則會清空
`lossDeferred`。所以只要：兩個來源各放一列到 `pendingByPackage` / `pendingReplay`、各自 defer 一次
（`gapWritesFail = true` 走一趟 replay，或直接把 id 放進 `lossDeferred`）、然後
`coordinator.setSourceEnabled(A, false)`，最後斷言 **B 的 id 仍在 `h.lossDeferred` 裡**。
改回全域 overload 時這條會紅。

**我怎麼驗證的**：實跑（變異 3 次全綠）+ 測試檔全文檢索。**界限**：我沒有寫出那條測試來證明它會紅；
我證明的是「現在沒有任何東西會紅」。

---

## Minor

### M1. NC18（合流）是真的控制，但它的鑑別力只有 8 分之 7

**位置**：`CaptureCoordinatorTest.kt`，測試 `two triggers arriving together run one replay pass, not two`

commit message 說九條控制「each verified red on the intended test」。NC18 這一條我第一次跑是 **綠的**，
於是我把同一個變異（`CaptureCoordinator.kt:1157-1167` 整段合流換成單純的 `replayPass()`）用
`--rerun` 連跑八次：

```
run 1: RED   run 2: RED   run 3: GREEN   run 4: RED
run 5: RED   run 6: RED   run 7: RED     run 8: RED
```

7 紅 1 綠。它是真的控制，但**不是確定性的**。我另外把斷言臨時改成 `shouldBe 999` 把實測值印出來，
變異版單獨跑時拿到 `entries=2 max=2`——也就是**兩趟 pass 確實重疊了**，測試在多數情況下看得到。

**為什麼偶爾看不到（模型，不是實測）**：測試的兩個觸發是 `vaultState = Ready`（`:309`）與
`setPaused(false)`（`:413`），中間夾著 `setPaused(true)`，而 `paused = value` 是**同步寫入**的
（`CaptureCoordinator.kt:380`）。`replayPass` 的迴圈條件是 `while (progressed && rounds++ < replayRounds && !paused)`
（`:1178`）。如果第一趟 pass 剛好在 `paused == true` 的那個極短視窗裡求值，它一輪都不跑、
連 `pendingJournal` 都不會進去——於是只觀察到一趟。

**這不會讓 CI 變不穩**：未變異的程式碼上 `maxConcurrent` 被閘門限制成 1，測試不可能偶爾變紅。
壞的方向只有一個：守衛若被拿掉，CI 有約八分之一的機率放它過去。

**怎麼修**：不要靠時序去製造重疊，直接強制它。在 `pendingJournal` 的 stub 裡讓第一次進入的呼叫等一個
`CompletableDeferred`，測試在確認第一趟已經進去之後再送第二個觸發，然後才 complete 它。
重疊變成必然，控制就是確定性的。

**我怎麼驗證的**：實跑八次 + 一次帶診斷輸出的單獨執行。**界限**：綠的那一次的成因是我的模型，
我沒有加 log 去證明那一次確實落在 `paused == true` 的視窗裡。

### M2. commit message 描述了一條不存在的測試（「兩趟 pass + latch」）

commit message 寫：

> The stale-batch commit (the Critical), as a JVM test with two passes and a latch between the page
> read and the claim.

round-38 BRIEF 也照抄了（「the stale-batch Critical with a latch between page read and claim」）。

實際加進去的測試 `a row another pass deferred is not committed by a batch that predates the deferral`
**沒有 latch、沒有第二趟 pass**。它做的是把契約直接釘住：

```kotlin
coEvery { h.ingest.claimEventLoss(any(), any()) } returns LossClaim.DEFERRED
```

我在 `CaptureCoordinatorTest.kt` 與 `JournalLossTransactionTest.kt` 全文搜尋 `latch` / `CountDownLatch`，
只命中 `:61` 的一句註解，沒有任何 latch。

**這不是覆蓋率的洞**：NC19 是紅的（我實跑），所以這條契約測試足以釘住守衛；用 stub 直接回
`DEFERRED` 甚至比重現交錯更確定。**問題純粹是文件跑在程式碼前面**——而 `CLAUDE.md` 把這件事列為專案的
硬規則，前幾輪也每一輪都被指出過。

**怎麼修**：把 commit message 那一句改成它實際做的事（「以 `claimEventLoss` 回 `DEFERRED` 的 stub 釘住契約，
交錯本身由合流閘門在結構上排除」），或補上真正的 latch 測試。前者成本是零，而且更誠實。

**我怎麼驗證的**：讀 diff 全文 + 全文檢索。

### M3. round-37 subagent M2 仍然是綠的，而且沒有出現在「已修」或「已知接受」的任何一張清單上

`CaptureCoordinator.kt:581-585`：

```kotlin
private fun retryDeferredSettlements() {
    if (!deferredSettlements) return
    deferredSettlements = false
    scope.launch { replayJournal() }
}
```

拿掉 `deferredSettlements = false` 這一行，`:platform:capture:testDebugUnitTest --rerun` **連跑兩次全綠**
（實跑）。也就是 KDoc `:570-572` 那句設計主張——「At most one replay per deferral」——到今天仍然沒有控制。

round 37 我把它報成 M2。這次 commit message 逐條交代了 round 37 的其他項目（M1 的 KDoc、M3 的
`state = 'PENDING'`、M4 的 `pm clear`、Codex M1 的前提、Codex M2 的接受理由），**唯獨沒有提到 M2**；
round-38 BRIEF 的「Accepted with reason, not fixed — do not re-report」清單裡也沒有它。所以它既不是
已修、也不是已知接受，而是掉了。

**危害仍然很低**（旗標本來就會被下一趟 defer 的 pass 重設；金庫真的壞掉時根本不會有成功的 gap write 來武裝它），
但現在合流閘門讓這句主張比以前更真，反而更值得一條測試釘住：兩次連續的 `retryDeferredSettlements()`
只應該產生一趟 pass。**或者**明確寫進「知道、接受、理由如下」。

**我怎麼驗證的**：實跑 2 次 + 比對 commit message 與兩份 BRIEF 的清單。

### M4. resume 落在最後一輪時，那批列會退回候選集而沒有任何東西武裝重試

`CaptureCoordinator.kt:1178-1198`。迴圈上界是 `rounds++ < replayRounds`，drain 之後的 resume 走 `continue`。
如果 drain 恰好發生在**最後一輪**，`continue` 之後迴圈條件就不成立了：那批列已經被
`resumeDeferredSettlements()` 從 `2` 改回 `0`，但**這一趟 pass 沒有重試它們**，因此
`deferredSettlements = true`（`:1239`）不會被設起來。

後果不是資料遺失——列仍是 `PENDING`、payload 完整、而且已經回到候選集，任何後續的 pass 都會第一個讀到它們。
真正的差別是：正常路徑下這批列由「下一次寫成功的 gap」武裝重試（by proof），這個邊緣情況下它們只能等
lifecycle 觸發。

也就是說，**這一輪明確接受的 residual（「a device with deferred rows, no further lossy event and no
lifecycle trigger waits, payload intact」）多了一條抵達路徑**。我同意 round 37 的判斷——這仍然比 timer 好——
所以我不要求改行為，只建議把這條路徑寫進 KDoc 或 SCOPE 的 residual 敘述裡，因為它跟已敘述的那條起因不同
（那條是「列還 deferred」，這條是「列已經不 deferred 了但沒人被叫醒」）。

需要 `replayRounds` 恰好用盡「而且」剛好在 drain 那一輪，機率極低。

**我怎麼驗證的**：逐行追迴圈的狀態轉移。**界限：純推理，未實跑。**

---

## BRIEF 的七個接縫，逐條回答

### 1. 合流閘門：`tryLock` + 一個 `@Volatile` Boolean，沒有 atomic

**「有沒有一種順序，讓旗標是 true、沒有 pass 在跑、而且沒有人會去跑」？——沒有。**

唯一一個「旗標可能是 true 而呼叫者離開」的出口是 `tryLock()` 失敗（`:1158`）。`tryLock()` 失敗 ⟹ 持有者
此刻**還沒 unlock**。所以持有者的 unlock 一定在請求者的旗標寫入之後，而持有者 unlock 之後的
外層 `while` 會再讀一次旗標（`:1158`），必然讀到 true。

**`@Volatile` 夠嗎？兩者都需要，而且分工不同。** 用 JMM 的同步順序講：

- 請求者：`W1 = replayRequested 的 volatile 寫`，接著 `R2 = Mutex 狀態的 atomic 讀`（讀到「已鎖」）。
- 持有者：`W3 = Mutex 狀態的 atomic 寫（unlock）`，接著 `R4 = replayRequested 的 volatile 讀`。

`R2` 讀到 `W3` 之前的值 ⟹ 同步順序上 `R2 < W3`。加上程式順序 `W1 <po R2`、`W3 <po R4`，得
`W1 < R2 < W3 < R4`，所以 `R4` 讀得到 `W1`。

結論：**`@Volatile` 是必要的**（沒有它，`R4` 可以讀到快取的舊值）；**Mutex 的 CAS 才是把「tryLock 失敗」
從一個牆鐘事實變成一個同步順序事實的東西**。兩者缺一不可，現在的寫法是對的。

**內層迴圈的順序也對**：`replayRequested = false` 在 `replayPass()` **之前**（`:1161-1162`），所以 pass 執行
期間到達的請求會把旗標重新設起來，內層迴圈出來時看得到。反過來寫（先跑再清）會吃掉那個請求。

**`CancellationException`**：`finally { replayGate.unlock() }`（`:1164-1166`）是非 suspend 的，取消時一定會跑，
閘門一定被釋放。但例外會往外拋，**外層 `while` 不會再被求值**，所以旗標可能停留在 true 而閘門空著。

這**不是**遺失請求：下一次 `replayJournal()` 進來時旗標已經是 true，`tryLock` 成功，內層迴圈把它清掉並
**恰好跑一趟**（不是兩趟）。而且每一個取消源都有自己的重新觸發——維護結束在 `:848`、下一個 vault Ready
在 `:309`、`collectLatest` 的下一次 emission。我認為這是可接受的，不需要改。

**非取消的例外能逃出 `replayPass()` 嗎？——不能。** `guarded {}`（`:1328-1335`）吞掉除了
`CancellationException` 以外的一切；`maintenance.work` 在維護進行中是 **return null 而不是 throw**
（`VaultMaintenance.kt:66`）。而 `scope` 帶 `SupervisorJob() + crashGuard`（`:125-129`），所以即使漏出去也不會
拖垮 scope。

**死鎖**：鎖序只有一個方向——`replayGate` → `pipelineMutex`（每個事件一次，`:1204`）。
沒有任何路徑持著 `pipelineMutex` 去等 `replayGate`：`retryDeferredSettlements`（`:584`）雖然在
`pipelineMutex` 底下被呼叫，但它只 `scope.launch`，不 await。其餘四個觸發點（`:309`、`:413`、`:771`、`:848`）
都不持鎖。**安全。**

**唯一一個我想指出但不算缺陷的行為**：如果維護正在進行（`work` 立刻回 null）而觸發又持續到達，
內層迴圈會每個觸發空轉一圈。觸發本身被 lifecycle 事件限制住，而且空轉的 pass 不會 defer 任何東西、
因此不會重新武裝 `deferredSettlements`，所以不會變成 busy loop。

**證據的界限**：以上全部是原始碼追蹤 + JMM 推理，**未實測交錯**。實測的只有 NC18（見 M1）。

### 2. rollback 之後的 `deferLoss`

`IngestRepository.kt:168-172` 的 catch **只在「認領成功、但 `writeGap` 拋了」時才會到達**。也就是說走到
`deferLoss` 的那個呼叫者，它的 claim 是**贏了**的，rollback 之前那一列在 `0`。

**「rollback 與 `deferLoss` 之間，另一趟 pass 能不能把它 `0 → 1` 並寫好缺口？」——不能。**
我重跑了跨模組盤點（第一次因為 zsh 的 glob 展開失敗，`--include=*.kt` 沒有被 grep 收到，這次把樣式加了引號）：

```
platform/capture/.../CaptureCoordinator.kt:1281:  return ingest.claimEventLoss(snapshot.eventId) {
platform/storage/.../IngestRepository.kt:150:     suspend fun claimEventLoss(...)
```

`claimEventLoss` 在整棵樹（排除測試）**只有一個呼叫者**：`recordCarriedOverLoss`。而它的兩個呼叫點——
replay 的 `:1222` 與走訪的 `:1319`——**都在 `pipelineMutex` 底下**（`:1204` 的 `withLock`，以及
`changeSourcePolicy` 的 `:485`）。所以任兩次針對同一個事件的認領是被序列化的，那個視窗不存在。

**即使它存在**，`deferLoss` 帶著 `lossRecorded = 0 AND state = 'PENDING'`（`Daos.kt:136`），會取到 0 列；
`aSettledRowCannotBeWalkedBackToDeferred` 在真實金庫上釘住了這一點（我未實跑，見界限）。

**「第一趟 pass 會不會把 DEFERRED 回報給呼叫者，而列其實已經結清？」——不會，因為那條路根本不回傳。**
claim 失敗的呼叫者走的是 `when` 分支、正常回傳；進到 catch 的是 claim **成功**的呼叫者，而它最後
`throw e`。兩個呼叫點對「拋例外」的處理與對 `false` 完全一樣（`:1230` 的 `settled.getOrDefault(false) != true`
同時涵蓋兩者；`:1319` 的 `check` 則根本收不到回傳值就被例外穿過去）。所以沒有任何下游會拿到過期的字眼。

**一個值得寫下來的細節**：走訪路徑上，那句 KDoc 說的「在被回滾的交易**之外**、開一個新的隱含交易」
其實不成立——走訪跑在來源政策交易裡（`SourceRepository.kt:66` 的 `db.withTransaction`），內層交易失敗會把
外層一起標成必須回滾，所以 `deferLoss` 這一筆也會被回滾掉。這**正是 `:1309-1310` 的註解所要的**
（「a settle that then fails takes the resume back with everything else」），行為一致，只是 `IngestRepository.kt:143`
的措辭在走訪這條路上讀起來會誤導。不值得算成一條發現，但如果要一併整理措辭，這是一處。

**證據的界限**：caller 盤點是實跑的 grep；巢狀交易語意是 Android `SQLiteSession` 的行為推理，**未實測**。

### 3. 交易內讀原因：`lossRecorded = 0` 但 claim 沒更新到，可能嗎？

**不可能。** `claimLoss` 的述詞（`Daos.kt:123`）是 `eventId = ? AND lossRecorded = 0 AND state = 'PENDING'`，
`pendingLossState`（`:170`）是 `eventId = ? AND state = 'PENDING'`——**前者剛好是後者加上 `lossRecorded = 0`**，
而且兩句跑在同一個 `db.withTransaction`（`IngestRepository.kt:153-167`）裡，`eventId` 是列的鍵。
在一個 SQLite 寫交易內沒有別人能改這一列。所以「claim 取到 0 列 ⟹ 不是 `state ≠ PENDING`（`null`）就是
`lossRecorded ≠ 0`」，映射是完備的。

這個欄位也只可能是 0/1/2（`Entities.kt:53-59`；寫入點只有 insert 的 0/1、`claimLoss` 的 1、`deferLoss` 的 2、
兩個 resume 的 0）。所以 `else -> DEFERRED` 就是 `2`，沒有第三種解讀。

**假設真的出現了壞值**（例如外部工具改過金庫），把它叫成 `DEFERRED` 是**保守**的答案：不 commit、
不清 payload、`isReplayCandidate` 仍為 true（`!= 2`），代價是每趟 pass 多讀一輪。fail-closed，方向正確。

**證據的界限**：SQL 述詞比對 + 寫入點盤點，**未實跑**。

### 4. resume-at-drain 的迴圈上界

**會終止。** `continue`（`:1197`）之後 `progressed` 一定是 true——能進到 empty-batch 分支就代表迴圈條件成立，
而該分支不會把 `progressed` 設成 false。所以下一輪一定會去讀那批被放回來的列。之後每一列只有三種去向：
結清（`progressed = true`）、defer 成功（`!isReplayCandidate` ⟹ `progressed = true`）、連 defer 都失敗
（不算進度，下一次條件檢查就跳出）。再下一輪的空頁撞上 `if (resumed) break`（`:1192`）。**最多多兩輪。**

**「一趟 pass 一次 resume」是不是對的數字？——對，而且 NC17 證明了它。** 我實跑把 resume 搬回 pass 開頭，
`a failing prefix longer than a whole pass...` 變紅，而且**只有這一條紅**。

**「會不會讓中途復原的裝置慢一個觸發？」——會，慢一趟 pass**，而且有一個更細的邊緣（M4）：drain 恰好落在
最後一輪時，那批列被放回候選集但沒被重試，`deferredSettlements` 也沒被設起來，於是「by proof」的武裝失效，
只剩 lifecycle 觸發。沒有資料遺失，payload 完整。詳見 M4。

### 5. replay 的全域 resume vs 走訪的 per-source resume

**兩者不可能交錯。** 走訪跑在 `changeSourcePolicy`（`:484-489`）內，那裡**整段持著 `pipelineMutex`**；
再往內是 `SourceRepository.setFlag` 的 `db.withTransaction`（`SourceRepository.kt:66`）。Android 的
`beginTransaction` 會立刻取得寫鎖，所以 replay 在 `pipelineMutex` 之外發出的那筆全域 resume 寫入（`:1195`）
**會被 SQLite 擋住**，直到政策交易 commit 或 rollback。

交易 commit 之後，該來源的列已經被 `discardPendingJournal` 移出 `PENDING`，而**兩個 resume 現在都帶
`AND state = 'PENDING'`**（`Daos.kt:151`、`:160`）——這正是 round-37 subagent M3 補上的那個守衛，
它讓這個順序是**安全的**而不只是**運氣好**。全域 resume 放不回一列已經被丟棄的列。

**所以走訪的 `check` 不會因為並行 replay 而觸發。** 我進一步找不到任何可達的路徑讓它觸發：
頁查詢已經濾掉 `state ≠ PENDING` 與 `lossRecorded ≠ 0`（`PENDING_FOR_PACKAGE_AFTER`），走訪自己先
resume 過同來源的列，交易內沒有別人能寫這一列，`eventId` 是主鍵，而且 snapshot 的 `eventId` 與列的鍵
必然一致（`IngestRepository.kt:105` 的 insert 兩者同源）。**它是一條防禦性斷言。**

**證據的界限**：原始碼追蹤 + Android 交易語意推理。**我沒有對真實金庫構造兩個並行 coroutine 去實測。**
這是我這一輪最想實測而沒有實測的一條。

### 6. 九條負向控制，逐條當成主張來檢驗

| NC | 主張 | 我的結果 |
|---|---|---|
| NC15 走訪只讀一頁 | 紅 | **實跑 RED**，真的 |
| NC16 無條件宣稱進度 | 紅 | **實跑 RED**，真的 |
| NC17 resume 放在開頭 | 紅 | **實跑 RED**，真的 |
| NC18 沒有合流 | 紅 | **實跑：8 次中 7 紅 1 綠** — 真的，但非確定性（M1） |
| NC19 deferred 當成 recorded | 紅 | **實跑 RED**，真的 |
| NC20 `deferLoss` 拿掉守衛 | 紅 | **未實跑**。推論為真：`aSettledRowCannotBeWalkedBackToDeferred` 直接呼叫 `journalDao().deferLoss(...) shouldBe 0`，拿掉 `lossRecorded = 0` 會回 1。確定性 SQL |
| NC21 空頁停住走訪 | 紅 | **未實跑**。推論為真：p3/p4 被弄壞之後正確分頁是 `[q1,q2] / [] / [q5]`，`visited shouldBe listOf(p1,p2,p5)` 加上 `second.next shouldNotBe null` 兩條一起釘住 |
| NC22 索引 seek 失效 | 紅 | **未實跑**。推論為真：`+ 0` 之後 row value 不再是索引範圍，plan 就不會含 `(receivedAtEpochMs,eventId)>(?,?)` |
| NC23 走訪改回全域 resume | 紅 | **實跑 GREEN 3/3 — 這條是空的**（I1） |

（NC20–NC22 的「未實跑」理由與界限見上方「我實際跑了什麼」一節。）

### 7. 每一條新測試，對抗性地看：哪一行產品碼的改動會讓它紅？

| 測試 | 一行改動 | 我的判定 |
|---|---|---|
| `a row another pass deferred is not committed by a batch that predates the deferral` | `:1230` 改回 `if (settled.isFailure)` | **實跑 RED**。契約被釘住了。但它不是 commit message 說的「兩趟 pass + latch」（M2） |
| `a replay that cannot even defer a row does not claim it made progress` | `:1238` 的 `isReplayCandidate` 守衛改成 `if (true)` | **實跑 RED** |
| `a failing prefix longer than a whole pass...` | resume 搬回 `:1175` 之前 | **實跑 RED** |
| `the pending rows of a source with more than one page are all settled before it is disabled` | `:1323` 只讀一頁 | **實跑 RED**。另外它也守著 `settleCarriedOverLosses` 與 `discardPendingJournal` 的**順序**（`:752-753`），因為 fake 的 `discardPendingJournal` 會真的清空 `pendingByPackage` |
| `two triggers arriving together run one replay pass, not two` | 拿掉合流 | **實跑 7/8 RED**。見 M1 |
| `aClaimSaysWhetherTheGapIsOnDiskOrOnlyThatItTookNothing`（instrumented） | 把 `pendingLossState` 的四路映射壓成兩路 | 未實跑；四個 case 各有一條斷言，判定為真 |
| `aPageOfUndecodableRowsIsEmptyAndStillPointsOn`（instrumented） | `next` 改成「只有解出東西才給游標」 | 未實跑；`second.next shouldNotBe null` 直接釘住，判定為真 |
| `resumingOneSourcesDeferredRowsLeavesAnothersAlone`（instrumented） | DAO 拿掉 `AND packageName = :packageName` | 未實跑；判定為真。**但它抓不到呼叫點的選擇——見 I1** |
| `aSettledRowCannotBeWalkedBackToDeferred`（instrumented） | `deferLoss` 拿掉 `lossRecorded = 0` | 未實跑；判定為真 |
| `theSettleWalkSeeksToItsCursorInsideTheIndex`（instrumented） | `(receivedAtEpochMs + 0, eventId)` | 未實跑；新增的 `plan shouldContain "(receivedAtEpochMs,eventId)>(?,?)"` 判定為真。改用 `PENDING_FOR_PACKAGE_AFTER` 常數（`Daos.kt:47`）+ `Regex(":\\w+")` → `?` 之後，四個位置參數的順序（packageName, afterTime, afterId, limit）與 `arrayOf(pkg, 100L, "evt-a", 200)` 對得上，我逐一核過 |

---

## BRIEF「已接受、請勿重報」兩條，我的看法

- **Codex M2（chip 措辭沒有測試）**：我同意這個理由。chip 測試讀的是字串目錄，再寫一條措辭測試就是在測
  `strings.xml` 自己。而 `tools/check-strings.py` 已經守住五份目錄的名稱 / placeholder / plural 一致性。
  **我不主張它應該加測試。**
- **`ORDER BY` 拿掉 `eventId` 在複合索引供序時是惰性的**：同意。那個子句是索引改變之後仍然成立的保證，
  留著是對的。

## 範圍外四項，我確認沒有回歸

issue #28、round-35 Codex I1 / I2、round-36 subagent I2 —— 這次 diff 沒有動到它們的任何一條路徑。
一個補充事實（**不是新發現**）：這次新加的 `check(recordCarriedOverLoss(snapshot))`（`:1319`）是一條新的
「中止政策交易」路徑，而 round-36 subagent I2 說的正是那條中止會被 `HealthViewModel` 的 `runCatching {}` 吞掉
（`HealthViewModel.kt:107`、`:114-115`，我核過兩個呼叫點都包了 `runCatching`）。所以它擴大了那個已知缺口的
**理論**表面。但我在 Seam 5 論證了 `check` 目前不可達，所以這是**脈絡，不是發現**——I2 修好的時候會一併蓋掉。

## residual 的立場

「a device with deferred rows, no further lossy event and no lifecycle trigger waits, payload intact」——
**我仍然同意這比 timer 的 residual 好**，理由與 round 37 一樣：deferral 的成因就是金庫拒絕寫入，
timer 會重試進一個仍然全面拒絕的金庫，而重試本身就是把列重新 defer 的那個動作。
我只補充 M4：這個 residual 現在多了一條抵達路徑，值得在敘述裡點一句。

---

## 四值契約是把接縫關掉了，還是只是把它搬走了？

**在 repository 那一層，它是真的關掉了。** 關鍵不是「四個值」而是**原因在 claim 自己的交易內被讀出來**
（`IngestRepository.kt:153-167`）：更新取到幾列、與為什麼取到那麼多列，兩件事之間沒有任何窗口讓別人改動那一列，
所以解釋不可能與更新脫節。這比「回傳一個更豐富的列舉」強得多——一個在交易外讀原因的版本會有一模一樣的
enum 而仍然是壞的。而 `gapIsDurable`（`:558`）把「哪些值可以往終局走」收斂成一個屬性，兩個呼叫點
（`:1230` 的 `!= true`、`:1319` 的 `check`）都只問這一個問題，這讓契約沒有第二種讀法。

**結構那一半則是把前提整個拿掉，而不是把它擋住。** 「一頁在 pipeline 鎖外被讀出來」這件事沒有改變；
改變的是**不再有第二趟 pass 可以在你手上那一頁還沒處理完的時候去 defer 它**。replay-對-replay 的
stale batch 因此不再可達，而不是被偵測到之後被拒絕——四值契約在這裡成了第二道防線（belt and braces），
這是對的設計，因為 replay-對-**走訪** 這一組並不受合流保護，靠的是 `pipelineMutex` 加上 SQLite 的單寫者，
而那條路徑上契約仍然是唯一的保險。

**被搬走的東西有一項，我認為要說清楚**：`check(...)`（`:1319`）把「證據沒上磁碟」從**默默繼續**變成
**中止整個政策交易**。行為方向是對的（走訪之後的 discard 會永久清掉 payload，沒有第二次機會），
但它把一個新的失敗出口接進了 round-36 subagent I2 那個已知的靜默失敗面。在目前的鎖與交易語意下它不可達，
所以今天不是缺陷；但這是這次修正唯一一處「把問題移到別處」而不是「把問題關掉」的地方，
而它移到的正好是一個已經開著的洞。I2 修好之前，這一點值得記在 SCOPE 裡。

---

## 修正清單

| # | 嚴重度 | 內容 | 成本 |
|---|---|---|---|
| 1 | Important | 為 `CaptureCoordinator.kt:1311` 的 per-source resume 補一條 coordinator 層的測試（兩來源、各一列 deferred、停用其中一個、斷言另一個的列仍 deferred） | 約 10 行，harness 的 fake 已經備好 |
| 2 | Minor | 讓 `two triggers arriving together run one replay pass, not two` 用 `CompletableDeferred` 強制重疊，取代靠時序 | 約 6 行 |
| 3 | Minor | 修正 commit message 對 stale-batch 測試的描述（沒有 latch、沒有兩趟 pass） | 一句話 |
| 4 | Minor | round-37 subagent M2：補一條「一次 deferral 只重試一次」的控制，或明寫接受 | 約 8 行，或一句話 |
| 5 | Minor | 把 M4 那條 residual 路徑寫進 `:1184-1198` 的註解或 SCOPE | 一句話 |

**沒有任何一項需要改產品碼。** 這是我給 APPROVE WITH MINOR FIXES 而不是 REQUEST CHANGES 的理由：
四值契約與合流閘門的行為我逐條追過，找不到缺陷；缺的是測試的鑑別力，而其中最重要的那一條（I1）
保護的是耦合而不是資料。

---

*審查者：Claude Opus 5（1M context）subagent。變異實驗 worktree `/tmp/qi-r38-sub-wt`（`29cfaf0`），已移除。*
