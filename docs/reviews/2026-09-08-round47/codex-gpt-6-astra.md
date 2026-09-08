REQUEST CHANGES

I2 可關閉；I1 的同步 close 與 slot 提早釋放已修正，但新增的獨立 close 工作仍可無界累積，因此 I1 尚未完整關閉。

## Critical

無已確認問題。

## Important

### I1 — P1：背景 close 未受限額保護，重試仍可耗盡共用 IO 工作容量

位置：`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:320`（第 322 行）；呼叫點同檔 `:265`、`:353`，slot 檢查在 `:328`。

`abandonClose` 每次都在共用 `Dispatchers.IO` 上 launch 一個直接執行 `stream.close()` 的工作，沒有去重、額度或單一所有者控制。`liveImportReads` 只計算 staging 工作者，沒有計算這些 close 工作者。尤其每次 import 都先呼叫 abandonClose，之後才由另一個背景工作檢查 read slot；即使已超額而拒絕開啟新 stream，close 工作仍已建立。

可重現序列：

1. 第一個匯入使用本輪測試的共用鎖 stream，read 持鎖等待；取消 caller，背景 close 開始等同一把鎖。
2. 第二個匯入也停在 read，兩個 staging slot 都被佔住，`currentImportStream` 指向第二個 stream。
3. 持續重試 import。每次第 265 行再排入一次對第二個 stream 的 close；第 328 行即使拒絕該次 staging，也不會撤銷或限制已排入的 close。
4. 原來的 read 未返回，指標不會由 finally 清除；多個 close 因此持續阻塞在同一把鎖上。

這不表示 IO dispatcher 會建立無限實體執行緒；問題是本服務沒有約束 close 工作的數量，足以佔滿共用 IO 執行容量並累積待執行工作。其他 IO 工作和負責回覆「read slot」的 coroutine 都可能無法取得執行機會。單次 cancel 能退出、exclusive 沒被 read 持有，不能排除此資源耗盡路徑。

本輪確實把主 staging 工作者的 slot 釋放移到 close 之後（`:346`、`:347`），也把 slot 取得移到 openInput 之前；這兩項修正成立。然而 round46 I1 明確要求避免「另開無上限的 close 工作」，此缺口仍直接屬於 I1。

建議：以每次匯入的工作物件管理 stream 與關閉狀態，使背景關閉請求去重，並對所有可能阻塞的 close 工作提供明確上限；超額匯入不得再為既有 stream 建立額外 close 工作者。保留 caller 不等待 provider 工作，以及清理實際結束後才釋放 slot 的性質。

必要回歸：保留共用鎖測試，另以可釋放 latch 卡住兩個 read／close，反覆提出超額匯入，確認 provider open 與 close 工作者數量維持固定上限、拒絕仍可返回，最後釋放 latch 並確認額度恢復。現有 `BackupHangTest.kt:92` 只執行一次匯入與取消，無法捕捉上述累積。

信心：高；由新增 launch 與 slot 檢查的先後順序直接確認。本次未在裝置上製造 IO 飽和。

## Minor

無另列的阻擋問題。

## 關閉條件核對

- **I1／caller 不同步 close：通過。** `BackupService.kt:265`、`:353` 都只排入獨立 scope 的工作；等待者取消後重新拋出取消，不 join provider 工作者。
- **I1／共用鎖 read 不阻止取消與 exclusive：測試形狀符合。** `BackupHangTest.kt:97`、`:104` 的 read／close 使用同一把鎖；`:114`–`:117` 在 releaseHung 尚未釋放時驗證 cancel/join 及另一個 exclusive。release 在 teardown。
- **I1／slot 持有至 close 完成：主 staging 路徑通過，整體有界性未通過。** `BackupService.kt:328` 先取得額度，`:336` 才 open；`:346` close 返回後 `:347` 才扣回額度。獨立 close 工作繞過此限制，見上方 I1。
- **I2／openInput 不在 Main：通過。** `BackupService.kt:96` 的獨立 scope 使用 IO，`:336` 在該 scope 內呼叫 openInput。沒有把不可合作的 open/read 放回 caller 必須等待結束的 structured child。
- **I2／apply 不在 Main：通過。** `BackupService.kt:287` 明確切到 IO，涵蓋 free-space 查詢、exclusive 與 apply；exclusive 內仍保留 token／epoch 重檢（`:294`、`:295`）。`VaultMaintenance.exclusive` 沒有切換 dispatcher，因此 `:407` Base64 與 `:415` encryptToFile 仍在 IO context 下執行。
- **I2／Main 呼叫測試：符合本輪要求。** `BackupHangTest.kt:159`–`:170` 從 Main 呼叫 import，記錄 openInput 和 apply 入口執行緒並排除 main。測試使用文字資料，未實際走媒體加密／寫檔分支；該分支的 dispatcher 判定另由上述原始碼控制流程支持，不宣稱測試有直接記錄媒體寫入執行緒。

## 範圍與驗證證據

- 已確認 HEAD 為 `3e8777b27dfd33bdee4b1df4d39e03c974368f1f`；審查完整 `git diff eff84c0..3e8777b`，並閱讀 round46 I1／I2 報告、Settings 呼叫端、maintenance 與 apply 相關實作。
- 已以唯讀 `git diff --quiet 3e8777b -- <BackupService.kt> <BackupHangTest.kt>` 確認兩個主要審查檔與指定 HEAD 一致，exit code 0。
- 本次執行的是原始碼與控制流程審查，未重跑 Gradle 或 instrumented tests。BRIEF 提供的 emulator-5556 上 BackupHangTest 三項與完整 backup connected tests 成功，屬既有證據，不冒充本次獨立執行結果；這些測試也沒有涵蓋上述多次 close 累積序列。
- 未修改產品程式碼、未執行 git 寫入、Play 或 orchestration workflow。唯一寫入檔案為本報告。
- 未延伸處理 #17、#33、F1–F4、INTERNET、0.1.5 或搜尋 M1。
