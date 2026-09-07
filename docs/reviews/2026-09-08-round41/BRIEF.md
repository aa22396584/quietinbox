# Round 41 — review brief (mini re-review)

READ-ONLY review. Do not change product code. Do not activate any orchestration
workflow mode. Write your report in 繁體中文 to the path you were given.

## Subject

`866b2d6..a63bc63` on `main` of `/Users/iml1s/Documents/mine/quietinbox` — the
round-40 REQUEST CHANGES follow-up:

- `5723156` Codex I1: `vaultGapSince` assigned before `openGap`; Ready collector
  settles under `pipelineMutex`; test parks `openGap`, emits Ready, then fails
  the write. I2: `check(usedFiles.none { it in writtenFiles })` inside the
  restore transaction after trim. I3: `vaultFingerprint` hashes every column
  of every touched table, not counts. CI JVM list adds
  `:feature:onboarding:testDebugUnitTest`. TEST_MATRIX / SCOPE counts.
- `a63bc63` the round-40 index row.

Round-40 reports (verbatim, REQUEST CHANGES) are in
`docs/reviews/2026-09-08-round40/`. Attack whether I1/I2/I3 are actually closed.

```
git log --oneline 866b2d6..a63bc63
git diff 866b2d6..a63bc63
```

## What to attack

1. **I1.** Can a Ready still settle between flag and bound? Does the new test
   fail if `pipelineMutex.withLock` is removed from the collector, or if
   `vaultGapSince` is assigned after `openGap` again? Deadlock with
   `replayJournal`?
2. **I2.** Does moving only `writtenFiles.removeAll(usedFiles)` out of the
   transaction fail the new `check`?
3. **I3.** Does `UPDATE message SET body='mutated'` now fail `vaultFingerprint`?
4. Do not re-litigate PARSE_/DECODE, issue #17, or schema 4.

## Output

Verdict first: APPROVE / APPROVE WITH MINOR FIXES / REQUEST CHANGES.
Then Critical / Important / Minor with file:line. 繁體中文.
