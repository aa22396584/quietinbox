REQUEST CHANGES

Critical：未發現。

Important：

1. **I1 — lock-out 的發布順序已改正，但 `Ready` collector 仍能遺失同一個 gap。**

   位置：`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:1015`、同檔 `:292`；現有測試 `platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:970`。

   `37da486` 確實把 `vaultLocked` 的發布移到 gap 處理之後，但 `vault.state` collector 不透過這個 status 同步，也沒有取得 `pipelineMutex`。寫入端先設 `vaultGapOpen = true`，等 `health.openGap()` 失敗後才設 `vaultGapSince`；collector 可以在兩者之間處理 `Ready`：讀到 `since == null`，只執行 `closeOpenGaps()`，再清除 `vaultGapOpen`。寫入端隨後留下時間戳並發布 locked，後續 `Ready` 卻因 open 已為 false 而跳過補記。未成功 journal 的通知因此沒有遺失紀錄。

   已用本 HEAD 的 `CaptureCoordinator.kt` 重新編譯並實際重現。探針以 deferred 控制 `openGap()` 的返回，正常對照等待失敗處理完成才送 `Ready`；反例在 `openGap()` 尚未返回時送 `Ready`，等 collector 清除 open 後才放行失敗。結果：

   ```text
   earlyReady=false records=1 closes=1 open=false since=null locked=false
   earlyReady=true  records=0 closes=1 open=false since=1700000000000 locked=true
   READY racing openGap lost the bounded gap: expected 1, actual 0
   ```

   新移到 `:1029` 的發布還會在 Ready 已清掉 locked 後，再把狀態覆寫成 `vaultLocked = true`；探針亦確認這個新發布位置造成的過期狀態。現有 `:980` 等到 `vaultLocked == true` 才在 `:982` 發出 `Ready`，避開了上述交錯，因此目前 76 個 coordinator 測試全過仍不能封住它。gap 遺失是本範圍修補的殘餘問題，並非聲稱 `37da486` 新引入該半初始化競態。應讓 gap 建立與 Ready 結算共用同步邊界，或以不可分割的狀態更新處理，並加入上述提前 Ready 的回歸測試；保留現有寫入失敗後重試的語意。

2. **I2 — 還原中斷測試殺不死它宣稱防住的 commit／return 回歸。**

   位置：`platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupCancellationTest.kt:42`、`:121`；`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:435`；`docs/TEST_MATRIX.md:20`、`docs/zh-Hant/TEST_MATRIX.md:20`。

   `AFTER_COMMIT` probe 在 `withTransaction` 已完整返回之後。只把 `writtenFiles.removeAll(usedFiles)` 從交易內 `:444` 移到 `:446` 與 `restoreProbe(AFTER_COMMIT)` 的 `:447` 之間，現有三個測試仍會通過：交易內 probe 仍在 trim 前；交易外 probe 則仍在 trim 後。這恰好重新打開 `CHANGELOG.md:168` 所述「資料已提交，但取消在 withTransaction 返回前送達」的窗口。測試註解與兩份矩陣把這個移動列為會失敗的負對照，與實際注入位置不符。

   目前產品確實在交易內 trim，未在這裡發現產品 placement 錯誤；問題是核心修補缺少能攔住原始回歸的測試。應在 durable commit 與 caller 收到返回值之間控制取消，而非僅在返回後丟例外，並更正負對照宣稱。本文的移動反例是控制流推演，未改動產品程式或實跑此 mutation。

3. **I3 — 拒絕還原的「逐位元組不變」判準只比筆數，會漏掉既有資料遭改寫。**

   位置：`platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRejectionTest.kt:103`、`:114`；`CHANGELOG.md:185`；`docs/TEST_MATRIX.md:21`、`docs/zh-Hant/TEST_MATRIX.md:21`。

   `vaultFingerprint()` 比較五個表的 `COUNT(*)` 與媒體檔案內容，沒有比較任何資料列欄位，也未涵蓋 restore 會碰到的 `search_token` 與 diagnostic 資料。若在拒絕返回前插入一行 `db.openHelper.writableDatabase.execSQL("UPDATE message SET body='mutated'")`，或刪除 search tokens，再回傳原本預期的 Failed reason，這個判準完全看不出異動。相同筆數不代表相同訊息、policy、投影或索引。

   目前拒絕路徑都在 `apply()` 前返回，沒有觀察到產品實際改壞資料；但這組資料完整性測試不足以支持文件的強保證。應比較各相關表穩定排序後的完整列值或其 digest，加上檔案 bytes，並以不改筆數的 UPDATE 作反向控制。不要把 SQLite 檔案／WAL 的實體位元組相等與使用者資料內容相等混為一談。

Minor：

1. **M1 — 兩份 TEST_MATRIX 的 core 計數已落後。** `docs/TEST_MATRIX.md:11`、`docs/zh-Hant/TEST_MATRIX.md:11` 仍寫 model 5、core 合計 79；新增 `core/model/src/test/kotlin/dev/quietinbox/core/model/SearchNormalizerTest.kt:18` 後，宣告數是 model 6、core 合計 80（6 + 13 + 5 + 22 + 34）。本次也實際執行了 6 個 model 測試。應同步兩份文件。

2. **M2 — 本輪新增的 onboarding 回歸測試仍未進入一般 CI JVM job。** `.github/workflows/ci.yml:40` 已補 health，卻仍未列 `:feature:onboarding:testDebugUnitTest`。本輪修改 `OnboardingViewModel.kt:135` 並新增 `feature/onboarding/src/test/kotlin/dev/quietinbox/feature/onboarding/OnboardingViewModelTest.kt:136`，刪掉取消舊 timer 的一行就會回歸，但 push／PR 的這個 job 不執行其測試。release workflow 的全域 `test` 不能替代一般 CI。建議將此 task 一併加入；這不否定 brief 中 health M1 已修正。

3. **M3 — 壞媒體測試的名稱與文件描述超過注入範圍。** `platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupCancellationTest.kt:142`、兩份 `TEST_MATRIX.md:20` 稱為「金庫寫不進 blob」，實際只把 Base64 改成 `%%%%`，觸發空 bytes 拒絕，沒有讓 `BlobCipher.encryptToFile` 失敗，也沒有觸發單檔大小上限。應把名稱／描述收窄為 decode failure；若要宣稱加密寫入失敗也被驗證，需另外注入該分支。

4. **M4 — media 正向控制沒有驗證縮圖檔仍在。** `platform/media/src/androidTest/kotlin/dev/quietinbox/platform/media/MediaDeletionRaceTest.kt:124` 只查主檔。把 `MediaCopier.kt:198` 的 `written.clear()` 改成 `written.remove(name)`，主檔仍在、blob 與 LOCAL_COPY 都成立，縮圖卻會在 finally 被刪，這個測試仍會通過。應對非 null 的 `thumbFileName` 也斷言檔案存在。這不影響目前零列連結的 race 修補判讀。

5. **M5 — secondary defer 的取消傳播沒有對應測試。** `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:184` 新增了 defer 本身遭取消時重拋的處理。四值 candidate 測試沒有走到這個分支，刪掉新增的 `.onFailure { if (it is CancellationException) throw it }` 不會被本輪測試攔住。可補「原 gap 寫入先失敗，接著 defer 遭取消」的例外型別斷言；目前產品寫法本身正確。

審查範圍與已確認事項：

- 審查日期 2026-09-08；工作目錄 `/Users/iml1s/Documents/mine/quietinbox`；分支 `main`；開始及結束前讀回的 HEAD 均為 `866b2d6498adcd8b900bd49e2c2ea4580ec8b04f`。差異範圍為 `97e631d..866b2d6`，包含 `32abcfc`、`37da486`、`866b2d6`。
- 政策失敗分類通過本次檢查：`CaptureCoordinator.kt:484` 分開包住 repository write 與 reload，reload 失敗會再試一次，兩次失敗才拋 `CommittedNotReloaded`；locked 與 cancellation 保留各自型別。`HealthViewModel.kt:145` 依例外分類，generic `IllegalStateException` 落入 UNKNOWN。`PolicyFailureDialog.kt:53` 的五種分類分別讀五個資源 key。五個 catalogue 的 committed／unknown 內文均未使用 rollback 的「沒有任何變更」保證。locked 使用獨立文案。真正的 rollback 才使用 rollback／settlement 文案。
- CI health M1 已修正：`.github/workflows/ci.yml:40` 明列 `:feature:health:testDebugUnitTest`，不是依賴 app task 間接執行。
- pendingExcluding M2 已修正：`platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/JournalLossTransactionTest.kt:734` 建出 included 的 0／1／2／3、excluded 的 0／1，先證明未排除時四個 candidate 都存在，再於 `:764` 以非空 exclusion list 呼叫真正的 `pendingJournal()`，只保留 included 的 0／1。對抽出的實際 SQL 另做記憶體 SQLite 檢查，included／excluded 各 0／1／2／3 的結果也一致；此檢查不等於 Room／SQLCipher instrumentation。
- Restore 目前產品控制流正確：`BackupService.kt:323` 明確拒絕 null、空 bytes 與超限 bytes；準備失敗的新訊息於 `:394` 標 FAILED 並計數；`SettingsScreen.kt:416` 顯示 `mediaNotRestored` 的資源文案。交易內 probe 在 trim 前，取消會 rollback 並清理全份 writtenFiles；返回後取消則只清理未引用檔案。trim 之後若實際 commit 失敗，可能留下待 retention 清除的孤兒檔，這是 `CHANGELOG.md:174` 已揭露的保守取捨，不應宣稱所有交易內失敗都已證明零孤兒。
- Media 目前產品控制流正確：`MediaCopier.kt:192` 對 `setMedia != 1` 在同一交易內移除剛插入的 blob，保留 written 清單供 finally 清理主檔與縮圖。`Daos.kt:418` 的回傳型別為 Int。兩個新 instrumented test 都以明確的 `Unit` 結尾；不存在回傳 matcher 物件而無法被 JUnit4 執行的問題。
- Store copy 與兩份 COMPATIBILITY 保留「來源未發通知便無從擷取」的限制，並明載 adapter 只有 synthetic 證據；未把 issue #17 宣稱為已完成。沒有將實機相容性推導成已驗證。

逐項測試的反向檢查：

本範圍淨新增 22 個測試。以下涵蓋所有新增項、被改寫的既有項，並列出同一組 UI 測試的原有保護。表中的 mutation 是對一行產品邏輯的靜態推演，沒有套用至產品程式；「應失敗」不是宣稱已做 mutation testing。I1 的額外探針則確有執行結果。

`feature/health/src/test/kotlin/dev/quietinbox/feature/health/HealthViewModelTest.kt`，本次重新編譯執行 8／8 通過：

| 測試位置與情境 | 會使其失敗的一行產品變更 | 判準 |
| --- | --- | --- |
| :83 結清回滾、App 名稱 | `HealthViewModel.kt:147` 將 SETTLEMENT 映成 REFUSED | `failure.kind` 不符；也有 package／displayName 斷言 |
| :99 移除被結清拒絕 | 同上 | 移除入口得到錯誤分類 |
| :112 暫停回滾 | `:148` 將 REFUSED 映成 UNKNOWN | kind 不符 |
| :124 提交後 reload 失敗（新增） | `:149` 將 COMMITTED_NOT_RELOADED 映成 REFUSED | 不能冒充 rollback |
| :138 locked（新增） | `:146` 將 LOCKED 映成 REFUSED | 獨立 locked 分類消失 |
| :151 未分類例外（新增） | `:150` 把 else 改成 REFUSED | generic IllegalStateException 的 UNKNOWN／非 rollback 斷言失敗 |
| :168 成功與關閉失敗 | `:137` 在成功 `change()` 後誤設一個 PolicyFailure | 第二次成功後的 null 斷言失敗 |
| :185 cancellation | 刪除 `:140` 的取消重拋 | cancellation 被報成 UNKNOWN，null 斷言失敗 |

`feature/health/src/androidTest/kotlin/dev/quietinbox/feature/health/PolicyFailureDialogTest.kt`，6 個；本次僅編譯產品 composable、核對測試與資源，未執行 Android UI：

| 測試位置與情境 | 會使其失敗的一行產品變更 | 判準 |
| --- | --- | --- |
| :37 結清文案 | `PolicyFailureDialog.kt:55` 改用一般 rollback key | 資源讀出的 settlement body 不再出現 |
| :51 一般回滾文案 | `:54` 改用 settlement key | plain body／settlement 排除斷言失敗 |
| :64 UNKNOWN（新增） | `:58` 改用 rollback key | unknown body 必須出現，兩種 rollback body 必須不存在 |
| :78 COMMITTED_NOT_RELOADED（新增） | `:57` 改用 rollback key | reload body 與非 rollback 斷言失敗 |
| :95 LOCKED（新增） | `:56` 改用 rollback key | locked body 與非 rollback 斷言失敗 |
| :109 OK 關閉 | `:63` 將按鈕 onClick 改為空函式 | dismissed 應為 1，實際為 0 |

這些測試讀 `R.string`，沒有複製英文期望值；但「每種 mapping 都有測試」不等於已逐 locale 在裝置上執行。五語文字意義與 key 完整性另以來源檢查確認。

`platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt`，新增 3 個，整組 76／76 通過：

| 測試位置 | 會使其失敗的一行產品變更 | 邊界 |
| --- | --- | --- |
| :1268 settlement refusal | `CaptureCoordinator.kt:494` 一律包成 Refused | 證明 coordinator 的例外分類；repository 為 mock |
| :1278 committed reload failure | `:504` 改拋 Refused | write fake 已完成後，兩次 reload 都失敗，不能標成 rollback |
| :1306 locked | `:493` 移除 VaultUnavailableException 的直通條件 | locked 被錯包成 PolicyChangeException |

上述新增測試未測 I1 的 Ready 交錯；既有 `:970` 的同步方式亦未涵蓋，詳見 I1。

`platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupCancellationTest.kt`，新增 3 個，未重跑 instrumentation：

| 測試位置 | 會使其失敗的一行產品變更 | 尚未證明的部分 |
| --- | --- | --- |
| :121 提交後取消、重複匯入清理 | 刪 `BackupService.kt:444` 的 trim，已連結檔會被 catch 刪除 | 將同一行移到返回後、probe 前仍可通過，I2 |
| :142 壞 Base64 與結果數量 | `:323` 刪掉 `bytes.isEmpty()` 條件 | 空 bytes 變成 LOCAL_COPY，media count／FAILED／無檔斷言失敗；未測真正 encrypt failure，M3 |
| :174 交易內取消 | 刪 `:454` 的 catch 檔案清理 | rows 雖 rollback，媒體目錄仍有檔案 |

`platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRejectionTest.kt`，新增 6 個，未重跑 instrumentation：

| 測試位置 | 會使其失敗的一行產品變更 | 實際涵蓋 |
| --- | --- | --- |
| :129 錯復原金鑰 | `BackupService.kt:265` 把 AEAD／IO 拒絕 reason 改成 IO | Failed 分類；有限 fingerprint |
| :136 密文翻轉 | 同上 | 認證失敗分類；有限 fingerprint |
| :148 正文截斷 | 同上 | 不完整加密串流分類；沒有直接驗證畫面文案 |
| :156 標頭截斷 | 刪 `:258` 的短標頭 guard | 會落到其他錯誤分類，TRUNCATED 斷言失敗 |
| :164 非備份檔案 | `:259` 將 BAD_HEADER 改為 IO | magic／header 的分類 |
| :170 空間不足 | `:284` 把 `<` 改為 `>` | LOW_SPACE 拒絕消失 |

六者都能攔住分類錯誤；它們對不改筆數的資料毀損沒有判別力，不能以這六個測試取代 I3 的完整內容比較。

其餘新增／修改測試：

| 測試位置 | 會使其失敗的一行產品變更 | 證據與限制 |
| --- | --- | --- |
| `platform/media/src/androidTest/kotlin/dev/quietinbox/platform/media/MediaDeletionRaceTest.kt:106`（新增） | `MediaCopier.kt:192` 把零列拒絕條件改成永不成立 | blob=0、orphans 空、檔案目錄空的斷言會失敗；明確 Unit |
| 同檔 :118 正向控制（新增） | 刪 `MediaCopier.kt:198` 的 `written.clear()` | 主檔被 finally 刪除，存在斷言失敗；縮圖盲點見 M4；明確 Unit |
| `platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/JournalLossTransactionTest.kt:734`（修改） | `Daos.kt:73` 刪 exclusion 條件，或將 loss 狀態 `< 2` 放寬為 `< 4` | excluded 的 0／1 或 included 的 2／3 會出現；非空 exclusion 已真正傳進 repository |
| `feature/onboarding/src/test/kotlin/dev/quietinbox/feature/onboarding/OnboardingViewModelTest.kt:136`（新增） | 刪 `OnboardingViewModel.kt:135` 的 `testTimeout?.cancel()` | 舊 timer 在第二次送出中途把 testFailed 設為 true；本次整組 6／6 通過 |
| `core/model/src/test/kotlin/dev/quietinbox/core/model/SearchNormalizerTest.kt:18`（新增） | `Normalization.kt:125` 的 else 改成將該 code point 加入 out 再前進 | emoji 不再被忽略，空 token 斷言失敗；本次整組 6／6 通過 |

文件宣告數核對：HealthViewModel 8、PolicyFailureDialog 6、CaptureCoordinator 76、BackupCancellation 3、BackupRejection 6、MediaDeletionRace 2、JournalLossTransaction 32、OnboardingViewModel 6 均符合目前樹。model／core 合計差異見 M1；行為證明的過度宣稱見 I2、I3、M3。

驗證紀錄與限制：

- `python3 tools/check-strings.py`：0 errors、0 warnings。
- `actionlint .github/workflows/ci.yml .github/workflows/release.yml`：通過。
- `shellcheck tools/check-instrumented.sh tools/check-version.sh`：僅 `check-version.sh:9` 的 SC2012 info（以 ls 取得路徑），exit 1；未發現本範圍因此造成的執行失敗，不列為阻擋問題。
- 對真正的 `tools/check-instrumented.sh` 使用暫存 XML：zero tests、缺報告、failure、error 均拒絕，正常報告接受，共 5 個分支符合預期。這驗證的是報告閘門，不是 Android 測試本身。
- 原定 Gradle 驗證命令包含 `:feature:health:testDebugUnitTest :platform:capture:testDebugUnitTest :feature:onboarding:testDebugUnitTest :core:model:test :platform:backup:testDebugUnitTest`。預設 cache 先被唯讀 wrapper `.zip.lck` 阻擋；改用可寫的暫存 GRADLE_USER_HOME 與已安裝 distribution，仍在 `FileLockContentionHandler` 因 `java.net.SocketException: Operation not permitted` 而無法啟動。沒有要求權限升級。
- 替代 JVM 驗證使用 JDK 17.0.15、Kotlin 2.4.10 與同版 Compose compiler，將目前樹的 `CaptureCoordinator`、`PolicyChangeException`、`HealthViewModel`、`PolicyFailureDialog`、`OnboardingViewModel`、`Normalization` 及四組測試重新編譯至暫存目錄，再由 JUnit Platform 執行。**96 found／96 started／96 successful，0 skipped／0 failed**：capture 76 + health 8 + onboarding 6 + model 6。依賴使用現有 checkout 的 build 產物與已快取函式庫，因此這是針對被審 source 的新鮮 JVM 執行，並非完整 Gradle build／lint 或 CI 證明。
- 同一批新編譯 source 的 lock-out 探針正常對照通過、提前 Ready 反例失敗，詳見 I1。來源與紀錄位於 `/private/tmp/qi-r40-astra-jvm-9kztv8bw/`：`config.json`、`compile.log`、`test.log`、`Round40LockGapProbe.kt`、`lock-gap-probe.log`。反例的 exit 1 是預期完整性斷言被違反的證據。
- 本次沒有重新執行 Room／SQLCipher／Tink／Compose Android instrumentation，也沒有將工作目錄已有的 Android 報告當成本輪通過證據。沒有宣稱 GitHub CI、實機來源相容性或 release 通過。
- 未啟動任何 orchestration workflow，未執行 Git 寫入命令，未修改產品程式。報告寫入前 `git diff --name-only` 為空；repository 內本次唯一寫入是此指定報告。編譯、測試輔助資料僅在 `/private/tmp`。

依 brief 排除：PARSE_／DECODE 的既有 PENDING／gap 後續議題不列為這三個提交的 blocker；issue #17 維持既定實機證據規則；schema 4 未發布、原地修訂不重複回報。
