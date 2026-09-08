APPROVE WITH NITS

Round47 I1 可關閉，I2 維持關閉。本次差異未發現阻擋問題；新增回歸測試有一項非阻擋的時序弱點。

範圍為 `git diff 3e8777b..ad1b48e`。已確認 HEAD 為 `ad1b48e`，且兩個 Kotlin 審查檔相對 HEAD 無差異。

關閉條件核對：

- **背景 close 去重成立。** `BackupService.kt:265`、`:356` 均以 `currentImportStream.getAndSet(null)` 原子取出後才呼叫 `abandonClose`。同一次登記的 stream 不會再被後續 import 或取消路徑重複取出。這裡的「一次」指背景 abandonClose 交付；不表示 staging 的 finally 不會再做正常 close。
- **超額重試不持續新增 close。** 兩個掛住的 read 佔住額度，取出的指標已清空；後續重試在 `BackupService.kt:331`–`:334` 以 read slot 拒絕，未進入 openInput 或重新登記 stream。主工作仍在 close 返回後才釋放額度（`:349`–`:350`），因此 round47 所述對同一掛住串流無界排入 close 的路徑已消除。
- **回歸場景已加入。** `BackupHangTest.kt:176`–`:209` 建立兩條持鎖掛住的 read，取消並等待 caller 結束，再做八次匯入，要求全部返回 IO 失敗且 close 計數不變；release 在 teardown。其同步弱點見下段。
- **caller 不同步 close。** `BackupService.kt:324`–`:325` 仍只在獨立 IO scope 啟動 close，取消路徑不等待 close 工作者。
- **apply 仍不在 Main。** `BackupService.kt:287` 的 `withContext(Dispatchers.IO)` 包含 exclusive 與 apply（`:302`）；本次差異未改動此界線。既有 Main 呼叫測試仍保留。

非阻擋建議 — `BackupHangTest.kt:201`–`:209`：

`first.join()`／`second.join()` 只等待 caller，並不等待獨立 scope 的 close 開始。合法的既有 close 可能在 `before = closes.get()` 之後才增加計數，造成修正正確仍偶發失敗；反過來，尚未排程執行的 close 也不會被這個計數觀察到。建議以可觀察的關閉請求計數或明確 barrier 固定基準，再驗證八次拒絕沒有新增請求。兩次匯入目前並行啟動，共用指標可能被覆寫，也不宜直接假設基準必為兩次 close；若要驗證兩個 close 均已開始，應依序啟動、等待 read、取消並等待 close 入口，再建立第二條。本項不推翻原子取出對本輪 I1 的修正。

驗證證據與界線：

- 本次完成差異、控制流程與測試原始碼審查，未重跑 Gradle 或裝置測試。
- 已讀取既有 `platform/backup/build/outputs/androidTest-results/connected/debug/TEST-QuietInbox_Phone(AVD) - 16.xml`：裝置 `emulator-5556`，時間 `2026-09-08T04:26:42`，BackupHangTest 四項測試、零失敗、零錯誤、零跳過，包含新增重試測試。XML 本身不記錄 commit；「此 commit 後 BUILD SUCCESSFUL」依 BRIEF 提供，未冒充本次獨立執行證據。
- 唯一寫入為本報告；未修改產品程式碼，未執行 Git 寫入、Play 或 orchestration workflow。
