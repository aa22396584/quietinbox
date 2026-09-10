# QuietInbox PR #34 — 306a011 複驗

## 固定版本與範圍

- PR：https://github.com/ImL1s/quietinbox/pull/34
- 實際從 GitHub 讀到的 head：`306a011465d8aeedce88e25dda4fab0974318aaa`
- 分支：`review/vault-expiry-backup-abort`
- 比較基準：`d73fe1a2887fb347b2d366caad3ea90ee718c74c`
- 提交：`fix(backup): one close owner, register staging before cancellable return`
- PR 狀態：OPEN、未合併。

本次是兩個 P2 的修正複驗，以及對相關取消／資源交接出口的追查；不是重新驗收整個專案。

## 判定

| 項目 | 判定 | 證據層級 |
|---|---|---|
| 同一 stream 的重複 close 不得提早釋放配額 | PASS | 指定 SHA 程式、Android 測試本體、本地 OnceClose 檢查／配額模型 |
| 可取消回傳不得遺失加密暫存檔擁有權 | PASS | 指定 SHA 程式、Android 測試本體、本地真實 withContext 取消與檔案模型 |
| 新 SHA 完整 CI | 尚未完成 | Run 34381608087 最後讀取仍 in_progress；不是 failure |

本輪範圍內未確認新的 P1 或 P2。這份 PASS 是對上述修正的判定，不表示尚未完成的 Android CI 已通過。

## 1. 單一 close 擁有者

來源：`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt`。

`OnceClose` 用 AtomicBoolean.compareAndSet 決定唯一真正執行 close 的呼叫者，done latch 只在該次 close 結束的 finally 釋放。後續 start 是 no-op，但 await 仍等同一個 done，因此「第二次呼叫返回」不再等於「第一個 close 完成」。

取消端取走 OnceClose 後只在 IO scope 呼叫 start；worker 的 finally 對相同物件 await，再遞減 liveExportWrites。import 也改成保存 OnceClose、在 finally await。沒有把等待搬到 UI 或金庫 exclusive。

新增 Android 案例：
`BackupHangTest.aSecondIdempotentCloseDoesNotReleaseTheWriteSlotBeforeTheFirstCloseFinishes`

案例使用第一次 close 卡住、後續 close 直接返回的 stream，在 write 停住時只取消 waiter，沒有呼叫全域 abort。兩個清理占槽時第三次被拒；釋放一個後能再開啟一個輸出。既有 abandoned-close 與 late-open 案例沒有移除。

本地執行：複製相同 OnceClose 方法，在多個 start／await 呼叫下驗證真正 close 只執行一次；兩個槽被占用時拒絕下一次；釋放一個後恰能容納一個，全部釋放後槽數歸零。另驗證 worker 自己執行 close 拋 IOException 時仍能收到例外，不會直接變成成功。

## 2. 暫存檔在跨越可取消回傳前登記

`writeStagingFile(op, ...)` 在 IO 區塊內寫完並關閉加密檔後，先 `op.stagingFile.set(staging)`，才回傳 StagingWrite.Ok。外層 export 在 finally 取得 `takeStaging(op)` 並清理，因此成功值被取消丟棄時仍能找到未移交檔案。

dest-copy worker 在 exportIo 鎖內檢查 cancelled／generation 並以 getAndSet(null) 取得擁有權。已移交的檔案由 worker 自己的 finally 刪除；caller 的 finally 與 abort 掃描不會再從 op 取得該檔案。

新增 Android 案例：
- `BackupHangTest.cancellingAfterEncryptedStagingIsReadyDoesNotLeaveAStagingFile`
- `SettingsBackupAbortTest.settingsStopAfterEncryptedStagingIsReadyDoesNotLeaveAStagingFile`

這兩個案例使用真正的加密備份生成程序，核對生成檔存在後分別用 Job.cancel 與 vm.abortBackup 停止，最後沒有新增 backup-*.qibk；目的地零寫入或未建立。

測試位置精確區別：Android afterEncryptedStagingReady 鉤子位於內層 withContext 已返回、writeStagingFile 尚未返給 export 的位置。它驗證交接前清理；本地另外以可控制的 caller dispatcher 測到 IO 已完成、withContext 回傳尚未被 caller 接收的 prompt-cancellation 窗口。新註冊模型無殘留；移除註冊的負對照留下 1 個檔案，符合上一輪缺陷形狀。另有交接後 caller finally／abort 不得刪除 worker 持有檔案的對照。

## 3. CI 觀測，不沿用旧 SHA

Run：https://github.com/ImL1s/quietinbox/actions/runs/34381608087

GitHub 返回的 head_sha 與本次固定版本一致。最後取得的狀態：

| Lane | 狀態 |
|---|---|
| JVM | success；已下載 artifact 核對 302 tests、0 failures、0 skipped |
| Assemble + permission gate | in_progress；字串檢查 success，建置進行中，權限 gate 尚未執行完 |
| API 29 | in_progress；尚未取得可下載 Android 報告 |
| API 35 | in_progress；尚未取得可下載 Android 報告 |

已下載的 JVM artifact ID：10116318155。
SHA-256：`1292e465af5d98538c6d37a409ef6cb59c69cd351fd898e7b36be98a4dc17c38`，與 GitHub API digest 相符。

只統計每個 task 的 root index.html，沒有把 class reports 重複加入。18 個 task reports 合計 302 個測試。

使用者回報 emulator-5556 backup connected 39/0（Hang21、Settings4、Cancellation3、Rejection7、RoundTrip4）。這是使用者提供的本地結果；本次未取得該執行的原始報告，不把它冒充為已下載的新 CI Android 結果。

## 4. 本地執行與限制

`harness/OwnershipChecks.kt`：8 種情境各 3 輪，24 個預期斷言成立，其中包含 3 次預期重現舊殘留檔的負對照；不是 24 個 Android 測試。

OnceClose 類別從本次 GitHub connector 讀到的程式逐字複製（改為獨立檔案中的類別）；其餘為縮減資源擁有權模型。暫存內容是合成 bytes，沒有聲稱是 Tink 密文。withContext 測試使用真正 coroutine dispatcher，不是用普通 throw 假裝取消。

環境：Kotlin 1.9.0、Java 21；coroutine JAR 是容器隨 Kotlin 安裝的版本，沒有宣稱與 App 依賴完全一致。完整資訊與 JAR digest 見 results/environment.txt。

本次沒有在此重跑 Gradle、安裝 APK、啟動模擬器、執行 Room／SQLCipher／Tink 整合或真實 DocumentsProvider。來源閱讀使用 GitHub connector；容器對 raw GitHub 的直接 HTTP 下載因 DNS 不可用失敗，故並未取得或編譯完整 production source tree。

重跑本地檢查：

```bash
./harness/run.sh
python harness/summarize_jvm.py artifacts/pr34-306a011-jvm.zip
```

## 5. 原始資料定位

- BackupService：https://github.com/ImL1s/quietinbox/blob/306a011465d8aeedce88e25dda4fab0974318aaa/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt
- BackupHangTest：https://github.com/ImL1s/quietinbox/blob/306a011465d8aeedce88e25dda4fab0974318aaa/platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupHangTest.kt
- SettingsBackupAbortTest：https://github.com/ImL1s/quietinbox/blob/306a011465d8aeedce88e25dda4fab0974318aaa/platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/SettingsBackupAbortTest.kt
- 差異：https://github.com/ImL1s/quietinbox/compare/d73fe1a2887fb347b2d366caad3ea90ee718c74c...306a011465d8aeedce88e25dda4fab0974318aaa
- Kotlin withContext：https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html
- CountDownLatch：https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CountDownLatch.html

未對 GitHub 寫入任何變更，未合併、打 tag 或發版。
