> 繁體中文：[docs/zh-Hant/COMPATIBILITY.md](zh-Hant/COMPATIBILITY.md)

# Source compatibility matrix

Status values (plan §14): `UNTESTED / SYNTHETIC_ONLY / REAL_DEVICE_PASSED / PARTIAL / REGRESSED / BLOCKED`.
A new source-app version never inherits an older row's status.

| Source | Package | Adapter | Adapter version | Status | QuietInbox commit | Source versionCode | OS / device | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| LINE | `jp.naver.line.android` | `line` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `parsers/apps/src/test/.../LineParserTest.kt` |
| WhatsApp | `com.whatsapp` | `whatsapp` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `WhatsAppParserTest.kt` |
| Telegram | `org.telegram.messenger` | `telegram` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `TelegramParserTest.kt` |
| Instagram | `com.instagram.android` | `instagram` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `InstagramParserTest.kt` |
| Messenger | `com.facebook.orca` | `messenger` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `MessengerParserTest.kt` |
| Any other app | — | `standard` | 1.0.0 | SYNTHETIC_ONLY | HEAD | — | — | `core/parser/src/test/.../StandardParserTest.kt` |
| QuietInbox synthetic publisher | `dev.quietinbox.app.debug` | `standard` | 1.0.0 | REAL_DEVICE_PASSED | afa7818 | 1 | Android 16 / Samsung SM-S9280 | Onboarding step 4 captured 3/3 messages (2026-09-06) |

The table above remains the adapter-level synthetic coverage summary. Its historical synthetic-
publisher observation predates the real-source protocols and is not evidence for any source app.

## Real-source evidence records (Issue #22, RS-01)

Record one row for each exact **source app version × scenario** combination. Never copy a result
forward to another app version, scenario, device, OS or language. `Expected outcome` comes from the
protocol in `TEST_MATRIX.md`; `Observed outcome` records only what happened. Use `PARTIAL`,
`REGRESSED` or `BLOCKED` at scenario level rather than promoting an entire source from a subset.
A failed or unknown read-state check cannot be `REAL_DEVICE_PASSED`, but it is still valid evidence
for `REGRESSED`, `PARTIAL` or `BLOCKED`. No real-source protocol has been run yet, so this template
deliberately contains no result rows.

| Source | Package | Source versionCode | Adapter / version | QuietInbox commit | Android / OEM / device | System language | Scenario | Expected outcome | Observed outcome | Read-state verification | Status | Result / evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
<!-- Add a row only after running one defined protocol with synthetic test-account content. -->

No adapter emits `sourceMessageId` or `SOURCE_CHAT_ID` evidence at `VERIFIED` confidence, because no
fixture from a real device exists yet. Each real-source scenario row can be `REAL_DEVICE_PASSED`
only after its named protocol succeeds and its read-state check is `PASS`. An adapter/source-version
cohort can be summarised as passed only after T001 / T004 / T016 / T017 / T045 all pass under the
same full configuration key described below. Use two consenting test accounts and synthetic markers
in the message bodies; real private messages never enter the repository.

A chat that is open on screen may post no notification at all: several apps suppress the shade
while the conversation is in front, and QuietInbox cannot tell that case from a muted chat. The
matrix does not verify it; which apps do this, and on which versions, is real-device evidence for
issue #17.

Compile/target baseline: compileSdk 37 (required by the AndroidX versions used), targetSdk 36
(plan §4 baseline). An API 37 target lane is tracked but not yet exercised.

## Work profiles, Device Policy and low-RAM devices (QI-ID-008)

- Android delivers a work-profile app's notifications to a listener installed in the **personal**
  profile (the copy is tagged with the profile: `profileKey` is `user:<id>`, and the inbox shows a
  "Work profile" tag on such conversations). A listener installed *inside* a work profile is
  ignored by the system, so QuietInbox must be installed in the personal profile.
- A Device Policy Controller (MDM) can block notifications from work-profile apps from reaching
  personal-profile listeners. QuietInbox cannot detect this; the source simply never appears.
  The capture health page shows the listener as connected while nothing arrives — which is why the
  page always states when it last accepted an event and when it last saved a copy, and why the
  diagnostics summary carries both.
- Sources are configured per package, not per profile: enabling LINE captures both the personal
  and the work LINE. Per-profile source control and a non-null account key in the conversation
  identity are planned schema work (see `docs/SCOPE.md`, "Not done").
- Android Q and older on low-RAM ("Go") devices do not bind notification listeners at all
  (`ActivityManager.isLowRamDevice`); QuietInbox cannot capture there.

## Hidden previews: Android's own setting and the app's (QI-ID-009)

A notification whose text is a placeholder is recorded as `PREVIEW_RESTRICTED_SUSPECTED`. Two very
different causes produce it and **QuietInbox cannot tell them apart**:

- the source app's own "hide message content in notifications" setting, and
- Android's platform redaction, including the sensitive-notification hiding added in Android 15,
  which replaces the text before any listener sees it.

Distinguishing them would need `RECEIVE_SENSITIVE_NOTIFICATIONS`, a restricted permission this app
deliberately does not request: it would widen what QuietInbox can read for a labelling nicety. So the
label stays honest about its own uncertainty, and the advice the app gives names both places to look
rather than claiming to know which one applies.

## Submitting an anonymised fixture (QI-PARSER-017)

Only fixtures with synthetic content are accepted; a real conversation never enters the
repository. To add a scenario evidence record:

1. Use two test accounts you own. Send messages whose bodies are test markers only
   (`T001 alpha`, `T004 sticker`, …), one per scenario of `TEST_MATRIX.md` (T001 / T004 / T016 /
   T017 / T045).
2. On a debug build, Capture → Copy summary gives the body-free diagnostic summary; the parser
   warnings and the notification template are what matter.
3. Record: source name and package, source app versionCode, adapter/version, QuietInbox commit,
   Android version, OEM/device, system language, scenario id, expected and observed outcomes,
   notification settings (preview on/off), the shape (MessagingStyle / BigText / Inbox / summary)
   and extras **key names** (never values that could carry text).
4. Record the read-state check as `PASS`, `FAIL` or `UNKNOWN`; failures and unknowns remain useful
   regression evidence but cannot produce `REAL_DEVICE_PASSED`.
5. Open a "Source compatibility report" issue (`.github/ISSUE_TEMPLATE/compatibility_report.yml`).
   A maintainer turns it into a Kotest fixture under `parsers/apps/src/test/` with the same synthetic
   text and adds the scenario record with its result and evidence. Promotion requires all five
   scenarios to pass under the same exact QuietInbox commit, adapter/version, source versionCode,
   Android/OEM/device and system language; it is evidence for that configuration, not a global
   source status.
