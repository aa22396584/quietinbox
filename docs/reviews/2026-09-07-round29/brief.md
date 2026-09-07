# Round 29 — audit-2 fixes, part 1 (`b30761d..edd261f`)

Repo: `/Users/iml1s/Documents/mine/quietinbox`, branch `main`. READ-ONLY review; do not change code.

DO NOT activate any orchestration workflow mode. This is a plain code review.

## What this is

The second GPT-5.5 Pro re-review produced issues #22–#27 (label `audit-2`), 80 items. Each item was
first **re-verified against HEAD by a separate agent** before any work started, because the issues
were written against a pre-0.1.3 tree. Those re-verification reports are in
`.omc/research/audit2/reverify-2{2,3,4,5,6,7}.md` and are part of the evidence: several of the
issues' own claims were wrong, and the fixes deliberately deviate from what the issue asked.

This round covers the first six commits. Roughly: the media pipeline, the adaptive-layout
breakpoint, accessibility, the capture-health page, onboarding's capture test, and search paging.

## Read these first

- `git log b30761d..edd261f` and `git diff b30761d..edd261f`
- `CHANGELOG.md` `[Unreleased]`
- `CLAUDE.md` (hard rules), `docs/reviews/README.md` (how verdicts are recorded)

## Deviations from the issues — challenge these specifically

Each of these is a place where the fix does NOT do what the issue asked. Say whether the reasoning
holds, and if not, what the right fix is.

1. **H4** asked to surface "last system callback". The timestamp is stamped inside `enqueue`, after
   the source filter, so a callback for a disabled source never reaches it. It was therefore
   relabelled "last accepted event" rather than surfaced under the requested name. Is that right, or
   should the app record a genuine callback timestamp instead?
2. **`inbox-incomplete-capture-marker` (a)** asked for a scope column on `gap_interval` carrying a
   conversation. The re-verification found no `recordGap` site can ever know a conversation. That
   schema work is deferred to a later commit; nothing in this range touches the schema. Confirm the
   range really is schema-free.
3. **FT-02**: the issue offered two fixes (move `wide` to 840dp, or use the medium-width two-pane
   directive). Neither was taken. Instead the single `wide` value was split into `railLayout`
   (600dp) and `twoPane` (840dp). Verify this is correct against `MainNavigation.kt` and that every
   downstream use went to the right one, including `tools/demo-screenshots.sh`.
4. **O8** asked onboarding to "end in a verified success". Next is deliberately NOT gated on a
   successful capture, because a device-policy block would trap the user. Failure is shown instead.
   Is that the right call?
5. **A11Y-04** asked for copy / share / delete accessibility actions. Share was deliberately left
   out as scope creep (it does not exist as a feature anywhere). Agree or disagree.
6. **MED-11** suggested writing `URI_EXPIRED` for stale PENDING rows with a media URI. `FAILED` is
   written for all of them instead, on the grounds that nothing observed why. Too cautious?

## Review dimensions

1. **Correctness of the media fix.** `MediaCopier` + `MediaRead.kt`: does the deferred-plus-timeout
   actually free the caller when a provider never returns? Does anything still hold a
   `VaultMaintenance` worker slot? Is the orphaned thread bounded? Is `CancellationException` ever
   swallowed? Does `readScope` leak (it is a `@Singleton`, never cancelled — is that acceptable)?
2. **The retention sweep.** `RetentionService.sweep` now deletes files no blob row points at.
   `maintenance.work` permits concurrency with `MediaCopier`. Is the directory-then-database read
   order plus the one-hour grace window sufficient to make deleting a live blob impossible? What
   about `.tmp` files from `BlobCipher`?
3. **The media-copy bound.** `CaptureCoordinator` caps in-flight copies at 32 and records
   `MEDIA_QUEUE_OVERFLOW`. Those rows stay PENDING until the sweep settles them — is that consistent
   with "gaps are shown, never hidden"?
4. **Honest labels.** Every string added or reworded: does it claim more than the code can observe?
   Particularly `health_last_event`, `health_last_saved`, `health_gaps_caveat`,
   `search_results_shown`, `conv_open_source_fallback`, `conv_media_vault_full`, and the zh-Hant
   `analytics_tab_quiet` rename.
5. **Compose correctness.** The rewritten `LaunchedEffect` in `ConversationScreen`: does the
   `rememberSaveable` "landed" flag behave across configuration change and process death? Does the
   at-end check work when the list is shorter than the viewport? Is the semantics work
   (`mergeDescendants`, `customActions`, `stateDescription`) correct, and does merging break the
   `SelectionContainer`?
6. **Search paging.** The generation guard in `loadMore`, the cursor-versus-hits distinction, and
   whether the header can ever state a number that is not true.
7. **Tests.** `MediaReadTest` (10), `OnboardingViewModelTest` (5), `SearchViewModelTest` (4 now),
   `MonogramTest` (+2). Do they actually fail against the old behaviour, or do they only assert the
   new code's shape? Name any that would pass on the broken code.
8. **Docs never ahead of code.** `docs/SCOPE.md`, `docs/TEST_MATRIX.md`, `docs/COMPATIBILITY.md`,
   `docs/ARCHITECTURE.md`, `docs/adr/0005-backup-container.md` and their zh-Hant twins. Every claim
   must be true today. The SCOPE adaptive row claims verification at 720dp — that run happened
   (`tools/demo-screenshots.sh` on the phone AVD resized with `wm size`, output in the commit
   message for `a290798`).
9. **Bilingual parity.** Every doc that exists in both languages must have changed in both.
10. **Anything missed.**

## Output format (繁體中文)

- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- Critical (must fix before push) — itemised, with `file:line`
- Important (should fix before push)
- Minor / nitpicks
- Observations
