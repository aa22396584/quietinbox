# Round 44 審查報告：0.1.4 發布就緒度審查 (Release Readiness of 0.1.4)

- **審查目標**：評估 `main` 分支在 commit `8406bdc` 是否已具備建立發布標籤 `v0.1.4` 之條件。
- **審查範圍**：
  1. 版本遞增變更 `git diff c2db4a8..8406bdc`（versionCode 8 / versionName 0.1.4、CHANGELOG 折疊、五國語言商店更新說明）。
  2. 自 `v0.1.3` 發布以來的所有 main 分支變更（`v0.1.3..8406bdc`，共 176 個檔案、21,384 行新增、503 行刪除）。
- **審查模式**：READ-ONLY（未修改任何產品代碼，未執行任何 git write 命令）。
- **審查路徑**：`/Users/iml1s/Documents/mine/quietinbox`。

---

## 審查結論 (Verdict)

### **APPROVE WITH MINOR FIXES**

- **Critical Findings**：0
- **Important Findings**：0
- **Minor Findings**：1
- **Observations**：3

**總結判定**：
本次審查確認 0.1.4 版本的核心產品代碼（經 Round 1 至 Round 43 嚴格迭代審查，特別是 Schema 4 遷移、通知遺失與缺口記錄、單一寫入鎖定、還原與取消原子性、多語系 UI 與無障礙支援）已經完全收斂且驗證完畢。CI 34153773217 與本地端各項驗證均通過（JVM 單元測試、Android 權限閘門、aapt2 版本比對、FLAG_SECURE 及模擬器端對端驗證）。

CHANGELOG 折疊完全符合規範，`[Unreleased]` 為空，導言真實且明確提及 issue #28 與 issue #33。文檔並未超前代碼，正確反映目前 Google Play 仍提供 0.1.3，且未宣稱 `v0.1.4` 標籤已建立。

唯一的 Minor 項目為：繁體中文（zh-TW）商店更新說明中使用了「保險庫」，而 App 內部 `values-b+zh+Hant/strings.xml` 的標準統一術語為「金庫」。建議在打標籤前順手微調為「金庫」，以維持術語 100% 一致。此問題不影響二進位程式碼與發布流程，修訂與否均可安全打下 `v0.1.4` 標籤。

---

## 問題分級清單 (Findings)

### Critical
無。

### Important
無。

### Minor

1. **繁體中文（zh-TW）商店更新說明中「保險庫」與字串目錄「金庫」之術語微差**
   - **檔案位置**：
     - [`fastlane/metadata/android/zh-TW/changelogs/8.txt:1`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/zh-TW/changelogs/8.txt#L1)
     - [`fastlane/whatsnew/whatsnew-zh-TW:1`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/whatsnew/whatsnew-zh-TW#L1)
     - [`fastlane/release-notes.json:8`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/release-notes.json#L8)
   - **現狀描述**：
     目前 zh-TW 的更新說明末句為：
     > 「來源變更被**保險庫**拒絕時會跳出對話框，不再默默沒動。」
   - **分析與對比**：
     - 依據 Round 18（commit `ee48710`）定義之專案術語對照標準：
       - `zh-Hans vault = 保险库`
       - `zh-Hant vault = 金庫`
     - 查閱 [`core/designsystem/src/main/res/values-b+zh+Hant/strings.xml`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-b+zh+Hant/strings.xml)，所有涉及 vault 的使用者可見字串均統一使用**「金庫」**（共 13 處，例如 `health_policy_failed_body_locked`：「金庫已鎖定，所以沒有做這項變更。請先解鎖再試一次。」、`backup_failed_vault`：「金庫已鎖定。」、`vault_locked_title`：「金庫已鎖定」、`delete_everything_desc`：「移除金庫、媒體、金鑰與設定…」），絕無使用「保險庫」。
     - 「保險庫」為簡體中文目錄（`values-b+zh+Hans/strings.xml`）所採用的翻譯。
   - **建議修訂**：
     將該句改為：
     > 「來源變更被**金庫**拒絕時會跳出對話框，不再默默沒動。」
     修改後字元數由 175 字減為 174 字，依然遠低於 Google Play 500 字上限，且能與繁體中文介面文案完美統一。

---

## 觀察與提示 (Observations)

1. **發布流程 `.github/workflows/release.yml` 的 AWK 註釋抽取命令精準度**
   - 經本地針對 `CHANGELOG.md` 執行指令：
     ```bash
     awk -v ver="0.1.4" '/^## \[/{p = index($0, "[" ver "]") > 0} p' CHANGELOG.md
     ```
     實測結果：AWK 能從第 7 行 `## [0.1.4] — 2026-09-08` 開始完整擷取所有 Fixed、Changed 等條目，並在抵達第 410 行 `## [0.1.3] — 2026-09-07` 時立即關閉輸出開關（`p = 0`），無任何尾部內容外洩，抽取出的 release notes 完整度與格式為 100%。

2. **Google Play 更新說明 500 字元上限檢測**
   - 使用 `python3 len()` 嚴格檢測五國語言的 `changelogs/8.txt`、`whatsnew-<locale>` 與 `release-notes.json`：
     - `en-US`：483 字元（上限 500，安全裕度 17 字元）
     - `zh-TW`：175 字元（上限 500，安全裕度 325 字元）
     - `zh-CN`：175 字元（上限 500，安全裕度 325 字元）
     - `ja-JP`：253 字元（上限 500，安全裕度 247 字元）
     - `ko-KR`：269 字元（上限 500，安全裕度 231 字元）
   - 三種來源檔案內容在五種語言下均完全 byte-for-byte 一致。

3. **無過度宣稱與 Play Store 格式就緒**
   - 商店說明中均無 Markdown 語法（無 `**`、`#`、`[]` 等），標點符號均適配各語系（中文使用「全部刪除」、日文使用「すべて削除」、韓文使用「모두 삭제」）。
   - 內容恪守產品原則，不誇大承諾「回覆」、「標為已讀」、「儲存全部訊息」或「取得來源未發出之內容」，僅如實描述「記錄缺口與標籤」、「搜尋命中導航」、「返回箭頭」、「媒體複製逾時」及「拒絕來源變更對話框」。

---

## 五大維度審查細項

### 維度 1：商店更新說明（Store notes）
- **字元數限制**：五種語言均小於等於 500 字（en: 483, zh-TW: 175, zh-CN: 175, ja: 253, ko: 269），全數通過。
- **宣稱對等性**：五種語言涵蓋之 6 項主張完全對齊：
  1. 記錄損失而非隱藏（缺口或標籤）。
  2. 搜尋不再將首頁視為總數，點擊可直接定位訊息。
  3. 中等寬度視窗提供返回導航箭頭。
  4. 媒體複製設有超時，避免卡死「全部刪除」或還原操作。
  5. 還原過程若遺失檔案會明確說明。
  6. 來源政策遭拒絕時彈出對話框而非靜默無效。
- **避免過度宣稱**：無任何違反三大原則之陳述。
- **術語符合度**：
  - 日文（`ja-JP`）精確採用「保管庫」、「ソース」、「復元」、「すべて削除」。
  - 韓文（`ko-KR`）精確採用「금고」、「소스」、「복원」、「모두 삭제」。
  - 簡中（`zh-CN`）精確採用「保险库」、「来源」、「还原」、「全部删除」。
  - 繁中（`zh-TW`）除上述 Minor 建議之「保險庫」宜為「金庫」外，其餘「來源」、「還原」、「全部刪除」、「對話框」均完全吻合。

### 維度 2：CHANGELOG 折疊（CHANGELOG fold）
- **`[Unreleased]` 狀態**：第 5 行 `## [Unreleased]` 下方為空行，第 7 行緊接著 `## [0.1.4] — 2026-09-08`，折疊乾淨。
- **導言真實性**：導言準確載明 `versionCode 8`，明列關閉之 issue #28（放棄儲存之遺失記錄）及 issues #22–#27，並明確宣告 `PARSE_` / `DECODE` 留下 `PENDING` 無缺口為 issue #33，**不屬於**本次發布。
- **內容完整性**：未發生任何條目丟失或重複，`### Fixed` 與 `### Changed` 分類結構清晰。

### 維度 3：文檔未超前代碼（Docs not ahead of code）
- [`README.md:29,136`](file:///Users/iml1s/Documents/mine/quietinbox/README.md#L29) 明確指出 Google Play 提供的是 0.1.3（2026-09-07 上架），並未提前修改為 0.1.4。
- [`docs/SCOPE.md:37`](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L37) 與 [`docs/zh-Hant/SCOPE.md:35`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/SCOPE.md#L35) 均正確記錄 Play 為 0.1.3，GitHub 為 0.1.3，沒有任何文檔宣稱 GitHub release `v0.1.4` 標籤已存在。

### 維度 4：發布流程機制（Release mechanics）
- [`.github/workflows/release.yml`](file:///Users/iml1s/Documents/mine/quietinbox/.github/workflows/release.yml)：
  - 觸發條件：`push: tags: ["v*"]`。
  - 建置產物：簽章 APK、AAB、mapping.txt 及 gzip 版本。
  - 檢驗閘門：
    - `tools/check-permissions.sh` 確保無任何網路權限（`INTERNET`、`ACCESS_NETWORK_STATE`、`QUERY_ALL_PACKAGES`）。
    - `tools/check-version.sh` 確保 APK 的 `versionName` 與標籤名完全一致（`0.1.4`）。
  - Release 說明組合：AWK 抽取結果加上 `SHA256SUMS.txt` 驗證說明，無註解行問題。
  - Play 獨立性：Google Play 上傳 job 受 `if: github.event_name == 'workflow_dispatch'` 保護，打標籤動作不會意外觸發 Play 上架。

### 維度 5：發布標籤阻擋條件（Release Blockers）
- 代碼庫目前乾淨（無未完成之程式碼修改）。
- 既有 CI run 34153773217 與本地 gradle/aapt2/permission 檢驗全部綠燈。
- 無任何阻止打上 `v0.1.4` 標籤的技術阻礙。

---

## 建議行動步驟 (Next Steps)

1. *(可選 minor 修正)* 將 `fastlane/metadata/android/zh-TW/changelogs/8.txt`、`fastlane/whatsnew/whatsnew-zh-TW` 與 `fastlane/release-notes.json` 中的「保險庫」替換為「金庫」。
2. 執行打標籤命令：
   ```bash
   git tag v0.1.4
   git push origin v0.1.4
   ```
3. GitHub Actions `Release` workflow 將自動建置簽章產物、驗證權限與版本並發布 GitHub Release。
4. Google Play 發布維持獨立之手動流程（如 `docs/RELEASE.md` 第 5 步所示）。
