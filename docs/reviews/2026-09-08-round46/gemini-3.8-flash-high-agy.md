# Round 46 審查報告：搜尋分頁、備份獨占掛起、還原誠實性與 CI 媒體測試

- **審查目標**：針對 2026-09-06–09-08 審查所確認之 5 項缺陷修復（2 P1 + 3 P2）進行獨立、嚴格之代碼審查與驗證。
- **審查範圍**：`git diff 0cca955..eff84c0`（單一 commit `eff84c0`，基準為 `0cca9552`）。
- **審查模式**：READ-ONLY（未修改任何產品代碼、未執行任何 git write 命令、未連網、未操作實體裝置）。
- **審查路徑**：`/Users/iml1s/Documents/mine/quietinbox`，HEAD `eff84c0`。

---

## 審查結論 (Verdict)

### **REQUEST CHANGES**

- **Critical Findings**：0
- **Important Findings**：2（I1：取消匯入仍同步等待不可信串流之 close，caller-exit 保證破裂；I2：移除 `importNow` 的 IO dispatcher 導致文件開啟與媒體加密寫檔退化至 UI 主執行緒）
- **Minor Findings**：3（M1：搜尋 flow collector 逐次等待 `run(s)` 之序列化限制；M2：`BackupHangTest` 假樁將 `close()` 實作為空方法掩蓋了 I1；M3：`liveImportReads` 在 `close()` 執行前即扣減計數使並行上限失準）
- **Observations**：2（O1：搜尋分頁狀態已嚴格綁定 `sessionId` 與 `pageRequestId`，成功根除 stale-unlock；O2：五語系字串齊備且 CI 採用根目錄 `test` 聚合任務）

**總結判定**：
本輪在搜尋分頁（綁定 `sessionId`/`pageRequestId`、凍結時間、獨立 cursor、隔離過期回調）、還原誠實性（`skippedMedia` vs `mediaNotRestored` 採用 `else if` 互斥統計）以及 CI 測試聚合（根目錄 `./gradlew test` 自動包含 `:platform:media`）等項目上的修復方向明確且多數代碼品質極高。
然而，在 **P1 備份獨占掛起（Backup Exclusive Hang）** 的重構中引入了兩項嚴重的架構與執行緒問題：
1. **I1 (P1)**：當等待端取消時，呼叫者執行緒在 `stageFromSource` 的 `finally` 區塊中**同步呼叫**不可信來源的 `input.close()`。若 document provider 的串流在 `read()` 與 `close()` 共用內部鎖，當 `read()` 永久阻塞時，`close()` 亦會永久卡死呼叫者執行緒，導致「取消後呼叫端可立即退出」的驗收承諾破裂。
2. **I2 (P2)**：重構時完全移除了原本包覆 `importNow` 的 `withContext(Dispatchers.IO)`，僅將 `readAndStage` 放入背景 scope。但在正式路徑 `SettingsViewModel.import`（運行於 `viewModelScope.launch` 即 `Dispatchers.Main.immediate`）中，`openInput`（IPC 與跨行程開啟描述符）、`StatFs.availableBytes`（磁碟 I/O）以及 `apply` 中的**媒體 Base64 解碼、Tink 加密與磁碟檔案寫入**全部退化至**主執行緒（UI Thread）** 同步執行，在大檔案或多媒體備份情境下將造成明顯畫面凍結與嚴重 ANR 風險。

因此，本輪判定為 **REQUEST CHANGES**，必須修復 I1 與 I2 後始得放行。

---

## Findings 詳情

### Important

#### I1 — P1：取消匯入仍同步等待不可信串流之 `close()`，caller-exit 保證破裂
- **檔案與行號**：
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:346-348`（`finally { runCatching { input.close() } }`）
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:265` 及 `:316`（`currentImportStream.getAndSet(...)?.let { runCatching { it.close() } }`）
  - `platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupHangTest.kt:100-102`
- **問題分析**：
  - BRIEF 明確要求：「A never-returning `InputStream.read` must let the caller exit and leave maintenance inactive.」
  - 代碼中雖然使用 `CompletableDeferred<Staged>` 讓呼叫端不用 `join()` 背景的 `importReads` 工作，但在 `stageFromSource` 的 `finally` 區塊中：
    ```kotlin
    try {
        return result.await()
    } finally {
        runCatching { input.close() }
    }
    ```
    這段 `finally` 是在**呼叫者的 Coroutine 執行緒**上執行。`runCatching` 只能捕捉拋出的例外，**無法中斷同步阻塞**。
  - 對於 Android 系統中來自外部 DocumentProvider / SAF / 管道的不可信 `InputStream`，其實作往往在 `read()` 與 `close()` 之間共用內部同步鎖（如 `synchronized(this)` 或 native lock）。若 `read()` 永久卡死，呼叫端發起 `job.cancel()` 後，執行緒進到 `finally` 呼叫 `input.close()` 時將會一同陷入無限等待，呼叫端永遠無法退出，`job.join()` 永不返回。
  - 此外，在 `import()` 開頭（line 265）與 `stageFromSource`（line 316）中呼叫 `currentImportStream.getAndSet(...)?.let { it.close() }` 也同樣是在呼叫者執行緒上同步執行，前一次掛起的串流若其 `close()` 阻塞，會直接導致下一次 `import()` 在進入前即被卡死。
- **修復建議**：
  - 呼叫者退出的路徑上**絕不能同步等待或呼叫任何 provider 的 blocking 操作（包括 `close()`）**。
  - 所有不可信串流的操作（`open`、`read`、`close`）必須完全限制於有明確配額上限的背景 IO 執行緒中非同步執行。呼叫者取消時僅取消對 `result.await()` 的等待並立即退出，不可在呼叫者上下文內同步執行 `input.close()`。

---

#### I2 — P2：移除 `importNow` 的 IO Dispatcher，文件開啟與媒體加密寫檔退化至 UI 主執行緒
- **檔案與行號**：
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:263`、`:315`、`:288`、`:384`、`:397-413`
  - `feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsViewModel.kt:106-110`
- **問題分析**：
  - 在基準 commit `0cca955` 中，`importNow` 整個方法體明確包裹在 `withContext(Dispatchers.IO) { ... }` 內執行。
  - 在 `eff84c0` 的重構中，作者為了將 `readAndStage` 搬到獨立的 `importReads`，直接將原 `withContext(Dispatchers.IO)` 刪除，但卻未替剩餘步驟指定 dispatcher：
    1. Line 315：`val input = openInput(source)` 在呼叫者上下文同步執行（觸發 ContentResolver IPC 取得串流）。
    2. Line 288：`val free = freeBytes()`（呼叫 `StatFs.availableBytes` 檢查磁碟容量）在呼叫者上下文同步執行。
    3. Line 301 & 384：`apply(db, staged)` 在呼叫者上下文同步執行。`VaultMaintenance.exclusive` 僅管理鎖與 coroutineScope，並不切換 dispatcher。
    4. Line 397-413：`apply` 在進入 Room transaction 之前，會走訪所有 `staged.media`，同步執行 `Base64.decode`、`blobCipher.encryptToFile`（Tink AEAD 加密計算）以及底層的檔案寫入（`file.writeBytes`）。
  - 在實際 App 運行路徑中，`SettingsViewModel.import(source, key)` 是由 UI 透過預設的 `viewModelScope.launch` 發起（預設為 `Dispatchers.Main.immediate`）。這意味著：**文件打開、磁碟空間查詢、所有媒體的 Base64 解碼、AEAD 加密計算與檔案磁碟 I/O，全部直接在 Android UI 主執行緒上執行！**
  - 當使用者還原包含多張圖片或影音的備份時，這將造成嚴重的 UI 卡頓、掉幀，極易引發系統 ANR（Application Not Responding）。
- **修復建議**：
  - 本地數據處理與磁碟寫入（`openInput`、`freeBytes` 以及 `apply` 及其中的媒體加密寫檔）必須明確置於 `withContext(Dispatchers.IO)` 下執行。注意：不要將不可信的 `InputStream.read` 包回呼叫者必須等待的 `withContext` 中，但本地的 `apply` 必須保證不在主執行緒上耗費 CPU 與 I/O。

---

### Minor

#### M1：搜尋 flow collector 逐次等待 `run(s)` 之序列化限制
- **檔案與行號**：
  - `feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt:84-90`
  - `feature/search/src/test/kotlin/dev/quietinbox/feature/search/SearchViewModelTest.kt:263-274`
- **問題分析**：
  - `init` 中的 flow 收集為 `combine(local.debounce(250), vault.state).collect { (s, v) -> if (v is VaultState.Ready) run(s) }`。
  - 由於 Kotlin Flow 的 `collect` 區塊是循序執行的，若前一次搜尋請求在 `search.searchPage(...)` 中延遲或掛起，collector 會暫停在 `run(s1)`，導致後續即使已經過了 debounce 且產生了新的 `sessionId`，新的搜尋也必須等待舊的 `run(s1)` 返回後才能真正發起。
  - `SearchViewModelTest` 的測試 `"A then B then A applies only the later A's pages"` 明確先手動讓第一個 A 的 gate 完成，才等待第二個 A，並在註解承認此序列化行為（`// The later run starts only after this returns`）。此限制未造成混頁或崩潰，但若產品預期新查詢應能立即發起請求，應考慮對 `run` 進行適當的任務排程或在安全邊界內取消舊 Job。

#### M2：`BackupHangTest` 測試假樁將 `close()` 實作為空方法掩蓋了 I1
- **檔案與行號**：
  - `platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupHangTest.kt:100-102`
- **問題分析**：
  - 測試中的假串流實作為：
    ```kotlin
    override fun close() {
        // Close must not be what unblocks the caller; cancel of the waiter is.
    }
    ```
  - 由於此測試假樁的 `close()` 是無操作（no-op），因而在 `finally { runCatching { input.close() } }` 中瞬間返回，使 `job.join()` 可以在 5 秒內成功。如果假樁模擬真實世界的阻塞（例如在 `close()` 內呼叫 `releaseHung.await()` 模擬 read 與 close 同鎖），現有測試將立刻死鎖逾時失敗。這屬於測試未涵蓋邊界條件的反例。

#### M3：`liveImportReads` 在 `close()` 執行前即扣減計數使並行上限失準
- **檔案與行號**：
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:319-322`、`:339-341`
- **問題分析**：
  - 在 `stageFromSource` 的 `importReads.launch` 中：
    - 超額分支（line 319）：先執行 `liveImportReads.decrementAndGet()`，隨後才執行 `runCatching { input.close() }`。
    - 正常結束 `finally`（line 339）：先執行 `liveImportReads.decrementAndGet()`，隨後才執行 `input.close()`。
  - 若 `input.close()` 發生阻塞，該執行緒實際上仍被佔用，但 `liveImportReads` 計數已被提前扣除，導致 `MAX_LIVE_IMPORT_READS`（配額 2）無法精確防禦累積的掛起執行緒。計數應在所有 cleanup 工作徹底結束後再扣減。

---

## 五大修復項驗證結果 (Detailed Audit)

| 修復項目 | 要求規範 | 審查與實作核對 | 結論 |
| :--- | :--- | :--- | :---: |
| **1. P1 Search Paging** | 綁定 session id；條件變更作廢 cursor；過期分頁不得影響新 session 之 flag（特別是 `loadingMore`）；每頁獨立 request id；禁止僅靠 `distinctBy` 或單純 cancel | `SearchUiState` 引入 `sessionId`、`pageRequestId`、`frozenFromMs`。條件變更均呼叫 `newSession` 重置 cursor 為 null。`loadMore` 與 `run` 在回調中嚴格比對 `sessionId` 與 `pageRequestId`，不符合時原樣返回 `cur`，不再誤清 `loadingMore = false`。 | **通過** |
| **2. P1 Backup Exclusive Hang** | 在 `VaultMaintenance.exclusive` 之外 stage/read/verify；短 exclusive apply；寫入前覆核 epoch/token；不可返回之 read 必須讓呼叫者退出且釋放 maintenance；禁止 unbounded jobs | stage 移至 exclusive 之外；進入 exclusive 前及內部均雙重檢驗 `token` 與 `keyMaterial.epoch`；使用 `CompletableDeferred` 隔離。然而因存在 **I1**（同步 `close()` 破壞 caller-exit）與 **I2**（主執行緒退化），本項未完全達標。 | **未通過 (I1, I2)** |
| **3. P2 Restore Honesty** | 備份匯出跳過 blob 但保留 `LOCAL_COPY` 之訊息，還原時 `skippedMedia > 0`；與損毀之 `mediaNotRestored` 嚴格區分且互斥，無 double-count | `BackupService.kt:472-480` 使用 `else if` 明確區隔 `media != null && blob == null`（`mediaNotRestored++`）與 `media == null && m.mediaState == LOCAL_COPY`（`mediaAbsent++`，回傳為 `skippedMedia`）。`BackupRoundTripTest` 斷言 `skippedMedia shouldBe 1` 且 `mediaNotRestored shouldBe 0`。 | **通過** |
| **4. P2 Search Errors** | Repository 拋出例外視為失敗而非空成功；load-more 失敗保留既有結果與 cursor；重拋 `CancellationException`；鎖定/開啟狀態維持區隔；同 query 失敗後可重試；五語系文案 | 例外捕獲分支重拋 `CancellationException`，其餘例外第一頁設 `failed = true`，load-more 則保留結果與 cursor。畫面提供重試按鈕呼叫 `retrySearch()`（讀取 `vault.state.value`）。五語系 `search_failed_title` / `search_failed_body` 齊備。 | **通過** |
| **5. P2 CI JVM** | CI 必須確實執行 `:platform:media:testDebugUnitTest`；偏好單一 aggregate 避免手動清單漏列 | `.github/workflows/ci.yml` 改為 `./gradlew --no-daemon --console=plain test`，透過 Gradle aggregate 自動排程所有模組的 JVM 與 Android 單元測試（包含 `:platform:media`）。 | **通過** |

---

## Hunt Especially 深度核查

1. **Session vs Sequential `run()` in Debounce Collector (A→B→A)**：
   - 審查確認：`combine(local.debounce(250), vault.state)` 中使用 `distinctUntilChanged { a, b -> a.sessionId == b.sessionId && va == vb }`，解決了過去 A→B→A 相同 query 被 `distinctUntilChanged` 吞噬的問題。但在前一個 `run()` 未返回前，collector 不會啟動新的 `run()`，屬保留之循序設計（見 M1）。
2. **Stale `loadingMore=false` on Session Mismatch (原始 Bug)**：
   - 審查確認：`SearchViewModel.kt:124` 與 `:130` 中，當 `cur.sessionId != sessionId || cur.pageRequestId != requestId` 時，均直接回傳 `cur`，不再誤將 `loadingMore` 設為 `false`，徹底修復了跨 session 狀態污染。
3. **`retrySearch` 讀取 `local.value.vaultOpening` vs `vault.state`**：
   - 審查確認：`SearchViewModel.kt:99` 嚴格檢查 `if (vault.state.value !is VaultState.Ready) return`，避開了 `local.value.vaultOpening`（在 `SearchUiState` 預設為 `true` 且從未被 local 自身更新）的陷阱。
4. **Import `CompletableDeferred.await` vs Joining a Blocking Read / Key Zeroing / Apply after `destroyAll`**：
   - 審查確認：
     - `stageFromSource` 透過 `result.await()` 等待，不 join 阻塞的背景 job。
     - `key` 僅在 `importReads.launch` 內部由 `RecoveryKeyCodec.decode` 產生，並在該 job 的 `finally` 區塊清零（`key.fill(0)`），呼叫端取消時不會提前把使用中的 key 歸零。
     - `keys.destroyAll()` 後 `keyMaterial.epoch` 遞增，在 apply 前與 exclusive 內部均會阻擋並回傳 `KEY_UNAVAILABLE`。
     - 但取消時呼叫者在 `finally` 中同步呼叫 `input.close()` 導致新的阻塞漏洞（見 I1）。
5. **Restore Count `else if` 互斥性**：
   - 審查確認：`BackupService.kt:472` 為 `if (media != null && blob == null)`，`:475` 為 `else if (media == null && m.mediaState == MediaState.LOCAL_COPY.name)`，兩者在邏輯上完全互斥，同一則訊息絕不可能同時計入 `skippedMedia` 與 `mediaNotRestored`。
6. **CI Root `test` 排程驗證**：
   - 審查確認：`:platform:media` 套用 `quietinbox.android.library`，AGP 產生的 `test` task 會依賴 `testDebugUnitTest`。根目錄執行 `test` 時，Gradle 會執行所有 subproject 的 `test` 任務，因此 `:platform:media` 必然被執行。
7. **Test Theatre 檢查**：
   - 審查確認：
     - `SearchViewModelTest.kt` 中所有的 `awaitUntil` 條件（包含 `any { ... }`、`count { ... }`）均帶有 `shouldBe true` 或具體值斷言，沒有任何將純 Boolean 當成表達式而瞬間通過的假測試。
     - `BackupHangTest.kt` 的輪詢使用 `while (entered.count > 0) delay(10)`，未在 `runBlocking` 主執行緒呼叫 `CountDownLatch.await()`。唯獨 `close()` 被實作為空方法（見 M2）。
8. **文檔超前與 Issue #33 宣稱**：
   - 審查確認：`CHANGELOG.md:27` 明確載明「`Issue #33 stays open.`」；未宣稱關閉 #33 或 #17，文檔與代碼現狀完全一致。

---

## 總結與後續修復指引

本次提交在搜尋架構與分頁一致性上的改進非常紮實，但在備份還原的並行與執行緒邊界處理上仍有瑕疵。請針對以下兩點進行修正：

1. **修正 I1**：重構 `stageFromSource`，確保不可信來源的 `InputStream` 其開啟、讀取與關閉全生命週期都在獨立受控的背景工作內進行；呼叫端在取消時僅退出協程，絕不在呼叫端同步呼叫 `input.close()`。
2. **修正 I2**：確保 `openInput`、`freeBytes` 以及 `apply`（特別是走訪 `staged.media` 進行 Base64 解碼、Tink 加密與磁碟寫入）在 `Dispatchers.IO` 上執行，避免主執行緒卡頓與 ANR。
3. **補充測試**：更新 `BackupHangTest`，模擬 `read()` 與 `close()` 共用鎖的阻塞情境，證明在串流 `close()` 亦阻塞時，呼叫端仍能迅速退出且 exclusive 閘門保持暢通。
