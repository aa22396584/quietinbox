# 第 44 輪審查 — 0.1.4 的發版就緒度（QuietInbox）

- 審查者：Claude Opus 5（subagent，唯讀）
- 日期：2026-09-08
- 範圍：`git diff c2db4a8..8406bdc`（版本 bump）＋ `git log v0.1.3..8406bdc`（整個 0.1.4）
- HEAD：`8406bdc`（`main`，領先 `origin/main` 一個 commit）
- 本輪要回答的唯一問題：**這個 tag 可不可以切下去。** 產品程式碼到第 43 輪為止已審過，不重審。
- 輸出路徑：啟動 prompt 未指名檔名，沿用第 33–40 輪的慣例寫在 `docs/reviews/2026-09-08-round44/claude-subagent.md`。

---

## Verdict：**REQUEST CHANGES**

**沒有 Critical。** 建置、閘門、CHANGELOG 折疊、版本號、字數上限、字串目錄完整性全部通過，我逐項複驗過（見文末「已驗證清單」）。

擋住 tag 的是**兩個 Important，都在商店文案，都不需要動任何一行產品程式碼**。

擋 tag 的理由不是「文案不完美」，而是 `.github/workflows/release.yml:136-148`：

```yaml
  google-play:
    if: github.event_name == 'workflow_dispatch'
      - uses: actions/checkout@…              # line 137
        with:
          ref: ${{ needs.build.outputs.sha }}  # line 138  ← tag 那個 commit
          path: src
      - name: Upload AAB
          whatsNewDirectory: src/fastlane/whatsnew   # line 148
```

**Play 的 what's-new 是從 tag 的 commit 讀出來的，不是從 main 讀。** 也就是說：現在 tag 下去，`fastlane/whatsnew/*` 的內容就被封進 `v0.1.4` 裡；之後在 main 上修文案，`docs/RELEASE.md` 第 5 步所寫的 `workflow_dispatch` 路徑仍然會上傳舊文字。要救只剩三條路：移動 tag、改切 0.1.5、或改走 `gplay` 手動路徑（讀工作目錄，繞過 tag）。

先修再 tag 是一個 copy commit 的事（3 個 `changelogs/8.txt`、3 個 `whatsnew-*`、`release-notes.json` 三行），重跑一次 `len()` 檢查即可，**不需要重跑產品程式碼審查**。相對於「tag 之後再處理」的成本，先修明顯便宜。

---

## Critical

無。

---

## Important

### I1. 五種語言裡有三種的商店文案，用了 App 本身從未顯示過的詞

商店更新說明的作用是讓使用者更新完打開 App 找得到對應的東西。en 的文案是照著 en 目錄寫的（"gaps" ↔ `health_gaps_title` "Possible gaps"），但三個翻譯版偏離了自己的目錄。我用 `grep -c` 逐字確認過每個詞在該語言目錄裡的出現次數（全 repo 只有 `core/designsystem` 與 `platform/capture` 兩處 `res/`，兩處都查過）。

| 語言 | 文案用詞 | App 目錄實際用詞 | 該詞在目錄出現次數 |
| --- | --- | --- | --- |
| zh-TW | 缺口 | **中斷**（`values-b+zh+Hant/strings.xml:204` 「可能中斷」、`:248` 「中斷是指 QuietInbox 知道自己看不到的那段時間」） | 缺口 **0** 次 |
| zh-TW | 保險庫 | **金庫**（`values-b+zh+Hant/strings.xml:184` 「加密金庫已鎖定」、`:307` 「金庫已鎖定。」） | 保險庫 **0** 次（金庫 14 次） |
| zh-CN | 缺口 | **中断**（`values-b+zh+Hans/strings.xml:204` 「可能的中断」、`:248`） | 缺口 **0** 次 |
| zh-CN | 还原 | **恢复**（`values-b+zh+Hans/strings.xml:294` 「从备份恢复」、`:214` 「重置或恢复进行中」） | 还原 **0** 次（恢复 17 次） |
| ko | 금고 | **보관소**（`values-ko/strings.xml:184` 「암호화된 보관소가 잠겨 있습니다」、`:307`） | 금고 **0** 次（보관소 14 次） |

`grep -rn "保險庫\|금고" --include="*.xml" --include="*.kt" .` 在整個 repo 回傳空集合 —— 這兩個詞只存在於商店文案裡。

ja 全部對得上（欠落 ↔ `values-ja:204` 「欠落の可能性」、保管庫 ↔ `:184`、復元 ↔ `:294`），en 全部對得上。

**位置**
- `fastlane/metadata/android/zh-TW/changelogs/8.txt:1`、`fastlane/whatsnew/whatsnew-zh-TW:1`、`fastlane/release-notes.json:8`
- `fastlane/metadata/android/zh-CN/changelogs/8.txt:1`、`fastlane/whatsnew/whatsnew-zh-CN:1`、`fastlane/release-notes.json:12`
- `fastlane/metadata/android/ko-KR/changelogs/8.txt:1`、`fastlane/whatsnew/whatsnew-ko-KR:1`、`fastlane/release-notes.json:20`

**建議修法**（三份副本必須同步，見 O7 的一致性檢查）
- zh-TW：「現在都有缺口或標籤」→「現在都有**中斷**紀錄或標籤」；「被保險庫拒絕」→「被**金庫**拒絕」
- zh-CN：「现在都有缺口或标签」→「现在都有**中断**记录或标签」；「或还原」「还原若有文件失败」→「或**恢复**」「**恢复**若有文件失败」（保险库 ✓ 不用改）
- ko：「금고가 거절한 소스 변경」→「**보관소**가 거절한 소스 변경」（공백 ✓ 不用改，對應 `values-ko:248`）

> 附帶說明：啟動 brief 假設 zh-Hant 的詞是「保險庫／來源／擷取」。來源（30 次）與擷取（22 次）確實是目錄用詞，**保險庫不是**——目錄用的是金庫。brief 這一項的前提本身就錯，這也正是它沒被發現的原因。

### I2. ja 與 ko 把新缺口的成因說成「通知太多」，那是另一種缺口

0.1.4 新增的缺口對應 `gap_reason_messages_dropped`：**一則通知裡帶的訊息多到讀不完**。

- en `values/strings.xml:220`：`the notification held more messages than could be read`
- ja `values-ja/strings.xml:218`：`通知に読み切れない数のメッセージが入っていました`
- ko `values-ko/strings.xml:218`：`알림에 다 읽을 수 없는 수의 메시지가 있었습니다`

商店文案卻寫成：

- ja `fastlane/whatsnew/whatsnew-ja-JP:1`／`release-notes.json:16`：「**通知が多すぎて**落ちたメッセージ」＝ 通知的**數量**太多
- ko `fastlane/whatsnew/whatsnew-ko-KR:1`／`release-notes.json:20`：「**알림이 너무 많아** 떨어진 메시지」＝ 通知的**數量**太多

「通知數量太多而丟掉」在 App 裡是另一個缺口，而且是 0.1.4 之前就有的：`gap_reason_overflow`（`values-ja:212` 「キューのオーバーフロー」、`values-ko:212` 「대기열 오버플로」）。所以 ja / ko 的文案把本次新增的東西，說成了一個沒有變的東西。

en 只寫 "Dropped messages"（不談成因），不受影響。zh-TW／zh-CN 的「通知塞太多而丟掉的訊息」可以讀成「一則通知裡塞太多」，語意勉強成立，但也可讀成「通知則數太多」——見 M3。

**建議修法**
- ja：「通知が多すぎて落ちたメッセージ」→「**1 つの通知に読み切れない数のメッセージが入って**落ちたメッセージ」
- ko：「알림이 너무 많아 떨어진 메시지」→「**한 알림에 다 읽을 수 없는 수의 메시지가 들어 있어** 놓친 메시지」

（ja 修完會從 253 字增加，ko 從 269 字增加，兩者離 500 字上限都還很遠，修完請重跑 `len()`。）

---

## Minor

### M1. en 的開頭句比其他四語都絕對，而 issue #33 還在

`fastlane/whatsnew/whatsnew-en-US:1`／`release-notes.json:4`：

> `0.1.4 records losses instead of hiding them.`

其他四語都是「把**以前會藏起來的**損失記下來」（zh-TW／zh-CN）、「これまで隠していた損失を記録します」（ja）、「숨기던 손실을 기록합니다」（ko）——限定在「以前藏起來的那些」。en 少了這個限定，變成一個全稱句。

而 `CHANGELOG.md:14` 自己寫著：`PARSE_` / `DECODE` 留在 `PENDING` 且不記缺口，是 issue #33，**不在這一版**。也就是說仍有一整類損失沒有被記錄。對一個第一條規則就是「缺口只會被顯示、不會被藏」的產品，en 這句是站不住的全稱主張。

**建議**：en 改成 `0.1.4 records the losses it used to hide.`，與其餘四語對齊。

### M2. CI 的 JVM lane 沒有跑 `:platform:media` 與 `:core:designsystem`

`.github/workflows/ci.yml:35-40` 的 `Unit tests` 明列 16 個 task，缺兩個有測試的模組：

- `:platform:media:testDebugUnitTest` — `platform/media/src/test/kotlin/dev/quietinbox/platform/media/MediaReadTest.kt`，**10 個測試，本版新增**（`git diff --stat v0.1.3..8406bdc` 顯示 `MediaReadTest.kt | 142 ++`）
- `:core:designsystem:test` — `MonogramTest`、`TimeFormatTest`，8 個測試

我從本地 `build/test-results/**/TEST-*.xml` 統計，本專案 JVM 測試共 **294 個、0 failure/0 error**；上述 **18 個從來沒有在 CI 上跑過**。而 `MediaReadTest` 正好覆蓋商店文案主打的那條（媒體複製逾時、provider 消失回報成「連結已失效」而非「媒體太大」）。

brief 引用的 CI run 34153773217 綠燈，因此**不涵蓋**這 10 個新測試。

這與第 39 輪 Codex M1（「CI JVM lane never runs `:feature:health:testDebugUnitTest`」，`docs/reviews/README.md` 第 39 列）是同一類缺陷；那次補上了 `feature:health`，但沒有把清單改成不會再漏的形式。

**為什麼只算 Minor**：`.github/workflows/release.yml:67` 跑的是 root `test`（`./gradlew … test :app:assembleRelease :app:bundleRelease`），root `test` 會下推到每個子專案，本地跑同一條指令確實產生了 `platform/media/build/test-results`。**所以 tag build 本身的閘門是涵蓋的**，比 CI 還寬。這是 CI 衛生問題，不是 tag 的證據缺口。

**建議**（發版後處理即可）：把兩個 task 補進 `ci.yml:39`，或改用 `./gradlew test` 一次涵蓋全部。

### M3. 幾處與 App 標籤有落差的措辭（不影響正確性）

- zh-TW `changelogs/8.txt:1` 「卡住『全部刪除』」— App 的按鈕是 `delete_everything`「刪除所有資料」、確認標題 `delete_everything_confirm_title`「刪除全部？」（`values-b+zh+Hant:314`、`:316`）。「全部刪除」詞序相反。zh-CN 同（`values-b+zh+Hans:314`「删除所有数据」、`:316`「删除全部？」）。ja「すべて削除」對 `values-ja:316`「すべて削除しますか？」、ko「모두 삭제」對 `values-ko:316`「모두 삭제할까요?」都可接受。
- zh-TW／zh-CN 「通知塞太多而丟掉的訊息」語意雙解（見 I2）。建議「**一則通知裡塞太多訊息**而丟掉的訊息」／「**一条通知里塞太多消息**而丢掉的消息」。

---

## Observations（不擋 tag，記錄用）

**O1. `release.yml` 自 `v0.1.3` 以來改了 35 行，從未在任何一次 tag push 上跑過。**
`git diff v0.1.3..8406bdc -- .github/workflows/release.yml` 顯示：checkout v4.4.0→v7.0.1、setup-java v4.9.1→v6.0.0、setup-gradle v4.4.4→v6.3.0（＋`cache-provider: basic`）、upload-artifact v4.6.2→v7.0.1、**download-artifact v4.3.0→v8.0.1**、新增 `Release APK carries the tag's version` step（`:71-73`）、新增 `sha` output 與下游 `ref:`（`:79`、`:108`、`:138`）、`SHA256SUMS.txt` 拿掉註解行、release notes 追加 footer（`:120`）。

`ci.yml` 已在真實 run 上驗過 checkout v7 / setup-java v6 / setup-gradle v6.3.0+basic / upload-artifact v7。**沒有任何 run 驗過 `download-artifact@3e5f45b2… v8.0.1`**（只出現在 release.yml），也沒有驗過 `sha` output 串接、`check-version.sh` 在真 tag 上的行為、`:app:bundleRelease`、keystore 還原。

我逐項推過，沒找到會炸的理由：
- `awk` 抽取：以 `v0.1.4` 實測，取出 403 行／36,628 bytes，起於 `CHANGELOG.md:7`、止於 `:410` 的 `## [0.1.3]`。`## [Unreleased]`（`:5`）正確地把 `p` 設成 0；`### Fixed` 這類三井字號不符合 `/^## \[/`；em dash 標題不影響 `index($0,"[0.1.4]")`。GitHub release body 上限 125,000 字元，36.6 KB 在內。
- `set -e` 陷阱：`VERSION="${GITHUB_REF_NAME#v}"; [ -n "${{ inputs.tag }}" ] && VERSION=… && VERSION=…`（`:72`、`:76`）在 tag push 時 `inputs.tag` 為空，整條 `&&` 串回傳 1。我實測 `bash -e` 不會中止（POSIX：失敗的命令位於 `&&` 串中且不是最後一個 `&&` 之後的命令 → 豁免），腳本繼續執行、`VERSION=0.1.4` 正確。此機器只有 bash 3.2，runner 是 bash 5.x，但這條規則兩版一致。
- `gh release create … || gh release upload … --clobber`（`:122-123`）本身冪等；job 失敗可直接 re-run。
- upload-artifact v7 ↔ download-artifact v8 同屬 v4 之後的新 artifact API 家族，相容。

風險評估：低，且失敗可觀察、可重跑、不產生副作用（`google-play` job 有 `if: github.event_name == 'workflow_dispatch'`，tag push 一定跳過，`docs/RELEASE.md` 「a tag alone never touches Play」成立）。**但這是推論，不是證據**——這幾塊要等 tag 真的跑過才算驗過。

**O2. GitHub release 頁會非常長。** 403 行的 CHANGELOG 段落直接當 release notes。技術上沒問題，只是 0.1.3 是 62 行，這次是 6.5 倍。若想收斂，可在 `CHANGELOG.md` 的 0.1.4 段開頭放一段摘要、把細節收進 `<details>`——非必要。

**O3. `main` 領先 `origin/main` 一個 commit。** `git push --tags` 會推 tag 與其可達物件，workflow 會跑，但 `origin/main` 的分支指標仍停在 `c2db4a8`，且 `ci.yml`（`on: push: branches: [main]`）不會在 `8406bdc` 上跑——brief 引用的 CI 綠燈是 `c2db4a8` 的。建議 `git push origin main` 後再推 tag，或直接 `git push --follow-tags`，讓 CI 也在 `8406bdc` 上跑一次。

**O4. tag 一發，README 與 SCOPE 的 GitHub 欄位就會落後。** `README.md:29`／`:136-137` 與 `docs/SCOPE.md:37`（及 `docs/zh-Hant/SCOPE.md:35`）目前寫「Play：0.1.3；GitHub：0.1.3」。Play 那半在 Play 更新前是對的（brief 已聲明不要求改）；**GitHub 那半在 tag 發佈的當下就變成舊資訊**。方向是落後而不是超前，符合「docs must not run ahead of the code」，但是發完 tag 的收尾清單應該包含這一項。目前 repo 內沒有任何檔案宣稱 `v0.1.4` 已存在（`grep -rn "v0\.1\.4"` 只命中 `docs/reviews/2026-09-08-round44/BRIEF.md`），`docs/reviews/README.md` 也還是 43 列 —— 這點是乾淨的。

**O5. ja / ko 目錄本身就有兩套缺口詞，先前就存在。** ja：`:204` 「欠落の可能性」vs `:248` 「空白とは…」；ko：`:204` 「누락 가능성」vs `:248` 「공백은…」。ja 文案取 `欠落`（對標題）、ko 文案取 `공백`（對說明），各自都能在畫面上找到，所以不列為 I1 的一部分，但目錄自身該收斂。

**O6. 之後那個 Play 步驟的一個陷阱（超出本輪範圍，只留紀錄）。** `workflow_dispatch` 路徑會**重新 build** 一份 AAB（`release.yml:67` 在同一個 `build` job），和 tag 那次 release run 產出的二進位不是同一份 bytes（R8 非位元可重現）。若之後用 release run 的 artifact 做 `gplay deobfuscation upload`，mapping 會對不上 dispatch run 上傳的 bundle。`docs/RELEASE.md` 第 5 步的 `gplay` 手動路徑（0.1.2、0.1.3 實際採用的那條）用同一份 CI artifact 做 bundle 與 mapping，沒有這個問題。

---

## 已驗證清單（本輪實際跑過，非轉述）

| 項目 | 結果 |
| --- | --- |
| `app/build.gradle.kts:50-51` | `versionCode = 8`、`versionName = "0.1.4"` ✓ |
| aapt2 dump badging（`app/build/outputs/apk/release/app-release.apk`，本機既有產出） | `versionCode='8' versionName='0.1.4'`、`minSdkVersion:'26'` ✓ |
| 五語 `changelogs/8.txt` 字數（python3 `len()`，去尾換行） | en 483／zh-TW 175／zh-CN 175／ja 253／ko 269，**全部 ≤ 500** ✓ |
| 三份副本一致性 | `changelogs/8.txt` == `whatsnew-<locale>` == `release-notes.json[].text`，五語全部逐字相同 ✓ |
| Play-ready | 五語皆無 markdown（`**`／`` ` ``／`#`／列表／連結）、無控制字元、單行結尾換行 ✓ |
| `release-notes.json` 結構 | 五語齊全、鍵僅 `language`/`text` ✓ |
| CHANGELOG 折疊 | 以 awk 抽出新舊 body 逐行 diff：**只有開頭那段 lead 換掉**（3 行 → 6 行），其餘 399 行一字不差；`## [0.1.3]` 整段與 `c2db4a8` 完全相同 ✓ 無遺失、無重複 |
| CHANGELOG lead 真實性 | `:9` 點名 `versionCode` 8；`:11` 點名 issue #28；`:14` 明寫 issue #33 **不在這一版** ✓；`:5` `[Unreleased]` 為空 ✓ |
| `awk` release notes 抽取（模擬 `TAG=v0.1.4`） | 403 行／36,628 bytes，正確斷在 `## [0.1.3]` ✓ |
| `set -e` + `&&` 串（`release.yml:72`、`:76`） | 實測 `bash -e` 不中止，`VERSION=0.1.4` ✓ |
| `python3 tools/check-strings.py` | `OK: 0 error(s), 0 warning(s)` ✓ |
| 依賴漂移 | `git diff v0.1.3..8406bdc -- gradle/libs.versions.toml gradle/verification-metadata.xml` 皆為空 ✓（新增的三個 `build.gradle.kts` 只加既有的 `kotest`/`mockk`/`coroutines-test` 座標） |
| 本地 JVM 測試 | 294 tests、0 failure、0 error（`build/test-results/**/TEST-*.xml` 統計） |
| TEST_MATRIX 數字對帳 | `CaptureCoordinatorTest` 77 ✓、`BackupStagerTest` 21 ✓、`SearchViewModelTest` 5 ✓、`AnalyticsViewModelTest` 8 ✓、`ConversationViewModelTest` 1 ✓、core:* 80（model 6／parser 13／identity 5／reconcile 22／analytics 34）✓、`parsers:apps` 47 ✓、`app` 5 ✓ —— `docs/TEST_MATRIX.md:11,27,29,31,33` 所述數字**全部與實測相符**，docs 沒有超前 |
| 新增的 instrumented 測試落點 | `MessageBubbleSemanticsTest`（feature/conversation）、`PolicyFailureDialogTest`（feature/health）、`MediaDeletionRaceTest`（platform/media）、backup／storage 各測試——**全部落在 `ci.yml:113` 有跑的六個模組內** ✓ |
| 工作目錄 | `git status --short --branch`：僅 `?? docs/reviews/2026-09-08-round44/`，無未提交的產品變更 ✓ |
| repo 內是否宣稱 tag 已存在 | 無 ✓（`docs/reviews/README.md` 43 列，沒有第 44 列） |
| `fastlane` 目錄完整性 | 五個 locale 各有 `changelogs/{4,5,6,7,8}.txt`；`whatsnew-*` 五個檔齊全，檔名符合 `r0adkll/upload-google-play` 的 `whatsnew-<locale>` 規約 ✓ |

---

## 放行條件

修掉 I1 與 I2（一個 copy commit，三個檔案家族共 7 處），重跑：

```sh
python3 - <<'EOF'
import glob, json
for f in sorted(glob.glob('fastlane/metadata/android/*/changelogs/8.txt')):
    print(f, len(open(f, encoding='utf-8').read().strip()))
d = {e['language']: e['text'] for e in json.load(open('fastlane/release-notes.json', encoding='utf-8'))}
for l in ['en-US','zh-TW','zh-CN','ja-JP','ko-KR']:
    a = open(f'fastlane/metadata/android/{l}/changelogs/8.txt', encoding='utf-8').read().strip()
    b = open(f'fastlane/whatsnew/whatsnew-{l}', encoding='utf-8').read().strip()
    print(l, a == b == d[l].strip(), len(a) <= 500)
EOF
```

三份副本一致且五語皆 ≤ 500 之後，`git push origin main && git tag v0.1.4 && git push origin v0.1.4`（或 `git push --follow-tags`）即可。**不需要重跑產品程式碼審查、不需要重做裝置走查**——I1／I2 只動 `fastlane/`，不進 APK。

M1、M2、M3 可以併進同一個 commit（建議 M1、M3 併，M2 另開）；不併也不擋 tag。
