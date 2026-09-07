# Issue #28 — design consult (read-only; answer on stdout, do not write files, do not change code)

Repository: /Users/iml1s/Documents/mine/quietinbox-wt-28 (a git worktree of QuietInbox at
29cfaf0, branch fix/issue-28). Read `docs/SCOPE.md`, `platform/storage/.../db/Daos.kt`,
`platform/storage/.../db/Entities.kt`, `platform/storage/.../repo/IngestRepository.kt`
(`claimEventLoss`, `markJournalRetryable`, `journal`), and
`platform/capture/.../CaptureCoordinator.kt` (`replayPass`, `recordCarriedOverLoss`,
`settleCarriedOverLosses`, the live acceptance path around `markJournalRetryable`).
`gh issue view 28` has the issue text; `docs/reviews/2026-09-07-round3{5,6,7}/` the history.

## The defect

`markJournalRetryable` files a row `FAILED` at the third failed commit attempt; `setState`
clears the payload for any non-PENDING state; nothing writes a gap. An accepted event whose
commit fails three times disappears with no record.

## Agreed part of the fix

Record a bounded loss *inside the transaction that files the row FAILED*: a new
`GapReason.COMMIT_FAILED` ("could not be saved"), bounds `postedAt ?: observedAt → observedAt`,
the event's package. The coordinator hands the gap write in as a lambda from both call sites
(live and replay), the same shape as `lossOnAccept`.

## The open decision: what happens when that gap write itself fails

The transaction rolls back, so the row is still PENDING with its payload. What next?

Constraint that makes this hard: `event_journal.lossRecorded` is currently three-valued —
0 unsettled, 1 settled (the acceptance gap or the carried-over claim is on disk), 2 deferred
(a carried-over settlement the vault refused; out of every pending read until a pass resumes
it, 2→0). Exhausted rows reach `markJournalRetryable` at 1 as often as at 0: a live event
with a loss is inserted at 1, and the replay settles a carried-over row 0→1 *before* it
tries the commit. Reusing today's `deferLoss` (`WHERE lossRecorded = 0`) and
`resumeDeferredLosses` (`SET 0 WHERE 2`) for the exhaustion case therefore either cannot
defer a settled row, or resumes it to 0 — after which `recordCarriedOverLoss` claims it
again and a second gap is written for one loss.

### Option A — no column change; the row simply stays PENDING

The exhaustion transaction rolls back; attempts stay at MAX−1; the row stays in the page.
Each replay pass retries it once (commit fails → mark → gap fails → rollback), bounded per pass
by the progress guard. Residual: N such rows occupy the head of `pending`'s 200-row page and
starve row N+1 — the shape round 36 Codex I1 rejected for carried-over settlements. The
precondition is stronger here, though: a commit that fails deterministically for that row
*and* a gap table that refuses writes. A gap-table failure alone does not reach this path.

### Option B — deferral becomes a bit orthogonal to settlement

`lossRecorded` bit 1 = settled, bit 2 = deferred: values 0, 1, 2, 3. `deferLoss` becomes
`SET lossRecorded = lossRecorded + 2 WHERE lossRecorded < 2 AND state = 'PENDING'`; both
resumes `SET lossRecorded = lossRecorded - 2 WHERE lossRecorded >= 2 AND state = 'PENDING'`;
`pending` / `pendingExcluding` / `isReplayCandidate` filter `lossRecorded < 2`;
`pendingForPackageAfter` keeps `= 0` (the walk wants unsettled rows only); `claimLoss` keeps
`= 0`; `pendingLossState` maps 1 → ALREADY_RECORDED, 2 or 3 → DEFERRED. On an exhaustion-gap
failure the row is deferred with its settled bit intact, leaves the page, and is retried
after the pass drains (a fourth commit attempt; if it succeeds nothing is lost, if it fails
the gap is tried again). Residual: every statement the round-38 reviewers are judging right
now changes again, the index's third column becomes a range for the global reads (they never
used it — they have no packageName), and the model needs one more review.

### Option C — something else you think is better

For instance: file the row FAILED anyway and remember the loss in RAM until the next
successful write (the `journalLossSince` contract, round 34 I3), accepting a process-death
window; or a separate `attempts`-based exclusion.

## What I want from you

1. Which option, and why — in terms of data-loss risk, starvation, and what the round-39
   reviewers will be able to verify.
2. For the option you pick: the exact SQL for each changed statement and the transitions
   that must be pinned on a real vault (`JournalLossTransactionTest`).
3. Any interaction with `claimEventLoss`'s four-case contract or the replay coalescing that
   the option breaks.

Answer in 繁體中文, on stdout only. Do not write any file. Do not change code.
