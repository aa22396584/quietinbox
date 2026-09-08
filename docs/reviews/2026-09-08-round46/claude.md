# Round 46 — Claude（Opus 5）獨立審查報告

**審查對象**：`git diff 0cca955..eff84c0`（單一 commit，HEAD `eff84c0`）
**日期**：2026-09-08
**依據**：`docs/reviews/2026-09-08-round46/BRIEF.md`

---

## 判定：REQUEST CHANGES

一句話的理由：**這個 diff 在修 P1「backup exclusive hang」時，把 `import()` 外層的 `withContext(Dispatchers.IO)` 一併拿掉了**，而 `VaultMaintenance.exclusive` 本身不切 dispatcher。結果是唯一的生產呼叫端（`SettingsViewModel.import`，`viewModelScope` → `Dispatchers.Main.immediate`）會在 **UI thread** 上執行 SAF 開檔、`StatFs`、以及整個 `apply()` 的 Base64 解碼 + Tink 加密 + 逐檔 `File.writeBytes`，而且是在持有 `pipelineMutex`、`maintenance.active = true` 的情況下。這是**本 commit 引入的** regression（`0cca955` 沒有這個問題），落在 P1 修改的正中央，不是既有缺陷。

其餘四項（P1 搜尋分頁、P2 還原誠實計數、P2 搜尋錯誤狀態、P2 CI JVM）在**程式邏輯層面**我逐條驗過，做法正確、沒有 brief 明令禁止的偷懶解法（沒有 `distinctBy(id)`、沒有只靠 coroutine cancel、沒有 `withTimeout` 包 exclusive）。P2「還原誠實」的**資料層正確、但使用者看到的那句話仍是匯出視角**，列為 Important。

> **審查時的工作目錄狀態（事實陳述，非審查範圍）**：我在審查途中偵測到 `platform/backup/.../BackupService.kt` 與 `BackupHangTest.kt` 出現**未提交**的工作目錄修改（`git status`：` M` 兩檔；HEAD 仍為 `eff84c0`）。該未提交變更正是把 `withContext(Dispatchers.IO)` 加回、把 `openInput` 移進 IO job，並新增 `applyThreadProbe` 與 `openAndApplyDoNotRunOnMain` 測試——也就是本報告 Critical-1 所指的缺陷。我的判定針對 **`eff84c0` 這個 commit**，不針對尚未提交的修補；若該修補隨後被提交，Critical-1 應由 mini re-review 就新 commit 重新確認，而不是視為本輪自動消解。

---

## Critical

### C1 — `import()` 把 SAF 開檔、`StatFs` 與整個 `apply()` 搬到呼叫端 dispatcher（生產上就是 main thread）

**位置**
- `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:263`（`suspend fun import` 已無 `withContext`）
- `platform/backup/.../BackupService.kt:288` — `val free = freeBytes()`（`android.os.StatFs`，:373）
- `platform/backup/.../BackupService.kt:292-301` — `maintenance.exclusive { … apply(db, staged) }`
- `platform/backup/.../BackupService.kt:315` — `val input = openInput(source)`（= `contentResolver.openInputStream`，**在 `importReads.launch` 之前**，跑在呼叫端 thread）
- `platform/backup/.../BackupService.kt:397-413` — media 迴圈：`Base64.decode` + `blobCipher.encryptToFile`
- `platform/backup/.../BackupService.kt:536` 與 `:541` — `for (f in writtenFiles) mediaDir.delete(f)`
- `platform/storage/.../db/VaultMaintenance.kt:85-97` — `exclusive` 只做 mutex + flag，**不切 dispatcher**
- `platform/crypto/.../BlobCipher.kt:86-104` — `encryptToFile` 是純 blocking（`tmp.writeBytes(ct)`），無 dispatcher 切換
- `platform/storage/.../retention/RetentionWorker.kt:159-163` — `MediaDirectory.file/delete` 純 blocking
- `feature/settings/.../SettingsViewModel.kt:106-110` — 唯一生產呼叫端：`fun import(...) = viewModelScope.launch { … backup.import(source, key) … }`

**對照基準**（`git show 0cca955:…/BackupService.kt:236`）：
```kotlin
suspend fun import(...) = maintenance.exclusive { importNow(source, recoveryKeyText) }
private suspend fun importNow(...) = withContext(Dispatchers.IO) { … apply(db, staged) }
```
舊版 `apply()` 是 `withContext(Dispatchers.IO)` 區塊的**最後一個表達式**，全程在 IO。新版整條路徑上唯一會離開呼叫端 thread 的只有 `db.withTransaction`（Room 自己切 transaction dispatcher），**它前面的 blob 準備迴圈與它後面的清理迴圈都不會**。

**失效情境**：使用者在「設定 → 匯入備份」輸入 recovery key → `viewModelScope.launch`（Main.immediate）→ `backup.import`：
1. `openInput()` 對 SAF/雲端 DocumentsProvider 做 binder IPC，`openFile` 本身就可能卡住或觸發下載——**這正是 P1 想解決的那類不合作 provider，第一次接觸卻被搬到了 UI thread**；
2. `freeBytes()` 的 `StatFs` 在 UI thread；
3. `apply()` 對備份裡**每一個** media 做 base64 解碼 + AES 加密 + 檔案寫入，全在 UI thread，且此時 `pipelineMutex` 已被持有、`maintenance.active = true`。一份含數十 MB 媒體的備份 = 數秒到數十秒的 UI 凍結，達到 ANR 門檻即被系統終止。行程若在寫完 blob、`withTransaction` 尚未提交時被殺，交易回滾但 `writtenFiles` 清理不會執行 → 媒體目錄留下孤兒密文檔（依 :529 的註解由 retention 掃除，是 leak 不是 loss）。

**諷刺點**：本輪 P1 的目的就是「不要讓不合作的 `read` 卡住整個金庫」。`read` 確實被移出 exclusive 了（C1 不影響那個結論，見下方驗證清單），但 `open` 與 `apply` 從 IO 掉到了 UI thread，換了一種凍結方式。

**為什麼現有證據沒抓到**：
- `BackupHangTest` / `BackupRoundTripTest` / `BackupCancellationTest` 都是從 `runBlocking` 或 `launch(Dispatchers.IO)` 直接呼叫 `service.import(...)`，**從未經過 `SettingsViewModel.import` 這條真實入口**，所以觀察不到 caller dispatcher 是 Main；
- 全 repo `grep -rn StrictMode` **零筆命中**，debug build 沒有磁碟/網路 policy 會叫出來；
- BRIEF 列的 device 證據是 `connectedDebugAndroidTest`，不是「設定 → 匯入備份」的 UI walkthrough。這正好落在專案自己 `feature-device-test-and-platform-pass` 那條 gate 的縫隙裡。

**方向（不開處方）**：dispatcher 與 exclusive 是正交的——把 IO context 加回「staging 之後、含 `freeBytes()` 與 `exclusive { apply }`」那一段，並把 `openInput` 移進 `importReads.launch`，兩者都不會重新把 `read` 拉回 exclusive 之內。需要一個能真正證明的測試：探針記錄 `apply` 與 `openInput` 實際跑在哪條 thread，且**從 `Dispatchers.Main` 呼叫 `import`**（負向控制：把 IO context 拿掉時該測試必須失敗）。

---

## Important

### I1 — 還原的 `skippedMedia` 用了匯出視角的句子，且沒說出「訊息已被標成 FAILED」這個後果

**位置**
- `feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsScreen.kt:417-418`
- `core/designsystem/src/main/res/values/strings.xml:303`（`backup_result_partial_media`）、`:304`（`restore_result_partial_media`）
- `platform/backup/.../BackupService.kt:478-482`（`mediaAbsent++`，同時把 row 寫成 `MediaState.FAILED`）

```kotlin
is BackupResult.Ok -> stringResource(R.string.backup_result_ok, …) +
    (if (result.skippedMedia > 0) " " + stringResource(R.string.backup_result_partial_media, result.skippedMedia) else "") +
    (if (result.mediaNotRestored > 0) " " + stringResource(R.string.restore_result_partial_media, result.mediaNotRestored) else "")
```

`backup_result_partial_media` = 「%1$d 個媒體無法讀取，未包含在此備份中。」這句是**寫給匯出**的。本 commit 讓 `skippedMedia` 在**還原**結果上也會 > 0（:481），於是使用者剛按完「匯入備份」，畫面卻說「未包含在此備份中」。

三個具體缺口：
1. **視角錯**：`backupResultText` 拿到的是同一個 `BackupResult.Ok`，型別上分不出匯出與還原，所以還原可能同時印出一句匯出腔（`skippedMedia`）＋一句還原腔（`mediaNotRestored`）。
2. **漏掉後果**：`restore_result_partial_media` 有「其訊息已標示」，`backup_result_partial_media` 沒有。但 :479 明確把這些 row 寫成 `FAILED` ——使用者被告知了數字，卻沒被告知那些訊息現在帶著什麼標籤。這正是 audit-2 ATOM-3 與專案「honest data-quality labels」在意的那一半。
3. 修正需要新字串（或讓結果型別能區分匯出/還原），並補齊五語系 + `check-strings.py`。

P2「restore honesty」的資料層（`else if` 互斥、不重複計數、`BackupRoundTripTest` 的 `skippedMedia shouldBe 1` / `mediaNotRestored shouldBe 0`）我確認**做對了**；沒做完的是使用者實際讀到的那句話。Round 44 剛因為 store-note terminology 被退過，這條同源。

---

## Minor

### M1 — `backup_failed_io` 是匯出腔，本輪新增了一條會踩到它的匯入路徑
`core/designsystem/.../values/strings.xml:308`：「無法讀取或寫入檔案。**備份未寫出**；除非最後的複製步驟本身失敗，否則既有的目標檔案不會被更動。」還原失敗時顯示這句是既有問題（舊版 `Reason.IO, "open"` 也會），**但本 commit 新增了 `Reason.IO, "read slot"`**（`BackupService.kt:321`），讓這句錯誤文案多一個觸發點。標為 pre-existing + 新觸發點，不計入本輪的新缺陷。

### M2 — 兩個卡死的 read 會永久停用匯入，且訊息不提示恢復方式；被遺棄的 read 會一直持有明文 recovery key
`BackupService.kt:105`（`MAX_LIVE_IMPORT_READS = 2`）、`:319-322`、`:338`。
`liveImportReads` 只在 IO job 的 `finally` 遞減；若 provider 連 `close()` 都不理，slot 永不釋放。第三次起匯入一律 `Failed(IO, "read slot")`，畫面顯示 M1 那句通用文案——使用者無從得知「重開 app 就好」。同一個 finally 才有 `key.fill(0)`，所以被遺棄的 read 期間，解碼後的 recovery key 位元組會一直留在記憶體。上限本身是合理設計（BRIEF 要求「Unbounded stuck jobs forbidden」，這條有做到），問題只在使用者可見性與 key 生命週期。

### M3 — `aWriteAfterKeyEpochChangeDoesNotLand` 只驗 reason，沒驗「沒 land」
`platform/backup/src/androidTest/.../BackupHangTest.kt:117-135`。測試斷言 `reason shouldBe KEY_UNAVAILABLE` 與 `keys.epoch shouldBe epoch + 1`，但**沒有斷言金庫內容未被寫入**（例如 message/conversation 計數不變）。CHANGELOG:19 寫的是「A write after a key-epoch change does not land.」——比測試實際證明的多了一步。這是 docs-ahead-of-code 的邊界案例，補一行計數斷言即可。

### M4 — 搜尋的 `now` 沒有隨 session 凍結（良性，但註解略微說滿）
`platform/storage/.../repo/SearchRepository.kt:50`（`now: Long = System.currentTimeMillis()`）；`SearchViewModel.kt:190` 與 `:119` 都沒傳 `now`，所以每一頁各取一次時鐘。`fromMs` 已正確凍結成 `frozenFromMs`（`SearchViewModel.kt:57`、`:161`）。因為 cursor 是 positional（`(sortKey, id)`），`now` 漂移只會把中途過期的列濾掉，**不會造成重複或跳過**，所以是良性。只是 `SearchUiState.sessionId` 的註解（`SearchViewModel.kt:44-48`）說 session 綁定了「frozen time range」，嚴格說 `now` 不在其中。

### M5 — `newSession` 在 `MutableStateFlow.update` 的 CAS retry 區塊內有副作用
`SearchViewModel.kt:103`、`:104`、`:147`、`:150`、`:152-163`：`newSession` 內含 `++sessionSeq` 與 `fromMs()`（讀時鐘）。`StateFlow.update` 在 CAS 失敗時會**重跑 lambda**，副作用因此可能執行多次。生產上 setter 全由 UI thread 呼叫、`update` 內無 suspension point，所以不會有競爭；後果也只是 id 多跳一號、時間重取一次，無害。但測試用 `Dispatchers.Unconfined`（`SearchViewModelTest.kt:34`），並沒有模擬 main-confinement，所以這層安全性目前是靠約定而非測試守住的。同理 `requestSeq`（`:73`、`:115`、`:184`）是裸 `var Long`，非原子。

### M6 — load-more 失敗是靜默的
`SearchViewModel.kt:120-128`：載入更多丟例外時只把 `loadingMore` 設回 false，`failed` 保持 false。畫面上「載入更多」按鈕原地復原，沒有任何訊息——與「成功但這一頁沒新命中」外觀完全相同。**這符合 BRIEF 的驗收條件**（保留 hits + cursor），但與專案「gaps are shown, never hidden」「soft-fallback 吞 exception」的自我要求有落差。列為 Minor 供取捨。

### M7 — 匯入不再對鎖定金庫 fail-fast
`BackupService.kt:296` 的 `holder.db()` 現在在 `exclusive` 之內、staging **之後**才呼叫；舊版（`0cca955:238`）在讀檔前就先取 db。現在使用者在金庫鎖定時匯入，會先把整份備份解密、staging 進記憶體，才得到 `VAULT_UNAVAILABLE`。是浪費與尖峰記憶體，不是正確性問題。

### M8 — 被較新匯入取代的那一次，回報 `Reason.MAINTENANCE`
`BackupService.kt:283`、`:285`、`:293` 的 token mismatch → `MAINTENANCE` → `backup_failed_maintenance`「正在重設或還原；請等它完成後再試一次」。字面上勉強成立（確實有另一次還原在跑），但拒絕它的並不是維護閘門。實務上 UI 有 `busy` 擋著（`SettingsScreen.kt:271`），難以觸發。

### M9 — `.gitignore` 的 scope creep
`.gitignore:26-36` 加入 `.omg/`、`*.pid`、`.env`、`.env.*`、`!.env.example`。與本輪五個缺陷無關的工具鏈設定混進了缺陷修復 commit。無害，但下次宜獨立成一個 commit。

### M10 — `CancellationException` 直接 rethrow 會永久殺掉 debounce collector（目前僅為理論風險）
`SearchViewModel.kt:191`（以及 `:120`）。`run()` 是在 `init` 的 `viewModelScope.launch { … collect { run(s) } }` 裡呼叫的；若 `searchPage` 丟出一個**並非本 job 取消**的 `CancellationException`（例如某層 `withTimeout` 洩漏出的 `TimeoutCancellationException`），該 launch 會靜靜結束，此後所有 `setQuery` 都不再觸發搜尋，畫面停在 spinner。我逐條看過 `SearchRepository.searchPage`（`:43-81`）與 `DatabaseHolder.db()`（`:61-68`），目前這條路徑上沒有任何東西會這樣丟，也不經過 `maintenance.work`（`MaintenanceCancellation` 是 `CancellationException`），所以**現在不是活的 bug**。rethrow 本身是正確做法；記錄在此僅供未來改動時留意。順帶一提，`CancellationException is not turned into an empty success` 這個測試（`SearchViewModelTest.kt:360-372`）只斷言 `searched/failed/results`，並沒有斷言 collector 之後還活著。

---

## 我獨立重跑的驗證（不採用 BRIEF 已列的證據）

| 檢查 | 指令 / 位置 | 結果 |
|---|---|---|
| CI 聚合任務真的排到 media | `./gradlew --dry-run test` | `:platform:media:testDebugUnitTest`（輸出第 534 行）、`:platform:media:test`（535）✅ |
| 聚合是否誤拉 release variant | 同上，`grep -c testReleaseUnitTest` | **0** ✅（不會多跑 release 單元測試） |
| 聚合覆蓋面 | 同上 | 21 個模組的 `:test`，含舊清單漏掉的 `:platform:media`、`:feature:inbox`、`:feature:settings`、`:core:designsystem`、`:core:testing` ✅ |
| 搜尋單元測試 | `./gradlew :feature:search:testDebugUnitTest` + 讀 XML | `tests="13" skipped="0" failures="0" errors="0"` ✅ |
| 五語系字串 | `python3 tools/check-strings.py` | `OK: 0 error(s), 0 warning(s)`，exit 0 ✅（`search_failed_title` / `search_failed_body` 在 en / zh-Hant / zh-Hans / ja / ko 五份都在） |
| 文件是否超前程式 | `docs/TEST_MATRIX.md`、`docs/zh-Hant/TEST_MATRIX.md` vs. `grep -c @Test` | `SearchViewModelTest` 13 = 實測 13；`BackupHangTest` 2 = 檔案 2 個 `@Test`；`BackupRoundTripTest` 3 = 檔案 3 個 `@Test`；兩語系數字一致 ✅ |
| #33 是否被宣稱關閉 | `grep -rn "#33" docs/ CHANGELOG.md` | `CHANGELOG.md:27`「Issue #33 stays open.」✅ 未被宣稱關閉 |
| 無 INTERNET / 不動來源通知 | 逐檔讀 diff | diff 內無 manifest / permission / `PendingIntent` / reply / dismiss 變更 ✅ |

### 逐條對照 BRIEF「Hunt especially」

| Hunt 項目 | 結論 |
|---|---|
| debounce collector 裡的 session vs 循序 `run()`（A→B→A，第一頁被 park） | ✅ 正確。`local` 是 StateFlow、`debounce` 上游是 rendezvous channel，collector 卡在 `run()` 期間中間值被 conflate，恢復後只看到**最新** session；`distinctUntilChanged` 只比 `sessionId` + vault state（`SearchViewModel.kt:83`），較新的 A 是新 session 會重跑。測試 `A then B then A applies only the later A's pages` 有覆蓋。 |
| session mismatch 時殘留 `loadingMore = false`（原始 bug） | ✅ 已修。四條會落地的路徑全部先比對 `sessionId` **且** `pageRequestId`，不匹配就原樣回傳 `cur`（`SearchViewModel.kt:123-126`、`:129-131`、`:194-196`、`:208`）。`run()` 的空查詢分支（`:177-180`）只比 session，但該 session 依定義從未發過請求，成立。`VaultState.Locked` 分支（`:87`）刻意不設防，鎖定即全清，正確。 |
| `retrySearch` 讀 `local.value.vaultOpening`（預設 true）而非 `vault.state` | ✅ 沒踩。`SearchViewModel.kt:99` 用的是 `vault.state.value !is VaultState.Ready`。`local` 的 `vaultLocked/vaultOpening` 只在 `state` 的 `combine`（`:75-77`）裡被填，`local.value` 永遠是預設值——這個陷阱被避開了。 |
| 匯入：`CompletableDeferred.await` vs join 一個 blocking read | ✅ 正確。`stageFromSource` 只 `await` deferred（`:345`），**不** join `importReads` 的 job；取消時 `finally` 關流並直接返回（`:346-348`）。 |
| 匯入：`withContext(IO)` 仍在等 | ✅ 不成立（等待是 deferred，非 blocking join）。⚠️ 但反向出了 **C1**：`withContext(IO)` 被整個拿掉了。 |
| 匯入：key 在 IO 使用中被歸零 | ✅ 沒踩。key 在 IO job 內解碼、在同一個 job 的 `finally` 歸零（`:325`、`:338`），呼叫端不再碰它。 |
| 匯入：`destroyAll` 之後仍套用 | ✅ 有擋。epoch 在 `:266` 擷取，於 `:286`（exclusive 前）與 `:294`（exclusive 內、`apply` 前）各檢查一次；token 在 `:283`、`:285`、`:293` 檢查三次。`BackupHangTest.aWriteAfterKeyEpochChangeDoesNotLand` 覆蓋（斷言深度見 M3）。 |
| 還原計數：`else if` 讓一則訊息不會同時是兩種 | ✅ 正確。`BackupService.kt:474-482` 是 `if (media != null && blob == null) … else if (media == null && m.mediaState == LOCAL_COPY) …`，互斥；`BackupRoundTripTest.kt:126-127` 同時斷言 `skippedMedia shouldBe 1` **和** `mediaNotRestored shouldBe 0`，是真正的區辨測試而非只驗一邊。 |
| CI：root `test` 是否真的排到 media，還是只在本機 dry-run | ✅ 我自己重跑 dry-run 確認（第 534 行）。註解（`ci.yml:35-38`）與行為一致。 |
| Test theatre：`awaitUntil { list.any { } }` 沒有 `shouldBe true` | ✅ 沒踩。`SearchViewModelTest.kt` 內每個 `awaitUntil { … any { … } }` 都以 `shouldBe true` 結尾（`:209`、`:237`、`:291`、`:309`；`:268` 用 `count { … } shouldBe 2`）；`awaitUntil`（`:37-42`）靠 `check()` 丟例外來輪詢，沒有斷言就永遠不會失敗，這點作者有守住。 |
| Test theatre：HangTest 在 `runBlocking` thread 上 blocking `CountDownLatch.await` | ✅ 沒踩。`BackupHangTest.kt:106` 是 `withTimeout(5_000) { while (entered.count > 0) delay(10) }`（可掛起的輪詢），阻塞的 `releaseHung.await()` 只發生在被遺棄的 IO thread 上（`:97`）。負向控制也在：若匯入真的把 exclusive 抱著，`:108` 的 `withTimeout(5_000) { maintenance.exclusive { 7 } }` 會逾時失敗。 |
| 文件超前程式 / #33 被宣稱關閉 | ✅ 兩者皆無（見上表）。 |

### 另外確認的兩點（brief 未列，但屬本輪修改的直接後果）

- **重複 key 崩潰面**：`SearchScreen.kt:166` 是 `items(state.results, key = { it.message.id })`。舊的 cursor 混用 bug 有可能讓同一則訊息在 `results` 出現兩次，那會是 Compose 的 `Key was already used` 崩潰，而不只是顯示錯誤。新的 session 模型讓首頁整批取代、load-more 只在同 session + 同 request 下附加，cursor 單調前進，重複不再可能——這個修法順帶關掉的是一個崩潰，值得記一筆。
- **陳舊結果不會閃現**：`newSession`（`:152-163`）刻意**不清** `results`，但 `SearchScreen.kt:135` 的 spinner 分支（`searching && !searched && !failed`）排在結果分支之前，所以新查詢在飛行中不會露出舊 session 的命中。這是對的，但它是靠 UI 分支順序守住的隱性契約；若日後有人調動 `when` 的分支順序就會破功，值得加一行註解。

---

## 給下一輪的最小清單

1. **C1 必修**：把 IO context 加回 `import()`（含 `freeBytes()` 與 `exclusive { apply }`），`openInput` 移進 `importReads.launch`；加一個從 `Dispatchers.Main` 呼叫 `import` 並探測 `openInput` / `apply` 實際 thread 的 instrumented 測試，且要有負向控制。
2. **I1 應同輪修**：還原專用的 partial-media 文案（或讓結果型別能區分匯出/還原），要說出「這些訊息已標為 FAILED」，五語系 + `check-strings.py`。
3. M3 補一行「金庫未被寫入」的斷言，讓 CHANGELOG 的 "does not land" 站得住。
4. M1 / M2 / M6 / M8 的文案與可見性，可併入下一次文案整理。
5. M9 的 `.gitignore` 建議日後獨立 commit。

修完後建議做一次真機 walkthrough：**設定 → 匯入備份（含 media 的檔案）**，這條路徑目前完全沒有 UI-entry 級別的驗證，C1 就是從這個縫隙漏出去的。

---

*本報告僅寫入 `docs/reviews/2026-09-08-round46/claude.md`。未修改任何產品 Kotlin / XML / CI，未執行任何 git 寫入指令，未操作裝置。執行過的驗證僅為唯讀：`./gradlew --dry-run test`、`./gradlew :feature:search:testDebugUnitTest`、`python3 tools/check-strings.py`、`git diff` / `git show`。*
