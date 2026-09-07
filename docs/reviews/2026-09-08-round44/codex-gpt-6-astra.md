Verdict: APPROVE WITH MINOR FIXES

本席未發現應阻擋 `v0.1.4` GitHub tag 的問題。Critical：0；Important：0；Minor：2，皆為商店文案與既有介面不完全一致，最小修正只涉及文案。本席不以這兩項 Minor 阻擋 tag；Google Play 仍是後續刻意執行的發布步驟。合併 roster 依最嚴格 verdict 判定，其他席若提出 REQUEST CHANGES，仍會阻擋 tag。

審查日期：2026-09-08。唯讀審查模式，未啟動任何 orchestration workflow。已確認 repository 為 `/Users/iml1s/Documents/mine/quietinbox`，分支 `main`，HEAD `8406bdcaa3f1542e35af87f8e5f355d36faf13cf`；本機 `origin/main` 指向 `c2db4a8d89617dc81ad92e5b61cdb1bb3d53f88a`。完整發布範圍 `v0.1.3..8406bdc` 共 62 個提交；`v0.1.3` 解參照後為 `d3cd83f4015c5e15d9c7fe5698c2890ebe85f1d5`。

已閱讀完整提交列表、版本提交差異、相關發布文件與 round 43 報告。`c2db4a8..8406bdc` 僅變更 13 個版本／發布文案檔案，其中 `app/build.gradle.kts:50`、`:51` 僅將版本改成 8／0.1.4；產品邏輯、測試與工作流程沒有新增差異。13 個檔案的工作樹內容均與指定 HEAD 的 Git blob 相同。

**Critical**

無。

**Important**

無。

**Minor — M1：部分商店用詞未沿用實際介面與目錄術語**

這是可讀性與一致性問題，不影響文案所述功能是否存在。

| 語系／項目 | 版本 8 文案 | 實際介面與建議對齊方式 |
| --- | --- | --- |
| zh-TW／vault | 「保險庫」 | `core/designsystem/src/main/res/values-b+zh+Hant/strings.xml:199` 使用「金庫」；`:70` 使用「加密金庫」。`fastlane/metadata/android/zh-TW/full_description.txt:4` 也使用「加密金庫」。建議該句改用「金庫」。 |
| ko-KR／vault | `금고` | `core/designsystem/src/main/res/values-ko/strings.xml:199` 使用 `보관소`；`fastlane/metadata/android/ko-KR/full_description.txt:4` 也使用 `암호화된 보관소`。建議改用 `보관소`。 |
| zh-TW／具名刪除操作 | 「全部刪除」 | `core/designsystem/src/main/res/values-b+zh+Hant/strings.xml:314` 是「刪除所有資料」，`:316` 是「刪除全部？」。引號內名稱宜對齊為「刪除全部」或「刪除所有資料」。 |
| zh-CN／具名刪除操作 | 「全部删除」 | `core/designsystem/src/main/res/values-b+zh+Hans/strings.xml:314` 是「删除所有数据」，`:316` 是「删除全部？」。宜對齊為「删除全部」或「删除所有数据」。 |

受影響位置如下，每個語系修正時應同步三個載體：

- zh-TW：`fastlane/metadata/android/zh-TW/changelogs/8.txt:1`、`fastlane/whatsnew/whatsnew-zh-TW:1`、`fastlane/release-notes.json:8`。
- zh-CN：`fastlane/metadata/android/zh-CN/changelogs/8.txt:1`、`fastlane/whatsnew/whatsnew-zh-CN:1`、`fastlane/release-notes.json:12`。
- ko-KR：`fastlane/metadata/android/ko-KR/changelogs/8.txt:1`、`fastlane/whatsnew/whatsnew-ko-KR:1`、`fastlane/release-notes.json:20`。

繁中術語判定依實際檔案：目前是「金庫／加密金庫」；「來源」與「擷取」則可在 `core/designsystem/src/main/res/values-b+zh+Hant/strings.xml:200` 對照。沒有依 brief 舉例推定介面一定使用「保險庫」。

**Minor — M2：日韓文對部分還原失敗的承諾比實作更強**

- 日文 `fastlane/metadata/android/ja-JP/changelogs/8.txt:1` 寫「復元でファイルを失ったときは完了とは言いません」，承諾這種情況不會稱為「完了」。相同文字在 `fastlane/whatsnew/whatsnew-ja-JP:1`、`fastlane/release-notes.json:16`。
- 韓文 `fastlane/metadata/android/ko-KR/changelogs/8.txt:1` 寫「파일을 잃은 복원은 완료라고 하지 않습니다」，同樣承諾不會稱為「완료」。相同文字在 `fastlane/whatsnew/whatsnew-ko-KR:1`、`fastlane/release-notes.json:20`。

實作的反例是 `BackupResult.Ok` 且 `mediaNotRestored > 0`：`feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsScreen.kt:416` 先顯示 `backup_result_ok`，`:418` 再附加未還原媒體提示。`core/designsystem/src/main/res/values-ja/strings.xml:297` 仍以「完了:」開頭，韓文 `core/designsystem/src/main/res/values-ko/strings.xml:297` 仍以「완료:」開頭；兩者的 `:299` 才補上失敗資訊。

因此實際行為是「完成摘要＋部分媒體失敗提示」。失敗資訊確實有揭露，故屬非阻擋文案 Minor。最小修正是把日韓三載體改成與英文、繁中、簡中一致的「若有媒體無法還原，會明確告知」，例如日文「復元できなかったメディアがあれば、その旨を明記します。」、韓文「복원하지 못한 미디어가 있으면 이를 명확히 알립니다.」。

**Observations — 五語商店文案的機械檢查通過**

以 Python 3 `len()` 計算 Unicode 字元；下表的檔案欄包含唯一一個檔尾換行，JSON 文字沒有該換行。

| 語系 | changelog／whatsnew 字元數，含換行 | JSON 文字字元數 | 三載體一致 | ≤ 500 |
| --- | ---: | ---: | --- | --- |
| en-US | 484 | 483 | 是 | 通過 |
| zh-TW | 176 | 175 | 是 | 通過 |
| zh-CN | 176 | 175 | 是 | 通過 |
| ja-JP | 254 | 253 | 是 | 通過 |
| ko-KR | 270 | 269 | 是 | 通過 |

位置為各語系 `fastlane/metadata/android/<locale>/changelogs/8.txt:1`、`fastlane/whatsnew/whatsnew-<locale>:1`，以及 `fastlane/release-notes.json:4`、`:8`、`:12`、`:16`、`:20`。實測關係均為 `changelog == whatsnew == JSON text + "\n"`；JSON 可解析，五個語系齊全且不重複，所有文案皆為純文字、沒有 Markdown 標記。

五語均涵蓋掉訊息、內文截短、重試後儲存失敗、來源暫停／停用區間、搜尋數量與命中定位、中等寬度返回箭頭、媒體逾時、還原失敗揭露與來源變更拒絕提示。除 M2 的承諾強度外，未發現實質功能範圍差異；未承諾回覆、標記來源已讀、擷取全部訊息，或取得來源未發布到通知的內容。

`python3 tools/check-strings.py --locales` 亦實際通過：0 errors、0 warnings。此工具驗證資源鍵與格式參數完整性；M1 的自然語言術語差異仍需人工比對。

**Observations — CHANGELOG 完整搬移，完整發布範圍有對應紀錄**

- `CHANGELOG.md:5` 的 `[Unreleased]` 內容為空；`:7` 為唯一的 `## [0.1.4] — 2026-09-08`。
- 以程式比對 `c2db4a8:CHANGELOG.md` 的 Unreleased 本文與目前 0.1.4 本文：從 `### Fixed` 起完全相同，共 393 行、49 個條目，沒有搬移遺失或新增重複。`:410` 起的 0.1.3 與更舊發布區段也逐位元組相同。
- `CHANGELOG.md:9` 的導言涵蓋此次實際變更，`:10` 與 `:142` 明確包括 issue #28。`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt:274` 的交易在重試耗盡時先呼叫 `lossOnExhaust()`（`:282`），再將 journal 列結案（`:284`），與這項發布聲明相符。
- `CHANGELOG.md:14` 明確寫出 `PARSE_`／`DECODE` 留在 `PENDING` 且沒有 gap 的問題是 issue #33，**不屬於本次發布**。本席沒有把它列為此輪阻擋項。

完整 `git log v0.1.3..8406bdc` 的產品變更，與發布本文的主要對應如下；並非只審版本號：

| 變更群組／代表提交 | 發布本文對應 |
| --- | --- |
| 媒體逾時、回收與備份保護：`08c2b10`、`32abcfc` | `CHANGELOG.md:17`、`:172`、`:180`、`:193` |
| Schema 3→4、截短證據、來源／journal 缺口與 issue #28：`9e379d3`、`f5f9581`、`a258662`、`f7a09ed`、`4b31f28` | `CHANGELOG.md:56`、`:84`、`:142`、`:255`、`:279`、`:389`；實際 migration 在 `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt:121` |
| 中寬返回、搜尋、onboarding、可及性與資訊揭露：`a290798`、`edd261f`、`389d7ce`、`ed98b49` | `CHANGELOG.md:47`、`:310`、`:333`、`:345`、`:371` |
| 來源拒絕提示、鎖定／Ready 時序與回歸測試：`868ee77`、`866b2d6`、`37da486`、`5723156`、`44296e5`、`5d21f7c` | `CHANGELOG.md:199`、`:210`；round 43 的通過證據見 `docs/reviews/2026-09-08-round43/codex-gpt-6-astra.md:9`、`:22` |

已讀回 round 43 留存的 baseline、Ready mutation 日誌與 fingerprint 探針 JSON，與該報告一致。其兩個 Low 觀察仍是測試排程／清理的既有非阻擋界限，不轉成此次產品修正要求。

**Observations — 文件沒有提前宣稱 0.1.4 已發布**

`README.md:29`、`:136` 與 `docs/SCOPE.md:37` 仍說 Play 提供 0.1.3，符合本輪提供的發布狀態；SCOPE 的 GitHub 已發布版本也仍列為 0.1.3。README 的 GitHub 連結是一般 Releases 頁面，沒有提前聲稱 `v0.1.4` 已存在。正式文件搜尋亦未發現此種提前宣告；CHANGELOG 的待切版本區段本身不構成已發布的宣告。

本機 tag 列表只有 `v0.1.0` 至 `v0.1.3`。遠端狀態未在本輪重新取得；本結論針對指定的切 tag 前快照。沒有要求將 README／SCOPE 的 Play 版本改成 0.1.4。

**Observations — tag 發布機制可執行，沒有已知的此提交專屬失敗原因**

1. `.github/workflows/release.yml:8` 接受 `v*` tag；`:41` checkout 該 ref。`app/build.gradle.kts:50`、`:51` 的 8／0.1.4 與預期 `v0.1.4` 相符。
2. `.github/workflows/release.yml:55` 還原 upload keystore，`:64` 起傳入簽章參數，`:67` 執行 JVM tests、`assembleRelease` 與 `bundleRelease`。`app/build.gradle.kts:31` 的環境簽章配置與 `:60` 的 release build type 共用於 APK／AAB。
3. `.github/workflows/release.yml:68`、`:70` 依序檢查 APK 權限與 tag 版本。現存 release APK 已在本輪通過兩個 gate，aapt2 亦直接證明 code 8／name 0.1.4。版本 gate 本身只驗 `versionName`；本輪另外讀回 code 8，沒有這次版本不符的缺口。
4. `.github/workflows/release.yml:81` 起收集 APK、AAB、plain mapping 與 gzip mapping，`:91` 產生四檔 checksums。`:122` 對 GitHub 附加 APK、gzip mapping 與 `SHA256SUMS.txt`；AAB 與 plain mapping 留在 workflow artifact。這與 `docs/RELEASE.md:46` 的說明一致。
5. `.github/workflows/release.yml:108` 使用 build 回報的實際 SHA 讀取發布本文。直接演練 `:118` 的原式 `awk` 與 shell command substitution，`NOTES` 得到 402 行、36,626 UTF-8 bytes，只有 `## [0.1.4]` 這一個版本標題，保留 issue #28 與 #33 排除文字，未混入 Unreleased 或 0.1.3；`:120` 再附加 checksums 說明。此次不會落入空本文 fallback。
6. `.github/workflows/release.yml:128` 使 Play job 僅在 `workflow_dispatch` 執行。純 tag 發布不執行 Play 上傳，符合 `docs/RELEASE.md:32`、`:34` 的分階段流程。

實跑 `actionlint .github/workflows/release.yml` 與 `bash -n tools/check-permissions.sh tools/check-version.sh` 均 exit 0。版本提交未變更 release workflow、發布文件或 gate scripts。

**Observations — 驗證證據與界限**

| 證據層 | 本輪確認結果 |
| --- | --- |
| 本輪實跑的靜態／檔案檢查 | 五語 `len()`、三載體比對、JSON／純文字檢查、CHANGELOG 搬移比對、原式 awk 擷取、strings gate、actionlint、shell syntax 全部通過。 |
| 現存本機 release APK | permission gate、version gate 通過；aapt2 為 `versionCode='8' versionName='0.1.4'`；apksigner 驗證成功，v2／v3 簽章均有效，憑證 SHA-256 與 `docs/RELEASE.md:17` 相符。 |
| 現存 JVM XML | 讀回 34 份報表，共 294 tests、0 failures、0 errors、0 skipped。這是已有報表的查核，本席沒有重跑 Gradle，也不將報表單獨當成精確 HEAD 的新測試紀錄。 |
| 已提供的本機建置 | `docs/reviews/2026-09-08-round44/BRIEF.md:17` 記錄 `8406bdc` 的 JVM／release／debug 建置成功；本輪以現存 APK 的版本、權限與簽章補充查核。 |
| 已提供的 GitHub CI | `docs/reviews/2026-09-08-round44/BRIEF.md:16` 記錄 [run 34153773217](https://github.com/ImL1s/quietinbox/actions/runs/34153773217) 在 `c2db4a8` 成功，涵蓋 JVM、Assemble／permission、API 29／35 instrumentation。此次 `gh api` 無法連線，網頁讀取亦失敗，因此這仍是已提供的前一提交 CI 證據。 |
| 已提供的裝置走查 | `docs/reviews/2026-09-08-round44/BRIEF.md:18` 的 FLAG_SECURE、onboarding、搜尋定位、720dp 返回等紀錄已核對；政策失敗 dialog 與 backup SAF 未注入。依本輪限制，沒有重跑裝置或 instrumentation。 |

現存 release APK SHA-256：`0385e62113b2c93140fa5db64ed4e3d021b1689d534d059f31e3286de39bca78`。

本輪沒有重建 0.1.4 AAB，未將較早留存的 AAB 當成此 HEAD 的發布證據。GitHub secrets、runner 與 tag workflow 的實際成功，仍須由之後的 tag run 證明；目前未取得顯示它們失效的證據。這些是發布後產物驗證的界限，並非已知的切 tag 阻擋缺陷。

依指定範圍，issue #17、#29–#32、#33 與 Play upload 均未列入修正要求。全程未執行任何 Git 寫入命令、未操作裝置或 Play Console、未修改產品／測試／文案；唯一寫入為本報告。
