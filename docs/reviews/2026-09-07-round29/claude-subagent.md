# Round 29 獨立審查 — Claude subagent（Opus 5, 1M context）

範圍：`b30761d..edd261f`（6 個 commit），分支 `main`，唯讀審查。
本輪另一位 reviewer：Kimi（quota blocked，見 `kimi-blocked.md`）。最嚴格的裁決生效，本報告不因「別人會抓到」而放寬任何一項。

## 本輪實際跑過的驗證

| 檢查 | 結果 |
| --- | --- |
| `./gradlew test --rerun-tasks`（強制重跑，非 UP-TO-DATE） | BUILD SUCCESSFUL，**231 tests / 0 failures**（解析全部 `TEST-*.xml` 得出），與 commit message 宣稱的 231 相符 |
| `python3 tools/check-strings.py` | `OK: 0 error(s), 0 warning(s)` |
| schema 是否被動到 | `git diff --name-only b30761d..edd261f \| grep -iE "schema\|Entities\|Database.kt\|Migration"` → **空**。本區間確實 schema-free |
| adaptive 斷點（反編譯已解析的 artifact） | `androidx.window:window-core-android:1.5.0` → `WIDTH_DP_BREAKPOINTS_V1 = [0, 600, 840]`；`androidx.compose.material3.adaptive:adaptive-layout:1.3.0` 的 `calculatePaneScaffoldDirective` 在 compact/medium/expanded/XL 分別給 `maxHorizontalPartitions` = 1 / 1 / **2** / 3 |
| Compose semantics 合併規則（反編譯已解析的 artifact） | `androidx.compose.ui:ui-android:1.12.0` 的 `SemanticsNode.mergeConfig` 在子節點 `isMergingSemanticsOfDescendants()` 為真時 **跳過** `mergeChild` 與遞迴；`androidx.compose.foundation:foundation-android:1.12.0` 的 `AbstractClickableNode.getShouldMergeDescendantSemantics()` 回傳 `iconst_1`（true） |
| 誰會寫入 `filesDir/media` | 只有 `MediaCopier.store()`（`maintenance.work`）與 `BackupService.apply()`。後者在 `import` 的 `maintenance.exclusive` 內（`BackupService.kt:219`），`work {}` 期間不可能執行，因此清掃只需要與 `MediaCopier` 競爭 |

---

## Verdict：**REQUEST CHANGES**

Critical **1** · Important **8** · Minor **11** · Observations 若干。

不是因為媒體管線修錯了——那一項是本輪最紮實的修正——而是因為這批 commit 在**修好一個誠實性缺陷的同時，在另外三個地方新造了誠實性缺陷**，而其中一個（搜尋）正是它自己標題所指的那一類，並且已經被測試、`TEST_MATRIX.md`（兩語）與 CHANGELOG 一起鎖死。另有一個無障礙修正經反編譯證實 **完全沒有生效**，但 CHANGELOG 已寫成「The row is one node now」。

---

## Critical（推之前必須修）

### C1. 搜尋把「掃描預算用盡」誤當成「索引已窮盡」，於是又一次把一頁講成總數

`feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt:102`
`feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt:132`

```kotlin
next = if (page.hits.isEmpty()) null else page.next,
```

repository 已經把「窮盡」這個訊號算對了。`SearchRepository.kt:80`：

```kotlin
return SearchPage(hits, if (exhausted) null else position)
```

而 `exhausted` 只在兩處設為 true：`rows.isEmpty()`（:65）與 `rows.size < pageSize && verified.size < limit`（:73）。迴圈條件是

```kotlin
while (verified.size < limit && !exhausted && pages++ < MAX_CANDIDATE_PAGES)   // :62
```

`MAX_CANDIDATE_PAGES = 200`、`pageSize = maxOf(limit=100, CANDIDATE_PAGE=200) = 200`。

因此 **`hits.isEmpty() && next != null` 的唯一成因，就是「掃了 40,000 筆候選、一筆都沒通過子字串驗證，而索引還沒到底」**。這正是「詞元都命中但片語不連續」的查詢（例如 `"the a"`、常見雙字詞）在大金庫上的典型行為。

ViewModel 把這個訊號丟掉、寫成 `next = null`，`SearchScreen.kt:146-147` 於是渲染：

```kotlin
if (state.next == null) stringResource(R.string.search_results_count, state.results.size)  // "%1$d 筆結果"
```

—— App 對一個**沒有數完的索引**宣告了一個總數。這與 S12 是同一個違規，只是換了觸發條件。`run()`（:132）更糟：**第一頁**就可能命中這條路徑，於是畫面直接顯示「沒有結果」的 empty state，而索引其實還有東西沒掃。

同一個錯誤前提被三處固化：

- `feature/search/src/test/kotlin/.../SearchViewModelTest.kt:107-124`「a page that verifies nothing ends the run instead of leaving a button that cannot help」——這個測試**斷言的是錯的行為**，等於把缺陷鎖住。
- `docs/TEST_MATRIX.md` 與 `docs/zh-Hant/TEST_MATRIX.md`：「一頁一個都驗不出來時就結束，不留下一顆按了也沒用的『載入更多』」。
- `CHANGELOG.md`：「A cursor means more *candidates*, not more hits, so a page that verifies nothing ends the run rather than leaving a button that can never produce anything.」——前半句對，後半句的推論不成立：cursor 還在就代表還有候選可掃，那顆按鈕**可能**產出結果。

**修法**：兩處都改回 `next = page.next`。若擔心「按了沒東西」的體驗，正確做法是在 VM 內對空頁自動續掃（`while (page.hits.isEmpty() && page.next != null)`，配一個回合上限），或在 `SearchPage` 加一個與 `next` 分離的 `exhausted: Boolean`，只有它為真時才允許 `search_results_count`。測試 4 必須改寫成「空頁**保留** cursor」，並同步修 `TEST_MATRIX.md`（兩語）與 CHANGELOG。

---

## Important（推之前應該修）

### I1. A11Y-02「The row is one node now」不成立——反編譯證實外層 merge 不會吸收內層 merge

`feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt:411`

```kotlin
modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
```

內層 Column（:429 起）掛的是 `combinedClickable(...)` 加 `.semantics { … }`。反編譯已解析的 artifact：

- `androidx.compose.foundation:foundation-android:1.12.0` → `AbstractClickableNode.getShouldMergeDescendantSemantics()` 回傳 `true`。**`combinedClickable` 本身就讓內層 Column 成為一個 merging node。**
- `androidx.compose.ui:ui-android:1.12.0` → `SemanticsNode.mergeConfig` 逐一走訪 unmerged children，於 `isMergingSemanticsOfDescendants()` 為真時 `ifne` 跳過 `mergeChild$ui` 與遞迴。

也就是說：**巢狀的 merging node 永遠不會被外層 merging node 吸收**（這正是 clickable Row 裡的 IconButton 仍能被獨立 focus 的原因）。

結果：新加的外層 `semantics(mergeDescendants = true) {}` 只吸收得到 sender 那個 `Text`（:414 附近）——氣泡是 merging node，不會被吸收，仍然是它自己的節點。**TalkBack 依舊需要兩個 focus stop（發送者姓名一個、氣泡一個），群組聊天的氣泡節點還是不會說誰發的。** 這與修改前的可及性結果相同。（我是唯讀審查，本模組也沒有 Compose UI test 基礎設施，所以只斷言 bytecode 能證明的「兩個 focus stop」這個結果，不宣稱節點總數的增減。）

CHANGELOG「In a group chat TalkBack read the sender's name as a node of its own… The row is one node now.」是不實陳述。

**修法**：把 `combinedClickable` 與 `.semantics { … }`（含 `customActions`）上提到外層 Column，讓 sender 的 `Text` 成為它的非 merging 子節點；或維持現狀但把 `senderName` 明確寫進氣泡節點的 `contentDescription` / `text` 語意。無論選哪一種，都必須改掉 CHANGELOG 的措辭。

### I2. `lastCommittedAtEpochMs`：時間戳可能倒退，而且在「什麼都沒存」時也會跳動

`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:880`

```kotlin
_status.update { it.copy(lastCommittedAtEpochMs = now) }   // now = snapshot.observedAtEpochMs（:837）
```

兩個獨立問題：

**(a) 用的是事件觀測時間，不是「現在」，而且沒有取 max。** `replayJournal()` 重播的是金庫上鎖期間累積的舊事件，其 `observedAtEpochMs` 就是當初的時間。因此解鎖後剛存下一份副本，`health_last_saved`（「最近存下副本 %1$s」）可能顯示「3 小時前」；更糟的是，重播一筆舊事件會把已經是「剛剛」的值**往回蓋**。

**(b) 這一行在 `ingest.commit` 之後無條件執行，但 commit 有一條什麼都不寫的路徑。** `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:178-181`：

```kotlin
if (identity == null || reconcile == null || reconcile.decisions.isEmpty()) {
    db.journalDao().setState(snapshot.eventId, "COMMITTED", null)
    return@withTransaction CommitOutcome(null, emptyList(), emptyList(), emptyList(), 0, summaryRecorded)
}
```

`processJournaled` 只擋掉 `batch.messages.isEmpty() && batch.summary == null`，所以「MessagingStyle 重貼整段歷史、每一則都被判為重複、`decisions` 為空」會走到這裡：**零列寫入，但頁面宣稱「存下副本」。** 全部 `Decision` 皆為 suppressed（`newMessageIds` 空、`suppressedCount > 0`）也是同一個結果。

這一行是這個 commit 為了誠實性而新增的唯一數字，它自己不誠實。

**修法**：

```kotlin
if (outcome.newMessageIds.isNotEmpty() || outcome.summaryRecorded) {
    val at = System.currentTimeMillis()
    _status.update { it.copy(lastCommittedAtEpochMs = maxOf(it.lastCommittedAtEpochMs ?: 0L, at)) }
}
```

### I3. 清掃的 `stalePending` 是無條件 UPDATE，會蓋掉剛剛 commit 成功的 `LOCAL_COPY`

`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt:102-103`

```kotlin
val stalePending = db.messageDao().pendingMedia(500).filter { it.observedAtEpochMs < now - PENDING_MEDIA_GRACE_MS }
for (row in stalePending) db.messageDao().setMedia(row.id, MediaState.FAILED.name, null)
```

`setMedia` 沒有狀態守衛（`Daos.kt:265-266`）：

```kotlin
@Query("UPDATE message SET mediaState = :state, mediaBlobId = :blobId WHERE id = :id")
```

`RetentionService.runOnce` 是 `maintenance.work {}`，`MediaCopier.copyPending` 也是 `maintenance.work {}` —— **兩者可以並行**。可達的交錯：

1. 金庫上鎖 > 1 小時，journal 累積；解鎖後 `replayJournal` 以**當初的** `observedAtEpochMs` 寫入 message 列並排隊媒體複製。
2. 12 小時週期的清掃同時醒來，`pendingMedia` 讀到這些列（`observedAtEpochMs` 已超過 1 小時的 grace）。
3. 在讀清單與寫 FAILED 之間，`MediaCopier.store()` 的 transaction 提交，寫下 blob 列並把 message 設為 `LOCAL_COPY` + `mediaBlobId`。
4. 清掃接著執行 `setMedia(id, FAILED, null)`，**把成功的副本標成失敗、並把 `mediaBlobId` 清成 null**。
5. 下一輪 `orphans()`（`m.mediaBlobId IS NULL`，`Daos.kt:366`）會把那個 blob 與它的檔案刪掉。使用者永久失去一張已經成功複製的照片，畫面上寫著「媒體複製失敗」。

**修法**：改成條件式更新，並在 DAO 層加一個新 query：

```kotlin
@Query("UPDATE message SET mediaState = :state, mediaBlobId = NULL WHERE id = :id AND mediaState = 'PENDING'")
suspend fun settlePendingMedia(id: Long, state: String)
```

`MediaCopier` 的 catch 路徑（`MediaCopier.kt:101, 108`）也建議改用同一個守衛。

### I4. `MEDIA_QUEUE_OVERFLOW` 之後那些列在畫面上宣稱「複製進行中」，最長一小時

`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:884-890`

溢位分支寫了 diagnostic 然後 `return false`，`outcome.pendingMediaMessageIds` 對應的列留在 `PENDING`。`mediaLabel(MediaState.PENDING)` 在氣泡上是沙漏／進行中的樣子，但**這時沒有任何工作在進行，也永遠不會有**——要等到 12 小時週期的清掃、而且要 `observedAtEpochMs` 超過 1 小時，才會被寫成 FAILED。

這與專案硬規則「Gaps are shown, never hidden」的精神相牴觸：不是隱藏，但是**主動宣稱一個不存在的狀態**。註解自己承認「the drop itself is recorded rather than left to look like a copy still in flight」，但實際上畫面看起來就是 in flight。

**修法**：溢位當下就把那批 id 寫成終局狀態（`FAILED`，或新增一個 `QUEUE_OVERFLOW` state 讓標籤能說實話），diagnostic 保留。

（返回值本身是對的：`return false` 讓呼叫端 `finally { if (item.captured.bitmap != null && !bitmapHandedOver) queuedBitmaps.decrementAndGet() }`（:807）正確地把 bitmap 計數還回去；`queuedMediaCopies` 在溢位分支先 `incrementAndGet` 再 `decrementAndGet`，在正常分支於 `finally` 遞減，兩條路徑都平衡。這一點沒有問題。）

### I5. `atEnd` 的 `?: true` 在設定變更／展開摺疊時會丟掉還原的捲動位置

`feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt:152-153`

```kotlin
val atEnd = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= lastIndex - 1 } ?: true
if (atEnd) listState.animateScrollToItem(lastIndex)
```

`landed` 是 `rememberSaveable`。**旋轉、摺疊／展開、進入分割視窗**時 composition 重建、ViewModel 存活，所以第一次組合時 `state.messages` 已經是 N 筆、`landed` 還原為 `true`，`LaunchedEffect` 立刻進入 `atEnd` 分支。但此時尚未經過第一次 measure，`listState.layoutInfo.visibleItemsInfo` 是空的 → `?: true` → 無條件 `animateScrollToItem(lastIndex)`。

這正是 commit message 說要修掉的缺陷 (b)：「it re-ran on every size change… which also overwrote a scroll position」。而且本輪的另一半工作（FT-02）就是關於 600–839dp 這個會在摺疊裝置上反覆跨越的斷點，這條路徑一定會被走到。

（行程死亡還原的情況反而沒問題：`layoutInfo` 那時已含 index 0 的 `info` item，`0 >= N-1` 為 false。所以問題只在「已組合過、又被重建」。）

**修法**：把 fallback 從「假定在底部」改成「不知道就不要動」：

```kotlin
val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
if (last >= lastIndex - 1) listState.animateScrollToItem(lastIndex)
```

其餘部分是對的：`lastIndex = state.messages.size`（`info` item 佔 index 0）與 `anchor + 1` 修掉了 off-by-one；清單短於視窗時 `visibleItemsInfo.last().index == N == lastIndex`，判斷成立；找不到 messageId 時什麼都不做也是正確的取捨。

### I6. 剪貼簿沒有標記 `EXTRA_IS_SENSITIVE`——這是本 App 威脅模型下的正面問題

`feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt:517-521`

```kotlin
context.getSystemService(ClipboardManager::class.java)
    ?.setPrimaryClip(ClipData.newPlainText("QuietInbox", text))
```

Android 13 起，複製會彈出系統剪貼簿浮層並**把內容預覽顯示出來**；剪貼簿本身也對其他 App 可讀。一個以「本機加密保存私人訊息」為賣點的 App，新增了兩條把訊息本文送進剪貼簿的路徑（工具列按鈕 :235、無障礙 custom action :293），卻沒有做 Android 提供的最低限度保護。

**修法**：

```kotlin
val clip = ClipData.newPlainText("QuietInbox", text)
if (Build.VERSION.SDK_INT >= 33) {
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
}
```

順帶：Android 12 以下沒有系統回饋，複製成功與否使用者無從得知（建議一個 Snackbar）；`copyToClipboard` 對 `text.isBlank()` 直接 return，於是選了一則純圖片訊息按複製會**靜默無事**。

### I7. 新的「未查看」篩選在空結果時說「還沒有任何副本」

`feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt:166`

```kotlin
title = stringResource(if (state.filter.archived) R.string.inbox_empty_archived_title else R.string.inbox_empty_title),
```

`inbox_empty_title` = 「還沒有任何副本」／"Nothing captured yet"，`inbox_empty_body` = 「你啟用的來源 App 發出通知後，副本會出現在這裡。先發一則合成測試通知…」。

本 commit 新增的「未查看」chip（`InboxScreen.kt:308-314`）一旦沒有未查看的對話，滿滿一整個金庫的使用者會看到「還沒有任何副本」加上一顆「發送測試通知」按鈕。`archived` 這條路徑當初有給自己的標題，新的這條沒有。

這是本輪自己新造的不實文案，而且與同一批 commit 到處在修的東西同一類。

**修法**：加 `inbox_empty_unviewed_title`（五個 catalogue）並在 title 的三元判斷中處理；`inbox_empty_body` 與那顆測試按鈕在有篩選時也不該出現。

### I8. `docs/COMPATIBILITY.md` 新章節插在清單中間，把三顆 bullet 孤立到錯誤的標題底下

`docs/COMPATIBILITY.md:38`（新的 `## Hidden previews…`）與 `docs/COMPATIBILITY.md:51-55`
`docs/zh-Hant/COMPATIBILITY.md`（同一位置，:46 起）

「Sources are configured per package, not per profile…」與「Android Q and older on low-RAM ("Go") devices…」這兩顆 bullet（zh-Hant 是 :46 與 :49）原本屬於 `## Work profiles, Device Policy and low-RAM devices (QI-ID-008)`，現在被新標題切開，渲染後會變成「隱藏預覽」章節底下的清單項目——語意上完全錯位（低記憶體裝置不綁定 listener，跟預覽被遮蔽毫無關係）。兩個語言版本都有。

**修法**：把 `## Hidden previews…` 整段移到 QI-ID-008 清單**之後**（低 RAM 那顆 bullet 的下面），兩語同步。

---

## Minor / nitpicks

- **M1（誠實標籤，遺漏的一半）** `core/designsystem/src/main/res/values-b+zh+Hans/strings_analytics.xml`：`analytics_tab_quiet` 仍是「沉默率」、`analytics_quiet_value` 仍是「沉默 %1$d%%」。這一輪把 zh-Hant 的「神隱率」改掉的理由是「這是畫面本身禁止的宣稱」；「沉默」同樣把行為歸給對方（對方保持沉默），而 en/ja/ko 現在講的都是「安靜的日子」這個**日子的屬性**。zh-Hans 成了唯一的例外，應一併改為「安靜天數 / 安靜 %1$d%%」。
- **M2（死字串）** `health_saved`（五語都加了）在整個 `.kt` 樹中沒有任何呼叫者。這正是本 commit 自己修掉的 `inbox_unviewed` 那一類缺陷，同一批又製造了一個。刪掉，或補上呼叫端。
- **M3（`loadMore` 的守衛不是 generation）** `SearchViewModel.kt:94` 用的是值相等（`query`/`range`/`packages`），不是單調遞增的 generation。使用者 A→B→A 的輸入序列（清空再打回同一字串）會讓舊頁通過檢查、被 append 到新結果之後，造成重複列。改成 `private var generation = 0` 並在每次 `run()`／filter 變更時 `++`，`loadMore` 帶著快照比對。
- **M4（`readScope` 判定：可接受，但 kdoc 的宣稱未被測試）** `MediaCopier.kt:64` 的 `readScope` 是 `@Singleton` 欄位且從不取消——**這不是洩漏**：`Deferred` 在逾時後被 `cancel()`，`SupervisorJob` 不會累積失敗，真正的代價是被 `runInterruptible` 中斷不了的執行緒，而那被 `READ_PARALLELISM = 2` 上界住。但兩點需要記下：(a) 一旦兩條執行緒永久卡死，`Dispatchers.IO.limitedParallelism(2)` 的內部佇列再也不會被排空，之後每一次 `async` 都會在佇列裡留下一個已取消的任務，行程存活期間單調成長；(b) `MediaReadTest` 全部用 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`（:73, :98, :122），**沒有一個測試用到 `limitedParallelism(READ_PARALLELISM)`**，所以 kdoc 那句「once they are all parked, later copies time out and are recorded as failures instead of piling up」是未經驗證的宣稱。建議：飽和時額外寫一筆 diagnostic（例如 `MEDIA_READ_TIMEOUT`），讓健康頁能說出「provider 沒有回應」而不是只留下一堆 `FAILED`。
- **M5（`run()` 不清 `loadingMore`；舊查詢結果可能貼到新查詢上）** `SearchViewModel.kt:129-136` 的 `local.update { it.copy(...) }` 用的是**當下**的 state，但結果來自快照 `s`。使用者在 `run("abc")` 執行中再輸入 `"abcd"` 時，`"abc"` 的結果會被寫進 `query == "abcd"` 的 state（約 250ms 的錯誤顯示）。`run()` 也不重置 `loadingMore`。加上 M3 的 generation 就一併解決。
- **M6（grace window vs 時鐘跳動）** `RetentionWorker.kt:94-96` 的 `strayCutoff = now - 1h` 與檔案的 `lastModified()` 都是牆鐘時間。使用者手動把時間往前調（或極端的 NTP 修正）之後，一個 5 秒前寫下的檔案會立刻「看起來超過一小時」，若此時清掃剛好在該複製 commit 之前執行，就會刪掉一個即將被 blob 列指向的檔案，留下一列 `LOCAL_COPY` 但檔案不存在。機率極低，但 CHANGELOG 寫的是「so it cannot race a copy that has not committed yet」——`cannot` 太滿。建議用 `SystemClock.elapsedRealtime()` 記錄行程內寫檔時間，或把措辭改成 `is very unlikely to`。
- **M7** `CaptureCoordinator.kt:891` 的 `scope.launch { … } finally { … }`：若 `scope` 已被取消，coroutine 不會執行 body，`finally` 不會跑，而 `return bitmap != null`（:900）又告訴呼叫端「bitmap 已交出」，於是 `queuedMediaCopies` 與 `queuedBitmaps` 同時洩漏。目前 `scope` 是行程生命週期，風險低，但把兩個遞減移到 `launch` 之外的 `try/catch` 會更穩。
- **M8（測試覆蓋缺口）** 本輪新增的兩個 retention 行為——目錄層級的 stray 檔案回收、`stalePending` 結案——**完全沒有測試**（JVM 或 instrumented 都沒有）。`DeletionGraphTest` 只覆蓋既有的列層級路徑。I3 那個競態就落在這個缺口裡。至少該補：一個 instrumented 測試放一個 2 小時前的孤兒檔 + 一個剛寫的檔，驗證只有前者被刪；以及一個驗證「已 commit 的 `LOCAL_COPY` 不會被 `stalePending` 蓋掉」。
- **M9** `OnboardingViewModelTest.kt:56`：`every { synthetic.postConversation(any(), any()) } returns TEST_MESSAGES` —— `postConversation` 的回傳值是**通知 id**，不是訊息數。在 mock 裡無害，但會誤導後續讀者。
- **M10** zh-Hant 分頁標題改成「安靜天數」（一個天數），但主數值 `analytics_quiet_value` 仍是「安靜 %1$d%%」（一個百分比），en 則是 "Quiet rate" / "%1$d%% quiet"。標題與數值的量綱對不上；副標「%2$d 天中有 %1$d 天」有救回來，但名稱本身建議改成「安靜日比例」之類。
- **M11** `ConversationScreen.kt:436` `this.selected = selected` 無條件設定，`stateDescription` 卻只在 `selecting` 時設。非選取模式下每一則氣泡都會帶著 `isSelected = false` 進 `AccessibilityNodeInfo`。把 `selected` 也包進 `if (selecting)` 較乾淨。

---

## 對六個「偏離 issue」的逐項裁決

**1. H4 —「last system callback」改成「last accepted event」：判定正確，但建議補一個後續。**
`lastEventAtEpochMs` 確實只在 `CaptureCoordinator.kt:730` 的 `enqueue` 裡、於來源過濾之後蓋章，所以被停用來源的 callback、被有界佇列丟掉的 callback 都不會碰到它。用原本的名字會是新的不實標籤，改名是對的，而且五語（`health_last_event` / `health_last_event_never`）都翻得精準。**但這個改名沒有補上 H4 真正想要的診斷能力**：目前的值無法區分「DPC 把所有東西都擋掉」與「只是被停用的來源在發通知」。建議另開 issue：在 `onNotificationPosted` 的最前面寫一個 `@Volatile var lastCallbackAtEpochMs: Long`（單一 long 的寫入，零配置、符合「callback thread 必須輕量」的規則），只放進診斷摘要、不上畫面。這不屬於本輪 blocker。

**2. `inbox-incomplete-capture-marker` (a) — schema 工作延後：確認本區間確實 schema-free。**
`git diff --name-only b30761d..edd261f` 中沒有任何 `platform/storage/schemas/**`、`@Entity`、`Migration` 或 `QuietInboxDatabase.kt` 的變更；新增的兩個 query（`observeCapturedSince`、`allFileNames`）都只用既有欄位。延後成立。

**3. FT-02 — 拆成 `railLayout`(600dp) / `twoPane`(840dp)：判定正確，且優於 issue 提的兩個方案。**
反編譯證據見上表：`WIDTH_DP_BREAKPOINTS_V1 = [0, 600, 840]`，`calculatePaneScaffoldDirective` 在 expanded 才給 2 個水平分割。`rememberListDetailSceneStrategy` 用的正是這個預設 directive，所以 `twoPane = isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)` 與框架行為精確對齊。移到 840dp 會讓 600–839dp 失去 rail，用 `calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth` 會在 600dp 的窄欄硬塞兩欄——兩個 issue 建議都比這個差。
下游全部檢查過：`showChrome`（:94）、`showBackButton`（:139）用 `twoPane` ✓；`if (railLayout)`（:156）用 rail ✓。`tools/demo-screenshots.sh` 也拆成 `LAYOUT`（tab 點擊，600dp）與 `PANES`（conversation-ready 判定與是否送 BACK，840dp），沒有殘留混用。`currentWindowAdaptiveInfoV2()` 的 V2 斷點集（多了 1200/1600）不影響 `>= 840` 的判斷。

**4. O8 — Next 不以「擷取成功」為前提：正確。**
把 Next 綁在成功擷取上，會讓工作設定檔 / DPC 封鎖的使用者永遠出不了 onboarding，而 `docs/COMPATIBILITY.md` 明確說 App 偵測不到這種封鎖。現在的做法（20 秒後顯示失敗、秀出 listener 狀態、提供重送、把出口改名為「不驗證，直接繼續」）既誠實又不設陷阱。`ob_skip_unverified` 五語都翻得準確。**同意這個偏離。**

**5. A11Y-04 — 不做 Share：正確。**
分享功能在整個 repo 不存在，臨時為了無障礙而發明一個把訊息本文送出 App 的出口，對這個 App 是**擴大攻擊面**而不是補齊功能。Copy + Delete 兩個 custom action 已經涵蓋螢幕閱讀器實際缺的東西。**同意。**（不過見 I1：這兩個 action 掛的節點與 CHANGELOG 描述的節點不同；I6 也指出 Copy 這條新出口本身還缺一層保護。）

**6. MED-11 — 一律寫 `FAILED` 而非 `URI_EXPIRED`：正確，不算過度保守。**
`RetentionWorker.kt:100-101` 的註解說得對：清掃只知道「這次複製沒有走完」，沒有任何觀測支持「URI 過期」這個具體原因。在一個把「誠實標籤」寫進硬規則的專案裡，猜一個更具體的原因才是違規。**同意。**（真正的問題不在選哪個 state，而在 I3 的無條件覆寫。）

---

## 測試：哪些真的會在舊行為下失敗

**`MediaReadTest`（10 個，`platform/media` 從零到有）**

- 前 7 個（:32–:70）是純函式的失敗對應。`:32`「provider 消失 → `URI_EXPIRED` 而非 `TOO_LARGE`」在舊碼下**確實會失敗**（QI-MEDIA-013 的迴歸測試），`:62` 的空 payload 同理。其餘幾個是把既有正確行為釘住，價值在於這個模組先前一個測試都沒有。
- `:72`「a provider that never answers is abandoned」與 `:97`「the caller is released while the read is still stuck」是**本輪最有價值的兩個測試**：舊碼的 `withTimeout` 包在阻塞 binder 呼叫外面，逾時永遠不可能觸發，這兩個測試會直接掛住到 `withTimeout(5_000)` 失敗。真迴歸測試。
- `:121`「a cancelled caller stays cancelled」驗證 `CancellationException` 沒被吞掉，也是真的。
- 但如 M4：三個都用 `Dispatchers.IO`，沒有一個用 `limitedParallelism(2)`，所以「飽和之後後續複製快速失敗」的設計宣稱沒有被測到。

**`OnboardingViewModelTest`（5 個）**

- `:73`「a vault that already holds messages does not make the test pass」——**這一個只斷言新程式碼的形狀。** mock 是 `every { inbox.observeCapturedSince(any(), any()) } returns captured`（初值 0），測試裡**完全沒有模擬「金庫已有訊息」**（連舊的 `observeCounts()` 都沒有 stub）。它斷言的只是「mock 回 0 時 `capturedMessages` 是 0」。要真的守住這個不變量，應該讓 `observeCapturedSince` 依 `since` 參數回不同值，並額外驗證呼叫時帶進去的 `packageName` 與 `since` 正確。
- `:85`「one of three is not a success」——有效：把「1 > 0 就算成功」這個舊判斷永久排除。（嚴格說舊判斷在 Composable 裡，ViewModel 測試本來也抓不到，但不變量的位置現在對了。）
- `:98` / `:105` / `:118`——`testFailed` 的逾時分支與「晚到的副本勝過過期的逾時」都是真測試，用 `advanceTimeBy` 精確地測到舊碼完全沒有的分支。這三個是好的。

**`SearchViewModelTest`（2 → 4）**

- 前兩個是既有測試遷移到 paged API，行為變更本身。
- `:92`「a full page keeps its cursor」——有效，釘住了「有 cursor 就不准叫總數」。
- `:107`「a page that verifies nothing ends the run」——**這個測試斷言的是錯的行為**（見 C1），必須連同實作一起改寫成「空頁保留 cursor」。

**`MonogramTest`（+2）**

- `anEmojiIsNeverCutInHalf`：對舊的 `take(2)` 會失敗（`"😀🎉"` 會回半個 surrogate pair），真迴歸測試，而且額外加了 surrogate 配對的不變量斷言，寫得好。
- `rightToLeftNamesGiveTwoInitials`：對舊碼會通過（阿拉伯／希伯來文字都在 BMP，`take(1)` 沒問題）。這是把既有行為釘住，不是迴歸測試——沒有問題，但它不是「emoji 修正」的證據。

**沒有測試的**：I3 的競態、stray 檔案回收、`MEDIA_QUEUE_OVERFLOW` 的計數平衡、`lastCommittedAtEpochMs`、`InboxViewModel` 的 `SavedStateHandle` 往返、`readScope` 飽和。

---

## Observations（做對的地方 / 其他）

1. **媒體逾時的核心修正是紮實的。** 我自己追過完整路徑：`copyPending` → `maintenance.work {}` → `supervisorScope` → `parallelism.withPermit` → `copyUri` → `readWithTimeout`。`await()` 是真正的暫停點，`withTimeoutOrNull` 因此一定會在 10 秒內返回；permit 在 `withPermit` 的 `finally` 釋放；`maintenance.work` 註冊的 job 因而完成，`exclusive` 的 `snapshot.joinAll()`（`VaultMaintenance.kt:91`）不再被卡住。QI-MEDIA-014 確實修好了。`CancellationException` 在 `MediaRead.kt:76-79` 與 `MediaCopier.kt:102-106` 兩處都正確地 rethrow，取消時不寫任何列。
2. **`.tmp` 殘留的處理是正確的。** `BlobCipher.encryptToFile`（`BlobCipher.kt:94-99`）寫 `<name>.tmp` 再 rename；失敗時 `.tmp` 留下，而 `store()` 的 `written` 清單不含它，所以舊碼永遠回收不到。新的目錄清掃會回收（`.tmp` 永遠不會出現在 `allFileNames()`，且必然超過 1 小時），而且不可能誤刪進行中的寫入。這是這個清掃最實在的收益。
3. **目錄先讀、資料庫後讀的順序推理成立。** 加上「`BackupService.apply` 在 `exclusive` 內、不可能與 `work {}` 同時跑」這個已驗證事實，唯一的並行寫入者就是 `MediaCopier.store()`；它從 `encryptToFile` 到 transaction 提交的耗時是秒級（`holder.db()` 在上鎖時是**丟例外**而不是掛住，`DatabaseHolder.kt:61-68`），遠低於 1 小時。除了 M6 的時鐘跳動，這個保護是充分的。
4. **`allFileNames()` 的 SQL 正確。** SQLite 的 `UNION` 中每個 term 各有自己的 `WHERE`，所以第二個 term 的 `thumbFileName IS NOT NULL` 生效，不會把 NULL 混進 `List<String>`。
5. **`ob_test_captured_of` 的參數順序**（zh-Hant/zh-Hans 是 `%2$d 則中…%1$d 則`，ja 是 `%2$d 件中 %1$d 件`）全部使用具名位置參數，五語都正確；`check-strings.py` 也過了。ja/ko/zh-Hans 的 25 條新字串我逐條讀過，**沒有任何一條宣稱程式碼觀測不到的事**——`health_gaps_caveat`、`ob_test_failed_body`、`conv_open_source_fallback`、`search_results_shown` 四條翻譯都忠於英文原意且措辭同樣克制。唯一的例外是 M1 的 zh-Hans 遺漏。
6. `conv_open_source_fallback` 從條件顯示改成無條件顯示是對的：`fallbackToHome` 原本硬編為 `true`（舊 `ConversationViewModel.kt`），等於每次都宣稱「原始通知已不存在」，而 App 根本無從得知。新文案只講它確定知道的事（不保留 `PendingIntent`，所以開的是 App 起始畫面）。
7. `openSourceNotificationSettings`（`HealthScreen.kt:472-486`）只開系統 Settings、從不碰來源 App 的 UI，也不觸發任何通知——符合「Never act on source notifications」的硬規則。
8. `diagnosticLabel`（`Labels.kt:88-98`）對未知代碼 fallback 回代碼本身、且把原始代碼保留在 supporting line，做法正確。
9. `docs/SCOPE.md` 對 720dp 的驗證宣稱（`wm size` + `tools/demo-screenshots.sh`）與 `a290798` 的 commit message 一致，工具腳本的 `PANES` 拆分也支持這個宣稱。雙語文件在本區間**全部成對更新**（SCOPE / TEST_MATRIX / ARCHITECTURE / COMPATIBILITY / ADR-0005 都有 zh-Hant 對應變更），除了 I8 的結構問題。
10. `README.md` 與 `docs/SCOPE.md` 把 Play 從「0.1.0 上架、0.1.3 審查中」改成「0.1.3 已上架」，與 memory 中「Play submission pending」的舊狀態不一致——這是 `a272344` 這個 commit 的目的，我無法從 repo 內獨立驗證 Play 上的實際狀態。**請 push 前自行以 Play Console 確認 0.1.3 真的已 published、且真的是 172 個國家**，因為這句話會出現在公開 README 上。

---

## 建議的收斂順序

1. C1（一行實作 + 改寫測試 4 + TEST_MATRIX 兩語 + CHANGELOG）
2. I3（DAO 加條件式 update）、I2（改 `System.currentTimeMillis()` + `maxOf` + 只在有寫入時蓋章）、I4（溢位當下寫終局狀態）
3. I1（把 clickable/semantics 上提，或修正 CHANGELOG 的宣稱）、I5（`?: return@LaunchedEffect`）
4. I6、I7（新增一條字串 × 5）、I8（移動章節 × 2 語）
5. M1、M2 可以順手做完
6. M8 的兩個 retention 測試建議在同一輪補上——I3 是這個缺口直接造成的
