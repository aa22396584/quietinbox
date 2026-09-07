# QuietInbox 第 29 輪程式碼審查報告（Round 29 — audit-2 fixes, part 1）

- **審查對象**：[`/Users/iml1s/Documents/mine/quietinbox`](file:///Users/iml1s/Documents/mine/quietinbox)
- **審查區間**：`git -C /Users/iml1s/Documents/mine/quietinbox diff b30761d..edd261f`（包含 commit `a272344` 至 `edd261f` 共 6 個 commits）
- **參考基準**：`/private/tmp/claude-501/-Users-iml1s-Documents-mine-quietinbox/e93272a8-1a1d-4635-87a8-e9677fec237e/scratchpad/round29-brief-safe.md`、`CLAUDE.md`、`docs/reviews/README.md`
- **審查模式**：唯讀審查（READ-ONLY），未修改倉庫專案原始碼，未啟用任何編排工作流模式。

---

## Verdict：**APPROVE WITH MINOR FIXES**

**0 Critical、1 Important、3 Minor / nitpicks、4 Observations。**

本輪改動（`b30761d..edd261f`）涵蓋媒體管線超時與掛起修復、大視窗自適應斷點分離、無障礙語意樹重建、擷取健康度誠實標籤、Onboarding 擷取測試判定修正與搜尋 keyset 分頁。整體工程品質極高，231 項 JVM 單元測試與權限閘門、字串完整性檢查全數綠燈。

唯有一項 **Important** 級別發現建議在後續提交中補齊：
- [`platform/capture/.../CaptureCoordinator.kt:884-889`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L884-L889)：當媒體佇列超過 32 個上限而觸發 `MEDIA_QUEUE_OVERFLOW` 時，訊息列已寫入資料庫但保持 `mediaState = PENDING`。該副本實際上永遠不會被排程執行，卻要在 UI 氣泡中顯示沙漏旋轉圖示長達 1 小時（直到 `RetentionWorker` 清理），違反了「缺口不隱瞞、資料品質即時誠實」的原則。應在丟棄當下直接將其標記為 `FAILED`。

---

## 審查發現清單（Findings）

### Critical（必須在 push 前修復）
*無*（0 項）。無編譯錯誤、無測試失敗、無未宣告權限、無破壞性 Migration、無死鎖風險。

---

### Important（應在 push 前修復）

#### 1. `CaptureCoordinator` 媒體佇列溢位時，訊息列長時間滯留在 `PENDING` 狀態誤導 UI
- **位置**：[`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:884-889`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L884-L889)
- **問題描述**：
  在 `CaptureCoordinator.processJournaled` 中，當 `queuedMediaCopies.incrementAndGet() > MAX_QUEUED_MEDIA_COPIES` (32) 時，協調器記錄了 `ingest.diagnostic("MEDIA_QUEUE_OVERFLOW", ...)` 並直接 `return false`，不再啟動協程執行 `mediaCopier.copyPending(...)`。
  然而，該訊息列在此之前已於 [`ingest.commit(...)`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L869) 中寫入 SQLite，且其欄位值預設為 `mediaState = MediaState.PENDING.name`。
  程式碼註解寫道：
  > `// The copy never runs. The rows stay PENDING and the retention sweep settles them;`
  > `// the drop itself is recorded rather than left to look like a copy still in flight.`
  
  這與 [`RetentionWorker.kt:98`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt#L98) 的清理邏輯產生了脫節：`RetentionWorker` 每一輪 sweep 只會將 `observedAtEpochMs < now - 1小時` 的 PENDING 訊息翻轉為 `FAILED`。
  這意味著在長達 1 小時（乃至 WorkManager 觸發間隔更久）的期間內，使用者在對話畫面 [`ConversationScreen.kt:446`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L446) 中，會看見這則訊息持續顯示載入中沙漏圖示，誤以為系統仍在嘗試拉取媒體；而事實上系統早在數秒前就已永久丟棄了該媒體複製任務。
- **修復建議**：
  在判定 `queuedMediaCopies > MAX_QUEUED_MEDIA_COPIES` 丟棄複製時，呼叫端當下即位於 `pipelineMutex` 保護之內，應直接呼叫 `db.messageDao().setMedia(id, MediaState.FAILED.name, null)`（或由 `ingest` 封裝），讓 UI 立即如實呈現失敗標籤，無須等待一小時後的背景清理。

---

### Minor / Nitpicks（次要問題與細節）

#### 1. `docs/SCOPE.md` 與 `docs/zh-Hant/SCOPE.md` 遺漏更新測試計數
- **位置**：[`docs/SCOPE.md:26`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L26) 與 [`docs/zh-Hant/SCOPE.md:24`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/SCOPE.md#L24)
- **問題描述**：
  在 commit `edd261f` 中，`SearchViewModelTest` 的測試案例由 2 個擴充為 4 個（補齊了 keyset 分頁游標保留與零驗證候選終止測試）。
  `docs/TEST_MATRIX.md:44` 與 `docs/zh-Hant/TEST_MATRIX.md:44` 均已正確更新為 `SearchViewModelTest (4 items: ...)` / `SearchViewModelTest（4 個：...）`。
  然而，`docs/SCOPE.md` 的表格列仍記載：
  > `SearchViewModelTest (2), ConversationViewModelTest (1)`
  
  `docs/zh-Hant/SCOPE.md` 的表格列亦仍記載：
  > `SearchViewModelTest（2）、ConversationViewModelTest（1）`
  
  違反了專案規範中「文件嚴格不得落後或超前程式碼」的原則。應將兩處文件的數字更新為 `4`。

#### 2. `OnboardingScreen.kt` 步驟 3 測試失敗時，同時顯示了兩個功能相同的按鈕
- **位置**：[`feature/onboarding/src/main/kotlin/dev/quietinbox/feature/onboarding/OnboardingScreen.kt:135-146`](file:///Users/iml1s/Documents/mine/quietinbox/feature/onboarding/src/main/kotlin/dev/quietinbox/feature/onboarding/OnboardingScreen.kt#L135-L146)
- **問題描述**：
  在引導流程的擷取測試步驟（`step == 3`）中：
  ```kotlin
  if (state.step == 3 && (!state.testSent || state.testFailed)) {
      TextButton(onClick = viewModel::next) { Text(stringResource(if (state.testFailed) R.string.ob_skip_unverified else R.string.ob_skip)) }
  }
  Button(
      onClick = { if (last) viewModel.finish(onFinished) else viewModel.next() },
      enabled = when (state.step) {
          2 -> state.granted
          else -> true
      },
  ) { Text(stringResource(if (last) R.string.ob_finish else R.string.ob_next)) }
  ```
  當測試逾時失敗後，畫面右下方同時出現：
  - 次要文字按鈕：`TextButton("不驗證直接繼續")`（`ob_skip_unverified`）
  - 主要填色按鈕：`Button("下一步")`（`ob_next`，因為 `else -> true` 始終為 enabled）
  
  兩者點擊均呼叫 `viewModel::next()` 前往下一頁。雖然這確保了使用者不被卡在 Onboarding，但兩個相鄰按鈕功能完全重複。
- **修復建議**：
  若主要按鈕在 `state.testFailed` 或未發送測試時維持啟用，則無須額外渲染次要的 `TextButton`；或者主要按鈕僅在 `state.testSucceeded` 時啟用，而將 `TextButton` 作為唯一跳過出口。

#### 3. 平板寬螢幕（≥ 840dp）從搜尋結果進入對話時無返回鍵
- **位置**：[`app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt:139`](file:///Users/iml1s/Documents/mine/quietinbox/app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt#L139)
- **問題描述**：
  `ConversationScreen` 的返回鍵顯示邏輯為 `showBackButton = !twoPane`（`twoPane` 為寬度 ≥ 840dp）。
  在收件匣（Inbox）進入對話的情境下，左側為收件匣清單、右側為對話內容，隱藏返回鍵是合理的（List-Detail 雙欄佈局）。
  但若使用者在搜尋頁（`SearchRoute`，非 List-Detail 結構）點擊某一搜尋結果跳轉至 `ConversationRoute`：
  在 `twoPane == true` 的大平板上，`showBackButton` 依然為 `false`。使用者在對話頁面頂部看不到任何返回按鈕，只能仰賴系統返回手勢，或點擊左側 NavigationRail（但點擊 Rail 會觸發 `goTop` 清空 backstack）。
- **修復建議**：
  可判斷返回堆疊前一個路由是否為 `InboxRoute`，或是僅在當前確實處於雙欄展開且左側為收件匣時才將 `showBackButton` 設為 `false`。

---

### Observations（觀察與架構反饋）

1. **`readMedia` 將空 Payload 視為 `URI_EXPIRED` 之設計**：
   在 [`MediaRead.kt:40`](file:///Users/iml1s/Documents/mine/quietinbox/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaRead.kt#L40)，若讀取的位元組長度為 0，回傳 `Read.Failed(MediaState.URI_EXPIRED)`。在 Android ContentProvider 體系中，第三方 App 清空暫存檔時經常產生 0-byte 串流，將其對應為連結失效避免了建立無意義的空本機複本，邏輯嚴謹且有單元測試防護。
2. **`InboxViewModel` 的第一個 `SavedStateHandle` 典範**：
   [`InboxViewModel.kt:67-75`](file:///Users/iml1s/Documents/mine/quietinbox/feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxViewModel.kt#L67-L75) 正確將套件過濾清單、封存狀態、未查看過濾狀態透過 `SavedStateHandle` 存入 Bundle，在 Process Death 與 Activity 重建時能完美復原篩選條件，是專案架構良性演進的重要範例。
3. **`MonogramTest` 中的 `rightToLeftNamesGiveTwoInitials` 為正向防禦測試**：
   該測試在舊程式碼上本就可通過（因阿拉伯文與希伯來文字元位於 BMP 單碼元區），其價值在於確立多語系字形提取的 Regression 基準，而非修復歷史缺陷。
4. **搜尋候選與驗證分離架構**：
   `SearchRepository` 在 SQLite 端利用 bigram/trigram 快速拉取最多 200 頁候選（40,000 筆），在記憶體中進行標準化完整字串匹配。因此當某頁候選全數驗證失敗時，將 `next` 游標直接設為 `null` 截斷，既能防止無限迴圈，也能保證 UI 標頭誠實揭示「已無後續符合結果」。

---

## 逐項回應 Brief 核心爭議點（Deviations Challenge）

Brief 提出了 6 處實作偏離 Issue 原先要求的決策，經深度檢驗分析如下：

| 編號 | Issue 原始要求 | 本輪實際實作決策 | 決策是否成立 | 評估與工程論據 |
| :---: | :--- | :--- | :---: | :--- |
| **H4** | 要求於健康頁呈現「最後系統回調時間」（last system callback） | 改為呈現「最後接收的事件」（last accepted event，`lastEventAtEpochMs`） | **完全成立** | `enqueue` 位於來源套件過濾器之後。若命名為「最後系統回調」，當系統針對未監控 App（例如下載管理員、電量通知）頻繁觸發 `onNotificationPosted` 時，該時間戳不會更新，將對使用者構成虛假陳述；且回調執行緒依規範必須極度輕量，不宜在此進行額外負擔。名為「最後接收的事件」誠實反映了金庫管線真正接納的時間點。 |
| **marker (a)** | 要求在 `gap_interval` 資料表增加帶有對話識別的 scope 欄位 | 經二次驗證發現無任何 `recordGap` 能預先知道對話；本輪完全不碰 Schema | **完全成立** | 經比對 `platform/storage/schemas/`，本輪改動的 git diff 在 Schema 目錄完全為空。中斷區間（Gap）屬於系統監控層級，在封包進入解析與對齊前根本無法得知歸屬對話。暫緩且不引入無效欄位是正確的架構決策。 |
| **FT-02** | 提供兩解：將 `wide` 門檻移至 840dp，或強行將 600dp 改為雙窗格指令 | 兩者皆不採納，而是將單一 `wide` 拆分為 `railLayout` (600dp) 與 `twoPane` (840dp) | **完全成立** | 嚴格符合 Material 3 規範：導覽欄（Navigation Rail）在 600dp 起取代底部列；而 `ListDetailSceneStrategy` 預設的雙欄分割僅在 Expanded (840dp) 生效。若採前者，600-839dp 會遺失導覽欄；若採後者，600dp 螢幕會擠壓雙欄導致可用性劣化。拆分為兩個獨立斷點完全反映了真實佈局狀態。 |
| **O8** | 要求 Onboarding 流程必須「以驗證成功為結束條件」 | Next 按鈕刻意不強制以驗證成功為門檻，而是呈現失敗並提供「不驗證直接繼續」 | **完全成立** | 在企業 MDM、工作設定檔、OEM 特殊省電管理或無通知權限等極端環境下，強行鎖死 Next 按鈕會將使用者永久困在引導頁面無法進入 App。誠實呈現失敗而非鎖死大門，符合 Android 生態系的容錯原則。 |
| **A11Y-04** | 要求為無障礙節點提供複製、分享、刪除三項動作 | 複製與刪除如實新增，刻意排除「分享（Share）」動作 | **完全成立** | 靜讀全域無網路權限，且對話介面中原本就無任何「分享」功能。在無障礙樹中單獨憑空捏造一個不存在的業務動作屬於典型的 Scope Creep（範疇蔓延），不予加入完全正確。 |
| **MED-11** | 建議對帶有媒體 URI 的過期 PENDING 列直接寫入 `URI_EXPIRED` | 一律寫入 `FAILED`，理由是清理當下並未實際觀測到失敗原因 | **完全成立** | 滯留在 PENDING 的原因可能是 Process Death、維護中斷、佇列溢位或權限遭拒，清理當下並未發起網路或 ContentProvider 查詢。標記為 `FAILED` 誠實反映了「已知未完成但未知其因」，符合專案對資料品質標籤的嚴謹要求。 |

---

## 十大審查維度詳解

### 1. 媒體修復之正確性（Correctness of the media fix）
- **Deferred-plus-timeout 是否確實釋放呼叫端**：
  在 [`MediaCopier.kt:120`](file:///Users/iml1s/Documents/mine/quietinbox/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L120) 與 [`MediaRead.kt:67-82`](file:///Users/iml1s/Documents/mine/quietinbox/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaRead.kt#L67-L82) 中，`readWithTimeout` 於獨立的 `readScope` 中啟動 `async`，並以 `withTimeoutOrNull(timeoutMs) { read.await() }` 等候。
  `await()` 是標準的協程暫停點（Suspension Point）。當 10 秒逾時觸發時，`withTimeoutOrNull` 取消區塊並回傳 `null`，進而呼叫 `read.cancel()`。呼叫端立刻收到 `null` 並回傳 `MediaState.FAILED`，完全脫離等候。
- **是否仍有物件持有 `VaultMaintenance` 工作槽**：
  呼叫端脫離後，`copyPending` 內的 `supervisorScope` 順利結束，`maintenance.work { ... }` 正常退出並在 `finally` 區塊中解除註冊。因此不會殘留任何 Job 於 `VaultMaintenance.workers` 內。
- **孤立執行緒是否有界（Bounded）**：
  `readScope` 定義為 `CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(READ_PARALLELISM))`，其中 `READ_PARALLELISM = 2`。
  即便 ContentProvider 底層在 Binder/Pipe 發生不可中斷的死鎖，最多只會佔用 `Dispatchers.IO` 中的 2 條執行緒。後續請求在 `limitedParallelism` 佇列中排隊時，因外部 `withTimeoutOrNull` 在 10 秒後取消該 Deferred，在未取得執行緒前即被丟棄，不會無限制堆積執行緒。
- **`CancellationException` 是否被吞噬**：
  - `MediaRead.kt:76`：明確 `catch (e: CancellationException) { read.cancel(); throw e }` 重新拋出。
  - `MediaCopier.kt:102`：明確 `catch (e: CancellationException) { throw e }` 重新拋出。
  - `readMedia` 為一般同步函式，捕獲 `InterruptedException` 並重新拋出。
  全路徑無吞噬情況。
- **`readScope` 是否洩漏**：
  `MediaCopier` 為 Hilt `@Singleton`，其生命週期與 Application Process 相同。在行程存活期間維持該 Scope 是必須且合法的，無記憶體洩漏風險。

### 2. 保留清理邏輯（The retention sweep）
- **目錄與資料庫讀取順序**：
  [`RetentionWorker.kt:90-93`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt#L90-L93) 的順序為：
  1. 先列出檔案系統：`val onDisk = mediaDir.namesWithAge()`
  2. 再向資料庫查詢存活列：`val live = db.mediaDao().allFileNames().toHashSet()`
  3. 雙重防護過濾：`onDisk.filter { (name, modified) -> name !in live && modified < strayCutoff }`
  這意味著：如果在列舉目錄後、查詢資料庫前，剛好有一筆媒體寫入並 Commit，它會出現在 `live` 中因而被保留；若檔案剛寫入尚未 Commit，其 `modified` 為最新時間，不滿足 `< strayCutoff`（1 小時前），同樣受到寬限期保護。
- **`BlobCipher` 產生的 `.tmp` 暫存檔處置**：
  `BlobCipher.encryptToFile` 寫入時暫存檔名為 `<name>.tmp`。若寫入中途當機，該檔案遺留在磁碟中。由於 `media_blob` 資料表僅記錄 UUID 與縮圖 UUID，不帶 `.tmp`，因此 `name !in live` 必定為 true。當該 `.tmp` 檔案修改時間超過 1 小時寬限期後， sweep 會如實呼叫 `mediaDir.delete(name)` 將其清除，不留殘留物。

### 3. 媒體複製上限控制（The media-copy bound）
- 如 **Important-1** 所述，`CaptureCoordinator` 將並行複製上限設為 32（`MAX_QUEUED_MEDIA_COPIES`）。超出上限時記錄 `MEDIA_QUEUE_OVERFLOW` 診斷，有效避免了協程爆炸與記憶體耗盡。
- 但將該批未複製的訊息列滯留在 `PENDING` 狀態交由 1 小時後的 Sweep 收斂，在 UI 上造成了一小時的虛假沙漏顯示，與「缺口誠實呈現、絕不隱瞞」之準則相悖，應立即在溢位處改標 `FAILED`。

### 4. 誠實標籤（Honest labels）
檢視所有新增與修改的 UI 字串：
- `health_last_event`：標記為「最後接收的事件」（last accepted event），如實反映進入佇列的時間點，不假稱系統回調。
- `health_last_saved`：標記為「最後存入金庫」（last copy saved），精確標示 SQLite commit 完成的時間點。
- `health_gaps_caveat`：清楚說明「中斷區間是確定自己錯過的時段，而不是漏掉的訊息則數」，不對未觀測數據作任何統計推論。
- `search_results_shown`：當有 cursor 剩餘時顯示「顯示最新的 %d 筆，可能還有更多」，不將首頁數量宣稱為搜尋總數。
- `conv_open_source_fallback`：移除了原本武斷宣稱「原始通知已不再有效」的錯誤前提，誠實說明「App 將開啟於起始畫面」。
- `conv_media_vault_full`：在達到 512 MB 上限時顯示「媒體金庫已滿」，不再誤導為單一媒體「檔案過大」。
- 繁中 `analytics_tab_quiet`：從帶有主觀揣測意味的「神隱率」正名為客觀觀測的「安靜天數」，與日韓文目錄同步。

### 5. Compose 正確性（Compose correctness）
- **`rememberSaveable` 的 `landed` 旗標**：
  [`ConversationScreen.kt:134`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L134) 使用 `rememberSaveable(conversationId, messageId) { mutableStateOf(false) }`。在螢幕旋轉或 Process Death 重建時，`landed` 的 true 值能從 Bundle 恢復，且 `rememberLazyListState` 自帶的捲動位置恢復機制得以生效，不會在每次重組時被 `LaunchedEffect` 強制覆蓋至最新訊息。
- **清單短於視口時的 `atEnd` 檢查**：
  `val atEnd = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= lastIndex - 1 } ?: true`。當訊息較少、全域可見時，最後一項之索引必大於等於 `lastIndex - 1`，`atEnd` 恆為 true，新訊息進入能順暢捲動到底部；而向上捲動時則能精準鎖定當前閱讀位置。
- **語意樹合併與 `SelectionContainer`**：
  在外層 Column 使用 `semantics(mergeDescendants = true)` 成功將群組發送者姓名與氣泡內容合併為單一無障礙節點，徹底解決 TalkBack 節點斷裂問題；Compose 的 `mergeDescendants` 僅影響 Accessibility 節點合併，不影響子樹中 `SelectionContainer` 的 Pointer Input 手勢攔截，兩者並存運作正常。

### 6. 搜尋分頁機制（Search paging）
- **`loadMore` 的世代保護（Generation guard）**：
  在異步分頁查詢期間，若使用者變更了搜尋關鍵字、日期範圍或套件過濾條件，回調時透過比對快照狀態，主動放棄非當前查詢的分頁結果，杜絕競爭條件導致的結果污染。
- **Cursor 與 Hits 的精準區分**：
  非 null 的 Cursor 僅代表底層 SQLite 尚有候選資料，不保證記憶體驗證必定通過。當某次分頁取出的候選無一通過驗證時，程式碼主動將 `next` 設為 null，避免在介面上殘留無法產生結果的「載入更多」按鈕。
- **標頭計數真實性**：
  僅在 `next == null`（索引完全耗盡）時才顯示「%d 筆結果」；尚有資料時嚴格限制為「顯示最新的 %d 筆」，絕無虛假總數。

### 7. 測試套件鑑別力檢驗（Tests validation）
- **`MediaReadTest` (10 個測試)**：
  - 4 項測試在舊程式碼上必定失敗（實質攔截 Regression）：Provider 消失回傳 null 判定為失效（舊版判定為過大）、空 Payload 判定為失效（舊版會寫入空本機檔）、Provider 死鎖時放棄等候（舊版永遠掛起）、死鎖時 joiner 不受阻（舊版卡死維護任務）。
  - 6 項測試為標準邊界與取消傳遞驗證。
- **`OnboardingViewModelTest` (5 個測試)**：
  - 全部 5 項測試針對舊行為均具備失敗鑑別力：舊版以金庫全域總數為準（本版測試驗證初始不通過）、舊版 1 則即算成功（本版測試驗證未達 3 則不算成功）、舊版無逾時會無限轉圈（本版驗證 20 秒逾時失敗）。
- **`SearchViewModelTest` (現為 4 個測試)**：
  - 新增的 2 個測試（滿頁保留 cursor、零驗證候選終止）在舊程式碼上根本無法通過（舊版一次性拋棄 cursor，無 loadMore 機制）。
- **`MonogramTest` (+2 個測試)**：
  - `anEmojiIsNeverCutInHalf`：在舊程式碼上必定失敗（舊版 `take(1)` / `take(2)` 會截斷 UTF-16 Surrogate Pair 產生亂碼 Tofu）。
  - `rightToLeftNamesGiveTwoInitials`：在舊程式碼上可通過（阿拉伯文/希伯來文字元在 BMP 內），屬維護性正向驗證。

### 8. 文件嚴格不超前程式碼（Docs never ahead of code）
- SCOPE 文件中宣告的 720dp 自適應驗證（`rail + single pane + back arrow`）已由 `tools/demo-screenshots.sh` 搭配 `wm size` 在 AVD 上實際走過並獲 commit message 證明。
- ADR-0005 修正了復原金鑰「僅顯示一次」的不實敘述，雙語同步記載隨時可重新查看的真實行為。
- 唯一瑕疵為前述 Minor-1：`docs/SCOPE.md` 與 `docs/zh-Hant/SCOPE.md` 的測試數字遺漏同步為 4。

### 9. 雙語文件對位（Bilingual parity）
經全量比對，本輪修改的所有文件均維持 100% 雙語對位：
- `docs/ARCHITECTURE.md` ↔ `docs/zh-Hant/ARCHITECTURE.md`
- `docs/COMPATIBILITY.md` ↔ `docs/zh-Hant/COMPATIBILITY.md`
- `docs/SCOPE.md` ↔ `docs/zh-Hant/SCOPE.md`
- `docs/TEST_MATRIX.md` ↔ `docs/zh-Hant/TEST_MATRIX.md`
- `docs/adr/0005-backup-container.md` ↔ `docs/zh-Hant/adr/0005-backup-container.md`
- `fastlane/metadata/android/zh-TW/full_description.txt` 同步修正。

### 10. 額外檢驗（Anything missed）
- 權限掃描：執行 `tools/check-permissions.sh` 確認合併 Manifest 中絕對無 `android.permission.INTERNET`。
- 多語系資源校驗：執行 `python3 tools/check-strings.py`，en、zh-Hant、zh-Hans、ja、ko 五份目錄 0 errors, 0 warnings，鍵名與佔位符完美匹配。

---

## 總結
本輪提交成功解決了媒體讀取阻塞金庫維護、大尺寸視窗導覽丟失、搜尋首頁假總數及無障礙標籤缺失等多項重大架構缺陷。在修正 **Important-1**（媒體佇列溢位時即時標記失敗而非滯留 PENDING）與 **Minor-1**（SCOPE 文件測試數量對齊）後，即可安心推入主線。
