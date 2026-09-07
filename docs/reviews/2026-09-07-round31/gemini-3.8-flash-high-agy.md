# QuietInbox 第 31 輪程式碼審查報告（Round 31 — Round 30 fixes re-review）

- **審查對象**：[`/Users/iml1s/Documents/mine/quietinbox`](file:///Users/iml1s/Documents/mine/quietinbox)
- **審查區間**：`git -C /Users/iml1s/Documents/mine/quietinbox diff b373146..fe0a5b0`（單一修正 commit `fe0a5b0`）
- **參考基準**：`/private/tmp/claude-501/-Users-iml1s-Documents-mine-quietinbox/e93272a8-1a1d-4635-87a8-e9677fec237e/scratchpad/round31-brief-safe.md`、`docs/reviews/2026-09-07-round30/`、`CLAUDE.md`
- **審查模式**：唯讀審查（READ-ONLY），未修改倉庫專案原始碼，未啟用任何編排工作流模式。

---

## 本輪實際跑過的驗證

| 驗證項目 | 執行命令 / 檢查方式 | 結果 |
| :--- | :--- | :--- |
| **JVM 單元測試全量重跑** | `./gradlew test --rerun-tasks --console=plain` | **BUILD SUCCESSFUL**，全模組共 **232 tests / 0 failures / 0 errors / 0 skipped**（379 actionable tasks 全部重新執行） |
| **多語系字串資源檢驗** | `python3 tools/check-strings.py` | **OK: 0 error(s), 0 warning(s)**（en, zh-Hant, zh-Hans, ja, ko 五目錄完全對齊，包含新字串 `search_no_results_yet` 與修改後的 `analytics_tab_quiet`） |
| **Git 倉庫狀態** | `git status --short` | 工作目錄乾淨，無未追蹤或未提交之程式碼改動 |

---

## Verdict：**APPROVE WITH MINOR FIXES**

**0 Critical · 0 Important · 1 Minor · 1 Observation**

第 30 輪審查中所提出的 **1 項 Critical**（搜尋頁面永久轉圈）、**3 項 Important**（C1 空首頁存活游標被誤判為找不到、I2 存檔戳記守衛漏掉修訂與歧義重複、I6 純媒體選取時工具列複製按鈕無效）以及 **Minor M1/M10**（日語與韓語安靜率標籤）均已在 `fe0a5b0` 中獲得實質且徹底的關閉；新增的實機 Compose 語意測試 `MessageBubbleSemanticsTest` 具備完整的正反向鑑別力；且雙語說明文件均未超前程式碼。

唯一一項 Minor 發現為雙語審查索引表格（`docs/reviews/README.md` 與 `docs/zh-Hant/reviews/README.md`）第 30 輪修正 Commit 誤記為已被替換的孤兒 Commit `088cc11`，應修正為 `fe0a5b0`。

---

## 逐項核實清單（Detailed Verification of Each Item）

### 1. The spinner Critical（搜尋轉圈無限持續缺陷）
- **程式碼位置**：
  - [`feature/search/.../SearchViewModel.kt:71-74`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt#L71-L74)（`init` 管線）
  - [`feature/search/.../SearchViewModel.kt:140`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt#L140)（`run()` 守衛）
  - [`feature/search/.../SearchViewModel.kt:97`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt#L97)（`loadMore()` 守衛）
- **覆核結果**：**確實修復（CLOSED）**。
- **機制剖析與驗證**：
  - **根本原因**：在第 30 輪的 `b373146` 中，`run()` 加入了 `if (it.generation != s.generation) return@update it`。然而 `SearchViewModel.init` 的流管線使用 `distinctUntilChanged` 比較 `(query, range, packages)` 三個屬性；但使用者的每一次擊鍵、範圍切換或來源標籤點擊都會使 `generation` 遞增。當使用者輸入一個字元後在 250ms 防抖時間內刪除（A -> B -> A），或是快速開啟又關閉某一來源標籤時，`generation` 改變但三欄位未變，管線認定無變更而不重新觸發 `run()`，但先前飛行中的 `run()` 回傳時卻因 `it.generation != s.generation` 將有效結果拋棄，導致 `searching` 永遠停留在 `true`，介面無限轉圈。
  - **修復驗證**：
    `fe0a5b0` 將 `run()` 內部的結果過濾改為：
    ```kotlin
    if (it.query != s.query || it.range != s.range || it.packages != s.packages) return@update it
    ```
    此比對條件與 `init` 管線的 `distinctUntilChanged` 嚴格對齊，使得「丟棄結果的條件不再嚴於管線重跑的條件」：
    1. **打字與過濾快速回彈（A -> B -> A）**：管線因 `distinctUntilChanged` 不會重複派發，但飛行中的 `run()` 回傳時發現 `it.query == s.query` 等條件完全相符，結果被正常寫入，`searching` 被正常清為 `false`，無孤兒轉圈。
    2. **實質變更（A -> B）**：飛行中的 A 結果回傳時因 `it.query != s.query` 被安全丟棄；同時管線感知到 B 的變更，排程派發 `run(B)`，完成後將 `searching` 設為 `false`。陳舊資料（stale page）依舊被嚴格丟棄。
    3. **清空搜尋框**：`setQuery("")` 同步設置 `searching = q.isNotBlank()`（即 `false`），且舊查詢結果回傳時因查詢不符而丟棄，狀態始終一致。
  - **`loadMore()` 世代防護**：
    `loadMore()`（行 97）繼續保留 `if (cur.generation != s.generation) cur.copy(loadingMore = false)`。這是正確的：分頁是針對當前快照的後續追加，使用者一旦變更了查詢條件，該次追加即屬過期，丟棄追加並重置 `loadingMore = false` 符合預期且不會卡住按鈕。

---

### 2. The rest of C1（C1 剩餘缺陷：空首頁與存活游標）
- **程式碼位置**：
  - [`feature/search/.../SearchScreen.kt:140`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchScreen.kt#L140)
  - [`feature/search/.../SearchScreen.kt:150-154`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchScreen.kt#L150-L154)
  - [`feature/search/.../SearchScreen.kt:164-174`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchScreen.kt#L164-L174)
- **覆核結果**：**確實修復（CLOSED）**。
- **機制剖析與驗證**：
  - **前次回歸**：在先前修正中，雖然狀態層已允許空頁攜帶存活游標（代表候選掃描預算用盡但索引未窮盡），但 `SearchScreen.kt` 的 `when` 分支卻以 `state.results.isEmpty()` 先行命中空狀態，直接宣告「找不到」，並將位於 `else -> LazyColumn` 內部的「載入更多」控制元件徹底遮蔽，使得留存的游標完全無路可走。
  - **修復驗證**：
    1. 行 140 將空狀態條件收緊為：
       ```kotlin
       state.results.isEmpty() && state.next == null -> EmptyState(...)
       ```
       唯有在索引「確實窮盡」且無結果時，才會宣告「找不到」。
    2. 當 `state.results.isEmpty() && state.next != null` 時，順利落入 `else -> LazyColumn` 分支：
       - 標頭文本命中 `state.results.isEmpty()` 分支，顯示誠實的 `search_no_results_yet`（「在目前已搜尋過的索引範圍內沒有符合的內容。」/ "Nothing matched in the part of the index searched so far."），不作「找不到」的虛假宣告。
       - 行 164 的 `if (state.next != null)` 順利渲染「載入更多」按鈕（`search_load_more`）或 `LoadingIndicator`，使用者可主動點擊繼續向後掃描。
    3. 全 5 個語系（en, zh-Hant, zh-Hans, ja, ko）之字串檔案均已補齊 `search_no_results_yet`，`tools/check-strings.py` 檢查通過。

---

### 3. I2（`CommitOutcome.revisedMessageIds` 與資料庫寫入路徑守衛）
- **程式碼位置**：
  - [`platform/storage/.../repo/IngestRepository.kt:45, 236, 336, 386`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L45)
  - [`platform/capture/.../CaptureCoordinator.kt:885-887`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L885-L887)
- **覆核結果**：**確實修復（CLOSED）**。
- **機制剖析與驗證**：
  - **修訂路徑收集精確性**：
    在 `IngestRepository.kt` 中，`revisedIds` 僅在 `for` 迴圈處理 `Decision.Revision` 且原始訊息存在（`old != null`）時透過 `revisedIds += id`（行 336）收集。此時確實向 `revisionDao` 插入了一筆修訂歷史實體並更新了 `messageDao`。若訊息已被使用者刪除（`old == null`），則不產生寫入也不收集。
  - **寫入列守衛覆蓋度**：
    `CaptureCoordinator.kt:885-887` 的條件更新為：
    ```kotlin
    if (outcome.newMessageIds.isNotEmpty() || outcome.ambiguousMessageIds.isNotEmpty() ||
        outcome.revisedMessageIds.isNotEmpty() || outcome.summaryRecorded
    ) {
        val savedAt = System.currentTimeMillis()
        _status.update { it.copy(lastCommittedAtEpochMs = maxOf(it.lastCommittedAtEpochMs ?: 0L, savedAt)) }
    }
    ```
    比對 `IngestRepository.commit` 的所有資料庫寫入邏輯：
    - `Decision.New`：寫入新訊息列，進入 `newMessageIds`。
    - `Decision.AmbiguousRepeat`：寫入真實訊息列（帶 AMBIGUOUS_REPEAT 狀態），進入 `ambiguousMessageIds`。
    - `Decision.Revision`：寫入修訂記錄列並更新訊息本文，進入 `revisedMessageIds`。
    - `batch.summary != null`：寫入健康摘要列，標記 `summaryRecorded = true`。
    - 其餘決策（如 `Known` 或過期視窗命中）僅產生關聯記帳（observation link）或更新視窗 checkpoint，不寫入任何訊息/本文實體。
    因此，擴充後的守衛完整覆蓋了所有真正寫入或更新資料列的路徑，消除了「剛儲存了修訂或歧義訊息卻仍顯示從未儲存」的誠實性漏洞。
  - **預設參數相容性**：
    `CommitOutcome` 新增欄位給予了預設值 `val revisedMessageIds: List<Long> = emptyList()`，所有既有調用端（包含 `CaptureCoordinatorTest.kt:66` 等測試以及 `IngestRepository.kt:188` 前置早退）均保持二進位與源碼層級的完全相容。

---

### 4. I6（純媒體選取時工具列複製按鈕禁用）
- **程式碼位置**：[`feature/conversation/.../ConversationScreen.kt:239-246`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L239-L246)
- **覆核結果**：**確實修復（CLOSED）**。
- **機制剖析與驗證**：
  - 在先前修正中，「無文字訊息不提供複製」僅在 Accessibility 自訂動作（行 440）中移除，但上方選取工具列的 Copy 按鈕仍對純媒體訊息響應點擊，造成點擊後靜默早退無反應。
  - 在 `fe0a5b0` 中，工具列按鈕直接將 `enabled` 綁定於過濾後的文字：
    ```kotlin
    val selectedText = state.messages.filter { it.id in state.selection }
        .map { it.body }.filter { it.isNotBlank() }.joinToString("\n\n")
    IconButton(
        onClick = { copyToClipboard(context, selectedText) },
        enabled = selectedText.isNotBlank(),
    ) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy)) }
    ```
  - 當選取內容全為純媒體/空白訊息時，`selectedText.isNotBlank()` 為 `false`，按鈕呈現視覺禁用態且不可點擊；當選取包含至少一則有文字之訊息時正常啟用。

---

### 5. M1/M10（日語與韓語安靜率標籤）
- **程式碼位置**：
  - [`core/designsystem/.../values-ja/strings_analytics.xml:12`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-ja/strings_analytics.xml#L12)
  - [`core/designsystem/.../values-ko/strings_analytics.xml:12`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-ko/strings_analytics.xml#L12)
- **覆核結果**：**確實修復（CLOSED）**。
- **機制剖析與驗證**：
  - 日語 `analytics_tab_quiet` 由原本代表天數的 `静かな日` 更正為代表比例的 `静かな日の割合`。
  - 韓語 `analytics_tab_quiet` 由原本代表天數的 `조용한 날` 更正為代表比例的 `조용한 날 비율`。
  - 修正後與卡片下方百分比數值（「80%」）的量綱完全吻合，並與 en（"Quiet rate"）、zh-Hant（"安靜率"）、zh-Hans（"安静率"）語義統一。

---

### 6. `MessageBubbleSemanticsTest`（實機 Compose 語意測試評估）
- **程式碼位置**：
  - [`feature/conversation/src/androidTest/.../MessageBubbleSemanticsTest.kt`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/androidTest/kotlin/dev/quietinbox/feature/conversation/MessageBubbleSemanticsTest.kt)
  - [`feature/conversation/.../ConversationScreen.kt:379`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L379)
- **詳細評估**：
  1. **測試是否確實證明了合併（Does it actually prove the merge?）**：
     - **是**。在 Compose 語意架構中，氣泡容器的 `.combinedClickable` 隱含設置了 `mergeDescendants = true`。當發送者 `Text` 與本文 `Text` 均作為其直接子節點時，Compose 會將子節點的文本語意聚合至該父節點。
     - 測試案例 `theSenderAndTheBodyAreOneNodeInTheMergedTree` 在預設合併樹（`useUnmergedTree = false`）中斷言 `hasText(SENDER) and hasText(BODY)` 的節點數量恰好為 1，直接且嚴謹地證明了螢幕閱讀器將其視為單一語意停駐點。
  2. **在修復前佈局中是否會失敗（Would it fail on the pre-fix layout?）**：
     - **必然失敗**。修復前的發送者姓名繪製在可點擊氣泡容器外部。由於 Compose 語意規則明確禁止外層節點吸收內部已存在的合併節點（a merging node is never absorbed by an outer one），因此發送者姓名與氣泡內容必定分散在兩個各自獨立的節點中。在修復前佈局下，沒有任何節點會同時匹配 `hasText(SENDER) and hasText(BODY)`，斷言結果為 0，測試必定失敗。
  3. **負向控制（Negative Control）是否具備實質意義（Is the negative control meaningful?）**：
     - **具備實質意義**。案例 `theyAreStillDrawnAsSeparateTextsUnderneath` 在未合併樹（`useUnmergedTree = true`）中斷言同時包含兩段文本的節點數量為 0。若有人採用將發送者與本文拼湊為單一 Composable 的取巧作法（如 `Text("$sender $body")`），此負向測試將立即報錯。因此正向與負向測試相結合，精確證明了「排版渲染保持獨立元素，無障礙層級實現自動合併」。
  4. **將 `MessageBubble` 改為 `internal` 是否為可接受切面（Is internal acceptable?）**：
     - **完全可接受且為最佳解**。在模組化設計中，`internal` 使得同屬 `:feature:conversation` 模組的 `androidTest` 能直接調用純展示型 Composable，同時對 `:app` 等外部模組完全隱藏。若強行透過整頁 `ConversationScreen` 測試，將不得不引入龐大的 `ConversationViewModel`、Flow 與資料庫 Mock 依賴，大幅增加無障礙測試的偶合度與脆弱性。

---

### 7. Documentation Parity & Review Index Rows（文件對齊與審查索引列）

#### 雙語文件與程式碼對齊檢查
- `CHANGELOG.md`：準確記錄了第 30 輪修正之細節，包含搜尋游標透傳與候選預算說明、群組訊息語意合併測試說明、日韓語系安靜率標籤等。
- `docs/TEST_MATRIX.md:20` 與 `docs/zh-Hant/TEST_MATRIX.md:20`：雙語同步登錄了 `MessageBubbleSemanticsTest` 項目，明確指出包含 2 項測試（合併樹單一節點 + 未合併樹反向控制），且註明需使用 `ANDROID_SERIAL=<emulator>` 執行。

#### 審查索引列檢查（Minor Finding）
- **程式碼位置**：
  - [`docs/reviews/README.md:46`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L46)
  - [`docs/zh-Hant/reviews/README.md:44`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L44)
- **問題說明**：
  - 雙語審查索引表格第 30 輪末尾記載的修正 Commit Hash 為：
    `` `088cc11` ``
  - 經 Git 歷程查詢，`088cc11` 為提交過程中被 amend 替換的孤兒 Commit（不存在於 main 分支上）。
  - 目前 main 分支上真正包含第 30 輪所有修正的單一 Commit 為：
    `` `fe0a5b0` ``（`fe0a5b06498479f93d8694902fb4ad19a9556a56`）。
- **建議修正**：此為非阻擋性之文檔索引筆誤，建議在下次提交時將雙語索引表格中的 `088cc11` 更正為 `fe0a5b0`。

---

## 發現匯總（Findings Summary）

- **Critical**: 0
- **Important**: 0
- **Minor**: 1
  - [`docs/reviews/README.md:46`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L46) 及 [`docs/zh-Hant/reviews/README.md:44`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L44)：審查索引表第 30 輪的修正 Commit 誤記為 `088cc11`，應修正為實際存在於 main 分支上的 `fe0a5b0`。
- **Observations**: 1
  - `MessageBubbleSemanticsTest` 是透過 `connectedDebugAndroidTest` 執行的實機測試。正如文檔與 commit message 所述，因 Gradle 行為會自動跑在所有連線裝置上，若本機掛載多台設備，需透過 `ANDROID_SERIAL` 鎖定單一設備。在純 JVM 單元測試環境下，全部 232 項單元測試均可獨立高速運行並完全通過。
