REQUEST CHANGES

Critical：未發現。Important：1 項。Minor：1 項。

**I1（Important）— 移除 Ready collector 的 pipeline 鎖，新測試仍能通過。**

位置：`platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:1003`、`:1010`、`:1011`；產品對應 `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:297`。

本輪從目前來源抽出原樣的 Harness 與完整目標測試 body，重新編譯受審的 coordinator，執行 `a Ready that arrives while the lock-out gap write is still in flight does not drop it`。原版 **1／1 通過**；僅將 Ready collector 的 `pipelineMutex.withLock {` 替換為 `run {`、保留區塊內容及其他鎖，仍然 **1／1 通過**。兩版各執行一次；這證明 mutation 有被放過的排程，未量測其失敗機率。

原因仍在 `:1010–1011`：發布 Ready 後立即放行 `releaseOpen`，沒有等待 collector 的處理進度。寫入端可以先恢復、設定 `openReturned=true` 並完成 lock-out 處理，無鎖 collector 才執行 `closeOpenGaps`。此時新增的 `check`、gap 呼叫次數與最終 `vaultLocked=false` 都會通過。

新增 `check` 確實能辨識「close 發生時 open 尚未返回」；若它觸發，例外會被產品的 `guarded`（`:1429–1435`）吞掉，但也會跳過後面的 `recordGap`，讓測試的 `coVerify` 失敗。缺口在於測試沒有強制走到這個交錯，因此 Round-41 I1 的回歸測試要求仍未關閉。

建議補上可控排程或 collector 進度屏障，讓 Ready 在 `openGap` 仍停住時確實得到執行機會，再放行寫入；以同一個「只移除 Ready 鎖」mutation 必須失敗作驗收。

**M1（Minor）— diagnostic 負對照仍被先前的 body 改寫掩蓋。**

位置：`platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRejectionTest.kt:195–201`。

`local_diagnostic_event` 已正確納入 fingerprint（`:130`），但新增測試先改 body，插入 diagnostic 後卻仍與 body 改寫前的 `before` 比較。即使刪掉 fingerprint 的 diagnostic 項目，兩個 `shouldNotBe before` 都會因 body 已不同而通過。本輪依目前 Room schema 建立的記憶體 SQLite 探針確認了這個反例。這是測試辨識力不足，並非目前 helper 仍漏讀診斷表。

最小修正：在 body 斷言後保存 `afterBody = vaultFingerprint()`，再插入 diagnostic，並與 `afterBody` 比較；或拆成獨立測試。

已確認的修正：

- **Ready 的產品順序已修正。** collector 在 `CaptureCoordinator.kt:297–316` 持有與 writer（`:977`）相同的鎖，writer 的 `vaultLocked=true`（`:1041`）完成後，collector 才於 `:315` 發布 false。Round-41 所指的舊狀態覆寫窗口已由此順序關閉；上述 Important 是測試缺口。
- **指定 body UPDATE 會被抓到。** `BackupRejectionTest.kt:112–117` 讀取並編碼每欄，`:126` 納入 message。`UPDATE message SET body='mutated'` 不改列數，但會改變 fingerprint；拒絕路徑的 `shouldBe before`（`:139`）因此會失敗，新增專用測試的 `shouldNotBe before`（`:197`）則會通過。SQLite 探針確認 message 列數維持 **1 → 1**，body cell 從 `S8:original` 變為 `S7:mutated`。
- **舊編碼碰撞已修正。** `N` 與 `S<長度>:<值>` 可區分 SQL NULL／字面文字 `∅`，長度前綴也保留包含分隔符的欄位邊界。唯讀編碼探針確認舊版的 NULL 與分隔符反例在新版得到不同結果。

驗證紀錄與限制：

- 日期 2026-09-08；目錄 `/Users/iml1s/Documents/mine/quietinbox`；分支 `main`；HEAD 前後均為 `44296e56e735378a8d937d80449c8310392ba866`。範圍為 `a63bc63..44296e5`。
- JVM 驗證使用 JDK 17.0.15、快取 Kotlin 2.4.10 編譯器與現有依賴，重新編譯目前 `CaptureCoordinator`、`PolicyChangeException` 及聚焦測試。測試只更名為獨立 FunSpec；Harness 與目標 body 已逐字核對一致。原版與移除鎖版本均為 **1 found／1 started／1 successful／0 failed**。
- 暫存驗證產物位於 `/private/tmp/qi-r42-astra-3zxck_xf/`；`config.json` 記錄來源 SHA-256 與 classpath，`baseline-test.log`、`no_ready_lock-test.log` 保存本輪執行結果。mutation 僅存在於暫存驗證副本，diff 已確認只有 Ready 區塊的鎖被移除；repository 來源 SHA-256 維持不變。
- SQL 探針讀取 `platform/storage/schemas/dev.quietinbox.platform.storage.db.QuietInboxDatabase/4.json` 的建表語句，在 SQLite `:memory:` 中驗證 body 與 diagnostic。移除 diagnostic 項目後，現有兩個不等於原始快照的斷言均仍成立；改與 body 更新後的快照比較，才會辨識 diagnostic 項目是否缺漏。
- 本輪證據限於聚焦 JVM 測試及 SQLite／編碼探針；未重新執行完整 Gradle suite、Room／SQLCipher／Tink instrumentation 或 GitHub CI。
- 未啟動 orchestration workflow，未執行 Git 寫入命令，未修改產品或 repository 測試；repository 本輪唯一寫入為本報告。依 brief 未重開 PARSE_／DECODE 議題。
