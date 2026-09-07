# 程式碼審查報告：Round 32 — GitHub Actions 升級 (`68dd97f..17bc5b0`)

**審查範圍**：`68dd97f..17bc5b0`（包含 6 個 commit）  
**審查檔案**：
- `.github/dependabot.yml`
- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`
- `docs/SCOPE.md`
- `docs/zh-Hant/SCOPE.md`  
**審查日期**：2026-09-07  
**審查者**：Gemini 3.8 Flash (High)

---

## 審查結論 (Verdict)

**APPROVE WITH MINOR FIXES**

本次升級範圍內的 6 個 commit 具備高度的工程嚴謹性：所有 action SHA 釘選皆與 upstream tag 精確吻合（含 annotated tag 解引用）；`setup-gradle` 透過 `cache-provider: basic` 完全阻斷了專有授權的 `gradle-actions-caching` 載入；`upload-artifact@v7` 與 `download-artifact@v8` 的互操作性與 digest 檢驗機制經過驗證無誤；`setup-java`、`checkout` 及 `setup-gradle` 的重大變更對本專案現有呼叫點均為安全且惰性（inert）。

然而，審查中發現一項存在於 `release.yml` 中的重要歷史缺漏（`workflow_dispatch` 觸發時 `checkout` 缺乏 `ref` 釘選，可能導致 Google Play 發布時抓取到 `main` 分支未發布的 release notes），以及 Dependabot 全局分組策略對未受 CI 覆蓋的發版動作可能帶來的隱性風險。修正建議如下文詳述。

---

## 核心主張檢驗與攻擊 (Claims Verification)

### 主張 1：每一個釘選的 SHA 皆與其 Tag 精確吻合 (Every pin matches its tag)
**檢驗結果：完全屬實（PASS）**

針對兩份 workflow（`ci.yml` 與 `release.yml`）中出現的全部 7 個 action，透過 GitHub API 進行 commit SHA 比對（包含輕量標籤與需要解引用的 annotated tag）：

1. `actions/checkout@v7.0.1`  
   - 參照：`git/ref/tags/v7.0.1`  
   - Commit SHA：`3d3c42e5aac5ba805825da76410c181273ba90b1`（完全吻合）
2. `actions/setup-java@v6.0.0`  
   - 參照：`git/ref/tags/v6.0.0`  
   - Commit SHA：`dd06d9cba3e5552c54d9f8ea23572deb30010f7c`（完全吻合）
3. `gradle/actions/setup-gradle@v6.3.0`  
   - 參照：`git/ref/tags/v6.3.0` -> Tag Object `67621b124fd2e251c5e8a0e6e3b91318f2287669` (Annotated tag)  
   - 簽章 commit：`9c971963bec38e04b3d30dcc455b5382be2fdbfb`（由維護者 Daz DeBoer 簽署，完全吻合）
4. `actions/upload-artifact@v7.0.1`  
   - 參照：`git/ref/tags/v7.0.1`  
   - Commit SHA：`043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`（完全吻合）
5. `actions/download-artifact@v8.0.1`  
   - 參照：`git/ref/tags/v8.0.1`  
   - Commit SHA：`3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c`（完全吻合）
6. `reactivecircus/android-emulator-runner@v2.38.0`  
   - 參照：`git/ref/tags/v2.38.0` -> Tag Object `c9c93e6ba9c4194322e114c40970d5983d9a07cc` (Annotated tag)  
   - Target commit：`a421e43855164a8197daf9d8d40fe71c6996bb0d`（完全吻合）
7. `r0adkll/upload-google-play@v1.1.5`  
   - 參照：`git/ref/tags/v1.1.5`  
   - Commit SHA：`e738b9dd8f2476ea806d921b64aacd24f34515a5`（完全吻合）

**結論**：無任何供應鏈偏離或 SHA 偽造風險。

---

### 主張 2：upload-artifact v7 與 download-artifact v8 具備互操作性 (Interoperability)
**檢驗結果：完全屬實（PASS）**

1. **上傳機制**：`upload-artifact@v7` 新增了 `archive` 參數，其預設值為 `'true'`。當上傳單一目錄（如 `release.yml` 的 `dist/`）時，它會保持既有的 zip 壓縮行為，透過 Actions Artifact 服務儲存為單一壓縮包，上傳的檔案路徑為相對於 `dist/` 的根目錄（`quietinbox-$VERSION.apk` 等）。
2. **下載機制**：`download-artifact@v8` 新增了 `Content-Type` 預檢。當下載的 artifact 為 zip 壓縮包時（即 `upload-artifact` 預設產物），其解壓縮行為與 v4 完全一致。
3. **目錄結構**：在 `download-artifact@v8` 中，使用 `name: release-${{ needs.build.outputs.version }}` 進行單一 artifact 下載時，解壓縮內容會直接解開至 `path: dist` 目錄下（不會多加一層同名子目錄）。
4. **向下相容**：無論是 `upload-artifact@v7` 配對 `download-artifact@v4`，或兩者皆升級至 v7/v8，由於底層 Actions Artifact v4+ 服務與預設 zip 傳輸協議一致，互操作性維持不變。

---

### 主張 3：v8 的 digest-mismatch-is-now-an-error 是一項改進，且發布失敗行為安全
**檢驗結果：屬實，且為 Fail-Closed 故障模式（PASS）**

在 `release.yml` 的情境下，深入分析若在下載發布產物時發生 digest 校验不符：
1. **中斷點**：`download-artifact` 步驟在比對雜湊不一致時直接拋出例外，執行 `core.setFailed()` 並中止 job（在 `github-release` 第 95 行或 `google-play` 第 117 行）。
2. **後續步驟**：`github-release` 中的「Publish release with the APK and checksums」步驟（`gh release create` / `upload`）與 `google-play` 中的「Upload AAB」步驟完全不會被執行。
3. **Git Tag 狀態**：由於 release workflow 是由 `push: tags: ['v*']` 或手動 `workflow_dispatch` 觸發，Git Tag 在 workflow 啟動前就已經存在於遠端倉庫。因此，**遠端確實會出現「已推 tag 但無 GitHub Release」的狀態**。
4. **安全性評估**：這項行為是正向且安全的改良。過去若 digest mismatch 僅為 warning，下載到損毀或被篡改的 APK/AAB 仍會被公開發布到 GitHub Release 或上傳至 Google Play Console，造成不可逆的用戶端損害。現在中斷後，維護者可直接透過 `release.yml` 的 `workflow_dispatch`（指定 `tag`）或在 GitHub 上重新觸發該 workflow 重新建置與發布，且第 107-109 行具備冪等處理機制（`--clobber`）。

---

### 主張 4：`cache-provider: basic` 確實避開了專有元件 `gradle-actions-caching`
**檢驗結果：完全屬實，且經散布程式碼驗證（PASS）**

直接審查 `gradle/actions` 於 commit `9c971963bec38e04b3d30dcc455b5382be2fdbfb`（v6.3.0）的散布檔案與源碼：
1. 在 `sources/src/cache-service-loader.ts` 中：
   ```typescript
   if (cacheConfig.getCacheProvider() === CacheProvider.Basic) {
       logCacheMessage(BASIC_CACHE_MESSAGE)
       return new BasicCacheService()
   }
   logCacheMessage(ENHANCED_CACHE_MESSAGE)
   return loadVendoredCacheService()
   ```
2. `loadVendoredCacheService()` 採用動態 `import(moduleUrl)` 去載入位於 `sources/vendor/gradle-actions-caching/index.js` 的專有元件。
3. 當傳入 `cache-provider: basic` 時，`BasicCacheService`（位於 `cache-service-basic.ts`）完全依賴官方開源的 `@actions/cache` 套件，且該動態 `import` 函式完全不會被執行。
4. 在打包後的 `dist/setup-gradle/main/index.js` 與 `dist/setup-gradle/post/index.js` 中，`gradle-actions-caching` 僅作為動態解析字串存在，未被靜態內聯進 bundle。只要設定了 `cache-provider: basic`，便絕不會載入或執行該專有模組。

---

### 主張 5：依賴項升級並未破壞專案既有運作 (Inertness of Breaking Changes)
**檢驗結果：完全屬實（PASS）**

逐一審查各重大變更在所有呼叫點的影響：
1. **`actions/setup-java@v6.0.0`**：
   - 移除了 AdoptOpenJDK 與部分 Maven 參數。
   - 本專案所有 4 個呼叫點均使用 `distribution: temurin` 與 `java-version: 17`，完全不涉及 AdoptOpenJDK 或 Maven 配置，完全相容。
2. **`actions/checkout@v7.0.1`**：
   - 調整了憑證持久化（`persist-credentials`）與封鎖 fork PR 的 `pull_request_target` / `workflow_run` checkout。
   - 本專案觸發條件為 `push`、`pull_request` 與 `workflow_dispatch`，不使用上述危險觸發器；發布步驟使用 `env: GH_TOKEN: ${{ github.token }}` 呼叫 GitHub CLI，不依賴 local git credential persistence。
3. **`gradle/actions/setup-gradle@v6.3.0`**：
   - 移除了 action 層級的 configuration-cache 跨 runner 儲存功能。
   - 本專案過去從未設定 `cache-encryption-key`（缺乏此金鑰本來就不會跨 runner 快取 configuration-cache）；專案在 `gradle.properties` 中宣告的 `org.gradle.configuration-cache=true` 是 Gradle 本地內部機制，經本機驗證兩次 `./gradlew help` 均能正常寫入與重用 configuration cache，對 GitHub CI 而言完全相容且惰性。

---

## 審查發現 (Findings)

### Critical (重大缺失)
*無*

---

### Important (重要發現)

#### 1. `release.yml` 的 `github-release` 與 `google-play` Job 中 `checkout` 缺乏 `ref` 參數，導致手動調度時抓取錯誤分支
- **位置**：`.github/workflows/release.yml:94` 與 `.github/workflows/release.yml:121-123`
- **問題描述**：
  在 `release.yml` 的 `build` job 中，第 34-36 行嚴謹地使用了：
  ```yaml
  - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
    with:
      ref: ${{ inputs.tag || github.ref }}
  ```
  然而在後續兩個 job 中：
  1. `github-release`（第 94 行）：
     ```yaml
     - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
     ```
  2. `google-play`（第 121-123 行）：
     ```yaml
     - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
       with:
         path: src
     ```
  當維護者透過 GitHub Actions 介面的 `workflow_dispatch` 觸發發布並指定舊版本標籤（例如發布已驗證的 `tag: v0.1.0`）時，workflow 的上下文 `github.ref` 為 `refs/heads/main`！
  - 在 `github-release` 中，`checkout` 會拉取當前 `main` 分支的 `CHANGELOG.md`，若 `main` 上的 changelog 已被後續 commit 改動，可能會導致發布筆記擷取異常。
  - 在 `google-play` 中更為嚴重：`checkout` 會將當前 `main` 分支檢出至 `src`，第 132 行的 `whatsNewDirectory: src/fastlane/whatsnew` 將會讀取 `main` 上的發布說明，而非該標籤當時凍結的發布說明。
- **具體修正**：
  在兩處的 `checkout` 均補上 `ref: ${{ inputs.tag || github.ref }}`：
  ```yaml
  # .github/workflows/release.yml:94
  - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
    with:
      ref: ${{ inputs.tag || github.ref }}

  # .github/workflows/release.yml:121-124
  - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
    with:
      ref: ${{ inputs.tag || github.ref }}
      path: src
  ```

#### 2. Dependabot 的 `patterns: ["*"]` 全局分組會掩蓋僅在 Release 中使用的 Action 破壞
- **位置**：`.github/dependabot.yml:21-22`
- **問題描述**：
  在 `commit 17bc5b0` 中，為了防止單獨 PR 衝突與 PR 上限漏單，設定了 `groups: actions: patterns: ["*"]`。
  然而，`ci.yml` 只測試了 `checkout`、`setup-java`、`setup-gradle`、`upload-artifact` 與 `android-emulator-runner`。
  **`actions/download-artifact` 與 `r0adkll/upload-google-play` 僅存在於 `release.yml` 中，日常 PR 的 CI 完全不執行**。
  若 Dependabot 開立合併 PR 同時升級所有 actions，即使 `upload-google-play` 或 `download-artifact` 出現破壞性變更（例如輸入參數更動），該 PR 的 CI 狀態仍然會顯示綠燈。維護者容易因 CI 全綠而直接合併，導致直到正式發版（tag push）時才遭遇 release 失敗。
- **具體修正**：
  建議將僅用於 release 的 actions 與日常 CI actions 解耦分組，或明確排除第三方發版 action：
  ```yaml
  # .github/dependabot.yml
  groups:
    ci-actions:
      patterns:
        - "actions/checkout"
        - "actions/setup-java"
        - "gradle/actions/*"
        - "actions/upload-artifact"
        - "actions/download-artifact"
    external-actions:
      patterns:
        - "r0adkll/upload-google-play"
        - "reactivecircus/android-emulator-runner"
  ```
  並且在 `ci.yml` 中考慮加入一個輕量化的 dry-run 步驟（或在 `assemble` job 下載上傳的 artifact），使 `download-artifact` 亦能受到 CI 的日常驗證。

---

### Minor (次要事項)

#### 1. `cache-read-only` 預設表達式在 Tag Push 時的行為驗證
- **位置**：`.github/workflows/release.yml:41-48`
- **問題描述**：
  `setup-gradle` 內建的 `cache-read-only` 預設為：
  `${{ github.event.repository != null && github.ref_name != github.event.repository.default_branch }}`
  當推動 release tag（如 `refs/tags/v0.1.0`）時，`github.ref_name` 為 `v0.1.0`，與 default branch（`main`）不同，因此 `cache-read-only` 會自動評估為 `true`。
- **影響評估**：
  此行為完全正確且符合預期：
  1. Release job 能讀取由 `main` CI 產生的既有 Gradle 依賴快取，加速建置。
  2. 避免將針對單一 tag 建置產生的快取寫回 GitHub Cache（GitHub Actions 的快取範圍機制中，tag 快取無法被 `main` 或未來的 PR 繼承，寫入只會白白浪費專案 10GB 的快取配額）。

#### 2. `permissions:` 權限區塊充分性確認
- **位置**：`.github/workflows/ci.yml:8-9` 與 `.github/workflows/release.yml:20-21, 90-91`
- **問題描述**：
  - `ci.yml` 頂層宣告 `permissions: contents: read`：升級後的 `checkout@v7`、`setup-java@v6`、`setup-gradle@v6`（`dependency-graph: disabled` 預設）、`upload-artifact@v7` 均不需要額外的 token 權限。
  - `release.yml` 頂層宣告 `permissions: contents: read`，並在 `github-release` job 中覆寫宣告 `permissions: contents: write`：提供給 `gh release create` 建立版本與上傳檔案使用。
  - `google-play` job 透過 service account JSON 進行驗證，不需額外 GitHub token 權限。
- **結論**：權限設定精準遵循最小特權原則（least privilege），完全正確。

---

### Observations (觀察與記錄)

1. **全面遷移至 Node 24 執行期環境**：
   本次升級後，工作流程中引用的所有 action（`actions/checkout@v7.0.1`、`actions/setup-java@v6.0.0`、`gradle/actions/setup-gradle@v6.3.0`、`actions/upload-artifact@v7.0.1`、`actions/download-artifact@v8.0.1`、`r0adkll/upload-google-play@v1.1.5`、`reactivecircus/android-emulator-runner@v2.38.0`）的 `runs.using` 均為 `node24`。在 GitHub-hosted `ubuntu-latest` 上具備高度一致性，消除了 Node 20 棄用警告。
2. **SCOPE.md 治理文件更新確實**：
   `commit 17bc5b0` 中在 `docs/SCOPE.md` 與 `docs/zh-Hant/SCOPE.md` 同步補充了 Dependabot alerts 與 Gradle verification metadata 的關聯決策記錄，文檔維護相當嚴密。

---
