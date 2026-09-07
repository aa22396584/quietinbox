# Round 31 迷你再審 — Claude subagent（Opus 5, 1M context）

範圍：`fe0a5b0`（單一修正 commit，`b373146..fe0a5b0`），分支 `main`，唯讀審查。
本輪另一位 reviewer：Gemini 3.8 Flash (high, via agy)；Kimi 仍被週配額擋住（`kimi-blocked.md`）。

兩項揭露：

1. 我沒有讀 agy 的本輪報告。唯一的例外是 `grep 088cc11` 時，同一次輸出把它的兩行帶了出來——而那一項
   我在**同一個指令**裡已經獨立查到（`git cat-file` / `git merge-base`），不是從它那裡得知的。
2. 「這個測試在修正前的版面上會不會失敗」我不是用推論回答的，而是實跑：用 `git archive HEAD`
   把工作樹**唯讀地**匯出到 scratchpad 的一份副本，在那份副本上還原舊版面再跑測試。本 repo 沒有被
   建立 worktree、沒有被 stash、沒有被修改（`git status` 只有本報告目錄）。

## 本輪實際跑過的驗證

| 檢查 | 結果 |
| --- | --- |
| `./gradlew test --rerun-tasks`（388 tasks executed） | BUILD SUCCESSFUL，**232 tests / 0 failures / 0 errors / 0 skipped**，與 commit message 相符 |
| `./gradlew lint`（全模組，`abortOnError = true`、無 baseline） | **BUILD SUCCESSFUL** |
| `python3 tools/check-strings.py` | `OK: 0 error(s), 0 warning(s)` |
| `ANDROID_SERIAL=emulator-5556 ./gradlew :feature:conversation:connectedDebugAndroidTest`（QuietInbox_Phone AVD, API 36） | **BUILD SUCCESSFUL，2 tests / 0 failures**（`theSenderAndTheBodyAreOneNodeInTheMergedTree` 1.672s、`theyAreStillDrawnAsSeparateTextsUnderneath` 4.493s） |
| **反事實 1**：scratchpad 副本還原成 `edd261f` 的版面（發送者在 clickable column **外**，外層掛 `semantics(mergeDescendants = true)`），跑同一組測試 | **FAILED**：`theSenderAndTheBodyAreOneNodeInTheMergedTree` — `Expected exactly '1' node but could not find any node`。負向控制通過 |
| **反事實 2**：scratchpad 副本改成**單一串接的 `Text("$sender\n$body")`** | **FAILED**：`theyAreStillDrawnAsSeparateTextsUnderneath` — `Did not expect any node but found '1' node`。正向斷言通過 |
| 獨立 repro（scratchpad `r30/repro`，忠實模型：`debounce(250)` → 同一組比較器的 `distinctUntilChanged` → `run()`／`loadMore()`／generation），14 種操作序列 | **全部 `spinner=false`、`loadingMore=false`**；且真正過期的頁面仍被丟棄 |
| `tools/check-permissions.sh` | **未跑**（需 release APK）。`fe0a5b0` 的 diff 不含 `AndroidManifest.xml`、`build.gradle.kts`、`libs.versions.toml`、`verification-metadata.xml` |
| `git status --short --branch` | `## main...origin/main [ahead 7]`，僅本報告目錄為未追蹤；測試與 lint 沒有弄髒 repo |

---

## Verdict：**APPROVE WITH MINOR FIXES**

Critical **0** · Important **1** · Minor **7**。

上一輪的每一項都真的關閉了，而且其中三項是**實跑證實**的，不是讀出來的：轉圈的 Critical
（14 種序列的 repro）、新的語意測試是否有鑑別力（兩次反事實實跑）、以及 232 個 JVM 測試。
唯一的 Important 不是產品缺陷，而是**這個新測試沒有被任何 runner 執行**——CI 沒有它，五份「跑裝置
測試」的清單也都沒有它，於是 commit message 那句「the assertion is now a test rather than an
argument」目前並不成立：沒有人會跑到的斷言，仍然只是一個論證。

### 逐項裁決

| 上一輪的發現 | 狀態 | 依據 |
| --- | --- | --- |
| C-NEW `run()` 的 generation 守衛造成永久轉圈 | **關閉** | `SearchViewModel.kt:140` 改比 query/range/packages；14 種序列的 repro 全部 `spinner=false`，且過期頁面仍被丟棄 |
| I-A C1 的另一半（空首頁被講成「找不到」、cursor 無入口） | **關閉** | `SearchScreen.kt:140` 加上 `&& state.next == null`；`:150-154` 新增 `search_no_results_yet`；`:164` 的 Load more 現在到得了 |
| I-B `lastCommittedAtEpochMs` 守衛漏掉兩條寫入路徑 | **關閉** | `IngestRepository.kt:336` `revisedIds += id` 只在 `old != null` 的分支；`CaptureCoordinator.kt:883-887` 四個條件涵蓋全部會寫列的路徑、且不多不少 |
| I-C 工具列的複製鈕沒有空本文守衛 | **關閉** | `ConversationScreen.kt:239-246` `enabled = selectedText.isNotBlank()`，且空本文也不再參與 `joinToString` |
| M5 ja / ko 的「安靜率」量綱錯位 | **關閉** | `values-ja/strings_analytics.xml:12` 静かな日の割合、`values-ko/...:12` 조용한 날 비율 |
| M1 / M2 commit message 的兩項不實佐證 | **關閉** | `fe0a5b0` 的 commit message 逐條更正，並把「需要新依賴」那句換成一個真的測試 |
| M3 `run()` 的例外路徑仍宣告一個沒跑完的索引 | **未關閉**（本輪列為 Minor 1，與上一輪同級） | `SearchViewModel.kt:132-133` 的 `.getOrDefault(SearchPage(emptyList(), null))` 未動 |
| M8 review 索引的 `<pending>` | **關閉但引入新錯**（Minor 2） | 第 29 列已是 `b373146` ✓；新加的第 30 列寫的是 `088cc11`，那是一個 amend 前的孤兒 commit |
| `MessageBubbleSemanticsTest` 是不是對的形狀 | **是**，兩次反事實實跑證明 | 見下方 Observations 1 |

---

## Important（推之前應該修）

### I1. 新的 `MessageBubbleSemanticsTest` 不在任何 runner 的路徑上——CI 與五份裝置測試清單都沒有它

`.github/workflows/ci.yml:88`

```yaml
script: ./gradlew --no-daemon --console=plain :platform:storage:connectedDebugAndroidTest :platform:crypto:connectedDebugAndroidTest :platform:backup:connectedDebugAndroidTest
```

`:feature:conversation:connectedDebugAndroidTest` 不在裡面。我把整個 repo 掃過一遍，這個測試只出現在
**一個**地方——`docs/TEST_MATRIX.md:20` 與 `docs/zh-Hant/TEST_MATRIX.md:20` 的那一列（附了正確的
`ANDROID_SERIAL=<emulator> ./gradlew :feature:conversation:connectedDebugAndroidTest`）。以下五份「跑
裝置測試」的清單全部沒有它：

| 位置 | 目前列的模組 |
| --- | --- |
| `.github/workflows/ci.yml:88` | storage、crypto、backup |
| `CLAUDE.md:27-28` | storage、crypto（**連 `:platform:backup` 都還沒補上**——那是第 26 輪 agy 指出、README 與 CONTRIBUTING 都已修好、唯獨這份漏掉的） |
| `README.md:85-87` | storage、crypto、backup |
| `README.md:188` | storage、crypto、backup |
| `CONTRIBUTING.md:25` | storage、crypto、backup |
| `docs/zh-Hant/CONTRIBUTING.md:24` | storage、crypto、backup |

後果是具體的：`CONTRIBUTING.md` 的 pre-push 清單、`CLAUDE.md` 的 Build and test、以及 CI 的
instrumented job，**沒有任何一條會執行這個測試**。A11Y-02 的迴歸保護因此只在有人手動想到時才存在。
這與第 27 輪那個「恆真式」發現是同一類：一個關於「已經驗證了」的宣稱，而 artifact 不支持它。
commit message 寫的是

> So the assertion is now a test rather than an argument

——就目前的接線而言，它仍然是一個論證，只是這個論證恰好可以被手動執行。

**另外，我沒有驗證它在 CI 的矩陣上會不會過。** 我只在 API 36 的 `QuietInbox_Phone` AVD 上跑過；
`ci.yml:70` 的矩陣是 **API 29 與 35**。把它加進 `ci.yml:88` 正是找出答案的方式；若 API 29 上
Compose 測試有問題，那條 job 會立刻說話，而不是留給未來某個人。

**修法**：`ci.yml:88` 加上 `:feature:conversation:connectedDebugAndroidTest`，五份清單同步（順便把
`CLAUDE.md` 缺的 `:platform:backup` 一起補回去）。`ANDROID_SERIAL` 的注意事項在 CI 裡不需要——
`android-emulator-runner` 只會起一台。

---

## Minor / nitpicks

1. **（上一輪未修）`run()` 的例外路徑仍會對一個沒跑完的索引說「找不到」。**
   `feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt:132-133`：
   `runCatching { … }.getOrDefault(SearchPage(emptyList(), null))`。查詢**拋例外**時狀態是
   `results = 空、next = null、searched = true`，於是 `SearchScreen.kt:140` 的
   `results.isEmpty() && next == null` 成立，畫面說「找不到「X」。」。這一輪把「空頁 + 活 cursor」那條
   路堵好了，於是這條**變成了唯一**還能在索引沒窮盡時說出「找不到」的路徑。可達性不高（金庫在查詢
   途中被鎖、SQLite 錯誤），而且 `vaultLocked` 一旦傳到就會蓋掉畫面，但值得一個 `searchFailed` 狀態，
   或至少在例外時不要把 `next` 設成 null。與上一輪同級（Minor），只是位置更顯眼了。
2. **review 索引指向一個孤兒 commit。**
   `docs/reviews/README.md:46` 與 `docs/zh-Hant/reviews/README.md:44` 第 30 列的修正 commit 欄寫的是
   `` `088cc11` ``。`git cat-file -t 088cc11` 說它是一個 commit，但
   `git merge-base --is-ancestor 088cc11 HEAD` 失敗、`git branch -a --contains 088cc11` 是空的——它是
   `fe0a5b0` 被 amend 之前的那一版（同一則訊息、同一個 `Mon Sep 7 11:38:19 2026` 時間戳），只存在於
   本機 reflog。我另外確認了 `git log origin/main..HEAD` 的 7 個 commit 都不含它，也就是**它從來沒有被
   推送過**，所以這純粹是文件筆誤，不是有人的 clone 歷史分岔；但它會被 gc 掉，任何人日後照著這個索引
   查都會查不到。應改成 `fe0a5b0`。（agy 也發現了這一項——我是在同一個 `grep` 指令的輸出裡先自己
   查到、才順帶看到它的結論的。）
3. **`searching` 可能停在 `true` 而沒有任何工作在進行。** repro 的第 11 與第 14 例：
   `searching=true, searched=true`。成因是 `setQuery` 無條件把 `searching` 設為 `q.isNotBlank()`，而
   管線的 `distinctUntilChanged` 判定「沒變」時不會有 `run()` 來把它清掉。今天無害——
   `SearchScreen.kt:135` 用的是 `searching && !searched`，而要走到這裡 `searched` 必為 true——但狀態
   物件裡有一個不成立的欄位，下一個依賴 `searching` 的人就會踩到。建議：`setQuery` 只在文字真的改變
   時才把它設 true，或在 collector 判定「不重跑」時順手清掉。
4. **`MessageBubble` 的 `isGroup` 參數沒有被使用。** `ConversationScreen.kt:381` 宣告，函式體內（:379–:470）
   一次都沒有引用；真正決定要不要畫發送者的是 `showSender`。這是既有的，但這一輪把函式改成 `internal`
   之後，它成了模組可見 API 的一部分，而新測試也必須為它填一個沒有意義的 `isGroup = true`
   （`MessageBubbleSemanticsTest.kt:58`）來表達「這是群組對話」——其實那個語意完全由 `showSender`
   承載。用它或刪掉它，兩者都比留著好。
5. **kdoc 夾在兩個註解中間。** `ConversationScreen.kt:376-379` 的順序是
   `@OptIn(...)` → `/** internal rather than private … */` → `@Composable` → `internal fun`。
   本檔其餘的 kdoc 都寫在宣告（含註解）之前。它能編譯，但放在註解清單中間的 doc comment 不會被當成
   該宣告的 KDoc 處理。移到 `@OptIn` 之前即可。
6. **工具列的 `selectedText` 每次重組都掃一次整個對話。**
   `ConversationScreen.kt:239-240`：`state.messages.filter { it.id in state.selection }.map { it.body }
   .filter { it.isNotBlank() }.joinToString("\n\n")`。它在 `if (selecting)` 內，所以只在選取模式下發生，
   但 `state.messages` 是整段對話（可能上千則），而這個 lambda 會隨 `state` 的任何變化重組。
   包成 `remember(state.selection, state.messages) { … }` 就好。
7. **語意測試可以再加一條直接表達需求的斷言。** 目前的兩條證明了「有一個節點同時帶著兩者」與
   「底下確實是兩個 `Text`」。A11Y-02 真正要的是「螢幕閱讀器只停一次」，那可以直接寫成
   `rule.onAllNodes(hasText(SENDER, substring = true)).assertCountEquals(1)`——我確認過它在現行版面上
   成立（合併樹裡只有氣泡那個節點帶 Text）。現行的兩條沒有排除「另外還有一個只帶發送者、不帶內文的
   合併節點」；這段程式碼裡不會發生，但那一行是免費的。

---

## Observations

1. **`MessageBubbleSemanticsTest` 是對的形狀，而且兩個方向都被實測過。** 我沒有只讀它：
   - **反事實 1（修正前的版面）**：把 scratchpad 副本的 `MessageBubble` 還原成 `edd261f` 的樣子——
     發送者在 clickable column 外、外層掛 `semantics(mergeDescendants = true) {}`——
     `theSenderAndTheBodyAreOneNodeInTheMergedTree` **失敗**，錯誤訊息是
     `Expected exactly '1' node but could not find any node`。**零個**節點同時帶著兩者，這也順手再一次
     證實了第 29 輪那個反編譯結論：外層的 `mergeDescendants` 對一個 merging 的子節點完全沒有作用。
   - **反事實 2（單一串接的 `Text`）**：把發送者與內文合成一個 `Text`，
     `theyAreStillDrawnAsSeparateTextsUnderneath` **失敗**
     （`Did not expect any node but found '1' node`），而正向那條照樣通過。
   
   所以負向控制**不是恆真的**：它確實排除掉「兩者剛好是同一個 composable」這個替代實作，正如它的
   註解所說。兩條合起來把版面夾在中間，是一個真正的迴歸測試對。
2. **`internal` 是這裡對的接縫，而且請不要「改進」成 `@VisibleForTesting`。**
   替代方案是透過 `ConversationScreen` 測，那要拖進 ViewModel 與 Hilt，重非常多；而
   `androidTest` 編譯單元是 main variant 的 friend，所以 `internal` 剛好夠用（測試能編過並通過即是
   證明）。至於加 `@VisibleForTesting`：本專案 lint 是硬閘門（`abortOnError = true`、無 baseline），
   而 `MessageBubble` 有一個正式的生產呼叫端（`ConversationScreen.kt:295`），
   `VisibleForTests` 檢查會因此報錯把 build 打掉。維持現狀。
3. **`revisedMessageIds` 的填法精確。** `IngestRepository.kt:336` 的 `revisedIds += id` 位在
   `is Decision.Revision ->` 的 `if (old != null)` 分支內，也就是**真的寫了** `MessageRevisionEntity`
   ＋ `applyRevision` ＋ 重建 token 之後；`old == null` 那條（什麼都沒寫）只設 `storedIds[index] = null`，
   不進清單。`CaptureCoordinator.kt:883-887` 的四個條件對照 `commit` 的每一條寫入：summary → ✓、
   New insert → `newIds` ✓、AmbiguousRepeat insert → `ambiguousIds` ✓、Revision → `revisedIds` ✓；
   而 `observationLinkDao().insert` / `incrementObservation`（`:252`、`:320`）、`checkpointDao().upsert`、
   `convDao.update`、`journalDao().setState` 這些純記帳仍然**不**觸發，這是對的——它們沒有存下任何
   新內容。既不漏也不多。
4. **加了預設值的欄位沒有打破任何呼叫端。** `CommitOutcome(` 全 repo 只有三處：
   `IngestRepository.kt:188`（6 個位置參數，第 7 個吃預設）、`:386`（7 個）、
   `CaptureCoordinatorTest.kt:874` 的 mock（6 個）。沒有任何解構或 `componentN` 用法。
   232 個 JVM 測試強制重跑全綠即是證明。
5. **repro 的判別子中途修正過，請以第二份表為準。** 第一版用 `query.length * 10` 當假的命中數，
   於是 `hello` 與 `world` 都是 50，第 3 例分不出「舊頁被丟棄」還是「舊頁被套用」。改成同時吃
   query/range/packages 的函式之後：第 3 例 `q=world results=149`（`world` 的值，不是 `hello` 的 213）、
   第 5 例 `1213`（hello+package）、第 7 例 `215`（hello/TODAY）——**過期的頁面確實被丟棄**；而第 2、4、
   6、9、10 例（query／chip／range 的 A→B→A，以及 collector 忙碌時再改回去）都只啟動了一次查詢、
   而那一次**被套用**了，`spinner=false`。
6. **一台已連線的 AVD 會被 AGP 拒絕。** 第一次我綁 `ANDROID_SERIAL=emulator-5554`（`Pixel_9(AVD)`）時，
   AGP 回 `Skipping device 'Pixel_9(AVD)': Unknown API Level` → `0 of which were compatible` → 測試判定
   失敗；換成 `emulator-5556`（`QuietInbox_Phone(AVD) - 16`）就成功。三台裝置 `getprop
   ro.build.version.sdk` 都是 36/REL，所以這是那台 AVD 的環境問題，不是程式問題。`TEST_MATRIX` 的指令
   建議把 AVD 名字也寫出來（專案本來就規定用 `QuietInbox_Phone` / `Foldable_Test`），省得下一個人
   照著 `ANDROID_SERIAL=<emulator>` 隨手挑一台。
7. **新字串誠實。** `search_no_results_yet` 五語齊備，英文是
   "Nothing matched in the part of the index searched so far."、繁中「在目前已搜尋過的索引範圍內沒有
   符合的內容。」——它只宣稱已經掃過的那一段，正是這一輪要的那句話。CHANGELOG 與兩份 `TEST_MATRIX`
   的新列也都與程式一致，沒有超前。

---

## 建議的收斂順序

1. **I1**：`ci.yml:88` 加上 `:feature:conversation:connectedDebugAndroidTest`，`CLAUDE.md`、`README.md`
   （兩處）、`CONTRIBUTING.md`、`docs/zh-Hant/CONTRIBUTING.md` 同步；順便補回 `CLAUDE.md` 缺的
   `:platform:backup`。CI 跑過 API 29／35 之後才算真的驗證過。
2. Minor 2（`088cc11` → `fe0a5b0`，兩語）——它會被 gc，越早改越好。
3. Minor 1、3（兩個都是「狀態欄位說了不成立的話」，與本輪主題同類）。
4. Minor 4、5、6、7 可以順手或擇期。
