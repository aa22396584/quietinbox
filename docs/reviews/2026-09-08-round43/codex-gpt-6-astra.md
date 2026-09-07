COMMENT（無阻擋項）— **是，指定 mutation 會讓具名測試失敗；原版通過。**

審查範圍：`44296e5..c13fdfd`，主要修正為 `5d21f7c`。Critical／High／Medium：0；Low：2 項非阻擋觀察。Round-42 的 Ready mutation 與 diagnostic 比較基準兩項缺口，本輪指定驗證均已通過。

**Ready collector mutation 實測**

完整具名測試：`a Ready that arrives while the lock-out gap write is still in flight does not drop it`。

| 版本 | 實際執行 | 結果 |
| --- | --- | --- |
| `c13fdfd` 原版 | 指定測試 1 項 | 1 成功、0 失敗；程序退出碼 0 |
| 僅將 Ready collector 的 `pipelineMutex.withLock {` 改成 `run {` | 相同測試 1 項 | 0 成功、1 失敗；程序退出碼 1 |

失敗訊息是 `Expected null but actual was true`，精確落在 `platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:1018` 的 `raced shouldBe null`。這是測試本體斷言失敗，兩版均編譯成功。

mutation diff 只有 `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:297` 這一行；Ready 區塊內容與其他鎖均保留。兩版重新編譯完整、未改名的 `CaptureCoordinatorTest.kt`，並重新編譯 coordinator 與 `PolicyChangeException`。測試來源與 `c13fdfd` 完全相同，沒有抽換 Harness 或改寫測試 body。JUnit 各顯示 **77 found／76 skipped／1 started**；載入位置也確認來自各自的暫存編譯輸出。

原因與新斷言一致：writer 停在 `releaseOpen.await()`（測試 `:998`），Ready 的無鎖 collector 在此期間呼叫 `closeOpenGaps`，先設 `settledDuringWrite=true`（`:1003`）。即使後面的 `check` 被產品 `guarded`（產品 `:1429`）吞掉，測試本體仍在放行 writer 前取得 `raced=true` 並失敗。有鎖原版則讓等待逾時，再於測試 `:1019` 放行 writer，後續 gap 與 unlocked 狀態斷言均通過。

**diagnostic fingerprint 比較基準**

`platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRejectionTest.kt:197` 保存 body 更新後的 `afterBody`，`:202` 再要求 diagnostic 插入後的 fingerprint 與它不同，已隔離第二次變化。

依目前 Room schema 與相同欄位編碼執行 SQLite 記憶體探針：body 更新前後 message 列數皆為 1；正常 fingerprint 的兩個斷言均成立。省略 diagnostic 項目時，body 斷言仍成立，但新的 `afterBody` 斷言失敗；舊的 `before` 比較則仍成立。先前 body 改寫掩蓋 diagnostic 缺漏的問題已被新比較方式抓到。

**非阻擋觀察**

- **Low — 固定排程窗口。** 測試 `:1014` 的一秒等待仍依賴 coordinator 的 `Dispatchers.Default`（產品 `:129`）。若 collector 超過一秒才獲排程，mutation 理論上仍可能漏過；本輪沒有重現這種排程，不能把一次 mutation 失敗解讀為所有排程下的保證。日後需要完全可控的交錯時，可使用可控制的測試 dispatcher／scope。
- **Low — 失敗路徑未放行 writer。** 測試 `:1018` 斷言失敗會跳過 `:1019`，留下該 coordinator 的 suspended coroutine。可用 `finally` 保證放行，或在保存 `raced` 後先放行再斷言。此次 mutation 測試程序正常結束，沒有程序卡死；此項不削弱 mutation 辨識力。

獨立審查結果：`code-reviewer` 為 **APPROVE**；`architect` 為 **WATCH（非阻擋）**，因此綜合建議為 **COMMENT**。核心審查為上述兩個測試檔及 Ready collector；沒有產品修正要求。

**驗證紀錄與界限**

- 日期：2026-09-08。目錄：`/Users/iml1s/Documents/mine/quietinbox`；分支 `main`；HEAD 前後均為 `c13fdfd33a0cf4948b039fb7e526b11766b4f127`。
- JVM 使用 JDK 17.0.15、快取 Kotlin 2.4.10 編譯器、Kotest 6.2.4 與現有依賴，直接重新編譯後由 JUnit Platform 執行；兩版各執行一次。沒有把其餘 76 項略過測試算成通過。
- 證據目錄：`/private/tmp/qi-r43-astra-5fsizp79/`。`baseline-test.log`、`no_ready_lock-test.log` 保存結果與載入位置；`ready-lock.patch` 保存唯一 mutation；`config.json` 記錄來源 SHA-256 與 classpath；`commands.json` 保存實際編譯／執行參數；`fingerprint-results.json` 保存 SQL 探針結果。
- 未執行完整 Gradle suite、Android Room／SQLCipher／Tink instrumentation 或 GitHub CI。SQL 探針只驗證比較基準的辨識力。
- repository 產品與測試來源 SHA-256 前後相同。未執行任何 Git 寫入命令；mutation 與驗證產物只在暫存目錄，本輪 repository 唯一寫入為本報告。未重開 PARSE_／DECODE 議題。
