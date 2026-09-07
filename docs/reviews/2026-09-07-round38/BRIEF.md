# Round 38 — mini re-review brief

READ-ONLY review. Do not change product code. Do not activate any orchestration
workflow mode. Write your report in 繁體中文 to the path you were given.

## Subject

`21ccbc5..50bfc10` on `main` of `/Users/iml1s/Documents/mine/quietinbox` — two commits
closing round 37:

- `392e80c` the fix (12 files, +591/−85)
- `50bfc10` the README row for round 37

```
git log --oneline 21ccbc5..50bfc10
git diff 21ccbc5..50bfc10 --stat
git show 392e80c
```

## What round 37 found and what this commit did about it

All three of you returned REQUEST CHANGES, and all three said the three-valued
`lossRecorded` model is the right shape and that arming the retry by proof rather than by
a timer is the right choice. The Critical you reached independently (Codex C1, agy C1,
subagent C1) was a **contract seam**: `claimEventLoss` returned `false` both for "the gap is
already on disk" and, since round 36, for "the row was deferred and there is no gap", and
`recordCarriedOverLoss` ignored the value. A page is read outside the pipeline lock, so one
pass could defer a row another pass still held, and the second pass committed it.

What changed:

1. **The contract** (`IngestRepository.kt:150-190`, `:545-560`). `claimEventLoss` returns
   `LossClaim` — `RECORDED` / `ALREADY_RECORDED` / `DEFERRED` / `NOT_PENDING` — and only the
   first two have `gapIsDurable`. When the claim update takes nothing, `pendingLossState` is
   read **inside the same `withTransaction`**, so the explanation cannot drift from the update.
   `recordCarriedOverLoss` (`CaptureCoordinator.kt:1279`) returns `gapIsDurable`; the replay
   site returns without committing on anything else; the settle walk (`:1304`) `check`s it and
   aborts the policy transaction.
2. **Coalescing** (`CaptureCoordinator.kt:1149-1168`). `replayGate = Mutex()` plus a
   `@Volatile replayRequested` flag: set the flag, `while (replayRequested && tryLock())`,
   inner `while (replayRequested) { replayRequested = false; replayPass() }`, unlock in
   `finally`, and the outer `while` re-checks the flag after the unlock.
3. **Resume at drain, not head** (`:1183-1200`). When `pendingJournal` returns empty, the pass
   resumes deferred rows **once** (`resumed` flag) and `continue`s if it put any back, else
   `break`s. Codex I1: a head-of-pass resume let 20,000 failing rows re-exhaust the 100-round
   budget on every trigger.
4. **Per-source resume in the walk** (`:1311`). `settleCarriedOverLosses` calls
   `resumeDeferredSettlements(packageName)`; the replay's resume stays global. Both DAO
   statements carry `AND state = 'PENDING'`.
5. **Progress on a deferral** is claimed only when `isReplayCandidate` says the row left the
   candidate set (`:1238`), and `deferredSettlements` arms one retry.
6. **Tests**: five new JVM tests (the stale-batch Critical with a latch between page read and
   claim; a replay that cannot even defer a row claims no progress; a failing prefix longer than
   a whole pass at `replayPageSize = 2`, `replayRounds = 3`, decided by a second trigger; 201
   rows settled through `setSourceEnabled(false)`; two triggers arriving together run one pass),
   and six instrumented ones (`JournalLossTransactionTest.kt:306-470`: an undecodable page in
   the middle of the walk; a page of undecodable rows is empty and still points on; the four-case
   claim; a settled row cannot be walked back to deferred; one source's resume leaves another's
   rows alone; the settle walk seeks to its cursor inside the index — it EXPLAINs the DAO's own
   hoisted `PENDING_FOR_PACKAGE_AFTER` constant and asserts `(receivedAtEpochMs,eventId)>(?,?)`).

## The seams to attack this round

These are the places the fix added; each is a claim to break, not a description to confirm.

1. **The coalescing gate.** `tryLock` + a `@Volatile` Boolean, no atomics. Walk the
   interleavings: A sets flag, A locks, A clears flag, A runs pass, B sets flag, A finishes pass,
   A's inner loop sees flag → runs again — fine. Now: A's inner loop exits (flag false), B sets
   flag, B `tryLock` fails (A still holds), B's outer loop exits, A unlocks, A's outer loop
   re-checks flag → true → A locks again. Is there an ordering where the flag is set, no pass is
   running, and nobody will run one? Is `@Volatile` sufficient on the JVM memory model here, or
   does the unlock/lock of the `Mutex` supply the happens-before that matters? Can a
   `CancellationException` inside `replayPass` leave the flag set with the gate unlocked and no
   caller — and is that acceptable (the next trigger picks it up) or a lost request?
2. **`deferLoss` after the rollback.** `claimEventLoss`'s catch runs `deferLoss` **outside** the
   rolled-back transaction, in a new implicit one. Between the rollback and that update another
   pass can have claimed the row (0 → 1 with a gap). `deferLoss` carries `lossRecorded = 0 AND
   state = 'PENDING'`, so it should take nothing. Verify that on the real SQL; then ask whether
   the *first* pass — whose claim failed — now reports `DEFERRED` to its caller while the row is
   actually settled, and whether anything downstream acts on that stale word.
3. **Reason-read inside the transaction.** `pendingLossState` returns `Int?`; `null` maps to
   `NOT_PENDING`, `1` to `ALREADY_RECORDED`, anything else to `DEFERRED`. A row at
   `lossRecorded = 0` that the claim nonetheless failed to update — is that reachable, and what
   does calling it `DEFERRED` do?
4. **Resume-at-drain's loop bound.** `continue` after a resume that put rows back does not reset
   `progressed` (still true from the previous batch) and `rounds` keeps counting. If the resumed
   cohort immediately re-defers, does the pass terminate? Is one resume per pass the right
   number, or does it leave a device whose vault recovered mid-pass one trigger behind?
5. **Global replay resume vs. per-source walk resume.** The walk resumes only its package inside
   the policy transaction; a concurrent replay pass (coalesced, so only one) resumes globally
   at its drain point, outside any policy transaction. Can the global resume put back a row the
   walk is about to discard, and does the walk's `check` then fire correctly (the source stays
   enabled, nothing lost) or incorrectly (the walk had already settled it)?
6. **The nine negative controls (NC15–NC23).** Each is listed in the commit message with the test
   it reddened. Treat the table as a claim: is any control weaker than it looks — a test that
   reddens for a reason other than the guard it names?
7. **Every new test, adversarially.** For each: what one-line change to product code would make
   it fail? If "none", say so.

## Accepted with reason, not fixed — do not re-report

- Codex M2 (the chip's new wording has no test): the chip test reads the catalogue string, so a
  wording test would be a test of the catalogue file. If you think that reasoning is wrong, say
  what the test should assert that the catalogue does not already pin.
- Dropping `eventId` from the `ORDER BY` alone is inert while the composite index supplies the
  order (verified red/green with the build-gated runner). The clause is the guarantee that
  survives an index change.

## Out of scope — deferred to separate commits, recorded in `docs/SCOPE.md`

Not regressions; please do not report them as new:

- issue #28 — a row whose commit attempts run out is filed `FAILED` with its payload cleared
  and no gap (pre-0.1.4);
- round-35 Codex I1 — a WhatsApp group body cut on a line separator loses a whole row with no gap;
- round-35 Codex I2 — a backup merge skipping a duplicate row drops that row's truncation evidence;
- round-36 subagent I2 — a settle failure makes "stop capturing this app" silently do nothing
  (`HealthViewModel` discards the result with `runCatching {}`).

The residual this commit knowingly leaves: a device with deferred rows, no further lossy event
and no lifecycle trigger waits, payload intact. Round 37 agreed that is better than a timer's
residual; say so again only if you now disagree.

## Gates, as run on this commit

- 268 JVM tests, 0 failures (`./gradlew test :app:assembleDebug`)
- 60 instrumented on `emulator-5556`: storage 51, conversation 5, backup 2, crypto 2
- `./gradlew lint` clean (`abortOnError = true`, no baseline)
- `tools/check-permissions.sh` on the debug APK: no network permission
- `python3 tools/check-strings.py`: 0 errors

If you run instrumented tests yourself: `export ANDROID_HOME=$HOME/Library/Android/sdk`,
`ANDROID_SERIAL=emulator-5556`. Never emulator-5554, never the device R5CX10VFFBA.

## Output format (繁體中文)

- **Verdict**: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- **Critical** (must fix before push), **Important**, **Minor** — each with file:line, why it
  is wrong, and what would make it right
- For every finding: **how you verified it**, and **the limit of that evidence** — say plainly
  when something is a source-trace or a model rather than a run
- One paragraph on whether the four-case contract closes the seam, or merely moves it
