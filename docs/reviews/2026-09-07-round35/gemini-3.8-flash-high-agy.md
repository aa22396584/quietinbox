# 第 35 輪唯讀審查：第 34 輪修正提交（`022b99a..f7a09ed`）

審查範圍：`022b99a..f7a09ed`，聚焦於提交 `f7a09ed`（Round 34 C2: a loss 0.1.3 recorded nowhere survives the upgrade）及其與前導提交 `022b99a`（Round 34 C1: a paused source keeps the gap that says so）之整合表現。
本輪審查嚴格遵循唯讀原則，未啟用任何自動化編排或工作流程模式，僅將完整審查報告寫入指定路徑 `docs/reviews/2026-09-07-round35/gemini-3.8-flash-high-agy.md`，無修改任何其他儲存庫檔案。

---

## Verdict

**APPROVE WITH MINOR FIXES**

第 34 輪三位審查員共同提出的 Critical（C1：調和邏輯誤關停用中的暫停缺口）已於 `022b99a` 解決；Codex 提出的第二項 Critical（C2：v0.1.3 遺留之確定性內容損失在升級重播時被靜默吞沒）以及 Important／Minor 事項（I1：WhatsApp 換行截斷誤標、I2：Known 路徑丟失同文重貼截短證據、I3：日誌寫入與 fallback 雙重失敗後的待補記義務、M1/M2：文件與註解過時陳述）已於本提交 `f7a09ed` 獲得結構性修復。

本輪對修復程式碼、SQL 交易邊界、舊版相容性謂詞、Room 巢狀交易機制、備份匯入及全庫測試進行了詳盡檢驗：
- **JVM 單元測試**：全庫共 **255 個測試全部通過**（0 failures, 0 errors, 0 skipped）。
- **Android Instrumented 測試**：共 **41 個測試**（`platform/storage`: 34、`platform/crypto`: 2、`platform/backup`: 2、`feature/conversation`: 3）。
- **靜態分析與閘門**：
  - `:platform:capture:lintDebug` 與 `:platform:storage:lintDebug` 均為 **0 errors, 0 warnings**。
  - `python3 tools/check-strings.py` 檢查五語系（en, zh-Hant, zh-Hans, ja, ko）字串目錄，結果為 **0 errors, 0 warnings**。
  - `tools/check-permissions.sh` 確認除通知、生物辨識、WakeLock 與前台服務外，**無任何 `INTERNET` 或網路相關權限**。

目前無阻擋發版的 Critical 缺陷。存在 2 項建議在正式 push 前調整的 Important 事項（包含 `journalLossSince` 在一般即時擷取恢復時未主動結清、以及 I2 單調規則在覆蓋式還原下的副作用），以及 2 項 Minor 文件數據細節。

---

## Critical（0.1.4 出貨前必須修正）

**無（None）**。未發現新的 Critical 缺陷。第 34 輪的所有 Critical 均已獲得實質性修復與回歸測試控制。

---

## Important（建議出貨前修正）

### I1. `journalLossSince` 僅掛於 `loadSourcePolicy()`，在無政策異動的一般擷取恢復情境下可能延遲結清或在行程重啟時遺失

- **位置**：
  - `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:505`
  - `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:941-953`
- **問題分析**：
  在 `process()` 中，當日誌寫入失敗（如磁碟空間已滿）且其緊急 fallback 缺口 `health.recordGap(...)` 也因相同原因拋出例外時，`CaptureCoordinator` 正確將起始時間記錄於記憶體變數：
  ```kotlin
  if (!recorded && journalLossSince == null) journalLossSince = snapshot.observedAtEpochMs
  ```
  依據目前實作，結清此待補記損失的函式 `settleUnrecordedJournalLoss(now)` 僅在 `loadSourcePolicy()`（`:505`）執行。
  然而，`loadSourcePolicy()` 的觸發點僅限於：
  1. Listener 服務初次連線／重連（`onConnected`）；
  2. 呼叫 `changeSourcePolicy`（使用者新增、啟用、暫停或移除來源）；
  3. `sources.observeSources()` 觸發（依賴 `source_configuration` 表變更）；
  4. `process()` 或 `replayJournal()` 中的 `if (!sourcesLoaded) loadSourcePolicy()`。

  **瑕疵場景**：
  若儲存空間在一段時間後恢復（例如背景 retention 清理了過期資料、或使用者清理了裝置空間），此時 `sourcesLoaded` 已經為 `true`。後續抵達的即時通知將成功通過 `ingest.journal(...)` 並正常 commit 進入收件匣。
  但因這段期間**沒有任何來源設定發生變化**，`loadSourcePolicy()` 根本不會被呼叫！
  這導致：
  1. 金庫早已恢復寫入且正常運作，但記憶體中的 `journalLossSince` 依然滯留未寫出；
  2. 若在使用者進入設定頁切換來源之前，應用程式行程被 Android 系統終結（如低記憶體 OOM Kill）或使用者重啟手機，該筆僅存於記憶體 `@Volatile private var journalLossSince` 的故障區間將**無聲無息地徹底消失**，無法在磁碟留下任何缺口記錄。
- **驗證方式**：
  審視 `CaptureCoordinatorTest.kt:1435` 的測試案例 `test("a loss neither the journal nor its fallback could record is written on the next policy load")`：該測試必須手動透過 `h.observedSources.emit(...)` 強制發射政策變更，才能觸發 `loadSourcePolicy()` 進行結清。若不發射該事件而直接調用 `offerCaptured` 送入成功事件，`journalLossSince` 不會被寫入 `gap_interval`。
- **建議修正**：
  除 `loadSourcePolicy()` 外，在 `process()` 成功寫入 `ingest.journal(...)` 或在 `replayJournal()` 成功推進後，亦呼叫一次 `settleUnrecordedJournalLoss(now)`，確保儲存層一旦恢復可寫，第一時間將滯留的故障區間落盤結清。

---

### I2. I2 單調規則（"set, never cleared"）在備份覆蓋還原與啟發式暫態誤標下的邊界效應

- **位置**：
  - `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:287`（`markTruncated`）
  - `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:394`
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:344-349`
- **問題分析**：
  提交 `f7a09ed` 為解決「同文重貼但帶有截短證據」的問題，於 `IngestRepository.kt` 的 `Decision.Known` 分支引入：
  ```kotlin
  truncationColumn(c.textTruncated)?.let { db.messageDao().markTruncated(id, it) }
  ```
  搭配 SQL：
  ```sql
  UPDATE message SET truncationFlags = :truncationFlags WHERE id = :id AND truncationFlags IS NULL
  ```
  設計理由為「只設不消（set, never cleared），已存入的內文不會因為後續重貼完整而變得不曾丟失」。但在以下兩種實際情境中，該規則會導致非預期的使用者視覺呈現：

  1. **備份還原覆蓋既有資料庫**：
     假設本地某訊息曾因某次帶截短標記的觀測而被設定為 `truncationFlags = "TEXT"`。使用者隨後匯入一份較早時間或另一設備產生的備份檔案（該備份中該則訊息為完整未截短，`truncationFlags = null`）。
     依 `BackupService.kt:344-349` 的去重邏輯：
     ```kotlin
     val dupKey = "${m.fingerprint}|${m.sortKey}|${m.observedAtEpochMs}"
     val remaining = preExisting.getValue(cid)[dupKey] ?: 0
     if (remaining > 0) {
         preExisting.getValue(cid)[dupKey] = remaining - 1
         continue
     }
     ```
     備份還原會判定該訊息已存在而直接跳過（不覆蓋亦不更新）。結果是：使用者還原了完整的備份，但畫面上該則訊息依然永久掛著 `[已截短]`（`conv_truncated`）標籤與剪刀圖示。
  2. **Adapter 啟發式誤判在後續精確重送中被澄清**：
     若某來源 App（如 WhatsApp）在群組通知中因暫態版面計算或邊界字元使 parser 標記了 `textTruncated = true`，隨後該 App 重新發送完整未截短的同一則通知（`textTruncated = false`）。Reconciler 判定為 `Known(REPOST)`，由於只設不消，資料庫維持 `TEXT`。使用者看到的訊息明明字元齊全，卻永遠顯示已截短標籤。
- **使用者可見後果**：
  在對話頁面（`ConversationScreen`）中，原本文字完整的訊息泡泡會持續顯示 `QualityTag`（`Icons.Outlined.ContentCut` + 橘色「已截短」），對使用者造成訊息內容不完整的誤導。
- **建議評估**：
  維持此設計作為悲觀上界（upper bound）尚可接受，但建議於文件（如 ADR 或設計備忘）中明確記載備份還原跳過已存在列不會沖刷既有截短標籤的語意邊界。

---

## Minor / Nitpicks

### M1. 文件中的測試計數與實際程式碼存在兩處脫節（Docs behind code）

- **位置**：
  - `docs/TEST_MATRIX.md:16` 與 `docs/zh-Hant/TEST_MATRIX.md:16`（`MonogramTest`）
  - `docs/TEST_MATRIX.md:20` 與 `docs/zh-Hant/TEST_MATRIX.md:20`（`MessageBubbleSemanticsTest`）
- **具體差異**：
  1. `TEST_MATRIX.md` 記載：
     ```markdown
     MonogramTest (4: Han, kana and hangul names give one glyph, Latin names two initials, blank gives ?)
     ```
     但檢視 `core/designsystem/src/test/kotlin/dev/quietinbox/core/designsystem/components/MonogramTest.kt`，實際包含 **6 個 `@Test` 案例**：
     - `hanNameGivesItsFirstCharacter`
     - `kanaAndHangulNamesGiveOneGlyphLikeHan`
     - `latinNamesGiveTwoInitials`
     - `blankOrMissingLabelIsAQuestionMark`
     - `anEmojiIsNeverCutInHalf`（Emoji 不被截斷一半）
     - `rightToLeftNamesGiveTwoInitials`（RTL 語言名稱縮寫）
     文件少計了 2 個案例。
  2. `TEST_MATRIX.md` 記載：
     ```markdown
     MessageBubbleSemanticsTest (2: in a group chat exactly one node in the merged tree carries both the sender and the body...)
     ```
     但檢視 `feature/conversation/src/androidTest/kotlin/dev/quietinbox/feature/conversation/MessageBubbleSemanticsTest.kt`，實際包含 **3 個 `@Test` 案例**：
     - `theSenderAndTheBodyAreOneNodeInTheMergedTree`
     - `theSenderIsReadOnceAndNotTwice`
     - `theyAreStillDrawnAsSeparateTextsUnderneath`
     文件少計了 1 個案例（提交訊息中已自述 `conversation 3`，但 `TEST_MATRIX.md` 雙語版本未更新括號內的數量標記）。

---

### M2. Schema 4 原地修改（amend in-place）對開發環境／測試機資料庫的相容性影響

- **位置**：
  - `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt:124`
  - `platform/storage/schemas/dev.quietinbox.platform.storage.db.QuietInboxDatabase/4.json`
- **架構評估**：
  - **贊成原地修改之理由**：v0.1.3 正式發布版為 Schema 3。Commit `9e379d3` 僅是內部未發布提交，任何正式標籤（Git tags）均不包含該版本。若在此時遞增為 Schema 5，將使生產環境永久留下無人走過的 `3→4` 與 `4→5` 冗餘過渡路徑。原地修改讓正式升級路徑維持單一且乾淨的 `MIGRATION_3_4`。
  - **對現有測試機／模擬器的損壞**：若開發者手機或 CI 模擬器曾安裝過 Round 33～34 的測試 APK，其 SQLite 的 `PRAGMA user_version` 已為 4，但其 `event_journal` 表缺少 `lossRecorded` 欄位。當安裝新版時：
    1. Room 在比對 `room_master_table` 的 identity hash 與 `4.json` 時會偵測到結構不符，拋出 `IllegalStateException: Room cannot verify the data integrity...`；
    2. 或在執行 `claimLoss`（`UPDATE event_journal SET lossRecorded = 1 ...`）時噴出 `SQLiteException: no such column: lossRecorded`。
  - **因應措施**：建議在 PR 或開發備忘中註記：曾安裝過中間測試版之裝置需執行 `adb shell pm clear dev.quietinbox.app.debug` 或清除資料。正式版使用者自 v0.1.3 升級不受影響。

---

## 查核屬實的主張、證據與極限（Claims Checked & Verified True）

### 1. 升級路徑的兩端消費者（Replay 與 Policy Discard）之覆蓋性與排他性
- **宣稱**：離開 `PENDING` 的途徑僅有重播（`replayJournal`）與來源停用／移除時的丟棄（`settleCarriedOverLosses`），兩者均在清空 payload 前先結清舊版損失。
- **查核證據**：
  1. 檢視 `Daos.kt:96`：
     ```sql
     DELETE FROM event_journal WHERE expiresAtEpochMs < :now AND state != 'PENDING'
     ```
     `deleteExpired()` 明確將 `state = 'PENDING'` 排除於過期清理之外；`RetentionWorker.kt:106` 不會清空 PENDING 列。
  2. `Daos.kt:99` 的 `deleteAllExpired()` 經全儲存庫檢索，**完全無任何呼叫端（0 callers）**。
  3. `Daos.kt:108` 的 `clear()` 僅在 DAO 介面宣告，正式產品程式碼中無任何呼叫點。
  4. 全庫獨佔維護（`deleteEverything()`）會直接銷毀金庫檔案重開；備份匯入（`BackupService.apply`）不碰觸 `event_journal` 表。
  5. 在 `CaptureCoordinator.kt:1105` 中，`recordCarriedOverLoss(replay)` 位於 `processJournaled` 之前；在 `CaptureCoordinator.kt:688` 與 `:720` 中，`settleCarriedOverLosses(packageName)` 位於 `discardPendingJournal` 之前。
- **極限**：若舊版 journal 存有格式損毀導致 JSON 無法反序列化（`decodeFromString` 失敗），該列在 `pendingJournal` 會被標記為 `FAILED(DECODE)`，無法從中讀取 shape，因而無法結清缺口。此極限已於 CHANGELOG 誠實說明。

---

### 2. 舊版確定性損失判定謂詞 `carriesUnrecordedLoss`
- **宣稱**：`LINES` 與「未帶任何內文截短倖存訊息的 `MESSAGES`/`HISTORIC_MESSAGES`」可 100% 確定在 v0.1.3 遺失了整行或整則訊息；其餘歧義情況不予猜測。
- **查核證據**：
  1. 對照 `git show v0.1.3:platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt`：
     - `:56`：`if (arr.size > Limits.MAX_TEXT_LINES) truncated += TruncationFlag.LINES`。在 v0.1.3 中，單一行文字超長僅默默截斷，只有陣列行數超限才會標註 `LINES`。因此舊 payload 上的 `LINES` 必然代表整行被丟棄。
     - `:153-157`（`bound()`）：
       ```kotlin
       if (list.size > Limits.MAX_MESSAGES) truncated += flag
       ...
       if (text?.truncated == true) truncated += flag
       ```
       只有這兩種原因會為 `flag`（`MESSAGES` 或 `HISTORIC_MESSAGES`）立標。若倖存訊息的 `it.text?.truncated == true` 均不成立（`none`），則唯一剩下之成因必然是訊息數量超過 64 則被丟棄。
     - 檢查 `bound()` 中的 `senderName`：`senderName = person?.name?.let { BoundedText.of(it, 256) }`，**其截斷從未被加入 `truncated` 集合中**。因此 `senderName` 是否截短與 `TruncationFlag.MESSAGES` 完全無關。
     - `HISTORIC_MESSAGES` 呼叫同一個 `bound()` 邏輯，兩者完全對稱。
  2. 現行 0.1.4 版本在 `SnapshotFactory.kt:56` 改產出 `LINES_DROPPED`；整則丟棄產出 `MESSAGES_DROPPED`。因此現行版本產生的 payload 均含有 `DROPPED_MESSAGES`，進入 `carriesUnrecordedLoss` 第一行即回傳 `false`。
  3. 即使是 0.1.4 產生的列，在接受時已由 `lossRecorded = lossOnAccept != null` 將資料庫欄位設為 1，從實體欄位上亦阻止了二次認領。

---

### 3. 認領邊界與條件式 UPDATE 冪等性（Exactly-Once）
- **宣稱**：`claimLoss` 的條件式更新保證多次重播、並行停用及行程崩潰下恰好記錄一次缺口；巢狀交易完全回滾。
- **查核證據**：
  1. 條件式 SQL：
     ```sql
     UPDATE event_journal SET lossRecorded = 1 WHERE eventId = :eventId AND lossRecorded = 0 AND state = 'PENDING'
     ```
     在 SQLite 單一寫入交易中，只有第一個成功將 `lossRecorded` 由 0 改為 1 的連線能取得 `rowsAffected == 1`。
  2. `claimEventLoss` 包裹於 `db.withTransaction { val won = claimLoss(eventId) == 1; if (won) writeGap(); won }`：
     - 若 `writeGap()` 失敗，交易回滾，`lossRecorded` 回復為 0，下次重播可繼續認領；
     - 若成功 commit 後行程崩潰，`lossRecorded` 已為 1，重開機後不會二次記錄。
  3. Room 巢狀交易安全性：`SourceRepository.setEnabled` 外層開啟 `withTransaction`，內層 `claimEventLoss` 再次進入 `withTransaction`。Room 利用 CoroutineContext 的 `TransactionElement` 實作重入（re-entrant），同一協程重用同一個 SQLite 交易。在 `SourcePolicyTransactionTest.kt:163`（`aDiscardWhoseSettlementFailsLeavesTheRowAndTheSourceWhereTheyWere`）實證中，當內層 gap 寫入拋出例外時，外層來源啟用狀態、PENDING 狀態與 `lossRecorded` 均完整回滾。

---

### 4. WhatsApp 換行截斷精確處理（I1）
- **宣稱**：截點剛好落在換行字元時，最後倖存的一列保持完整，不標記為內文截短。
- **查核證據**：
  1. `WhatsAppParser.kt:75`：
     ```kotlin
     val cutInsideLastRow = truncatedBody && body.substringAfterLast('\n').isNotBlank()
     ```
  2. 當截斷恰落在 `\n` 或 `\n` 後接空白時，`body.substringAfterLast('\n').isNotBlank()` 為 `false`，`cutInsideLastRow` 為 `false`，最後一則訊息的 `textTruncated` 為 `false`。
  3. 檢視 `WhatsAppParserTest.kt:95`（`a cut that landed on a line separator leaves the last surviving row complete`）與 `:108`（`a cut that landed inside the last row still marks it`），正向與負向控制測試均已涵蓋。

---

### 5. 缺口記錄點數量驗證（M1/M2）
- **宣稱**：全專案共有 15 處記錄 gap 的呼叫點，其中 9 處為全程序（process-wide），6 處帶有 source package，無任何對話級缺口。
- **查核證據**：
  檢索 `platform/capture/src/main/`，所有 `health.openGap` 與 `health.recordGap` 的實體呼叫點如下：
  1. `:270` - `recordGap(..., UNKNOWN)`（程序級）
  2. `:327` - `openGap(..., LISTENER_DISCONNECTED / NOT_GRANTED)`（程序級）
  3. `:381` - `openGap(..., PAUSED_BY_USER)`（程序級）
  4. `:499` - `recordGap(..., COLD_START)`（程序級）
  5. `:517` - `recordGap(..., UNKNOWN)`（程序級）
  6. `:629` - `recordGap(..., COLD_START)`（程序級）
  7. `:650` - `openGap(..., COLD_START)`（程序級）
  8. `:684` - `openGap(..., SOURCE_DISABLED_BY_USER, ..., packageName)`（**來源級 1**）
  9. `:701` - `openGap(..., SOURCE_PAUSED_BY_USER, ..., packageName)`（**來源級 2**）
  10. `:782` - `recordGap(..., MAINTENANCE)`（程序級）
  11. `:856` - `recordGap(..., QUEUE_OVERFLOW, ..., packageName)`（**來源級 3**）
  12. `:904` - `recordGap(..., MESSAGES_DROPPED, ..., packageName)`（**來源級 4**）
  13. `:928` - `openGap(..., UNKNOWN)`（程序級）
  14. `:945` - `recordGap(..., UNKNOWN, ..., packageName)`（**來源級 5**）
  15. `:1132` - `recordGap(..., MESSAGES_DROPPED, ..., packageName)`（`recordCarriedOverLoss`，**來源級 6**）

  共計 **恰好 15 處**（9 處程序級、6 處來源級、0 處對話級）。`QuietInboxDatabase.kt` 與 `CHANGELOG.md` 的陳述完全精確。

---

## 總結

提交 `022b99a..f7a09ed` 完整且嚴謹地解決了第 34 輪審查指出的兩項 Critical 缺陷。升級兼容邏輯具備明確的理論依據（對齊 v0.1.3 源碼合約）與可驗證的資料庫交易認領邊界；所有 255 個單元測試、41 個實機測試、Lint 及權限閘門均全綠通過。除 I1（`journalLossSince` 在正常即時擷取恢復時的結清時機）及 M1（兩處測試計數說明）建議在推送到主分支前微調外，其餘整體架構健全，給予 **APPROVE WITH MINOR FIXES** 判定。
