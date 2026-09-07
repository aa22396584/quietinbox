# Round 39 — review brief

READ-ONLY review. Do not change product code. Do not activate any orchestration
workflow mode. Write your report in 繁體中文 to the path you were given.

## Subject

`29cfaf0..HEAD` on `main` of `/Users/iml1s/Documents/mine/quietinbox` — the five fix commits
that close what rounds 35–38 left open, plus their docs:

- `bb8c2ba` round-35 Codex I1 — a WhatsApp group body cut on a line separator records the missing
  row as a gap with the commit (`ParsedBatch.wholeMessagesLost`, `IngestRepository.commit(lossOnCommit)`
  invoked on both commit exits).
- `468e925` round-35 Codex I2 — a backup merge adds the truncation label an existing row lacks
  (`BackupService.apply`: pre-existing rows kept per key, `markTruncated` on a duplicate hit).
- `868ee77` round-36 subagent I2 — a source change the coordinator refuses is a dialog, not
  silence (`HealthViewModel.policyChange`, `PolicyFailureDialog`, five catalogues; the first
  `feature:health` tests).
- `4b31f28` issue #28 — a row whose commit attempts run out records the loss of the whole event
  (`GapReason.COMMIT_FAILED`) in the transaction that files it FAILED; when that record cannot be
  written the row is parked. **The deferral became a bit**: `lossRecorded` is 0/1/2/3, bit 1
  settled, bit 2 deferred; `deferLoss` adds 2 below 2, both resumes subtract 2 at or above it,
  the replay's candidate reads take `< 2`, `claimLoss` and the settle walk keep `= 0`. The design
  was put to Codex read-only as three options (`docs/reviews/2026-09-07-issue28-consult/`) and it
  chose the bit; its transition table and test list are there.
- `15a5229` round-38 Codex I1 — every replay caller waits for the gate; only the holder clears the
  request flag; a pass that finds maintenance active leaves the request standing. Plus the
  round-38 Minors: two-source resume test, deterministic coalescing control, "at most one retry per
  deferral" test, KDoc, the residual's second path.
- `6e843c9` docs, both languages; `.github/workflows/ci.yml` gains `:feature:health:connectedDebugAndroidTest`.

```
git log --oneline 29cfaf0..HEAD
git diff 29cfaf0..HEAD --stat
git show <sha>
```

## What to attack

1. **The two-bit column.** Enumerate every statement that reads or writes `lossRecorded` (`Daos.kt`)
   and every transition the code can make: 0→1 (claim), 0→2 / 1→3 (park), 2→0 / 3→1 (resume), and
   the terminal states. Is there a path to a value outside 0–3? A resume that gives back the wrong
   settled bit? A read that treats 3 as settled-and-safe (round 37's Critical, one value further)?
   `pendingLossState` maps 1 → `ALREADY_RECORDED` and everything else non-null → `DEFERRED`; a row
   at 0 that the claim nonetheless failed to update — reachable?
2. **`markJournalRetryable`'s transaction** (`IngestRepository.kt`). State check first, attempts,
   the record, `fileFailed` with a `check`, the catch that parks only on the exhausting branch and
   rethrows otherwise, the return of `RETRYABLE` when the park itself fails. Is the state check a
   real duplicate guard, given the gap write precedes `fileFailed`? Can `NOT_PENDING` hide a lost
   record? The live entrance runs this inside `guarded {}` and discards the result — is that right?
3. **The waiting gate** (`CaptureCoordinator.replayJournal`). `withLock` where `tryLock` was. The
   commit claims no caller holds the pipeline lock; verify every call site (five). Can waiters pile
   up unboundedly? Does a waiter cancelled while waiting leave the flag in a bad state? A pass that
   returns `false` restores the flag and returns — can that spin with several waiters during a long
   maintenance run? The commit says the restore line has no discriminating test and why; is the
   reasoning right?
4. **`lossOnCommit` on both exits.** The early exit (no identity or no decisions) and the storing
   exit both invoke it before `setState(COMMITTED)`. Is there a third exit? The `SKIPPED` path
   (`markJournal`) is not transactional — the commit message says the flag is raised only with at
   least two candidates so that path cannot carry it; check `StandardParser.parse`'s guard
   (`messages.size >= 2 && …`) and whether an adapter override could break it.
5. **The backup merge.** `preExisting` is now a deque of rows per key; `removeFirstOrNull` on a hit;
   `markTruncated` only where the column is null. Multiplicity unchanged? A backup row with a flag
   hitting an existing row with a *different* flag (the column is single-valued): what happens, and
   is that right?
6. **The dialog.** `policyChange` rethrows `CancellationException` and records everything else.
   Is a `VaultUnavailableException` (vault locked) correctly reported as a refused change, and is
   the wording honest for that cause? The `settle` flag is set for disable and remove; a disable
   that fails at the *gap open* write rather than the settlement gets the settle wording — is that
   acceptable?
7. **Every new test, adversarially** (JVM `CaptureCoordinatorTest` 63→73, `WhatsAppParserTest`
   8→12, `HealthViewModelTest` 5 new; instrumented `JournalLossTransactionTest` 21→33,
   `BackupRoundTripTest` 2→3, `PolicyFailureDialogTest` 3 new). What one-line change to product code
   would make each fail? The commits list ten negative controls (NC24–NC26 for #28, NC27–NC31 for
   round 38); treat the list as a claim.

## Stated, please do not re-report

- The maintenance-active branch's "restore the flag" line is belt and braces with no
  discriminating test (`15a5229`'s message says why: the run's end always fires after the flag
  drops and always requests a pass).
- The round-37 commit message overstated its stale-batch test; the correction is in `15a5229`.
- `CaptureCoordinatorTest` "a lock-out gap the pipeline could not open is written as a bounded gap
  once the vault opens" (pre-existing) failed **once** in six runs of the suite during this work and
  passed in three consecutive `--rerun-tasks` runs afterwards. Not reproduced, not understood. If
  you can reproduce it, that is a finding, and say whether the waiting gate made it likelier.
- The residual: a device with parked rows (a refused settlement, or a refused record of a row given
  up on) waits for a gap write to succeed or a lifecycle trigger, payload intact; plus the
  last-round resume path (round 38 subagent M4). Both are in SCOPE.

## Gates, as run on `HEAD`

- 285 JVM tests, 0 failures (`./gradlew test :app:assembleDebug`)
- 76 instrumented on `emulator-5556`: storage 63, conversation 5, health 3, backup 3, crypto 2
- `./gradlew lint` clean (`abortOnError = true`, no baseline)
- `tools/check-permissions.sh` on the debug APK: no network permission
- `python3 tools/check-strings.py`: 0 errors
- `CaptureCoordinatorTest` run six times in all during this work (see the one-off above)

If you run instrumented tests yourself: `export ANDROID_HOME=$HOME/Library/Android/sdk`,
`ANDROID_SERIAL=emulator-5556`. Never emulator-5554, never the device R5CX10VFFBA. Start with
`adb -s emulator-5556 shell pm clear dev.quietinbox.app.debug` if you install the app: schema 4 is
amended in place while unreleased.

## Output format (繁體中文)

- **Verdict**: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- **Critical** (must fix before push), **Important**, **Minor** — each with file:line, why it
  is wrong, and what would make it right
- For every finding: **how you verified it**, and **the limit of that evidence** — say plainly
  when something is a source-trace or a model rather than a run
- One paragraph each on: is the two-bit column the right shape, and is the waiting gate the right
  fix for round 38 I1
