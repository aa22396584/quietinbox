# Round 30 — mini re-review of the round-29 fixes (`edd261f..b373146`)

Repo: `/Users/iml1s/Documents/mine/quietinbox`, branch `main`. READ-ONLY; do not change code.
DO NOT activate any orchestration workflow mode.

Round 29 returned a combined **REQUEST CHANGES** (1 Critical, 9 Important across two reviewers;
Kimi blocked). `b373146` is the single fix commit. Both round-29 reports are in
`docs/reviews/2026-09-07-round29/` — read them, then check each finding is genuinely closed and that
the fixes introduced nothing new.

## Verify each, by reading the code and not the commit message

- **C1** `SearchViewModel`: the cursor from `SearchRepository` must now pass through untouched, and
  the header may say a total only when it is null. Check `run()` and `loadMore()`, and that the
  rewritten test asserts the *correct* behaviour rather than the old one. Confirm both TEST_MATRIX
  files and the CHANGELOG now describe what the code does.
- **I1** the sender's name moved inside the clickable column so it is a plain child of the merging
  node. Confirm structurally. Note the claim in the commit message that `uiautomator dump` cannot
  verify a merge on this app — say whether you agree, and whether there is a cheaper verification
  than adding Compose UI test infrastructure.
- **I2** `lastCommittedAtEpochMs`: wall clock, monotonic, and only when rows were written. Check the
  guard matches every path in `IngestRepository.commit` that writes nothing.
- **I3** `settlePendingMedia` is a compare-and-set. Confirm the sweep uses it and that the returned
  count is what `RetentionReport` now reports.
- **I4** the overflow branch settles the rows under the pipeline lock. Confirm that write is legal
  where it stands and cannot deadlock.
- **I5** the scroll effect no longer assumes "at the bottom" when unmeasured.
- **I6** the clip is marked sensitive on 33+, and Copy is not offered on an empty body.
- **I7** filtered empty states.
- **I8** the COMPATIBILITY section placement in both languages.
- **agy 3** `showBackButton` now depends on the entry beneath the conversation being the inbox.
  Check every route that can open a conversation.
- **M1/M2/M3/M5/M10/M11** and agy's minors 1 and 2.

## Also

- Anything the fixes broke. Pay attention to the generation counter in `SearchViewModel` (is it
  bumped everywhere it must be, and never compared against the wrong snapshot?) and to the new
  `buildList` of accessibility actions.
- Docs never ahead of code, both languages, and the review index rows for round 29.

## Output (繁體中文)

Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT, then Critical / Important /
Minor / Observations, each with `file:line`.
