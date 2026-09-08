# Round 47 審查報告：備份掛起非同步關閉 (I1) 與背景執行緒隔離 (I2) 複審

- **審查目標**：針對 Round 46 審查所判定 REQUEST CHANGES 之兩項重要缺陷（I1：取消匯入同步呼叫 close 導致 caller-exit 破裂；I2：openInput 與 apply 退化至 UI 主執行緒）的修復進行獨立、嚴格之代碼審查與驗證。
- **審查範圍**：`git diff eff84c0..3e8777b`（單一 commit `3e8777b`，基準為 `eff84c0`）。
- **審查模式**：READ-ONLY（未修改任何產品代碼、未執行任何 git write 命令、未連網、未操作實體裝置）。
- **審查路徑**：`/Users/iml1s/Documents/mine/quietinbox`，HEAD `3e8777b`。

---

## 審查結論 (Verdict)

### **REQUEST CHANGES**

- **Critical Findings**：0
- **Important Findings**：1（I1：背景 `abandonClose` 未受配額限制且缺乏指標清零與去重，重複重試將無界累積卡死之協程耗盡 IO 資源）
- **Minor Findings**：0
- **Observations**：2（O1：I2 已徹底閉合，`openInput`、`apply`、Base64 與 Tink 加密寫檔完全脫離 Main 執行緒；O2：`BackupHangTest` 實作共用鎖假樁，確實證明單次 cancel 可解除呼叫端並釋放 exclusive）

**總結判定**：
在本次提交 `3e8777b` 中，開發團隊成功解決了 **I2**（主執行緒退化問題），並對 **I1** 進行了部分修正：
1. **I2 已完整閉合 (PASS)**：`openInput` 移入 `importReads.launch`（`Dispatchers.IO`），且 `freeBytes()`、`maintenance.exclusive` 與 `apply`（Base64、Tink 加密寫檔、Room 交易）整體由 `withContext(Dispatchers.IO)` 包覆。新增的 `openAndApplyDoNotRunOnMain` 測試透過 probe 探針確實證明無任何操作在 Main 執行緒上執行。
2. **I1 未完整閉合 (PARTIALLY CLOSED / REMAINS OPEN)**：
   - 開發團隊確實將呼叫者執行緒上的同步 `input.close()` 移除，改由 `abandonClose` 於背景協程執行，使得單次取消呼叫端能在 5 秒內返回，且 `maintenance.exclusive` 維持暢通；工作者協程內的 `liveImportReads.decrementAndGet()` 也修正為在 `input?.close()` 結束後才扣除。
   - **然而，新增的 `abandonClose` 引入了無界協程累積漏洞**：
     - `abandonClose(currentImportStream.get())` 僅使用 `.get()` 讀取，**從未將指標清零（未執行 `getAndSet(null)`）**。
     - 當串流因 `read()` 卡死時，工作者協程永遠無法到達 `finally`，因此 `currentImportStream.compareAndSet(input, null)` 永遠不會執行，`currentImportStream` 將永久持有該卡死串流。
     - 呼叫端在取消時（Line 353）會對該串流呼叫一次 `abandonClose`；若使用者或 UI 再次嘗試 `import()`，Line 265 會**再次**對同一個卡死串流呼叫 `abandonClose`。
     - 每次 `abandonClose` 都直接在 `importReads` 發起一個新的協程執行 `stream.close()`，完全**繞過 `liveImportReads` 配額檢查**。即使後續的 staging 因超過 `MAX_LIVE_IMPORT_READS` 而被拒絕，該次呼叫已然在背景派發了一支永遠卡在 `close()` 監視器鎖上的協程。
     - 每次重試匯入都會額外派發一支卡死的 close 協程，導致共用 `Dispatchers.IO` 上的阻塞協程無界累積，足以耗盡線程池執行容量並阻礙其他 IO 任務。這直接違反了 Round 46 審查所明確要求的「避免另開無上限的 close 工作取代目前問題」。

因此，本輪判定為 **REQUEST CHANGES**，必須修復 I1 的背景 close 累積與去重問題後始得放行。

---

## Findings 清單

### Critical
無。

### Important

#### I1 — P1：背景 `abandonClose` 未受配額限制且缺乏清零去重，重試匯入將無界累積卡死協程
- **檔案與行號**：
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:320-323`（`abandonClose` 定義）
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:265`（`import` 開頭呼叫）
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:352-355`（`stageFromSource` 捕捉 `CancellationException`）
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:328`（`liveImportReads` 檢查位置）
  - `platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupHangTest.kt:92-118`
- **問題分析**：
  1. **指標未清空且無去重機制**：
     在 `BackupService.kt` 中：
     ```kotlin
     suspend fun import(source: Uri, recoveryKeyText: String): BackupResult {
         val token = importSeq.incrementAndGet()
         abandonClose(currentImportStream.get()) // <-- 使用 get() 而非 getAndSet(null)
         ...
     ```
     以及：
     ```kotlin
     private suspend fun stageFromSource(source: Uri, recoveryKeyText: String): Staged {
         ...
         try {
             return result.await()
         } catch (e: CancellationException) {
             abandonClose(currentImportStream.get()) // <-- 同樣只讀取未清除
             throw e
         }
     }
     ```
     `abandonClose` 實作如下：
     ```kotlin
     private fun abandonClose(stream: InputStream?) {
         if (stream == null) return
         importReads.launch { runCatching { stream.close() } }
     }
     ```
  2. **卡死串流永不清空，重試無限派發阻塞任務**：
     - 假設第一個匯入的 provider 串流在 `read()` 與 `close()` 共用同一把內部鎖（如本輪 `BackupHangTest` 所示）。
     - 當 `read()` 陷入永久阻塞時，背景工作者停留在 `readAndStage(input, key)`，**永遠無法執行到 worker 的 `finally` 區塊**。因此，Line 345 的 `currentImportStream.compareAndSet(input, null)` **永遠不會被執行**。
     - 呼叫端取消等待，Line 353 呼叫 `abandonClose(currentImportStream.get())`，派發了協程 #1 去執行 `stream.close()`，協程 #1 隨即阻塞在同一把鎖上。
     - 此時 `currentImportStream` 依然持有該卡死串流。
     - 若使用者在 UI 再次點擊「匯入」，Line 265 再次呼叫 `abandonClose(currentImportStream.get())`，派發了協程 #2 去執行同一串流的 `close()`，協程 #2 同樣阻塞。
     - 接著第二個匯入啟動 `stageFromSource`，開啟串流 #2 並將 `currentImportStream` 指向串流 #2。若串流 #2 也同樣卡死在 `read()`，則 `currentImportStream` 永久持有串流 #2，此時 `liveImportReads` 達到 2（配額上限）。
     - 若使用者繼續點擊匯入或發起重試：
       - Line 265 立即呼叫 `abandonClose(currentImportStream.get())`，派發協程 #3 嘗試關閉串流 #2，協程 #3 陷入阻塞。
       - 隨後 `stageFromSource` 啟動，在 Line 328 檢測到 `liveImportReads.incrementAndGet() > MAX_LIVE_IMPORT_READS`，扣回計數並回傳失敗。
       - **但是！Line 265 所派發的協程 #3 已經產生，且絕不會被撤銷！**
       - 若使用者重試 N 次，就會在共用 `Dispatchers.IO` 上堆積 N 個永遠阻塞在 `close()` 鎖上的協程！
  3. **違反有界保護與 Round 46 審查要求**：
     - `liveImportReads` 僅約束了 `readAndStage` 工作者的數量，完全沒有約束 `abandonClose` 的派發數量。
     - 雖然協程本身很輕量，但 `stream.close()` 是同步阻塞操作，會佔用底層實體執行緒（`Dispatchers.IO` 預設上限 64 個執行緒）。無界累積卡死的 `close()` 工作將逐步蠶食並佔滿共用 IO 執行緒池，導致金庫其他正常的磁碟 I/O、檔案操作，甚至連回覆 `read slot` 失敗的協程調度都受到延遲或餓死。
     - 這直接違背了 Round 46 報告中對 I1 的明確要求：「避免另開無上限的 close 工作取代目前問題」、「所有不可信串流的操作必須完全限制於有明確配額上限的背景 IO 執行緒中」。
- **修復建議**：
  1. **指標原子清空（Single Ownership / Transfer）**：
     若要對前一次的串流發起關閉，必須使用 `currentImportStream.getAndSet(null)?.let { ... }`，確保同一個串流絕不可能被重複派發多個 `abandonClose` 協程。
  2. **避免在超額拒絕前先派發無效 close**：
     Line 265 不應盲目在每次 `import()` 進入時無條件派發背景關閉。應由每次匯入任務自身的生命週期管理其串流，或在確認取得合法 staging slot 之後，才處置前一個已棄置之串流。
  3. **Close 工作必須納入配額限制或去重保護**：
     對可能阻塞的關閉操作進行總量約束，或為串流封裝一個帶有 `AtomicBoolean` 關閉防重的包裝器，確保任何串流至多只有一個背景關閉任務在執行。

---

### Minor
無新增項目。

---

## 審查項核對與驗證結果 (Closed if true Audit)

### 1. I1 — 取消匯入不可同步 close 串流，caller-exit 與 exclusive 保證 (部分通過 / 存在累積缺陷)
- **已達標部分**：
  - 呼叫端協程徹底移除了 `finally { input.close() }`，捕捉 `CancellationException` 後非同步發起 `abandonClose` 並立即重拋退出。
  - `BackupHangTest` 假樁使用 `synchronized(lock)` 讓 `read` 與 `close` 共用監視器鎖，驗證 `job.cancel()` 配合 `withTimeout(5_000) { job.join() }` 可順利在 5 秒內退出，且另一個 `maintenance.exclusive { 9 }` 順利執行，證實 caller 不再被不可信串流同步卡死，exclusive 亦無被佔用。
  - 工作者協程內的 `liveImportReads.decrementAndGet()` 已正確後置於 `runCatching { input?.close() }` 之後，單一工作者的 slot 在 close 完成前維持佔用。
- **未達標部分**：
  - 如 Important I1 所述，`abandonClose` 缺乏去重保護與配額控制，`currentImportStream` 未原子清空，重試匯入將無界累積卡死協程，使整體有界性保證失效。

### 2. I2 — `openInput` 與 `apply` 不得在 Main 執行緒上執行 (完全通過)
- **已達標部分**：
  - `openInput(source)` 移至 `importReads.launch` 內部執行，綁定 `Dispatchers.IO`，不再由呼叫端 Main 執行緒發起 ContentResolver IPC 開啟描述符。
  - `import()` 在 staging 結束後，後續的磁碟可用空間檢查 `freeBytes()`、排他鎖 `maintenance.exclusive` 以及包含 Base64 解碼、Tink AEAD 加密寫檔（`blobCipher.encryptToFile`）、Room Transaction 的 `apply` 流程，全部明確包覆在 `return withContext(Dispatchers.IO) { ... }` 內。
  - 新增測試 `BackupHangTest.openAndApplyDoNotRunOnMain()`：在 `openInput` 與 `applyThreadProbe` 注入執行緒名稱探針，從 `Dispatchers.Main` 發起 `service.import()`，實機驗證斷言 `openOn.get()!!.startsWith("main") shouldBe false` 與 `applyOn.get()!!.startsWith("main") shouldBe false` 全數通過。I2 完全閉合。

---

## 邊界條件與代碼健全性核對 (Deep Audit)

1. **取消例外傳遞完整性**：
   - `stageFromSource` 在 `catch (e: CancellationException)` 中派發非同步關閉後重拋 `throw e`。
   - `import()` 在 Line 270、Line 280 及 Line 299 均明確重拋 `CancellationException`。
   - `apply()` 在 Line 550 於 catch 區塊清理未提交檔案後亦明確 `if (e is CancellationException) throw e`。未吞噬任何協程取消例外。
2. **金鑰抹除時機**：
   - 復原金鑰解碼後的 `key: ByteArray?` 在 `importReads.launch` 內使用，並在工作者 `finally` 區塊中 `key?.fill(0)`，抹除時機恰當。
3. **Double-check Token 與 Epoch**：
   - 進入 `maintenance.exclusive` 之前與內部均分別比對了 `importSeq` 與 `keyMaterial.epoch`，有效防止 staging 與 apply 之間金鑰輪替或並發重設所引發的狀態錯亂。
4. **測試與文檔一致性**：
   - `TEST_MATRIX.md`（英文）與 `docs/zh-Hant/TEST_MATRIX.md`（繁中）均精確將 `BackupHangTest` 筆數由 (2) 修正為 (3)，描述與代碼完全相符。
   - `CHANGELOG.md` 準確記錄了 I1 與 I2 的變更內容，未超前宣稱未關閉的 Issue #17 或 #33。
   - 執行 `python3 tools/check-strings.py`，字串檢查通過（`OK: 0 error(s), 0 warning(s)`）。

---

## 總結與後續修復指引

本次提交在 I2 的執行緒隔離上表現完美，在 I1 的 caller-exit 與 slot 持續佔用上也走在正確的道路上。然而，直接使用未受配額限制且未清空指標的 `abandonClose` 取代同步關閉，開啟了新的無界協程累積後門。

請依下列指引進行修正：
1. **清空指標以去重**：將 `currentImportStream.get()` 改為 `currentImportStream.getAndSet(null)`，確保同一串流僅被非同步關閉一次。
2. **有界控制**：確保背景關閉工作不繞過配額；或對超額被拒絕的匯入請求，不得再次派發背景 close 協程。
3. **測試補強**：在 `BackupHangTest` 中增加多次重試卡住匯入的測試案例，驗證背景工作者數量維持固定上限。
