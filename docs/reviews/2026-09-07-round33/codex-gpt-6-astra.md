# 第 33 輪唯讀審查：schema 4、截短旗標與來源缺口

審查對象：9e379d37d99e13c6980840923b9bd0c9b5430a96。本文程式行號均以此提交為準；涉及舊版時另行標示。正式 v0.1.3 tag 指向 d3cd83f4015c5e15d9c7fe5698c2890ebe85f1d5。

依 BRIEF.md 審查提交差異、必要呼叫鏈、schema、測試及指定文件，未讀取其他審查報告，未啟動工作流程模式。本次只寫入此報告。

## Verdict

**REQUEST CHANGES**

遷移的欄位型別、nullable 設定及新建／升級路徑沒有發現結構不一致。阻擋出貨的是缺口寫入缺乏耐久與重播保證、來源狀態和缺口分開提交，以及既有 journal 旗標在升級後被重新解讀。

驗證包含原始碼追蹤、五語系字串檢查及記憶體 SQLite 遷移比對。**未重新執行 Gradle JVM 測試、Android instrumentation、lint、權限檢查或實際加密備份來回還原**；提交訊息所述「236 JVM、17 instrumented 通過」不視為本輪重新驗證的結果。下列並行與故障案例是程式碼可達的具體排程，沒有宣稱已在裝置重現。

## Critical — 0.1.4 出貨前必須修正

### C1. 來源設定已提交，缺口卻在鎖外另外寫入，會漏記或顯示相反狀態

**位置：** platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:609、624；同檔 :408、999。維護邊界位於 platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/VaultMaintenance.kt:85。

兩個 setter 只把來源設定放進 changeSourcePolicy／pipelineMutex；返回後才計時，再用 guarded 開啟或關閉缺口。這些新增的健康資料寫入既不持有 pipelineMutex，也不是 maintenance.work。HealthRepository 本身沒有補上這個鎖。

可觸發的序列：

- 停用／暫停已寫入，程序在 openGap 前死亡。重啟後來源仍停用／暫停，但沒有相應缺口。
- 啟用／恢復已寫入，程序在 closeOpenGapsForSource 前死亡。擷取已恢復，健康頁仍有未結束的停用／暫停缺口。
- 同一來源快速「關→開」：關閉操作先提交設定並釋放鎖；開啟操作提交設定並查詢缺口，此時沒有列可關；先前的關閉操作才插入缺口。最後來源 enabled=true，卻存在開放的 SOURCE_DISABLED_BY_USER。暫停／恢復亦同。HealthViewModel.kt:107 每次操作啟動自己的 coroutine，並未把這兩半序列化。
- 設定半段離鎖後，exclusive 取得 pipelineMutex 開始重設／還原；缺口半段仍可同時碰觸舊資料庫或尚未開好的金庫。若它拋出例外，guarded 直接吞掉。

loadSourcePolicy（CaptureCoordinator.kt:420）只重新載入集合及處理冷啟動缺口，沒有來源狀態與缺口的啟動修復；也沒有為這些失敗留下待補寫義務。未結束的錯誤缺口會持續到日後吻合的切換或保留期清理，不能把它當成已恢復擷取的正確表示。

**修正要求：** 把實際來源狀態轉換、pending journal 處理及缺口 open／close 納入同一次 pipelineMutex 所有權及資料庫 transaction；不能只把兩段依序呼叫。補上故障／啟動收斂，並只在狀態真正改變時新增缺口，避免重複送出同值就插入多列。新增測試需涵蓋兩半之間中止、維護插入及快速反轉。

### C2. dropped-message 缺口可以寫入失敗後被遺忘，也會在重播時重複

**位置：** platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:871；同檔 :817、882、904、984、999。缺口實體位於 platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Entities.kt:34。

新增 recordGap 是獨立 insert，包在會吞掉非取消例外的 guarded 裡；它不和 journal 的 terminal transition／訊息 commit 共用 transaction，也沒有 eventId 或其他事件唯一鍵。

**漏記案例：** 一份帶 MESSAGES_DROPPED 的有效 snapshot 已進 journal；讓這一次 health.recordGap 拋出例外，之後資料庫操作恢復正常。程式仍繼續解析，並可把 journal 寫成 COMMITTED；空解析則寫成 SKIPPED。JournalDao.setState（platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:60）會清掉 terminal row 的 payload，於是既沒有缺口，也沒有待重播的損失證據。guarded 也吞掉 VaultUnavailableException，因此這次失敗不會進到外層保存 vaultGapSince 的處理。

**重複案例：** 缺口 insert 成功後，identity／設定讀取或 ingest.commit 在 terminal commit 前失敗，事件留在 PENDING；或程序就在這個時間窗死亡。下一次重播再次執行 :871，同一 event 又插入一個缺口。健康頁將一次遺失列成多次。

**修正要求：** 讓事件的損失義務具有耐久性，且以事件識別做冪等處理。缺口寫入失敗不能被當作已處理成功；terminal commit／discard 必須保證該義務已落盤。測試至少要注入缺口 insert 失敗、後段 commit 失敗及成功插入後重播。

### C3. 新缺口仍晚於第一道 commit fence，存在完全繞過記錄的路徑

**位置：** platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:864、871、612；移除路徑位於 platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SourceRepository.kt:69。

具體序列：

1. t0：通知已超過訊息上限，snapshot 帶 MESSAGES_DROPPED，且 journal 已成功接受。
2. journal 返回前後，全域 pause 生效；setPaused 在 CaptureCoordinator.kt:341 立即改變旗標。processJournaled 的第一個 commitFenced 因而返回，完全沒執行 :871。
3. journal 留在 PENDING。使用者在恢復／重播之前停用或移除該來源。
4. discardPendingJournal／SourceRepository.remove 清除該 payload 並將它標成 DISCARDED。

這份 snapshot 再也不會產生 MESSAGES_DROPPED 缺口。稍後才開啟的全域暫停或來源停用區間，不能表示 t0 已發生的批次內容遺失。這是除了空解析之外，提交所稱「不會被 short-circuit 跳過」的另一個反例。

**修正要求：** 在耐久接受事件時就保存損失義務，並涵蓋所有 terminal discard 分支；不能只把 insert 放在 parser.parse 前。保留 pause／停用的安全圍籬，補上「journal 後暫停→停用／移除→不再重播」的回歸案例。

### C4. TruncationFlag 早已存在持久化 journal；升級會把舊的整則遺失誤認成文字截短

**位置：** platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:96、107；core/model/src/main/kotlin/dev/quietinbox/core/model/NotificationSnapshot.kt:50、148；platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:871、1014。

「TruncationFlag 沒有被持久化」在變更前就不成立：journal 以 NotificationSnapshot.serializer() 保存整份 snapshot，包含 shape.truncated。v0.1.3 的 MESSAGES／HISTORIC_MESSAGES 同時表示兩種截斷；本提交保留原名稱，卻把它們限縮為文字截短，parserInputVersion 仍為 1。

**觸發：** v0.1.3 收到 65 則皆短於 4,096 字元的訊息，留下最後 64 則並以 MESSAGES 記錄截斷；journal 寫好後、commit 前程序死亡，再升級至 schema 4。兩個 ALTER 不會改寫 journal payload。新版可成功解碼舊名稱，但不會產生新的 MESSAGES_DROPPED 缺口；留下的 64 則反而全被寫上文字截短旗標。

可觀察結果是被捨棄的一則仍無缺口，未截短的倖存文字卻被標記為截短。Historic 路徑同樣成立。這不是 hypothetical downgrade，而是既有資料庫升級後的正常重播路徑。

**修正要求：** 定義並版本化舊 journal 的相容轉換。舊名稱不能直接依新語意使用；可以利用保留下來的 BoundedText.truncated 證據，但對舊格式無法區分的組合須保留不確定性，不能武斷標成「只有文字截短」。補上真正的 v0.1.3 payload fixture。

## Important — 建議出貨前修正

### I1. 0.1.4 備份會被正式 0.1.3 拒絕，並非「舊 reader 忽略新欄位」就能讀取

**位置：** platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:157；同模組 BackupStager.kt:61、BackupRecords.kt:77；platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt:52。

新版 exporter 把 Room VERSION=4 寫入 manifest.schemaVersion。已核對正式 v0.1.3：它的 VERSION=3，BackupStager.kt:61 同樣會拒絕任何 schemaVersion > VERSION 的 manifest。拿任一新版匯出的正常備份交給舊版，讀到 manifest 就得到 UNSUPPORTED_VERSION，尚未走到 Message 或 truncationFlags。

兩版的 ignoreUnknownKeys=true 確實能處理額外 JSON key，但不能繞過這個版本檢查。CHANGELOG.md:150 及 BackupRecords.kt:77 的敘述因此不能用來保證整個 archive 的相容性。

| 方向 | 程式碼結論 |
| --- | --- |
| 0.1.3 archive → 本提交 reader | schema 3 通過；缺少 truncationFlags 時使用預設 null |
| 本提交 archive → 0.1.3 reader | schema 4 被拒絕，回報 UNSUPPORTED_VERSION |

這是相容性承諾不符，沒有發現舊 reader 因而破壞原資料；BRIEF 要求的「新版仍能讀取舊備份」方向並未被這個 gate 破壞，因此列為 Important。

**修正要求：** 若要維持雙向可讀承諾，須把備份相容版本與 Room schema 版本分開處理，並以正式舊版讀取器驗證。若產品只承諾新版讀舊檔，須刪除目前對反方向的保證，並補上兩方向的明確預期測試。

### I2. 所謂「逐則」旗標實際取自整批 snapshot，完整訊息也被標成截短

**位置：** platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:314；platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:152；core/parser/src/main/kotlin/dev/quietinbox/core/parser/StandardParser.kt:128、144。

**觸發：** 一份 MessagingStyle 通知包含兩則新訊息：A 為 4,097 個字元，B 為「收到」。SnapshotFactory 會把 A 截為 4,096 字元，並在整份 shape.truncated 放入 MESSAGES。Parser 取各自的文字，卻沒有把各則 BoundedText.truncated 傳入 MessageCandidate；ingest 每新增一列都複製相同的 snapshot.shape.truncated 子集合。

因此 A、B 都保存 MESSAGES，ConversationScreen.kt:505 都顯示「文字被截短」。只有 historic 訊息被截短時，新的正常訊息也會承襲 HISTORIC_MESSAGES。僅通知標題或未被 parser 選作 body 的 text 欄位截短，也會把完整 body 標記為截短。

**修正要求：** 由實際生成候選 body 的欄位攜帶逐則截短資訊，不能把批次彙總旗標直接當成該列自己的證據。測試需同時包含截短／完整訊息、historic／live 混合，以及被忽略的通知文字欄位。

### I3. InboxStyle 的 LINES 仍把整行捨棄當成文字截短，真正的行內截短卻沒被標記

**位置：** platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:54；platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:39；core/parser/src/main/kotlin/dev/quietinbox/core/parser/StandardParser.kt:158。

有兩個具體輸入：

- 只有一行 4,097 字元的 InboxStyle：BoundedText.of 會截短並設該行的 truncated=true，但這段 mapNotNull 沒有把它加入 shape.truncated。Parser 又只保留 line.value。若其他欄位未截短，資料列存 null，已被切短的 bubble 沒有任何截短標記。
- 33 行完整短文字的 InboxStyle：takeLast(32) 丟掉第一整行並設 LINES；新的 TEXT_TRUNCATION 卻包含 LINES，導致 32 則完整文字全顯示截短。DROPPED_MESSAGES 不包含它，所以丟掉的整行仍沒有對應缺口。

行數與行內文字的兩種損失在這條 producer 路徑原已存在；本提交沒有補齊，並開始把行數超限的 LINES 持久化成「這則文字被截短」。因此不能把新標記宣稱為已涵蓋所有訊息 body。

**修正要求：** InboxStyle 也須區分捨棄整行與保留行內截短，並把後者傳入各候選。分別驗證 1 行超長文字及 33 行正常文字；目前新增的 negative control 只涵蓋 MESSAGES／BIG_TEXT，抓不到此問題。

### I4. 移除／重新加入不處理來源缺口，刪除資料也留下新的來源 metadata

**位置：** platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:599、637；platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SourceRepository.kt:26、64。

**觸發一：** 暫停 A → 移除 A → 重新加入 A。移除沒有關閉 pause gap；addSource 只呼叫 sources.enable。新來源列為 enabled=true、paused=false，訊息已恢復擷取，舊 SOURCE_PAUSED_BY_USER 卻仍開放。停用後移除再加入亦同。直接對既有停用來源呼叫 addSource，也會啟用來源而未關閉 disabled gap。

**觸發二：** A 有 packageName=A 的缺口，使用者選擇 deleteData=true 移除。remove 的 transaction 刪除會話、媒體、抑制、摘要與診斷，完全沒有處理 gap_interval；因此 A 的 package 名稱和缺口列仍留在金庫。ADR-0007:32 的「移除來源資料包含整個刪除圖」未涵蓋這個新加入的來源歸屬。DeletionGraphTest.kt:189 也沒有種入或驗證來源缺口。

此外，直接移除正常啟用的來源只停止擷取／discard journal，沒有和停用相同的來源缺口記錄。這些是來源生命週期的缺項，單純修好 setSourceEnabled／setSourcePaused 的鎖還不夠。

**修正要求：** 讓 add／remove／enable／pause 使用一致的生命週期規則；明確處理停止擷取、重新加入及 deleteData 的缺口結果。兩種 deleteData 值都需要真實 DAO 回歸測試，並驗證別的來源與 process-wide 缺口保持正確。

### I5. 未知旗標被映射成「沒有截短」，把仍保存的損失資訊藏起來

**位置：** platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/Mappers.kt:68；core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Labels.kt:105。

**觸發：** 可通過目前備份 schema gate 的 record，或資料列，帶 truncationFlags="FUTURE_FLAG"。這是目前 String? 欄位可接受的輸入，不需要假設未來真的使用這個名稱。備份 export／restore 保留原字串，但 mapper 的 mapNotNull 會捨棄未知 token，得到 emptySet；truncationLabel 隨即返回 null。未知名稱沒有 crash，卻把「有損失但本版不認識種類」顯示成無標記。

「和其他 enum 一樣容錯」也不精確：同檔的 gap reason 會降為 GapReason.UNKNOWN；截短資訊沒有相應的未知狀態。

Journal 的行為又不同：NotificationShape.truncated 是序列化 enum set，而非上述不透明字串；pendingJournal 在解碼例外時會設 FAILED／DECODE 並清空 payload（IngestRepository.kt:111；Daos.kt:60），沒有記錄缺口。ignoreUnknownKeys 的存在不能證明未知 enum 名稱可讀。本輪未實際執行未知 enum 的 kotlinx serialization 測試，因此將解碼器細節列為待驗證邊界；解碼失敗後的處置可直接由程式確認。

**修正要求：** 對非空的未知截短資訊保留通用標記或未知狀態，避免以 emptySet 表達。分開測試資料列／backup 的純未知及混合 token，以及 journal 的不支援 enum／payload 版本。

## Minor — 文件及覆蓋描述

### M1. 測試名稱、CHANGELOG 與兩份 TEST_MATRIX 對已證明行為的描述不一致

**位置：** platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:105、936、954；docs/TEST_MATRIX.md:25；docs/zh-Hant/TEST_MATRIX.md:25。

- 「messages dropped … ingest … succeeded」實際呼叫 capturedWithTruncation，它以 title=null、text=null 的空 shape 建立 snapshot。真實 parser 會走空結果／SKIPPED，沒有成功寫入訊息的正向案例；測試也只 verify recordGap 呼叫。它確實補到了空解析這條路徑，但不能充當「成功 commit 後仍保留缺口」的測試。
- 「pausing one source never closes another」只操作一個 package，health 是 mock；證明的是呼叫 source-scoped 方法而非 global close，沒有建立 A、B 兩列驗證真實 DAO 行為。方法實作的 package 過濾可由程式確認，這不等於已有兩來源／交錯時序測試。
- 英文 TEST_MATRIX 新增了四個案例敘述，繁中版本只把 32 改為 36，案例列表沒有同步新增。
- docs/SCOPE.md:19 仍只列 1→2、2→3，:20 仍寫 32 個 coordinator tests，:50 仍把 per-id suppression token 指向「schema v4」，但本次 v4 沒有實作該項。應更新證據與延後版本描述。

本輪確認 coordinator 有 36 個測試宣告、storage instrumentation 有 17 個 @Test，且提交新增四個 coordinator 案例及一個 migration 案例；這些是來源盤點，不是測試通過證明。也未新增非 null 截短欄位的備份 round-trip、逐則截短或 SnapshotFactory 邊界測試。

### M2. 「沒有任何缺口能知道 conversation」不成立；七個 site 的數字也漏算本次新增點

**位置：** platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt:101；core/model/src/main/kotlin/dev/quietinbox/core/model/CaptureHealth.kt:54；CHANGELOG.md:67。

MESSAGES_DROPPED 只丟掉超量項目，留下的訊息仍可正常 ingest。CaptureCoordinator.kt:872 寫缺口後，同一次處理在 :887 resolve identity、:890 findConversationId；已有會話時即可取得目前系統認可的會話 id，新增會話時 commit outcome 也有 id。例子是帶同一 shortcutId 的既有會話收到 65 則訊息，留下的 64 則成功解析。

因此「記錄當下尚未求出 id」不等於「永遠不可求出」。IdentityResolver.kt:49 的 shortcut 結論仍保留 INFERRED_FROM_STREAM，不能把這裡推論成已證明真實來源身份；但這足以反駁該欄位在程式裡永遠無法填入的論據。

逐一盤點見後表：以正式碼的 recordGap 呼叫點計，舊版七個，本提交新增後為八個；其中三個直接握有 snapshot。若連 openGap 也算，還有六個呼叫點。**這不是要求本次一定加入 conversationId**；應把 schema 選擇說成目前未實作／刻意不關聯的取捨，而非不可發生的事實。

## Claims checked and found true — 已查核成立的主張

### 1. schema 3→4 的結構變更一致，未發現破壞性遷移

核對 platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt:110、Entities.kt:43／156，以及 platform/storage/schemas/dev.quietinbox.platform.storage.db.QuietInboxDatabase/3.json、4.json。

| 欄位 | Entity | migration SQL | schema 4／新建路徑 |
| --- | --- | --- | --- |
| gap_interval.packageName | String?，Kotlin 預設 null | ADD COLUMN packageName TEXT | nullable TEXT，沒有 SQL DEFAULT 子句 |
| message.truncationFlags | String?，Kotlin 預設 null | ADD COLUMN truncationFlags TEXT | nullable TEXT，沒有 SQL DEFAULT 子句 |

Kotlin 預設 null 不應和 SQL DEFAULT 宣告混為一談；這裡兩個建立路徑都沒有 SQL DEFAULT，舊列讀回新欄位為 NULL，彼此吻合。

本輪以記憶體 SQLite 依 schema 3 的 createSql 建表，插入代表性 gap／message，執行原樣的兩條 ALTER，再與 schema 4 新建表的 PRAGMA table_info 比對，兩表的結果完全一致。既有 gap 的 id=7、reason=LISTENER_DISCONNECTED、start=10，以及 message 的 id=9、body=body、fingerprint=fp 均保留，兩個新增欄位均為 NULL。匯出 schema 的 PK、FK、index 定義沒有額外變更。

MIGRATIONS 已加入 MIGRATION_3_4（QuietInboxDatabase.kt:117），DatabaseHolder.kt:112 會註冊該陣列；未新增 destructive fallback。migration 沒有 DELETE、UPDATE、DROP TABLE 或重建資料表。MigrationTest.kt:109 的確呼叫 runMigrationsAndValidate，並檢查代表性舊列及新欄位 NULL，最後還能插入有來源的新 gap。

**證據界線：** 以上支持新建與遷移的 schema 收斂，也未找到會使 Room schema validation 失敗的欄位差異；記憶體 SQLite 不能取代本輪尚未執行的 Android Room／SQLCipher validateMigration。

### 2. 新版讀舊備份的欄位預設，以及新版欄位的 export／restore mapping 成立

BackupRecord.Message 的 truncationFlags 確實附加在最後，且明確預設 null（BackupRecords.kt:82）。新版 stager 容許 schema 3；缺少此欄位的舊 record 依這個預設還原。BackupService.kt:183 匯出原始字串，:365 把字串寫回 MessageEntity；正式 export 建構點已改為具名參數，沒有發現參數錯位。

備份仍使用 newline-delimited JSON 置於 Tink streaming AEAD 容器內；本提交未修改 BackupCrypto，FORMAT_VERSION 仍為 1（BackupCrypto.kt:22）。這是原始碼與變更範圍確認，未宣稱已跑完實際密文來回還原。反方向的整檔相容性見 I1。

### 3. MessagingStyle 的旗標拆分本身正確

SnapshotFactory.kt:68／69 分別傳入 live／historic 的 text 與 dropped 旗標；:153 只在 list.size > MAX_MESSAGES 時設 droppedFlag，:154 保留最後 64 則，:157 只在保留訊息的文字被截短時設 textFlag。

CaptureCoordinator.kt:1014 的集合只包含 MESSAGES_DROPPED、HISTORIC_MESSAGES_DROPPED。因此，**對新格式 snapshot**，只有 MESSAGES／BIG_TEXT 的文字截短不會因這個條件產生 dropped-message gap。這個拆分不需要新增 TruncationFlag 的 exhaustive when 分支；但「無持久化相容成本」不成立，見 C4。

### 4. 已知旗標與來源資訊確實一路接到 UI

MessageEntity → Mappers.kt:68 → domain Message → ConversationScreen.kt:505 → Labels.kt:105 的資料鏈存在；非空且已知的截短集合會顯示 QualityTag。是否把正確旗標給了正確訊息則有 I2／I3 的問題。

GapIntervalEntity.packageName → Mappers.kt:94 → domain GapInterval → feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt:276 的鏈也完整。非 null 時顯示原因及 package 名稱；null 時保持原本只顯示原因的行為。

### 5. 正常順序的來源隔離成立，但不是完整生命週期保證

HealthRepository.kt:63 先以 reason 取得開放列，再明確比對 gap.packageName == packageName。由此可確認下列正常順序結果：

| 序列／情境 | 結果 |
| --- | --- |
| A 停用→重新啟用 | 關閉 A 的 disabled gap |
| A 暫停→恢復 | 關閉 A 的 paused gap |
| A 暫停→B 暫停→A 恢復 | B 的 package 不相等，不會被 A 的 close 關閉 |
| A 暫停→停用→啟用，但尚未 resume | disabled gap 關閉；來源 paused 狀態仍在，paused gap 保留符合狀態 |
| 完整 open 已落盤後重啟，再明確 resume／enable | close 依資料庫查詢，不依賴遺失的記憶體 gap id，可以關閉匹配列 |
| 升級前 gap 的 packageName=NULL | 不會被任何有名稱的來源 close 誤關，仍作 process-wide gap 顯示 |

上述未涵蓋兩個寫入半段之間的中止、重複同值及 remove／add，見 C1／I4。另查明升級時已經 disabled／paused 的 source_configuration 只會重載 policy，**不會補建來源缺口**；舊狀態的開始時間也無從由這兩個新增欄位還原，不能宣稱既有空窗已被此次 migration 補齊。

### 6. 五語系字串名稱、placeholder 與 plurals parity 通過

本輪實際執行 python3 tools/check-strings.py --locales，結果為 **OK: 0 error(s), 0 warning(s)**。工作樹與目標提交在受檢程式／資源上相同，後續 HEAD 差異只有 README 與 FUNDING。

en、zh-Hant、zh-Hans、ja、ko 均有 conv_truncated、gap_reason_source_disabled、gap_reason_source_paused、gap_reason_messages_dropped 四個新名稱；本次新增文字沒有格式參數。完整 gate 同時核對了既有名稱、替換參數與複數項目。本輪沒有把語意翻譯或母語審校也稱為已通過。

### 7. snapshot 到 commit 的提前返回盤點

下表的行號皆指 CaptureCoordinator.kt，除非另註。這裡區分「嘗試記錄」與「確保落盤」，避免把 C2 的 guarded 呼叫算作耐久保證。

| 位置／分支 | dropped-message 損失是否已被記錄 |
| --- | --- |
| offer :697／698／703／708：無 generation、全域暫停或非授權來源 | 正式路徑尚未建立 snapshot；不能把未讀的非來源通知推論為已擷取的 dropped-message 事件 |
| policy 未知時 hold／releaseHeld／dropHeld :705、506、577 | 走既有冷啟動處理；溢位／逾時有 COLD_START 記錄及補寫記憶。releaseHeld 的 factory 失敗只記 captureErrors，屬既有未完整記 gap 的路徑 |
| factory 失敗 :710 | 尚未形成可交付的 snapshot；只增加 captureErrors／lastError，沒有 gap。是既有路徑，本提交未修正 |
| offerCaptured :727–737 | JVM seam 重述 admission；不能當作正式來源會在授權前讀取 snapshot 的證據 |
| enqueue 失敗 :752／761 | 不到 dropped-message site；改嘗試寫 QUEUE_OVERFLOW，已增加 package。非同步 guarded 寫失敗沒有同樣的補寫機制，是既有路徑的限制 |
| process 入鎖前／入鎖後 admission :787／794 | 只增加 droppedAfterRevoke 後返回，不到新 site；安全圍籬保留，但不能保證批次損失義務已落盤 |
| journal insert 返回 false :799 | 同 eventId 的 insert conflict；本次停止重複處理。單憑此分支不能認定有新事件遺失，須看已有 journal row |
| journal 前後 VaultUnavailableException :803 | 外層會記 UNKNOWN lock-out，失敗時保存 vaultGapSince；但新 site 自己吞掉的例外不會到此，見 C2 |
| journal 寫入失敗 :821 | 嘗試 source-scoped UNKNOWN gap；若補寫也失敗，沒有在這一段留下補寫義務，屬既有路徑 |
| 第一個 commitFenced :864 | **在新 site 前**；pause／maintenance 留 PENDING，停用／移除可 terminal discard，反例見 C3 |
| parser exception :877、空解析 :882 | 新 gap attempt 已先執行；所以位置上的空解析修正成立。落盤失敗仍受 C2 影響 |
| identity／查詢失敗 :887–899 | gap attempt 已執行；後續保留 PENDING／重播可能重複，見 C2 |
| 第二個 commitFenced :903 | gap attempt 已執行；若事件仍 PENDING，重播仍可能再插入 |
| commit 空 outcome／成功、media overflow 或一般 return :904–948 | 新 site 已走過，這些 return 不會在位置上漏掉 gap；仍非缺口落盤的 transaction 保證 |
| replay 的非 PENDING 檢查 :978 | 已 terminal 的 row 不再處理；若 terminal 前已吞掉 gap 失敗，無法靠這個 recheck 補救 |
| pendingJournal 解碼失敗，IngestRepository.kt:111 | 不會到 processJournaled；目前寫 FAILED／DECODE，見 C4／I5 的格式與損失處理邊界 |

新 dropped-message site 的正常 live 呼叫在 :791 的 pipelineMutex 內，replay 呼叫在 :976 的同一 mutex 內；這部分符合單一 writer。新增來源 lifecycle 寫入則不在鎖內。guarded 會重拋 CancellationException，但取消發生在已提交來源設定與 gap 寫入之間，仍會留下 C1 的中間狀態。

### 8. 每個缺口記錄點的 scope 與 conversation 證據

以下盤點正式碼的全部直接呼叫，不計 HealthRepository 包裝方法的宣告，也不計 debug DemoDataRepository 的兩個合成 insert。recordGap 呼叫共有八個：

| 檔案：行 | 原因／資料 | 本次能知道什麼 |
| --- | --- | --- |
| HealthRepository.kt:45 | PROCESS_RESTART，懸置 session | process-wide；沒有單一 snapshot／conversation |
| CaptureCoordinator.kt:260 | 補記 UNKNOWN vault lock-out | 只有累積起點與恢復時間；process-wide |
| CaptureCoordinator.kt:451 | 補記 COLD_START 損失 | 累積時間；process-wide |
| CaptureCoordinator.kt:565 | COLD_START 溢位／過期 held 損失 | 以彙總起點呼叫；沒有單一會話 |
| CaptureCoordinator.kt:687 | MAINTENANCE | 整個金庫維護區間；process-wide |
| CaptureCoordinator.kt:761 | QUEUE_OVERFLOW | 握有完整 snapshot，package 已傳入；目前實作沒有求 conversation id，但 snapshot 可保留 shortcut／stream 證據，不能稱為原理上不可求 |
| CaptureCoordinator.kt:827 | journal 寫入失敗的 UNKNOWN | 握有 snapshot 並傳入 package；金庫失敗時不能保證查得到 conversation，不等於輸入完全沒有識別證據 |
| CaptureCoordinator.kt:872 | MESSAGES_DROPPED | 握有 snapshot；同一次後續 ingest 可 resolve／findConversationId，是 M2 的直接反例 |

此外，openGap 的六個正式呼叫點是：

| CaptureCoordinator.kt 行號 | 原因 | scope |
| --- | --- | --- |
| :317 | LISTENER_DISCONNECTED／NOT_GRANTED | process-wide |
| :371 | PAUSED_BY_USER | process-wide |
| :586 | COLD_START | process-wide |
| :619 | SOURCE_DISABLED_BY_USER | 指定 package，可涉及該來源的多個會話 |
| :629 | SOURCE_PAUSED_BY_USER | 指定 package，可涉及該來源的多個會話 |
| :811 | UNKNOWN vault lock-out | 此點握有 snapshot，但現行設計將整段 lock-out 彙總為 process-wide |

這支持「多數缺口應保持無會話歸屬」與「來源級暫停不可硬綁某個會話」，不支持「所有缺口永遠不可能得到會話 id」。是否儲存關聯應保留 scope 與 identity confidence 的區別。
