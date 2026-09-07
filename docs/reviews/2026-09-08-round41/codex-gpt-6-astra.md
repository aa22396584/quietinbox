REQUEST CHANGES

Critical：未發現。

Important：

1. **I1 — 產品的 gap 競態已修正，但新測試仍會放過原始回歸。**

   位置：`platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:1005`、`:1006`、`:1008`；產品對應 `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:295`、`:1024`。

   測試等到了 `openGap` 開始，卻在送出 Ready 後立即 `releaseOpen.complete(Unit)`。StateFlow 賦值只發布狀態，不保證 collector 已處理它；寫入端仍可先完成失敗處理、記住 bound，collector 才開始結算。這正好避開 round-40 的交錯。

   本輪把新測試的原始 body 與 Harness 抽至暫存測試類別，對重新編譯的來源執行。原版、只移除 collector 鎖、只恢復晚寫 bound，以及兩者同時退回舊寫法，**四者皆為 1／1 通過**。因此不是僅憑控制流猜測測試可能漏接；具有原始缺陷的組合確實被放行。這是各一次的實跑結果，不代表回歸版本在所有排程下都會通過。

   另以 deferred 明確等到無鎖 collector 已關閉 flag，再放行 `openGap` 失敗，同一份雙重回退版本得到 `records=0, open=false, since=1700000000000`，完整性斷言失敗。只移除鎖也不是無害變更：當停住的 `openGap` 最後成功，collector 可先補記 bounded gap 並清 flag，寫入端隨後才建立開放區間，留下 `records=1, durableOpen=1, open=false`；目前新測試只注入失敗，沒有攔住這個分支。

   僅恢復晚寫 bound、仍保留共同鎖時，原始競態仍受鎖保護；這個 mutation 存活本身不構成缺陷。需補的是能控制 Ready 處理進度的回歸測試，讓雙重回退確實失敗，並涵蓋 `openGap` 最後成功時區間仍被正確關閉。不要只靠發布 Ready 後立刻放行的排程運氣。

2. **I3 — 訊息 body UPDATE 已能抓到，但「每欄、每個會寫入的表都不變」仍有兩個盲點。**

   位置：`platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRejectionTest.kt:115`、`:121`；`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:432`；`docs/TEST_MATRIX.md:21`、`docs/zh-Hant/TEST_MATRIX.md:21`。

   **診斷表仍未納入。** `BackupService.kt:433` 會寫入 `DiagnosticEventEntity`，實際表名是 `local_diagnostic_event`（`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Entities.kt:309`）。fingerprint 的六張表清單沒有它。向此表插入、刪除或改寫資料都不影響比較；round-40 I3 已明確指出 diagnostic 資料缺漏，本輪只補入 `search_token`，沒有完全關閉該 finding。

   **欄位值的編碼仍會碰撞。** `:115` 把 SQL NULL 與字面文字 `∅` 都變成相同字串，也未跳脫欄位分隔符 `|`。合法的 `senderName = NULL → '∅'` 更新不會改變 fingerprint；相鄰欄位從 `senderName='a|b', senderKey='c'` 改為 `senderName='a', senderKey='b|c'` 也完全相同。因此雖然讀取所有欄位，結果並未保留每個欄位的真實值與邊界。

   本輪以目前匯出 schema 建立記憶體 SQLite，依此 helper 的查詢、表清單與編碼規則驗證：`UPDATE message SET body='mutated'` 有被識別；NULL／文字替換、分隔符移動、診斷表插入都未被識別。這是 SQL／編碼層的反例，未宣稱已執行 Android 還原測試或觀察到產品拒絕路徑真的毀損資料。

   應把 `local_diagnostic_event` 納入，逐欄保留 null、型別及欄位邊界，並加入不改筆數的 UPDATE 與診斷資料變更作負對照。現有六種拒絕案例的完整性保證仍不足以支持兩份 TEST_MATRIX 的敘述。

Minor：

1. **I1 的過期 `vaultLocked` 狀態仍未修正。**

   位置：`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:290`、`:295`、`:1038`。

   Ready 仍在取得 pipeline 鎖之前發布 `vaultLocked=false`。若 writer 停在 `openGap`，Ready 先清狀態並等待鎖，writer 隨後又在 `:1038` 設為 true；collector 取得鎖後只結清 gap，沒有再校正狀態。受控探針在目前未修改來源的成功、失敗兩個分支皆得到 `vaultStateReady=true, statusLocked=true`，且 replay 已被呼叫。這是 round-40 I1 附帶指出、仍存在的狀態不一致；本輪未發現此欄位直接造成畫面誤報或阻止 capture，不提升為資料遺失問題。應將 Ready 的狀態發布納入相同序列化順序，並對最終狀態加斷言。

其餘指定攻擊點的結論：

- **I1 的實際 gap 結算已關閉原始窗口。** `CaptureCoordinator.kt:974` 包住建立 gap、bound 與失敗處理；collector 在 `:295` 取得同一個 mutex，無法插入 flag／bound 的半初始化狀態。本輪目前來源的受控探針中，`openGap` 失敗會留下恰好一筆 bounded gap；成功則只關閉既有區間，沒有多補一筆，也沒有殘留開放區間。
- **未見與 `replayJournal` 的新死鎖。** collector 的 `withLock` 在 `CaptureCoordinator.kt:313` 結束，`:314` 才呼叫 `replayJournal()`；後者在 `:1226` 取得 `replayGate`，`:1274` 再取得 pipeline 鎖。新增區段沒有持有 pipeline 鎖等待 replayGate，也沒有重入同一個非重入 mutex。77 個 coordinator 測試及本輪探針皆完成；這不是對任意 I/O 停滯的全面證明。
- **I2 已關閉 brief 指定的單行移動回歸。** 首次還原含一個真正插入並連結的 blob：`BackupService.kt:415` 將其加入 `usedFiles`，原先也在 `writtenFiles`。只把 `:444` 的 `removeAll` 搬到交易外、保留 `:448` 的 `check`，交易內交集必然非空而拋錯。交易回滾，catch 清檔並回傳 `Failed(IO)`；`BackupCancellationTest.kt:123` 預期的 `CancellationException` 因此不會發生，測試會失敗。第二次重複匯入即使 `usedFiles` 為空，第一次已足以殺死 mutation。此判定為控制流核對，未實跑此 Android mutation。現有 probe 仍不是直接在 Room durable commit 與 caller 返回之間注入取消；若連同 check 一起搬走，超出本次單行反向控制的證明範圍。
- **I3 的指定 body UPDATE 確實會失敗。** `BackupRejectionTest.kt:112` 的 `SELECT *` 現在包含 body，`:124` 納入比較，原本文字改成 `mutated` 會使 `:136` 的 equality 不成立；它修好了原先只比 COUNT 的問題，但沒有消除上述其他盲點。
- **CI 與計數修正成立。** `.github/workflows/ci.yml:40` 明列 `:feature:onboarding:testDebugUnitTest`，`actionlint` 通過。來源宣告數為 model 6、parser 13、identity 5、reconcile 22、analytics 34，core 合計 80；coordinator 77，與本輪兩份 TEST_MATRIX／SCOPE 的改動一致。

驗證紀錄與限制：

- 日期 2026-09-08；目錄 `/Users/iml1s/Documents/mine/quietinbox`；分支 `main`；HEAD 為 `a63bc63a9cb38e3b21b01e38e76594307d5f23f6`。檢查範圍 `866b2d6..a63bc63`，包含 `5723156` 與文件索引提交 `a63bc63`；對照 round-40 原始報告，未把其既有測試結果當成本輪結果。
- 使用 JDK 17.0.15、Kotlin 2.4.10，重新編譯目前的 `CaptureCoordinator`、`PolicyChangeException` 與整份 `CaptureCoordinatorTest`，JUnit Platform 結果為 **77 found／77 started／77 successful，0 skipped／0 failed**。第一次 JVM 啟動因 MockK 無法 self-attach 而失敗；以快取 Byte Buddy 的 `-javaagent` 啟動後取得上述通過結果。
- 新增測試的四個聚焦版本均重新編譯執行，各 **1 found／1 successful**：原版、移除 collector 鎖、恢復晚寫 bound、兩者回退。測試 body 與 Harness 保持原內容，只抽成獨立測試類別；mutation 僅在 `/private/tmp` 的副本，未套用至 repository。
- 受控 gap 探針：目前來源的成功、失敗正向控制均通過；雙重回退的遺失反例與移除鎖的成功寫入反例均由完整性斷言拒絕。後兩者 exit 1 是預期抓到缺陷的結果。
- 記憶體 SQLite／編碼探針：body UPDATE 被識別；NULL／文字替換、分隔符移動及診斷表插入均證實可漏過比較。未重新執行 Room／SQLCipher／Tink instrumentation。
- 編譯及探針使用現有 build 依賴與快取函式庫；這是針對受審來源的新鮮 JVM／邏輯驗證，不是完整 Gradle build、Android instrumentation 或 GitHub CI 通過證明。
- 證據目錄 `/private/tmp/qi-r41-astra-jli73uyu/`：`config.json` 含來源 SHA-256 與 classpath；`baseline-tests-with-agent.log`、`mutation-results.json`、各 mutation 目錄的 `test.log`、`Round41LockGapProbe.kt` 與 `*-probe.log`、`fingerprint-probe.py` 與 `fingerprint-probe.log`。
- 未啟動 orchestration workflow，未執行 Git 寫入命令，未修改產品或 repository 測試。repository 中本輪唯一寫入為此指定報告；其餘驗證產物皆在 `/private/tmp`。brief 排除的議題未重開。
