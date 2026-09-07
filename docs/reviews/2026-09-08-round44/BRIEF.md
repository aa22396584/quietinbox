# Review round 44 (release readiness of 0.1.4) — QuietInbox

READ-ONLY review. No product-code edits, no state-changing git, no instrumented tests, no devices, no Play Console. Do not activate orchestration workflow modes.

Repository: `/Users/iml1s/Documents/mine/quietinbox`, branch `main`, HEAD `8406bdc` (local, one commit ahead of origin). Review `git diff c2db4a8..8406bdc` (the version bump) **and** the release as a whole: everything on main since tag `v0.1.3` (`git log v0.1.3..8406bdc`). Product code through round 43 is already reviewed (`docs/reviews/2026-09-08-round43/`); this round is whether the **tag** may be cut.

Write the complete report in 繁體中文 to the path named in your launcher prompt, and print it to stdout.

## What ships
- `versionCode` 8 / `versionName` 0.1.4 (`app/build.gradle.kts`).
- Store notes for versionCode 8: `fastlane/metadata/android/{en-US,zh-TW,zh-CN,ja-JP,ko-KR}/changelogs/8.txt`, the same texts in `fastlane/whatsnew/whatsnew-<locale>` and `fastlane/release-notes.json`. Google Play hard limit 500 characters each (`python3` `len()`, not `wc -c`).
- `CHANGELOG.md`: `[Unreleased]` is empty; body folded into `## [0.1.4] — 2026-09-08`. Lead names issue #28 (closed) and issue #33 (PARSE_/DECODE, **not** this release).
- Play still serves **0.1.3**. This tag is GitHub-only until a deliberate Play step. Do not demand README/SCOPE Play lines to say 0.1.4.

## Evidence already collected (verify the files, do not repeat on devices)
- GitHub CI run 34153773217 on `c2db4a8`: success (JVM, Assemble + permission gate, Instrumented API 29, Instrumented API 35). https://github.com/ImL1s/quietinbox/actions/runs/34153773217
- Local on `8406bdc`: `./gradlew test :app:assembleRelease :app:assembleDebug` BUILD SUCCESSFUL; `tools/check-permissions.sh` OK (no INTERNET); `tools/check-version.sh … 0.1.4` OK; aapt2 `versionCode='8' versionName='0.1.4'`.
- Device walkthrough on emulator-5556 (QuietInbox_Phone) only: release APK FLAG_SECURE (screencap all-black); onboarding 5/5 with "3 of 3 messages captured and saved."; inbox "3 recognisable messages saved."; Capture "admitted" + "last copy saved"; recovery-key copy names Delete everything; Activity Quiet rate + preview-hidden count. Debug demo seed: 128 recognisable messages; search "the" → "41 results"; search hit opened at that message; 720dp rail + single pane still shows Back. Policy-failure dialog and backup SAF not injected. `mobile_list_crashes` empty.

## Out of scope (do not re-litigate, do not ask to implement)
- Issue #17 (real-source fixtures).
- F1–F4 (issues #29–#32).
- Issue #33 PARSE_/DECODE leaving PENDING with no gap — issue only, not this release.
- Google Play upload.

## Review dimensions
1. **Store notes (five languages)**: ≤ 500 chars; same claims; no over-claim (no reply, mark-read, all messages, content the source did not post); terminology matches the catalogues (zh-Hant 保險庫/來源/擷取 — check the actual strings, do not invent); Play-ready (no markdown).
2. **CHANGELOG fold**: nothing lost or duplicated; lead is true; issue #33 is named as not this release.
3. **Docs not ahead of code**: SCOPE/README still saying Play serves 0.1.3 is correct until Play is updated. Anything that now claims the GitHub tag exists before it does?
4. **Release mechanics**: `.github/workflows/release.yml` + `docs/RELEASE.md`. Tag `v0.1.4` → signed APK/AAB, permission gate, GitHub release notes from `awk` on `## [0.1.4]`. Would extraction work? Any reason the workflow would fail on this commit?
5. Anything that should **stop the tag**. Play is a later, deliberate step.

## Output format
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- Critical / Important / Minor / observations, with file:line.
Combined roster rule: strictest verdict wins. REQUEST CHANGES blocks the tag.
