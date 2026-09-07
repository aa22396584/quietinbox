# Review round 45 (mini re-review of round-44 store-note fixes) — QuietInbox

READ-ONLY. No product-code edits, no git writes, no devices, no Play. Do not activate orchestration workflow modes.

Repository `/Users/iml1s/Documents/mine/quietinbox`, HEAD `a93762e`. Review `git diff 8406bdc..a93762e` (round-44 reports plus the store-note copy). Product APK unchanged.

Round 44 combined **REQUEST CHANGES** (Claude I1, I2). Codex and agy were APPROVE WITH MINOR FIXES. Claude then hit its weekly limit; it is not a seat this round.

## Closed if true
- **I1**: zh-TW 缺口→中斷、保險庫→金庫; zh-CN 缺口→中断、还原→恢复; ko 금고→보관소. Zero remaining hits of 保險庫/缺口/금고 in version-8 store notes. 还原 gone from zh-CN notes.
- **I2**: ja/ko no longer say too many notifications; they say one notification held more messages than could be read.
- Also taken: en "records the losses it used to hide"; zh 刪除所有資料/删除所有数据; ja/ko restore no longer promise they will not say Done.

## Still required
python3 `len()` ≤ 500; changelog == whatsnew == JSON text; no markdown; no over-claim.

## Out of scope
#17, F1–F4, #33, Play upload, product code.

## Output
繁體中文. Verdict first: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT.
Critical / Important / Minor with file:line. REQUEST CHANGES still blocks the tag.
