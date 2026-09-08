REQUEST CHANGES

獨立審查範圍：`git diff 0cca955..eff84c0`，依 round46 BRIEF 檢查五項修復及其測試。判定依本次差異與相關呼叫端原始碼；未將簡報提供的綠燈當成本次重跑結果。

## Critical

無已確認的 Critical 問題。

## Important

### I1 — P1：取消匯入仍同步等待不可信串流的 close，caller-exit 保證不成立

位置：`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:344`，尤其第 347 行；同檔第 265、316、319–341 行。

`result.await()` 的確可被取消而不用 join 讀取工作，但離開時立即在呼叫者執行緒執行 `input.close()`。`runCatching` 只能接住例外，無法中止阻塞。若串流的 read 持有鎖且永不返回，而 close 需要同一把鎖，取消後仍永遠停在 finally，`job.join()` 不會完成。下一次 import 開頭關閉前一個 stream 也有相同問題。exclusive 此時可以是 inactive，但「呼叫者能退出」這個獨立驗收條件仍未達成。

讀取數量上限也沒有完整涵蓋清理工作：超額分支先扣回計數再 close，正常 finally 也在最後一次 close 前扣回計數。若 close 阻塞，這些工作已不在 liveImportReads 的限制內。因此目前的上限只能限制部分 read 階段，不能證明整個 provider 工作生命週期不會累積卡住的工作。

現有 `BackupHangTest.kt:100` 將 close 寫成立即返回的空實作，只驗證「read 卡住、close 不會卡住」的子集合，無法排除此反例。

建議：把 provider open/read/close 納入同一個有明確數量上限的背景工作生命週期；取消等待者時不得同步呼叫 provider 方法，也不得等待該工作。額度需在任何可能阻塞的 provider 操作之前取得，清理真正結束後才釋放。避免另開無上限的 close 工作取代目前問題。

必要驗證：以 read 與 close 共用鎖的可釋放測試串流重現；保持 read 未釋放時取消 importer，確認 caller 已結束且另一個 exclusive 可進入，再於 finally 釋放測試資源。另驗證超額請求不再建立 provider 工作，close 卡住時也不能繞過上限。

信心：高，阻塞路徑直接存在於控制流程；本次未執行裝置重現。

### I2 — P2：移除 importNow 的 IO dispatcher，文件開啟與媒體加密寫檔回到 UI 執行緒

位置：`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:315`、`:301`、`:397`。

原實作 `importNow` 整體包在 `withContext(Dispatchers.IO)`；新版只有 readAndStage 放進 importReads。`openInput(source)` 在 launch 之前同步呼叫；await 返回後的 apply 也沒有 dispatcher 切換。

正式呼叫端 `feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsViewModel.kt:106` 使用預設的 `viewModelScope.launch`。`VaultMaintenance.exclusive` 僅提供鎖與 coroutineScope，並不切換 dispatcher。因此文件提供者開啟可能直接卡住主執行緒；還原媒體時，apply 在 Room transaction 之前同步 Base64 解碼並呼叫 `BlobCipher.encryptToFile`。該方法在 `platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/BlobCipher.kt:92` 執行加密、第 95 行同步 writeBytes，同樣會在主執行緒上執行。大量或較大的媒體可以造成可見凍結，嚴重時有 ANR 風險。

這是本次移除 dispatcher 所引入的執行緒退化，不是要求重設原本跨 provider read 持有 exclusive 的設計。

建議：provider 開啟跟隨 I1 的有界獨立工作；驗證完成後的本地 apply 明確切到 IO 執行，保留 exclusive 內的 token／epoch 重檢。不要重新把不可合作的 provider read 包回呼叫者必須等待的 withContext。

必要驗證：從 Main dispatcher 呼叫 service，以 seam 記錄 openInput 與媒體寫入所在執行緒，確認都不在 Main；保持暫停 read 時可取消退出的測試，防止 dispatcher 修復重新引入等待問題。

信心：高，已追到 Settings → import → apply → 同步加密／寫檔完整路徑。

## Minor

### M1 — 搜尋的 A→B→A 測試只證明舊 A 返回後的新 session，不證明舊第一頁仍停住時的新查詢進度

位置：`feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt:84`；`feature/search/src/test/kotlin/dev/quietinbox/feature/search/SearchViewModelTest.kt:263`。

collector 仍逐次等待 `run(s)`。第一頁 A 不返回時，後續 session 即使已經 debounce，也不能開始 repository 呼叫。現有測試明確先完成第一個 A，再等待第二個 A，故它驗證結果歸屬，沒有驗證新 session 可以獨立取得進展。

這是保留的序列化限制，不把它誤報成本次新引入的混頁錯誤，也不單獨用它阻擋本輪。若驗收期待「第一頁仍停住時就能執行後來的查詢」，應補上該排程測試並隔離 request 執行；仍需保留 session/request 檢查，不能只靠 cancel。否則文件應明確描述這個進度邊界。

## 五項修復核對

1. **搜尋分頁：核心歸屬檢查成立。** 條件 setter 立即建立新 session、清空 cursor；fromMs 在 session 建立時凍結；第一頁及下一頁都有 request id；過期成功／失敗分支直接保留現有 state，不再清掉新頁的 loadingMore。沒有靠 distinctBy 掩蓋重複。newSession 雖保留舊 results，但 Screen 以 searching／searched 顯示載入佔位，因此不把它當作已確認的舊結果可見問題。序列化限制見 M1。
2. **匯入獨占掛起：僅部分修復。** stage／驗證已移出 exclusive，等待 CompletableDeferred，不 join read 工作；key 由 IO 工作者使用完後清零，沒有在 waiter 取消時先清零。apply 前及 exclusive 內都查 token／epoch。但 I1 的同步 close 使 caller-exit 和完整有界性不足，I2 另造成主執行緒退化。
3. **還原誠實性：指定案例符合。** `BackupService.kt:474` 的 present-but-bad 與第 478 行的 absent LOCAL_COPY 使用 else if，單一插入訊息不會同時計入兩類。缺少 Media record 時轉 FAILED 並增加 mediaAbsent，回傳為 skippedMedia。RoundTrip 新增 skippedMedia == 1、mediaNotRestored == 0 斷言；已存在的重複訊息在計數前跳過。
4. **搜尋錯誤：指定結果區分成立。** 第一頁一般例外設 failed、searched=false，畫面顯示錯誤與 retry；retrySearch 讀取 vault.state.value，而非 local 中預設為 true 的 vaultOpening。load-more 例外保留 hits/cursor 並解除自身 loadingMore；CancellationException 重新拋出。Locked／Opening 的顯示優先序保留。取消測試僅證明不被當空成功，未證明取消後 collector 持續服務新查詢。
5. **CI JVM：設定修正合理。** CI 實際命令已改成在根目錄執行 `./gradlew --no-daemon --console=plain test`。settings 納入 platform:media，該模組套用 Android library 並設定 JUnit Platform；未見該模組的 task 排除設定。因此不再依賴漏列 media 的手動清單。本次未取得遠端 Actions 執行證據，不宣稱遠端 CI 已通過。

## 驗證與範圍界線

- 本次執行 `python3 tools/check-strings.py`：`OK: 0 error(s), 0 warning(s)`。新增錯誤文案在五語系都有對應。
- 已閱讀指定 diff、搜尋 ViewModel／Screen／測試、backup stage/apply／HangTest／RoundTrip 差異，以及 Settings、VaultMaintenance、BlobCipher 和 CI／Gradle 設定。
- 新搜尋測試中的 any/count 等等待帶有 shouldBe 斷言，沒有將單純 Boolean 當等待條件。HangTest 由 IO 工作者執行阻塞 read，runBlocking 端用 delay 輪詢，未見簡報提醒的同執行緒 CountDownLatch.await 死鎖。
- 搜尋 13 tests、backup 裝置測試、root test dry-run、assemble/lint 與 APK permission gate 的成功均是 BRIEF 提供的既有證據，本次未重跑，不擴大成獨立驗證結果。
- 未連網、未執行裝置、未執行 Git 寫入，未修改 Kotlin／XML／CI。唯一輸出修改為本報告。
- 未處理或宣稱關閉 #17／#33；CHANGELOG 明示 #33 stays open。未變更 Dependabot #18–#21，未執行來源通知動作，不作 0.1.5／Play／發版判定。

本輪需先修復 I1、I2 並補上對應驗證，才能核准。
