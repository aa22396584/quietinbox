# Round 30 迷你再審 — Claude subagent（Opus 5, 1M context）

範圍：`b373146`（單一修正 commit，`edd261f..b373146`），分支 `main`，唯讀審查。
本輪另一位 reviewer：Gemini 3.8 Flash (high, via agy)；Kimi 仍被週配額擋住（`kimi-blocked.md`）。
最嚴格的裁決生效。本報告在寫成之前**沒有**讀過 agy 的本輪報告（只在 `grep "231"` 時意外看到它的
兩行，與本報告的任何實質判斷無關）。

## 本輪實際跑過的驗證

| 檢查 | 結果 |
| --- | --- |
| `./gradlew test --rerun-tasks --console=plain`（強制重跑，379 tasks executed，非 UP-TO-DATE） | BUILD SUCCESSFUL，**232 tests / 0 failures / 0 errors / 0 skipped**（解析全部 `TEST-*.xml` 得出），與 commit message 宣稱的 232 相符 |
| `python3 tools/check-strings.py` | `OK: 0 error(s), 0 warning(s)` |
| `tools/check-permissions.sh` | **未跑**：它需要一個已建好的 release APK 作為參數。本 commit 的 diff 不含任何 `AndroidManifest.xml`、`build.gradle.kts`、`libs.versions.toml` 或 `verification-metadata.xml` 變更，權限面沒有新的攻擊面 |
| `uiautomator dump` 的實際內容（scratchpad `conv-ui.xml` / `conv-ui2.xml`，11:06–11:07，修正後的組建） | 62 個節點，其中 **10 個 `clickable="true"`、5 個 `long-clickable="true"`**（正是五顆氣泡）。發送者 `Text` 的 bounds `[69,425][190,471]` **落在氣泡節點 `[32,399][778,709]` 之內** |
| 反編譯 `androidx.compose.ui:ui-android:1.12.0` | `SemanticsOwnerKt.getAllUncoveredSemanticsNodesToIntObjectMap` 第 14–18 byte 明確呼叫 `SemanticsOwner.getUnmergedRootSemanticsNode()` ——**送給 a11y 框架（含 uiautomator）的虛擬節點樹是 unmerged tree** |
| 反編譯 `androidx.compose.material3.adaptive:adaptive-navigation3:1.3.0` | `ListDetailSceneStrategy.calculateScene` 由 `lastIndex` 往回掃；**遇到沒有 pane metadata 的 entry 就 `break`**（byte 103–108 `goto 197`），sceneKey 不同才是 `continue`（byte 125 `ifeq 191`） |
| 獨立可執行的 repro（scratchpad `r30/repro`，Kotlin + coroutines 1.11.0，非本 repo） | 重現了 `SearchViewModel.init` 的 `debounce(250) → distinctUntilChanged → run()` 管線，證實下面的 C-NEW 與提出的修法（見 Critical 一節）。C1 的證據不在這裡，而在 `SearchScreen.kt:136` 的分支順序與兩處 `next = page.next` |
| `git status --short` | 除了 `docs/reviews/README.md` / `docs/zh-Hant/reviews/README.md` 的既有未提交修改與本報告目錄外乾淨；測試執行沒有弄髒 repo |

---

## Verdict：**REQUEST CHANGES**

Critical **1** · Important **3** · Minor **7** · Observations 若干。

九項發現裡有六項**確實關閉**，其中 I3、I4、I5、I8 與 agy 的 minor 3 是紮實、可驗證的修正。但這一批
commit 在關掉 C1 的同時，**在同一個檔案裡新造了一個更容易踩到的缺陷**（搜尋頁永久轉圈），而 C1
本身只關了一半：狀態層誠實了，畫面層仍然對一個沒掃完的索引說「找不到」。另有 I2 的守衛漏掉兩條
真的會寫入內容的路徑。

### 逐項裁決

| 發現 | 狀態 | 一句話 |
| --- | --- | --- |
| C1 搜尋把「預算用盡」當成「索引窮盡」 | **部分關閉** | `next = page.next` 兩處都對了，但 `SearchScreen.kt:136` 讓空首頁走進「找不到」空狀態，保留下來的 cursor 沒有任何入口可以用 |
| I1 A11Y-02「合併成一個節點」 | **關閉** | 結構正確，且由 dump 的幾何關係實證；但 commit message 的兩項佐證都不成立（見 Minor M1/M2） |
| I2 `lastCommittedAtEpochMs` | **部分關閉** | 牆鐘 + `maxOf` 對了；守衛漏掉 `ambiguousMessageIds` 與 `Decision.Revision` 兩條寫入路徑 |
| I3 `settlePendingMedia` CAS | **關閉** | DAO 加了 `AND mediaState = 'PENDING'`，回傳 Int，清掃累加真實更新列數 |
| I4 溢位當下結案 | **關閉** | 在 `pipelineMutex` 內、`return false` 之前，不會死鎖，兩個計數器都平衡 |
| I5 未量測時不要假設在底部 | **關閉** | `?: return@LaunchedEffect`，且不影響正常的「跟隨新訊息」 |
| I6 剪貼簿 | **部分關閉** | `EXTRA_IS_SENSITIVE` 兩條路徑都拿到了；但「空本文不提供複製」只做了無障礙動作，工具列那顆按鈕仍會靜默無事 |
| I7 篩選後的空狀態 | **關閉** | 三個標題、五語齊全，測試按鈕與授權區塊在有篩選時都收掉 |
| I8 COMPATIBILITY 章節位置 | **關閉** | 兩語都把新章節移到 QI-ID-008 清單之後 |
| agy minor 3 `showBackButton` | **關閉** | `besideList` 的推導與 `ListDetailSceneStrategy` 的實際行為精確對齊（反編譯佐證） |
| M1 / M10 zh-Hans 沉默率 | **部分關閉** | zh-Hant / zh-Hans 對了，ja / ko 仍是「靜かな日」「조용한 날」壓在一個百分比上 |
| M2 `health_saved` 死字串 | **關閉** | 五語全部移除，`.kt` 樹中無殘留 |
| M3 / M5 `loadMore` 的守衛 | **關閉（但見 C1）** | generation 取代值相等是對的；`run()` 也補了 `loadingMore = false`。問題出在把同一個守衛也套進 `run()` |
| M11 `selected` 無條件設定 | **關閉** | `this.selected` 與 `stateDescription` 現在都在 `if (selecting)` 內 |
| agy minor 1 / 2 | **關閉** | SCOPE 兩語的 `SearchViewModelTest（5）`、onboarding 重複按鈕 |
| `buildList` 無障礙動作 | **正確** | 空本文時只留 Delete，順序穩定，兩個 action 都回傳 `true` |

---

## Critical（推之前必須修）

### C-NEW. `run()` 的 generation 守衛會讓搜尋頁永久轉圈——這是本 commit 新造的

`feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt:136`

```kotlin
local.update {
    // `s` is the snapshot this run was started from; anything newer owns the state now.
    if (it.generation != s.generation) return@update it
    …
}
```

`run()` 不是被呼叫端直接觸發的，它是被 `init` 的這條管線驅動的（`SearchViewModel.kt:71-73`）：

```kotlin
combine(local.debounce(250), vault.state) { s, v -> s to v }
    .distinctUntilChanged { (a, va), (b, vb) -> a.query == b.query && a.range == b.range && a.packages == b.packages && va == vb }
    .collect { (s, v) -> if (v is VaultState.Ready) run(s) … }
```

**兩個判斷的粒度不一樣**：`distinctUntilChanged` 用的是 (query, range, packages)，`generation` 卻在**每
一次** `setQuery` / `setRange` / `togglePackage` / `clearPackages` 都遞增。於是存在一個空隙——
generation 變了，但 (query, range, packages) 沒變：

1. 使用者在搜尋頁輸入 `hello`（gen 1）。250ms 後 debounce 送出，`run(gen=1)` 開始跑。
2. 查詢還在飛行中時，使用者多打一個字又退掉（`hello2` → gen 2 → `hello` → gen 3）。
   換成「把某個來源 chip 點開再點關」也一樣。
3. debounce 再次送出（query 仍是 `hello`），但 `distinctUntilChanged` 判定與上一次相同 → **不再觸發
   `run()`**。
4. `run(gen=1)` 回來了，`it.generation`（3）≠ `s.generation`（1）→ **結果被丟棄**。

`searching` 只有在 `run()` 成功套用時才會被清成 false（`:141`），`setQuery(非空)` 只會把它設回 true，
所以最終狀態是 `searching = true, searched = false`——`SearchScreen.kt:135` 的條件

```kotlin
state.vaultOpening || (state.searching && !state.searched) -> … LoadingIndicator()
```

**永遠成立**。畫面上只剩一顆轉不完的圈，而且沒有任何背景工作在跑。

我寫了一個獨立的 repro（scratchpad `r30/repro`，同樣的 `debounce(250)` + 自訂 `distinctUntilChanged`
比較器 + 1 秒的假查詢），實跑輸出：

```
== current code (generation guard in run())
  A->B->A while in flight  -> runs=[1] state(q=hello, results=0) spinner=true
  A->B    while in flight  -> runs=[1, 2] state(q=hello2, results=7) spinner=false
== proposed fix (run() guards on the same fields distinctUntilChanged uses)
  A->B->A while in flight  -> runs=[1] state(q=hello, results=100) spinner=false
  A->B    while in flight  -> runs=[1, 2] state(q=hello2, results=7) spinner=false
```

`runs=[1]` 這一行就是全部的重點：**整個過程只啟動過一次查詢，而它被丟掉了，沒有任何東西會來補。**

不變式應該是：**`run()` 丟棄結果的條件，不得比 `distinctUntilChanged` 重跑的條件更嚴格**——比它嚴格
一格，就會出現「被丟棄卻沒有替補」的狀態。

**修法（repro 已驗證，上表第三、四行）**：`run()` 改用管線自己的身分比較，`loadMore()` 保留 generation：

```kotlin
local.update {
    if (it.query != s.query || it.range != s.range || it.packages != s.packages) return@update it
    …
}
```

第四行證明這個改法**沒有**放掉 M5 想守住的東西：`hello` → `hello2` 這種真的改了查詢的情況，舊那一頁
仍然被丟棄（`results=7`，是 `hello2` 的結果，不是 `hello` 的 100 筆）。
`loadMore()`（`:97`）必須維持 generation，因為那裡丟棄是安全的——按鈕還在，使用者可以再按一次；
而 A→B→A 的重複 append 正是 M3 要擋的。

（另一個等價修法：generation 不在 setter 裡遞增，改在 collector 派工的當下遞增。兩者都滿足上面的
不變式。）

**回歸測試不需要任何新基礎設施**：現有的 `SearchViewModelTest.kt:133`「a stale page is discarded when
the query has moved on」結束時狀態其實就已經是 `searching = true` 且沒有任何待處理工作了，只是因為
第一次 `run()` 已經成功、`searched` 為 true 才沒有顯示出來。在該測試的重打之後加一行

```kotlin
awaitUntil { vm.state.value.searching shouldBe false }
```

對 `b373146` 會失敗、修好之後會通過。

**嚴重度說明（請自行斟酌下修）**：這個轉圈可以靠「再改一次查詢字串」自行恢復——任何**真的**改變
query/range/packages 的動作都會重跑並解除。我仍然給 Critical，理由是：它是修正 commit 新造的缺陷、
由極普通的操作（打錯字再退格）觸發、發生在第一次搜尋這個最常見的路徑上、而且畫面在那期間對使用者
說的是一件不成立的事（「還在搜尋」）。

---

## Important（推之前應該修）

### I-A. C1 只關了一半：空首頁 + 存活 cursor 仍然被畫成「找不到」，而且那個 cursor 按不到

`feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchScreen.kt:136-140`

實作面 C1 是修對的，我逐行確認過：`run()`（`SearchViewModel.kt:139`）與 `loadMore()`（`:107`）都改成
`next = page.next`，repository 的 `if (exhausted) null else position`（`SearchRepository.kt:80`）原封傳到
UI；`loadMore()` 的例外路徑（`:94` `.getOrNull()` → `page == null`）只清 `loadingMore`、**保留 cursor**，
這是對的。改寫後的測試 4（`SearchViewModelTest.kt:111`）斷言 `next shouldBe deeper`，對舊碼
（`next = if (page.hits.isEmpty()) null else page.next` → null）會直接掛在 `awaitUntil` 上，是真迴歸測試。
`docs/TEST_MATRIX.md` 與 `docs/zh-Hant/TEST_MATRIX.md` 都改成「仍保留 cursor，因為空頁代表候選掃描
預算用盡」，CHANGELOG 也改對了。**這一半沒有問題。**

但畫面的 `when` 分支順序是：

```kotlin
state.results.isEmpty() -> EmptyState(title = stringResource(R.string.search_no_results, state.query), …)   // :136
else -> LazyColumn { … 標頭 … items … if (state.next != null) { Load more } }                                // :141-170
```

`search_no_results` = 「找不到「%1$s」。」／"No matches for “%1$s”."。

也就是說，**第一頁掃了 40,000 筆候選、一筆都沒通過子字串驗證、而 cursor 還活著**的那個情境——正是
round 29 的 C1 裡我寫「`run()`（:132）更糟：**第一頁**就可能命中這條路徑」的那一句——現在的行為是：

- 狀態層誠實了（`state.next != null`）✓
- 畫面層仍然宣告「找不到」，對一個**沒有掃完**的索引 ✗
- 而且那顆「載入更多」在 `else` 分支裡，`results.isEmpty()` 先命中就永遠到不了——**保留下來的 cursor
  完全沒有出口**，比修正前更可惜（修正前至少狀態與畫面是一致地錯，現在是狀態對、畫面錯、且無法補救）

**修法（兩擇一）**：

- 畫面側：把空狀態的條件收緊成 `state.results.isEmpty() && state.next == null`，並在 `state.next != null`
  且 `results` 為空時渲染一個「這一批候選都不符合，可以繼續往下找」的狀態加上那顆 Load more；
- ViewModel 側（round 29 提過的另一案）：在 `run()` 內對空頁自動續掃
  （`while (page.hits.isEmpty() && page.next != null)`，配一個回合上限），只有真的掃到底才交給畫面。

### I-B. `lastCommittedAtEpochMs` 的守衛漏掉兩條真的會寫入內容的路徑

`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:883-886`

```kotlin
if (outcome.newMessageIds.isNotEmpty() || outcome.summaryRecorded) {
    val savedAt = System.currentTimeMillis()
    _status.update { it.copy(lastCommittedAtEpochMs = maxOf(it.lastCommittedAtEpochMs ?: 0L, savedAt)) }
}
```

牆鐘、`maxOf` 單調、只在有寫入時蓋章——這三點都對，round 29 I2 的 (a) 完全關閉。但 (b) 的守衛條件
不夠：我把 `IngestRepository.commit`（`:158-382`）裡每一條寫入都列過一遍：

| 寫入 | 進到哪個回傳欄位 | 守衛涵蓋？ |
| --- | --- | --- |
| `healthDao().insertSummary`（`:169`） | `summaryRecorded` | ✓ |
| `messageDao().insert` + `Decision.New`（`:266`、`:310`） | `newMessageIds` | ✓ |
| `messageDao().insert` + `Decision.AmbiguousRepeat`（同一個 insert，`:303`） | **`ambiguousMessageIds`** | ✗ |
| `Decision.Revision`：`revisionDao().insert` + `applyRevision` + 重建 token（`:328-331`） | 三個清單都不進 | ✗ |
| `observationLinkDao().insert` / `incrementObservation`（`:252`、`:320`） | 都不進 | ✓（本來就不該算，那只是既有列的記帳） |
| `checkpointDao().upsert`、`convDao.update`、`journalDao().setState` | 都不進 | ✓（記帳） |

`CommitOutcome` 的欄位名是 `newMessageIds` / `ambiguousMessageIds`（`IngestRepository.kt:39-40`）。

`AMBIGUOUS_REPEAT` 插入的是一列**真的訊息**——它會出現在對話裡、會被 `ambiguousCount` 計數、會在氣泡
上帶一個「可能重複」的標籤。`Decision.Revision` 寫入的是**新的本文**加一筆修訂歷史。這兩種批次都會讓
`health_last_saved` 停在舊時間，甚至在行程剛啟動時顯示
`health_last_saved_never`（「自監聽器啟動以來沒有存下任何副本」），而副本其實剛剛才存進去。

方向上這比修正前安全（少報而不是多報），但誠實標籤不該少報，這一頁存在的目的就是讓使用者判斷擷取
有沒有在運作；顯示「沒有存下任何副本」會讓人以為壞了。

**修法**：

```kotlin
if (outcome.newMessageIds.isNotEmpty() || outcome.ambiguousMessageIds.isNotEmpty() || outcome.summaryRecorded) {
```

（修訂路徑若也要算，`CommitOutcome` 需要多一個 `revisedMessageIds` 或一個 `wroteRows: Boolean`；
`AMBIGUOUS_REPEAT` 那一條是必修的，修訂那一條可以另開 issue。）

### I-C. I6 的「空本文不提供複製」只做了無障礙動作，工具列那顆按鈕沒做

`feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt:239-241`

```kotlin
IconButton(onClick = {
    copyToClipboard(context, state.messages.filter { it.id in state.selection }.joinToString("\n\n") { it.body })
}) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy)) }
```

`EXTRA_IS_SENSITIVE` 的部分**兩條路徑都拿到了**，因為它做在共用的 `copyToClipboard`
（`:524-533`，`Build.VERSION.SDK_INT >= 33` 時設 `PersistableBundle`）——這是 I6 的主體，確實關閉。

但 commit message 寫的「Copy is also no longer offered on a message with no text, where it did nothing
silently」只兌現在無障礙自訂動作上（`:433-436` 的 `buildList` 以 `message.body.isNotBlank()` 為條件）。
工具列這顆按鈕沒有任何守衛：選取一則或多則**只有圖片、本文為空**的訊息再按複製，`joinToString` 得到
空字串，`copyToClipboard` 在 `:526` 直接 `return`——**靜默無事**，正是那一句要修掉的行為，而且是視力
正常使用者唯一看得到的那一條路徑。Android 12 以下也仍然沒有任何成功回饋（round 29 順帶提過）。

**修法**：把按鈕的 `enabled` 綁在 `state.messages.any { it.id in state.selection && it.body.isNotBlank() }`，
或在複製後給一個 Snackbar。

---

## Minor / nitpicks

- **M1（commit message 的佐證之一不成立）** commit message 說 uiautomator dump「every inbox row comes
  back `clickable=false`」。我實際解析了 scratchpad 裡修正後的兩份 dump：`conv-ui.xml` 共 62 個節點，其中
  **10 個 `clickable="true"`、5 個 `long-clickable="true"`**，後者的 bounds 正是五顆氣泡
  （`[32,399][778,709]`、`[32,725][847,966]` …）。可點擊節點是**存在**的，只是在 unmerged tree 裡它們
  身上沒有 text／content-desc（文字在各自的子節點上），所以用「找到含這段文字的節點再看它的
  clickable」這種方式去查就會得到 false。**結論（dump 無法證明合併）是對的，佐證是誤讀**，建議修正措辭。
- **M2（commit message 的佐證之二不成立，且它擋掉了本來就做得到的驗證）** commit message 說
  「the project has no Compose UI test infrastructure to assert it, which needs a new dependency and so a
  cold-cache `verification-metadata.xml` regeneration」。這不成立：
  `build-logic/src/main/kotlin/quietinbox.android.library.compose.gradle.kts:39` 已經對**每一個** Compose
  模組加了 `androidTestImplementation(androidx-compose-ui-test-junit4)`，而 `feature/conversation` 透過
  `quietinbox.android.feature`（`quietinbox.android.feature.gradle.kts:3` 套用 `quietinbox.android.library.compose`）
  正在這個範圍內；`gradle/verification-metadata.xml:637-660` 也已經有 `ui-test` / `ui-test-junit4` /
  `ui-test-junit4-android` 的 checksum（`grep -c "ui-test"` = 13）。**不需要新依賴，也不需要重生
  verification-metadata。** 所以「比 Compose UI test 更便宜的驗證方式」這個問題的答案是：
  1. 你已經做到的那一種——unmerged dump 的**幾何包含關係**（發送者 `[69,425][190,471]` 落在氣泡
     `[32,399][778,709]` 內）加上 round 29 的反編譯不變式（`combinedClickable` 讓節點 merging；merging
     node 不會被外層吸收；純 `Text` 是非 merging 子節點），已經是一個完整的結構性證明。我認為 I1
     **結構上關閉**就是基於這一條。
  2. 若要驗到「TalkBack 實際唸出來的內容」，最便宜的仍然是一個 `createComposeRule()` 的 androidTest：
     `onNode(hasText(sender) and hasText(body))`（合併樹，`useUnmergedTree = false` 預設）斷言存在，
     再用 `useUnmergedTree = true` 驗兩者是不同的 unmerged 節點。零新依賴、零 metadata 變更。
- **M3（`run()` 的例外路徑仍會宣告一個沒跑完的索引）** `SearchViewModel.kt:132-133`：
  `runCatching { … }.getOrDefault(SearchPage(emptyList(), null))`。查詢**拋例外**時，狀態變成
  `results = 空、next = null、searched = true`，畫面於是說「找不到「X」。」——一個從未完成的搜尋被講成
  一個已窮盡的結果。這是既有行為（不是 `b373146` 造成的），但與 C1 同一類；`loadMore()` 的對應路徑
  （`:94` `.getOrNull()` → 保留 cursor）處理得比它好。建議加一個 `searchFailed` 狀態，或至少不要把
  `next` 設成 null。
- **M4（`++generation` 是可重試 lambda 裡的副作用）** `SearchViewModel.kt:79-80`、`:114-115` 把
  `++generation` 寫在 `MutableStateFlow.update { … }` 內。`update` 在 CAS 失敗時會**重跑** lambda，
  於是 generation 可能多跳一格。因為它只需要單調遞增，功能上無害；但把副作用放進可重試的 lambda 是
  一個會被下一個讀者誤會的寫法。另外 `generation` 是普通 `var`（非 `@Volatile`），目前只從主執行緒的
  UI callback 寫入，可以接受，但值得寫一行註解。
- **M5（M10 只做了一半：ja / ko 仍然是「日子」壓在「百分比」上）**
  `core/designsystem/src/main/res/values-ja/strings_analytics.xml:12` `静かな日`、
  `values-ko/strings_analytics.xml:12` `조용한 날`，而它們底下的
  `analytics_quiet_value`（同檔 `:64`）是 `静かな日 %1$d%%` / `조용한 날 %1$d%%`。round 29 的 M10 說
  zh-Hant「安靜天數」（一個天數）與「安靜 %1$d%%」（一個百分比）量綱對不上，這一輪把 zh-Hant / zh-Hans
  改成「安靜率 / 安静率」對齊 en 的 "Quiet rate" ✓，但 ja / ko 現在成了唯一仍有同一個量綱錯位的兩本
  catalogue。建議改成「静かな日の割合」「조용한 날 비율」之類。
- **M6（`RetentionReport.deletedStrayFiles` 數的是「嘗試刪除」而非「確實刪除」）**
  `RetentionWorker.kt:95-96`：`for ((name, _) in stray) mediaDir.delete(name)`，而 `MediaDirectory.delete`
  （`:161-163`）是 `runCatching { File(dir, name).delete() }`，回傳值被丟掉。相對地本輪新加的
  `settledPendingMedia` 數的是 DAO 回傳的**真實更新列數**，是準確的（`:104`、`:117`；
  `RetentionReport.settledPendingMedia` kdoc「Copies stuck in PENDING long enough to be called failed」
  與行為相符）。兩個欄位的語義嚴謹度不一致；`RetentionReport` 目前沒有任何消費者（全 repo 只有
  `RetentionWorker.kt` 內的 4 處），所以現在改成本低。
- **M7（溢位分支的結案沒有 `guarded {}`，而它排在 diagnostic 之前）**
  `CaptureCoordinator.kt:896-897`：`ingest.settlePendingMedia(...)` 之後才寫
  `ingest.diagnostic("MEDIA_QUEUE_OVERFLOW", ...)`。`IngestRepository.settlePendingMedia`（`:143-147`）
  沒有 `runCatching`，`holder.db()` 在金庫剛好被關掉時會拋 `VaultUnavailableException`；那個例外雖然
  會被外層 `catch`（`CaptureCoordinator.kt:778` 起）接住並記成缺口，但**這次丟棄本身的 diagnostic 就寫
  不進去了**——「Gaps are shown, never hidden」在這條極窄的路徑上會失守。把 `diagnostic` 排到 settle
  之前，或把 settle 包進 `guarded {}`，成本是零。
- **M8（review 索引的 `<pending>`）** `docs/reviews/README.md:45` 與 `docs/zh-Hant/reviews/README.md:43`
  第 29 列的修正 commit 欄在 `b373146` 裡寫的是 `<pending>`。這一點**已經在工作目錄裡改成 `b373146`
  但尚未提交**（`git diff docs/reviews/README.md`），只要記得一起提交即可，不算未修。

---

## Observations（做對的地方 / 其他）

1. **I3 是本輪最紮實的修正，而且範圍剛好。** `Daos.kt:268-275` 的
   `UPDATE message SET mediaState = :state, mediaBlobId = NULL WHERE id = :id AND mediaState = 'PENDING'`
   回傳 `Int`，`RetentionWorker.kt:104` 逐列累加。這正好切斷 round 29 I3 那條交錯：清掃在讀清單與寫
   FAILED 之間，`MediaCopier.store()` 若已把該列設成 `LOCAL_COPY`，CAS 就不會命中，`mediaBlobId` 不會
   被清空，下一輪 `orphans()` 也就不會刪掉那個檔案。kdoc 把「兩者都是 `maintenance.work`，允許並行」
   這個前提明寫出來，也是對的。`MediaCopier` 的 catch 路徑沒有一起改，我認為可以接受：那條路徑寫的是
   它自己正在處理的那一列，不存在第三方競爭者。
2. **I4 的落點是對的，而且我逐條確認了它不會死鎖。** `processJournaled` 整段跑在
   `process()` 的 `pipelineMutex.withLock { … }` 內（`CaptureCoordinator.kt:766`、`:830` 的 kdoc
   「Must run under `pipelineMutex`」），而 `IngestRepository.settlePendingMedia` **不**再取任何鎖
   （沒有 `maintenance.work`、沒有 mutex），所以不存在對非重入 `Mutex` 的二次取得。註解說的
   「This runs under `pipelineMutex`, the single-writer lane」是真的。計數器也平衡：
   `queuedMediaCopies` 在 `:890` 先 `incrementAndGet`、`:891` 立刻 `decrementAndGet`；`return false` 讓
   呼叫端的 `finally`（`:808`）把 `queuedBitmaps` 還回去。UI 那一側，`MediaState.FAILED` 對應
   `Labels.kt:47` 的 `conv_media_failed` + `QualityColors.failed`，沙漏消失，標籤說的是「這次複製沒有
   走完」——與 MED-11 的裁決一致。
3. **agy 的 minor 3 修得比它自己的建議更準，我用反編譯確認過。**
   `besideList = twoPane && backStack.getOrNull(backStack.lastIndex - 1) is InboxRoute`
   （`MainNavigation.kt:98`）。三條可以推 `ConversationRoute` 的路徑我都走過：
   Inbox（`:132-133`，推之前先 `removeLastOrNull` 掉舊的對話，堆疊是 `[…, Inbox, Conv]`）→ `besideList`
   為真、隱藏返回鍵 ✓；Search（`:147`，`[Inbox, Search, Conv]`）與 Analytics（`:150`，
   `[Inbox, Analytics, Conv]`）→ 為假、顯示返回鍵 ✓。而這**不只是猜測**：
   `ListDetailSceneStrategy.calculateScene` 由後往前掃，**碰到沒有 pane metadata 的 entry 就中斷**
   （`SearchRoute` / `AnalyticsRoute` 都是沒有 metadata 的 `entry<…> { }`），所以收件匣不可能越過它們
   跟對話併成雙欄。`besideList` 與框架的實際行為精確對齊。堆疊長度不足也安全：只有 1 個元素時
   `getOrNull(-1)` 回 null，空堆疊時 `getOrNull(-2)` 回 null，都不會拋。
4. **I5 的 `?: return@LaunchedEffect` 沒有把「跟隨新訊息」弄壞。** 新訊息到達時 `state.messages.size`
   改變、effect 重啟，此時 `layoutInfo` 反映的是**上一幀**的版面（N-1 則），讀者若在底部
   `visibleEnd = N-1`，而新的 `lastIndex = N`，`N-1 >= N-1` 成立 → 仍會捲到底 ✓；讀者往上捲一則時
   `visibleEnd = N-2`，`N-2 >= N-1` 不成立 → 不動 ✓。旋轉／摺疊重建時 `visibleItemsInfo` 為空 →
   早退，`rememberLazyListState` 自己的還原生效 ✓。
5. **I1 的結構修正我用 dump 的幾何關係實證過，不只是讀 modifier。** `conv-ui.xml` 裡第一顆氣泡的
   `long-clickable="true"` 節點 bounds 是 `[32,399][778,709]`，發送者「姊姊 Sis」的 TextView 是
   `[69,425][190,471]`、本文是 `[69,487][741,556]`——發送者確實已經落在氣泡節點內，而且兩者的左緣同為
   x=69（修正前發送者用的是 `padding(start = 12.dp)`、本文用氣泡的 `padding(horizontal = 14.dp)`，
   在 2.75 密度下會差約 5px；現在完全對齊，等於視覺上也證明它移進去了）。搭配 round 29 的反編譯結論
   （`AbstractClickableNode.getShouldMergeDescendantSemantics()` 回 true；`SemanticsNode.mergeConfig` 在子
   節點 merging 時跳過），一個純 `Text` 作為 merging node 的非 merging 子節點會被吸收，TalkBack 從兩個
   focus stop 變成一個。**這一項關閉。**
6. **I7 的三個標題與收掉的按鈕都對。** `InboxScreen.kt:168` 的
   `filtered = archived || unviewed || packages.isNotEmpty()`，`:171-176` 的四路 `when`，
   `:178` 的 `body = if (filtered) ""`，`:191` 的授權區塊與 `:192` 的測試按鈕守衛。
   `inbox_empty_unviewed_title` / `inbox_empty_filtered_title` 五語齊備（en「Nothing unviewed」/
   「Nothing from these sources」，zh-Hant「沒有未查看的對話」/「這些來源沒有內容」，zh-Hans、ja、ko
   同步），`check-strings.py` 通過。`EmptyState` 的 `body = ""` 只是渲染一個空 `Text`，
   `ConversationScreen.kt:256` 的 `conv_empty` 早就是這個用法，不是新問題。
   附帶：有套件篩選且 listener 未授權時，「開啟權限」按鈕也一併被收掉；實務上不可達
   （沒有 listener 就不會有 `availablePackages` chip 可選），不必處理。
7. **I8 兩語都對。** `docs/COMPATIBILITY.md:37-41` 與 `docs/zh-Hant/COMPATIBILITY.md:34-36` 現在把
   「per package, not per profile」與「low-RAM (Go) devices」兩顆 bullet 放在 QI-ID-008 清單末尾、
   `## Hidden previews` 之前，語意歸位。
8. **M11 的收斂是乾淨的。** `ConversationScreen.kt:429-432`：`this.selected` 與 `stateDescription` 現在
   都在 `if (selecting)` 內，非選取模式的氣泡不再帶 `isSelected = false` 進
   `AccessibilityNodeInfo`。`:433-436` 的 `buildList` 順序穩定（Copy 在前、Delete 在後），空本文時只留
   Delete，兩個 action 都回傳 `true`——正確。
9. **文件沒有超前程式碼**（除了上面 M1/M2 說的 commit message 佐證問題）。`SearchViewModelTest` 實測
   5 個 `test(` block，`docs/SCOPE.md:26` 與 `docs/zh-Hant/SCOPE.md:24` 的「5，其中 2 項是鎖定與開啟中
   的金庫」與 `docs/TEST_MATRIX.md:26` / `docs/zh-Hant/TEST_MATRIX.md:26` 的五條描述都與實際測試名稱
   對得上。CHANGELOG 的 C1 段與 I1 段（「Merging from the outside does not fix this — a clickable is
   itself a merging semantics node…the sender's name moved inside the bubble」）敘述準確。
   `docs/zh-Hant/ARCHITECTURE.md:94-95` 的「安靜率」與 `docs/zh-Hant/SCOPE.md:25` 同步。全 repo 沒有殘留的
   「231」測試數（`grep` 命中的都在歷次 review 報告的逐字存檔裡，那些本來就該保持原樣）。
10. **測試 5「a stale page is discarded when the query has moved on」是有鑑別力的。** 對舊碼
    （守衛是 `cur.query != s.query || …`）而言，`setQuery("")` → `setQuery("hello")` 之後
    `cur.query == s.query`，那 5 筆會被 append 成 105 筆，`shouldNotBe 105` 失敗；對新碼 generation 不同
    而被丟棄，通過。唯一的小遺憾是 `shouldNotBe 105` 太寬鬆（results 是 0 也會通過），改成
    `shouldBe 100` 會更精確——而且加上 C-NEW 建議的那一行 `searching shouldBe false` 之後，這個測試就
    同時守住兩個不變式了。

---

## 建議的收斂順序

1. **C-NEW**（`run()` 的守衛改成值相等，`loadMore()` 保留 generation；在測試 5 加一行
   `awaitUntil { vm.state.value.searching shouldBe false }`）
2. **I-A**（`SearchScreen.kt:136` 的空狀態條件加上 `&& state.next == null`，或 VM 內自動續掃）
3. **I-B**（守衛補上 `outcome.ambiguousMessageIds.isNotEmpty()`）、**I-C**（工具列複製鈕的 `enabled`）
4. M7（diagnostic 排到 settle 之前，成本為零）、M8（把已在工作目錄的 `b373146` 一起提交）
5. M1 / M2（改掉 commit message 兩段不成立的佐證；若要一併補上那個 `createComposeRule` 測試，
   依賴與 checksum 都已經就位）
6. M3、M5、M6、M4 可以擇期
