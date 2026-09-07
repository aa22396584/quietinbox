# QuietInbox Round 39 唯讀審查

**Verdict：REQUEST CHANGES**

Critical 0 項、Important 1 項、Minor 2 項。兩位元欄位、提交失敗交易與等待式 replay gate，未發現本輪新增的資料完整性缺陷。需要修正的是失敗對話框對「交易是否已完成」及「擷取是否仍在進行」的錯誤保證；另有兩處測試覆蓋缺口。

審查範圍為 main 的 **29cfaf0dc60a6f787d101bbd84c91febf8469031..4ba8652e2d33d59faa250f75b706c3f69e8fdd59**。唯一讀取的審查 brief 是 /tmp/qi-r39-brief-safe.md，沒有讀取其他 brief、既有 reviewer 報告或記憶。依該 brief 檢查產品原始碼、測試、相關文件及提交訊息，未啟用工作流程模式，也未修改產品程式碼。

開始時工作樹乾淨，HEAD 為 f7f6bd9c00a57f885ebe806f45b5254424763c9a；相對受審終點只有 round-39 brief 文件不同。後續另以 git archive 匯出 4ba8652 到 /tmp/qi-r39-target.NWoM4i 驗證。結束前比對 DAO、IngestRepository、CaptureCoordinator、HealthViewModel、BackupService，原工作樹及暫存副本皆與受審 git blob 相同。

## Critical

無。

## Important

### I1．捕捉到例外不等於交易回滾；對話框卻一律保證「沒有任何變更」

**位置：** [HealthViewModel.kt:139](/Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthViewModel.kt:139)、[PolicyFailureDialog.kt:32](/Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/PolicyFailureDialog.kt:32)、[繁中字串:197](/Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-b+zh+Hant/strings.xml:197) 與 :198；相同語意存在於其餘四份 catalogue。

新 wrapper 只知道 coordinator 丟出 Exception，卻把它解讀為「變更完全沒有發生」。這個前提不成立：

1. **交易已完成，重載才失敗。** [CaptureCoordinator.kt:484](/Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:484) 先執行 block()，接著才在 :487 呼叫 loadSourcePolicy()；後者於 :497 重新查詢來源。SourceRepository.setFlag 的交易在 :66–72 完成，remove 的交易也在 :99–112 完成。因此，若後續來源查詢拋出資料庫讀取例外，已完成的停用、移除，甚至刪除副本，仍會落入新 catch，顯示「沒有任何變更」。這個訊息無法作為刪除結果或隱私設定的可信回報。
2. **尚未走到結清，就被說成結清失敗。** 停用時 CaptureCoordinator.kt:748 的 SOURCE_DISABLED_BY_USER gap-open 在 :752 的 settlement 之前。前者失敗也會收到 settlement 文案。金庫鎖定時，DatabaseHolder.kt:61–66 更可能在交易開始前就丟出 VaultUnavailableException，與待處理通知的結清寫入無關。
3. **原本就沒有擷取，仍會宣稱擷取繼續。** remove 一律傳 settle = true（HealthViewModel.kt:123），來源卻可以原本已停用、暫停，或整體擷取已暫停。移除失敗並不代表「擷取仍在進行」。鎖定原因也可能是金鑰問題，不能由這個 Boolean 推定需要清出儲存空間。

這不是要求把所有底層例外搬到 UI。最小修正是讓通用失敗文案不推定提交結果、原有擷取狀態或失敗階段，例如「此次操作未能正常完成，請確認來源目前的設定後再試一次」。只有確認交易回滾時，才保證未套用變更；只有確認是 settlement 寫入失敗時，才使用該說明。若需要保留精確文案，則讓 coordinator 區分交易拒絕與提交後重載失敗，並對 locked vault 使用既有的金庫處理指引。

**如何驗證：** 逐段追蹤真實 SourceRepository 交易邊界、changeSourcePolicy 的先寫後讀順序、DatabaseHolder 的 locked throw、ViewModel 的固定 settle 分類，以及五份字串。現有 5 個 ViewModel 測試和 3 個 dialog 測試皆通過，但測試的 fake coordinator 直接拋例外，沒有區分上述階段。

**證據限制：** 此 finding 是原始碼控制流程與文案比對，沒有在實體金庫注入「提交完成後下一次 SELECT 失敗」。它證明 blanket 保證缺乏依據；不表示正常停用交易會遺失原子性，也不把既有的政策重載設計另報為本輪新缺陷。應增加提交後重載失敗、locked vault、gap-open 失敗及移除已停用來源的案例。

## Minor

### M1．CI 沒有執行新增的 HealthViewModel JVM 測試

**位置：** [.github/workflows/ci.yml:40](/Users/iml1s/Documents/mine/quietinbox/.github/workflows/ci.yml:40)。

本輪在 :112 加入 health 的 connectedDebugAndroidTest，但 JVM lane 的明列任務仍沒有 :feature:health:testDebugUnitTest。App 測試需要編譯 health 的產品類別，不會順帶執行 health 的單元測試。因此新增的五個 policy failure／cancellation 測試可以在本機通過，卻不受目前 PR CI 保護。

**修正：** 在 JVM lane 加入 :feature:health:testDebugUnitTest。

**如何驗證：** 從受審 CI 檔擷取實際任務清單，於獨立副本執行 Gradle --dry-run，exit 0；任務圖包含 health:compileDebugKotlin，沒有 health:testDebugUnitTest。另已強制執行該套件，5/5 通過。

**證據限制：** 這是本機解析實際 CI 任務圖的結果，沒有啟動或查核新的 GitHub Actions run。證明的是 CI 漏接測試，不是五個測試或產品程式本身失敗。

### M2．四值測試沒有走到 pendingExcluding，無法守住有暫停來源時的查詢

**位置：** [JournalLossTransactionTest.kt:734](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/JournalLossTransactionTest.kt:734)，對應 [Daos.kt:73](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:73)。

theCandidateReadsAgreeOnAllFourValues 建出 0、1、2、3，卻只透過 pendingIds() 呼叫沒有 exclusions 的 pendingJournal()。IngestRepository.kt:233 因此只選 pending()，完全沒有選 pendingExcluding()。其餘 storage instrumented 呼叫也沒有提供非空的 excludingPackages；coordinator 測試則 mock 掉 repository，不能補上這條真實 SQL 的驗證。

目前兩條 SQL 都正確使用 lossRecorded < 2，未發現產品查詢缺陷。但若只把 pendingExcluding 的條件退回 lossRecorded != 2，值 3 便會在有暫停來源時重新進入候選集，新增四值測試仍沒有執行到被改壞的查詢。

**修正：** 在同一套真實金庫測試加入非空 exclusions，安排被排除及未被排除的來源，直接驗證 0/1 入選、2/3 排除，且被暫停來源的列都不入選。

**如何驗證：** 追蹤測試 helper 與 repository 分支，搜尋 storage 測試的所有 pendingJournal 呼叫；另抽取受審 DAO 的原 SQL，在 SQLite 模型中以非空 exclusions 執行。原 SQL 只回傳 0/1；把該 SQL 字串改成 != 2 的模型會額外回傳 3。

**證據限制：** SQL 字串反例實際執行過，但沒有修改產品檔案，也沒有編譯並執行整套 Kotlin mutant。此 finding 是測試漏掉一個重要分支，不能寫成已證實目前產品錯誤放行 3。

## 實際驗證與邊界

| 驗證 | 本輪結果 |
|---|---|
| 原工作樹：./gradlew test :app:assembleDebug --rerun-tasks | 285 個 JVM 測試，0 failure、0 error、0 skipped；組建成功 |
| 受審 git archive：./gradlew test :app:assembleDebug lint | 成功；285 個測試報告全數通過。部分純 JVM 模組由快取還原，沒有把這些計為另一次全量實跑 |
| 獨立副本強制重跑 capture、parsers:apps、health 三個 test task | 73 + 47 + 5 = 125，全部實際執行並通過；WhatsAppParserTest 為其中 12 項 |
| CaptureCoordinatorTest | 共三次成功完成 73 項套件，其中兩次在獨立副本；沒有重現 brief 指定的 lock-out gap 斷言失敗 |
| emulator-5556，API 36，直接執行 JournalLossTransactionTest | AndroidJUnitRunner 回報 OK (32 tests)，全部通過 |
| 同一指定模擬器的其他 instrumented 結果 | BackupRoundTripTest 3、PolicyFailureDialogTest 3、conversation semantics 5、crypto 2，合計 13 項通過 |
| lint | 受審副本所有 lint 任務成功；XML 無 Error/Fatal，但仍有 Warning/Hint，不能稱為零警告 |
| 字串檢查 | python3 tools/check-strings.py：0 errors、0 warnings |
| debug APK 權限 | tools/check-permissions.sh：沒有網路權限 |
| DAO SQL 模型 | 使用實際 DAO SQL 及 schema 4，SQLite 3.53.0 記憶體資料庫，80 個狀態／操作組合通過 |
| CI 任務圖 | 實際 CI JVM 清單 --dry-run 成功，確認漏掉 health JVM task |

**未完成的驗證不能算綠燈。** 首次多模組 connected 執行的 storage 任務失敗且 XML 為零項；單獨 Gradle 重跑雖回報 BUILD SUCCESSFUL，XML 仍為零項，因此也沒有算通過。接著直接執行整個 storage package，runner 宣告 63 項但在第二項開始後回報 Process crashed，沒有完整套件結果。改為直接指定 JournalLossTransactionTest 後，32 項完整通過。故本輪可確認的 Android 測試是 **32 + 13 = 45 項**，不是重新證明 brief 宣稱的全部 76 項。已核對實際安裝的 storage 測試 APK 與本機產物 SHA-256 相同：5592c2a0661ec4fed9dc8a926ee52681b2bbc78e48a3d3fff68b547713e53150。

原目錄另一次 capture 重跑出現 Gradle 的 in-progress-results-generic.bin 不存在，與具名的 lock-out gap 斷言失敗不同。同機可見其他建置活動，但未證明它與上述 runner／輸出異常的因果關係；這是改用 git archive 的理由，不是把任何失敗歸咎於其他程序。沒有對其他裝置執行測試，也沒有重跑 CI 的 API 29/35 組合。

**測試數校正：** 受審 JournalLossTransactionTest 的 @Test 實際為 **20→32**，不是 brief 的 21→33。新增 13 項、移除 1 項舊缺陷固定測試，另改寫並更名 1 項既有測試；runner 的 numtests=32 與原始碼一致。docs/TEST_MATRIX.md 及繁中對應文件的 33 應一起校正。這不是有一項測試被跳過。

執行記錄保留於 /tmp/qi-r39-jvm.log、/tmp/qi-r39-isolated-jvm-lint.log、/tmp/qi-r39-isolated-targeted.log、/tmp/qi-r39-journal-direct.log、/tmp/qi-r39-instrumented.log、/tmp/qi-r39-storage-isolated.log、/tmp/qi-r39-storage-direct.log、/tmp/qi-r39-ci-dryrun.log、/tmp/qi-r39-sql-model.txt。暫存記錄不是版本控制內的永久證據。

## 兩位元欄位：完整讀寫盤點

以下 Daos.kt 指 platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt。

| statement／位置 | 對 lossRecorded 的行為 |
|---|---|
| insert，:57–58 | 唯一整列插入入口；IngestRepository.kt:109–125 建立 0，或在 acceptance loss 同一交易內建立 1；重複 eventId 忽略插入且不再記 gap |
| PENDING_FOR_PACKAGE_AFTER，:48–53／:108 | SELECT *，只讀 PENDING、指定來源、值 0；以時間及 eventId 游標前進 |
| pending，:65 | SELECT *，PENDING 且 < 2，因此只納入 0/1 |
| pendingExcluding，:73 | 同樣只納入 0/1，再排除指定來源；NULL package 仍可入選 |
| claimLoss，:125 | PENDING 且 = 0 才寫 1；0→1 |
| deferLoss，:139 | PENDING 且 < 2 才加 2；0→2、1→3；再次 park 不會再加 |
| resumeDeferredLosses，:160 | PENDING 且 >= 2 才減 2；2→0、3→1 |
| resumeDeferredLossesForPackage，:169 | 同上，另限定來源；不修改其他來源 |
| pendingLossState，:181 | 只讀仍 PENDING 的欄位；不存在或已 terminal 回 null |
| isReplayCandidate，:185 | 用與 pending 相同的 PENDING／< 2 條件計數 |
| setState，:79–85 | 不改 lossRecorded；增加 attempts，非 PENDING 時清 payload |
| fileFailed，:194 | 僅將 PENDING 標 FAILED、增加 attempts 並清 payload；保留原 bit 值 |
| discardPending，:198 | 僅將該來源 PENDING 標 DISCARDED、清 payload；保留原 bit 值 |
| deleteExpired，:210 | 只刪已非 PENDING 的過期列；停放列不因 TTL 被清掉 |
| payload／attempts／state／count，:201–217 | 不讀取或改寫 lossRecorded；observePendingCount 包含停放列，沒有把它們誤算成已完成 |

schema 3→4 migration 的新增欄位預設 0；產品內未找到其他建立或改寫 lossRecorded 的路徑。備份的訊息合併不會匯入 event_journal。

| 起始值 | 意義 | claimEventLoss | park 後 | resume 後 | replay 候選 |
|---|---|---|---|---|---|
| 0 | arrival loss 尚未結清，或沒有需結清的 arrival loss | 勝出時寫 gap，成 1／RECORDED | 2 | 0 | 是 |
| 1 | arrival loss 已結清 | ALREADY_RECORDED | 3 | 1 | 是 |
| 2 | 未結清且停放 | DEFERRED | 2 | 0 | 否 |
| 3 | arrival loss 已結清，但另一筆必要 gap 寫入失敗而停放 | DEFERRED | 3 | 1 | 否 |

所有四值進入 terminal state 後都保持原值，但 claim、park、resume、候選讀取的 PENDING 條件都使它們失效；不需要為 terminal row 另清 bit。一般提交、SKIPPED、PARSE／DECODE failure 與使用者選擇的 discard，分別有自己的退出路徑；本輪新增的耗盡退出則由 COMMIT_FAILED gap 和 fileFailed 的共同交易負責。

從 0/1 起步，現有 SQL 的可達值集合封閉於 0..3；加減 2 不會弄丟低位的 settled 狀態。模型也逐一驗證四個值在 PENDING、COMMITTED、FAILED、SKIPPED、DISCARDED 下的四種更新，共 80 組。資料表沒有 CHECK，故任意直接 SQL、外部破壞或未來違約插入不在此結論內；目前沒有具體應用路徑產生越界值。

pendingLossState 的判斷在 IngestRepository.kt:173–176，只把精確的 1 當成 ALREADY_RECORDED，3 確實是 DEFERRED。對正常 PENDING／0 列，claimLoss 的 WHERE 完全命中；更新和後續讀取又在同一交易，沒有正常競態會讓「更新 0 列、重讀仍 0」成立。外加 RAISE(IGNORE) trigger 等可以製造此狀態，但產品沒有這種 trigger。else→DEFERRED 在此是保守的後備分類，沒有找到現存誤判路徑。

**兩位元是不是正確形狀：** 是。arrival loss 是否已結清，與目前是否因必要 gap 寫不進去而退出候選集，是兩個獨立事實。1→3→1 正是本輪必須保留的資訊；單一「deferred=2」無法同時停放已結清列並避免重複認領。保持 claim／settle walk 的 =0、所有 replay 候選的 <2，再以精確 enum 回報可否繼續，是目前合理且有限的修補。M2 是守住這個契約的測試缺口，不是要求更換資料模型。

## markJournalRetryable 的交易與呼叫端

IngestRepository.kt:268–296 的順序正確：先檢查 PENDING，再讀 attempts；未達閾值只增加 attempts；耗盡時先寫 COMMIT_FAILED gap，再要求 fileFailed 恰好更新一列。兩次耗盡不是兩個獨立的「先查後寫」：Room 的共同交易序列化了檢查與寫入，後來者看到 terminal 就回 NOT_PENDING。即使 gap 已寫而 fileFailed 拒絕或未更新列，例外仍必須讓整筆交易回滾，不能留下代表不存在終結的 gap。

exhausting 只在進入耗盡分支後設為 true。一般 attempts 更新失敗會重拋；耗盡分支失敗才在回滾後另外 park。連 park 都寫不進去時回 RETRYABLE，對目前兩個呼叫入口是誠實的：列仍在候選集，attempts 與 payload 沒被耗盡交易消耗。CancellationException 直接重拋，不轉成失敗結案。

NOT_PENDING 也適用於「文字已成功提交，後續媒體或診斷工作才失敗」；此時不能再報整個事件未存下來。沒有找到由新交易先清掉唯一 payload、再用 NOT_PENDING 掩蓋未寫 gap 的路徑。

live 入口 CaptureCoordinator.kt:1010 忽略 enum 是可接受的。journal 新列的 attempts 固定為 0；同 eventId 插入失敗後 :980 立即離開，所以既有 attempts=2 的列不會被當作新 live 事件繼續提交。真實 live 第一次失敗只會到 1。後續耗盡由 replay 處理，:1283–1292 重新查詢 PENDING 與候選資格，以持久化結果判斷 progress／deferredSettlements，沒有信任不代表持久化完成的返回文字。

上述交易性有實際 32 項 JournalLossTransactionTest 支持，包括 gap 拒絕、filing 的 ABORT trigger、park 的 ABORT trigger及恢復 settled bit；不把 coordinator fake 的 attempts 演算法當成真實 Room 交易證據。

## 等待式 replay gate 與五個入口

| 入口 | 位置 | 呼叫 replay 時是否持有 pipelineMutex |
|---|---|---|
| vault Ready collector | CaptureCoordinator.kt:309 | 否，collector 在初始化建立的 scope 中執行 |
| 全域恢復 | :408–413 | 否，獨立 scope.launch |
| 成功 gap 寫入觸發 deferred retry | :581–584 | retryDeferredSettlements 可在 pipeline lock 內被呼叫，但它 launch 新工作，不在鎖內 await replay |
| 單一來源恢復 | :771 | changeSourcePolicy 已返回、鎖已釋放，再 launch |
| maintenance 結束 | :843–848 | listener 只 launch bookkeeping／replay，不等待它完成 |

沒有找到持有 pipelineMutex 等待 replayGate，而 gate holder 又等待 pipelineMutex 的鎖循環。VaultMaintenance.work 在 :65–77 登記可取消工作，exclusive 在 :85–96 設 active、取消並等待既有 work、再進入 pipeline lock；結束時先把 active 設 false，再通知 listener。

等鎖 waiter 被取消，不會清除 replayRequested；仍在執行的 holder 或其他 waiter 可以服務已留下的請求。holder 取消則 withLock 釋鎖，活著的 waiter 接手。若所有請求者都已取消，就不能保證沒有後續 lifecycle trigger 仍會自行重播；這不等同本輪要修的「仍有有效請求者卻無人接手」。

等待者數量沒有硬上限：旗標合併的是 pass 工作，不是所有掛起 coroutine。持續外部觸發可累積 waiter；本輪沒有壓力量測或可歸因的資源退化，不將其升格為缺陷。maintenance 長時間 active 時，每個取得 gate 的 waiter 至多做一次拒絕檢查，恢復旗標後 return；有限個 waiter 是有限次檢查，不會在 while 中自旋。

**等待 gate 是不是 round 38 I1 的正確修正：** 是。有效請求者現在自行等待，不再完全依賴可能被取消的舊 holder 執行 unlock 後的補跑。新增取消接手案例與強制 page-read 重疊的 coalescing 案例，都與這個錯誤直接相關。maintenance-active 的 restore-flag 那一行仍沒有可區辨測試；由於 exclusive 結束先降 active、再必定發出新 replay request，brief 對此的解釋成立。本報告不把既已揭露的該行證據限制、last-round resume 或 parked-row lifecycle 殘餘再列為 finding。

三次成功完成的 capture 套件沒有重現具名 lock-out gap flaky failure；沒有做舊 gate 與新 gate 的統計對照，因此無法聲稱新 gate 提高或降低其發生率。

## lossOnCommit、parser 與備份合併

**兩個提交出口。** IngestRepository.kt:372–375 的 identity／reconcile／decisions 空分支，以及 :584–586 的一般儲存分支，都先執行 lossOnCommit，再標 COMMITTED，而且在同一交易。迴圈內的 continue 不是第三個函式出口；中途例外則回滾，沒有第三個成功提交出口漏掉 callback。

**SKIPPED 與 adapter。** CaptureCoordinator.kt:1074 的 SKIPPED 要求 messages 為空且沒有 summary。StandardParser.kt:69 則在 messages.size >= 2 時才呼叫 wholeMessagesLost override，所以現有 override 無法使空批次帶旗標進入此路徑。WhatsAppParser.kt:78–81 排除 MessagingStyle、historic messages、InboxStyle，並與拆列路徑共用 groupRows。切在完整列後的分隔位置回報 whole-message loss；切在最後一列內部只標該列。所有現有 registry 實作維持此路徑。未來直接 override parse 或另寫 NotificationParser 仍可製造違約 ParsedBatch，這是擴充契約的限制，不是現在已存在的漏記。

**備份 multiplicity。** BackupService.kt:331–357 仍以每個 conversation 的 fingerprint／sortKey／observedAtEpochMs 為 key。每個 backup copy 只 removeFirstOrNull 一筆「匯入前就存在」的列；deque 耗盡後才新增，所以原有 n 筆、備份 m 筆的同 key multiplicity 仍為 max(n,m)，不因本輪改為保留 entity 而變成全部跳過或全部新增。新增的旗標更新也在 apply 的交易內。

**不同非 null 旗標。** 現有列優先，匯入不覆蓋；不是集合聯集。當前產品對 message.truncationFlags 的寫入契約是 null 或 TEXT，UI 也以非空內容顯示截短標籤，因此現有合法單值資料沒有兩種有效標籤需要合併的衝突。備份若帶入歷史／未知的不同非空值，保留哪個值確實與原有資料有關；本輪沒有承諾保全多種 reason。這個結果在目前單值契約下可接受，不能擴大解讀成任意旗標集合的無損合併。

上述 parser 路徑有 JVM 測試；backup 的 before→after、after→before 有真實金庫測試。多筆同 key 配對及不同非 null flag 的行為則是 source trace，沒有新增實跑的專門案例。

## 每個新增／改寫測試的反向控制稽核

下表的產品突變均是**原始碼層級推導**，不是已套用的 patch，也不是歷史 NC 執行證明。沒有為本次 review 修改產品或測試程式。表中刻意只選會經過該測試實際執行層的一行變更；例如 coordinator 測試使用 mock repository，不能用「刪掉真實 IngestRepository callback」宣稱會讓它變紅。

### CaptureCoordinatorTest：10 個新增、1 個改寫

測試檔：platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt。產品 CC 指同模組 main 的 CaptureCoordinator.kt。

| 測試起始行／案例 | 一行產品突變 | 會失敗的斷言 |
|---|---|---|
| :2049 兩個 trigger 重疊，改寫為可控制的 page-read 阻擋 | CC:1188 的 replayGate.withLock 改成 inline run | 第二個 page read 與第一個重疊，讀取數／maxConcurrent 不再符合單一 pass |
| :2104 parser 證明整列被切掉 | CC:1125 把傳入的 lossOnCommit 改 null | commit 中沒有收到並執行 loss callback，gap／inside-commit 觀察不符 |
| :2130 切在最後一列內，不是 gap | CC:1103 條件改成 wholeMessagesLost 或 messages 非空 | 錯記 MESSAGES_DROPPED，exactly 0 失敗 |
| :2156 三次 commit 失敗後只記一次整個事件 | CC:1332 把 COMMIT_FAILED 改 UNKNOWN | fake 執行真實 callback 後，reason／選出的 record 不符 |
| :2189 拒絕記錄時 park，後列仍可提交 | CC:1292 把 progressed = true 改 false | pageSize=1 時 evt-fine 沒有被走到 |
| :2228 既不能記錄也不能 park，不算進度 | CC:1285 改為只要仍 PENDING 就進入 progress 分支 | 同一候選列反覆 commit，exactly 1 不符 |
| :2254 live 入口交入相同的記錄 callback | CC:1010 第三參數改為空 callback | 測試手動執行收到的 callback 後沒有 COMMIT_FAILED record |
| :2280 舊 pass 取消，等待者仍被服務 | CC:1187 改為只有 gate 未鎖定才設 replayRequested | B 等到 A 取消後沒有 request 可服務，reads 停在 1 |
| :2314 maintenance active 時不能讀，結束後可讀 | VaultMaintenance.kt:66 的 active 分支改為直接執行 block() | exclusive 尚未結束時 reads 應為 0 的斷言失敗 |
| :2339 停用 A 只恢復 A 的 deferred rows | CC:1371 改用不帶 package 的 resume overload | B 的 deferred 集合也被改動 |
| :2373 一次 deferral episode 至多武裝一次 retry | CC:583 不再清除 deferredSettlements，改設 true | 第二個成功 gap 又觸發 pass，evt-late 被錯誤提交 |

:2314 守的是 maintenance 拒絕與結束通知，不是 :1195 restore-flag 那一行。:2156 的次數及 terminal 效果部分由 fake 模擬，真正 repository 的閾值、rollback、payload 和 bit 行為必須由 storage 測試決定。

### WhatsAppParserTest：2 個新增、2 個補強

測試檔：parsers/apps/src/test/kotlin/dev/quietinbox/parsers/apps/WhatsAppParserTest.kt。

| 測試起始行／案例 | 一行產品突變 | 會失敗的斷言 |
|---|---|---|
| :95 分隔位置截斷，補強 | WhatsAppParser.kt:81 改回 false | wholeMessagesLost 應為 true |
| :112 列內截斷，補強 | :81 改為只看 rows.truncatedBody | wholeMessagesLost 應為 false |
| :125 完整群組內容，新增 | :81 移除 truncatedBody，僅看 !cutInsideLastRow | 完整內容被錯報整列遺失 |
| :134 adapter 沒有拆列，新增 | StandardParser.kt:69 改為 messages.isNotEmpty() | 單列內容的 wholeMessagesLost 應為 false |

### JournalLossTransactionTest：13 個新增、1 個改寫，另移除 1 個舊缺陷固定測試

測試檔：platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/JournalLossTransactionTest.kt。IR 指 IngestRepository.kt；DAO 指 Daos.kt。

| 測試起始行／案例 | 一行產品突變 | 會失敗的斷言 |
|---|---|---|
| :423 settled row 可 park，但不退回 unsettled，改寫 | DAO:139 的加 2 改為直接寫 2 | park 後應為 3，而非 2 |
| :588 儲存出口寫入 parser loss | 移除 IR:584 的 callback invocation | message 已存但 MESSAGES_DROPPED 數量不是 1 |
| :603 不儲存訊息的出口也寫 loss | 移除 IR:373 的 callback invocation | early exit 缺少 gap |
| :618 loss callback 失敗，整批 rollback | IR:584 改為用 runCatching 吞掉 callback 例外 | commit 不再失敗，message／PENDING 斷言也不符 |
| :645 耗盡時記錄及 filing，只做一次 | 移除 IR:280 的 lossOnExhaust() | terminal row 沒有 COMMIT_FAILED gap |
| :666 記錄失敗後 park，resume 後才可結案 | IR:292 的 park 呼叫改為回傳 0 的 runCatching | 返回值、deferred bit 與候選資格不符 |
| :694 acceptance 已 settled，park／resume 不再認領 | IR:175 把值 3 也分類成 ALREADY_RECORDED | 停放期間必須回 DEFERRED 的斷言失敗 |
| :716 carried-over claim 消耗後經 per-source resume 仍消耗 | DAO:169 的減 2 改為寫 0 | resume 後應為 1，claim 不可再次勝出 |
| :734 四個值的讀取 | DAO:65 把 < 2 改 != 2 | pendingIds 額外包含值 3；不涵蓋 :73，見 M2 |
| :758 gap 先成功、filing 被 trigger 拒絕，整體 rollback | IR:282 改為 Unit，跳過 fileFailed | 沒有執行拒絕 filing，錯回 FAILED_RECORDED 並留下 gap |
| :775 park、resume 後真實 commit 成功，不報耗盡遺失 | 移除 IR:585 的 COMMITTED 更新 | 成功提交後仍是 PENDING |
| :794 在 claim 前已 park，不能取得 gap | DAO:125 的 =0 條件改成 <3 | 值 2 被重新 claim，錯回 RECORDED 且多寫 gap |
| :808 park 也被拒絕，必須留在原地 | IR:295 將未 park 的返回值改成 FAILED_DEFERRED | 應回 RETRYABLE 的斷言失敗 |
| :827 已離開 PENDING，不計費也不記錄 | 移除 IR:273 的 state guard | 已 COMMITTED 列被當作 retryable 重新計費，NOT_PENDING 斷言失敗 |

原 aRowWhoseCommitAttemptsRunOutLosesItsEvidenceAndSaysNothing 被移除是正確的，因為它固定的是被修掉的缺陷。改名的 settled-row 測試也正確改成保護低位 bit，而不是禁止任何 settled row 被 park。

證據仍有細分：:758 實際注入的是 RAISE(ABORT)，沒有專門注入「fileFailed 回傳 0、不拋 SQL 例外」；:603 以無 identity 覆蓋 early exit，沒有把 reconcile=null、decisions empty 三種條件分別跑一次。這些不推翻現有實作，但不能宣稱每個 guard／每個 OR 分支都有獨立 negative control。

### BackupRoundTripTest：1 個新增

測試檔：platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRoundTripTest.kt。

| 測試起始行／案例 | 一行產品突變 | 會失敗的斷言 |
|---|---|---|
| :159 先還原有旗標或無旗標的備份，結果都保留標籤 | 移除 BackupService.kt:357 的 markTruncated | before→after 還原仍為 null |

這項測試確實經過真實匯出／匯入與資料庫，而不是只檢查 helper。它沒有覆蓋多筆同 key 的 multiplicity、部分 duplicate 帶 flag，或兩個不同非 null flag 的配對。

### HealthViewModelTest：5 個新增

測試檔：feature/health/src/test/kotlin/dev/quietinbox/feature/health/HealthViewModelTest.kt。

| 測試起始行／案例 | 一行產品突變 | 會失敗的斷言 |
|---|---|---|
| :79 停用被拒絕 | HealthViewModel.kt:116 將 settle 改 false | failure.settle 應為 true |
| :94 移除被拒絕 | :123 將 settle 改 false | 移除的 failure.settle 應為 true |
| :106 暫停被拒絕 | :117 額外傳 settle=true | 此 failure.settle 應為 false |
| :118 成功不報錯、dismiss 後不再顯示 | :128 改為不清除 policyFailure | 等待 policyFailure=null 無法完成 |
| :136 cancellation 不報成拒絕 | 移除 :138 的 CancellationException rethrow | 取消變成 policyFailure，shouldBeNull 不符 |

這五項會守住「操作種類→settle Boolean」的實作，但不會證明 Boolean 是實際失敗原因。未測 addSource／重新啟用的失敗、locked vault、gap-open 先失敗、提交後重載失敗及移除原本已停用來源；I1 正是這個分類假設沒有被挑戰的結果。

### PolicyFailureDialogTest：3 個新增

測試檔：feature/health/src/androidTest/kotlin/dev/quietinbox/feature/health/PolicyFailureDialogTest.kt。

| 測試起始行／案例 | 一行產品突變 | 會失敗的斷言 |
|---|---|---|
| :31 settlement body | PolicyFailureDialog.kt:32 永遠選一般 body | settlement body 應出現、一般 body 不應出現 |
| :40 一般 body | :32 永遠選 settlement body | 一般 body 應出現、settlement body 不應出現 |
| :49 OK dismiss | :33 的 onClick 改為空 callback | dismissed 應增加為 1 |

測試從資源取字串，能守 body 選擇與互動，不會判斷文字的因果敘述是否真實。五份 catalogue 的 key／XML 檢查也不等同逐語系渲染與語意驗證；本輪沒有切換五個 locale 逐一測試。

## NC24–NC31 的宣稱核對

提交訊息可對應到以下測試，但文字中的「曾 red、恢復後 green」沒有被當成新的執行證據：

| NC | 提交宣稱的產品變更 | 對應 CaptureCoordinatorTest |
|---|---|---|
| NC24 | parked exhaustion 不算 progress | :2189 |
| NC25 | live 入口不交入 loss callback | :2254 |
| NC26 | COMMIT_FAILED 改成 UNKNOWN | :2156、:2189、:2254 |
| NC27 | 忙碌 gate 改回交給 holder、直接離開 | :2280 |
| NC29 | retry launch 前不清 flag | :2373 |
| NC30 | settle walk 改用 global resume | :2339 |
| NC31 | 移除 coalescing | :2049、:2280 |

實際列出的是 **7 個不同 NC 編號、10 個 test-control 配對**。NC28 沒有被提交訊息宣稱；不把它與已揭露的 restore-flag 測試限制再報一次。各變更與目前斷言之間具有靜態可辨識性，且原版測試本輪通過；沒有對七個 Kotlin mutant 重新做 red→green 執行，所以沒有宣稱已獨立重現歷史 NC 結果。
