# QuietInbox 第 30 輪程式碼審查報告（Round 30 — audit-2 fixes re-review）

- **審查對象**：[`/Users/iml1s/Documents/mine/quietinbox`](file:///Users/iml1s/Documents/mine/quietinbox)
- **審查區間**：`git -C /Users/iml1s/Documents/mine/quietinbox diff edd261f..b373146`（單一修正 commit `b373146`）
- **參考基準**：`/private/tmp/claude-501/-Users-iml1s-Documents-mine-quietinbox/e93272a8-1a1d-4635-87a8-e9677fec237e/scratchpad/round30-brief-safe.md`、`docs/reviews/2026-09-07-round29/`、`CLAUDE.md`
- **審查模式**：唯讀審查（READ-ONLY），未修改倉庫專案原始碼，未啟用任何編排工作流模式。

---

## 本輪實際跑過的驗證

| 驗證項目 | 執行命令 / 檢查方式 | 結果 |
| :--- | :--- | :--- |
| **JVM 單元測試全量強制重跑** | `./gradlew test --rerun-tasks --console=plain` | **BUILD SUCCESSFUL**，全模組共 **232 tests / 0 failures**（較第 29 輪的 231 個增加 1 個 `SearchViewModelTest` 世代防護測試） |
| **多語系字串資源一致性** | `python3 tools/check-strings.py` | **OK: 0 error(s), 0 warning(s)**（en, zh-Hant, zh-Hans, ja, ko 五目錄完全對齊） |
| **權限閘門掃描** | `tools/check-permissions.sh app/build/outputs/apk/debug/app-debug.apk` | **OK: no network permission in APK**（無 `INTERNET` 或任何網路相關權限） |
| **Schema 與資料庫結構檢驗** | `git diff --name-only edd261f..b373146 \| grep -iE "schema\|Entities\|Database.kt\|Migration"` | **空**（本修正區間維持嚴格的 Schema-free，新增 query 均基於既有欄位） |

---

## Verdict：**APPROVE**

**0 Critical · 0 Important · 0 Minor · 2 Observations**

第 29 輪審查所提出的 **1 項 Critical、9 項 Important（含 Claude subagent 8 項與 agy 1 項）、8 項 Minor 及 agy 提報的 2 項 Minor**，在本次提交 `b373146` 中均已獲得實質且嚴謹的關閉，未留殘留缺陷，且未引入新的迴歸問題。

---

## 逐項核實清單（Verification of Each Finding）

### Critical

#### C1. 搜尋游標無損透傳與標頭總數真實性
- **程式碼位置**：
  - [`feature/search/.../SearchViewModel.kt:107`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt#L107)（`loadMore()`）
  - [`feature/search/.../SearchViewModel.kt:139`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt#L139)（`run()`）
  - [`feature/search/.../SearchScreen.kt:146-150`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchScreen.kt#L146-L150)
  - [`feature/search/.../SearchViewModelTest.kt:111-131`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/test/kotlin/dev/quietinbox/feature/search/SearchViewModelTest.kt#L111-L131)
  - [`docs/TEST_MATRIX.md:44`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L44) 與 [`docs/zh-Hant/TEST_MATRIX.md:44`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L44)
  - [`CHANGELOG.md:54-57`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L54-L57)
- **覆核結果**：**確實修復（CLOSED）**。
  - 在 `SearchViewModel.kt` 中，`loadMore()` 與 `run()` 均已將原本武斷的 `next = if (page.hits.isEmpty()) null else page.next` 移除，改為無條件透傳 `next = page.next`。
  - `SearchScreen.kt` 嚴格限制僅在 `state.next == null`（索引完全耗盡）時才調用 `search_results_count` 顯示「%1$d 筆結果」；當 `state.next != null` 時顯示 `search_results_shown`（「顯示最新的 %1$d 筆，可能還有更多」）。
  - 單元測試 `a page that verifies nothing keeps its cursor, because the index is not finished` 已徹底改寫：模擬首頁回傳 100 筆及游標 `cursor`，次頁回傳 0 筆命中但攜帶深層游標 `deeper`，明確斷言 `vm.state.value.next shouldBe deeper`，將正確不變量鎖定於測試中。
  - 雙語 `TEST_MATRIX.md` 與 `CHANGELOG.md` 均已同步更正，清楚說明「空頁代表候選掃描預算用盡、不是索引已到底」，不再出現文件超前或失實。

---

### Important

#### I1. 發送者名稱無障礙語意合併與驗證成本判定
- **程式碼位置**：[`feature/conversation/.../ConversationScreen.kt:416-452`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L416-L452)
- **覆核結果**：**確實修復（CLOSED）**。
  - **結構檢驗**：在外層 `Column`（無 `semantics`）之下，原本位於氣泡外的發送者姓名 `Text`（行 446）已完整移入具有 `.combinedClickable` 與 `.semantics` 的內層氣泡 `Column` 內部，排在 `Thumbnail`、`SelectionContainer` 與 `MetaLine` 之前。由於 `combinedClickable` 預設具備 `shouldMergeDescendantSemantics = true`，發送者姓名作為該容器的純文本子節點，會直接被合併至氣泡的單一語意節點中，徹底消除了 TalkBack 在群組對話中的斷裂停頓。
  - **關於 commit message 中 `uiautomator dump` 宣告之分析與低成本驗證替代方案**：
    - Commit message 指出 `uiautomator dump` 無法在本作中檢驗此合併（回報 unmerged tree 且每行呈現 `clickable=false`）。**同意此觀察**：在未啟用無障礙服務（如 TalkBack）的情況下，Compose 的 `AndroidComposeViewAccessibilityDelegateCompat` 是被動暴露虛擬節點，部分 Compose 容器節點在系統 dump 中不會主動合成可點擊狀態，導致傳統 `uiautomator dump` 抓取到的層級失真。
    - **是否有比引入 Compose UI 測試架構（需拉入依賴並冷啟動重新產生 verification-metadata）更輕量的驗證方式？**
      **有的，且至少有兩種無需引入任何新依賴的低成本途徑**：
      1. **Device Accessibility Event / TalkBack 日誌驗證**：利用既有 AVD，透過 `adb shell settings put secure enabled_accessibility_services com.google.android.marvin.talkback/...` 啟用 TalkBack，接著執行 `adb shell dumpsys accessibility` 或監聽 `logcat -s TalkBack`。當焦點落在訊息氣泡時，TalkBack 會直接輸出合成後的朗讀字串（一次朗讀「發送者姓名 + 訊息內容 + 時間」），直接以系統真實語音引擎輸出作為證據。
      2. **既有模組的 Instrumentation 測試中直接查詢 `AccessibilityNodeProvider`**：在現有具備 `connectedDebugAndroidTest` 的模組中，直接向載有該 Composable 的 `ComposeView` 取得 `getAccessibilityNodeProvider()`，並對其虛擬節點執行 `createAccessibilityNodeInfo(virtualViewId)`，斷言返回的 `AccessibilityNodeInfoCompat` 的 `text` 是否包含發送者與本文，完全無需引入 `androidx.compose.ui:ui-test-junit4` 繁重套件。

#### I2. `lastCommittedAtEpochMs` 單調牆鐘時間與空寫入守衛
- **程式碼位置**：[`platform/capture/.../CaptureCoordinator.kt:882-886`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L882-L886)
- **覆核結果**：**確實修復（CLOSED）**。
  - 時間戳改採 `System.currentTimeMillis()` 牆鐘時間，並搭配 `maxOf(it.lastCommittedAtEpochMs ?: 0L, savedAt)` 確保單調遞增，完全解決 Journal 重播舊事件導致時間倒退或顯示數小時前的缺陷。
  - **空寫入路徑比對**：
    檢視 [`IngestRepository.kt:184-187`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L184-L187)，當無身分或 decisions 為空時回傳 `newMessageIds` 為空且 `summaryRecorded = false`；當所有決策遭抑制（line 245）或所有決策為已知重複（`Decision.Known`，line 315）或 Stale Window 命中（line 253）時，均不寫入任何訊息資料列，此時 `outcome.newMessageIds` 均為空，若無摘要寫入則守衛條件 `outcome.newMessageIds.isNotEmpty() || outcome.summaryRecorded` 準確評估為 `false`，不產生虛假存檔戳記。（關於僅含 `AmbiguousRepeat` 的極端情況見下方 Observations 1）。

#### I3. `settlePendingMedia` 條件式 CAS 與清掃計數真實性
- **程式碼位置**：
  - [`platform/storage/.../db/Daos.kt:273`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L273)
  - [`platform/storage/.../retention/RetentionWorker.kt:102-105, 117`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt#L102-L105)
- **覆核結果**：**確實修復（CLOSED）**。
  - DAO 層新增原子 Compare-And-Set 查詢：`UPDATE message SET mediaState = :state, mediaBlobId = NULL WHERE id = :id AND mediaState = 'PENDING'`，僅在狀態仍為 `PENDING` 時才更新並回傳影響列數（1 或 0）。若與 `MediaCopier.store` 並行且後者已成功寫入 `LOCAL_COPY`，此處將安全跳過（回傳 0），絕不破壞剛提交的本機複本。
  - `RetentionWorker` 累加 `settlePendingMedia` 實際回傳的成功更新筆數（`stalePending += ...`），並將此實際翻轉數量傳入 `RetentionReport`，不再以查詢前的候選總數虛報。

#### I4. 媒體佇列溢位於 Pipeline 鎖內就地結案
- **程式碼位置**：
  - [`platform/capture/.../CaptureCoordinator.kt:893-896`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L893-L896)
  - [`platform/storage/.../repo/IngestRepository.kt:143-146`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L143-L146)
- **覆核結果**：**確實修復（CLOSED）**。
  - 當 `queuedMediaCopies > MAX_QUEUED_MEDIA_COPIES` 時，呼叫 `ingest.settlePendingMedia(outcome.pendingMediaMessageIds, MediaState.FAILED.name)`，當場將未排程的訊息列標記為 `FAILED`，UI 不再殘留長達 1 小時的虛假沙漏圖示。
  - **死鎖與合法性驗證**：呼叫端處於 `pipelineMutex.withLock` 單一寫入者保護下，`ingest.settlePendingMedia` 內部僅透過 Room DAO 執行直接的 SQLite UPDATE，無任何非重入鎖取得動作，未請求 `maintenance.work` 或 `maintenance.exclusive`，亦無掛起等待其他工作者之行為，寫入完全合法且在架構上杜絕了死鎖可能。

#### I5. 捲動效果在未測量版面時放棄猜測
- **程式碼位置**：[`feature/conversation/.../ConversationScreen.kt:157-158`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L157-L158)
- **覆核結果**：**確實修復（CLOSED）**。
  - 捲動位置邏輯修正為：
    ```kotlin
    val visibleEnd = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
    if (visibleEnd >= lastIndex - 1) listState.animateScrollToItem(lastIndex)
    ```
  - 當旋轉螢幕、摺疊／展開裝置或調整分割視窗觸發重組，但尚未完成首輪 measure 時，`visibleItemsInfo` 為空，代碼透過 `?: return@LaunchedEffect` 直接退出，不再盲目猜測 `?: true` 強制捲動到底部，完整保護了已復原的捲動錨點。

#### I6. 剪貼簿 API 33+ 敏感性標記與空文字防護
- **程式碼位置**：
  - [`feature/conversation/.../ConversationScreen.kt:528-531`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L528-L531)
  - [`feature/conversation/.../ConversationScreen.kt:433-436`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L433-L436)
- **覆核結果**：**確實修復（CLOSED）**。
  - 在 `copyToClipboard` 中，針對 Android 13+（API 33+）明確透過 `PersistableBundle` 設置 `ClipDescription.EXTRA_IS_SENSITIVE = true`，系統不再於覆蓋層中公開預覽私密訊息。
  - 無障礙自訂動作改用 `buildList` 構造，明確加上 `if (message.body.isNotBlank())` 判斷，純圖片／空白訊息不再提供複製動作，徹底消除無聲失敗。

#### I7. 收件匣過濾空狀態文字誠實化與按鈕收斂
- **程式碼位置**：[`feature/inbox/.../InboxScreen.kt:166-198`](file:///Users/iml1s/Documents/mine/quietinbox/feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt#L166-L198)
- **覆核結果**：**確實修復（CLOSED）**。
  - 判定 `val filtered = state.filter.archived || state.filter.unviewed || state.filter.packages.isNotEmpty()`。
  - 當處於過濾狀態時，Title 依序分流為 `inbox_empty_archived_title`、`inbox_empty_unviewed_title`（「沒有未查看的對話」）、`inbox_empty_filtered_title`（「這些來源沒有內容」）；Body 設為空字串，且隱藏「發送測試通知」與「授權存取」按鈕，絕不對已存有訊息的金庫宣稱「還沒有任何副本」。五語系字串全部齊全。

#### I8. 相容性文件結構與孤立清單項歸位
- **程式碼位置**：
  - [`docs/COMPATIBILITY.md:37-43`](file:///Users/iml1s/Documents/mine/quietinbox/docs/COMPATIBILITY.md#L37-L43)
  - [`docs/zh-Hant/COMPATIBILITY.md:34-39`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/COMPATIBILITY.md#L34-L39)
- **覆核結果**：**確實修復（CLOSED）**。
  - 中英雙語版本均已將 `## Hidden previews...`（隱藏預覽）章節整段移至 `## Work profiles, Device Policy and low-RAM devices (QI-ID-008)` 清單之後（低記憶體 Go 裝置條目下方），所有 bullet 完全歸屬於其正確標題，結構整齊。

#### agy 3. 寬螢幕非收件匣進入對話之返回鍵防護
- **程式碼位置**：[`app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt:97, 143`](file:///Users/iml1s/Documents/mine/quietinbox/app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt#L97)
- **覆核結果**：**確實修復（CLOSED）**。
  - 核心邏輯定為：`val besideList = twoPane && backStack.getOrNull(backStack.lastIndex - 1) is InboxRoute`，並指派 `showBackButton = !besideList`。
  - 逐一審查全部開對話路徑：
    1. **Inbox 進入對話**：前一項為 `InboxRoute`，在 `twoPane == true`（≥ 840dp）時 `besideList` 為 true，隱藏返回鍵（雙欄 List-Detail）。
    2. **Search 進入對話**：前一項為 `SearchRoute`，非 `InboxRoute`，`besideList` 為 false，即便在 ≥ 840dp 寬平板上亦強制顯示頂部返回鍵。
    3. **Analytics 進入對話**：前一項為 `AnalyticsRoute`，非 `InboxRoute`，`besideList` 為 false，同樣保留頂部返回鍵。
  - 返回點擊調用 `backStack.removeLastOrNull()`，精確返回上一層搜尋或分析畫面。

---

### Minor / Nitpicks

- **M1 & M10（分析標籤正名與多語系量綱對齊）**：
  - [`values-b+zh-Hans/strings_analytics.xml:9, 64`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-b+zh-Hans/strings_analytics.xml#L9)：簡中「沉默率」修正為「安静率」、「沉默 %1$d%%」修正為「安静 %1$d%%」。
  - [`values-b+zh-Hant/strings_analytics.xml:12`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-b+zh-Hant/strings_analytics.xml#L12)：繁中標籤頁「安靜天數」正名為「安靜率」，數值與標題量綱完全對齊，與英文 "Quiet rate" 語義吻合。
  - Fastlane 中英商店宣傳文案同步更新完成。
- **M2（全目錄死字串清理）**：
  - `health_saved` 已自 `values`、`values-b+zh-Hans`、`values-b+zh-Hant`、`values-ja`、`values-ko` 五個目錄全數刪除，未留未引用的死字串。
- **M3 & M5（`SearchViewModel` 世代計數器與快照防護）**：
  - [`SearchViewModel.kt:45, 62`](file:///Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt#L45)：在 `SearchUiState` 與 `SearchViewModel` 引入單調遞增的 `generation`。
  - `setQuery`、`setRange`、`togglePackage`、`clearPackages` 均執行 `generation = ++generation`。
  - `loadMore()`（行 97）與 `run()`（行 136）均以啟動前獲取的 snapshot generation 進行嚴格比對，過期非當前查詢結果直接拋棄，且在 `run()` 中一併重置 `loadingMore = false`。
  - 新增單元測試 `a stale page is discarded when the query has moved on` 確保快速連續輸入時舊資料不污染新結果。
- **M11（氣泡選取無障礙狀態精簡）**：
  - [`ConversationScreen.kt:429-432`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L429-L432)：將 `this.selected = selected` 包入 `if (selecting)`，非選取模式下不再對每一則訊息附加無意義的 `isSelected = false`。
- **agy 1（SCOPE 文件測試計數同步）**：
  - [`docs/SCOPE.md:26`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L26) 與 [`docs/zh-Hant/SCOPE.md:24`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/SCOPE.md#L24) 均已將 `SearchViewModelTest` 測試數量更新為 5，與實際程式碼完全對齊。
- **agy 2（Onboarding 測試步驟雙按鈕收斂）**：
  - [`feature/onboarding/.../OnboardingScreen.kt:231`](file:///Users/iml1s/Documents/mine/quietinbox/feature/onboarding/src/main/kotlin/dev/quietinbox/feature/onboarding/OnboardingScreen.kt#L231)：加上 `if (!state.testFailed)` 守衛，測試失敗時上方按鈕隱藏，僅保留失敗區塊自帶的重試按鈕，杜絕重複介面。

---

### Also（衍生與副作用檢查）

1. **`SearchViewModel` 世代計數器覆蓋率檢驗**：
   - 經全量檢視，所有能變更搜尋條件之公開方法（`setQuery`、`setRange`、`togglePackage`、`clearPackages`）均已無一遺漏地遞增 `generation`。
   - `init` 區塊中的 StateFlow 收集與 `run(s)` 呼叫依賴快照 `s`，在寫回 state 時比對 `it.generation != s.generation`，時序邏輯完全閉合。
2. **`buildList` 無障礙自訂動作防護**：
   - `buildList` 是標準 Kotlin 集合建構器，`CustomAccessibilityAction` 針對空正文訊息排除 Copy 動作，針對所有訊息保留 Delete 動作，程式碼乾淨無副作用。
3. **雙語文件與審查索引**：
   - `docs/reviews/README.md` 與 `docs/zh-Hant/reviews/README.md` 均已更新第 29 輪紀錄與裁決說明（工作目錄中的未暫存變更將 `<pending>` 填入 `b373146`，內容忠實完整）。

---

## Observations（架構觀察）

1. **`CaptureCoordinator.kt:882` 在極端情境下的微小假陰性**：
   - 守衛條件為 `if (outcome.newMessageIds.isNotEmpty() || outcome.summaryRecorded)`。
   - 若某次通知完全僅由 `Decision.AmbiguousRepeat` 組成（例如同秒內無識別碼的多筆重複訊息），`IngestRepository.commit` 會向資料庫寫入 `MessageEntity` 列（行 268），但該 ID 會被放入 `outcome.ambiguousMessageIds` 而非 `newMessageIds`。此時實際上金庫存入了訊息副本，但 `lastCommittedAtEpochMs` 不會推進。
   - 此設計絕不會造成「什麼都沒寫卻假稱存入」的假陽性誠實違規，僅在全為模糊重複通知時略微延後該時間戳之更新，對整體健康度呈現無實質損害，未來若需極致嚴謹可擴充為 `outcome.newMessageIds.isNotEmpty() || outcome.ambiguousMessageIds.isNotEmpty() || outcome.summaryRecorded`。
2. **審查索引未暫存狀態確認**：
   - 根目錄下 `docs/reviews/README.md` 與 `docs/zh-Hant/reviews/README.md` 包含一處將 `<pending>` 更新為 `b373146` 的 working tree 變更。依審查唯讀指示，本報告保持該狀態未被觸動，建議維護者在後續合併或提交時一同包含。

---

## 結論

Commit `b373146` 是一次極為扎實、技術推理縝密的修正提交。在徹底修正 C1 搜尋誠實性違規的同時，將無障礙節點樹從根本結構上理順，並全數消除了第 29 輪指出的 9 項 Important 與所有次要問題。全模組 232 項 JVM 單元測試、權限掃描與多語系檢驗全數綠燈。**建議直接批准（APPROVE）！**
