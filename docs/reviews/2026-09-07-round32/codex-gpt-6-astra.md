Verdict: REQUEST CHANGES

七個 action 的 SHA／版本核對全部通過；目前的 artifact 封裝、Java、checkout、Gradle provider 與權限設定，未發現本輪升級造成的執行回歸。需要修正的是新增文件對 checksum 覆蓋範圍的宣稱，以及 brief 明確要求追查、目前仍存在的手動發行版本來源不一致。以下將新增問題與既有問題分開標示；未將缺少 Release 實跑紀錄或假設性的 digest mismatch 當成升級失敗。

審查日期：2026-09-07。範圍：`68dd97f3def4298c3ce832d8b470857664564ce9..17bc5b089dd622b011972aac0d802da6c14a4f5f`，共六個 commits。開始及完成檢查時，本機為 `main`，HEAD 均是上述終點。實際差異共有五個檔案：兩份 workflows、Dependabot 設定，以及 `docs/SCOPE.md`、`docs/zh-Hant/SCOPE.md`；brief 所稱「全部在 .github/」不完全正確。以下 repo 行號均以審查終點為準。

**Critical**

未發現。七個 action 都沒有 SHA 與版本註解不符的情形，也未發現 `basic` 路徑會執行 proprietary caching component。

**Important**

**I1．新增的「每一個 Gradle artifact 都做 checksum 驗證」宣稱與實際設定不符。**

位置：`docs/SCOPE.md:51`、`docs/zh-Hant/SCOPE.md:49`；核對依據：`gradle/verification-metadata.xml:6`、`:8`，以及 `docs/RELEASE.md:69`。

這兩句治理說明是本範圍新增的，但驗證設定已將 `com.android.tools.build:aapt2` 放入 `trusted-artifacts`，而且沒有版本或平台限制。這類匹配項會略過 dependency verification，因此不能宣稱所有 artifact 都經 checksum 驗證。RELEASE 文件本身也記錄了 aapt2 的例外。本次發現的是新增安全覆蓋宣稱不實；這個 trust 規則本身早已存在，不能算成本輪新增的驗證繞過。[Gradle 官方對 trusted artifacts 的說明](https://docs.gradle.org/current/userguide/dependency_verification.html#sec:dependency-verification-ignoring-dependencies)

具體修正：兩份 SCOPE 都改為準確描述「Gradle dependency verification 已啟用，aapt2 目前屬於明示的 trusted-artifacts 例外」，並連到 RELEASE 的限制說明。若要保留「每一個」的宣稱，則必須先補齊各平台 aapt2 的驗證資料並移除該 trust 規則。就本輪的最小修正而言，更新兩份文件即可，不需要擴大修改建置流程。

**I2．既有：手動指定 tag 時，binary 與發行文案可能來自不同 commit。**

位置：`.github/workflows/release.yml:34`、`:36`、`:94`、`:104`、`:121`、`:123`、`:132`。

`build` 使用 `inputs.tag || github.ref` checkout。後續 `github-release` 的 checkout 沒有 `ref`，Google Play job 也只設定 `path: src`，所以這兩份 checkout 仍使用觸發 workflow 的 ref。`workflow_dispatch` 的 ref 與使用者自訂的 `inputs.tag` 是兩個不同欄位。[GitHub 的 workflow_dispatch 定義](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch)

例如從 `main` 啟動 workflow、指定舊 tag，AAB 會從舊 tag 建置，`src/fastlane/whatsnew` 卻取自 main。本次以 `git show` 比較已存在的 `v0.1.2` 與審查終點：前者英文 whats-new 描述 0.1.2，後者描述 0.1.3，足以重現來源不一致。這是唯讀內容比較，未聲稱實際向 Play 發布過錯誤文案。在「GitHub tag 已切、main 已前進、該 tag 首次送 Play」的正常流程中，這個錯配就會影響使用者看到的更新說明。GitHub Release 的 CHANGELOG 來源也有相同漂移風險。

具體修正：兩個下游 checkout 都設定與 build 相同的 `ref: ${{ inputs.tag || github.ref }}`，Google Play checkout 保留 `path: src`。更穩定的做法是 build 輸出已 checkout 的 commit SHA，兩個下游直接使用該 SHA。驗證時安排「workflow ref 與輸入 tag 不同、兩者文案不同」的案例，確認 binary、CHANGELOG 與 whats-new 同源。

此問題在 `68dd97f` 已存在，並非 checkout v7 新增的行為；因 brief 明確要求檢查 CI 看不到的 release 路徑，列為必須處理的既有發行問題。

**Minor**

**M1．新增的 Gradle 註解把 cache miss 說成清除快取，且對 proprietary component 的描述應更精確。**

位置：`.github/workflows/ci.yml:26`、`:57`、`:97`；`.github/workflows/release.yml:42`。

Basic provider 以一組指定 Gradle 檔案的雜湊產生 exact key，沒有 fallback restore keys。命中那些檔案的變更會產生新 key，不會刪除後端舊 cache entry。其 hash patterns 也不是所有建置設定：例如 root `gradle.properties` 不在該清單內。因此目前的「build files 改變便 clears it」描述過廣。[固定版本的 Basic 實作](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/sources/src/cache-service-basic.ts#L15-L22)、[key 計算](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/sources/src/cache-service-basic.ts#L156-L168)

具體修正：四處統一改成「basic 使用 @actions/cache，依指定 Gradle 檔案雜湊取得 exact key；key 改變時不使用 fallback restore，舊 entry 由 GitHub 快取淘汰機制處理」。對授權相關敘述，改寫為可由程式碼直接證實的事實：「action 套件包含 proprietary provider；basic 不載入或執行它」，避免把選 provider 與是否已接受全部授權條款混寫。

**M2．Alerts 確實開啟，但新增說明沒有交代 SHA-pinned Actions 的 alerts 盲點。**

位置：`.github/dependabot.yml:1`、`:5`、`:13`；`docs/SCOPE.md:51`、`docs/zh-Hant/SCOPE.md:49`。

本次唯讀 REST 查詢確認 `vulnerability-alerts` 回應 HTTP 204，`automated-security-fixes` 回應 `enabled: false, paused: false`。所以「alerts 已開、automatic security-update PR 已關」的設定狀態是正確的。

但 GitHub 官方文件明列：GitHub Actions 的 Dependabot alerts 只涵蓋 semantic version 引用，不涵蓋 SHA 引用。本 repo 的七個 actions 全部使用 SHA，版本註解不會將它們變成 semantic version 引用。因此不能把 alerts 視為這批 Actions version-update PR 漏開時的漏洞告警後盾。[Dependabot alerts 的官方限制](https://docs.github.com/en/code-security/concepts/supply-chain-security/dependabot-alerts#limitations)

具體修正：在新增說明旁補上 SHA-pinned Actions 不受此 alerts 機制覆蓋，需透過版本更新審查與上游 security advisories 追蹤。保留 SHA pin；不要為了取得 alerts 而改成可移動的版本引用。此項是覆蓋說明缺口，不是 alerts 設定未生效，也不代表已發現某個 action 有已知漏洞。

**Observations**

**O1．七個 pins 全數相符，已處理 annotated tags。**

直接從兩份 workflow 的 22 個 `uses:` 取得七組 action／SHA／版本。逐一使用 `gh api repos/<owner>/<repo>/git/ref/tags/<tag>`，當回傳 object type 為 `tag` 時，再呼叫 `git/tags/<object-sha>` 取得最後的 commit。結果如下；「commit」表示 tag ref 直接指向 commit。

| Action／版本 | REST tag object | 最終 commit，與 workflow pin 完全相同 | Repo call sites |
| --- | --- | --- | --- |
| [actions/checkout v7.0.1](https://api.github.com/repos/actions/checkout/git/ref/tags/v7.0.1) | commit | `3d3c42e5aac5ba805825da76410c181273ba90b1` | `ci.yml:20,51,86`；`release.yml:34,94,121` |
| [actions/setup-java v6.0.0](https://api.github.com/repos/actions/setup-java/git/ref/tags/v6.0.0) | commit | `dd06d9cba3e5552c54d9f8ea23572deb30010f7c` | `ci.yml:21,52,92`；`release.yml:37` |
| [gradle/actions/setup-gradle v6.3.0](https://api.github.com/repos/gradle/actions/git/ref/tags/v6.3.0) | annotated：`67621b124fd2e251c5e8a0e6e3b91318f2287669` | `9c971963bec38e04b3d30dcc455b5382be2fdbfb` | `ci.yml:25,56,96`；`release.yml:41` |
| [actions/upload-artifact v7.0.1](https://api.github.com/repos/actions/upload-artifact/git/ref/tags/v7.0.1) | commit | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` | `ci.yml:40,70,110`；`release.yml:81` |
| [actions/download-artifact v8.0.1](https://api.github.com/repos/actions/download-artifact/git/ref/tags/v8.0.1) | commit | `3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c` | `release.yml:95,117` |
| [reactivecircus/android-emulator-runner v2.38.0](https://api.github.com/repos/reactivecircus/android-emulator-runner/git/ref/tags/v2.38.0) | annotated：`c9c93e6ba9c4194322e114c40970d5983d9a07cc` | `a421e43855164a8197daf9d8d40fe71c6996bb0d` | `ci.yml:104` |
| [r0adkll/upload-google-play v1.1.5](https://api.github.com/repos/r0adkll/upload-google-play/git/ref/tags/v1.1.5) | commit | `e738b9dd8f2476ea806d921b64aacd24f34515a5` | `release.yml:125` |

表中的 `ci.yml`、`release.yml` 均位於 `.github/workflows/`。兩個 annotated object 也已分別查詢：[Gradle tag object](https://api.github.com/repos/gradle/actions/git/tags/67621b124fd2e251c5e8a0e6e3b91318f2287669)、[emulator-runner tag object](https://api.github.com/repos/reactivecircus/android-emulator-runner/git/tags/c9c93e6ba9c4194322e114c40970d5983d9a07cc)。

處理建議：維持現有 pins；沒有需要更換的錯誤 SHA。這證明版本註解與執行內容一致，不等同證明所有上游程式碼沒有漏洞。

**O2．預設 ZIP 路徑支持 upload v7 與 download v4／v8 互通。**

位置：`.github/workflows/release.yml:81`、`:83`、`:84`、`:95`、`:117`；`.github/workflows/ci.yml:40`、`:70`、`:110`。

上傳端未設定 `archive`，固定版本的預設值是 `true`。發布的 `dist/upload/index.js` 也確實走 ZIP stream；只有 `archive: false` 才設定 `skipArchive` 並改走單檔 raw stream。本 repo 上傳 `dist/` 目錄，沒有使用這項新模式。[v7 action input](https://github.com/actions/upload-artifact/blob/043fb46d1a93c77aae656e7c1c64a875d1fc6a0a/action.yml#L48-L53)、[v7 發布 bundle 的上傳分支](https://github.com/actions/upload-artifact/blob/043fb46d1a93c77aae656e7c1c64a875d1fc6a0a/dist/upload/index.js#L124229-L124304)

核對中間狀態 `upload v7 + download v4.3.0` 時，v7 的 CreateArtifact 雖送出 `version: 7`，RPC 服務仍是 `github.actions.results.api.v1.ArtifactService`。舊 v4 使用同一服務的 ListArtifacts／GetSignedArtifactURL，將 blob 作 ZIP 解壓，並在原始 blob stream 計算 SHA-256；它沒有依 action major number 拒絕這種 ZIP。因此相容性有分發程式碼的依據，不能只靠「上游沒再寫不相容公告」推定。[v7 RPC 服務](https://github.com/actions/upload-artifact/blob/043fb46d1a93c77aae656e7c1c64a875d1fc6a0a/dist/upload/index.js#L84005-L84010)、[舊 v4 解壓與 hash](https://github.com/actions/download-artifact/blob/d3f86a106a0bac45b974a628896c90dbdf5c8093/dist/index.js#L2157-L2255)、[舊 v4 internal download](https://github.com/actions/download-artifact/blob/d3f86a106a0bac45b974a628896c90dbdf5c8093/dist/index.js#L2299-L2342)

升到 v8 後，兩個消費端仍以 `name` 下載單一 artifact，直接展開至 `dist/`。v5 之後針對 artifact ID 下載的目錄行為調整不影響這裡；APK、AAB、mapping 與 SHA256SUMS 的現有路徑仍成立。Artifact 解壓後不保留原 POSIX 執行權限，也不影響這些資料檔。[v8 下載目的路徑實作](https://github.com/actions/download-artifact/blob/3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c/src/download-artifact.ts#L185-L197)、[v8 檔案權限限制](https://github.com/actions/download-artifact/blob/3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c/README.md#L341-L347)

處理建議：保留 `archive: true` 的預設及按名稱下載；不需要為了 major number 不同而退版。這次核對支持程式碼／格式相容性，沒有把未執行的跨 job artifact round-trip 當作實測成功。

**O3．Digest mismatch 改成失敗，對簽署後產物是合理改進；tag 與各發布 job 不具原子性。**

位置：`.github/workflows/release.yml:8`、`:88`、`:95`、`:99`、`:113`、`:117`、`:124`。

舊 download v4.3.0 已讀取 artifact digest，但 mismatch 只產生 warning。v8 的 `digest-mismatch` 預設為 `error`；下載結果有 mismatch 時會拋出錯誤，由外層 `core.setFailed` 令 step 失敗。校驗失敗是在下載流程後回報，磁碟可能已有展開的檔案，但目前後續步驟沒有 `always()` 或 `continue-on-error` 繞過它。[v4 的 warning 行為](https://github.com/actions/download-artifact/blob/d3f86a106a0bac45b974a628896c90dbdf5c8093/src/download-artifact.ts#L172-L202)、[v8 預設值](https://github.com/actions/download-artifact/blob/3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c/action.yml#L43-L47)、[v8 失敗分支](https://github.com/actions/download-artifact/blob/3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c/src/download-artifact.ts#L200-L251)

具體結果：

- Tag push：Git tag 在觸發 workflow 前就已存在。若 `github-release` 的下載 step 失敗，`:99` 的發布 step 不執行，首次發行會留下「tag 已推送、沒有 GitHub Release」；既有 Release 也不會被這次失敗下載更新。workflow 沒有回滾 tag。Google Play job 因不是 workflow_dispatch 而跳過。
- Workflow dispatch：哪一個 job 的下載失敗，就停止該 job 的發布。`github-release` 與 `google-play` 都只 `needs: build`，彼此沒有依賴；某一邊 mismatch 不會自動停止另一邊，所以可能出現 GitHub 已發布而 Play 失敗，或相反的狀態。不能宣稱一次 mismatch 會原子性地撤銷全部發布。

處理建議：保留 `error`，可在兩個下載 call site 顯式寫出 `digest-mismatch: error` 以固定意圖；不要為了讓 release 變綠而降成 `warn`。在 RELEASE 操作說明加入「tag 已存在但發行失敗」的檢查及重試步驟。若要求 Play 必須等 GitHub Release 成功，需另行讓 Play job 依賴它。Artifact digest 核對的是傳輸 blob，本次沒有額外驗證 APK 簽章。

**O4．Basic provider 的 main、post 都不載入 proprietary caching component；套件仍包含該 component。**

位置：`.github/workflows/ci.yml:25`、`:32`、`:56`、`:63`、`:96`、`:103`；`.github/workflows/release.yml:41`、`:48`。

已直接檢查 pinned SHA 的發布檔 `dist/setup-gradle/main/index.js`，不只閱讀 release notes。分流在 basic 時建立 BasicCacheService；只有 enhanced 分支才 dynamic import `sources/vendor/gradle-actions-caching/index.js`。main restore 與 post save 都透過同一 loader 選 provider，因此四處明示 basic 足以避開這個 vendor module 的載入與執行。[發布 bundle 的分流](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/dist/setup-gradle/main/index.js#L252-L253)、[可讀來源的 loader](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/sources/src/cache-service-loader.ts#L29-L69)、[main／post 呼叫](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/sources/src/setup-gradle.ts#L28-L94)

其他初始化確實會在選 provider 前複製 Gradle init scripts，但這不會繞路載入 proprietary caching module；可選的 Develocity 注入在未啟用時直接返回。上游 distribution 本身包含 vendor bytes，這與 basic 是否載入／執行它是不同事實。[init script 安裝](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/sources/src/gradle-user-home.ts#L8-L42)、[Develocity 的未啟用分支](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/sources/src/resources/init-scripts/gradle-actions.inject-develocity.init.gradle#L25-L35)、[固定版本 DISTRIBUTION](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/DISTRIBUTION.md#L5-L50)

處理建議：保留四處 `cache-provider: basic`，修正 M1 的說明即可。可以確認「不用 proprietary caching 實作」，不能將同一份 action 發行包描述成完全沒有 proprietary component。

**O5．Configuration Cache 與 tag cache-read-only 沒有本輪執行回歸。**

位置：`gradle.properties:4`；`.github/workflows/ci.yml:25`、`:56`、`:96`；`.github/workflows/release.yml:36`、`:41`、`:48`。

setup-gradle v6 移除的是 action 對 project Configuration Cache 的跨執行保存／還原支援，不會使 Gradle 的 `org.gradle.configuration-cache=true` 無效。Basic 只保存 Gradle User Home 的 `caches` 與 `wrapper`；project 的 `.gradle/configuration-cache` 不在這兩條路徑內。四個 call site 都沒有使用已移除的 action configuration-cache inputs。原生 Configuration Cache 仍能在目前 checkout／job 中運作，沒有證據顯示這次設定會把 project configuration cache 或簽署金鑰一起上傳到 basic cache。[上游移除支援的 commit](https://github.com/gradle/actions/commit/c999154b1f49a1d81018d696d0cbe259d9842be1)、[Basic 的 archive 路徑](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/sources/src/cache-service-basic.ts#L15-L22)、[Gradle 原生 Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache_enabling.html#config_cache:usage:enable)

固定版本 `cache-read-only` 預設為 `github.ref_name != github.event.repository.default_branch`，舊 v4 也是這個判斷。本 repo default branch 實查為 main，因此事件行為如下。[v6 input 預設](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/setup-gradle/action.yml#L25-L30)、[v4 input 預設](https://github.com/gradle/actions/blob/748248ddd2a24f49513d8f472f81c3a07d4d50e1/setup-gradle/action.yml#L17-L22)

| 情境 | 預設 read-only | 影響 |
| --- | --- | --- |
| push `vX.Y.Z` tag | `true` | 可嘗試還原可見快取；不回寫 |
| main 分支 CI | `false` | 可還原、保存 |
| pull_request CI | `true` | 不回寫 |
| 從 main dispatch，即使 `inputs.tag` 是舊 tag | `false` | checkout tag 不改變事件 ref，仍可保存 |
| 從 tag ref dispatch | `true` | 不回寫 |

處理建議：目前 tag push 的預設符合只讀意圖，無需修正。若專案政策是「所有簽署 release 建置都不得回寫」，就在 release 的 setup-gradle 明示 `cache-read-only: true`；這是政策明確化，並非 v6 引入的 regression。

**O6．Java、checkout 的已知 breaking changes 對現有 call sites 均不構成新故障，權限也足夠。**

位置：`.github/workflows/ci.yml:3`、`:8`、`:21`、`:52`、`:92`；`.github/workflows/release.yml:6`、`:20`、`:37`、`:90`、`:101`、`:125`。

- Setup-java：四處都是 `distribution: temurin` 與 Java 17，沒有使用被移除的 AdoptOpenJDK 名稱，也沒有傳入改名的 Maven username/password 或 GPG passphrase inputs。Pinned action.yml 仍列有部分舊名的 deprecated aliases；本 repo 不依賴它們。[固定版本 setup-java inputs](https://github.com/actions/setup-java/blob/dd06d9cba3e5552c54d9f8ea23572deb30010f7c/action.yml#L46-L96)、[v6 變更說明](https://github.com/actions/setup-java/blob/dd06d9cba3e5552c54d9f8ea23572deb30010f7c/README.md#L48-L79)
- Checkout：六處都在 push、pull_request 或 workflow_dispatch；沒有 pull_request_target／workflow_run，因此 v7 的 fork checkout 限制不會命中。Credential 改存在 RUNNER_TEMP 不影響目前流程；沒有需要在 Docker action 內使用 authenticated Git 的步驟，`gh release` 也明示 GH_TOKEN。I2 的 ref 漂移是原有設定問題。[固定版本 checkout 變更說明](https://github.com/actions/checkout/blob/3d3c42e5aac5ba805825da76410c181273ba90b1/README.md#L3-L25)
- Runner：新 actions 的 Node 24 要求 runner 至少 2.327.1；checkout 的 Docker authenticated Git 情境另需 2.329.0。本 repo 使用 GitHub-hosted `ubuntu-latest`。審查終點 CI 已成功跑過這些升級後的 action，沒有自架 runner 過舊的現況證據。Major upgrade 的跨度包含 runtime／ESM 等變更，不能概括為「只有 archive 這一個差異」。[download 的 runner 要求](https://github.com/actions/download-artifact/blob/3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c/README.md#L45-L52)

權限逐用途核對：

| 用途與位置 | 現有權限／認證 | 結論與處理建議 |
| --- | --- | --- |
| CI 與 release build checkout：`ci.yml:8`、`release.yml:20` | `contents: read` | 足夠，維持即可 |
| Gradle basic cache：四個 setup-gradle call sites | Actions runtime cache service | 不需額外增加 `actions: write`；dependency-graph 預設 disabled |
| 同 run artifact upload／download：`release.yml:81,95,117` | Actions runtime artifact service | 沒有傳 github-token／run-id／repository，不需為此補 `actions: read` |
| GitHub Release：`release.yml:90,101` | job `contents: write`，GH_TOKEN | 足夠，維持 job 範圍 |
| Google Play：`release.yml:125,127` | Play service account secret | GitHub contents 權限不是 Play API 的授權來源，升級未改此設定 |

跨 run／跨 repo artifact download 才需要另行提供適當 token 與目標資訊；目前沒有這種用法。[固定版本 download 的授權／跨 run 說明](https://github.com/actions/download-artifact/blob/3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c/README.md#L327-L339)、[Gradle dependency-graph 預設](https://github.com/gradle/actions/blob/9c971963bec38e04b3d30dcc455b5382be2fdbfb/setup-gradle/action.yml#L87-L105)

**O7．Dependabot 全域群組減少 PR 數量，但不保證相容性或必然開出 PR。**

位置：`.github/dependabot.yml:10`、`:13`、`:15`、`:21`、`:23`。

這個 `patterns: ["*"]` 群組合法。未設定 `applies-to` 時只涵蓋 version updates；未限制 `update-types` 時包含 major、minor、patch。它能減少同一輪相鄰 YAML 行的更新衝突，但任一不相關 action 的重大變更也可能拖住其餘修補，審查與回退粒度會變大。[Dependabot groups 官方定義](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference#groups)

群組不會驗證 upload／download 的協定是否相容，也不會要求兩者 major number 相同；本次相容的 v7／v8 就是反例。它更不能證明先前「提交建立 PR 後消失」的服務端問題已排除。本次沒有重做該歷史 Dependabot 故障的原因調查。

處理建議：可保留目前單一群組，但在維護規則明示逐 action 審查 major changes，必要時先拆出被阻塞的更新。若實際頻繁互相拖延，再將 upload／download 配成同一組，其餘更新另分組。這是維護取捨，沒有依此新增阻擋項目。

**O8．CI 已綠，但目前仍未覆蓋 artifact 下載與簽署發行路徑。**

位置：`.github/workflows/ci.yml:3`、`:33`、`:66`、`:104`；`.github/workflows/release.yml:49`、`:61`、`:95`、`:117`。

本次驗證結果：

- `git diff --check 68dd97f..17bc5b0` 通過。
- 兩份 workflow 皆以 `git show 17bc5b0:<path>` 傳入 `actionlint` stdin；actionlint 1.7.12 與可用的 ShellCheck 0.11.0 未報錯。
- 七組 SHA/tag 全數核對成功，包含兩個 annotated tag object；22 個 `uses:` 均納入核對。
- 審查終點的 [CI run 34085614005](https://github.com/ImL1s/quietinbox/actions/runs/34085614005) 已 `completed / success`，`head_sha` 正是 `17bc5b089dd622b011972aac0d802da6c14a4f5f`。JVM tests、assemble／permission gate 與 emulator jobs 的一般 CI 流程已有這次升級的實跑證據。
- Exact-head workflow runs 查詢只回傳 CI 與 Dependabot 動態工作，沒有 Release run。CI 本身沒有 download-artifact call site，也沒有執行 signed APK／AAB 的 release job；不能把 CI 綠燈寫成 signed release／Play upload 已成功。

具體補強：在 CI 加入不使用 secrets、也不發布產品的兩個 job，使用本次相同 pins 上傳及下載模擬 dist 內容，確認檔名、目錄布局與下載後 SHA-256；另外用「事件 ref 與輸入 tag 不同」的資料驗證 release 文案來源。這些可覆蓋本輪最容易漏掉的實際行為。簽署、真實 GitHub Release 與 Play 發布仍需各自的授權環境驗證，本次唯讀審查未執行。

本次沒有修改 source、workflow 或設定，也沒有執行 merge、push、PR 留言或發行操作。唯一寫入檔案為本報告。
