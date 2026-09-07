# Issues #22–#27 disposition consult — Codex `gpt-6-astra` (reasoning effort max), read-only, 2026-09-08

Dispatched against `main` at `29cfaf0` with `-s read-only`, answer on stdout only. The question is `BRIEF.md`; the per-item matrix six read-only Claude Opus subagents produced on 2026-09-07 is `triage-matrix.md`. Archived verbatim; absolute paths in the answer are the reviewer's own.

---

建議把 0.1.4 的優先工作放在還原一致性、刪除後媒體清理、誠實標示與回歸驗證。80 項完整分配為 **17 BUILD、36 TEST_ONLY、25 FOLLOW_UP、1 DECLINE、1 REAL_DEVICE**。

已核對 `main@29cfaf0`、矩陣及 #22–#27 原文。全程唯讀，完整報告已輸出至 stdout；未修改檔案、建立／關閉 issue 或執行建置測試。以下「已有測試」指已讀到測試斷言，不代表本次執行通過。

十項 DONE 的第二次核對：

- **RS-14：讀取分支的 DONE 成立。** [MediaRead.kt:26](/Users/iml1s/Documents/mine/quietinbox/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaRead.kt:26) 與 [MediaReadTest.kt:32](/Users/iml1s/Documents/mine/quietinbox/platform/media/src/test/kotlin/dev/quietinbox/platform/media/MediaReadTest.kt:32) 涵蓋 expired、revoked、超限、正常讀取及 timeout；尚未鎖住 copier 將結果寫回訊息的路徑。
- **D4：gap 核心 DONE，操作失敗處理仍 PARTIAL。** [CaptureCoordinatorTest.kt:1178](/Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:1178) 與 [SourcePolicyTransactionTest.kt:169](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/SourcePolicyTransactionTest.kt:169) 有開關、no-op、來源隔離及 rollback 對照；[HealthViewModel.kt:107](/Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthViewModel.kt:107) 仍吞停用失敗。
- **S12：降為 DONE_UNTESTED。** [SearchScreen.kt:149](/Users/iml1s/Documents/mine/quietinbox/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchScreen.kt:149) 正確用 cursor 決定標題；[SearchViewModelTest.kt:97](/Users/iml1s/Documents/mine/quietinbox/feature/search/src/test/kotlin/dev/quietinbox/feature/search/SearchViewModelTest.kt:97) 只驗 `state.next`，改壞 UI 的「總數」分支仍會通過。
- **RK-3：文案已修，降為 DONE_UNTESTED。** [strings.xml:283](/Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values/strings.xml:283) 已說明失鑰後果；[check-strings.py:61](/Users/iml1s/Documents/mine/quietinbox/tools/check-strings.py:61) 只驗 key／placeholder／plural，不能保證警語內容。
- **MED-4：完整「維護不被卡死」保證降為 DONE_UNTESTED。** [MediaRead.kt:67](/Users/iml1s/Documents/mine/quietinbox/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaRead.kt:67) 已分離讀取 scope；[MediaReadTest.kt:97](/Users/iml1s/Documents/mine/quietinbox/platform/media/src/test/kotlin/dev/quietinbox/platform/media/MediaReadTest.kt:97) 未接 `VaultMaintenance`，且 latch 可被 interrupt，沒有模擬不可中斷 provider。
- **RK-6：降為 DONE_UNTESTED。** [SettingsScreen.kt:240](/Users/iml1s/Documents/mine/quietinbox/feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsScreen.kt:240) 條件正確，但沒有金鑰顯示／隱藏及截圖保護開／關的 UI 正負對照。
- **RK-7：DONE 成立，屬文件修正。** [ADR-0005:19](/Users/iml1s/Documents/mine/quietinbox/docs/adr/0005-backup-container.md:19) 及繁中版已與可重顯行為一致。表中只提可合併進金鑰流程的生命週期補強，文件本身不用重做。
- **MED-1：failure mapping 的 DONE 成立。** [MediaReadTest.kt:32](/Users/iml1s/Documents/mine/quietinbox/platform/media/src/test/kotlin/dev/quietinbox/platform/media/MediaReadTest.kt:32) 與 :47 鎖住 null／FileNotFound→URI_EXPIRED，:37 有 TOO_LARGE 對照；延後失效到持久化狀態的路徑尚未測。
- **MED-2：failure mapping 的 DONE 成立。** [MediaReadTest.kt:42](/Users/iml1s/Documents/mine/quietinbox/platform/media/src/test/kotlin/dev/quietinbox/platform/media/MediaReadTest.kt:42) 鎖住 SecurityException→PERMISSION_DENIED；這不是撤銷真來源 URI grant 的實機證據。
- **A11Y-02：原 sender 分離缺陷的 DONE 成立。** [MessageBubbleSemanticsTest.kt:76](/Users/iml1s/Documents/mine/quietinbox/feature/conversation/src/androidTest/kotlin/dev/quietinbox/feature/conversation/MessageBubbleSemanticsTest.kt:76) 有 merged／unmerged 及 sender 不重複的有效對照；time／quality state 尚未一起斷言。

FOLLOW_UP 合併為四個新 issue。**F1–F4 是建議代號，尚未建立，不是假定的 GitHub 編號**：

- **F1「備份與媒體：完整匯入流程及剩餘韌性驗證」**：預覽、筆數／空間、裝置標記、策略及剩餘媒體測試。
- **F2「來源健康、收件匣與搜尋：操作完整性與大型資料」**：來源狀態、時間線、截斷證據、paging、修訂、刪除、分享、搜尋及狀態恢復。
- **F3「可及性與自適應：鍵盤、RTL 與完整視窗矩陣」**：語系、focus、視窗與字體驗收。
- **F4「發版證據：簽署標籤、provenance、SBOM 與 R8 執行驗證」**。

表內測試名稱是**建議新增或擴充的案例**；「人工」代表人工驗收。原 DONE 項目的 TEST_ONLY 只補所列跨層缺口。重複需求共用測試，不重複估工。

| id | disposition | 一行理由／成本與驗證 |
| --- | --- | --- |
| RS-02 | BUILD | small；定義五個 T-id 與六種情境的對應，保留既有 T004 sticker 引用；驗收：`ScenarioReferenceCheck`（雙語定義與引用）。 |
| RS-16 | BUILD | small；五語商店文案補 SYNTHETIC_ONLY 限制；驗收：`StoreClaimsReview`（逐語人工核對）。 |
| RS-01 | BUILD | medium；改為來源×版本×情境證據列，補語言與預期結果；驗收：`CompatibilityMatrixCheck`，依賴 RS-02。 |
| RS-04 | BUILD | medium；來源證據層級獨立於 adapter 存在與否，移除誤導綠勾；測試：`SourceEvidenceTest.versionMismatchIsNotVerified`。 |
| RS-14 | TEST_ONLY | 讀取分支已有測試；補 `MediaCopierIntegrationTest.uriOutcomesReachMessageState`，與 MED-14 共用 provider。 |
| RS-10 | BUILD | small；五語文案與雙語文件說明「聊天開啟時可能不發通知」；驗收：`ForegroundLimitReview`（人工），實機證據歸 #17。 |
| H5 | TEST_ONLY | 補 `CaptureCoordinatorTest.lastSavedChangesOnlyAfterWrite`，涵蓋空解析、repost 與真正新增／修訂。 |
| H9 | TEST_ONLY | 補 `HealthScreenTest.previewAdviceAndSettingsFallback`；常駐提示已滿足必要需求，「頻繁才提示」只是加值。 |
| H4 | TEST_ONLY | 補 `HealthScreenTest.lastAcceptedEventInEveryState` 與 diagnostics 斷言；維持「最後接納事件」的正確語義。 |
| O6 | TEST_ONLY | 補 `OnboardingScreenTest.previewAdviceAtResult`，驗證成功、失敗／未驗證結果都能看到預覽設定指引。 |
| O7 | TEST_ONLY | 補 `CapturedSinceDaoTest.filtersPackageAndSince`：排除舊資料、其他 package，納入邊界時間，再銜接 1/3 與 3/3。 |
| O8 | BUILD | small；取消前次計時器，補健康頁入口；測試：`OnboardingViewModelTest.retryGetsFullTimeout` 與未驗證完成情境。 |
| H8 | TEST_ONLY | 補 `HealthScreenTest.missingCountUnknownWithAndWithoutGaps`，驗證空清單不暗示零遺漏。 |
| D4 | BUILD | small；gap 交易已有保護，補 UI 停用失敗回饋；測試：`HealthViewModelTest.policyFailureIsVisibleAndSourceUnchanged`。 |
| D8 | DECLINE | 拒絕憑 placeholder 斷定遮蔽來源、或擴權讀敏感通知；依 COMPATIBILITY.md:52–55 的明示設計與誠實標籤規則。 |
| O9 | FOLLOW_UP | → F2；預設關閉與 Settings 指引已存在，onboarding 直接操作三種選項屬新增流程。 |
| jump-to-search-hit | TEST_ONLY | 補 `SearchHitNavigationTest.anchorMissingAndRestored`：正確 id／info 偏移、找不到時不跳、恢復後不重跳；與 S13 共用。 |
| inbox-incomplete-capture-marker | FOLLOW_UP | → F2；補來源／全域 gap 時間線及跨 restore 的截斷證據保全；不得冒稱某對話確定漏訊息。 |
| inbox-unviewed-vs-all | TEST_ONLY | 補 `InboxViewModelTest.unviewedAndAll`，包括來源／封存組合及 SavedStateHandle 還原；效能工作交 F2。 |
| msg-copy | TEST_ONLY | 補 `MessageCopyTest.toolbarAndAccessibilityAction`：驗實際 clipboard 內容、順序及空內容，不只驗 action 存在。 |
| revision-history | FOLLOW_UP | → F2；接上 observeRevisions，增加可開啟、排序與空狀態的修訂歷史介面。 |
| long-text-url-emoji-rtl | FOLLOW_UP | → F3；surrogate 與截斷缺陷已修，剩 RTL／長文字驗收和 URL 互動決策。 |
| source-app-open-fallback | TEST_ONLY | 補 `SourceLaunchTest.missingAppAndLaunchRace`：無 launcher、檢查後被停用及啟動失敗均有正確提示。 |
| inbox-multiselect-bulk-delete | FOLLOW_UP | → F2；新增選取、確認與批次刪除，沿既有刪除圖處理媒體、索引及 suppression。 |
| inbox-source-disabled-state | FOLLOW_UP | → F2；把來源 enabled／paused 接至收件匣列與對話。 |
| paging-large-data | FOLLOW_UP | → F2；改有界 keyset 視窗，同時保留搜尋 anchor、未檢視篩選及捲動恢復。 |
| msg-share | FOLLOW_UP | → F2；設計使用者主動分享及明文離開 vault 的提示；ACTION_SEND 本身不違反無 INTERNET 規則。 |
| filter-and-scroll-across-process-death | FOLLOW_UP | → F2；inbox filter 已保存，但搜尋、選取及捲動仍需完整恢復契約與程序重建測試。 |
| S12 | TEST_ONLY | 補 `SearchScreenTest.cursorControlsCountAndLoadMore`：相同 hits、有／無 cursor 的標題不同，空頁有 cursor 仍能續查。 |
| A12 | TEST_ONLY | 補 `ActivityClaimsReview`（五語 UI＋商店人工語義驗收）；保留「安靜／未觀測」，不用泛用禁字清單代替判讀。 |
| S13 | TEST_ONLY | 共用 `SearchHitNavigationTest`，另驗 highlight 在目標載入後可見且不因重組永久存在。 |
| S6 | FOLLOW_UP | → F2；自訂起訖時間需傳入首頁及 loadMore，定義時區與端點包含規則。 |
| S7 | FOLLOW_UP | → F2；新增 conversationId 查詢限制及對話內入口，各頁維持同一範圍。 |
| S3 | BUILD | small；明示 emoji-only／符號不在現有索引範圍；測試：`SearchLimitationsTest.emojiOnlyIsExplained`。 |
| S9 | FOLLOW_UP | → F2；caption 媒體標籤已有，無文字媒體搜尋及篩選仍需儲存／查詢設計。 |
| A8 | TEST_ONLY | 補 `ActivityAnalyticsTest.previewRestrictedCount`，加正常訊息負對照，驗畫面使用第四個數值。 |
| S8 | FOLLOW_UP | → F2；以 package／conversation／senderKey 篩選，避免 display name 撞名。 |
| A5 | REAL_DEVICE | → #17；取得真來源合成測試帳號通知形狀後，才能決定貼圖辨識與 kind 傳遞。 |
| RK-1 | BUILD | small；整把重輸入後解碼並比對目前 key；測試：`RecoveryKeyConfirmationTest.rejectsOtherValidKey`。 |
| RK-3 | TEST_ONLY | 補 `RecoveryKeyDisclosureTest.lossWarningAndResetConsequence`／五語人工驗收；parity 不保證失鑰警語。 |
| ATOM-1 | TEST_ONLY | 補 `BackupImportFailureTest.wrongValidKeyPreservesExistingContent`，比較完整既有資料及媒體雜湊，不只筆數。 |
| MED-14 | TEST_ONLY | 建共用 `MediaCopierIntegrationTest`：test ContentProvider、真 Room、圖像／縮圖及持久化狀態；模擬器即可。 |
| ATOM-2 | BUILD | small；文案涵蓋錯 key、損毀與不完整，維持完整驗證後 apply；測試：`BackupImportFailureTest.tamperedAndTruncatedAreAtomic`。 |
| ATOM-4 | BUILD | medium；修提交後取消的清檔判定，加取消／離頁結果；測試：`BackupCancellationTest.beforeCommitRollsBack_afterCommitKeepsLinkedFiles`。 |
| MED-4 | TEST_ONLY | 補 `MediaMaintenanceTest.uninterruptibleProviderCannotBlockReset`，接真正 copier＋maintenance，provider 刻意忽略 interrupt。 |
| MED-5 | BUILD | medium；隔離已實作，修重播列的 grace 起點；測試：`MediaFailureTest.dbFailureSiblingSurvivalAndReplayGrace`。 |
| MED-12 | TEST_ONLY | 補 `RetentionFilesTest.orphansAndGrace`：舊孤檔刪除，近期檔／被引用主檔與縮圖保留，含競態負對照。 |
| MED-13 | TEST_ONLY | 補 `CaptureCoordinatorTest.uriCopyQueueBoundAndRelease`：第 33 批拒絕並 FAILED＋diagnostic，前批結束後可再接納。 |
| RK-6 | TEST_ONLY | 補 `SettingsKeyWarningTest.visibilityMatrix`：key 顯示＋保護關才警告；保護開及 key 隱藏是負對照。 |
| RK-7 | TEST_ONLY | ADR 已修完；僅補跨頁生命週期的 `RecoveryKeyFlowTest.reshowSameKeyUntilReset`，不再改文件。 |
| IMP-1 | FOLLOW_UP | → F1；匯入前顯示 format／schema，manifest 預覽不可冒充完整檔案驗證。 |
| IMP-2 | FOLLOW_UP | → F1；沿 IMP-7 顯示建立時間，使用 composition locale。 |
| IMP-3 | FOLLOW_UP | → F1；區分宣告筆數、驗證後實際筆數與實際新增筆數，尤其缺媒體及重複資料。 |
| IMP-7 | FOLLOW_UP | → F1；整合 inspect→預覽→確認→完整驗證→apply；確認前不寫 vault，重新開啟的文件仍须驗證。 |
| IMP-6 | FOLLOW_UP | → F1；說清既有 merge／去重與保留期重設，再設計 replace／only-missing；取消安全另先處理。 |
| ATOM-3 | BUILD | medium；補空間預檢及實際遺失媒體數，避免缺媒體仍報完整成功；測試：`BackupLowSpaceTest.partialMediaAndDbFailure`。 |
| MED-1 | TEST_ONLY | helper 映射已有測試；補 `MediaProviderTest.expiresBetweenObservationAndCopy`，共用 MED-14。 |
| MED-2 | TEST_ONLY | SecurityException 映射已有測試；補 `MediaProviderTest.revocationReachesPermissionDenied` 與可讀成功對照。 |
| MED-7 | BUILD | small；交易內確認訊息存在／連結更新成功，否則清檔；測試：`MediaDeletionRaceTest.deleteBeforeLinkLeavesNoBlob`。 |
| MED-9 | BUILD | medium；quota 納入新檔／縮圖並防並行超額，補低空間處理；測試：`MediaQuotaTest.concurrentCopiesAndDiskFull`。 |
| MED-11 | TEST_ONLY | 補 `MediaMaintenanceTest.cancelCopyThenResetOrRestore`：無永久 PENDING、無懸掛 join、成功媒體不被掃成 FAILED。 |
| IMP-4 | FOLLOW_UP | → F1；有需求時做可選暱稱／opt-in；通知限定規則不禁止加密備份中有使用者自填 metadata。 |
| IMP-5 | FOLLOW_UP | → F1；估算 vault、staging、媒體及 DB 額外空間；處理舊 manifest 缺欄位，StatFs 不保證成功。 |
| A11Y-03 | TEST_ONLY | 補 `SelectionSemanticsTest.selectedAndUnviewed`，驗 merged tree 的 selected／stateDescription 及負對照。 |
| FT-02 | TEST_ONLY | 補 `NavigationWidthTest.backAndPanes`：599／600／839／840dp，另驗 expanded 從搜尋開對話仍有返回。 |
| A11Y-04 | FOLLOW_UP | → F2；copy／delete 測試由 msg-copy 共用；完整 share 無障礙操作與 msg-share 一起設計。 |
| A11Y-08 | BUILD | medium；將驗收矩陣、命令及未通過即停止條件接入發版程序；驗收：`ReleaseAccessibilityGate`，依各 FT 提供證據。 |
| FT-05 | TEST_ONLY | 補 `AdaptiveUiTest.largeDisplayDensity`：驗實際 dp 寬度、按鈕可達及返回，不只 PNG 尺寸。 |
| REL-09 | FOLLOW_UP | → F4；新增 provenance、限定發行權限，驗證發布檔案 digest。 |
| FT-01 | TEST_ONLY | 補 `AdaptiveUiTest.coverWidth`：300–400dp 五個導航項目仍可辨識、操作，重要操作無遮蔽。 |
| FT-03 | TEST_ONLY | 補 `AdaptiveUiTest.landscape`；先修測試程式只讀直向實體 display 的假設，再驗視窗與返回。 |
| FT-04 | TEST_ONLY | 補 `AdaptiveUiTest.splitWindow`，以實際 app window 驗分割、還原及選取／捲動狀態。 |
| FT-06 | TEST_ONLY | 補 `AdaptiveUiTest.fontScale200`，驗長正文、關鍵按鈕與可捲動內容。 |
| FT-07 | TEST_ONLY | 補 `AdaptiveUiTest.longEnglishAndTraditionalChinese`，涵蓋限制對話框、手動設定文字及大字體。 |
| A11Y-02 | TEST_ONLY | sender／body 已有正負對照；擴充 `MessageBubbleSemanticsTest.timeAndQualityShareNode` 補時間及狀態。 |
| REL-02 | FOLLOW_UP | → F4；新版本採簽署 annotated tag 並於 CI 驗簽，不重寫已發布標籤。 |
| APK-07 | BUILD | small；發布前核對實際 APK versionName 與 tag；測試：`ReleaseVersionGateTest.mismatchFails`。 |
| A11Y-06 | FOLLOW_UP | → F3；定義鍵盤 focus 順序、對話開關的移交及搜尋快捷操作，進行互動驗證。 |
| REL-05 | FOLLOW_UP | → F4；產生並發布可驗證 SBOM；verification-metadata 是建置輸入校驗資料，不是成品 SBOM。 |
| APK-09 | TEST_ONLY | 補 `MinifiedReleaseSmokeTest`：安裝真正 R8 APK，走 Room／Hilt／備份序列化及核心畫面。 |

BUILD／TEST_ONLY 按「避免資料損失及錯誤宣稱的價值 ÷ 含驗證成本」排序。**所有 53 個 ID 均已納入，重複需求合併估工**：

1. **ATOM-4、ATOM-1、ATOM-2**，4–6 小時：共用還原 fixture，驗提交／取消、錯 key、損壞檔。
2. **ATOM-3、MED-7**，2–4 小時：修缺媒體卻報成功、刪除後留下媒體的競態。
3. **O8、O7、D4**，1–2 小時：重試計時器、真 SQL 篩選及來源停用失敗回饋。
4. **RS-02、RS-01、RS-16、RS-10、S3、APK-07**，1–2 小時：證據與限制文案、發布版本閘門；RS-02 先於 RS-01。
5. **MED-14、RS-14、MED-1、MED-2、MED-4、MED-11、MED-12、MED-13、MED-5、MED-9**，4–7 小時：先建 provider／Room fixture，再驗取消、回收、佇列、grace 及配額。
6. **S12、FT-02、msg-copy、A11Y-03、A11Y-02**，2–4 小時：把已修 UI 行為接上實際畫面斷言。
7. **RK-1、RK-6、RK-3、RK-7**，2–4 小時：金鑰核對、截圖警語、失鑰後果及重顯；RK-7 最後。
8. **H5、H4、H8、H9、O6、RS-04**，2–3 小時：健康頁共用測試，再接來源證據層級。
9. **jump-to-search-hit、S13、inbox-unviewed-vs-all、A8、A12、source-app-open-fallback**，3–4 小時：定位、篩選、統計及來源啟動。
10. **FT-05、FT-06、FT-07、FT-01、FT-03、FT-04、A11Y-08**，4–6 小時：密度、大字體、窄窗、橫向及分割，最後接入 release gate。
11. **APK-09**，2–4 小時：真正 minified APK 執行驗證。

**一天預算**：以 8 小時計，保留 2 小時驗證，截在第 1 批。第 2 批仍有已知資料處理問題，因此一天不足以承諾 0.1.4 可以發布。

**三天預算**：以 24 小時計，保留 4 小時整合／回歸，保守完成第 1–4 批；第 5 批只有整套 fixture、修正及負對照都能完成才納入。第 6–11 批按領域轉 F1–F4，不能標 DONE。若前四批仍有資料一致性失敗，就延後發布。這是預算限制下對上表完整建議的縮減。

矩陣還有以下需要更正的地方：

- **ATOM-4 不只缺取消按鈕。** [BackupService.kt:394](/Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:394) 在 transaction 返回後才設 `committed`，:401 依此清檔。提交後、caller 恢復前取消，存在誤刪已連結媒體的風險。測試必須區分提交前 rollback 與提交後保檔，不能一律要求取消後完全沒變。
- **ATOM-3 不能只數 prepared 失敗。** [BackupService.kt:345](/Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:345) 會略過重複資料，:355–356 又區分写檔失敗與備份原本缺媒體，:397 仍回 `Ok(counts)`。應數實際受影響的還原訊息。
- **MED-7 競態存在，但「最多 12 小時」不成立。** [Entities.kt:234](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Entities.kt:234) 的 `media_blob` 無 message FK；[Daos.kt:396](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:396) 不檢查更新筆數，因此刪除後仍能提交孤 blob。[RetentionWorker.kt:133](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt:133) 只是週期設定，正確界線是「下一次成功 sweep」。
- **MED-9 尚非嚴格容量上限。** [MediaCopier.kt:142](/Users/iml1s/Documents/mine/quietinbox/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt:142) 只比较既有大小，未算本次主檔／縮圖，也未防兩個 store 並行超額。
- **MED-5 的 grace 起點不對。** [RetentionWorker.kt:102](/Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt:102) 使用 `observedAt`，重播舊通知建立的新 PENDING 可立即被判 FAILED。
- **IMP 第一行預覽不等於完整驗證。** [BackupStager.kt:48](/Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupStager.kt:48) 讀至 EOF，:79–83 才核 end／counts；manifest 宣告筆數也不等於實際新增筆數。
- **RK-1 不能直接把一組交給 decode。** [RecoveryKeyCodec.kt:34](/Users/iml1s/Documents/mine/quietinbox/platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/RecoveryKeyCodec.kt:34) 要求完整 56 字元。整把 checksum 正確後仍須比較目前 key；單組確認只能驗該組。
- **診斷文字未涵蓋所有 emitted code。** [BackupService.kt:390](/Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:390) 產生 `RESTORE_ORPHAN_MESSAGES`，[Labels.kt:99](/Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Labels.kt:99) 仍退回原始碼；預覽受限的 SKIPPED 也被泛化。
- **截斷證據仍可能遺失。** [SCOPE.md:80](/Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md:80) 已承認行分隔處整列損失、restore 略過重複項時丟截斷證據。應在 F2 明列，不能因 #28 另案處理就消失。
- **測試層級被混用。** `MediaReadTest` 注入 lambda，沒有建立 `MediaStreams`／`MediaCopier`；test ContentProvider 可在模擬器測。只有真來源 app 的授權、通知形狀及帳號證據才歸 #17。[ci.yml:112](/Users/iml1s/Documents/mine/quietinbox/.github/workflows/ci.yml:112) 只能證明列了哪些測試，本次未查驗此 HEAD 的 CI 結果。

#22–#27 可以在本版項目有證據、延後項逐 ID 連到實際 follow-up、拒絕項有規則理由後關閉。**#17 保留，#28 獨立驗證；搬移待辦不等於已實作。**
