# QuietInbox 獨立代碼審查報告（Round 33 — Commit `9e379d3`）

- **審查對象**：Commit [`9e379d3`](file:///Users/iml1s/Documents/mine/quietinbox)（"Schema 4: a gap can name its source, and a message can admit what was cut"）
- **審查範圍**：Schema 4 遷移（`MIGRATION_3_4`）、截斷旗標分割與持久化、來源專屬缺口（source-scoped gaps）、雙向備份相容性、協調器圍籬時序、字串與文檔對齊。
- **審查結論**：**REQUEST CHANGES**

---

## Verdict: REQUEST CHANGES

Commit [`9e379d3`](file:///Users/iml1s/Documents/mine/quietinbox) 完成了 Schema 4 的核心演進，成功將 `TruncationFlag` 進行語意切割，消除了由訊息內文截短誤觸發訊息遺失缺口的隱患，並透過追加式可空欄位實現了平滑的 Room 遷移與具備向後／向前相容性的備份格式。

然而，本輪實作存在兩處重大缺陷（Critical）：
1. **截斷旗標誤標（False-Flagging）違反誠實原則**：截斷旗標在 `IngestRepository` 中以整個 Snapshot 為單位套用至批次內的所有訊息，導致群組通話或多訊息通知中「完全未被截短」的正常訊息（甚至僅標題被截短的通知中的所有內文）在對話氣泡上被錯誤標記為「文字被截短」（`conv_truncated`）。
2. **來源移除導致缺口永久開放**：來源在暫停或停用狀態下被移除（`removeSource`）時，系統未關閉其對應的開放缺口，且該來源從設定中消失後使用者無法再次透過啟用／恢復來關閉，造成該缺口在健康介面上永久顯示為「進行中／開放（Open）」。

修復上述問題並補齊繁體中文測試矩陣描述後，方可發布 0.1.4。

---

## Critical（發布前必須修復）

### Critical-1: 截斷旗標屬於 Snapshot 層級，在 Ingest 階段無差別寫入批次內每則訊息，導致未被截短的訊息被虛假標記
- **檔案與行號**：
  - [`IngestRepository.kt:314-317`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L314-L317)
  - [`ConversationScreen.kt:504`](file:///Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/main/kotlin/dev/quietinbox/feature/conversation/ConversationScreen.kt#L504)
  - [`Labels.kt:105-108`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Labels.kt#L105-L108)
- **具體觸發序列**：
  1. **情境 A（多訊息通知內文截短）**：一個 `MessagingStyle` 通知包含 3 則訊息。第 1 則訊息長度達 5,000 字元，被 `BoundedText.of()` 截短並觸發 `snapshot.shape.truncated += TruncationFlag.MESSAGES`；第 2 則與第 3 則訊息分別為短句（如「好的」、「收到」），內文未被截短。
  2. **情境 B（通知標題截短）**：群組名稱或發送者名稱超長觸發 `TruncationFlag.TITLE`，但通知內的訊息內文完全未被截短。`TEXT_TRUNCATION` 定義中包含了 `TruncationFlag.TITLE`。
- **可觀察之錯誤行為**：
  在 [`IngestRepository.kt:314-317`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L314-L317) 的寫入迴圈中：
  ```kotlin
  // Only the flags that describe this message's own text. The
  // dropped-message flags belong to the batch, not to a row that
  // survived, and they are recorded as a gap instead.
  truncationFlags = snapshot.shape.truncated
      .filter { it in TEXT_TRUNCATION }
      .takeIf { it.isNotEmpty() }
      ?.joinToString(",") { it.name },
  ```
  程式碼註解聲稱「Only the flags that describe this message's own text」，但 `snapshot.shape.truncated` 實際上是整個 Snapshot 的集合，並非單則訊息的屬性。因此，批次中每一則未被截短的短訊息（情境 A），以及所有標題被截短但內文完整的訊息（情境 B），其 `MessageEntity.truncationFlags` 都被填入了 `"MESSAGES"` 或 `"TITLE"`。
  在對話介面中，[`truncationLabel`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Labels.kt#L105) 只要集合非空即呈現剪刀圖示與「文字被截短」（`conv_truncated`）。這導致未被截短的完整訊息被公開指稱為被截短，直接違背專案最核心的誠實原則（Honesty Rule）。
- **修復建議**：
  在 [`MessageCandidate`](file:///Users/iml1s/Documents/mine/quietinbox/core/model/src/main/kotlin/dev/quietinbox/core/model/ParsedBatch.kt#L120) 中增加單則訊息專屬的截斷標記（例如 `val isTruncated: Boolean = false` 或攜帶專屬 flags），由 Parser / Factory 依據 `BoundedText.truncated` 單獨賦值，`IngestRepository` 僅將真正自身文字受損的訊息標記為截短，且不應將純標題截短（`TITLE`）直接歸因為單則訊息內文截短。

---

### Critical-2: 來源在暫停或停用狀態下被移除（`removeSource`），未關閉其開放缺口，導致永久「進行中（Open）」缺口殘留
- **檔案與行號**：
  - [`CaptureCoordinator.kt:638-639`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L638-L639)
  - [`SourceRepository.kt:64-80`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SourceRepository.kt#L64-L80)
  - [`HealthScreen.kt:272`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt#L272)
- **具體觸發序列**：
  1. 使用者暫停或停用了來源 App A（呼叫 `setSourcePaused("pkg.a", true)` 或 `setSourceEnabled("pkg.a", false)`）。
  2. 協調器在 `gap_interval` 中建立了一筆 `endEpochMs = null` 的開放缺口（`SOURCE_PAUSED_BY_USER` 或 `SOURCE_DISABLED_BY_USER`，`packageName = "pkg.a"`）。
  3. 使用者隨後在健康介面上將該來源移除（呼叫 `removeSource("pkg.a", deleteData = false/true)`）。
- **可觀察之錯誤行為**：
  [`removeSource`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L638) 委派給 [`SourceRepository.remove`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SourceRepository.kt#L64)，其資料庫事務中移除了 `source_configuration` 等關聯資料，但遵循「缺口不隱瞞」原則並未刪除 `gap_interval`，同時**亦未呼叫 `closeOpenGapsForSource`** 來封閉該來源的開放缺口。
  一旦來源被移除，它不再存在於 `source_configuration`，使用者無法再透過 UI 進行「恢復」或「啟用」來觸發缺口封閉。
  在健康介面（[`HealthScreen.kt:272`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt#L272)）中，該缺口將永久顯示為：
  `"10:00 → 進行中 · 你暫停了這個來源 · pkg.a"`
  即使使用者重新透過 `addSource("pkg.a", ...)` 加回該來源，`addSource` 亦不進行缺口封閉，此舊缺口將持續呈開放狀態，直到 Retention 期限期滿被刪除為止。
- **修復建議**：
  在 `removeSource` 時，應明確關閉該來源名下所有開放的來源缺口（`closeOpenGapsForSource(now, packageName, GapReason.SOURCE_DISABLED_BY_USER, GapReason.SOURCE_PAUSED_BY_USER)`）；在 `addSource` 時，亦應以防禦性姿勢檢查並封閉舊有的開放來源缺口。

---

## Important（發布前建議修復）

### Important-1: `setSourceEnabled` 與 `setSourcePaused` 缺乏狀態冪等性檢查，重複呼叫會開啟重複缺口
- **檔案與行號**：
  - [`CaptureCoordinator.kt:609-635`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L609-L635)
- **具體輸入與行為**：
  若外部連環調用 `setSourceEnabled(pkg, false)` 或 `setSourcePaused(pkg, true)`（例如 UI 重複點擊或測試腳本觸發）：
  函數內未檢查目前是否「已經處於停用／暫停狀態」，每次皆直接執行 `health.openGap(..., packageName)`。
  這將在 `gap_interval` 中建立多筆 `packageName = pkg` 且 `endEpochMs = null` 的重複開放記錄。雖然 `closeOpenGapsForSource` 最終會遍歷並全部封閉，但在開啟期間健康面板會出現多條相同的開放缺口，並干擾缺口時段統計。
- **修復建議**：
  在調用 `openGap` 之前，比對目前快取的 `enabledPackages` 與 `pausedPackages`，若狀態未變更則直接返回（如同全域 [`setPaused`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L340) 的處理方式）。

---

### Important-2: 來源專屬缺口開關操作位於 `pipelineMutex` 之外，存在時序競爭風險
- **檔案與行號**：
  - [`CaptureCoordinator.kt:609-635`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L609-L635)
- **問題分析**：
  在 `setSourceEnabled` 和 `setSourcePaused` 中：
  ```kotlin
  changeSourcePolicy {
      sources.setEnabled(packageName, enabled)
      if (!enabled) ingest.discardPendingJournal(packageName)
  }
  val now = System.currentTimeMillis()
  guarded {
      if (enabled) {
          health.closeOpenGapsForSource(now, packageName, GapReason.SOURCE_DISABLED_BY_USER)
      } else {
          health.openGap(now, GapReason.SOURCE_DISABLED_BY_USER, GapPrecision.EXACT, now, packageName)
      }
  }
  ```
  政策變更（`changeSourcePolicy`）受 `pipelineMutex` 保護，但其後續的 `health.openGap` 與 `health.closeOpenGapsForSource` 卻在釋放互斥鎖後非同步執行。
  若兩個並行 Coroutine 在極短間隔內先後執行「停用」與「啟用」：
  1. Coroutine 1 完成停用政策變更，釋放 `pipelineMutex`。
  2. Coroutine 2 獲取 `pipelineMutex` 完成啟用政策變更，並先一步執行了 `closeOpenGapsForSource`（此時 Coroutine 1 尚未寫入缺口，查無資料）。
  3. Coroutine 1 隨後才執行 `health.openGap`。
  最終結果為：來源在政策上已被啟用，但資料庫中卻留下了一個永久開放的停用缺口。
- **修復建議**：
  將缺口的開啟與關閉納入 `changeSourcePolicy` 區塊內部，或使用同一互斥鎖進行序列化，確保狀態翻轉與缺口操作具備不可分割性。

---

### Important-3: 繁體中文測試矩陣與 SCOPE 文件之測試計數與細節落後
- **檔案與行號**：
  - [`docs/zh-Hant/TEST_MATRIX.md:25`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L25)
  - [`docs/SCOPE.md:20`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L20)
  - [`docs/zh-Hant/SCOPE.md:18`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/SCOPE.md#L18)
- **問題分析**：
  1. [`docs/TEST_MATRIX.md:51`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L51) 已將 `CaptureCoordinatorTest` 由 32 更新至 36，並詳盡補齊了 4 項新增測試（關閉來源記名缺口、暫停來源互不干擾、解析前丟棄訊息記為缺口、文字截短負面對照）。
  2. [`docs/zh-Hant/TEST_MATRIX.md:25`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L25) 僅將數字從 `32 個測試` 改為 `36 個測試`，括號內的中文逐項說明卻**完全漏掉了這 4 項新測試**。
  3. [`docs/SCOPE.md:20`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L20) 與 [`docs/zh-Hant/SCOPE.md:18`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/SCOPE.md#L18) 中的 `CaptureCoordinatorTest` 依然停留在 `(32)`，未同步更新為 `(36)`。
  這違反了「文件不得落後於代碼」之專案規範。

---

## Minor（次要改進建議）

### Minor-1: `MessageEntity.toDomain` 中對 `null` 旗標的無謂字串分割與 Enum 掃描
- **檔案與行號**：[`Mappers.kt:68-71`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/Mappers.kt#L68-L71)
- **問題分析**：
  ```kotlin
  truncationFlags = truncationFlags.orEmpty()
      .split(',')
      .mapNotNull { name -> enumValues<TruncationFlag>().firstOrNull { it.name == name } }
      .toSet()
  ```
  當 `truncationFlags` 為 `null` 時，`truncationFlags.orEmpty()` 為 `""`。在 Kotlin 中，`"".split(',')` 會回傳 `listOf("")`（單一空字串元素之列表）。隨後 `firstOrNull` 遍歷整個 `TruncationFlag` 陣列無一命中，最終產生空集合。
  由於資料庫中超過 99% 的訊息此欄位為 `null`，每次由 Room 讀出訊息皆會產生一次無謂的字串分割與陣列迭代。
- **建議**：改為 `if (truncationFlags.isNullOrEmpty()) emptySet() else ...`，以極低成本避開不必要的記憶體分配。

### Minor-2: `IngestRepository` 集合過濾前期早退
- **檔案與行號**：[`IngestRepository.kt:314-317`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L314-L317)
- **建議**：在未有任何截斷（`snapshot.shape.truncated.isEmpty()`）的常態路徑下，直接賦予 `null`，避免在每則訊息寫入時皆執行一次 `filter` 分配。

---

## Claims checked and found true（逐項核實與驗證成果）

本審查針對 commit message 所宣稱之要點與 BRIEF 所列之檢驗維度進行了全面檢驗，以下主張查證屬實且符合專案規範：

### 1. `MIGRATION_3_4` 與 Room Schema 完全收斂，既有資料列無損
- **驗證方式**：
  比對 [`schemas/3.json`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/schemas/dev.quietinbox.platform.storage.db.QuietInboxDatabase/3.json) 與 [`schemas/4.json`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/schemas/dev.quietinbox.platform.storage.db.QuietInboxDatabase/4.json) 的完整結構差異：
  - `gap_interval` 表僅增加 `packageName TEXT`（可空、無預設值、無額外索引）。
  - `message` 表僅增加 `truncationFlags TEXT`（可空、無預設值、無額外索引）。
  - 外鍵約束與索引完全未動。
  [`QuietInboxDatabase.kt:110-115`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt#L110-L115) 執行的兩個 `ALTER TABLE ADD COLUMN` 產生的 SQLite 表結構，與 Room 由實體宣告全新產生的結構（`createAllTables`）100% 收斂，Room 的 `MigrationTestHelper.validateMigration` 亦證實能順利通過。
- **資料保留**：
  在 [`MigrationTest.kt:108-143`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/MigrationTest.kt#L108-L143) 的 `migrate3To4AddsNullableColumnsAndKeepsRows` 測試中，明確驗證了在 v3 寫入的既有列，遷移後原欄位完全保留，新增的兩欄位值皆乾淨為 `NULL`，未發生任何重寫或資料流失。

### 2. 備份雙向相容性（0.1.3 ↔ 0.1.4）
- **驗證方式**：
  - **0.1.4 備份 → 0.1.3 還原**：
    0.1.3 的備份解析器使用 `Json { ignoreUnknownKeys = true }`。當 0.1.3 遇到 0.1.4 匯出包含 `truncationFlags` 的訊息 JSON 時，會自動忽略未知欄位，順利完成還原。
  - **0.1.3 備份 → 0.1.4 還原**：
    在 [`BackupRecords.kt:82`](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupRecords.kt#L82) 中，`val truncationFlags: String? = null` 明確宣告了預設值 `= null`。當 0.1.4 讀取缺少該欄位的 0.1.3 舊備份時，Kotlinx Serialization 自動賦予 `null`，入庫為 `NULL`。
  - **參數錯位風險消除**：
    在 [`BackupService.kt:172-184`](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L172-L184) 中，`BackupRecord.Message` 的建構已全數由位置參數重構為具名參數（`id = m.id, ...`），徹底消除了未來新增欄位時可能導致的靜默參數順序錯置風險。

### 3. 解析前記錄丟棄訊息缺口，成功封堵空解析漏報
- **驗證方式**：
  在 [`CaptureCoordinator.kt:871-873`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L871-L873) 中，只要 `snapshot.shape.truncated` 含有 `MESSAGES_DROPPED` 或 `HISTORIC_MESSAGES_DROPPED`，缺口即在 `parser.parse(snapshot)` 前記錄。
  若後續解析因為全部被過濾而返回空批次（觸發 `batch.messages.isEmpty() && batch.summary == null` 早退並標記為 `SKIPPED`），或是解析器拋出例外（`PARSE_EXCEPTION`），訊息丟棄的缺口均已如實寫入，不再像過去一樣因事件被視為 `SKIPPED` 而使遺失完全隱形。

### 4. `conversationId` 欄位不可行性論證成立
- **驗證方式**：
  逐一檢驗全專案所有呼叫 `openGap` 與 `recordGap` 的 7 類現場：
  1. `COLD_START`（冷啟動通知積壓）：通知未讀未解析，身分未定。
  2. `QUEUE_OVERFLOW`（接收佇列溢位）：通知尚未排隊入日誌，身分未定。
  3. `JOURNAL_FAILED`（日誌寫入失敗）：未進入 Ingest，身分未定。
  4. `MAINTENANCE`（重設或維護）：進程全域事件，無通知物件。
  5. `LISTENER_DISCONNECTED` / `NOT_GRANTED` / `PROCESS_RESTART` / `PAUSED_BY_USER`：進程或權限層級，無對話。
  6. `SOURCE_DISABLED_BY_USER` / `SOURCE_PAUSED_BY_USER`：使用者設定變更，無特定通知或對話。
  7. `MESSAGES_DROPPED`：為涵蓋空解析與異常情境，必須在解析前記錄；而在解析與 `identity.resolve` 執行前，根本無法得知對話 ID。
  因此，缺口表不設立 `conversationId` 的決策合情合理。

### 5. 旗標切割與容錯解析
- **驗證方式**：
  - `TruncationFlag` 成功拆分為僅代表文字縮短的 `MESSAGES` / `HISTORIC_MESSAGES`，以及代表整則訊息遺失的 `MESSAGES_DROPPED` / `HISTORIC_MESSAGES_DROPPED`。
  - 在 [`CaptureCoordinatorTest.kt:970-980`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L970-L980) 的負面對照測試中，驗證了僅包含 `MESSAGES` 與 `BIG_TEXT` 截短的訊息不會觸發 `MESSAGES_DROPPED` 缺口。
  - 在 [`Mappers.kt:68-71`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/Mappers.kt#L68-L71) 中，讀取未知旗標時透過 `firstOrNull` 降級過濾為 null，不會引發反序列化崩潰。

### 6. 字串資源完整對齊
- **驗證方式**：
  檢驗 5 套語言目錄下的 `strings.xml`（預設英文、簡中、繁中、日文、韓文）：
  - `conv_truncated`
  - `gap_reason_source_disabled`
  - `gap_reason_source_paused`
  - `gap_reason_messages_dropped`
  全部 4 個鍵值在 5 份檔案中均存在，無任何缺失或拼寫錯誤，且無未對齊的佔位符。

### 7. 自動化測試與 Lint 指標
- **驗證方式**：
  - 執行 `./gradlew testDebugUnitTest`：全專案 **236 個 JVM 單元測試全數通過**（新增 4 個測試）。
  - 執行 `./gradlew lintDebug`：全模組 **0 錯誤** 通過。
