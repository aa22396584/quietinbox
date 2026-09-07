# Round 40 — review brief

READ-ONLY review. Do not change product code. Do not activate any orchestration
workflow mode. Write your report in 繁體中文 to the path you were given.

## Subject

`97e631d..866b2d6` on `main` of `/Users/iml1s/Documents/mine/quietinbox` — three
commits after the round-39 reports were archived:

- `32abcfc` audit-2 batches 1–2: restore cancellation vs commit, restore refusal
  (wrong key / tamper / truncate / cut header / not-a-backup / low space), media
  deletion race, BackupService honesty, store-copy and COMPATIBILITY honesty.
- `37da486` round 39 agy M1: open the lock-out gap before publishing the lock.
- `866b2d6` round 39 Codex I1/M1/M2: policy-change failures classified at the
  write-vs-reload seam (`PolicyChangeException`), five catalogues, Health
  ViewModel + dialog tests, CI JVM `:feature:health:testDebugUnitTest`,
  four-value journal test also calls `pendingJournal` with a non-empty
  exclusion list.

```
git log --oneline 97e631d..866b2d6
git diff 97e631d..866b2d6 --stat
git show <sha>
```

## What to attack

1. **Policy-failure honesty (Codex I1).** `CaptureCoordinator.changeSourcePolicy`
   writes then reloads. Does the UI still promise "nothing was changed" for a
   post-commit reload failure, a locked vault, or an unclassified throw?
   Drive `HealthViewModel` and `PolicyFailureDialog` (resource strings, not
   hardcoded English). A generic `IllegalStateException` from the coordinator
   must not use the rollback copy.
2. **CI JVM list (Codex M1).** `.github/workflows/ci.yml` jvm-tests job must
   name `:feature:health:testDebugUnitTest`. `:app:testDebugUnitTest` alone
   does not run it.
3. **pendingExcluding (Codex M2).** `JournalLossTransactionTest.theCandidateReadsAgreeOnAllFourValues`
   must call the pending read with a non-empty exclusion list: 0/1 of included
   sources are candidates, 2/3 are not, and every row of an excluded source is
   absent even if it would otherwise be 0 or 1.
4. **Restore interruption.** After `withTransaction` returns, a cancellation
   must keep linked files. Inside the transaction, it must roll rows back and
   delete files. A blob that cannot be decoded (including Android Base64 of
   invalid characters yielding empty bytes) must not become a LOCAL_COPY.
5. **Media deletion race.** `MediaCopier.store` must not leave a blob/file when
   `setMedia` updates 0 rows. The instrumented tests must return `Unit`.
6. **Lock-out gap order (agy M1).** Publishing lock status before recording the
   gap lets a test (or a collector) miss it. Confirm the gap is opened first.
7. **Every new test, adversarially.** What one-line product change would make
   each fail? Docs (`CHANGELOG`, both `TEST_MATRIX` files) must not claim a
   count or behaviour the tree does not have.

## Stated, please do not re-report

- PARSE_/DECODE leaving PENDING with no gap is out of this range; record it as
  a follow-up if you see it, do not treat it as a push blocker for these three
  commits.
- Issue #17 real-source fixtures stay open by product rule.
- Schema 4 is unreleased and still amended in place.

## Output

Verdict first: APPROVE / APPROVE WITH MINOR FIXES / REQUEST CHANGES.
Then Critical / Important / Minor with file:line. 繁體中文.
