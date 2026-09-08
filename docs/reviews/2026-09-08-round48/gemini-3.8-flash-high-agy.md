# Round 48 審查報告：備份掛起串流去重與非同步關閉 (I1) 複審

- **審查目標**：針對 Round 47 審查所保留之重要缺陷（I1：`abandonClose(currentImportStream.get())` 缺乏清零去重，重試匯入將無界累積卡死協程）的修復進行獨立、嚴格之代碼審查與驗證。
- **審查範圍**：`git diff 3e8777b..ad1b48e`（單一 commit `ad1b48e`，基準為 `3e8777b`）。
- **審查模式**：READ-ONLY（僅撰寫本審查報告；未修改任何產品程式碼、未執行任何 git 寫入指令、未連網、未更動 orchestration 狀態）。
- **審查路徑**：`/Users/iml1s/Documents/mine/quietinbox`，HEAD `ad1b48e`。

---

## 審查結論 (Verdict)

# **APPROVE**

- **Critical Findings**：0
- **Important Findings**：0
- **Minor Findings**：0
- **Observations / Nits**：1（O1：`BackupHangTest` 取樣 `before` 計數基準前僅等待 caller join，存在極微小之調度時序弱點，但不阻擋放行）

### 總結判定：
在本次提交 `ad1b48e` 中，開發團隊精準且徹底地修閉了 Round 47 的唯一殘留缺陷 **I1**：
1. **I1 徹底閉合 (CLOSED)**：在 `BackupService.kt` 的 `import()` 進入點與 `stageFromSource()` 取消攔截區塊中，均改採 `currentImportStream.getAndSet(null)?.let { abandonClose(it) }` 進行原子取出。同一個串流至多僅會交付一次背景關閉；卡死重試時，指標已被清空，後續匯入在 `liveImportReads` 配額處（read slot）直接被擋下，不再發起無謂且阻塞的 `abandonClose`。
2. **I2 維持良好閉合 (STILL CLOSED)**：`openInput` 仍於 `importReads.launch`（`Dispatchers.IO`）中執行，`apply` 與 `exclusive` 維持包覆於 `withContext(Dispatchers.IO)`，完全隔離主執行緒。
3. **回歸測試與文件同步齊備 (PASS)**：`BackupHangTest` 新增之 `retriesDoNotEnqueueAnotherBlockedCloseOnTheSameStream` 真實覆蓋兩條卡死串流取消後連續 8 次重試情境，且 `docs/TEST_MATRIX.md` 與 `docs/zh-Hant/TEST_MATRIX.md` 計數同步精準更新為 4。

因此，本輪判定為 **APPROVE**。

---

## 針對 BRIEF 核心條件之逐項檢驗

### 1. `getAndSet(null)` 確保每個串流至多僅交付一次背景關閉 (PASS)
- **檔案與行號**：
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:265`（`import` 進入點）
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:356`（`stageFromSource` 捕捉 `CancellationException`）
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:320-326`（`abandonClose` 定義）
- **機制驗證**：
  - 在 `BackupService.kt` 中，`currentImportStream` 為 `AtomicReference<InputStream>`。
  - 在前次提交 `3e8777b` 中，程式使用 `currentImportStream.get()`，導致串流若在 `read()` 中持鎖卡死，該指標永不為 null，任何後續取消或重試都會反覆取出同一串流並派發新的 `close()` 協程。
  - 提交 `ad1b48e` 將兩處呼叫點全部替換為：
    ```kotlin
    currentImportStream.getAndSet(null)?.let { abandonClose(it) }
    ```
  - 透過 `getAndSet(null)` 的原子所有權轉移（atomic transfer），取出的串流立即自指標中脫鉤。任何後續的重試或多重取消操作只會讀到 `null`，絕不可能對同一個實體串流重複發起第二次 `abandonClose`。
  - 同時，`abandonClose(stream: InputStream)` 的簽名由可空改為非空，並在 KDoc 明確載明合約：「Callers must pass a stream they have already taken off [currentImportStream] so each stream is closed at most once from here; retries must not enqueue another blocked close.」
  - 協程正常退出時，`finally` 區塊中之 `currentImportStream.compareAndSet(input, null)` 即使遇到已被 `getAndSet(null)` 取走之狀態，CAS 亦安全失效，不會產生任何競爭異常。

### 2. 超額匯入於 read slot 處直接拒絕，且不再發起額外 close (PASS)
- **檔案與行號**：
  - `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:331-335`
- **機制驗證**：
  - 當金庫存在 2 條卡死之串流佔滿 `MAX_LIVE_IMPORT_READS`（配額為 2）時：
    1. 外部呼叫 `service.import()`，Line 265 執行 `currentImportStream.getAndSet(null)`，此時指標已為 `null`，不觸發 `abandonClose`。
    2. 進入 `stageFromSource()` 啟動工作協程，Line 331 檢查 `if (liveImportReads.incrementAndGet() > MAX_LIVE_IMPORT_READS)` 成立（計數由 2 增為 3）。
    3. 立即呼叫 `liveImportReads.decrementAndGet()`（計數扣回 2），並透過 `result.completeExceptionally(StagingException(BackupResult.Reason.IO, "read slot"))` 立即終止工作協程。
    4. 工作協程未執行 `openInput(source)`，亦未設定 `currentImportStream`。
    5. 呼叫端捕捉到 `StagingException`，於 Line 278 返回 `BackupResult.Failed(BackupResult.Reason.IO, "read slot")`。
  - 整趟重試路徑未曾開啟任何新串流、未曾向共用池派發任何關閉工作，完美符合有界保護原則。

### 3. 回歸測試驗證：兩條卡死串流、取消、八次重試、關閉計數不變 (PASS)
- **檔案與行號**：
  - `platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupHangTest.kt:176-211`
- **測試邏輯分析**：
  - 新增測試 `retriesDoNotEnqueueAnotherBlockedCloseOnTheSameStream`：
    ```kotlin
    @Test
    fun retriesDoNotEnqueueAnotherBlockedCloseOnTheSameStream() = runBlocking {
        val entered = CountDownLatch(2)
        val closes = AtomicInteger(0)
        fun hung(): InputStream {
            val lock = Any()
            return object : InputStream() {
                override fun read(): Int {
                    synchronized(lock) {
                        entered.countDown()
                        releaseHung.await()
                        return -1
                    }
                }
                override fun close() {
                    closes.incrementAndGet()
                    synchronized(lock) { }
                }
            }
        }
        service.openInput = { hung() }
        val first = launch(Dispatchers.IO) { service.import(Uri.parse("content://quietinbox.test/hang"), recoveryKey) }
        val second = launch(Dispatchers.IO) { service.import(Uri.parse("content://quietinbox.test/hang"), recoveryKey) }
        withTimeout(5_000) { while (entered.count > 0) delay(10) }
        first.cancel()
        second.cancel()
        withTimeout(5_000) { first.join(); second.join() }
        val before = closes.get()
        repeat(8) {
            withTimeout(5_000) {
                service.import(Uri.parse("content://quietinbox.test/hang"), recoveryKey)
                    .shouldBeInstanceOf<BackupResult.Failed>().reason shouldBe BackupResult.Reason.IO
            }
        }
        closes.get() shouldBe before
        Unit
    }
    ```
  - 測試精確覆蓋了：
    - 兩條真實卡在 `read()`（持鎖狀態）之串流。
    - 呼叫端執行 `cancel()` 並確認 caller 順利退出。
    - 隨後執行 8 次重試匯入，全數被快速判定為 `Reason.IO` 失敗。
    - 斷言重試後的 `closes.get()` 嚴格等於重試前的 `before` 計數。
  - 測試方法結束於 `Unit`，符合 JUnit 4 / Kotest instrumented test 之簽名規範。

### 4. 呼叫端維持非同步關閉，apply 絕不落在 Main 執行緒 (PASS)
- **機制驗證**：
  - `abandonClose` 維持於 `importReads.launch`（`Dispatchers.IO`）背景排程，取消路徑從未同步等待 `stream.close()`。
  - `openInput`、`readAndStage` 均由 `importReads.launch` 調度。
  - `apply` 連同 `freeBytes()` 與 `maintenance.exclusive` 全程受 `withContext(Dispatchers.IO)` 包覆（Line 287-304）。
  - 既有測試 `openAndApplyDoNotRunOnMain`（Line 147-173）持續在真實環境中驗證 `openOn` 與 `applyOn` 執行緒名稱皆非 `"main"`。

---

## 驗證證據（Verification Evidence）

- **真實裝置測試執行報告**：
  - 檔案：`platform/backup/build/outputs/androidTest-results/connected/debug/TEST-QuietInbox_Phone(AVD) - 16.xml`
  - 設備：`emulator-5556`
  - 時間戳記：`2026-09-08T04:26:42`
  - 結果：**4 tests, 0 failures, 0 errors, 0 skipped**（總耗時 22.607 秒）
    - `cancellingANeverReturningReadReleasesTheCallerAndDoesNotHoldExclusive`（5.487s）: PASS
    - `retriesDoNotEnqueueAnotherBlockedCloseOnTheSameStream`（3.722s）: PASS（新增回歸測試）
    - `aWriteAfterKeyEpochChangeDoesNotLand`（6.015s）: PASS
    - `openAndApplyDoNotRunOnMain`（7.383s）: PASS

- **文件精準度查核（Docs Integrity）**：
  - `docs/TEST_MATRIX.md:120` 與 `docs/zh-Hant/TEST_MATRIX.md:120` 中 `BackupHangTest` 計數均自 3 個準確遞增為 4 個，並補上第四個測試項目的中文描述。完全符合 CLAUDE.md 關於「文件不得超前亦不得落後代碼」之要求。

---

## 觀察與改進建議 (Observations / Nits)

### O1 (Nit) — `BackupHangTest.kt:201-209` 取樣基準之協程排程微小邊界
- **細節**：
  在 `first.join(); second.join()` 之後，測試直接以 `val before = closes.get()` 取樣基準。`join()` 僅保證了呼叫端 `import()` 協程結束，但 `abandonClose` 是在 `importReads` 獨立協程池中非同步執行。若在極度極端的執行緒飢餓環境下，背景關閉協程若尚未執行到 `closes.incrementAndGet()`，`before` 可能在 close 發生前被取樣，隨後在 `repeat(8)` 期間才遞增，導致測試假性不符。
- **評估**：
  在當前 AVD 環境下 5 秒的緩衝與排程已足以讓背景 close 順利進入並阻塞在 lock 上（真機測試 3.722s 全數通過）。此現象屬純測試同步之細節考量，不影響生產代碼修復的正確性，評為 Non-blocking Nit。

---

## 最終結論

提交 `ad1b48e` 完整落實了原子指標清零、關閉去重、槽位拒絕與回歸驗證，徹底消除無界協程積累之隱患。
**Verdict: APPROVE**
