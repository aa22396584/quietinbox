# Round 45 審查報告：0.1.4 商店更新說明修訂再審 (Mini Re-review of Round-44 Store-Note Fixes)

- **審查目標**：針對 Round 44 審查中提出的商店更新說明（store notes）問題（Claude I1、I2 及相關 minor 修訂）在 commit `a93762e` 上的修復情況進行精確再審，評估是否可放行 `v0.1.4` 標籤。
- **審查範圍**：`git diff 8406bdc..a93762e`（Round 44 報告歸檔及 11 處商店文案修改）。產品代碼未變更。
- **審查模式**：READ-ONLY（未修改任何產品代碼，未執行任何 git write 命令）。
- **審查路徑**：`/Users/iml1s/Documents/mine/quietinbox`，HEAD `a93762e`。

---

## 審查結論 (Verdict)

### **APPROVE**

- **Critical Findings**：0
- **Important Findings**：0
- **Minor Findings**：0
- **Observations**：0

**總結判定**：
Round 44 中由 Claude 提出並阻擋發版標籤的兩項 Important 議題（I1：目錄術語脫節；I2：缺口成因描述混淆）以及建議的 Minor 修訂（M1、M3 及 ja/ko 還原說明優化），已在 commit `a93762e` 中被**完全、精確、無遺漏地修復**。

經全面程式化複驗：
1. **I1 判定關閉**：zh-TW 已將「缺口」改為「中斷紀錄」、「保險庫」改為「金庫」；zh-CN 已將「缺口」改為「中断记录」、「还原」改為「恢复」；ko 已將「금고」改為「보관소」。五語版本 8 的所有商店文案中，「保險庫」、「缺口」、「금고」剩餘出現次數為 **0**；zh-CN 中「还原」剩餘出現次數為 **0**。
2. **I2 判定關閉**：ja 與 ko 不再將遺失原因歸為「通知太多」，而是精確描述為「單一通知內包含過多訊息無法讀取」（ja：「1 つの通知に読み切れない数のメッセージが入って落ちたメッセージ」；ko：「한 알림에 다 읽을 수 없는 수의 메시지가 들어 있어 놓친 메시지」），且 zh-TW/zh-CN 亦同步採用此精確措辭。
3. **附帶改進項目確認**：en 開頭已收斂為 `"records the losses it used to hide"`（避免在 issue #33 未解前作出全稱過度承諾）；zh-TW/zh-CN 正確對齊 `delete_everything` 按鈕字串（「刪除所有資料」／「删除所有数据」）；ja/ko 亦不再作出「不會顯示完成」的過度承諾，改為如實說明「若有未成功復原的媒體會明確告知」。
4. **硬性指標 100% 達標**：
   - Python `len()` 去尾空白後字元數：en-US (480)、zh-TW (183)、zh-CN (184)、ja-JP (273)、ko-KR (294)，五語全數嚴格符合 **≤ 500 字元**。
   - `changelogs/8.txt` == `whatsnew-<locale>` == `release-notes.json`：五語、三處副本共 15 份文本逐字完全相等。
   - 無任何 Markdown 語法或控制字元。
   - 無任何過度承諾（over-claim）。

本輪阻擋發版之障礙已全部排除，**可以安全建立 `v0.1.4` 發布標籤**。

---

## 逐項驗證結果 (Verification Details)

### 1. I1：術語與 App 資源目錄嚴格對齊（已關閉）

針對 Round 44 指出文案用詞脫離 App 本身字串目錄之問題，逐語核驗：
- **zh-TW**：
  - 「缺口」→「中斷紀錄」：對齊 `core/designsystem/.../values-b+zh+Hant/strings.xml:204`（`health_gaps_title`「可能中斷」）與 `:248`（「中斷是指…」）。
  - 「保險庫」→「金庫」：對齊 `values-b+zh+Hant/strings.xml` 中 14 處統一使用的「金庫」（如 `:184`「加密金庫已鎖定」、`:307`「金庫已鎖定。」）。
- **zh-CN**：
  - 「缺口」→「中断记录」：對齊 `values-b+zh+Hans/strings.xml:204`（`health_gaps_title`「可能的中断」）與 `:248`。
  - 「还原」→「恢复」：對齊 `values-b+zh+Hans/strings.xml` 中 14 處統一使用的「恢复」（如 `:294`「从备份恢复」），「还原」在文案中已完全清除。
  - 保留「保险库」：對齊 `values-b+zh+Hans/strings.xml` 中 14 處統一使用的「保险库」（簡體目錄使用「保险库」，繁體目錄使用「金庫」）。
- **ko**：
  - 「금고」→「보관소」：對齊 `values-ko/strings.xml` 中 14 處統一使用的「보관소」（如 `:184`「암호화된 보관소가 잠겨 있습니다」、`:307`），「금고」在文案中已完全清除。
- **禁詞掃描**：
  - 在 `fastlane/metadata/android/*/changelogs/8.txt`、`fastlane/whatsnew/*` 及 `fastlane/release-notes.json` 全域搜尋 `保險庫`、`缺口`、`금고`、`还原`（zh-CN），檢索結果均為 **0**。

### 2. I2：新缺口成因精準表述（已關閉）

- 0.1.4 新增的缺口對應 `gap_reason_messages_dropped`（單一通知內訊息過多無法讀取），而非 0.1.4 之前的佇列滿載 `gap_reason_overflow`（通知數量過多）。
- **ja-JP**：由「通知が多すぎて落ちたメッセージ」改為「**1 つの通知に読み切れない数のメッセージが入って**落ちたメッセージ」，精準對齊 `values-ja/strings.xml:218`（「通知に読み切れない数のメッセージが入っていました」）。
- **ko-KR**：由「알림이 너무 많아 떨어진 메시지」改為「**한 알림에 다 읽을 수 없는 수의 메시지가 들어 있어** 놓친 메시지」，精準對齊 `values-ko/strings.xml:218`（「알림에 다 읽을 수 없는 수의 메시지가 있었습니다」）。
- **zh-TW / zh-CN**：亦同步採用無歧義表述「一則通知裡塞太多訊息而丟掉的訊息」／「一条通知里塞太多消息而丢掉的消息」，消除了將「通知」誤解為複數篇數的可能性。

### 3. Also Taken：附帶改進項目驗證

1. **en 開頭限定語境（對齊 issue #33 現狀）**：
   - 舊文案：`0.1.4 records losses instead of hiding them.`（全稱判斷句，與 CHANGELOG 記載 issue #33 PARSE_/DECODE 仍留在 PENDING 不記缺口衝突）。
   - 新文案：`0.1.4 records the losses it used to hide.`（正確限定為「過去會隱藏的那些損失」，與中、日、韓語意完全對齊）。
2. **zh 刪除所有資料（對齊 `delete_everything` 按鈕）**：
   - zh-TW：改為「刪除所有資料」，對齊 `values-b+zh+Hant/strings.xml:314`。
   - zh-CN：改為「删除所有数据」，對齊 `values-b+zh+Hans/strings.xml:314`。
3. **ja / ko 備份還原提示精確化（避免過度承諾）**：
   - 舊文案承諾「發生檔案遺失時不會說完成」（但實際 UI 在部分媒體失敗時仍會顯示訊息匯入完成，並附帶顯示 `restore_result_partial_media` 警示）。
   - 新文案修訂為：
     - ja：「復元できなかったメディアがあれば、その旨を明記します。」
     - ko：「복원하지 못한 미디어가 있으면 이를 명확히 알립니다。」
     - 真實反映 App 行為，消除不實承諾。

### 4. 仍需滿足之硬性規範查驗清單 (Checklist)

| 查驗維度 | 規則要求 | 驗證方法與數值 | 狀態 |
| :--- | :--- | :--- | :---: |
| **Play 字數上限** | Python `len(text.strip()) <= 500` | en-US: 480<br>zh-TW: 183<br>zh-CN: 184<br>ja-JP: 273<br>ko-KR: 294 | **PASS** |
| **三副本一致性** | `changelogs/8.txt` == `whatsnew` == `release-notes.json` | 逐語言自動化字元比對，5 個 locale 均完全一致 | **PASS** |
| **格式規範** | 無 Markdown（無 `**`、`` ` ``、`#`、`[ ]()` 等），單行純文字結尾換行 | 正則表達式檢測，未發現任何 Markdown 標記 | **PASS** |
| **內容真實性** | 無過度承諾，不涉及未宣稱功能 | 逐句對帳產品規格與功能實現 | **PASS** |
| **發布腳本相容** | `check-strings.py` 驗證 | `python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)` | **PASS** |
| **二進位閘門** | Manifest 權限閘門與版本號比對 | `tools/check-permissions.sh` → OK（無網路權限）<br>`tools/check-version.sh ... 0.1.4` → OK | **PASS** |

---

## 結論與後續建議

commit `a93762e` 乾淨、精準地解決了 Round 44 留下的所有商店文案問題，沒有任何副作用或多餘改動。
目前審查判決為 **APPROVE**，已可執行後續標籤切換與發布流程：

```bash
git push origin main
git tag v0.1.4
git push origin v0.1.4
```
