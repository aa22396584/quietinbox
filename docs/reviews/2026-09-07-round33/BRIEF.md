# Round 33 — schema 4 (migration 3→4, truncation flags, source-scoped gaps)

READ-ONLY review. Do not modify any file under this repository except the report
path you are given. Do not activate orchestration or workflow modes.

## Subject

Commit `9e379d3` — "Schema 4: a gap can name its source, and a message can admit
what was cut". 26 files, +1521/-28 (1142 of the insertions are the exported
`schemas/4.json`). It is the most structural change in the unreleased 0.1.4 and
it has had no independent review. Everything before it (rounds 29–32) is reviewed
and archived under `docs/reviews/`.

Read it with `git show 9e379d3` in the repository root. `git log` for context.

## What the commit claims to do

1. Splits `TruncationFlag.MESSAGES` into `MESSAGES` (a kept message whose text was
   shortened) and `MESSAGES_DROPPED` (whole messages discarded because the batch
   exceeded `Limits.MAX_MESSAGES`), and the same for `HISTORIC_MESSAGES`. The
   claim is that one flag was raised by two different losses, so keying a gap on
   it would have manufactured gaps that never happened.
2. Records a gap when messages are dropped, **before** the parse result can
   short-circuit it.
3. Persists per-message text truncation and shows it on the bubble.
4. Opens a gap naming the source when a source is disabled or paused, closes it on
   re-enable or resume, scoped so that ending one app's pause cannot end another's.
5. Adds `gap_interval.packageName` and `message.truncationFlags` in `MIGRATION_3_4`,
   exports `schemas/4.json`, and appends `truncationFlags` to the backup record.
6. Argues `conversationId` on a gap is a column that can never be filled.

## The project's hard rules, for judging whether this commit honours them

- **Gaps are shown, never hidden.** Any path that drops a captured notification
  records a gap; a loss the locked vault cannot record yet is remembered and
  written once the vault opens.
- **No destructive Room migrations.** Schema JSON under `platform/storage/schemas/`
  is versioned; a migration must preserve every existing row.
- Single-writer pipeline: everything that commits goes through `pipelineMutex`,
  owned by `VaultMaintenance` (ADR-0007). Source policy changes go through
  `CaptureCoordinator`, never straight to `SourceRepository`.
- Backup is a Tink streaming AEAD container; a record format change has to stay
  readable for archives written by the previous version.
- Docs must not run ahead of the code (`docs/SCOPE.md`, `CHANGELOG.md`,
  `docs/TEST_MATRIX.md` and its zh-Hant twin).
- Every user-facing string exists in all five catalogues with the same names,
  placeholders and plurals.

## Where to look hardest

1. **`MIGRATION_3_4` against `schemas/3.json` and `schemas/4.json`.** Do the added
   columns match the entity declarations exactly — type, nullability, default?
   Would `validateMigration` pass? Is any existing row lost or rewritten? What
   happens to a database that was created at version 4 by `createAllTables`
   rather than migrated — do the two paths converge?
2. **Backup compatibility both ways.** `truncationFlags` was appended to
   `BackupRecord.Message`. Can 0.1.3 read a 0.1.4 archive? Can 0.1.4 read a 0.1.3
   archive? What does the decoder do with the missing field — and is that the
   behaviour the code claims?
3. **The gap placed before the parse.** Is it actually unreachable-proof? Trace
   every early return in `CaptureCoordinator` between the snapshot arriving and
   the commit, and say whether a dropped-message loss can still slip through any
   of them unrecorded. The commit message says a test caught one such case; look
   for a second.
4. **Source-scoped gap open/close.** Enable, pause, resume, disable, remove, in
   every order, plus a process death between open and close, plus two sources
   pausing and resuming interleaved. Can a gap be left open forever? Opened twice?
   Closed by the wrong source? What happens on the first run after upgrade, when
   old rows have `packageName = NULL`?
5. **The `conversationId` argument.** The commit asserts no gap site can ever know
   a conversation. Verify that claim against all seven recording sites rather than
   accepting it.
6. **The flag split.** `TruncationFlag` is claimed to be neither persisted nor
   exhaustively switched — but this same commit starts persisting it in
   `message.truncationFlags` and in the backup. Check whether that claim is still
   true after the commit, and what happens when a future version reads a flag name
   it does not know.
7. Coroutine and lock discipline, string-catalogue parity, and whether the
   CHANGELOG and both TEST_MATRIX files match what the tests actually assert.

## Output

Traditional Chinese. Structure:

- **Verdict**: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- **Critical** — must fix before 0.1.4 ships. Each with file:line, the concrete
  input or sequence that triggers it, and the observable wrong behaviour.
- **Important** — should fix before shipping.
- **Minor** — nitpicks.
- **Claims checked and found true** — say which of the commit message's assertions
  you verified, and how. This section is as valuable as the findings.

Report only what you can point at in the code. If you cannot verify something,
say so plainly rather than guessing.
