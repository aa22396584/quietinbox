# Round 36 — the round-35 fixes

READ-ONLY review. Change no file in this repository except the report path you are
given. Do not activate orchestration or workflow modes.

## Subject

`f7a09ed..HEAD` — two commits answering round 35, whose three reviewers returned
REQUEST CHANGES (Codex, Claude subagent) and APPROVE WITH MINOR FIXES (agy). Read
`docs/reviews/2026-09-07-round35/` for the findings, then `git show` each commit:

- `f410809` — the deferred journal loss no longer waits for a source-policy change;
  two stale test counts.
- `8e3bb3b` — the Critical two reviewers found independently (a settlement failure
  spending the event's commit retry budget), the settle/discard ordering made
  testable, the per-source pending read paged, two `remove` failure controls, the
  PENDING-exit invariant corrected in four places, and a batch of false claims.

## Scope boundary, stated rather than hidden

Two round-35 Importants are **deliberately not in this range** and are not findings
against it: Codex I1 (a WhatsApp body cut on a line separator loses a whole row with
no gap) and Codex I2 (a backup merge drops truncation evidence on a duplicate hit).
Both add a loss-writing path and are getting their own commit. Say so if you find
them; do not treat them as regressions here.

## What to check hardest

1. **Did the fix introduce a new defect?** Every round from 29 to 35 found one that
   the previous round's *fix commit* had created — round 35's Critical was in round
   34's fix, and it was found by two reviewers independently. Assume the same here.
2. **The new claim placement.** `recordCarriedOverLoss(replay)` now runs before the
   try, with `runCatching`, rethrowing `CancellationException`, and returns from the
   lock on failure. Walk every consequence: does the replay loop spin? Can a row be
   starved for ever? Is `progressed` right? What happens when the claim throws on
   one row and later rows in the same batch would have succeeded? Is the
   `CancellationException` rethrow correct inside `runCatching`?
3. **The paged read.** `pendingForPackageAfter` pages by `(receivedAtEpochMs,
   eventId)`. Prove it cannot skip a row or loop for ever — including ties on
   `receivedAtEpochMs`, rows inserted during the walk, and the interaction with the
   claim, which leaves the row PENDING. Is `JournalPage.next` right when the last
   page is exactly `limit` rows?
4. **Is the ordering test really discriminating now?** The harness keeps pending rows
   per package and the discard empties them. Reverse the two lines in
   `setSourceEnabled` yourself and say which tests redden. Then look for the *next*
   test in this file whose assertion is really about a fake — round 34 and round 35
   each found one.
5. **The invariant, restated.** Four places now say a pending row settles before it
   is committed or discarded, with two exits that never settle. Enumerate every
   mutation of `event_journal` in the repository and say whether the statement is
   now complete. Issue #28 is the known hole; anything else is a finding.
6. **Documentation against code.** Every count in both `TEST_MATRIX.md`, both
   `SCOPE.md` and both `ARCHITECTURE.md` moved again. Re-derive them. Round 35 found
   six wrong; "docs ahead of code" has been a finding in every round of this project.
7. **The set-only semantics.** It is now named historical and scoped to the `Known`
   path. Is the wording true of every path that writes `truncationFlags`? Does the
   user-facing label match that model, and if not, is that a finding against the copy
   or against the model?

## Output format (繁體中文)

- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- Critical (must fix before push) — each with file:line and how you verified it
- Important (should fix before push)
- Minor / nitpicks
- Claims you checked and found true, with the evidence and its limits
