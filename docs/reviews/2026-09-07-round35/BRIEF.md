# Round 35 — the round-34 fixes

READ-ONLY review. Change no file in this repository except the report path you are
given. Do not activate orchestration or workflow modes.

## Subject

`022b99a..f7a09ed` — the commit answering round 34, whose three reviewers all returned
REQUEST CHANGES. Read `docs/reviews/2026-09-07-round34/` for the findings first,
then `git show` the range. `022b99a` (already reviewed as part of round 34's own
fix set) closed the shared Critical; the commit under review closes Codex's second
Critical and every Important and Minor it raised.

What it claims to do:

- **C2** — a determinable content loss carried in from 0.1.3 is recorded on upgrade.
  A new `event_journal.lossRecorded` column is the idempotency boundary; the
  `LINES` / `LINES_DROPPED` split is vocabulary only.
- **I1** — the WhatsApp split no longer marks a row the cut left complete.
- **I2** — a repost of identical text applies truncation evidence the stored row
  did not have; set-only, never cleared.
- **I3** — a loss neither the journal nor its fallback gap could write is kept and
  written by the next policy load.
- **M1 / M2** — six false or stale claims in `CHANGELOG.md`, `QuietInboxDatabase`,
  `CaptureHealth`, `Entities`, both `SCOPE.md` and both `TEST_MATRIX.md`.

## What to check hardest

1. **Did the fix introduce a new defect?** Rounds 29–34 each found one that the
   previous round's *fix commit* had created — round 34's shared Critical was in
   round 33's fix. Assume the same here and look for it specifically.
2. **The two consumers of the upgrade path, named.** `recordCarriedOverLoss(replay)`
   runs in `replayJournal` before `processJournaled`; `settleCarriedOverLosses`
   runs inside `SourceRepository.setEnabled`'s and `remove`'s transactions. The
   commit claims those are the only two ways a row leaves `PENDING`. Test that:
   `deleteExpired` excludes `PENDING`, `deleteAllExpired` appears to have no caller
   — verify both, and look for anything under `maintenance.exclusive {}`, a backup
   import, or a vault reset that could empty or clear a pending row without passing
   through either site. A third exit means a silent loss again.
3. **The predicate.** `carriesUnrecordedLoss` claims two payload shapes are
   decidable. Check it against `git show v0.1.3:platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt`
   yourself — the `LINES` line and the whole of `bound()` — not against this
   description. Is `senderName` truncation relevant? Is the `HISTORIC_MESSAGES`
   arm symmetric with the `MESSAGES` one? Can anything this release writes match
   the predicate, and does the column make that impossible either way?
4. **The boundary.** `claimLoss` is a conditional UPDATE with `lossRecorded = 0 AND
   state = 'PENDING'`, and `claimEventLoss` wraps it with the gap write in one
   transaction. Is that exactly-once under a replay that keeps failing, a
   concurrent disable, a process death between the claim and the commit? Are the
   nested `withTransaction` calls (claim inside a source-policy transaction) safe
   in Room, and is the outer rollback total?
5. **Schema 4, amended in place for the second time.** 0.1.3 shipped schema 3 and
   no tag contains `9e379d3`. Argue for or against amending rather than adding a
   fifth version, and say what breaks on a device or emulator that already ran the
   earlier 3→4.
6. **I2's rule is monotone: set, never cleared.** Find a case where that is the
   wrong answer — an edit, a revision, a restore, a backup import — and say what
   the user would see.
7. **I3.** The settle runs from `loadSourcePolicy`. Is it reachable if the policy
   load itself keeps failing, or if the vault never reopens? Is one interval for a
   whole outage the right shape, and is the `VaultUnavailableException` path really
   disjoint from the generic one?
8. **Documentation against code.** Every count in both `TEST_MATRIX.md` files, both
   `SCOPE.md` files and both `ARCHITECTURE.md` files was touched. Re-derive them.
   Reviewers have flagged "docs ahead of code" in every round of this project.

## Output format (繁體中文)

- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- Critical (must fix before push) — itemized, each with file:line and how you
  verified it
- Important (should fix before push)
- Minor / nitpicks
- Claims you checked and found true, with the evidence and its limits
