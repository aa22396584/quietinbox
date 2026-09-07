# 第 34 輪獨立審查報告：第 33 輪修復檢驗 (`42f8d18..60fa4f0`)

- **審查標的**：提交範圍 [`42f8d18..60fa4f0`](file:///Users/iml1s/Documents/mine/quietinbox)（包含 [`f5f9581`](file:///Users/iml1s/Documents/mine/quietinbox)、[`a258662`](file:///Users/iml1s/Documents/mine/quietinbox)、[`234e5cd`](file:///Users/iml1s/Documents/mine/quietinbox)、[`60fa4f0`](file:///Users/iml1s/Documents/mine/quietinbox) 四個修復提交）
- **審查結論（Verdict）**：**REQUEST CHANGES**

---

## 總結評述

本輪四個提交直接針對第 33 輪審查所指出的核心缺失進行了大規模的結構性重構：
1. **Cluster a ([`f5f9581`](file:///Users/iml1s/Documents/mine/quietinbox))**：將遺失訊息的缺口寫入從 `processJournaled` 移至事件驗收入庫（`ingest.journal`）的同一資料庫交易中，藉由 `event_journal.eventId` 主鍵約束實現了結構性的 Exactly-Once，根除了重播與重試重複建立缺口以及在 commit 圍籬前丟棄遺失證據的缺陷。
2. **Cluster b ([`a258662`](file:///Users/iml1s/Documents/mine/quietinbox))**：將來源政策變更（`setEnabled` / `setPaused`）與缺口開閉合併為單一資料庫交易，確保狀態翻轉與缺口記錄完全原子化；在 `removeSource` 時明確封閉來源未結束缺口並支援以 `NULL` 抹除名稱；並在政策載入時加入對帳邏輯（`reconcileSourceGaps`）。
3. **Cluster c ([`234e5cd`](file:///Users/iml1s/Documents/mine/quietinbox))**：將文字截斷旗標從 Snapshot 廣播式複寫改為單則訊息層級傳遞（`MessageCandidate.textTruncated`），並在 [`WhatsAppParser`](file:///Users/iml1s/Documents/mine/quietinbox/parsers/apps/src/main/kotlin/dev/quietinbox/parsers/apps/WhatsAppParser.kt) 與各解析路徑中精確指認受損訊息列，徹底消除了第 33 輪「完整訊息被虛假標記為截短」的違反誠實原則問題。
4. **Importants ([`60fa4f0`](file:///Users/iml1s/Documents/mine/quietinbox))**：修正了資料保留（retention）清理機制，確保未結束缺口（`endEpochMs IS NULL`）不被時間掃描刪除；將 `TruncationFlag.LINES` 正確歸類為遺失內容（`DROPPED_MESSAGES`）；並修正備份向下相容性的註解。

**然而，審查團隊在深入追蹤 Cluster b 的對帳邏輯時發現了一處新的重大缺陷（Critical）：**
提交 [`a258662`](file:///Users/iml1s/Documents/mine/quietinbox) 新增的 [`CaptureCoordinator.reconcileSourceGaps`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L418) 使用了 `pkg !in pausedPackages` 作為暫停缺口矛盾的判斷標準。然而，`pausedPackages` 僅包含「啟用且暫停（`it.enabled && it.paused`）」的套件。這導致：
- 當一個來源處於停用狀態（`enabled == false`）時，若被呼叫 [`setSourcePaused(pkg, true)`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L653)，交易內剛開啟的暫停缺口會在**同一呼叫內的 `loadSourcePolicy` 對帳中被立即強制關閉**。
- 更嚴重的是，若使用者遵循「暫停來源 → 停用來源 → 重新啟用來源」的操作順序，該來源在資料庫中將保持為暫停狀態（`paused == true`），擷取管線會持續拒絕擷取該來源的通知，但在健康頁面上卻**完全沒有任何開放的暫停缺口**！這直接違反了專案不可動搖的最高準則——「**Gaps are shown, never hidden**」（缺口必須顯示，絕不隱瞞）。

此外，繁體中文版 [`docs/zh-Hant/TEST_MATRIX.md`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md) 遺漏了第 34 輪新增的 [`SourcePolicyTransactionTest`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/SourcePolicyTransactionTest.kt) 描述，測試總數停留在 17 個（未與英文版 23 個同步）。

必須修復上述問題後，0.1.4 方可發布。

---

## Critical（發布前必須修復）

### C1. 來源在停用狀態下被暫停，其缺口被 `reconcileSourceGaps` 立即封閉；且「暫停 → 停用 → 重啟」序列導致來源實質暫停卻無任何開放缺口
- **檔案與行號**：
  - [`CaptureCoordinator.kt:418-430`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L418-L430)
  - [`CaptureCoordinator.kt:448`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L448)
  - [`CaptureCoordinator.kt:653-666`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L653-L666)
  - [`HealthScreen.kt:420-424`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt#L420-L424)
- **具體觸發序列**：
  - **情境 A（停用狀態下暫停來源，剛開的缺口在同次呼叫被閃閉）**：
    1. 來源 `pkg` 目前處於停用狀態（例如使用者在健康介面關閉了 Switch，`enabled = false`，`paused = false`）。
    2. 使用者點擊該列的暫停按鈕（在 [`HealthScreen.kt:420`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt#L420) 中，IconButton 未設定 `enabled = source.enabled`，停用時仍可點擊），或外部呼叫 [`coordinator.setSourcePaused(pkg, true)`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L653)。
    3. `setSourcePaused` 在交易內呼叫 `sources.setPaused(pkg, true)`，將資料庫中的 `paused` 設為 `true`，並執行 `health.openGap(now, GapReason.SOURCE_PAUSED_BY_USER, ..., pkg)` 開啟未結束缺口。交易成功提交。
    4. [`changeSourcePolicy`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L433) 接著執行 [`loadSourcePolicy()`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L445)。
    5. 在 [`loadSourcePolicy`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L448) 中：
       ```kotlin
       enabledPackages = list.filter { it.enabled }.map { it.packageName }.toSet()
       pausedPackages = list.filter { it.enabled && it.paused }.map { it.packageName }.toSet()
       ```
       因為 `pkg.enabled == false`，`pkg` **不會被加入 `pausedPackages`**！
    6. [`loadSourcePolicy`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L449) 緊接著呼叫 [`reconcileSourceGaps`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L418)：
       ```kotlin
       val contradicted = when (gap.reason) {
           GapReason.SOURCE_DISABLED_BY_USER.name -> pkg in enabledPackages
           GapReason.SOURCE_PAUSED_BY_USER.name -> pkg !in pausedPackages
           else -> false
       }
       if (contradicted || pkg !in known) health.closeGap(gap.id, now)
       ```
       因為 `pkg !in pausedPackages` 為 `true`，`contradicted` 被判定為 `true`！
    7. `reconcileSourceGaps` 呼叫 `health.closeGap(gap.id, now)`，**直接將第 3 步剛剛在同一個交易中打開的開放缺口強制封閉**！存續時間為 0 毫秒。
  - **情境 B（暫停 → 停用 → 重新啟用，造成實質停止擷取卻無開放缺口）**：
    1. 來源 `pkg` 原本為啟用且處於暫停狀態（`enabled = true, paused = true`），帶有開啟中的 `SOURCE_PAUSED_BY_USER` 缺口。
    2. 使用者在健康頁面關閉該來源開關（呼叫 [`setSourceEnabled(pkg, false)`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L635)）。
    3. `setSourceEnabled` 開啟 `SOURCE_DISABLED_BY_USER` 缺口。隨後 `loadSourcePolicy` 執行時，因 `pkg` 脫離 `pausedPackages`，`reconcileSourceGaps` 將原有的 `SOURCE_PAUSED_BY_USER` 缺口判定為矛盾並封閉。
    4. 使用者隨後將開關重新打開（呼叫 [`setSourceEnabled(pkg, true)`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L635)）。
    5. `setSourceEnabled` 封閉了 `SOURCE_DISABLED_BY_USER` 缺口。但在資料庫中，`source_configuration.paused` 從未被重設為 `false`，依然為 `true`！
    6. `loadSourcePolicy` 執行，`pkg` 再次進入 `pausedPackages`（因為 `it.enabled && it.paused` 均為 true）。
    7. 當 `pkg` 的通知送達時，[`admitted`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L828) 檢查 `pkg !in pausedPackages` 失敗，通知被直接攔截阻擋或維持 PENDING。
    8. **然而，健康頁面上沒有任何開放缺口**！`SOURCE_PAUSED_BY_USER` 缺口早在第 3 步被關閉，第 5 步重新啟用僅關閉了停用缺口，並未補開暫停缺口。
- **可觀察之錯誤行為**：
  在情境 A 中，對停用來源設定暫停，缺口瞬間打開並在同一呼叫內被關閉；
  在情境 B 中，使用者重新啟用來源後，該來源實質上仍處於「暫停中」而停止擷取通知，但健康介面（`HealthScreen`）的缺口清單中**完全沒有任何進行中的缺口**。這直接破壞了「**Gaps are shown, never hidden**」這一核心承諾，使使用者誤以為系統正常運作中，實際上通知卻被靜默丟棄。
- **修復建議**：
  1. **UI 限制**：在 [`HealthScreen.kt:420`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt#L420) 中，將暫停圖示按鈕設為依賴啟用狀態：`enabled = source.enabled`，防止使用者對未啟用的來源進行無效暫停操作。
  2. **協調器防禦**：在 [`CaptureCoordinator.setSourcePaused`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L653) 中，若 `pkg !in enabledPackages`，應直接拒絕或拋出防禦性狀態例外，不可對未啟用的來源開啟暫停缺口。
  3. **對帳邏輯精確化**：在 [`CaptureCoordinator.reconcileSourceGaps`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L425) 中，判斷 `SOURCE_PAUSED_BY_USER` 是否矛盾時，不應僅比對記憶體中的 `pausedPackages`（它混雜了 `enabled` 條件），而應直接檢驗來源在配置清單中的真實屬性（例如 `val source = list.firstOrNull { it.packageName == pkg }; contradicted = source == null || !source.paused || !source.enabled`），並且當來源在已暫停狀態下被重新啟用時，應補開或維持其對應的缺口；或者在停用來源（`sources.setEnabled(pkg, false)`）時，明確定案是否應一併清空 `paused = false`。

---

## Important（建議出貨前修正）

### I1. 繁體中文版 `docs/zh-Hant/TEST_MATRIX.md` 遺漏 `SourcePolicyTransactionTest`，測試計數停留於 17 個
- **檔案與行號**：
  - [`docs/zh-Hant/TEST_MATRIX.md:18`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L18)
  - [`docs/TEST_MATRIX.md:18`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L18)
- **具體問題與比對**：
  在提交 [`a258662`](file:///Users/iml1s/Documents/mine/quietinbox) 與 [`60fa4f0`](file:///Users/iml1s/Documents/mine/quietinbox) 中，英文版 [`docs/TEST_MATRIX.md:18`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L18) 在儲存層 instrumented 測試列加入了全新的 [`SourcePolicyTransactionTest`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/SourcePolicyTransactionTest.kt)（包含 6 個測試），並將總數自 17 個更新為 23 個（`— 23 tests`）。
  然而，繁體中文版雙胞檔案 [`docs/zh-Hant/TEST_MATRIX.md:18`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L18) 仍維持舊版內容，完全未提及 `SourcePolicyTransactionTest`，且結尾文字仍停留在 `——共 17 個`。
  這違反了專案「Docs must not run ahead of code」以及多語系矩陣文件必須維持同等同步之原則。
- **修復建議**：
  將 [`docs/TEST_MATRIX.md:18`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L18) 補充的 `SourcePolicyTransactionTest` 項目翻譯並同步至 [`docs/zh-Hant/TEST_MATRIX.md:18`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L18)，將總測試數同步更新為「——共 23 個」。

---

## Minor（次要調整建議）

### M1. `HealthScreen.kt` 的 `SourceRow` 暫停按鈕未受 `source.enabled` 狀態約束
- **檔案與行號**：
  - [`HealthScreen.kt:420`](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt#L420)
- **具體問題**：
  當使用者將開關關閉（`source.enabled == false`）時，該列的暫停圖示按鈕仍處於可點擊狀態。雖然點擊後按鈕文字會切換為「繼續」，但因來源已停用，此操作在語意上是不合理的，且會觸發 C1 的缺陷。建議在按鈕或點擊處理加上 `enabled = source.enabled` 約束。

---

## 審查要點逐項檢驗與確認真實性（Claims Checked and Found True）

本節逐一回應 [`BRIEF.md`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/2026-09-07-round34/BRIEF.md) 所列之 7 項檢驗要求，並給予具體代碼與測試驗證證據：

### 1. Cluster a 的結構性 Exactly-Once 與交易原子性證明
- **Exactly-Once 結構性驗證**：
  - 在 [`CaptureCoordinator.kt:872`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L872)，`ingest.journal(snapshot, item.generation, ttl, lossOnAccept)` 是全系統**唯一**呼叫入庫接受的位置。
  - [`IngestRepository.journal`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L95) 將寫入包裹於 `db.withTransaction` 中：
    ```kotlin
    val accepted = db.journalDao().insert(row) != -1L
    if (accepted) lossOnAccept?.invoke()
    accepted
    ```
  - [`JournalDao.insert`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L43) 明確標記 `@Insert(onConflict = OnConflictStrategy.IGNORE)`，且主鍵為 [`EventJournalEntity.eventId`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Entities.kt#L49)。若同一事件被重複遞送，資料庫直接回傳 `-1L`，`accepted` 為 `false`，`lossOnAccept` 絕不會二次執行。
- **Lambda 拋出例外的情境**：
  - 若 `lossOnAccept` 執行拋出例外，Room 的 `withTransaction` 會捕獲該例外並觸發交易 Rollback，使 `journalDao().insert(row)` 隨之一同復原，日誌列不成立。
  - 例外向上傳播至 [`CaptureCoordinator.process`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L890) 的 `catch (e: Exception)` 區塊。此時 `journaled` 變數仍為 `false`，進入 `else` 分支記錄 `JOURNAL_FAILED` 診斷，並透過 round-11 的保底路徑補記 `GapReason.UNKNOWN` 缺口，保證遺失永不憑空消失。
- **金庫鎖定情境**：
  - 若金庫處於鎖定中，`holder.db()` 會拋出 `VaultUnavailableException`，由第 876 行捕獲。此時日誌未入庫，系統透過 `vaultGapSince = snapshot.observedAtEpochMs` 紀錄鎖定起點，待金庫解鎖後由 [`settleColdStartGap`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L470) 一併補記。
- **重播路徑（Replay Path）**：
  - [`replayJournal`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L1025) 讀取的是已經在日誌庫中的 PENDING 列，直接呼叫 [`processJournaled`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L939)，**完全不呼叫 `ingest.journal`**。且第 33 輪原本置於 `processJournaled` 中的缺口寫入已在 [`f5f9581`](file:///Users/iml1s/Documents/mine/quietinbox) 中被徹底刪除。因此，重播路徑絕不可能再次觸發缺口寫入。
  - 此行為已在 [`CaptureCoordinatorTest.kt:994`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L994) 之測試「`replaying a pending row does not record its loss again`」中獲得驗證。

### 2. Cluster b 政策交易、來源移除與資料隔離性
- **來源移除時的資料抹除（`deleteData = true`）**：
  - 經檢查 [`HealthDao.forgetGapSource`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L503)：
    ```sql
    UPDATE gap_interval SET packageName = NULL WHERE packageName = :packageName
    ```
  - **程序層級缺口（Process-wide gaps）**：因程序級缺口其 `packageName` 原本即為 `NULL`，`WHERE packageName = :packageName` 不會匹配，故不受任何影響。
  - **其他來源的缺口**：`packageName` 為其他字串，不會匹配，不受任何影響。
  - **誠實原則**：缺口列並未被刪除，`startEpochMs`、`endEpochMs`、`reason` 均原樣保留，證明擷取曾發生中斷的事實並未被隱瞞，僅抹除已被使用者要求遺忘的套件名稱。
- **`addSource` 的缺口行為**：
  - [`addSource`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L625) 將來源以 `enabled = true, paused = false` 寫入。新增來源並不代表擷取遺失，故不開啟缺口。若過去曾有未關閉的舊開放缺口，`loadSourcePolicy` 呼叫的 `reconcileSourceGaps` 會自動因狀態矛盾而將其封閉，設計得當。

### 3. Cluster c 單則訊息截斷傳遞精確性
- **`StandardParser` 的三個建構點**：
  - `messagingCandidates`（MessagingStyle）：精確讀取各訊息自身的 [`m.text?.truncated == true`](file:///Users/iml1s/Documents/mine/quietinbox/core/parser/src/main/kotlin/dev/quietinbox/core/parser/StandardParser.kt#L153)。
  - `inboxCandidates`（InboxStyle）：精確讀取各行自身的 [`line.truncated`](file:///Users/iml1s/Documents/mine/quietinbox/core/parser/src/main/kotlin/dev/quietinbox/core/parser/StandardParser.kt#L190)。
  - `singleCandidate`（BigText / 一般單則）：精確讀取選定內文的 [`bounded.truncated`](file:///Users/iml1s/Documents/mine/quietinbox/core/parser/src/main/kotlin/dev/quietinbox/core/parser/StandardParser.kt#L225)。
- **`WhatsAppParser` 的群組分割與單行處理**：
  - 在 [`WhatsAppParser.kt:83`](file:///Users/iml1s/Documents/mine/quietinbox/parsers/apps/src/main/kotlin/dev/quietinbox/parsers/apps/WhatsAppParser.kt#L83) 中，多行群組通知分割時設定：
    `textTruncated = truncatedBody && index == pairs.lastIndex`。
    因為 `BoundedText.of()` 是針對整段字串從尾端進行截斷（Tail Truncation），若字串被切斷，受損的位置必然位於最後一行的行尾（或截斷點切在最後一行的中間）。前面的各行字元皆完整無損。因此僅標記最後一行是完全符合客觀物理事實的。
  - 若內文小於 2 行（`lines.size < 2`）或格式無法解析，直接退回 `super.appSingleCandidates`，由 `StandardParser.singleCandidate` 精確賦值 `bounded.truncated`。
- **無 Snapshot 層級旗標外溢至訊息列**：
  - [`IngestRepository.kt:332`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L332) 與 [`applyRevision`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L366) 均使用 `truncationColumn(c.textTruncated)`。整份代碼中已無任何位置會把 `snapshot.shape.truncated` 子集合複製進 `MessageEntity`。
- **布林欄位 `Message.bodyTruncated`**：
  - 領域模型 [`Message.bodyTruncated: Boolean`](file:///Users/iml1s/Documents/mine/quietinbox/core/model/src/main/kotlin/dev/quietinbox/core/model/Message.kt#L57) 僅需回答「本則訊息內文是否被切短」。對話氣泡 UI（[`Labels.kt`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Labels.kt#L105)）向使用者呈現剪刀圖示與 `conv_truncated`，從未需要向使用者區分該內文原先來自哪種通知欄位。資料庫列中保存 `"TEXT"` 字串以相容 Schema 4，領域層轉為布林值完全無資訊損失。

### 4. K6 宣稱驗證（舊版 `MESSAGES` 旗標在 v0.1.4 不再參與決策）
- 檢驗全專案代碼：
  - `TruncationFlag.MESSAGES` 已自 `DROPPED_MESSAGES` 集合中移除（`DROPPED_MESSAGES` 僅含 `MESSAGES_DROPPED`、`HISTORIC_MESSAGES_DROPPED`、`LINES`）。
  - `StandardParser` 與 `IngestRepository` 完全不依賴 `TruncationFlag.MESSAGES`；個別訊息的截斷依賴 `BoundedText.truncated`（該欄位在 0.1.3 時即已隨 `MessagingMessageShape` 序列化持久化於日誌庫）。
  - 在 [`StandardParserTest.kt:51`](file:///Users/iml1s/Documents/mine/quietinbox/core/parser/src/test/kotlin/dev/quietinbox/core/parser/StandardParserTest.kt#L51) 的測試「`a v0.1.3 payload's ambiguous MESSAGES flag decides nothing`」中，即使 JSON 載荷帶有舊版 `MESSAGES` 旗標，還原解析後僅真正標記 `truncated = true` 的訊息具有 `textTruncated = true`。K6 宣稱完全屬實。

### 5. 測試誠實性（Test Honesty）檢驗
審查團隊逐一檢查本輪四個提交所新增的測試，確認其皆為真控制（Real Controls），並非無效劇院式測試（Test Theatre）：
- [`CaptureCoordinatorTest`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt)：
  - `an event the journal already holds does not record its loss a second time`：若將第二次插入模擬為成功，測試立即轉紅。
  - `replaying a pending row does not record its loss again`：若將缺口寫入放回 `processJournaled`，測試立即以 `recordGap` 被呼叫 1 次而轉紅。
  - `lines an InboxStyle notification could not hold are a gap, not a shortened body`：若自 `DROPPED_MESSAGES` 中移除 `TruncationFlag.LINES`，測試立即失敗。
- [`StandardParserTest`](file:///Users/iml1s/Documents/mine/quietinbox/core/parser/src/test/kotlin/dev/quietinbox/core/parser/StandardParserTest.kt)：
  - `only the message that was actually cut is marked as shortened` 與 `a batch whose messages all survived intact marks none of them`：若將 `textTruncated` 還原為舊版依賴 `snapshot.shape.truncated`，兩者皆立即轉紅。
- [`SourcePolicyTransactionTest`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/SourcePolicyTransactionTest.kt)：
  - 在真實 SQLCipher 資料庫上直接測試：旗標與缺口同 transaction 提交、重複設定旗標不觸發 lambda、缺口拋例外導致政策 rollback、移除來源同 transaction 關閉缺口、遺忘來源保留區間並清空名稱、retention 僅刪除已關閉缺口保留開放缺口。6 項測試皆直接針對真實 DAO 與交易邊界驗證。

### 6. 文件、矩陣與全域代碼驗證
- **單元測試執行結果**：全專案 246 個 JVM 單元測試全數通過（`BUILD SUCCESSFUL`）。
- **靜態程式碼分析**：全專案 `./gradlew lintDebug` 通過，0 錯誤、0 警告。
- **字串目錄多語系檢查**：執行 `python3 tools/check-strings.py`，en、zh-Hant、zh-Hans、ja、ko 5 語系完全對齊，0 錯誤、0 警告。
- **文件規範**：[`docs/SCOPE.md`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md) 與繁體中文版均正確更新為 13 個測試；[`docs/ARCHITECTURE.md`](file:///Users/iml1s/Documents/mine/quietinbox/docs/ARCHITECTURE.md) 與繁體中文版均標明 schema 4；僅繁體中文版 [`docs/zh-Hant/TEST_MATRIX.md:18`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L18) 遺漏 `SourcePolicyTransactionTest`，詳見 Important-1。
