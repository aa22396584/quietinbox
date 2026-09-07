# Round 37 — review brief

READ-ONLY review. Do not change product code. Do not activate any orchestration
workflow mode. Write your report in 繁體中文 to the path you were given.

## Subject

`44ce484..4797b13` on `main` of `/Users/iml1s/Documents/mine/quietinbox` — three commits
closing round 36:

- `f3d4407` the fix
- `86c4401` a one-line docs correction
- `4797b13` the fairness test Codex I1 named, plus negative controls for the two
  `SourceRepository.remove` tests, plus doc counts

```
git log --oneline 44ce484..4797b13
git diff 44ce484..4797b13 --stat
git show f3d4407
```

## What this round is actually asking

Rounds 34, 35 and 36 each found a defect **in the previous round's fix**. That is the
signal this round exists to judge. Round 36's three reviewers were not reporting three
separate mistakes; they were circling one missing idea — *a settlement the vault refuses
has no state of its own* — and each of the two available readings had already produced a
defect:

- charge the failure to the event's three commit attempts → three of those file the row
  `FAILED` and clear its payload, losing the survivors and the record together (round 35);
- leave the row merely unsettled → it stays at the head of every replay page, and two
  hundred of them starve row 201 for ever (round 36 Codex I1).

So this commit changes the **model** rather than patching the next symptom:
`event_journal.lossRecorded` holds three values, and a refused settlement is *deferred* —
still `PENDING`, payload and unspent claim untouched, out of the two passes that read
pending rows until one of them puts it back, which both do at the head of every pass.

**Judge whether the model is right, not whether the patch works.** If the model is wrong,
say so plainly and say what the right one is; that is more useful than another list of
symptoms. If it is right, the interesting question is what it costs.

## The one design choice to challenge

Codex I1 asked for「有限、可取消且有退避的恢復重試」— a bounded, cancellable, backed-off
recovery retry. **I did not build a timer.** The retry is armed by *proof* instead: an event
accepted *with* a loss has just written a gap in its acceptance transaction, so the table
that refused is taking writes again, and that arms exactly one replay. An acceptance that
wrote no gap arms nothing.

My reasoning, stated so you can attack it: a timer retries into a vault that is still
refusing everything, and the retry itself is what re-defers the row; a gap-write failure on
local SQLCipher is not time-transient, so time is the wrong signal. The retry is bounded
(one launch per deferral episode, flag cleared before the launch), cancellable (`scope.launch`
inside `maintenance.work {}`), and cannot storm (a broken vault produces no armed retries at
all, because an event carrying a loss cannot be accepted while its gap write fails).

**The residual I am aware of and accepting:** a device with deferred rows, no further lossy
event, and no lifecycle trigger (vault ready, source unpause, maintenance end, manual
recovery) waits. That was true before this commit too — what changed is that row 201 is now
processed and every deferred payload is intact meanwhile. Tell me if you think that residual
is worse than a timer's, and why.

## Please re-run your own round-36 harness against this commit

Codex: your I1 reproduction (200 rows whose gap insert a trigger fails, plus a 201st clean
row, three consecutive replays) and your I2 measurement (page counts and VM instructions at
1,000 / 2,000 / 4,000 / 8,000 rows) were the most useful things in the round. Please point
both at `f3d4407` and report what they now say. The production SQL moved: `pendingForPackageAfter`
is a row-value cursor with a `lossRecorded = 0` filter, `pending` / `pendingExcluding` exclude
`lossRecorded = 2`, and there are three new statements (`deferLoss`, `resumeDeferredLosses`,
`isReplayCandidate`).

Subagent: your seven-mutation table is the right instrument. Please re-run it against the
current `Daos.kt` and `IngestRepository.kt` and say which mutations are now caught, which are
still silent, and — the part I care about most — which *new* mutations the tri-state has made
possible that nothing catches.

## Specific claims to check hardest

1. **The state machine is closed.** 0 → 1 (claim wins, gap written in the same transaction),
   0 → 2 (claim's transaction rolled back, deferral written afterwards), 2 → 0 (a pass resumes),
   and nothing else. Is there a path that reaches 2 and never leaves it? A path that reaches 1
   without a gap? A row at 2 that a discard can destroy before any pass resumes it?
2. **`resumeDeferredLosses` is global, not per-source.** It runs at the head of every replay
   and at the head of every settle walk — including a settle walk inside a source-policy
   transaction, where it will be rolled back with everything else if the settle then fails.
   Is a global reset from inside one source's policy transaction correct? What does it do to
   another source's deferred rows if that transaction aborts?
3. **Progress accounting.** The replay claims `progressed = true` on a deferral *only* when
   `isReplayCandidate` says the row really left the candidate set. Is that honest under
   concurrency — can the row be resumed by another pass between the deferral and the check?
   Is the 100-round bound still a bound?
4. **The index and the row-value cursor.** `(receivedAtEpochMs, eventId) > (:afterTime, :afterId)`
   with `index_event_journal_packageName_state_lossRecorded_receivedAtEpochMs_eventId`. Row values
   need SQLite ≥ 3.15; the app is SQLCipher on minSdk 26. Is that safe on every supported API
   level, or only on the bundled build I measured? Does `lossRecorded = 0` in the middle of the
   index hurt any other query? Is the migration's `CREATE INDEX IF NOT EXISTS` right for a
   development vault that already ran the earlier schema 4?
5. **Retry arming.** `retryDeferredSettlements()` is called from the acceptance path while the
   caller holds `pipelineMutex`, and from `settleUnrecordedJournalLoss`. It launches into `scope`.
   Can that deadlock, leak, or outlive a maintenance run? Can two arm at once?
6. **The tests, adversarially.** Every new test: what one-line change to product code would make
   it fail? If the answer is "none", say so — that is the finding this project has been hit by
   most often (round 34 I1, round 35 subagent C3, round 36 subagent C1, round 36 agy I1).

## Verified claims — please do not spend the round re-deriving these

Each of these I checked this round; challenge them if you disagree, but they are not open questions.

- **`deferLoss` inside a source-policy transaction rolls back with it.** The instrumented test
  "a flag change whose settlement fails leaves the row pending, the claim unspent and the source
  switched on" passes at 47 tests, and "unspent" requires the row at 0, not 2.
- **Dropping `eventId` from the `ORDER BY` alone changes nothing** while the composite index
  supplies that order — I ran it: green. The clause is the guarantee that survives an index
  change, not today's behaviour. The mutation that *does* lose rows is neutralising the cursor's
  tiebreak, and that is red.
- **Two earlier attempts at that control were green because the mutation did not compile** (Room
  rejects an unused `:afterId`) and the runner read stale results. The runner now gates on the build.
- **`deleteAllExpired` and `clear` had no callers anywhere** in the non-build tree; both removed.

## Negative controls already run (each verified red on the named test, then restored)

| # | mutation | test that reddened |
| --- | --- | --- |
| 1 | `removeSource` without `settleCarriedOverLosses` | pending rows removed with their source are settled |
| 2 | a deferral not counted as progress | rows whose loss cannot be written stop holding the page |
| 3 | no retry on a successful gap write | a deferred settlement is retried when a gap write is next seen to succeed |
| 4 | retry on any accepted event | an event accepted without a loss does not retry a deferred settlement |
| 5 | the settle walk without its resume | a deferred row is still settled before its source is disabled |
| 6 | the replay without its resume | a deferred settlement is retried … |
| 7 | the cursor built from decoded snapshots | a page that will not decode still moves the walk on |
| 8 | the replay page still holds deferred rows | 2 tests |
| 9 | a failed settlement is not deferred | 5 tests |
| 10 | the cursor tiebreak neutralised | the settle walk visits every pending row once in order |
| 11 | the cursor includes its own row | 2 tests |
| 12 | `remove`'s callback moved before the transaction | 3 tests |
| 13 | `remove`'s graph deletions moved out of the transaction | a remove that fails part way through the graph rolls all of it back |
| 14 | the truncation chip not drawn | a shortened body is labelled as shortened in a notification |

Please treat this table as a claim to check, not as evidence. If any of these controls is
weaker than it looks, that is a finding.

## Out of scope this round — three deferred losses, recorded in `docs/SCOPE.md`

Not regressions, and please do not report them as new:

- a WhatsApp group body cut on a line separator loses a whole row with no gap (round 35 Codex I1);
- a backup merge skipping a duplicate row drops that row's truncation evidence (round 35 Codex I2);
- a settle failure makes "stop capturing this app" silently do nothing, because the policy
  transaction rolls back and `HealthViewModel` discards the result with `runCatching {}`
  (round 36 subagent I2).

All three add a write path, and every round from 29 to 36 found a defect introduced by a fix
commit, so they go in a commit of their own. Issue #28 (a row whose commit attempts run out is
filed `FAILED` with its payload cleared and no gap) is older than 0.1.4 and also out of scope.

## Gates, as run

- 263 JVM tests, 0 failures (`./gradlew test`)
- 56 instrumented on `emulator-5556`: storage 47, conversation 5, backup 2, crypto 2
- `./gradlew lint` clean (`abortOnError = true`, no baseline)
- `tools/check-permissions.sh` on the debug APK: no network permission
- `python3 tools/check-strings.py`: 0 errors

## Output format (繁體中文)

- **Verdict**: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- **Critical** (must fix before push), **Important**, **Minor** — each with file:line, why it
  is wrong, and what would make it right
- For every finding: **how you verified it**, and **the limit of that evidence** — say plainly
  when something is a source-trace or a model rather than a run
- A short section on the model question above: is a three-valued `lossRecorded` the right shape,
  or is there a better one?
